import UIKit
import Shared

/** UIKit strokes and native scheduling; editor operations remain injected by the controller. */
final class IOSHandwritingInput {
    private let state = HandwritingSession()
    private let engine = HandwritingEngine()
    private let prefs: UserDefaults
    private let active: () -> Bool
    private let button: (String, @escaping () -> Void) -> UIButton
    private let output: (String) -> String
    private let commit: (String) -> Void
    private let syncMarked: () -> Void
    private let queueEdit: (@escaping () -> Void) -> Void
    private var pending: DispatchWorkItem?
    private var board: HandwritingView?
    private var choices: UIStackView?
    var word: String { state.marked }
    var candidate: String { state.candidate }
    var hasStrokes: Bool { board?.hasStrokes == true }

    init(prefs: UserDefaults, active: @escaping () -> Bool,
         button: @escaping (String, @escaping () -> Void) -> UIButton,
         output: @escaping (String) -> String, commit: @escaping (String) -> Void,
         syncMarked: @escaping () -> Void, queueEdit: @escaping (@escaping () -> Void) -> Void) {
        self.prefs = prefs; self.active = active; self.button = button; self.output = output
        self.commit = commit; self.syncMarked = syncMarked; self.queueEdit = queueEdit
    }
    private func clearChoices() {
        choices?.arrangedSubviews.forEach { choices?.removeArrangedSubview($0); $0.removeFromSuperview() }
    }
    func undo() { board?.undo() }
    func finish() {
        let text = state.finish()
        pending?.cancel(); pending = nil
        if !text.isEmpty { commit(text) }
        board?.clear(); clearChoices()
    }
    func clear() { state.clear(); pending?.cancel(); pending = nil; board?.clear(); clearChoices() }
    func close() { clear(); engine.close(); board = nil; choices = nil }

    func resolve(_ completion: @escaping (Bool) -> Void) {
        guard !state.hasText, let board, board.hasStrokes else {
            finish(); completion(true); return
        }
        // The engine queue waits for running recognition; the controller holds later edits.
        let request = state.revision, points = board.points()
        board.isUserInteractionEnabled = false
        engine.recognize(points) { [weak self, weak board] words in
            board?.isUserInteractionEnabled = true
            guard let self else { return }
            guard active(), state.isCurrent(request: request), let first = words.first else {
                completion(false); return
            }
            state.accept(request: request, text: output(first)); finish(); completion(true)
        }
    }

    func render(in keys: UIStackView, ink: UIColor, height: CGFloat) {
        state.invalidateRequests(); pending?.cancel(); pending = nil
        let strip = UIStackView(); strip.spacing = 4; choices = strip
        let scroll = UIScrollView(); scroll.addSubview(strip); strip.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            strip.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            strip.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            strip.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            strip.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            strip.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor),
            scroll.heightAnchor.constraint(equalToConstant: 42)
        ])
        keys.addArrangedSubview(scroll)
        let board = HandwritingView(); self.board = board
        board.backgroundColor = .clear; board.isOpaque = false; board.ink = ink
        board.layer.cornerRadius = 12; board.lineWidth = CGFloat(prefs.integer(forKey: "handwritingThickness"))
        board.accessibilityLabel = "中文手写画板"
        board.heightAnchor.constraint(equalToConstant: height).isActive = true; keys.addArrangedSubview(board)
        board.began = { [weak self, weak strip] in
            guard let self else { return }; pending?.cancel()
            let previous = state.beginStroke()
            if !previous.isEmpty { commit(previous); self.board?.clear() }
            strip?.arrangedSubviews.forEach { strip?.removeArrangedSubview($0); $0.removeFromSuperview() }
        }
        board.changed = { [weak self, weak board, weak strip] points in
            guard let self, let board, let strip else { return }; pending?.cancel()
            let request = state.beginRecognition(hasStrokes: !points.isEmpty); clearChoices()
            guard !points.isEmpty else { return }
            engine.recognize(points) { [weak self] words in
                guard let self, active(), state.isCurrent(request: request) else { return }
                state.accept(request: request, text: words.first.map(output) ?? "")
                for word in words {
                    let b = button(output(word)) { [weak self] in
                        guard let self else { return }
                        queueEdit { [weak self] in
                            guard let self, active(), state.isCurrent(request: request) else { return }
                            state.accept(request: request, text: output(word)); finish()
                        }
                    }
                    b.widthAnchor.constraint(equalToConstant: 52).isActive = true; strip.addArrangedSubview(b)
                }
                guard !words.isEmpty else { return }
                let work = DispatchWorkItem { [weak self] in
                    guard let self, active(), state.mark(request: request) else { return }
                    syncMarked(); board.clear()
                }
                pending = work
                let delay = Double(KeyboardPolicy.shared.handwritingDelay(speed: prefs.string(forKey: "handwritingSpeed") ?? "normal")) / 1000
                DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
            }
        }
    }
}
