import Foundation
import Shared

final class PinyinEngine {
    private let queue = DispatchQueue(label: "dev.learnime.pinyin", qos: .userInitiated)
    private let lock = NSLock()
    private var epoch = 0
    private var handle: OpaquePointer?
    private func load() {
        guard handle == nil, let url = Bundle.main.url(forResource: "sc", withExtension: "dict", subdirectory: "libime") else { return }
        let user = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: user, withIntermediateDirectories: true)
        handle = url.path.withCString { path in
            user.appendingPathComponent("libime-user-v1.dat").path.withCString { li_pinyin_create_with_user(path, $0) }
        }
    }

    func cancel() { lock.lock(); epoch += 1; lock.unlock() }
    func search(input: String, nine: Bool, locks: String, corrections: Bool,
                completion: @escaping (Result<[PinyinCandidate], Error>) -> Void) {
        lock.lock(); let request = epoch; lock.unlock()
        queue.async { [self] in
            lock.lock(); let current = epoch == request; lock.unlock()
            guard current else { return }
            load()
            let result: Result<[PinyinCandidate], Error>
            if let handle {
                li_pinyin_configure(handle, UInt32(UserDefaults.standard.integer(forKey: "fuzzyGroups")))
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
    func select(_ candidate: PinyinCandidate, learn: Bool, completion: @escaping (Bool) -> Void) {
        queue.async { [self] in
            let ok = handle.map { h in candidate.path.withCString {
                String(cString: li_pinyin_select(h, UInt64(bitPattern: candidate.token), candidate.nativeIndex, $0, learn ? 1 : 0)) == candidate.text
            } } ?? false
            if learn, let handle {
                let error = String(cString: li_pinyin_learning_error(handle))
                if !error.isEmpty { NSLog("LEARNIME_LIBIME_TRACE learning pending: %@", error) }
            }
            DispatchQueue.main.async { completion(ok) }
        }
    }
    func predict(_ history: String, completion: @escaping ([String]) -> Void) {
        queue.async { [self] in
            load()
            let words = handle.map { h in history.withCString { String(cString: li_pinyin_predict(h, $0)) } } ?? ""
            DispatchQueue.main.async { completion(words.split(separator: "\n").map(String.init)) }
        }
    }
    func words(completion: @escaping ([String]) -> Void) {
        queue.async { [self] in
            load()
            let words = handle.map { String(cString: li_pinyin_user_words($0)) } ?? ""
            DispatchQueue.main.async { completion(words.split(separator: "\n").map(String.init)) }
        }
    }
    func word(spelling: String, text: String, remove: Bool = false, completion: @escaping (Bool) -> Void) {
        queue.async { [self] in
            load()
            let ok = handle.map { h in spelling.withCString { p in text.withCString {
                li_pinyin_user_word(h, p, $0, remove ? 1 : 0) != 0
            } } } ?? false
            DispatchQueue.main.async { completion(ok) }
        }
    }
    func clearLearning(completion: @escaping (Bool) -> Void) {
        queue.async { [self] in
            load(); let ok = handle.map { li_pinyin_clear_learning($0) != 0 } ?? false
            DispatchQueue.main.async { completion(ok) }
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
