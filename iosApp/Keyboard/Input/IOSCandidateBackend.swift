import Foundation
import Shared

/// Native queues and dictionary IO; callbacks enter Shared on the main thread.
final class IOSCandidateBackend: CandidateBackend {
    private let engine: PinyinEngine
    private let extras: DispatchQueue
    private let dictionary: () -> EnglishDictionary
    private var epoch: UInt64 = 0

    init(engine: PinyinEngine, extras: DispatchQueue, dictionary: @escaping () -> EnglishDictionary) {
        self.engine = engine; self.extras = extras; self.dictionary = dictionary
    }
    func cancel() { epoch &+= 1; engine.cancel() }
    func pinyin(request: CandidateRequest, completion: @escaping (CandidateResult) -> Void) {
        let expected = epoch
        engine.search(input: request.input, nine: request.mode == .chineseNineKey,
            locks: request.locks, corrections: request.corrections) { [weak self] result in
                guard let self, epoch == expected else { return }
                switch result {
                case .success(let words): completion(CandidateResult(candidates: words, error: ""))
                case .failure(let error): completion(CandidateResult(candidates: [], error: error.localizedDescription))
                }
            }
    }
    func predict(context: String, completion: @escaping ([String]) -> Void) {
        let expected = epoch
        engine.predict(context) { [weak self] words in
            guard let self, epoch == expected else { return }
            completion(words)
        }
    }
    func english(input: String, nine: Bool, completion: @escaping ([String]) -> Void) {
        let expected = epoch
        extras.async { [self] in
            let data = dictionary()
            let words = nine ? data.nine(input) : data.suggestions(input)
            DispatchQueue.main.async { [weak self] in
                guard let self, epoch == expected else { return }
                completion(words)
            }
        }
    }
}
