package com.smartledger.data.ai.http

/**
 * HTTP 收发抽象。
 *
 * 存在的唯一理由：**让 AiClient 可以在纯 JVM 单测里被完整验证**。
 * 不引入 MockWebServer / OkHttp，测试时注入 [FakeHttpEngine] 即可脚本化
 * 返回任意状态码、任意 SSE 行序列、任意异常。
 *
 * 生产实现是 [UrlConnectionHttpEngine]（HttpURLConnection），
 * 与项目现有 `UpdateChecker` 的网络范式保持一致，零新增依赖。
 */
interface HttpEngine {

    /**
     * 一次性请求。
     *
     * 实现必须：
     *  - 在 IO 线程执行，不阻塞调用方；
     *  - 非 2xx 时也要读出错误体（很多网关的错误信息在 body 里）；
     *  - 无论成败都断开连接；
     *  - 超时抛 [java.net.SocketTimeoutException]，DNS/连接失败抛对应 IOException。
     */
    suspend fun post(request: HttpRequest): HttpResponse

    /**
     * 流式请求（SSE）。逐行回调，返回最终 HTTP 状态码。
     *
     * 实现必须：
     *  - 每读到一行立即回调，不做缓冲聚合（否则失去流式意义）；
     *  - 协程取消时关闭底层连接，让阻塞的 readLine 尽快返回；
     *  - 连接断开（EOF）时正常返回状态码，不抛异常。
     */
    suspend fun postStreaming(request: HttpRequest, onLine: suspend (String) -> Unit): Int
}

/**
 * 请求描述。
 *
 * 刻意不含任何「已拼接好的 header 字符串」，避免调用方把 API Key
 * 拼进日志友好的结构里。headers 的打印一律由调用方负责脱敏。
 */
data class HttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int
)

data class HttpResponse(
    val code: Int,
    val body: String
) {
    val isSuccessful: Boolean get() = code in 200..299
}
