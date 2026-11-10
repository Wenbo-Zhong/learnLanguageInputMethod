import Foundation

// Integration probe compiled with the production LocalEngine and TranslationRuntime.
enum KeyboardPreferences { static let store = UserDefaults.standard }

@main struct TranslationSmoke {
    static func main() {
        guard CommandLine.arguments.count >= 2, let resources = Bundle(path: CommandLine.arguments[1]) else {
            fatalError("Usage: translation-smoke <LearningKeyboard.appex> [gpu]")
        }
        let mode = CommandLine.arguments.count > 2 ? CommandLine.arguments[2] : "default"
        let m2m = mode == "m2m100" || mode == "default"
        let engine = LocalEngine(resources: resources, useGPU: mode == "gpu",
                                 model: mode == "default" ? nil : m2m ? "m2m100" : "opus")
        let supplied = Array(CommandLine.arguments.dropFirst(3))
        let corpus = supplied.isEmpty ? (m2m
            ? ["你好世界", "今天天气很好。", "我想学习英语。", "I would like to learn Chinese."]
            : ["你好世界", "今天天气很好。", "我想学习英语。"]) : supplied
        var index = 0
        var failed = false
        var peak: UInt64 = 0
        let sample = DispatchSource.makeTimerSource(queue: .main)
        sample.schedule(deadline: .now(), repeating: .milliseconds(10))
        sample.setEventHandler { peak = max(peak, li_ort_footprint()) }
        sample.resume()
        func finish() {
            print("TRANSLATION_SMOKE peak_footprint_mb=\(Double(peak) / 1048576)")
            fflush(stdout)
            exit(failed ? 1 : 0)
        }
        func cancellation() {
            var obsoleteDelivered = false
            engine.translate(source: String(repeating: "这是一段用于验证翻译取消的完整句子。", count: 8)) { _ in
                obsoleteDelivered = true
                failed = true
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                engine.cancel()
                let start = Date()
                engine.translate(source: "谢谢你的帮助。") { result in
                    if case .failure = result { failed = true }
                    if case .success(let text) = result {
                        print("TRANSLATION_SMOKE latest_request=\(text) obsolete_delivered=\(obsoleteDelivered) elapsed=\(Date().timeIntervalSince(start))s")
                        if text.isEmpty { failed = true }
                    }
                    engine.unload()
                    engine.translate(source: "我想学习英语。") { result in
                        if case .failure = result { failed = true }
                        if case .success(let text) = result {
                            print("TRANSLATION_SMOKE reload=\(text)")
                            if text != "I want to learn English." { failed = true }
                        }
                        if obsoleteDelivered { failed = true }
                        finish()
                    }
                }
            }
        }
        func next() {
            guard index < corpus.count else {
                if m2m { cancellation() } else { finish() }
                return
            }
            let source = corpus[index]; index += 1
            let start = Date()
            engine.translate(source: source) { result in
                switch result {
                case .success(let text):
                    print("TRANSLATION_SMOKE \(engine.backendLabel) | \(source) | \(text) | \(Date().timeIntervalSince(start))s")
                    if text.isEmpty { failed = true }
                case .failure(let error):
                    failed = true
                    print("TRANSLATION_SMOKE FAILED | \(source) | \(error.localizedDescription)")
                }
                fflush(stdout)
                next()
            }
        }
        next()
        dispatchMain()
    }
}
