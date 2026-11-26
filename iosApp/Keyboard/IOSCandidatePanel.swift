import UIKit
import Shared

/** Native candidate presentation uses one captured candidate for both layouts. */
final class IOSCandidatePanel {
    private let button: (String, @escaping () -> Void) -> UIButton
    private weak var menuDelegate: UIContextMenuInteractionDelegate?
    init(menuDelegate: UIContextMenuInteractionDelegate,
         button: @escaping (String, @escaping () -> Void) -> UIButton) {
        self.menuDelegate = menuDelegate; self.button = button
    }
    func render(_ values: [PinyinCandidate], in strip: UIStackView, expanded: UIStackView,
                nineKey: Bool, isExpanded: Bool, font: CGFloat, selected: UIColor,
                output: (String) -> String, choose: @escaping (Int, PinyinCandidate) -> Void) {
        for (index, candidate) in values.prefix(12).enumerated() {
            let detail = !nineKey && !candidate.correction.isEmpty
            let text = output(candidate.text)
            let label = detail ? text + "\n" + candidate.correction + " " + candidate.spelling : text
            let b = button(label) { choose(index, candidate) }
            b.titleLabel?.numberOfLines = 2; b.titleLabel?.textAlignment = .center
            b.titleLabel?.font = .systemFont(ofSize: detail ? 12 : font)
            b.backgroundColor = index == 0 ? selected : .clear
            b.contentEdgeInsets = UIEdgeInsets(top: 0, left: 12, bottom: 0, right: 12)
            b.tag = index
            if let menuDelegate { b.addInteraction(UIContextMenuInteraction(delegate: menuDelegate)) }
            strip.addArrangedSubview(b)
        }
        guard isExpanded else { return }
        for start in stride(from: 0, to: values.count, by: 3) {
            let line = UIStackView(); line.distribution = .fillEqually; line.spacing = 4
            for index in start..<min(start + 3, values.count) {
                let candidate = values[index]
                line.addArrangedSubview(button(output(candidate.text)) { choose(index, candidate) })
            }
            line.heightAnchor.constraint(equalToConstant: 38).isActive = true; expanded.addArrangedSubview(line)
        }
    }
}
