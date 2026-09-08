package com.smartledger.data.ai

import com.smartledger.data.ai.http.HttpEngine
import com.smartledger.data.ai.http.HttpErrorStatusException
import com.smartledger.data.ai.http.HttpRequest
import com.smartledger.data.ai.http.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/**
 * 脚本化的 [HttpEngine] 测试替身。
 *
 * 用它替代 MockWebServer：不引入任何网络依赖、不起真实端口、
 * 不需要 Robolectric，测试在毫秒级完成，同时能精确构造
 * 401 / 404 / 429 / 500 / 超时 / 非法 JSON / 流中断 / 永久挂起等所有分支。
 *
 * 所有收到的请求都会记录下来，供断言检查
 * 「Authorization 头是否正确」「请求体是否是合法 JSON」「Key 有没有泄漏进 body」。
 *
 * ## 为什么两个方法都要 withContext(Dispatchers.IO)
 *
 * 这是**刻意模仿生产实现的行为**，不是多余的。
 *
 * 真实 bug：`AiClientImpl.streamChat` 原本用 `flow { emit(...) }`，
 * 而 `UrlConnectionHttpEngine.postStreaming` 内部是 `withContext(Dispatchers.IO)`，
 * 于是 `onLine` 回调里的 `emit` 发生在与收集者不同的上下文，
 * 直接抛 `IllegalStateException: Flow exception transparency is violated`
 * —— 也就是说 AI 消费体检的流式功能在真机上必崩。
 *
 * 当时的 Fake 没切线程，所以 51 个单测全绿，只有真链路集成测试才暴露。
 * 现在让 Fake 也切到 IO，这类错误在单测阶段就能被拦住。
 */
class FakeHttpEngine(
    /** post() 的返回；为 null 时用 [postCode]/[postBody] 构造 */
    var postCode: Int = 200,
    var postBody: String = "",
    /** post() 抛出的异常，优先于返回值 */
    var throwOnPost: Exception? = null,

    /** postStreaming() 逐行推送的内容 */
    var streamLines: List<String> = emptyList(),
    var streamCode: Int = 200,
    /** 流式请求返回的 HTTP 状态码非 2xx 时，engine 应抛 HttpErrorStatusException */
    var streamErrorBody: String = "",
    /** postStreaming() 抛出的异常，优先于逐行推送 */
    var throwOnStream: Exception? = null,
    /** 推送 N 行后抛异常，模拟「已收到部分内容后连接中断」；-1 表示不中断 */
    var streamBreakAfterLines: Int = -1,
    /** 推送 N 行后永久挂起，用于测试取消传播；-1 表示不挂起 */
    var streamHangAfterLines: Int = -1
) : HttpEngine {

    val postRequests = mutableListOf<HttpRequest>()
    val streamRequests = mutableListOf<HttpRequest>()

    /** 实际推给 onLine 的行数 */
    var linesDelivered: Int = 0
        private set

    override suspend fun post(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) {
            postRequests += request
            throwOnPost?.let { throw it }
            HttpResponse(postCode, postBody)
        }

    override suspend fun postStreaming(
        request: HttpRequest,
        onLine: suspend (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        streamRequests += request
        throwOnStream?.let { throw it }

        if (streamCode !in 200..299) {
            throw HttpErrorStatusException(streamCode, streamErrorBody)
        }

        streamLines.forEachIndexed { index, line ->
            if (streamHangAfterLines >= 0 && index >= streamHangAfterLines) {
                awaitCancellation()
            }
            if (streamBreakAfterLines >= 0 && index >= streamBreakAfterLines) {
                throw java.io.IOException("Connection reset by peer")
            }
            // 先计数再回调：若回调里抛异常（例如收到 [DONE] 后主动跳出），
            // 该行仍算「已送达」，断言才能准确反映读到了第几行。
            linesDelivered = index + 1
            onLine(line)
        }
        streamCode
    }

    // ═══ 断言辅助 ═══

    val lastPost: HttpRequest? get() = postRequests.lastOrNull()
    val lastStream: HttpRequest? get() = streamRequests.lastOrNull()

    /** 所有请求里是否出现过 API Key 明文（body 里绝不该有） */
    fun keyLeakedIntoBody(key: String): Boolean =
        (postRequests + streamRequests).any { it.body.contains(key) }
}
