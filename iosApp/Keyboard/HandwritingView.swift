import UIKit

final class HandwritingEngine {
    private let queue = DispatchQueue(label: "dev.learnime.handwriting", qos: .userInitiated)
    private var handle: UnsafeMutableRawPointer?
    func recognize(_ points: [Int32], completion: @escaping ([String]) -> Void) {
        queue.async { [self] in
            if handle == nil, let file = Bundle.main.url(forResource: "handwriting-zh_CN", withExtension: "model", subdirectory: "extras") {
                handle = file.path.withCString { li_handwriting_create($0) }
            }
            var words: [String] = []
            if let handle, let text = points.withUnsafeBufferPointer({ li_handwriting_recognize(handle, $0.baseAddress, Int32($0.count)) }) {
                words = String(cString: text).split(separator: "\n").map(String.init)
                li_handwriting_free(text)
            }
            DispatchQueue.main.async { completion(words) }
        }
    }
    func close() {
        queue.async { [self] in if let handle { li_handwriting_destroy(handle); self.handle = nil } }
    }
    deinit { if let handle { li_handwriting_destroy(handle) } }
}

final class HandwritingView: UIView {
    var began: (() -> Void)?
    var changed: (([Int32]) -> Void)?
    var ink = UIColor.label, lineWidth: CGFloat = 3
    private var strokes: [[CGPoint]] = []
    var hasStrokes: Bool { !strokes.isEmpty }
    func clear() { strokes = []; setNeedsDisplay() }
    func undo() { if hasStrokes { strokes.removeLast() }; setNeedsDisplay(); changed?(points()) }
    func points() -> [Int32] {
        strokes.enumerated().flatMap { index, stroke in stroke.flatMap {
            [Int32(index), Int32(1000 * min(max($0.x, 0), bounds.width) / max(bounds.width, 1)),
             Int32(1000 * min(max($0.y, 0), bounds.height) / max(bounds.height, 1))]
        } }
    }
    override func draw(_ rect: CGRect) {
        ink.setStroke()
        for stroke in strokes {
            let path = UIBezierPath(); path.lineWidth = lineWidth; path.lineCapStyle = .round; path.lineJoinStyle = .round
            for (i, p) in stroke.enumerated() { if i == 0 { path.move(to: p) } else { path.addLine(to: p) } }
            path.stroke()
        }
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first, strokes.count < 64 else { return }
        began?(); strokes.append([touch.location(in: self)]); setNeedsDisplay()
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first, hasStrokes, strokes.reduce(0, { $0 + $1.count }) < 8000 else { return }
        for sample in event?.coalescedTouches(for: touch) ?? [touch] { strokes[strokes.count - 1].append(sample.location(in: self)) }
        setNeedsDisplay()
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard hasStrokes, let touch = touches.first else { return }
        strokes[strokes.count - 1].append(touch.location(in: self)); setNeedsDisplay(); changed?(points())
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { undo() }
}
