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
    private let translationPolicy = TranslationPolicy()
    private let candidateCoordinator = CandidateCoordinator()
    private var candidateRevision: Int64 { candidateCoordinator.generation }
    private var candidateLoading = false
    private var deferred: (() -> Void)?
    private var editActions: [() -> Void] = []
    private var runningEdits = false
    private var lastHostContext: String?
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
    private let clips = ClipboardHistory()
    private lazy var candidatePanel = IOSCandidatePanel(menuDelegate: self,
        button: { [weak self] title, action in self?.button(title, action: action) ?? UIButton() })
    private lazy var panels = IOSKeyboardPanels(panel: panel, prefs: prefs, clips: clips, pasteTarget: self,
        skin: { [weak self] in self?.palette ?? IOSKeyboardSkin(dark: false, followsSystem: true) },
        button: { [weak self] title, action in self?.button(title, action: action) ?? UIButton() },
        row: { [weak self] labels, height, action in self?.makeRow(labels, height: height, action: action) ?? UIStackView() },
        action: { [weak self] in self?.panelAction($0) })
    private lazy var handwriting = IOSHandwritingInput(prefs: prefs,
        active: { [weak self] in self?.active == true && self?.tool == "手写" },
        button: { [weak self] title, action in self?.button(title, action: action) ?? UIButton() },
        output: { [weak self] in self?.output($0) ?? $0 },
        commit: { [weak self] in self?.commit($0) },
        syncMarked: { [weak self] in self?.syncMarked() },
        queueEdit: { [weak self] in self?.queueEdit($0) })
    private var handwritingWord: String { handwriting.word }
    private var handwritingCandidate: String { handwriting.candidate }
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
        resolveHandwriting { self.changeModeNow(mode) }
    }
    private func changeModeNow(_ mode: KeyboardMode) {
        finishEnglish(); cancelMarked(); invalidateCandidates()
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
            hint.text = candidateLoading ? "正在生成候选…" : session.composition.isEmpty ? "" : "暂无候选，可退格修改或点原文"
            candidates.addArrangedSubview(hint)
        }
        let request = candidateCoordinator.snapshot(session: session)
        candidatePanel.render(session.candidates, in: candidates, expanded: expanded,
            nineKey: session.mode == .chineseNineKey, isExpanded: expandedCandidates,
            font: CGFloat(prefs.integer(forKey: "candidateFont")), selected: palette.selected, output: output) { [weak self] index, candidate in
                self?.queueEdit {
                    guard let self, self.candidateCoordinator.canChoose(request: request, session: self.session,
                        candidate: candidate, index: Int32(index)) else { return }
                    self.choose(index)
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
        (candidateBar.arrangedSubviews.last as? UIButton)?.setTitle(expandedCandidates ? "收起" : "展开", for: .normal)
        resize()
    }
    private func invalidateCandidates() {
        candidateCoordinator.invalidate(); candidateLoading = false; deferred = nil; pinyin.cancel()
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
        let request = candidateCoordinator.snapshot(session: session)
        let input = request.input, mode = request.mode
        let editRevision = request.revision, locks = request.locks, corrections = request.corrections
        guard !input.isEmpty else {
            englishChoices = []; predictions = []
            if prefs.bool(forKey: "prediction"), wordInput, editField == nil, mode != .english {
                let context = textDocumentProxy.documentContextBeforeInput ?? ""
                pinyin.predict(context) { [weak self] words in
                    guard let self, self.active, self.candidateCoordinator.isCurrent(request: request, session: self.session) else { return }
                    self.predictions = words; self.renderCandidates()
                }
            }
            renderCandidates(); return
        }
        candidateLoading = true; renderCandidates()
        pinyin.search(input: input, nine: mode == .chineseNineKey, locks: locks, corrections: corrections) { [weak self] result in
            guard let self, active, candidateCoordinator.isCurrent(request: request, session: session) else { return }
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
                    guard let self, active, candidateCoordinator.isCurrent(request: request, session: session) else { return }
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
                let expected = self.englishWord; self.candidateCoordinator.invalidate(); let request = self.candidateRevision
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
        let candidate = session.candidates[index], request = candidateCoordinator.snapshot(session: session)
        deferred = {}
        pinyin.select(candidate, learn: prefs.bool(forKey: "learning") && wordInput && editField == nil) { [weak self] ok in
            guard let self, active, candidateCoordinator.isCurrent(request: request, session: session) else { return }
            deferred = nil
            if ok && candidateCoordinator.canChoose(request: request, session: session, candidate: candidate, index: Int32(index)) {
                commit(output(session.choose(index: Int32(index))) + suffix); expandedCandidates = false
            }
            requestCandidates(); drainEdits()
        }
    }
    private func space() {
        queueEdit { self.resolveHandwriting { self.spaceNow() } }
    }
    private func spaceNow() {
        if candidateLoading && !session.composition.isEmpty {
            deferred = { [weak self] in self?.spaceNow(); self?.drainEdits() }; return
        }
        let boundary = candidateCoordinator.boundary(session: session, boundary: .space)
        if boundary != .direct {
            if boundary == .selectFirst { choose(0) }
            else { preedit.text = session.composition + " · 请先选词、修改或点原样" }
            return
        }
        if !englishWord.isEmpty {
            let value = prefs.bool(forKey: "englishCorrection") ? dictionary?.correction(englishWord) ?? englishWord : englishWord
            finishEnglish(value)
        }
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        if prefs.bool(forKey: "doubleSpace"), KeyboardPolicy.shared.doubleSpace(context: before, elapsedMs: Int64(Date().timeIntervalSince(lastSpace) * 1000)) {
            textDocumentProxy.deleteBackward(); commit(session.mode == .english ? ". " : "。")
        } else { commit(" ") }
        lastSpace = Date(); autoCapitalization(); renderKeys(); renderCandidates()
    }
    private func literal(_ text: String) {
        queueEdit { self.resolveHandwriting { self.literalNow(text) } }
    }
    private func literalNow(_ text: String) {
        finishEnglish()
        if candidateLoading && !session.composition.isEmpty {
            deferred = { [weak self] in self?.literalNow(text); self?.drainEdits() }; return
        }
        let boundary = candidateCoordinator.boundary(session: session, boundary: .literal)
        if boundary == .direct { commit(text) }
        else if boundary == .selectFirst { choose(0, suffix: text) }
        autoCapitalization(); renderCandidates()
    }
    private func backspace() {
        queueEdit {
            if !self.englishWord.isEmpty {
                self.englishWord.removeLast(); self.englishChoices = []; self.syncMarked(); self.renderCandidates(); return
            }
            if !self.handwritingWord.isEmpty { self.discardHandwriting(); return }
            if self.handwriting.hasStrokes, self.tool == "手写" { self.handwriting.undo(); return }
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
        let text = value ?? englishWord; englishWord = ""; englishChoices = []; candidateCoordinator.invalidate(); commit(text)
    }
    private func autoCapitalization() {
        guard session.mode == .english, !capsLock, prefs.bool(forKey: "autoCap"), wordInput else { return }
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        let uppercase = KeyboardPolicy.shared.capitalize(context: before)
        if session.uppercase != uppercase { session.toggleShift() }
    }
    private func output(_ text: String) -> String { prefs.bool(forKey: "traditional") ? converter?.convert(text) ?? text : text }
    private func openTool(_ name: String) {
        queueEdit { self.resolveHandwriting {
            self.finishEnglish(); self.cancelMarked()
            self.session.clearComposition(); self.invalidateCandidates(); self.expandedCandidates = false
            self.editField = nil; self.form.isHidden = true
            self.tool = name; self.clear(self.panel); self.panelScroll.isHidden = name == "手写"; self.keys.isHidden = name != "手写"
            if name != "手写" {
                self.panel.addArrangedSubview(self.makeRow([name, "关闭"], height: 36) { [weak self] label in
                    if label == "关闭" { self?.closePanel() }
                })
            }
            self.panels.render(name)
            self.renderKeys(); self.renderCandidates()
        } }
    }
    private func closePanel() {
        queueEdit { self.resolveHandwriting { self.closePanelNow() } }
    }
    private func closePanelNow() {
        finishEnglish(); cancelMarked()
        tool = nil; editField = nil; form.isHidden = true; panelScroll.isHidden = true
        if let mode = modeBeforeForm { session.changeMode(value: mode); modeBeforeForm = nil }
        renderKeys(); requestCandidates()
    }
    private func notice(_ text: String) {
        panels.notice(text)
    }
    private func settingButton(_ title: String, action: @escaping () -> Void) {
        panels.settingButton(title, action: action)
    }
    private func panelAction(_ action: IOSPanelAction) {
        switch action {
        case .settingChanged(let key):
            if key == "autoCorrection" { session.setAutoCorrection(enabled: prefs.bool(forKey: key)) }
            if key == "gpuTranslation" || key == "translationModel" { engine.unload(); translationPolicy.reset() }
            renderKeys(); renderCandidates(); refreshTranslation()
        case .open(let name): openTool(name)
        case .close: closePanel()
        case .dictionary: dictionaryPanel()
        case .shortcuts: shortcutsPanel()
        case .commit(let text): literal(text)
        case .edit(let command): editCommand(command)
        case .clearLearning:
            let alert = UIAlertController(title: "清除全部学习数据？", message: "个人词语和选词历史会被删除。", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "取消", style: .cancel))
            alert.addAction(UIAlertAction(title: "清除", style: .destructive) { _ in self.pinyin.clearLearning { ok in self.notice(ok ? "已清除学习数据" : "清除失败") } })
            present(alert, animated: true)
        }
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
    private func editCommand(_ command: String) {
        queueEdit {
            switch command {
            case "←": self.textDocumentProxy.adjustTextPosition(byCharacterOffset: -1)
            case "→": self.textDocumentProxy.adjustTextPosition(byCharacterOffset: 1)
            case "复制", "剪切":
                guard self.hasFullAccess, let text = self.textDocumentProxy.selectedText, !text.isEmpty else {
                    self.notice("请先选择文字；复制需开启键盘完全访问。"); return
                }
                UIPasteboard.general.string = text; self.clips.add(text)
                if command == "剪切" { self.textDocumentProxy.deleteBackward() }
            case "撤销":
                let before = self.textDocumentProxy.documentContextBeforeInput ?? ""
                guard before == self.undoContext, !self.lastCommit.isEmpty, before.hasSuffix(self.lastCommit) else { return }
                for _ in self.lastCommit { self.textDocumentProxy.deleteBackward() }
                self.lastCommit = ""; self.undoContext = nil
            default:
                let tail = KeyboardPolicy.shared.deleteWord(context: self.textDocumentProxy.documentContextBeforeInput ?? "")
                for _ in tail { self.textDocumentProxy.deleteBackward() }
            }
            self.refreshTranslation()
        }
    }
    private func finishHandwriting() {
        handwriting.finish()
    }
    private func discardHandwriting() {
        cancelMarked(); clearHandwriting()
    }
    private func clearHandwriting() {
        handwriting.clear()
    }
    private func resolveHandwriting(_ action: @escaping () -> Void) {
        guard tool == "手写" else {
            finishHandwriting(); action(); return
        }
        deferred = {}
        handwriting.resolve { [weak self] resolved in
            guard let self, active else { return }
            deferred = nil
            if resolved { action() }
            drainEdits()
        }
    }
    private func renderHandwriting() {
        keys.isHidden = false
        handwriting.render(in: keys, ink: palette.ink, height: compact ? 120 : keyHeight * 4)
        keys.addArrangedSubview(makeRow(["键盘","撤销","清空","空格","⌫",enterTitle], height: 44) { [weak self] name in
            guard let self else { return }
            switch name {
            case "键盘": closePanel()
            case "清空": discardHandwriting()
            case "撤销", "⌫":
                if !handwritingWord.isEmpty { discardHandwriting() }
                else if handwriting.hasStrokes { handwriting.undo() } else if name == "⌫" { textDocumentProxy.deleteBackward() }
            case "空格": space()
            default: literal("\n")
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
            if !committing && context != lastHostContext && (!session.composition.isEmpty || !englishWord.isEmpty ||
                !handwritingWord.isEmpty || !handwritingCandidate.isEmpty || (tool == "手写" && handwriting.hasStrokes)) {
                editActions.removeAll()
                if !englishWord.isEmpty || !handwritingWord.isEmpty { textDocumentProxy.unmarkText(); marked = "" }
                else { cancelMarked() }
                englishWord = ""; englishChoices = []; clearHandwriting()
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
        active = false; translationPolicy.reset(); pending?.cancel(); engine.unload(); pinyin.close()
        tool = nil; editField = nil; form.isHidden = true; panelScroll.isHidden = true
        handwriting.close()
        editActions.removeAll()
        session.clearComposition(); invalidateCandidates()
        super.viewDidDisappear(animated)
    }
    private func refreshTranslation() {
        guard active else { return }
        var context = textDocumentProxy.documentContextBeforeInput ?? ""
        context = KeyboardPolicy.shared.committedContext(context: context, marked: marked)
        let enabled = prefs.bool(forKey: "translation") && wordInput && editField == nil
        let model = prefs.string(forKey: "translationModel") ?? "m2m100"
        let direction = TranslationModels.shared.direction(context: context, model: model)
        guard let request = translationPolicy.update(context: context, enabled: enabled, direction: direction,
            model: model, allowModelSwitch: false) else { return }
        pending?.cancel(); engine.cancel()
        guard enabled else {
            source.text = ""; english.text = ""
            translationCard.isHidden = true; return
        }
        let sentence = request.source
        let bilingual = TranslationModels.shared.supports(model: model, direction: .englishToChinese)
        let reverse = direction == .englishToChinese
        layout.direction.text = reverse ? "EN → 中" : "中 → EN"
        guard !sentence.isEmpty else {
            source.isHidden = true
            english.text = bilingual ? "输入中文或英文，查看对照翻译" : "输入中文，看看英文怎么说"
            layout.direction.text = bilingual ? "中 ⇄ EN" : "中 → EN"
            status.text = bilingual ? "M2M100 · 离线" : "OPUS-MT · 离线"
            resize()
            return
        }
        source.text = sentence; source.isHidden = compact; english.text = "正在翻译…"
        resize()
        status.text = "等待停顿…"
        let work = DispatchWorkItem { [weak self] in
            guard let self, active, translationPolicy.isCurrent(request: request) else { return }
            status.text = "正在本地翻译…"
            engine.translate(source: sentence) { [weak self] result in
                guard let self, active, translationPolicy.isCurrent(request: request) else { return }
                switch result {
                case .success(let output): english.text = output; status.text = engine.backendLabel
                case .failure(let error): english.text = error.localizedDescription; status.text = "翻译未完成"
                }
                resize()
            }
        }
        pending = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(request.delayMillis) / 1000, execute: work)
    }
}
