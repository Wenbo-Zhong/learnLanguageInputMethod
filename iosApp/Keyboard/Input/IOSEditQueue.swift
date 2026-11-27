import Foundation

/// Main-thread adapter: preserves ordered intent while a native candidate operation completes.
final class IOSEditQueue {
    private(set) var epoch: UInt64 = 0
    var deferred: (() -> Void)?
    private var actions: [() -> Void] = []
    private var running = false
    func enqueue(_ action: @escaping () -> Void) { actions.append(action); drain() }
    func drain() {
        guard !running, deferred == nil else { return }
        running = true
        defer { running = false }
        while !actions.isEmpty && deferred == nil { actions.removeFirst()() }
    }
    func reset() { epoch &+= 1; actions.removeAll(); deferred = nil }
}
