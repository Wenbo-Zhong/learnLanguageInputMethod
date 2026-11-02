import Foundation
import Shared

final class PinyinEngine {
    private let queue = DispatchQueue(label: "dev.learnime.pinyin", qos: .userInitiated)
    private let lock = NSLock()
    private var epoch = 0
    private var handle: OpaquePointer?

    func cancel() { lock.lock(); epoch += 1; lock.unlock() }
    func search(input: String, nine: Bool, locks: String, corrections: Bool,
                completion: @escaping (Result<[PinyinCandidate], Error>) -> Void) {
        lock.lock(); let request = epoch; lock.unlock()
        queue.async { [self] in
            lock.lock(); let current = epoch == request; lock.unlock()
            guard current else { return }
            if handle == nil, let url = Bundle.main.url(forResource: "sc", withExtension: "dict", subdirectory: "libime") {
                let user = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                try? FileManager.default.createDirectory(at: user, withIntermediateDirectories: true)
                handle = url.path.withCString { path in
                    user.appendingPathComponent("libime-user-v1.dat").path.withCString {
                        li_pinyin_create_with_user(path, $0)
                    }
                }
            }
            let result: Result<[PinyinCandidate], Error>
            if let handle {
                let text = input.withCString { raw in
                    locks.withCString { fixed in
                        String(cString: li_pinyin_search_advanced(handle, raw, nine ? 1 : 0, fixed, corrections ? 1 : 0)!)
                    }
                }
                result = .success(PinyinResults.shared.parse(serialized: text))
            } else { result = .failure(DecoderError.unavailable) }
            lock.lock(); let valid = epoch == request; lock.unlock()
            guard valid else { return }
            DispatchQueue.main.async { completion(result) }
        }
    }
    func close() {
        cancel()
        queue.async { [self] in if let handle { li_pinyin_destroy(handle); self.handle = nil } }
    }
    deinit { if let handle { li_pinyin_destroy(handle) } }
    enum DecoderError: LocalizedError {
        case unavailable
        var errorDescription: String? { "拼音词库加载失败" }
    }
}
