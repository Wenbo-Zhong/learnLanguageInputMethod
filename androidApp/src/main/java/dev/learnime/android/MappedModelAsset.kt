package dev.learnime.android

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer

/** Borrow an APK mapping only during ONNX parsing; ORT owns the parsed initializers. */
internal object MappedModelAsset {
    init { System.loadLibrary("learnime") }
    private external fun map(fd: Int, offset: Long, length: Long): Long
    private external fun buffer(handle: Long): ByteBuffer
    private external fun unmap(handle: Long)

    fun openSession(environment: OrtEnvironment, fd: Int, offset: Long, length: Long,
                    options: OrtSession.SessionOptions): OrtSession {
        val handle = map(fd, offset, length)
        check(handle != 0L) { "无法映射 APK 模型" }
        try {
            // Only protobuf ONNX graphs are bundled; their model bytes are copied by ORT.
            return environment.createSession(buffer(handle), options)
        } finally {
            // Do not retain ~828 MiB of input mapping until a later Java GC.
            unmap(handle)
        }
    }
}
