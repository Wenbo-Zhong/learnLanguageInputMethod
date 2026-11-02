import Foundation
import TensorFlowLite

/// Runs the same encode/prefill/decode LiteRT signatures and SentencePiece vocabulary as Android.
final class LocalEngine {
    private let queue = DispatchQueue(label: "dev.learnime.inference", qos: .utility)
    private let lock = NSLock()
    private var interpreter: Interpreter?
    private var encodeRunner: SignatureRunner?
    private var prefillRunner: SignatureRunner?
    private var decodeRunner: SignatureRunner?
    private var tokenizer: OpaquePointer?
    private var revision: UInt64 = 0

    func cancel() {
        lock.lock(); revision &+= 1; lock.unlock()
    }

    func translate(source: String, completion: @escaping (Result<String, Error>) -> Void) {
        lock.lock(); let request = revision; lock.unlock()
        queue.async { [self] in
            do {
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
            if let tokenizer { li_sentencepiece_destroy(tokenizer); self.tokenizer = nil }
        }
    }

    private func ensureLoaded() throws {
        guard interpreter == nil else { return }
        guard let modelURL = Bundle.main.url(forResource: "model", withExtension: "tflite"),
              let spmURL = Bundle.main.url(forResource: "source", withExtension: "spm") else {
            throw EngineError.failed("键盘扩展内未找到 OPUS-MT 模型文件")
        }
        var options = Interpreter.Options(); options.threadCount = 4
        let loaded = try Interpreter(modelPath: modelURL.path, options: options)
        let spm = spmURL.path.withCString { li_sentencepiece_create($0) }
        guard let spm else { throw EngineError.failed("SentencePiece 词表加载失败") }
        do {
            let encode = try loaded.signatureRunner(with: "encode")
            let prefill = try loaded.signatureRunner(with: "prefill")
            let decode = try loaded.signatureRunner(with: "decode")
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
        let hidden = try encodeRunner.output(named: "output_0").data
        var caches = Dictionary(uniqueKeysWithValues: Self.cacheNames.map { ($0, Data(repeating: 0, count: Self.cacheBytes)) })
        var prefillInputs = caches
        prefillInputs.merge([
            "decoder_input_ids": bytes([Self.start]),
            "decoder_input_pos": bytes([Int32(0)]),
            "encoder_hidden_states": hidden,
            "pad_mask": bytes(mask)
        ]) { _, new in new }
        try prefillRunner.invoke(with: prefillInputs)
        for name in Self.cacheNames { caches[name] = try prefillRunner.output(named: name).data }
        var next = Self.argmax(try prefillRunner.output(named: "logits").data)
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
            for name in Self.cacheNames { caches[name] = try decodeRunner.output(named: name).data }
            next = Self.argmax(try decodeRunner.output(named: "logits").data)
        }
        guard !output.isEmpty else { return "" }
        let decoded = output.withUnsafeBufferPointer {
            li_sentencepiece_decode(tokenizer, $0.baseAddress, $0.count)
        }
        guard let decoded else {
            throw EngineError.failed("译文解码失败")
        }
        defer { li_sentencepiece_free(decoded) }
        return String(cString: decoded).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func isCurrent(_ request: UInt64) -> Bool {
        lock.lock(); defer { lock.unlock() }; return request == revision
    }

    private func bytes<T>(_ values: [T]) -> Data {
        values.withUnsafeBytes { Data($0) }
    }

    private static func argmax(_ data: Data) -> Int32 {
        data.withUnsafeBytes { raw in
            let values = raw.bindMemory(to: Float.self)
            guard !values.isEmpty else { return eos }
            var best = 0
            for i in 1..<values.count where values[i] > values[best] { best = i }
            return Int32(best)
        }
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
