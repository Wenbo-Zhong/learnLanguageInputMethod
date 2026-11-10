import Foundation
import TensorFlowLite
import LiteRT
import Metal

/// Platform execution only; tokenization and autoregressive decoding stay in LocalEngine.
final class TranslationRuntime {
    private let cpu: Interpreter?
    private let gpu: CompiledModel?
    let label: String

    init(path: String, useGPU: Bool) throws {
        if useGPU {
            guard MTLCreateSystemDefaultDevice() != nil else {
                throw RuntimeError.message("此设备没有可用的 Metal GPU")
            }
            // Extensions load embedded frameworks from their containing application.
            let app = URL(fileURLWithPath: path).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            let directory = app.appendingPathComponent("Frameworks/LiteRtMetalAccelerator.framework")
            let environment = try LiteRT.Environment(options: [.runtimeLibraryDir(directory.path)])
            let options = try LiteRT.Options()
            try options.setHardwareAccelerators([.gpu, .cpu])
            let model: CompiledModel
            do { model = try CompiledModel(filePath: path, environment: environment, options: options) }
            catch {
                NSLog("LEARNIME_TRANSLATION_TRACE Metal model compilation failed: %@", String(describing: error))
                throw RuntimeError.message("当前环境无法用 Metal 运行此模型，请关闭 GPU 实验")
            }
            guard environment.hasGpuEnvironment() else {
                throw RuntimeError.message("Metal GPU 环境创建失败")
            }
            gpu = model; cpu = nil
            label = "本地 · GPU / CPU"
            NSLog("LEARNIME_TRANSLATION_TRACE backend=%@ gpuEnvironment=true", label)
        } else {
            var cpuOptions = Interpreter.Options(); cpuOptions.threadCount = 4
            cpu = try Interpreter(modelPath: path, options: cpuOptions)
            gpu = nil
            label = "本地翻译 · CPU"
        }
    }

    func signature(_ name: String) throws -> TranslationSignature {
        if let gpu {
            guard let index = try gpu.signatureKeys().firstIndex(of: name) else {
                throw RuntimeError.message("模型缺少 \(name) 签名")
            }
            return try TranslationSignature(gpu: gpu, index: index)
        }
        guard let cpu else { throw RuntimeError.message("CPU 模型尚未加载") }
        return try TranslationSignature(cpu: cpu.signatureRunner(with: name))
    }

    enum RuntimeError: LocalizedError {
        case message(String)
        var errorDescription: String? { if case .message(let value) = self { return value }; return nil }
    }

}

final class TranslationSignature {
    private let cpu: SignatureRunner?
    private let gpu: CompiledModel?
    private let index: Int
    private var inputs: [String] = [], outputs: [String] = []
    private var inputBuffers: [TensorBuffer] = [], outputBuffers: [TensorBuffer] = []
    private var outputBytes: [Int] = []

    init(cpu: SignatureRunner) { self.cpu = cpu; gpu = nil; index = 0 }
    init(gpu: CompiledModel, index: Int) throws {
        self.gpu = gpu; self.index = index; cpu = nil
        inputs = try (0..<gpu.inputCount(signatureIndex: index)).map { try gpu.inputName(inputIndex: $0, signatureIndex: index) }
        outputs = try (0..<gpu.outputCount(signatureIndex: index)).map { try gpu.outputName(outputIndex: $0, signatureIndex: index) }
        inputBuffers = try gpu.createInputBuffers(signatureIndex: index)
        outputBuffers = try gpu.createOutputBuffers(signatureIndex: index)
        outputBytes = try outputs.indices.map {
            guard let count = try gpu.outputTensorType(outputIndex: $0, signatureIndex: index).layout.elementCount else {
                throw TranslationRuntime.RuntimeError.message("GPU 输出张量尺寸无效")
            }
            return count * MemoryLayout<Float>.size
        }
    }
    func invoke(with values: [String: Data]) throws {
        if let cpu { try cpu.invoke(with: values); return }
        guard let gpu else { return }
        for (i, name) in inputs.enumerated() {
            guard let data = values[name] else { throw TranslationRuntime.RuntimeError.message("缺少模型输入 \(name)") }
            try inputBuffers[i].write(data)
        }
        try gpu.run(inputs: inputBuffers, outputs: outputBuffers, signatureIndex: index)
    }
    func output(named name: String) throws -> Data {
        if let cpu { return try cpu.output(named: name).data }
        guard let i = outputs.firstIndex(of: name) else { throw TranslationRuntime.RuntimeError.message("缺少模型输出 \(name)") }
        let data = try outputBuffers[i].read()
        guard data.count >= outputBytes[i] else { throw TranslationRuntime.RuntimeError.message("GPU 输出张量不足") }
        // GPU allocations include alignment padding that is not part of the logits/cache tensor.
        return Data(data.prefix(outputBytes[i]))
    }
}
