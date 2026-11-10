import Foundation
import Shared

enum M2mError: LocalizedError {
    case failed(String), cancelled
    var errorDescription: String? {
        switch self {
        case .failed(let text): return text
        case .cancelled: return "翻译已取消"
        }
    }
    static func native() -> M2mError { .failed(String(cString: li_ort_error())) }
}

private final class OrtTensor {
    let handle: OpaquePointer
    init<T>(_ values: [T], shape: [Int64], type: Int32) throws {
        guard let value = values.withUnsafeBytes({ bytes in
            shape.withUnsafeBufferPointer { li_ort_tensor(bytes.baseAddress, bytes.count, $0.baseAddress, $0.count, type) }
        }) else { throw M2mError.native() }
        handle = value
    }
    deinit { li_ort_value_destroy(handle) }
}

private final class OrtResult {
    let handle: OpaquePointer
    init(_ handle: OpaquePointer) { self.handle = handle }
    func output(_ name: String) throws -> OpaquePointer {
        guard let value = name.withCString({ li_ort_output(handle, $0) }) else { throw M2mError.native() }
        return value
    }
    deinit { li_ort_result_destroy(handle) }
}

/// A serial iOS adapter; beam scoring and selection are shared with Android.
final class M2mEngine {
    private let resources: Bundle
    private var runtime: OpaquePointer?
    private var tokenizer: OpaquePointer?
    private let lock = NSLock()
    private var revision: UInt64 = 0
    private var forward = [Int32]()
    private var outputPieces = [Data]()
    private var vocabulary: Int32 = 0
    private var chinese: Int32 = 0
    private var english: Int32 = 0
    func ticket() -> UInt64 {
        lock.lock(); defer { lock.unlock() }; return revision
    }
    init(resources: Bundle) { self.resources = resources }

    func cancel() {
        lock.lock(); defer { lock.unlock() }
        revision &+= 1
        if let runtime { li_ort_cancel(runtime) }
    }
    // Called only on LocalEngine's serial queue.
    func unload() {
        lock.lock()
        if let runtime { li_ort_destroy(runtime); self.runtime = nil }
        lock.unlock()
        if let tokenizer { li_sentencepiece_destroy(tokenizer); self.tokenizer = nil }
        forward.removeAll(); outputPieces.removeAll()
    }
    deinit { unload() }

    private func current(_ request: UInt64) throws {
        lock.lock(); let valid = request == revision; lock.unlock()
        if !valid { throw M2mError.cancelled }
    }
    private func load() throws {
        guard runtime == nil else { return }
        func asset(_ name: String) throws -> URL {
            guard let url = resources.url(forResource: name, withExtension: nil, subdirectory: "m2m100") else {
                throw M2mError.failed("安装包内未找到 M2M100 模型：\(name)")
            }
            return url
        }
        let manifest = try JSONSerialization.jsonObject(with: Data(contentsOf: asset("manifest.json"))) as? [String: Any]
        guard manifest?["runtime"] as? String == "1.23.0" else {
            throw M2mError.failed("M2M100 模型与 iOS 推理库版本不匹配，请重新构建")
        }
        let map = try Data(contentsOf: asset("tokenizer-map.bin"))
        var offset = 0
        func integer() throws -> Int32 {
            guard offset + 4 <= map.count else { throw M2mError.failed("M2M100 词表文件损坏") }
            let value = map[offset..<offset + 4].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
            offset += 4
            return Int32(bitPattern: value)
        }
        guard try integer() == 0x4D324D32 else { throw M2mError.failed("M2M100 词表版本无效") }
        let size = try integer(); vocabulary = try integer()
        let pieces = try integer(); chinese = try integer(); english = try integer()
        guard size == 128000, vocabulary == 128112, pieces == 128004, chinese == 128102, english == 128022 else {
            throw M2mError.failed("M2M100 词表与模型不匹配")
        }
        var inputMap = [Int32]()
        for _ in 0..<size {
            let id = try integer()
            guard id >= 0, id < pieces else { throw M2mError.failed("M2M100 输入词表无效") }
            inputMap.append(id)
        }
        var outputMap = [Data]()
        for _ in 0..<pieces {
            let count = Int(try integer())
            guard count >= 0, count <= map.count - offset else { throw M2mError.failed("M2M100 输出词表无效") }
            outputMap.append(map.subdata(in: offset..<offset + count)); offset += count
        }
        guard offset == map.count else { throw M2mError.failed("M2M100 词表长度无效") }
        guard let spm = try asset("sentencepiece.bpe.model").path.withCString({ li_sentencepiece_create($0) }) else {
            throw M2mError.failed("M2M100 SentencePiece 加载失败")
        }
        do {
            let encoder = try asset("encoder.ort").path, decoder = try asset("decoder.ort").path
            guard let loaded = encoder.withCString({ enc in decoder.withCString { li_ort_create(enc, $0) } }) else {
                throw M2mError.native()
            }
            lock.lock(); runtime = loaded; lock.unlock()
            tokenizer = spm; forward = inputMap; outputPieces = outputMap
            NSLog("LEARNIME_M2M_TRACE loaded footprint_mb=%.1f", Double(li_ort_footprint()) / 1048576)
        } catch { li_sentencepiece_destroy(spm); throw error }
    }
    func translate(_ source: String, reverse: Bool, request: UInt64) throws -> String {
        try current(request)
        try load()
        lock.lock()
        guard request == revision, let runtime else { lock.unlock(); throw M2mError.cancelled }
        li_ort_begin(runtime); lock.unlock()
        guard let tokenizer else { throw M2mError.failed("M2M100 词表尚未加载") }
        var ids = [Int32](repeating: 0, count: 254)
        let utf8 = Array(source.utf8)
        let count = utf8.withUnsafeBytes { bytes in
            ids.withUnsafeMutableBufferPointer {
                li_sentencepiece_encode_utf8(tokenizer, bytes.baseAddress?.assumingMemoryBound(to: CChar.self),
                                            bytes.count, $0.baseAddress, $0.count)
            }
        }
        guard count >= 0 else { throw M2mError.failed("M2M100 输入编码失败") }
        guard count <= 254 else { throw M2mError.failed("句子太长，请分句翻译") }
        let encoded = [Int64(reverse ? english : chinese)] + ids.prefix(Int(count)).map { Int64(forward[Int($0)]) } + [2]
        let input = try OrtTensor(encoded, shape: [1, Int64(encoded.count)], type: 7)
        let mask = try OrtTensor([Int64](repeating: 1, count: encoded.count), shape: [1, Int64(encoded.count)], type: 7)
        let hidden = try run(decoder: false, inputs: [("input_ids", input.handle), ("attention_mask", mask.handle)])
        try current(request)
        let empty = try OrtTensor([Float](), shape: [1, 16, 0, 64], type: 1)
        let first = try step(2, hidden: hidden, mask: mask, empty: empty, cross: nil, state: nil)
        let search = M2mBeamSearch(targetId: reverse ? chinese : english)
        var states = [Int32: OrtResult](), nextState: Int32 = 1
        let started = Date()
        for position in 0..<128 {
            for beam in search.beams {
                try current(request)
                let next = try step(beam.lastToken, hidden: hidden, mask: mask, empty: empty,
                                    cross: first, state: beam.state == 0 ? first : states[beam.state])
                let key = nextState; nextState += 1; states[key] = next
                var tokens = [Int32](repeating: 0, count: 10), scores = [Double](repeating: 0, count: 10)
                let logits = try next.output("logits")
                let count = li_ort_top_tokens(logits, vocabulary, &tokens, &scores, 10)
                guard count == 10 else { throw M2mError.native() }
                for i in 0..<Int(count) { search.add(beam: beam, token: tokens[i], logProbability: scores[i], state: key) }
            }
            search.advance()
            if search.done {
                try current(request)
                let output = search.output.map { $0.int32Value }.filter { $0 > 3 }
                guard output.allSatisfy({ $0 < outputPieces.count }) else { throw M2mError.failed("M2M100 输出词元无效") }
                let pieces = output.map { outputPieces[Int($0)] }
                let buffers = pieces.map { piece -> UnsafeMutablePointer<CChar> in
                    let pointer = UnsafeMutablePointer<CChar>.allocate(capacity: max(1, piece.count))
                    piece.copyBytes(to: UnsafeMutableRawBufferPointer(start: pointer, count: piece.count))
                    return pointer
                }
                defer { buffers.forEach { $0.deallocate() } }
                let pointers = buffers.map { Optional(UnsafePointer($0)) }, lengths = pieces.map(\.count)
                guard let decoded = li_sentencepiece_decode_pieces(tokenizer, pointers, lengths, pieces.count) else {
                    throw M2mError.failed("M2M100 译文解码失败")
                }
                defer { li_sentencepiece_free(decoded) }
                let text = String(cString: decoded).trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { throw M2mError.failed("M2M100 返回空译文") }
                NSLog("LEARNIME_M2M_TRACE complete input=%d output=%d beams=5 steps=%d seconds=%.2f footprint_mb=%.1f",
                      encoded.count, output.count, position + 1, Date().timeIntervalSince(started), Double(li_ort_footprint()) / 1048576)
                return text
            }
            states = states.filter { search.retains(state: $0.key) }
        }
        throw M2mError.failed("翻译未能生成完整结果，请缩短句子后重试")
    }
    private func run(decoder: Bool, inputs: [(String, OpaquePointer)]) throws -> OrtResult {
        let strings = inputs.map { strdup($0.0)! }
        defer { strings.forEach { free($0) } }
        let names = strings.map { Optional(UnsafePointer($0)) }
        let values = inputs.map { Optional($0.1) }
        guard let result = li_ort_run(runtime, decoder ? 1 : 0, names, values, inputs.count) else { throw M2mError.native() }
        return OrtResult(result)
    }
    private func step(_ token: Int32, hidden: OrtResult, mask: OrtTensor, empty: OrtTensor,
                      cross: OrtResult?, state: OrtResult?) throws -> OrtResult {
        let input = try OrtTensor([Int64(token)], shape: [1, 1], type: 7)
        let flag = try OrtTensor([UInt8(cross == nil ? 0 : 1)], shape: [1], type: 9)
        var inputs = [("input_ids", input.handle), ("encoder_hidden_states", try hidden.output("last_hidden_state")),
                      ("encoder_attention_mask", mask.handle), ("use_cache_branch", flag.handle)]
        for layer in 0..<12 {
            for kind in ["key", "value"] {
                inputs.append(("past_key_values.\(layer).encoder.\(kind)", try cross?.output("present.\(layer).encoder.\(kind)") ?? empty.handle))
                inputs.append(("past_key_values.\(layer).decoder.\(kind)", try state?.output("present.\(layer).decoder.\(kind)") ?? empty.handle))
            }
        }
        return try run(decoder: true, inputs: inputs)
    }
}
