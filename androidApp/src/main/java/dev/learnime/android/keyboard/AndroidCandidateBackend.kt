package dev.learnime.android

import dev.learnime.shared.*
import kotlinx.coroutines.*

/** Coroutine and resource adapter; candidate decisions and publication live in Shared. */
internal class AndroidCandidateBackend(
    private val scope: CoroutineScope, private val engine: PinyinEngine,
    private val dictionary: suspend () -> EnglishDictionary, private val prepare: suspend () -> Unit,
) : CandidateBackend {
    private var work = SupervisorJob(scope.coroutineContext[Job])
    override fun cancel() { work.cancel(); work = SupervisorJob(scope.coroutineContext[Job]) }
    override fun pinyin(request: CandidateRequest, completion: (CandidateResult) -> Unit) {
        scope.launch(work) {
            val result = try {
                prepare()
                CandidateResult(engine.search(request.input, request.mode == KeyboardMode.CHINESE_NINE_KEY,
                    request.locks, request.corrections))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { CandidateResult(emptyList(), e.message ?: "候选生成失败") }
            completion(result)
        }
    }
    override fun predict(context: String, completion: (List<String>) -> Unit) {
        scope.launch(work) { completion(engine.predict(context)) }
    }
    override fun english(input: String, nine: Boolean, completion: (List<String>) -> Unit) {
        scope.launch(work) {
            val data = dictionary()
            val result = withContext(Dispatchers.Default) {
                if (nine) data.nineSuggestions(input) else data.suggestions(input)
            }
            completion(result)
        }
    }
}
