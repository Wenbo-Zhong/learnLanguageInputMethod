import Foundation
import TensorFlowLite
import Shared

/// Runs the same encode/prefill/decode LiteRT signatures and SentencePiece vocabulary as Android.
final class LocalEngine {
    private(set) var backendLabel = "本地翻译 · CPU"
    private let queue = DispatchQueue(label: "dev.learnime.inference", qos: .utility)
    private let lock = NSLock()
    private var interpreter: TranslationRuntime?
    private var encodeRunner: TranslationSignature?
    private var prefillRunner: TranslationSignature?
    private var decodeRunner: TranslationSignature?
    private var tokenizer: OpaquePointer?
    private var revision: UInt64 = 0
    private let resources: Bundle
    private let useGPU: Bool?
    private let model: String?
    private let m2m: M2mEngine

    init(resources: Bundle = .main, useGPU: Bool? = nil, model: String? = nil) {
        self.resources = resources; self.useGPU = useGPU; self.model = model
        let containing = Bundle(url: resources.bundleURL.deletingLastPathComponent().deletingLastPathComponent())
        self.m2m = M2mEngine(resources: resources.url(forResource: "m2m100", withExtension: nil) != nil
            ? resources : containing ?? resources)
    }

    func cancel() {
        lock.lock(); revision &+= 1; lock.unlock()
        m2m.cancel()
    }

    func translate(source: String, completion: @escaping (Result<String, Error>) -> Void) {
        lock.lock(); let request = revision; lock.unlock()
        let modelRequest = m2m.ticket()
        queue.async { [self] in
            do {
                guard isCurrent(request) else { return }
                let selected = model ?? KeyboardPreferences.store.string(forKey: "translationModel") ?? "m2m100"
                if selected == "m2m100" {
                    let reverse = TranslationModels.shared.direction(context: source, model: selected) == .englishToChinese
                    let result = try m2m.translate(source, reverse: reverse, request: modelRequest)
                    guard isCurrent(request) else { return }
                    backendLabel = "M2M100 · \(reverse ? "EN → 中" : "中 → EN") · CPU"
                    DispatchQueue.main.async { completion(.success(result)) }
                    return
                }
                guard selected == "opus" else { throw EngineError.failed("翻译模型设置无效，请在设置中重新选择") }
                try ensureLoaded()
                let result = try run(source: source, request: request)
                guard isCurrent(request) else { return }
                DispatchQueue.main.async { completion(.success(result)) }
            } catch {
                guard isCurrent(request) else { return }
                DispatchQueue.main.async { completion(.failure(error)) }
            }
        }
    }

    func unload() {
        cancel()
        queue.async { [self] in
            encodeRunner = nil; prefillRunner = nil; decodeRunner = nil
            interpreter = nil
            m2m.unload()
            if let tokenizer { li_sentencepiece_destroy(tokenizer); self.tokenizer = nil }
        }
    }

    private func ensureLoaded() throws {
        guard interpreter == nil else { return }
        guard let modelURL = resources.url(forResource: "model", withExtension: "tflite"),
              let spmURL = resources.url(forResource: "source", withExtension: "spm") else {
            throw EngineError.failed("键盘扩展内未找到 OPUS-MT 模型文件")
        }
        let loaded = try TranslationRuntime(path: modelURL.path, useGPU: useGPU ?? KeyboardPreferences.store.bool(forKey: "gpuTranslation"))
        backendLabel = "OPUS-MT · \(loaded.label)"
        let spm = spmURL.path.withCString { li_sentencepiece_create($0) }
        guard let spm else { throw EngineError.failed("SentencePiece 词表加载失败") }
        do {
            let encode = try loaded.signature("encode")
            let prefill = try loaded.signature("prefill")
            let decode = try loaded.signature("decode")
            interpreter = loaded
            tokenizer = spm
            encodeRunner = encode
            prefillRunner = prefill
            decodeRunner = decode
        } catch {
            li_sentencepiece_destroy(spm)
            throw error
        }
    }

    private func run(source: String, request: UInt64) throws -> String {
        guard let tokenizer, let encodeRunner, let prefillRunner, let decodeRunner else {
            throw EngineError.failed("翻译模型尚未加载")
        }
        var encoded = [Int32](repeating: 0, count: Self.maxTokens - 1)
        let count = source.withCString { text in
            encoded.withUnsafeMutableBufferPointer { buffer in
                li_sentencepiece_encode(tokenizer, text, buffer.baseAddress, buffer.count)
            }
        }
        guard count > 0 else { throw EngineError.failed("请输入中文句子") }
        encoded = Array(encoded.prefix(Int(count))); encoded.append(Self.eos)
        var ids = [Int32](repeating: Self.pad, count: Self.maxTokens)
        ids.replaceSubrange(0..<encoded.count, with: encoded)
        let mask = (0..<Self.maxTokens).map { $0 < encoded.count ? Float.zero : Self.maskedAttention }
        let positions = (0..<Self.maxTokens).map(Int32.init)

        try encodeRunner.invoke(with: [
            "input_ids": bytes(ids),
            "input_pos": bytes(positions),
            "pad_mask": bytes(mask)
        ])
        guard isCurrent(request) else { throw EngineError.cancelled }
        let hidden = try encodeRunner.output(named: "output_0")
        Self.trace("encode", hidden)
        var caches = Dictionary(uniqueKeysWithValues: Self.cacheNames.map { ($0, Data(repeating: 0, count: Self.cacheBytes)) })
        var prefillInputs = caches
        prefillInputs.merge([
            "decoder_input_ids": bytes([Self.start]),
            "decoder_input_pos": bytes([Int32(0)]),
            "encoder_hidden_states": hidden,
            "pad_mask": bytes(mask)
        ]) { _, new in new }
        try prefillRunner.invoke(with: prefillInputs)
        for name in Self.cacheNames { caches[name] = try prefillRunner.output(named: name) }
        let firstLogits = try prefillRunner.output(named: "logits")
        Self.trace("prefill", firstLogits)
        var next = try Self.argmax(firstLogits)
        var output = [Int32]()
        for position in 1..<Self.maxTokens {
            guard isCurrent(request) else { throw EngineError.cancelled }
            if next == Self.eos { break }
            output.append(next)
            var inputs = caches
            inputs.merge([
                "decoder_input_ids": bytes([next]),
                "decoder_input_pos": bytes([Int32(position)]),
                "encoder_hidden_states": hidden,
                "pad_mask": bytes(mask)
            ]) { _, new in new }
            try decodeRunner.invoke(with: inputs)
            for name in Self.cacheNames { caches[name] = try decodeRunner.output(named: name) }
            let logits = try decodeRunner.output(named: "logits")
            if position == 1 { Self.trace("decode", logits) }
            next = try Self.argmax(logits)
        }
        guard !output.isEmpty else { throw EngineError.failed("模型返回空译文，请补充完整句子") }
        guard next == Self.eos else { throw EngineError.failed("模型未正常结束翻译，请缩短句子后重试") }
        let decoded = output.withUnsafeBufferPointer {
            li_sentencepiece_decode(tokenizer, $0.baseAddress, $0.count)
        }
        guard let decoded else {
            throw EngineError.failed("译文解码失败")
        }
        defer { li_sentencepiece_free(decoded) }
        let text = String(cString: decoded).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw EngineError.failed("模型返回空译文，请补充完整句子") }
        return text
    }

    private func isCurrent(_ request: UInt64) -> Bool {
        lock.lock(); defer { lock.unlock() }; return request == revision
    }

    private func bytes<T>(_ values: [T]) -> Data {
        values.withUnsafeBytes { Data($0) }
    }

    private static func argmax(_ data: Data) throws -> Int32 {
        try data.withUnsafeBytes { raw in
            let values = raw.bindMemory(to: Float.self)
            guard values.count == 32001, values.allSatisfy(\.isFinite) else {
                throw EngineError.failed("模型输出无效")
            }
            var best = 0
            for i in 1..<values.count where values[i] > values[best] { best = i }
            return Int32(best)
        }
    }

    private static func trace(_ stage: String, _ data: Data) {
        #if DEBUG
        data.withUnsafeBytes { raw in
            let values = raw.bindMemory(to: Float.self)
            let finite = values.filter(\.isFinite)
            NSLog("LEARNIME_TRANSLATION_TRACE %@ count=%d finite=%d min=%g max=%g argmax=%d",
                  stage, values.count, finite.count, finite.min() ?? 0, finite.max() ?? 0, (try? argmax(data)) ?? -1)
        }
        #endif
    }

    private enum EngineError: LocalizedError {
        case failed(String), cancelled
        var errorDescription: String? {
            switch self {
            case .failed(let message): return message
            case .cancelled: return "翻译已取消"
            }
        }
    }

    private static let maxTokens = 256
    private static let eos: Int32 = 0
    private static let start: Int32 = 32000
    private static let pad: Int32 = 32000
    private static let maskedAttention: Float = -1.0e9
    private static let cacheBytes = 1 * maxTokens * 8 * 32 * MemoryLayout<Float>.size
    private static let cacheNames = [
        "cross_attn_kv_cache_k_0", "cross_attn_kv_cache_k_1",
        "cross_attn_kv_cache_v_0", "cross_attn_kv_cache_v_1",
        "self_attn_kv_cache_k_0", "self_attn_kv_cache_k_1",
        "self_attn_kv_cache_v_0", "self_attn_kv_cache_v_1"
    ]
}
