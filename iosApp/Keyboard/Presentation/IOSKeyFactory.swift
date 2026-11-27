import UIKit

/// Owns native key feedback, preview and held-key lifetime.
final class IOSKeyFactory: NSObject {
    private let prefs: UserDefaults
    private let skin: () -> IOSKeyboardSkin
    private let host: () -> UIView?
    private let literal: (String) -> Void
    private let backspace: () -> Void
    private var deleteTimer: Timer?
    private var preview: UILabel?
    init(prefs: UserDefaults, skin: @escaping () -> IOSKeyboardSkin, host: @escaping () -> UIView?,
         literal: @escaping (String) -> Void, backspace: @escaping () -> Void) {
        self.prefs = prefs; self.skin = skin; self.host = host
        self.literal = literal; self.backspace = backspace
    }
    func button(_ title: String, action: @escaping () -> Void) -> UIButton {
        let button = skin().button(title, font: CGFloat(prefs.integer(forKey: "keyFont")), border: prefs.bool(forKey: "keyBorder"))
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
        if title == "⌫" { button.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(deleteHeld(_:)))) }
        if title.count == 1, prefs.bool(forKey: "preview") {
            button.addAction(UIAction { [weak self, weak button] _ in
                guard let self, let button, let view = host() else { return }
                preview?.removeFromSuperview()
                let label = UILabel(); label.text = title; label.textAlignment = .center
                label.font = .systemFont(ofSize: 28); label.backgroundColor = skin().selected; label.textColor = skin().ink
                label.layer.cornerRadius = 8; label.clipsToBounds = true
                let frame = button.convert(button.bounds, to: view)
                label.frame = CGRect(x: frame.midX - 22, y: max(0, frame.minY - 50), width: 44, height: 48)
                view.addSubview(label); preview = label
            }, for: .touchDown)
            button.addAction(UIAction { [weak self] _ in self?.preview?.removeFromSuperview() }, for: [.touchUpInside, .touchUpOutside, .touchCancel])
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
        } else if [.ended, .cancelled, .failed].contains(gesture.state) { deleteTimer?.invalidate(); deleteTimer = nil }
    }
    func reset() { deleteTimer?.invalidate(); deleteTimer = nil; preview?.removeFromSuperview(); preview = nil }
    deinit { deleteTimer?.invalidate() }
}
