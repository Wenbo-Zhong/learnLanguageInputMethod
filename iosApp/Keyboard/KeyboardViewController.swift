import UIKit
import Shared

final class KeyboardViewController: UIInputViewController, UIInputViewAudioFeedback, UIContextMenuInteractionDelegate {
    var enableInputClicksWhenVisible: Bool { prefs.bool(forKey: "sound") }
    private let layout = IOSKeyboardLayout()
    private var source: UILabel { layout.source }
    private var english: UILabel { layout.english }
    private var status: UILabel { layout.status }
    private var preedit: UILabel { layout.preedit }
    private var candidates: UIStackView { layout.candidates }
    private var keys: UIStackView { layout.keys }
    private var phonetics: UIStackView { layout.phonetics }
    private let session = KeyboardSession()
    private let pinyin = PinyinEngine()
    private let engine = LocalEngine()
    private var height: NSLayoutConstraint? { layout.height }
    private var pending: DispatchWorkItem?
    private var revision = 0, candidateRevision = 0
    private var candidateLoading = false
    private var deferred: (() -> Void)?
    private var editActions: [() -> Void] = []
    private var runningEdits = false
    private var lastSource = "", lastHostContext: String?
    private var active = false
    private var committing = false
    private let prefs = KeyboardPreferences.store
    private var root: UIStackView { layout.root }
    private var body: UIStackView { layout.body }
    private var toolbar: UIStackView { layout.toolbar }
    private var translationCard: UIStackView { layout.translationCard }
    private var candidateBar: UIStackView { layout.candidateBar }
    private var expanded: UIStackView { layout.expanded }
    private var panel: UIStackView { layout.panel }
    private var form: UIStackView { layout.form }
    private var candidateScroll: UIScrollView { layout.candidateScroll }
    private var phoneticScroll: UIScrollView { layout.phoneticScroll }
    private var panelScroll: UIScrollView { layout.panelScroll }
    private var expandedScroll: UIScrollView { layout.expandedScroll }
    private var bodyWidth: NSLayoutConstraint? {
        get { layout.bodyWidth }
        set { layout.bodyWidth = newValue }
    }
    private var expandedCandidates = false
    private var tool: String?
    private var englishWord = "", englishChoices: [String] = [], predictions: [String] = []
    private var marked = "", lastCommit = "", undoContext: String?
    private var capsLock = false, lastShift = Date.distantPast, lastSpace = Date.distantPast
    private let extrasQueue = DispatchQueue(label: "dev.learnime.extras", qos: .userInitiated)
    private var dictionary: EnglishDictionary?, converter: TraditionalConverter?
    private let clips = ClipboardHistory(), handwritingEngine = HandwritingEngine()
    private var board: HandwritingView?, handwritingPending: DispatchWorkItem?
    private var handwritingRevision = 0, handwritingWord = ""
    private var editField: Int?, draft = ["", ""], originalWord: [String]?
    private var editingShortcut = false
    private var modeBeforeForm: KeyboardMode?
    private var deleteTimer: Timer?, keyPreview: UILabel?
    private var nineCompositionActions = [UIButton]()
    private var palette: IOSKeyboardSkin {
        IOSKeyboardSkin(dark: prefs.string(forKey: "theme") == "dark" ||
            (prefs.string(forKey: "theme") != "light" && traitCollection.userInterfaceStyle == .dark),
            followsSystem: !["light", "dark"].contains(prefs.string(forKey: "theme") ?? "system"))
    }
    private var sensitive: Bool { [.numberPad, .decimalPad, .phonePad, .asciiCapableNumberPad].contains(textDocumentProxy.keyboardType ?? .default) }
    private var wordInput: Bool { !sensitive && ![.URL, .emailAddress, .webSearch].contains(textDocumentProxy.keyboardType ?? .default) }
    private var keyHeight: CGFloat { compact ? 30 : CGFloat(prefs.integer(forKey: "keyHeight")) }
    private var compact: Bool { view.bounds.width > 600 }

    override func viewDidLoad() {
        super.viewDidLoad()
        KeyboardPreferences.register()
        let saved = UserDefaults.standard.string(forKey: "keyboardMode")
        let mode = [KeyboardMode.chineseQwerty, .chineseNineKey, .english].first { $0.name == saved } ?? .chineseQwerty
        session.changeMode(value: mode)
        if let enabled = UserDefaults.standard.object(forKey: "autoCorrection") as? Bool {
            session.setAutoCorrection(enabled: enabled)
        }
        let expand = button("展开") { [weak self] in self?.expandedCandidates.toggle(); self?.renderCandidates() }
        layout.mount(in: view, expand: expand)
        renderModes(); renderKeys(); renderCandidates()
        pasteConfiguration = UIPasteConfiguration(forAccepting: NSString.self)
    }
    private func clear(_ stack: UIStackView) { stack.arrangedSubviews.forEach { stack.removeArrangedSubview($0); $0.removeFromSuperview() } }
    private func button(_ title: String, action: @escaping () -> Void) -> UIButton {
        let button = palette.button(title, font: CGFloat(prefs.integer(forKey: "keyFont")), border: prefs.bool(forKey: "keyBorder"))
        button.addAction(UIAction { [weak self] _ in
            if self?.prefs.bool(forKey: "sound") == true { UIDevice.current.playInputClick() }
            if self?.prefs.bool(forKey: "vibration") == true { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
            action()
        }, for: .touchUpInside)
        if title.count == 1, title.first?.isLetter == true {
            let hold = UILongPressGestureRecognizer(target: self, action: #selector(letterHeld(_:)))
            hold.minimumPressDuration = Double(prefs.integer(forKey: "longPress")) / 1000
            button.addGestureRecognizer(hold)
        }
        if title == "⌫" {
            let hold = UILongPressGestureRecognizer(target: self, action: #selector(deleteHeld(_:)))
            button.addGestureRecognizer(hold)
        }
        if title.count == 1, prefs.bool(forKey: "preview") {
            button.addAction(UIAction { [weak self, weak button] _ in
                guard let self, let button else { return }
                keyPreview?.removeFromSuperview()
                let label = UILabel(); label.text = title; label.textAlignment = .center
                label.font = .systemFont(ofSize: 28); label.backgroundColor = palette.selected; label.textColor = palette.ink
                label.layer.cornerRadius = 8; label.clipsToBounds = true
                let frame = button.convert(button.bounds, to: view)
                label.frame = CGRect(x: frame.midX - 22, y: max(0, frame.minY - 50), width: 44, height: 48)
                view.addSubview(label); keyPreview = label
            }, for: .touchDown)
            button.addAction(UIAction { [weak self] _ in self?.keyPreview?.removeFromSuperview() }, for: [.touchUpInside,.touchUpOutside,.touchCancel])
        }
        return button
    }
    @objc private func letterHeld(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began, let title = gesture.view?.accessibilityLabel else { return }
        if let i = Array("qwertyuiop").firstIndex(of: title.lowercased().first ?? " ") { literal(String((i + 1) % 10)) }
        else { literal(title.uppercased()) }
    }
    @objc private func deleteHeld(_ gesture: UILongPressGestureRecognizer) {
        if gesture.state == .began {
            backspace(); deleteTimer?.invalidate()
            deleteTimer = Timer.scheduledTimer(withTimeInterval: 0.075, repeats: true) { [weak self] _ in self?.backspace() }
        } else if [.ended,.cancelled,.failed].contains(gesture.state) { deleteTimer?.invalidate(); deleteTimer = nil }
    }
    private func row(_ labels: [String], action: @escaping (String) -> Void) {
        let row = makeRow(labels, height: keyHeight, action: action)
        keys.addArrangedSubview(row)
    }
    private func makeRow(_ labels: [String], height: CGFloat, nineBottom: Bool = false, action: @escaping (String) -> Void) -> UIStackView {
        return layout.row(labels, height: height, weights: nineBottom ? [1, 1, 3.8, 1.35] : nil) { label in
            let b = button(label) { action(label) }
            if ["ABC","DEF","GHI","JKL","MNO","PQRS","TUV","WXYZ"].contains(label) { b.titleLabel?.font = .systemFont(ofSize: 21) }
            if label == "空格" { b.setTitle(tool == "手写" ? "手写" : session.mode == .english ? "English" : session.mode == .chineseNineKey ? "中文 · 九键" : "中文 · 26键", for: .normal) }
            if let image = palette.symbol(label) {
                b.setTitle(nil, for: .normal); b.setImage(image, for: .normal); b.tintColor = palette.ink
            }
            if label == "空格" { b.backgroundColor = palette.key }
            if ["123", "返回", "中/EN", "⇧", "分词", "⌫"].contains(label) { b.backgroundColor = palette.function }
            if label == enterTitle { b.backgroundColor = palette.primary; b.setTitleColor(.white, for: .normal) }
            if nineBottom {
                palette.styleNineKey(b, title: label, role: label == "空格" ? .space : label == enterTitle ? .enter : .utility,
                                     font: CGFloat(prefs.integer(forKey: "keyFont")), border: prefs.bool(forKey: "keyBorder"))
            }
            return b
        }
    }
    private func renderModes() {
        clear(toolbar); toolbar.distribution = .fillEqually
        let layoutTitle = session.mode == .chineseNineKey ? "26键" : "九键"
        for label in [layoutTitle, "剪贴板", "编辑", "符号", "表情", "手写", "设置"] {
            let b = button(label) { [weak self] in
                guard let self else { return }
                if label == layoutTitle { queueEdit { self.changeMode(self.session.mode == .chineseNineKey ? .chineseQwerty : .chineseNineKey) } }
                else { openTool(label) }
            }
            b.titleLabel?.font = .systemFont(ofSize: 13); b.backgroundColor = .clear
            b.setTitleColor(palette.muted, for: .normal); b.isEnabled = !sensitive
            if let image = palette.toolbarSymbol(label) {
                b.setTitle(nil, for: .normal); b.setImage(image, for: .normal); b.tintColor = palette.muted
            }
            toolbar.addArrangedSubview(b)
        }
        // Home-indicator iPhones provide this below the extension. Other devices may require our key.
        if needsInputModeSwitchKey {
            let next = palette.button("切换输入法", font: 14, border: false)
            next.setTitle(nil, for: .normal); next.setImage(UIImage(systemName: "globe"), for: .normal)
            next.tintColor = palette.muted; next.backgroundColor = .clear
            next.addTarget(self, action: #selector(handleInputModeList(from:with:)), for: .allTouchEvents)
            toolbar.addArrangedSubview(next)
        }
        // Keep UIKit's root keyboard background, shared with the system switch/dictation bar.
        translationCard.backgroundColor = palette.card
        translationCard.layer.cornerRadius = palette.cardCorner
        [source, status, layout.direction].forEach { $0.textColor = palette.muted }
        preedit.textColor = palette.primary; english.textColor = palette.ink
    }
    private func changeMode(_ mode: KeyboardMode) {
        finishEnglish(); finishHandwriting(); cancelMarked(); invalidateCandidates()
        session.changeMode(value: mode); prefs.set(mode.name, forKey: "keyboardMode")
        if mode != .english { prefs.set(mode.name, forKey: "chineseMode") }
        tool = nil; panelScroll.isHidden = true; renderKeys(); renderCandidates()
    }
    private func languages() {
        openTool("语言")
        for (name, mode) in [("中文 · 九键", KeyboardMode.chineseNineKey), ("中文 · 26键", .chineseQwerty), ("English", .english)] {
            panel.addArrangedSubview(button(name) { [weak self] in self?.queueEdit { self?.changeMode(mode) } })
        }
        let globe = button("其他输入法") { [weak self] in self?.advanceToNextInputMode() }
        panel.addArrangedSubview(globe)
    }
    private var enterTitle: String {
        switch textDocumentProxy.returnKeyType ?? .default {
        case .done: return "完成"
        case .go: return "前往"
        case .search: return "搜索"
        case .send: return "发送"
        case .next: return "下一项"
        default: return "↵"
        }
    }
    private func renderKeys() {
        clear(keys)
        nineCompositionActions.removeAll()
        renderModes()
        translationCard.isHidden = !prefs.bool(forKey: "translation") || !wordInput || editField != nil
        bodyWidth?.isActive = false
        let hand = prefs.string(forKey: "oneHand") ?? "off"
        root.alignment = .top
        bodyWidth = body.widthAnchor.constraint(equalTo: root.widthAnchor, multiplier: !compact && hand != "off" ? 0.82 : 1)
        bodyWidth?.isActive = true
        // UIStackView uses leading/trailing spacer views for the one-hand placement.
        for v in root.arrangedSubviews where v !== body { root.removeArrangedSubview(v); v.removeFromSuperview() }
        if !compact, hand != "off" {
            let spacer = UIView()
            if hand == "right" { root.insertArrangedSubview(spacer, at: 0) } else { root.addArrangedSubview(spacer) }
        }
        if sensitive {
            for labels in [["1","2","3"],["4","5","6"],["7","8","9"],[".", "0","⌫"]] {
                row(labels) { [weak self] in if $0 == "⌫" { self?.backspace() } else { self?.commit($0) } }
            }
            row(["其他输入法",enterTitle]) { [weak self] in if $0 == "其他输入法" { self?.advanceToNextInputMode() } else { self?.commit("\n") } }
            resize(); return
        }
        if tool == "手写" { renderHandwriting(); resize(); return }
        if tool != nil { keys.isHidden = editField == nil; resize(); return }
        keys.isHidden = false
        if prefs.bool(forKey: "numberRow") {
            keys.addArrangedSubview(makeRow(Array("1234567890").map(String.init), height: 30) { [weak self] in self?.literal($0) })
        }
        if session.symbols {
            let symbolRows = KeyboardKeymap.shared.symbolRows(english: session.mode == .english)
            for letters in symbolRows {
                row(letters.map { String($0) }) { [weak self] in self?.literal($0) }
            }
        } else if session.mode == .chineseNineKey {
            func style(_ b: UIButton, _ title: String, _ role: IOSKeyboardSkin.NineKeyRole, digit: String? = nil) -> UIButton {
                palette.styleNineKey(b, title: title, digit: digit, role: role,
                                     font: CGFloat(prefs.integer(forKey: "keyFont")), border: prefs.bool(forKey: "keyBorder"))
                return b
            }
            let punctuation = ["，","。","？"].map { name in
                style(button(name) { [weak self] in self?.literal(name) }, name, .punctuation)
            }
            let rows = KeyboardKeymap.shared.nineRows().enumerated().map { r, labels in
                labels.enumerated().map { c, title in
                    let digit = KeyboardKeymap.shared.nineDigit(row: Int32(r), column: Int32(c))
                    let b = button(title) { [weak self] in self?.type(digit) }
                    return style(b, title, .letters, digit: digit == "'" ? nil : digit)
                }
            }
            let utilities = ["⌫","清除","原样"].map { name in
                let b = button(name) { [weak self] in
                    guard let self else { return }
                    if name == "⌫" { backspace() }
                    else if name == "清除" { queueEdit { self.cancelMarked(); self.session.clearComposition(); self.requestCandidates() } }
                    else { queueEdit { self.commit(self.session.takeRaw()); self.requestCandidates() } }
                }
                if name != "⌫" {
                    b.isEnabled = !session.composition.isEmpty
                    b.alpha = b.isEnabled ? 1 : 0.45; nineCompositionActions.append(b)
                    b.accessibilityHint = name == "清除" ? "清除当前拼音，重新输入" : "直接输入当前编码"
                }
                return style(b, name, .utility)
            }
            keys.addArrangedSubview(layout.nineGrid(rows: rows, punctuation: punctuation, utilities: utilities,
                                                    keyHeight: compact ? keyHeight + 4 : max(48, keyHeight + 10)))
        } else {
            for letters in KeyboardKeymap.shared.letterRows() {
                row(letters.map { session.mode == .english && session.uppercase ? String($0).uppercased() : String($0) }) { [weak self] in self?.type($0.lowercased()) }
            }
            let letters = "zxcvbnm".map { session.mode == .english && session.uppercase ? String($0).uppercased() : String($0) }
            row([session.mode == .english ? "⇧" : "分词"] + letters + ["⌫"]) { [weak self] key in
                guard let self else { return }
                switch key {
                case "⇧": queueEdit {
                    if Date().timeIntervalSince(self.lastShift) < 0.35 { self.capsLock = true; if !self.session.uppercase { self.session.toggleShift() } }
                    else { self.capsLock = false; self.session.toggleShift() }
                    self.lastShift = Date(); self.renderKeys()
                }
                case "分词": type("'")
                case "⌫": backspace()
                default: type(key.lowercased())
                }
            }
        }
        let bottomLabels = session.mode == .chineseNineKey && !session.symbols ?
            ["123", "中/EN", "空格", enterTitle] :
            [session.symbols ? "返回" : "123", "中/EN", session.mode == .english ? "," : "，", "空格", session.mode == .english ? "." : "。", enterTitle]
        let nineBottom = session.mode == .chineseNineKey && !session.symbols
        let bottom = makeRow(bottomLabels, height: nineBottom && !compact ? max(44, keyHeight + 4) : keyHeight,
                             nineBottom: nineBottom) { [weak self] key in
            guard let self else { return }
            switch key {
            case "123", "返回": queueEdit { self.session.toggleSymbols(); self.renderKeys() }
            case "空格": space()
            case "中/EN": queueEdit {
                let chinese: KeyboardMode = self.prefs.string(forKey: "chineseMode") == KeyboardMode.chineseNineKey.name ? .chineseNineKey : .chineseQwerty
                self.changeMode(self.session.mode == .english ? chinese : .english)
            }
            case self.enterTitle:
                if !session.composition.isEmpty { space() }
                else { finishEnglish(); commit("\n") }
            default: literal(key)
            }
        }
        keys.addArrangedSubview(bottom)
        if let spaceKey = bottom.arrangedSubviews.first(where: { $0.accessibilityLabel == "空格" }) {
            let hold = UILongPressGestureRecognizer(target: self, action: #selector(spaceHeld(_:))); spaceKey.addGestureRecognizer(hold)
            let pan = UIPanGestureRecognizer(target: self, action: #selector(spaceDragged(_:))); spaceKey.addGestureRecognizer(pan)
        }
        resize()
    }
    @objc private func spaceHeld(_ gesture: UILongPressGestureRecognizer) { if gesture.state == .began { languages() } }
    @objc private func spaceDragged(_ gesture: UIPanGestureRecognizer) {
        if gesture.state == .changed {
            let steps = Int(gesture.translation(in: view).x / 16)
            if steps != 0 {
                if !session.composition.isEmpty { session.moveCursor(position: session.cursor + Int32(steps)); renderCandidates() }
                else { finishEnglish(); textDocumentProxy.adjustTextPosition(byCharacterOffset: steps) }
                gesture.setTranslation(.zero, in: view)
            }
        }
    }
    private func resize() {
        let width = max(200, (view.bounds.width - 10) * (!compact && prefs.string(forKey: "oneHand") != "off" ? 0.82 : 1))
        let fitting = body.systemLayoutSizeFitting(CGSize(width: width, height: 0),
            withHorizontalFittingPriority: .required, verticalFittingPriority: .fittingSizeLevel)
        height?.constant = ceil(fitting.height) + 10 + view.safeAreaInsets.bottom
    }
    private func renderCandidates() {
        for button in nineCompositionActions {
            button.isEnabled = !session.composition.isEmpty
            button.alpha = button.isEnabled ? 1 : 0.45
        }
        if session.candidates.isEmpty { expandedCandidates = false }
        (candidateBar.arrangedSubviews.last as? UIButton)?.isEnabled = !session.candidates.isEmpty
        candidateBar.isHidden = sensitive || tool != nil || (session.candidates.isEmpty && session.composition.isEmpty && englishChoices.isEmpty && predictions.isEmpty)
        phoneticScroll.isHidden = sensitive || tool != nil || session.mode != .chineseNineKey || session.composition.isEmpty
        preedit.isHidden = sensitive || tool != nil || (session.composition.isEmpty && englishWord.isEmpty)
        expandedScroll.isHidden = !expandedCandidates || tool != nil || session.candidates.isEmpty
        keys.isHidden = tool != nil && tool != "手写" && editField == nil || expandedCandidates && tool == nil
        clear(expanded)
        let raw = Array(session.composition)
        let cursor = Int(session.cursor)
        let spelling = session.mode == .chineseNineKey ? session.candidates.first?.spelling ?? session.composition : session.composition
        preedit.text = !englishWord.isEmpty ? englishWord : raw.isEmpty ? "" : session.mode == .chineseNineKey ? spelling : String(raw[..<cursor]) + "│" + String(raw[cursor...])
        clear(candidates); clear(phonetics)
        if session.mode != .english {
            let toggle = button(session.autoCorrection ? "纠错开" : "纠错关") { [weak self] in
                self?.queueEdit {
                    guard let self else { return }
                    self.session.setAutoCorrection(enabled: !self.session.autoCorrection)
                    UserDefaults.standard.set(self.session.autoCorrection, forKey: "autoCorrection")
                    self.requestCandidates()
                }
            }
            toggle.titleLabel?.font = .systemFont(ofSize: 12); phonetics.addArrangedSubview(toggle)
            for (index, syllable) in session.lockedSyllables.enumerated() {
                let b = button("✓ \(syllable.spelling) ×") { [weak self] in
                    self?.queueEdit { self?.session.unlockFrom(index: Int32(index)); self?.requestCandidates() }
                }
                b.titleLabel?.font = .systemFont(ofSize: 12); phonetics.addArrangedSubview(b)
            }
            if !session.nextSyllables.isEmpty {
                let label = UILabel(); label.text = "第\(session.lockedSyllables.count + 1)音节"; label.font = .systemFont(ofSize: 12)
                phonetics.addArrangedSubview(label)
            }
            let begin = Int(session.lockedSyllables.last?.end ?? 0)
            for syllable in session.nextSyllables {
                let typed = String(raw[begin..<Int(syllable.end)])
                let b = button("\(syllable.spelling)（\(typed)）") { [weak self] in
                    self?.queueEdit {
                        if self?.session.lockNext(syllable: syllable) == true { self?.requestCandidates() }
                    }
                }
                b.titleLabel?.font = .systemFont(ofSize: 12); phonetics.addArrangedSubview(b)
            }
        }
        if session.candidates.isEmpty {
            let hint = UILabel(); hint.font = .systemFont(ofSize: 12)
            hint.textColor = palette.muted
            hint.text = candidateLoading ? "正在生成候选…" : session.composition.isEmpty ? "" : "暂无候选，空格输入原文"
            candidates.addArrangedSubview(hint)
        } else {
            for (index, candidate) in session.candidates.enumerated() {
                let detail = session.mode != .chineseNineKey && !candidate.correction.isEmpty
                let text = output(candidate.text)
                let label = detail ? text + "\n" + candidate.correction + " " + candidate.spelling : text
                let b = button(label) { [weak self] in
                    guard let self else { return }
                    queueEdit {
                        guard self.session.candidates.indices.contains(index),
                              self.session.candidates[index].isEqual(candidate) else { return }
                        self.choose(index)
                    }
                }
                b.titleLabel?.numberOfLines = 2; b.titleLabel?.textAlignment = .center
                b.titleLabel?.font = .systemFont(ofSize: detail ? 12 : CGFloat(prefs.integer(forKey: "candidateFont")))
                b.backgroundColor = index == 0 ? palette.selected : .clear
                b.contentEdgeInsets = UIEdgeInsets(top: 0, left: 12, bottom: 0, right: 12)
                b.tag = index
                b.addInteraction(UIContextMenuInteraction(delegate: self))
                if index < 12 { candidates.addArrangedSubview(b) }
            }
        }
        if prefs.bool(forKey: "mixed"), !session.composition.isEmpty, session.lockedSyllables.isEmpty {
            let value = session.composition
            candidates.addArrangedSubview(button(value) { [weak self] in self?.queueEdit {
                guard let self, self.session.composition == value else { return }
                self.commit(self.session.takeRaw()); self.requestCandidates()
            } })
        }
        for word in englishChoices {
            let expected = englishWord.isEmpty ? session.composition : englishWord
            candidates.addArrangedSubview(button(word) { [weak self] in self?.queueEdit {
                guard let self, (self.englishWord.isEmpty ? self.session.composition : self.englishWord) == expected else { return }
                if !self.englishWord.isEmpty { self.finishEnglish(word) }
                else { self.session.clearComposition(); self.commit(word); self.requestCandidates() }
            } })
        }
        if session.composition.isEmpty, englishWord.isEmpty {
            for word in predictions { candidates.addArrangedSubview(button(output(word)) { [weak self] in self?.commit(self?.output(word) ?? "") }) }
        }
        if expandedCandidates {
            for start in stride(from: 0, to: session.candidates.count, by: 3) {
                let line = UIStackView(); line.distribution = .fillEqually; line.spacing = 4
                for index in start..<min(start + 3, session.candidates.count) {
                    line.addArrangedSubview(button(output(session.candidates[index].text)) { [weak self] in self?.queueEdit { self?.choose(index) } })
                }
                line.heightAnchor.constraint(equalToConstant: 38).isActive = true; expanded.addArrangedSubview(line)
            }
        }
        (candidateBar.arrangedSubviews.last as? UIButton)?.setTitle(expandedCandidates ? "收起" : "展开", for: .normal)
        resize()
    }
    private func invalidateCandidates() {
        candidateRevision += 1; candidateLoading = false; deferred = nil; pinyin.cancel()
    }
    func contextMenuInteraction(_ interaction: UIContextMenuInteraction, configurationForMenuAtLocation location: CGPoint) -> UIContextMenuConfiguration? {
        guard let index = interaction.view?.tag, session.candidates.indices.contains(index) else { return nil }
        let candidate = session.candidates[index]
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            UIMenu(children: [UIAction(title: "移除个人学习记录", attributes: .destructive) { [weak self] _ in
                guard let self else { return }
                pinyin.word(spelling: candidate.spelling.replacingOccurrences(of: " ", with: "'"), text: candidate.text, remove: true) { [weak self] _ in self?.requestCandidates() }
            }])
        }
    }
    private func requestCandidates() {
        invalidateCandidates()
        syncMarked()
        let input = session.composition, mode = session.mode, request = candidateRevision
        let editRevision = session.revision, locks = session.lockSpecification, corrections = session.autoCorrection
        guard !input.isEmpty else {
            englishChoices = []; predictions = []
            if prefs.bool(forKey: "prediction"), wordInput, editField == nil, mode != .english {
                let context = textDocumentProxy.documentContextBeforeInput ?? ""
                pinyin.predict(context) { [weak self] words in
                    guard let self, self.active, request == self.candidateRevision else { return }
                    self.predictions = words; self.renderCandidates()
                }
            }
            renderCandidates(); return
        }
        candidateLoading = true; renderCandidates()
        pinyin.search(input: input, nine: mode == .chineseNineKey, locks: locks, corrections: corrections) { [weak self] result in
            guard let self, active, request == candidateRevision, session.revision == editRevision,
                  session.composition == input, session.mode == mode else { return }
            candidateLoading = false
            switch result {
            case .success(let list): _ = session.applyCandidates(input: input, inputMode: mode, result: list, inputRevision: editRevision); renderCandidates()
            case .failure(let error): preedit.text = input + " · " + error.localizedDescription
            }
            let action = deferred; deferred = nil; action?()
        }
        if prefs.bool(forKey: "mixed"), session.lockedSyllables.isEmpty {
            let shortcuts = prefs.dictionary(forKey: "shortcuts") as? [String: String] ?? [:]
            extrasQueue.async { [weak self] in
                guard let self else { return }
                let dict = dictionary ?? EnglishDictionary(); dictionary = dict
                let words = mode == .chineseNineKey ? dict.nine(input) : dict.suggestions(input)
                DispatchQueue.main.async { [weak self] in
                    guard let self, active, candidateRevision == request else { return }
                    englishChoices = (shortcuts[input].map { [$0] } ?? []) + words
                    renderCandidates()
                }
            }
        }
    }
    private func type(_ value: String) {
        queueEdit {
            if self.session.mode == .english, self.wordInput, self.editField != 1,
               self.prefs.bool(forKey: "englishSuggestions") {
                self.englishWord += self.session.uppercase ? value.uppercased() : value
                if self.session.uppercase && !self.capsLock { self.session.toggleShift(); self.renderKeys() }
                self.syncMarked()
                let expected = self.englishWord; self.candidateRevision += 1; let request = self.candidateRevision
                self.extrasQueue.async { [weak self] in
                    guard let self else { return }
                    let dict = self.dictionary ?? EnglishDictionary(); self.dictionary = dict
                    let words = dict.suggestions(expected)
                    DispatchQueue.main.async {
                        guard self.active, self.candidateRevision == request, self.englishWord == expected else { return }
                        self.englishChoices = words; self.renderCandidates()
                    }
                }
                self.renderCandidates(); return
            }
            let before = self.session.composition
            let direct = self.session.key(value: value)
            if !direct.isEmpty { self.commit(direct) }
            if before != self.session.composition { self.requestCandidates() }
        }
    }
    private func queueEdit(_ action: @escaping () -> Void) {
        guard active else { return }
        editActions.append(action); drainEdits()
    }
    private func drainEdits() {
        guard !runningEdits, deferred == nil else { return }
        runningEdits = true
        while !editActions.isEmpty && deferred == nil { editActions.removeFirst()() }
        runningEdits = false
    }
    private func choose(_ index: Int, suffix: String = "") {
        guard session.candidates.indices.contains(index) else { return }
        let candidate = session.candidates[index], request = candidateRevision, editRevision = session.revision
        deferred = {}
        pinyin.select(candidate, learn: prefs.bool(forKey: "learning") && wordInput && editField == nil) { [weak self] ok in
            guard let self, active, request == candidateRevision else { return }
            deferred = nil
            if ok && KeyboardPolicy.shared.canChoose(session: session, candidate: candidate, revision: editRevision, index: Int32(index)) {
                commit(output(session.choose(index: Int32(index))) + suffix); expandedCandidates = false
            }
            requestCandidates(); drainEdits()
        }
    }
    private func space() {
        queueEdit {
            if self.candidateLoading && !self.session.composition.isEmpty {
                self.deferred = { [weak self] in self?.space(); self?.drainEdits() }; return
            }
            if !self.session.composition.isEmpty {
                if self.session.candidates.isEmpty { self.commit(self.session.takeRaw()); self.requestCandidates() }
                else { self.choose(0) }
                return
            }
            if !self.englishWord.isEmpty {
                let value = self.prefs.bool(forKey: "englishCorrection") ? self.dictionary?.correction(self.englishWord) ?? self.englishWord : self.englishWord
                self.finishEnglish(value)
            }
            self.finishHandwriting()
            let before = self.textDocumentProxy.documentContextBeforeInput ?? ""
            if self.prefs.bool(forKey: "doubleSpace"), KeyboardPolicy.shared.doubleSpace(context: before, elapsedMs: Int64(Date().timeIntervalSince(self.lastSpace) * 1000)) {
                self.textDocumentProxy.deleteBackward(); self.commit(self.session.mode == .english ? ". " : "。")
            } else { self.commit(" ") }
            self.lastSpace = Date(); self.autoCapitalization(); self.renderKeys(); self.renderCandidates()
        }
    }
    private func literal(_ text: String) {
        queueEdit {
            self.finishEnglish(); self.finishHandwriting()
            if self.candidateLoading && !self.session.composition.isEmpty {
                self.deferred = { [weak self] in self?.literal(text); self?.drainEdits() }; return
            }
            if self.session.composition.isEmpty { self.commit(text) }
            else if let first = self.session.candidates.first, first.consumed == self.session.composition.count, first.correction.isEmpty { self.choose(0, suffix: text) }
            self.autoCapitalization(); self.renderCandidates()
        }
    }
    private func backspace() {
        queueEdit {
            if !self.englishWord.isEmpty {
                self.englishWord.removeLast(); self.englishChoices = []; self.syncMarked(); self.renderCandidates(); return
            }
            if !self.handwritingWord.isEmpty { self.cancelMarked(); self.handwritingWord = ""; self.handwritingRevision += 1; return }
            if self.board?.hasStrokes == true, self.tool == "手写" { self.board?.undo(); return }
            if let field = self.editField { if !self.draft[field].isEmpty { self.draft[field].removeLast() }; self.renderForm(); return }
            if self.session.backspace() { self.requestCandidates() }
            else { self.textDocumentProxy.deleteBackward(); self.lastHostContext = self.textDocumentProxy.documentContextBeforeInput; self.refreshTranslation() }
        }
    }
    override func paste(itemProviders: [NSItemProvider]) {
        itemProviders.first?.loadObject(ofClass: NSString.self) { [weak self] object, _ in
            guard let text = object as? String else { return }
            DispatchQueue.main.async {
                guard let self, self.active, !self.sensitive else { return }
                if self.editField == nil { self.clips.add(text) }
                self.literal(text)
                if self.tool == "剪贴板" { self.openTool("剪贴板") }
            }
        }
    }
    private func commit(_ text: String) {
        guard !text.isEmpty else { return }
        if let field = editField {
            draft[field] += text; renderForm(); return
        }
        committing = true
        textDocumentProxy.insertText(text)
        if !marked.isEmpty { textDocumentProxy.unmarkText(); marked = "" }
        lastHostContext = textDocumentProxy.documentContextBeforeInput
        lastCommit = text; undoContext = lastHostContext
        committing = false
        refreshTranslation()
    }
    private func syncMarked() {
        guard editField == nil else { return }
        let value = KeyboardPolicy.shared.markedText(english: englishWord, handwriting: handwritingWord,
            pinyin: session.composition, inline: prefs.bool(forKey: "inline"), sensitive: sensitive)
        committing = true
        if value.isEmpty {
            if !marked.isEmpty { textDocumentProxy.setMarkedText("", selectedRange: NSRange(location: 0, length: 0)); textDocumentProxy.unmarkText() }
        } else { textDocumentProxy.setMarkedText(value, selectedRange: NSRange(location: (value as NSString).length, length: 0)) }
        marked = value; lastHostContext = textDocumentProxy.documentContextBeforeInput; committing = false
    }
    private func cancelMarked() {
        if !marked.isEmpty {
            committing = true; textDocumentProxy.setMarkedText("", selectedRange: NSRange(location: 0, length: 0))
            textDocumentProxy.unmarkText(); marked = ""; lastHostContext = textDocumentProxy.documentContextBeforeInput; committing = false
        }
    }
    private func finishEnglish(_ value: String? = nil) {
        guard !englishWord.isEmpty else { return }
        let text = value ?? englishWord; englishWord = ""; englishChoices = []; candidateRevision += 1; commit(text)
    }
    private func autoCapitalization() {
        guard session.mode == .english, !capsLock, prefs.bool(forKey: "autoCap"), wordInput else { return }
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        let uppercase = KeyboardPolicy.shared.capitalize(context: before)
        if session.uppercase != uppercase { session.toggleShift() }
    }
    private func output(_ text: String) -> String { prefs.bool(forKey: "traditional") ? converter?.convert(text) ?? text : text }
    private func openTool(_ name: String) {
        queueEdit {
            self.finishEnglish(); self.finishHandwriting(); self.cancelMarked()
            self.handwritingPending?.cancel(); self.handwritingRevision += 1
            self.session.clearComposition(); self.invalidateCandidates(); self.expandedCandidates = false
            self.editField = nil; self.form.isHidden = true
            self.tool = name; self.clear(self.panel); self.panelScroll.isHidden = name == "手写"; self.keys.isHidden = name != "手写"
            if name != "手写" {
                self.panel.addArrangedSubview(self.makeRow([name, "关闭"], height: 36) { [weak self] label in
                    if label == "关闭" { self?.closePanel() }
                })
            }
            switch name {
            case "设置": self.settingsPanel()
            case "剪贴板": self.clipboardPanel()
            case "编辑": self.editPanel()
            case "符号", "表情": self.symbolPanel(emoji: name == "表情")
            case "手写": break
            default: break
            }
            self.renderKeys(); self.renderCandidates()
        }
    }
    private func closePanel() {
        finishEnglish(); finishHandwriting(); cancelMarked(); handwritingPending?.cancel(); handwritingRevision += 1
        tool = nil; editField = nil; form.isHidden = true; panelScroll.isHidden = true
        if let mode = modeBeforeForm { session.changeMode(value: mode); modeBeforeForm = nil }
        renderKeys(); requestCandidates()
    }
    private func notice(_ text: String) {
        let label = UILabel(); label.text = text; label.numberOfLines = 0
        label.font = .systemFont(ofSize: 13); label.textColor = palette.muted; panel.addArrangedSubview(label)
    }
    private func toggle(_ title: String, _ key: String) {
        let line = UIStackView(); line.alignment = .center
        let label = UILabel(); label.text = title; label.font = .systemFont(ofSize: 14); label.textColor = palette.ink
        let control = UISwitch(); control.isOn = prefs.bool(forKey: key); control.accessibilityLabel = title
        control.addAction(UIAction { [weak self, weak control] _ in
            guard let control else { return }
            self?.prefs.set(control.isOn, forKey: key)
            if key == "autoCorrection" { self?.session.setAutoCorrection(enabled: control.isOn) }
            if key == "gpuTranslation" {
                self?.engine.unload(); self?.lastSource = ""
            }
            self?.renderKeys(); self?.renderCandidates(); self?.refreshTranslation()
        }, for: .valueChanged)
        line.addArrangedSubview(label); line.addArrangedSubview(control); panel.addArrangedSubview(line)
    }
    private func settingButton(_ title: String, action: @escaping () -> Void) {
        let b = button(title, action: action); b.heightAnchor.constraint(equalToConstant: 38).isActive = true; panel.addArrangedSubview(b)
    }
    private func settingsPanel() {
        cycle("翻译模型", "translationModel", ["m2m100", "opus"], ["M2M100 · 中英互译", "OPUS-MT · 中文译英文"])
        if prefs.string(forKey: "translationModel") == "opus" { toggle("Metal GPU 翻译（实验）", "gpuTranslation") }
        for (title, key) in [("翻译栏","translation"),("拼音错键纠正","autoCorrection"),("学习选词和短语","learning"),
                             ("后续字词联想","prediction"),("中文建议英文／数字原文","mixed"),("输入框内联拼音","inline"),
                             ("繁体中文输出","traditional")] { toggle(title, key) }
        let groups = ["an / ang","en / eng","in / ing","c / ch","s / sh","z / zh","l / n","f / h"]
        for (i, title) in groups.enumerated() {
            let line = UIStackView(); let label = UILabel(); label.text = "模糊音 \(title)"; label.font = .systemFont(ofSize: 14); label.textColor = palette.ink
            let control = UISwitch(); control.isOn = prefs.integer(forKey: "fuzzyGroups") & (1 << i) != 0; control.accessibilityLabel = "模糊音 \(title)"
            control.addAction(UIAction { [weak self, weak control] _ in
                guard let self, let control else { return }; let old = prefs.integer(forKey: "fuzzyGroups")
                prefs.set(control.isOn ? old | (1 << i) : old & ~(1 << i), forKey: "fuzzyGroups")
            }, for: .valueChanged)
            line.addArrangedSubview(label); line.addArrangedSubview(control); panel.addArrangedSubview(line)
        }
        settingButton("个人词典：添加、编辑、删除") { [weak self] in self?.dictionaryPanel() }
        settingButton("短语缩写：添加、编辑、删除") { [weak self] in self?.shortcutsPanel() }
        settingButton("清除个人词典和学习记录") { [weak self] in
            guard let self else { return }
            let alert = UIAlertController(title: "清除全部学习数据？", message: "个人词语和选词历史会被删除。", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "取消", style: .cancel))
            alert.addAction(UIAlertAction(title: "清除", style: .destructive) { _ in self.pinyin.clearLearning { ok in self.notice(ok ? "已清除学习数据" : "清除失败") } })
            self.present(alert, animated: true)
        }
        for (title,key) in [("英文词候选与拼写纠正","englishSuggestions"),("英文空格自动更正","englishCorrection"),
                            ("句首自动大写","autoCap"),("双击空格插入句号","doubleSpace"),("数字行","numberRow"),
                            ("按键振动","vibration"),("按键声音","sound"),("按键弹出预览","preview"),("按键边框","keyBorder")] { toggle(title,key) }
        cycle("单手模式", "oneHand", ["off","left","right"], ["关闭","靠左","靠右"])
        cycle("主题", "theme", ["system","light","dark"], ["跟随系统","浅色","深色"])
        slider("按键高度", "keyHeight", 40, 68)
        slider("按键字号", "keyFont", 16, 28); slider("候选字号", "candidateFont", 16, 28)
        cycle("手写速度", "handwritingSpeed", ["fast","normal","slow"], ["快","正常","慢"])
        slider("手写笔画粗细", "handwritingThickness", 1, 6)
        slider("长按延迟（毫秒）", "longPress", 250, 700)
        settingButton("清除剪贴板历史") { [weak self] in self?.clips.clear(); self?.notice("已清除剪贴板历史") }
        notice("设置保存在键盘扩展内。iOS 剪贴板需系统许可；键盘扩展不能访问麦克风，语音请使用系统听写。")
    }
    private func cycle(_ title: String, _ key: String, _ values: [String], _ labels: [String]) {
        let index = values.firstIndex(of: prefs.string(forKey: key) ?? values[0]) ?? 0
        settingButton("\(title)：\(labels[index])") { [weak self] in
            guard let self else { return }; prefs.set(values[(index + 1) % values.count], forKey: key)
            if key == "translationModel" {
                engine.unload(); lastSource = ""; refreshTranslation()
            }
            openTool("设置")
        }
    }
    private func slider(_ title: String, _ key: String, _ min: Float, _ max: Float) {
        let label = UILabel(); label.font = .systemFont(ofSize: 14); label.textColor = palette.ink
        label.text = "\(title)：\(prefs.integer(forKey: key))"; panel.addArrangedSubview(label)
        let slider = UISlider(); slider.minimumValue = min; slider.maximumValue = max; slider.value = Float(prefs.integer(forKey: key))
        slider.accessibilityLabel = title
        slider.addAction(UIAction { [weak self, weak slider] _ in
            guard let slider else { return }
            self?.prefs.set(Int(slider.value.rounded()), forKey: key); label.text = "\(title)：\(Int(slider.value.rounded()))"
        }, for: .valueChanged)
        panel.addArrangedSubview(slider)
    }
    private func dictionaryPanel() {
        clear(panel); tool = "词典"
        settingButton("关闭") { [weak self] in self?.closePanel() }
        settingButton("＋ 添加词语") { [weak self] in self?.startForm(nil, shortcut: false) }
        pinyin.words { [weak self] words in
            guard let self, active, tool == "词典" else { return }
            for row in words {
                let parts = row.split(whereSeparator: { $0.isWhitespace }).map(String.init)
                guard parts.count >= 2 else { continue }
                settingButton("\(parts[0])  \(parts[1])") { [weak self] in self?.startForm(parts, shortcut: false) }
            }
        }
    }
    private func shortcutsPanel() {
        clear(panel); tool = "缩写"
        settingButton("关闭") { [weak self] in self?.closePanel() }
        settingButton("＋ 添加缩写") { [weak self] in self?.startForm(nil, shortcut: true) }
        let shortcuts = prefs.dictionary(forKey: "shortcuts") as? [String: String] ?? [:]
        for key in shortcuts.keys.sorted() {
            settingButton("\(key) → \(shortcuts[key]!)") { [weak self] in self?.startForm([shortcuts[key]!,key], shortcut: true) }
        }
    }
    private func startForm(_ entry: [String]?, shortcut: Bool) {
        modeBeforeForm = session.mode
        originalWord = entry; editingShortcut = shortcut; draft = entry.map { Array($0.prefix(2)) } ?? ["",""]
        editField = 0; tool = nil; panelScroll.isHidden = true; form.isHidden = false
        session.changeMode(value: .chineseQwerty); renderForm(); renderKeys(); renderCandidates()
    }
    private func renderForm() {
        clear(form)
        for index in 0..<2 {
            let title = index == 0 ? "词语／短语" : editingShortcut ? "缩写（小写字母或2–9）" : "全拼（用 ' 分隔）"
            let fieldButton = button("\(editField == index ? "● " : "")\(title)：\(draft[index])") { [weak self] in
                guard let self else { return }; finishEnglish(); session.clearComposition(); invalidateCandidates()
                editField = index; session.changeMode(value: index == 1 ? .english : .chineseQwerty)
                renderForm(); renderKeys(); renderCandidates()
            }
            fieldButton.accessibilityIdentifier = index == 0 ? "wordField" : "spellingField"
            form.addArrangedSubview(fieldButton)
        }
        form.addArrangedSubview(makeRow(originalWord == nil ? ["保存","取消"] : ["保存","删除","取消"], height: 30) { [weak self] name in
            guard let self else { return }
            finishEnglish(); session.clearComposition(); invalidateCandidates()
            if name == "取消" { closePanel(); return }
            if name == "删除" {
                if editingShortcut {
                    var data = prefs.dictionary(forKey: "shortcuts") as? [String: String] ?? [:]; data.removeValue(forKey: originalWord![1]); prefs.set(data, forKey: "shortcuts"); closePanel()
                } else { pinyin.word(spelling: originalWord![1], text: originalWord![0], remove: true) { [weak self] _ in self?.closePanel() } }
                return
            }
            let text = draft[0].trimmingCharacters(in: .whitespacesAndNewlines), spelling = draft[1].trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty, text.count <= 1000, !spelling.isEmpty else { return }
            if editingShortcut {
                guard KeyboardPolicy.shared.validShortcut(key: spelling, phrase: text) else { return }
                var data = prefs.dictionary(forKey: "shortcuts") as? [String:String] ?? [:]
                if let old = originalWord { data.removeValue(forKey: old[1]) }; data[spelling] = text
                prefs.set(data, forKey: "shortcuts"); closePanel()
            } else {
                pinyin.word(spelling: spelling, text: text) { [weak self] ok in
                    guard let self else { return }
                    if ok {
                        if let old = originalWord, old[0] != text || old[1] != spelling { pinyin.word(spelling: old[1], text: old[0], remove: true) { _ in } }
                        closePanel()
                    } else { (form.arrangedSubviews.last as? UIStackView)?.accessibilityLabel = "请填写有效全拼，音节数对应汉字" }
                }
            }
        })
    }
    private func clipboardPanel() {
        notice("点系统粘贴按钮读取文字；未固定文字保留 1 小时。")
        let paste = UIPasteControl(configuration: .init()); paste.target = self; panel.addArrangedSubview(paste)
        for entry in clips.entries() {
            settingButton((entry.pinned ? "📌 " : "") + entry.text) { [weak self] in self?.commit(entry.text) }
            panel.addArrangedSubview(makeRow([entry.pinned ? "取消固定" : "固定", "删除"], height: 30) { [weak self] action in
                guard let self else { return }; if action == "删除" { clips.delete(entry.text) } else { clips.pin(entry.text) }
                openTool("剪贴板")
            })
        }
        if clips.entries().isEmpty { notice("暂无剪贴板历史") }
    }
    private func editPanel() {
        notice("复制和剪切请先在输入框选择文字。")
        panel.addArrangedSubview(makeRow(["←","→","复制","剪切"], height: 42) { [weak self] action in
            guard let self else { return }
            switch action {
            case "←": textDocumentProxy.adjustTextPosition(byCharacterOffset: -1)
            case "→": textDocumentProxy.adjustTextPosition(byCharacterOffset: 1)
            default:
                guard hasFullAccess, let text = textDocumentProxy.selectedText, !text.isEmpty else { notice("请先选择文字；复制需开启键盘完全访问。"); return }
                UIPasteboard.general.string = text; clips.add(text)
                if action == "剪切" { textDocumentProxy.deleteBackward() }
            }
        })
        let paste = UIPasteControl(configuration: .init()); paste.target = self; panel.addArrangedSubview(paste)
        panel.addArrangedSubview(makeRow(["按词删除","撤销"], height: 42) { [weak self] action in
            guard let self else { return }
            let before = textDocumentProxy.documentContextBeforeInput ?? ""
            if action == "撤销" {
                guard before == undoContext, !lastCommit.isEmpty, before.hasSuffix(lastCommit) else { return }
                for _ in lastCommit { textDocumentProxy.deleteBackward() }; lastCommit = ""; undoContext = nil
            } else {
                let tail = KeyboardPolicy.shared.deleteWord(context: before)
                for _ in tail { textDocumentProxy.deleteBackward() }
            }
            refreshTranslation()
        })
    }
    private func symbolPanel(emoji: Bool) {
        let groups = KeyboardSymbols.shared.categories(emoji: emoji).map {
            ($0, KeyboardSymbols.shared.values(emoji: emoji, category: $0, recent: "").joined(separator: " "))
        }
        let values = UIStackView(); values.axis = .vertical; values.spacing = 4
        let key = emoji ? "recentEmoji" : "recentSymbols"
        let render: (String) -> Void = { [weak self, weak values] text in
            guard let self, let values else { return }
            clear(values)
            let symbols = text.split(separator: " ").map(String.init)
            for i in stride(from: 0, to: symbols.count, by: 6) {
                values.addArrangedSubview(makeRow(Array(symbols[i..<min(i+6,symbols.count)]), height: 42) { [weak self] symbol in
                    guard let self else { return }; commit(symbol)
                    let recent = prefs.stringArray(forKey: key) ?? []
                    prefs.set(KeyboardSymbols.shared.remember(value: symbol, previous: recent.joined(separator: " ")).split(separator: " ").map(String.init), forKey: key)
                })
            }
        }
        let tabs = UIStackView(); tabs.distribution = .fillEqually
        for (name,text) in groups { tabs.addArrangedSubview(button(name) { [weak self] in render(name == "最近" ? self?.prefs.stringArray(forKey: key)?.joined(separator: " ") ?? "" : text) }) }
        panel.addArrangedSubview(tabs)
        if emoji {
            panel.addArrangedSubview(makeRow(KeyboardSymbols.shared.keywords(), height: 30) { term in
                render(KeyboardSymbols.shared.keywordValues(keyword: term).joined(separator: " "))
            })
        }
        panel.addArrangedSubview(values); render(groups[1].1)
    }
    private func finishHandwriting() {
        if !handwritingWord.isEmpty { let word = handwritingWord; handwritingWord = ""; commit(word) }
    }
    private func renderHandwriting() {
        keys.isHidden = false; handwritingRevision += 1; handwritingPending?.cancel()
        let strip = UIStackView(); strip.spacing = 4
        let scroll = UIScrollView(); scroll.addSubview(strip); strip.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            strip.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor), strip.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            strip.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor), strip.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            strip.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor), scroll.heightAnchor.constraint(equalToConstant: 42)
        ])
        keys.addArrangedSubview(scroll)
        let board = HandwritingView(); self.board = board; board.backgroundColor = .clear; board.isOpaque = false; board.ink = palette.ink
        board.layer.cornerRadius = 12; board.lineWidth = CGFloat(prefs.integer(forKey: "handwritingThickness")); board.accessibilityLabel = "中文手写画板"
        board.heightAnchor.constraint(equalToConstant: compact ? 120 : keyHeight * 4).isActive = true; keys.addArrangedSubview(board)
        board.began = { [weak self] in
            guard let self else { return }; handwritingPending?.cancel(); handwritingRevision += 1; finishHandwriting(); clear(strip)
        }
        board.changed = { [weak self, weak board, weak strip] points in
            guard let self, let board, let strip else { return }; handwritingPending?.cancel(); handwritingRevision += 1
            let request = handwritingRevision; clear(strip)
            guard !points.isEmpty else { return }
            handwritingEngine.recognize(points) { [weak self] words in
                guard let self, active, tool == "手写", request == handwritingRevision else { return }
                for word in words {
                    let b = button(output(word)) { [weak self] in
                        guard let self, request == handwritingRevision else { return }
                        handwritingPending?.cancel(); handwritingWord = ""; commit(output(word)); board.clear(); handwritingRevision += 1; clear(strip)
                    }
                    b.widthAnchor.constraint(equalToConstant: 52).isActive = true; strip.addArrangedSubview(b)
                }
                guard let first = words.first else { return }
                let work = DispatchWorkItem { [weak self] in
                    guard let self, active, request == handwritingRevision, tool == "手写" else { return }
                    handwritingWord = output(first); syncMarked(); board.clear()
                }
                handwritingPending = work
                let delay = Double(KeyboardPolicy.shared.handwritingDelay(speed: prefs.string(forKey: "handwritingSpeed") ?? "normal")) / 1000
                DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
            }
        }
        keys.addArrangedSubview(makeRow(["键盘","撤销","清空","空格","⌫",enterTitle], height: 44) { [weak self] name in
            guard let self else { return }
            switch name {
            case "键盘": closePanel()
            case "清空": handwritingPending?.cancel(); handwritingRevision += 1; cancelMarked(); handwritingWord = ""; board.clear(); clear(strip)
            case "撤销", "⌫":
                if !handwritingWord.isEmpty { cancelMarked(); handwritingWord = ""; handwritingRevision += 1; clear(strip) }
                else if board.hasStrokes { board.undo() } else if name == "⌫" { textDocumentProxy.deleteBackward() }
            case "空格": finishHandwriting(); commit(" ")
            default: finishHandwriting(); commit("\n")
            }
        })
    }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated); active = true
        if !wordInput { session.changeMode(value: .english) }
        lastHostContext = textDocumentProxy.documentContextBeforeInput
        autoCapitalization(); renderKeys(); renderCandidates(); refreshTranslation()
        extrasQueue.async { [weak self] in
            guard let self else { return }
            if self.dictionary == nil { self.dictionary = EnglishDictionary() }
            if self.converter == nil { self.converter = TraditionalConverter() }
        }
    }
    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        if active {
            let context = textDocumentProxy.documentContextBeforeInput
            if !committing && context != lastHostContext && (!session.composition.isEmpty || !englishWord.isEmpty || !handwritingWord.isEmpty) {
                editActions.removeAll()
                if !englishWord.isEmpty || !handwritingWord.isEmpty { textDocumentProxy.unmarkText(); marked = "" }
                else { cancelMarked() }
                englishWord = ""; englishChoices = []; handwritingWord = ""
                handwritingRevision += 1; handwritingPending?.cancel(); board?.clear()
                session.clearComposition(); invalidateCandidates(); renderCandidates()
            }
            lastHostContext = context; refreshTranslation()
        }
    }
    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in self?.renderKeys(); self?.resize() }
    }
    override func viewDidDisappear(_ animated: Bool) {
        deleteTimer?.invalidate(); deleteTimer = nil; keyPreview?.removeFromSuperview()
        finishEnglish(); finishHandwriting(); cancelMarked()
        active = false; revision += 1; pending?.cancel(); engine.unload(); pinyin.close()
        tool = nil; editField = nil; form.isHidden = true; panelScroll.isHidden = true
        handwritingRevision += 1; handwritingPending?.cancel(); handwritingEngine.close(); board = nil
        editActions.removeAll()
        session.clearComposition(); invalidateCandidates(); lastSource = ""
        super.viewDidDisappear(animated)
    }
    private func refreshTranslation() {
        guard active else { return }
        var context = textDocumentProxy.documentContextBeforeInput ?? ""
        context = KeyboardPolicy.shared.committedContext(context: context, marked: marked)
        guard prefs.bool(forKey: "translation"), wordInput, editField == nil else {
            revision += 1; pending?.cancel(); engine.cancel(); lastSource = ""; source.text = ""; english.text = ""
            translationCard.isHidden = true; return
        }
        let sentence = TranslationText.shared.sentence(context: context)
        let bilingual = prefs.string(forKey: "translationModel") != "opus"
        let reverse = bilingual
            && TranslationText.shared.accepts(source: sentence, direction: .englishToChinese)
        layout.direction.text = reverse ? "EN → 中" : "中 → EN"
        guard reverse || TranslationText.shared.accepts(source: sentence, direction: .chineseToEnglish) else {
            revision += 1; pending?.cancel(); engine.cancel(); lastSource = ""
            source.isHidden = true
            english.text = bilingual ? "输入中文或英文，查看对照翻译" : "输入中文，看看英文怎么说"
            layout.direction.text = bilingual ? "中 ⇄ EN" : "中 → EN"
            status.text = bilingual ? "M2M100 · 离线" : "OPUS-MT · 离线"
            resize()
            return
        }
        guard sentence != lastSource else { return }
        lastSource = sentence; revision += 1
        let request = revision
        pending?.cancel(); engine.cancel(); source.text = sentence; source.isHidden = compact; english.text = "正在翻译…"
        resize()
        guard !sentence.isEmpty else { status.text = "输入中文，停顿后显示英文"; return }
        status.text = "等待停顿…"
        let work = DispatchWorkItem { [weak self] in
            guard let self, active, revision == request else { return }
            status.text = "正在本地翻译…"
            engine.translate(source: sentence) { [weak self] result in
                guard let self, active, revision == request else { return }
                switch result {
                case .success(let output): english.text = output; status.text = engine.backendLabel
                case .failure(let error): english.text = error.localizedDescription; status.text = "翻译未完成"
                }
                resize()
            }
        }
        pending = work; DispatchQueue.main.asyncAfter(deadline: .now() + 0.7, execute: work)
    }
}
