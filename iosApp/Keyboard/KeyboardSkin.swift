import UIKit

/** iOS semantic colors and SF Symbols. No input/business decisions live here. */
struct IOSKeyboardSkin {
    enum NineKeyRole { case letters, punctuation, utility, space, enter }
    let card, key, function, primary, selected, ink, muted: UIColor
    let nineKey, nineFunction: UIColor
    let keyCorner: CGFloat = 7
    let cardCorner: CGFloat = 12
    init(dark: Bool, followsSystem: Bool = false) {
        let traits = UITraitCollection(userInterfaceStyle: dark ? .dark : .light)
        func color(_ value: UIColor) -> UIColor { followsSystem ? value : value.resolvedColor(with: traits) }
        func adaptive(light: UIColor, dark: UIColor) -> UIColor {
            color(UIColor { environment in
                (environment.userInterfaceStyle == .dark ? dark : light).resolvedColor(with: environment)
            })
        }
        card = color(.secondarySystemBackground)
        key = adaptive(light: .systemBackground, dark: .systemGray3)
        function = adaptive(light: .systemGray4, dark: .systemGray5)
        primary = color(.systemBlue)
        selected = color(.systemBackground)
        ink = color(.label)
        muted = color(.secondaryLabel)
        nineKey = key
        nineFunction = adaptive(light: .systemGray3, dark: .systemGray5)
    }
    func symbol(_ title: String) -> UIImage? {
        let names = ["⌫":"delete.left", "⇧":"shift", "↵":"return"]
        return names[title].flatMap { UIImage(systemName: $0) }
    }
    func toolbarSymbol(_ title: String) -> UIImage? {
        let names = ["剪贴板": "doc.on.clipboard", "编辑": "cursorarrow.rays", "符号": "textformat.123",
                     "表情": "face.smiling", "手写": "pencil.tip.crop.circle", "设置": "gearshape"]
        return names[title].flatMap {
            UIImage(systemName: $0, withConfiguration: UIImage.SymbolConfiguration(pointSize: 17, weight: .regular))
        }
    }
    func button(_ title: String, font: CGFloat, border: Bool) -> UIButton {
        let button = UIButton(type: .system); button.setTitle(title, for: .normal)
        button.setTitleColor(ink, for: .normal)
        button.backgroundColor = key; button.layer.cornerRadius = keyCorner
        button.titleLabel?.font = .systemFont(ofSize: title.count == 1 ? font : 14)
        button.titleLabel?.adjustsFontSizeToFitWidth = true; button.titleLabel?.minimumScaleFactor = 0.65
        button.accessibilityLabel = title
        if border { button.layer.borderWidth = 0.5; button.layer.borderColor = muted.withAlphaComponent(0.3).cgColor }
        return button
    }

    func styleNineKey(_ button: UIButton, title: String, digit: String? = nil,
                      role: NineKeyRole, font: CGFloat, border: Bool) {
        let surface: UIColor
        switch role {
        case .letters, .space: surface = nineKey
        case .punctuation: surface = .clear
        case .utility, .enter: surface = nineFunction
        }
        button.backgroundColor = surface
        button.layer.cornerRadius = 5
        button.layer.cornerCurve = .continuous
        button.layer.borderWidth = border && role != .punctuation ? 0.5 : 0
        button.layer.borderColor = muted.withAlphaComponent(0.25).cgColor
        button.layer.shadowColor = UIColor.black.cgColor
        button.layer.shadowOffset = CGSize(width: 0, height: 1)
        button.layer.shadowRadius = 0.5
        let shadow: Float = role == .punctuation ? 0 : 0.16
        button.layer.shadowOpacity = shadow
        button.setTitleColor(ink, for: .normal)
        button.tintColor = ink
        button.imageView?.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 22, weight: .regular)
        let size: CGFloat = role == .letters && digit != nil ? font + 1 :
            role == .punctuation ? 24 : role == .space ? 18 : 16
        button.titleLabel?.font = .systemFont(ofSize: size, weight: .regular)
        if role == .space { button.setTitle("空格", for: .normal) }
        if role == .utility, title == "⌫" || title == "清除" {
            button.setTitle(nil, for: .normal)
            button.setImage(UIImage(systemName: title == "⌫" ? "delete.left" : "arrow.counterclockwise"), for: .normal)
        }
        if role == .enter, title == "↵" {
            button.setTitle("换行", for: .normal); button.setImage(nil, for: .normal)
        } else if role == .enter {
            button.backgroundColor = primary; button.setTitleColor(.white, for: .normal); button.tintColor = .white
        }
        if let digit {
            button.setAttributedTitle(NSAttributedString(string: title, attributes: [
                .font: UIFont.systemFont(ofSize: size), .kern: 0.7, .foregroundColor: ink
            ]), for: .normal)
            button.contentEdgeInsets = UIEdgeInsets(top: 7, left: 2, bottom: 0, right: 2)
            let number = UILabel(); number.text = digit
            number.font = .systemFont(ofSize: 10, weight: .medium)
            number.textColor = muted; number.isAccessibilityElement = false
            number.translatesAutoresizingMaskIntoConstraints = false; button.addSubview(number)
            NSLayoutConstraint.activate([
                number.topAnchor.constraint(equalTo: button.topAnchor, constant: 4),
                number.trailingAnchor.constraint(equalTo: button.trailingAnchor, constant: -7)
            ])
        }
        let idle = button.backgroundColor
        button.addAction(UIAction { [weak button] _ in
            button?.backgroundColor = role == .enter && title != "↵" ? primary.withAlphaComponent(0.75) : nineFunction
            button?.layer.shadowOpacity = 0
        }, for: [.touchDown, .touchDragEnter])
        button.addAction(UIAction { [weak button] _ in
            button?.backgroundColor = idle; button?.layer.shadowOpacity = shadow
        }, for: [.touchUpInside, .touchUpOutside, .touchCancel, .touchDragExit])
    }
}
