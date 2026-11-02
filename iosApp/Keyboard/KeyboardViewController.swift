import UIKit
import Shared

final class KeyboardViewController: UIInputViewController {
    private let source = UILabel(), english = UILabel(), status = UILabel(), preedit = UILabel()
    private let candidates = UIStackView(), modes = UIStackView(), keys = UIStackView()
    private let phonetics = UIStackView()
    private let session = KeyboardSession()
    private let pinyin = PinyinEngine()
    private let engine = LocalEngine()
    private var height: NSLayoutConstraint?
    private var pending: DispatchWorkItem?
    private var revision = 0, candidateRevision = 0
    private var candidateLoading = false
    private var deferred: (() -> Void)?
    private var editActions: [() -> Void] = []
    private var runningEdits = false
    private var lastSource = "", lastHostContext: String?
    private var active = false
    private var committing = false
    private var compact: Bool { view.bounds.width > 600 }

    override func viewDidLoad() {
        super.viewDidLoad()
        let saved = UserDefaults.standard.string(forKey: "keyboardMode")
        let mode = [KeyboardMode.chineseQwerty, .chineseNineKey, .english].first { $0.name == saved } ?? .chineseQwerty
        session.changeMode(value: mode)
        if let enabled = UserDefaults.standard.object(forKey: "pinyinCorrection") as? Bool {
            session.setAutoCorrection(enabled: enabled)
        }
        let stack = UIStackView(); stack.axis = .vertical; stack.spacing = 4
        stack.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(stack)
        height = view.heightAnchor.constraint(equalToConstant: 466); height?.priority = .defaultHigh; height?.isActive = true
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 6),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -6),
            stack.topAnchor.constraint(equalTo: view.topAnchor, constant: 6),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -6)
        ])
        source.font = .systemFont(ofSize: 13); source.numberOfLines = 1
        english.font = .systemFont(ofSize: 16); english.textColor = .systemBlue; english.numberOfLines = 2
        status.font = .systemFont(ofSize: 10); status.text = "输入中文，停顿后显示英文"
        preedit.font = .systemFont(ofSize: 16); preedit.textColor = .systemBlue; preedit.numberOfLines = 1
        [source, english, status, preedit].forEach { stack.addArrangedSubview($0) }
        source.heightAnchor.constraint(lessThanOrEqualToConstant: 18).isActive = true
        english.heightAnchor.constraint(lessThanOrEqualToConstant: 42).isActive = true
        preedit.heightAnchor.constraint(equalToConstant: 22).isActive = true
        let scroll = UIScrollView(); scroll.showsHorizontalScrollIndicator = true
        candidates.axis = .horizontal; candidates.spacing = 6; candidates.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(candidates)
        NSLayoutConstraint.activate([
            candidates.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            candidates.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            candidates.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            candidates.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            candidates.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor),
            scroll.heightAnchor.constraint(equalToConstant: 42)
        ])
        stack.addArrangedSubview(scroll)
        let phoneticScroll = UIScrollView()
        phoneticScroll.showsHorizontalScrollIndicator = false
        phonetics.axis = .horizontal; phonetics.spacing = 5; phonetics.translatesAutoresizingMaskIntoConstraints = false
        phoneticScroll.addSubview(phonetics)
        NSLayoutConstraint.activate([
            phonetics.leadingAnchor.constraint(equalTo: phoneticScroll.contentLayoutGuide.leadingAnchor),
            phonetics.trailingAnchor.constraint(equalTo: phoneticScroll.contentLayoutGuide.trailingAnchor),
            phonetics.topAnchor.constraint(equalTo: phoneticScroll.contentLayoutGuide.topAnchor),
            phonetics.bottomAnchor.constraint(equalTo: phoneticScroll.contentLayoutGuide.bottomAnchor),
            phonetics.heightAnchor.constraint(equalTo: phoneticScroll.frameLayoutGuide.heightAnchor),
            phoneticScroll.heightAnchor.constraint(equalToConstant: 32)
        ])
        stack.addArrangedSubview(phoneticScroll)
        modes.distribution = .fillEqually; modes.heightAnchor.constraint(equalToConstant: 32).isActive = true
        stack.addArrangedSubview(modes)
        keys.axis = .vertical; keys.spacing = 4; stack.addArrangedSubview(keys)
        renderModes(); renderKeys(); renderCandidates()
        pasteConfiguration = UIPasteConfiguration(forAccepting: NSString.self)
    }
    private func clear(_ stack: UIStackView) { stack.arrangedSubviews.forEach { stack.removeArrangedSubview($0); $0.removeFromSuperview() } }
    private func button(_ title: String, action: @escaping () -> Void) -> UIButton {
        let button = UIButton(type: .system); button.setTitle(title, for: .normal)
        button.backgroundColor = .secondarySystemBackground; button.layer.cornerRadius = 6
        button.titleLabel?.font = .systemFont(ofSize: 16)
        button.addAction(UIAction { _ in action() }, for: .touchUpInside)
        return button
    }
    private func row(_ labels: [String], action: @escaping (String) -> Void) {
        let row = UIStackView(); row.distribution = .fillEqually; row.spacing = 3
        labels.forEach { label in row.addArrangedSubview(button(label) { action(label) }) }
        row.heightAnchor.constraint(equalToConstant: compact ? 30 : 38).isActive = true
        keys.addArrangedSubview(row)
    }
    private func renderModes() {
        clear(modes)
        let entries: [(String, KeyboardMode)] = [("中文26键", .chineseQwerty), ("中文九键", .chineseNineKey), ("English", .english)]
        entries.forEach { label, mode in
            let title = (session.mode == mode ? "● " : "") + label
            let b = button(title) { [weak self] in
                guard let self else { return }
                queueEdit {
                    self.invalidateCandidates(); self.session.changeMode(value: mode)
                    UserDefaults.standard.set(mode.name, forKey: "keyboardMode")
                    self.renderModes(); self.renderKeys(); self.renderCandidates()
                }
            }
            b.titleLabel?.font = .systemFont(ofSize: 12); modes.addArrangedSubview(b)
        }
    }
    private func renderKeys() {
        clear(keys)
        if session.symbols {
            let symbolRows = session.mode == .english ? ["1234567890", "@#$%&*()-+", ".,?!:;\"'/_"] : ["1234567890", "@#￥%&*()-+", "，。？！：；“”《》"]
            for letters in symbolRows {
                row(letters.map { String($0) }) { [weak self] in self?.literal($0) }
            }
        } else if session.mode == .chineseNineKey {
            for labels in [["1 分词", "2 ABC", "3 DEF"], ["4 GHI", "5 JKL", "6 MNO"], ["7 PQRS", "8 TUV", "9 WXYZ"]] {
                row(labels) { [weak self] key in self?.type(key.first == "1" ? "'" : String(key.prefix(1))) }
            }
        } else {
            for letters in ["qwertyuiop", "asdfghjkl"] {
                row(letters.map { session.mode == .english && session.uppercase ? String($0).uppercased() : String($0) }) { [weak self] in self?.type($0.lowercased()) }
            }
            let letters = "zxcvbnm".map { session.mode == .english && session.uppercase ? String($0).uppercased() : String($0) }
            row([session.mode == .english ? "⇧" : "分词"] + letters + ["⌫"]) { [weak self] key in
                guard let self else { return }
                switch key {
                case "⇧": queueEdit { self.session.toggleShift(); self.renderKeys() }
                case "分词": type("'")
                case "⌫": backspace()
                default: type(key.lowercased())
                }
            }
        }
        row([session.symbols ? "返回" : "123", session.mode == .english ? "," : "，", "空格", "⌫", "↵"]) { [weak self] key in
            guard let self else { return }
            switch key {
            case "123", "返回": queueEdit { self.session.toggleSymbols(); self.renderKeys() }
            case "空格": resolve { [weak self] in self?.session.space() ?? "" }
            case "⌫": backspace()
            case "↵": resolve { [weak self] in self?.session.enter() ?? "" }
            default: literal(key)
            }
        }
        let tools = UIStackView(); tools.distribution = .fillEqually; tools.spacing = 3
        let globe = UIButton(type: .system); globe.setTitle("🌐", for: .normal)
        globe.addTarget(self, action: #selector(handleInputModeList(from:with:)), for: .allTouchEvents)
        tools.addArrangedSubview(globe)
        let paste = UIPasteControl(configuration: UIPasteControl.Configuration()); paste.target = self; tools.addArrangedSubview(paste)
        tools.addArrangedSubview(button(session.mode == .english ? "." : "。") { [weak self] in
            guard let self else { return }; literal(session.mode == .english ? "." : "。")
        })
        tools.addArrangedSubview(button(session.mode == .english ? "'" : "原样") { [weak self] in
            guard let self else { return }
            if session.mode == .english { literal("'") }
            else { queueEdit { self.commit(self.session.takeRaw()); self.requestCandidates() } }
        })
        tools.heightAnchor.constraint(equalToConstant: 32).isActive = true; keys.addArrangedSubview(tools)
    }
    private func renderCandidates() {
        let raw = Array(session.composition)
        let cursor = Int(session.cursor)
        preedit.text = raw.isEmpty ? "" : String(raw[..<cursor]) + "│" + String(raw[cursor...])
        clear(candidates); clear(phonetics)
        if session.mode != .english {
            let toggle = button(session.autoCorrection ? "纠错开" : "纠错关") { [weak self] in
                self?.queueEdit {
                    guard let self else { return }
                    self.session.setAutoCorrection(enabled: !self.session.autoCorrection)
                    UserDefaults.standard.set(self.session.autoCorrection, forKey: "pinyinCorrection")
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
            hint.text = candidateLoading ? "正在生成候选…" : session.composition.isEmpty ? "拼音选词 · 横滑查看更多候选" : "暂无候选，可退格修改或点「原样」"
            candidates.addArrangedSubview(hint)
        } else {
            for (index, candidate) in session.candidates.enumerated() {
                let detail = session.mode == .chineseNineKey || !candidate.correction.isEmpty
                let label = detail ? candidate.text + "\n" + candidate.correction + " " + candidate.spelling : candidate.text
                let b = button(label) { [weak self] in
                    guard let self else { return }
                    queueEdit {
                        guard self.session.candidates.indices.contains(index),
                              self.session.candidates[index].isEqual(candidate) else { return }
                        self.commit(self.session.choose(index: Int32(index))); self.requestCandidates()
                    }
                }
                b.titleLabel?.numberOfLines = 2; b.titleLabel?.textAlignment = .center
                b.titleLabel?.font = .systemFont(ofSize: detail ? 13 : 20)
                b.contentEdgeInsets = UIEdgeInsets(top: 0, left: 12, bottom: 0, right: 12)
                candidates.addArrangedSubview(b)
            }
        }
    }
    private func invalidateCandidates() {
        candidateRevision += 1; candidateLoading = false; deferred = nil; pinyin.cancel()
    }
    private func requestCandidates() {
        invalidateCandidates()
        let input = session.composition, mode = session.mode, request = candidateRevision
        let editRevision = session.revision, locks = session.lockSpecification, corrections = session.autoCorrection
        guard !input.isEmpty else { renderCandidates(); return }
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
    }
    private func type(_ value: String) {
        queueEdit {
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
    private func resolve(_ action: @escaping () -> String) {
        queueEdit {
            if self.candidateLoading && !self.session.composition.isEmpty {
                self.deferred = { [weak self] in
                    self?.commit(action()); self?.requestCandidates(); self?.drainEdits()
                }
            } else { self.commit(action()); self.requestCandidates() }
        }
    }
    private func literal(_ text: String) { resolve { [weak self] in self?.session.literal(value: text) ?? "" } }
    private func backspace() {
        queueEdit {
            if self.session.backspace() { self.requestCandidates() }
            else { self.textDocumentProxy.deleteBackward(); self.lastHostContext = self.textDocumentProxy.documentContextBeforeInput; self.refreshTranslation() }
        }
    }
    override func paste(itemProviders: [NSItemProvider]) {
        itemProviders.first?.loadObject(ofClass: NSString.self) { [weak self] object, _ in
            guard let text = object as? String else { return }
            DispatchQueue.main.async { self?.literal(text) }
        }
    }
    private func commit(_ text: String) {
        guard !text.isEmpty else { return }
        committing = true
        textDocumentProxy.insertText(text)
        lastHostContext = textDocumentProxy.documentContextBeforeInput
        committing = false
        refreshTranslation()
    }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated); active = true; lastHostContext = textDocumentProxy.documentContextBeforeInput; refreshTranslation()
    }
    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        if active {
            let context = textDocumentProxy.documentContextBeforeInput
            if !committing && !session.composition.isEmpty && context != lastHostContext {
                editActions.removeAll()
                session.clearComposition(); invalidateCandidates(); renderCandidates()
            }
            lastHostContext = context; refreshTranslation()
        }
    }
    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        height?.constant = size.width > 600 ? 376 : 466
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in self?.renderKeys() }
    }
    override func viewWillDisappear(_ animated: Bool) {
        active = false; revision += 1; pending?.cancel(); engine.unload(); pinyin.close()
        editActions.removeAll()
        session.clearComposition(); invalidateCandidates(); lastSource = ""; source.text = ""; english.text = ""
        super.viewWillDisappear(animated)
    }
    private func refreshTranslation() {
        guard active else { return }
        let sentence = TranslationText.shared.sentence(context: textDocumentProxy.documentContextBeforeInput ?? "")
        guard sentence != lastSource else { return }
        lastSource = sentence; revision += 1
        let request = revision
        pending?.cancel(); engine.cancel(); source.text = sentence; english.text = ""
        guard !sentence.isEmpty else { status.text = "输入中文，停顿后显示英文"; return }
        status.text = "等待停顿…"
        let work = DispatchWorkItem { [weak self] in
            guard let self, active, revision == request else { return }
            status.text = "正在本地翻译…"
            engine.translate(source: sentence) { [weak self] result in
                guard let self, active, revision == request else { return }
                switch result {
                case .success(let output): english.text = output; status.text = "LiteRT CPU · 本地翻译 · 仅供学习"
                case .failure(let error): status.text = error.localizedDescription
                }
            }
        }
        pending = work; DispatchQueue.main.asyncAfter(deadline: .now() + 0.7, execute: work)
    }
}
