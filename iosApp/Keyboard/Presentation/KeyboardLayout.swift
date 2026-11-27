import UIKit

/// UIKit structure and constraints. Input actions and semantic colors are supplied by the controller.
final class IOSKeyboardLayout {
    let source = UILabel(), english = UILabel(), status = UILabel(), preedit = UILabel(), direction = UILabel()
    let candidates = UIStackView(), keys = UIStackView(), phonetics = UIStackView()
    let root = UIStackView(), body = UIStackView(), toolbar = UIStackView()
    let translationCard = UIStackView(), candidateBar = UIStackView(), expanded = UIStackView()
    let panel = UIStackView(), form = UIStackView()
    let candidateScroll = UIScrollView(), phoneticScroll = UIScrollView()
    let panelScroll = UIScrollView(), expandedScroll = UIScrollView()
    var height: NSLayoutConstraint?
    var bodyWidth: NSLayoutConstraint?

    func mount(in view: UIView, expand: UIButton) {
        root.axis = .horizontal; root.alignment = .top
        root.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(root)
        body.axis = .vertical; body.spacing = 6; root.addArrangedSubview(body)
        height = view.heightAnchor.constraint(equalToConstant: 440)
        height?.priority = .defaultHigh; height?.isActive = true
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 5),
            root.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -5),
            root.topAnchor.constraint(equalTo: view.topAnchor, constant: 5),
            root.bottomAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -5)
        ])
        bodyWidth = body.widthAnchor.constraint(equalTo: root.widthAnchor); bodyWidth?.isActive = true
        translationCard.axis = .vertical; translationCard.spacing = 4
        translationCard.isLayoutMarginsRelativeArrangement = true
        translationCard.layoutMargins = UIEdgeInsets(top: 6, left: 10, bottom: 6, right: 10)
        body.addArrangedSubview(translationCard)
        let header = UIStackView()
        direction.text = "中 ⇄ EN"; direction.font = .systemFont(ofSize: 11, weight: .semibold)
        header.addArrangedSubview(direction); header.addArrangedSubview(status); status.textAlignment = .right
        translationCard.addArrangedSubview(header)
        source.font = .systemFont(ofSize: 13); source.numberOfLines = 1
        english.font = .systemFont(ofSize: 16); english.numberOfLines = 2
        english.text = "输入中文，看看英文怎么说"
        status.font = .systemFont(ofSize: 11); status.text = "离线对照"
        preedit.font = .systemFont(ofSize: 14); preedit.numberOfLines = 1
        translationCard.addArrangedSubview(source); translationCard.addArrangedSubview(english)
        body.addArrangedSubview(preedit); source.isHidden = true
        preedit.heightAnchor.constraint(equalToConstant: 22).isActive = true
        horizontal(candidates, in: candidateScroll, height: 44, spacing: 6)
        candidateScroll.accessibilityIdentifier = "candidateStrip"
        candidateBar.axis = .horizontal; candidateBar.addArrangedSubview(candidateScroll)
        expand.widthAnchor.constraint(equalToConstant: 48).isActive = true
        candidateBar.addArrangedSubview(expand); body.addArrangedSubview(candidateBar)
        horizontal(phonetics, in: phoneticScroll, height: 32, spacing: 5)
        body.addArrangedSubview(phoneticScroll)
        vertical(expanded, in: expandedScroll, height: 240, spacing: 4)
        expandedScroll.isHidden = true; body.addArrangedSubview(expandedScroll)
        toolbar.axis = .horizontal; toolbar.spacing = 3
        toolbar.heightAnchor.constraint(equalToConstant: 34).isActive = true
        body.addArrangedSubview(toolbar)
        form.axis = .vertical; form.spacing = 4; form.isHidden = true; body.addArrangedSubview(form)
        vertical(panel, in: panelScroll, height: 260, spacing: 6)
        panelScroll.accessibilityIdentifier = "toolPanel"
        panelScroll.isHidden = true; body.addArrangedSubview(panelScroll)
        keys.axis = .vertical; keys.spacing = 4; body.addArrangedSubview(keys)
    }

    private func horizontal(_ stack: UIStackView, in scroll: UIScrollView, height: CGFloat, spacing: CGFloat) {
        stack.axis = .horizontal; stack.spacing = spacing
        content(stack, in: scroll, height: height, horizontal: true)
        scroll.showsHorizontalScrollIndicator = false
    }

    private func vertical(_ stack: UIStackView, in scroll: UIScrollView, height: CGFloat, spacing: CGFloat) {
        stack.axis = .vertical; stack.spacing = spacing
        content(stack, in: scroll, height: height, horizontal: false)
    }

    private func content(_ stack: UIStackView, in scroll: UIScrollView, height: CGFloat, horizontal: Bool) {
        stack.translatesAutoresizingMaskIntoConstraints = false; scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            horizontal ? stack.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor) :
                stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor),
            scroll.heightAnchor.constraint(equalToConstant: height)
        ])
    }

    func row(_ labels: [String], height: CGFloat, weights: [CGFloat]? = nil, button: (String) -> UIButton) -> UIStackView {
        let row = UIStackView(); row.distribution = .fill; row.alignment = .fill; row.spacing = weights == nil ? 4 : 6
        if labels.count == 9, labels.allSatisfy({ $0.count == 1 && $0.first?.isLetter == true }) {
            row.isLayoutMarginsRelativeArrangement = true
            row.layoutMargins = UIEdgeInsets(top: 0, left: 14, bottom: 0, right: 14)
        }
        var first: UIButton?
        for (index, label) in labels.enumerated() {
            let key = button(label); row.addArrangedSubview(key)
            if let first { key.widthAnchor.constraint(equalTo: first.widthAnchor, multiplier: weights?[index] ?? (label == "空格" ? 4 : 1)).isActive = true }
            else { first = key }
        }
        row.heightAnchor.constraint(equalToConstant: height).isActive = true
        return row
    }

    func nineGrid(rows: [[UIButton]], punctuation: [UIButton], utilities: [UIButton], keyHeight: CGFloat) -> UIStackView {
        let grid = UIStackView(); grid.spacing = 6; grid.alignment = .fill
        func column(_ buttons: [UIButton]) -> UIStackView {
            let stack = UIStackView(arrangedSubviews: buttons)
            stack.axis = .vertical; stack.spacing = 6; stack.distribution = .fillEqually
            return stack
        }
        let left = column(punctuation), right = column(utilities)
        let center = UIStackView(); center.axis = .vertical; center.spacing = 6
        for buttons in rows {
            let row = UIStackView(arrangedSubviews: buttons)
            row.distribution = .fillEqually; row.spacing = 6
            row.heightAnchor.constraint(equalToConstant: keyHeight).isActive = true
            center.addArrangedSubview(row)
        }
        grid.addArrangedSubview(left); grid.addArrangedSubview(center); grid.addArrangedSubview(right)
        left.widthAnchor.constraint(equalTo: grid.widthAnchor, multiplier: 0.105).isActive = true
        right.widthAnchor.constraint(equalTo: grid.widthAnchor, multiplier: 0.14).isActive = true
        grid.accessibilityIdentifier = "nineKeyGrid"
        return grid
    }
}
