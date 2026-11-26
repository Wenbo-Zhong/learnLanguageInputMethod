import UIKit
import Shared

enum IOSPanelAction {
    case settingChanged(String), open(String), close, dictionary, shortcuts, clearLearning
    case commit(String), edit(String)
}

/** UIKit panel presentation; editor commands are dispatched to the keyboard controller. */
final class IOSKeyboardPanels {
    private let panel: UIStackView, prefs: UserDefaults, clips: ClipboardHistory
    private weak var pasteTarget: UIResponder?
    private let skin: () -> IOSKeyboardSkin
    private let button: (String, @escaping () -> Void) -> UIButton
    private let row: ([String], CGFloat, @escaping (String) -> Void) -> UIStackView
    private let action: (IOSPanelAction) -> Void

    init(panel: UIStackView, prefs: UserDefaults, clips: ClipboardHistory, pasteTarget: UIResponder,
         skin: @escaping () -> IOSKeyboardSkin,
         button: @escaping (String, @escaping () -> Void) -> UIButton,
         row: @escaping ([String], CGFloat, @escaping (String) -> Void) -> UIStackView,
         action: @escaping (IOSPanelAction) -> Void) {
        self.panel = panel; self.prefs = prefs; self.clips = clips; self.pasteTarget = pasteTarget
        self.skin = skin; self.button = button; self.row = row; self.action = action
    }

    func render(_ name: String) {
        switch name {
        case "设置": settings()
        case "剪贴板": clipboard()
        case "编辑": edit()
        case "符号", "表情": symbols(emoji: name == "表情")
        default: break
        }
    }

    func notice(_ text: String) {
        let label = UILabel(); label.text = text; label.numberOfLines = 0
        label.font = .systemFont(ofSize: 13); label.textColor = skin().muted; panel.addArrangedSubview(label)
    }
    func settingButton(_ title: String, action: @escaping () -> Void) {
        let b = button(title, action); b.heightAnchor.constraint(equalToConstant: 38).isActive = true
        panel.addArrangedSubview(b)
    }
    private func toggle(_ key: String, title: String? = nil) {
        let setting = KeyboardSettings.shared.get(key: key, platform: .ios)
        let line = UIStackView(); line.alignment = .center
        let label = UILabel(); label.text = title ?? setting.title
        label.font = .systemFont(ofSize: 14); label.textColor = skin().ink
        let control = UISwitch(); control.isOn = prefs.bool(forKey: key); control.accessibilityLabel = label.text
        control.addAction(UIAction { [weak self, weak control] _ in
            guard let self, let control else { return }
            prefs.set(control.isOn, forKey: key); action(.settingChanged(key))
        }, for: .valueChanged)
        line.addArrangedSubview(label); line.addArrangedSubview(control); panel.addArrangedSubview(line)
    }
    private func settings() {
        cycle("translationModel")
        if prefs.string(forKey: "translationModel") == "opus" { toggle("gpuTranslation") }
        for key in ["translation", "autoCorrection", "learning", "prediction"] { toggle(key) }
        toggle("mixed", title: "中文建议英文／数字原文"); toggle("inline", title: "输入框内联拼音"); toggle("traditional")
        for (i, title) in KeyboardSettings.shared.fuzzyGroups.enumerated() {
            let line = UIStackView(); let label = UILabel(); label.text = "模糊音 \(title)"
            label.font = .systemFont(ofSize: 14); label.textColor = skin().ink
            let control = UISwitch(); control.isOn = prefs.integer(forKey: "fuzzyGroups") & (1 << i) != 0
            control.accessibilityLabel = label.text
            control.addAction(UIAction { [weak self, weak control] _ in
                guard let self, let control else { return }
                let old = prefs.integer(forKey: "fuzzyGroups")
                prefs.set(control.isOn ? old | (1 << i) : old & ~(1 << i), forKey: "fuzzyGroups")
            }, for: .valueChanged)
            line.addArrangedSubview(label); line.addArrangedSubview(control); panel.addArrangedSubview(line)
        }
        settingButton("个人词典：添加、编辑、删除") { [weak self] in self?.action(.dictionary) }
        settingButton("短语缩写：添加、编辑、删除") { [weak self] in self?.action(.shortcuts) }
        settingButton("清除个人词典和学习记录") { [weak self] in self?.action(.clearLearning) }
        for key in ["englishSuggestions", "englishCorrection", "autoCap", "doubleSpace", "numberRow",
                    "vibration", "sound", "preview", "keyBorder"] { toggle(key) }
        cycle("oneHand"); cycle("theme")
        slider("keyHeight"); slider("keyFont"); slider("candidateFont")
        cycle("handwritingSpeed"); slider("handwritingThickness"); slider("longPress")
        settingButton("清除剪贴板历史") { [weak self] in self?.clips.clear(); self?.notice("已清除剪贴板历史") }
        notice("设置保存在键盘扩展内。iOS 剪贴板需系统许可；键盘扩展不能访问麦克风，语音请使用系统听写。")
    }
    private func cycle(_ key: String) {
        let setting = KeyboardSettings.shared.get(key: key, platform: .ios), options = setting.options
        let value = setting.validateChoice(value: prefs.string(forKey: key) ?? setting.choiceDefault)
        let index = options.firstIndex { $0.value == value } ?? 0
        settingButton("\(setting.title)：\(options[index].label)") { [weak self] in
            guard let self else { return }
            prefs.set(options[(index + 1) % options.count].value, forKey: key)
            if key == "translationModel" { action(.settingChanged(key)) }
            action(.open("设置"))
        }
    }
    private func slider(_ key: String) {
        let setting = KeyboardSettings.shared.get(key: key, platform: .ios)
        let value = setting.validateNumber(value: Int32(clamping: prefs.integer(forKey: key)))
        let label = UILabel(); label.font = .systemFont(ofSize: 14); label.textColor = skin().ink
        label.text = "\(setting.title)：\(value)"; panel.addArrangedSubview(label)
        let slider = UISlider(); slider.minimumValue = Float(setting.minimum); slider.maximumValue = Float(setting.maximum)
        slider.value = Float(value); slider.accessibilityLabel = setting.title
        slider.addAction(UIAction { [weak self, weak slider] _ in
            guard let self, let slider else { return }
            let value = setting.validateNumber(value: Int32(slider.value.rounded()))
            prefs.set(Int(value), forKey: key); label.text = "\(setting.title)：\(value)"
        }, for: .valueChanged)
        panel.addArrangedSubview(slider)
    }
    private func addPasteControl() {
        let paste = UIPasteControl(configuration: .init()); paste.target = pasteTarget; panel.addArrangedSubview(paste)
    }
    private func clipboard() {
        notice("点系统粘贴按钮读取文字；未固定文字保留 1 小时。"); addPasteControl()
        let entries = clips.entries()
        for entry in entries {
            settingButton((entry.pinned ? "📌 " : "") + entry.text) { [weak self] in self?.action(.commit(entry.text)) }
            panel.addArrangedSubview(row([entry.pinned ? "取消固定" : "固定", "删除"], 30) { [weak self] command in
                guard let self else { return }
                if command == "删除" { clips.delete(entry.text) } else { clips.pin(entry.text) }
                action(.open("剪贴板"))
            })
        }
        if entries.isEmpty { notice("暂无剪贴板历史") }
    }
    private func edit() {
        notice("复制和剪切请先在输入框选择文字。")
        panel.addArrangedSubview(row(["←", "→", "复制", "剪切"], 42) { [weak self] in self?.action(.edit($0)) })
        addPasteControl()
        panel.addArrangedSubview(row(["按词删除", "撤销"], 42) { [weak self] in self?.action(.edit($0)) })
    }
    private func symbols(emoji: Bool) {
        let groups = KeyboardSymbols.shared.categories(emoji: emoji).map {
            ($0, KeyboardSymbols.shared.values(emoji: emoji, category: $0, recent: "").joined(separator: " "))
        }
        let values = UIStackView(); values.axis = .vertical; values.spacing = 4
        let key = emoji ? "recentEmoji" : "recentSymbols"
        let render: (String) -> Void = { [weak self, weak values] text in
            guard let self, let values else { return }
            values.arrangedSubviews.forEach { values.removeArrangedSubview($0); $0.removeFromSuperview() }
            let symbols = text.split(separator: " ").map(String.init)
            for i in stride(from: 0, to: symbols.count, by: 6) {
                values.addArrangedSubview(row(Array(symbols[i..<min(i + 6, symbols.count)]), 42) { [weak self] symbol in
                    guard let self else { return }; action(.commit(symbol))
                    let recent = prefs.stringArray(forKey: key) ?? []
                    prefs.set(KeyboardSymbols.shared.remember(value: symbol, previous: recent.joined(separator: " "))
                        .split(separator: " ").map(String.init), forKey: key)
                })
            }
        }
        let tabs = UIStackView(); tabs.distribution = .fillEqually
        for (name, text) in groups {
            tabs.addArrangedSubview(button(name) { [weak self] in
                render(name == "最近" ? self?.prefs.stringArray(forKey: key)?.joined(separator: " ") ?? "" : text)
            })
        }
        panel.addArrangedSubview(tabs)
        if emoji {
            panel.addArrangedSubview(row(KeyboardSymbols.shared.keywords(), 30) {
                render(KeyboardSymbols.shared.keywordValues(keyword: $0).joined(separator: " "))
            })
        }
        panel.addArrangedSubview(values); render(groups[1].1)
    }
}
