package dev.learnime.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object NativeHandwriting {
    init { System.loadLibrary("learnime") }
    external fun create(path: String): Long
    external fun recognize(handle: Long, points: IntArray): ByteArray
    external fun destroy(handle: Long)
}
internal class HandwritingEngine(private val context: Context) {
    private val lock = Mutex()
    private var handle = 0L
    suspend fun recognize(points: IntArray): List<String> = withContext(Dispatchers.Default) {
        lock.withLock {
            if (handle == 0L) {
                val file = File(context.noBackupFilesDir, "handwriting-zh_CN-0.3.model")
                context.assets.openFd("extras/handwriting-zh_CN.model").use { asset ->
                    if (!file.exists() || file.length() != asset.length) {
                        val temp = File(file.path + ".tmp")
                        asset.createInputStream().use { from -> temp.outputStream().use { from.copyTo(it) } }
                        check(temp.renameTo(file))
                    }
                }
                handle = NativeHandwriting.create(file.absolutePath); check(handle != 0L) { "中文手写模型加载失败" }
            }
            NativeHandwriting.recognize(handle, points).toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
        }
    }
    suspend fun close() = withContext(Dispatchers.Default) {
        lock.withLock { if (handle != 0L) { NativeHandwriting.destroy(handle); handle = 0L } }
    }
}
internal class HandwritingView(
    context: Context, ink: Int, widthDp: Float = 2.5f,
    private val started: () -> Unit = {},
    private val changed: (IntArray) -> Unit
) : View(context) {
    private val strokes = ArrayList<MutableList<Pair<Float, Float>>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = widthDp * resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private var active = false
    val hasStrokes get() = strokes.isNotEmpty()
    fun clear() { strokes.clear(); active = false; invalidate() }
    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex); invalidate(); changed(points()) }
    private fun points(): IntArray = strokes.flatMapIndexed { index, stroke ->
        stroke.flatMap { (x, y) -> listOf(index, (1000 * x / width.coerceAtLeast(1)).toInt(), (1000 * y / height.coerceAtLeast(1)).toInt()) }
    }.toIntArray()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokes.forEach { stroke ->
            path.reset()
            stroke.forEachIndexed { index, (x, y) -> if (index == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            canvas.drawPath(path, paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (strokes.size >= 64) return true
                started()
                strokes.add(arrayListOf(event.x to event.y)); active = true
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (active && strokes.sumOf { it.size } < 8000) {
                for (index in 0 until event.historySize) strokes.last().add(event.getHistoricalX(index) to event.getHistoricalY(index))
                strokes.last().add(event.x to event.y)
            }
            MotionEvent.ACTION_UP -> if (active) {
                strokes.last().add(event.x to event.y); active = false
                changed(points()); performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (active && strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
                active = false; changed(points())
            }
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean = super.performClick()
}
