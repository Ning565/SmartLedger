package com.smartledger.data.ai.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 基于 [HttpURLConnection] 的生产实现。
 *
 * 选择它而不是 OkHttp 的理由：
 *  1. 项目现有网络代码（`util/UpdateChecker.kt`）就是这套范式，不混用两种；
 *  2. **零新增依赖** —— release 开了 `isMinifyEnabled`，少一个库就少一份 R8 风险；
 *  3. SSE 只需 `BufferedReader.readLine()` 逐行读，不需要专门的 HTTP 客户端。
 */
class UrlConnectionHttpEngine : HttpEngine {

    override suspend fun post(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = open(request, accept = ACCEPT_JSON)
                val code = conn.responseCode
                val body = readBody(conn, code)
                HttpResponse(code, body)
            } finally {
                conn?.disconnect()
            }
        }

    override suspend fun postStreaming(
        request: HttpRequest,
        onLine: suspend (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = open(request, accept = ACCEPT_EVENT_STREAM)

            // 协程被取消（用户点「停止生成」/ 页面销毁）时主动断开连接，
            // 让阻塞在 readLine() 上的 IO 线程尽快抛 IOException 退出，
            // 否则连接会一直挂到 readTimeout 才释放。
            val connection = conn
            val job = currentCoroutineContext().job
            val onCancel = job.invokeOnCompletion { cause ->
                if (cause != null) runCatching { connection.disconnect() }
            }

            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    // 流式请求失败时错误信息同样在 errorStream 里
                    val errBody = readBody(conn, code)
                    throw HttpErrorStatusException(code, errBody)
                }

                BufferedReader(
                    InputStreamReader(conn.inputStream, Charsets.UTF_8)
                ).use { reader ->
                    while (true) {
                        // 每轮都检查取消状态，避免取消后还在往 Flow 里推数据
                        currentCoroutineContext().ensureActive()
                        val line = reader.readLine() ?: break   // EOF：服务端正常收尾
                        onLine(line)
                    }
                }
                code
            } finally {
                onCancel.dispose()
            }
        } finally {
            conn?.disconnect()
        }
    }

    // ═══ 内部实现 ═══

    private fun open(request: HttpRequest, accept: String): HttpURLConnection {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = request.connectTimeoutMs
        conn.readTimeout = request.readTimeoutMs
        conn.doOutput = true
        conn.doInput = true
        conn.instanceFollowRedirects = false
        // 不使用连接池缓存：AI 响应体大且一次性，缓存反而占内存
        conn.useCaches = false

        conn.setRequestProperty("Accept", accept)
        /**
         * 关键：**显式禁用 gzip**。
         *
         * HttpURLConnection 在未设置 Accept-Encoding 时会自动加 `gzip` 并透明解压，
         * 而透明解压需要读完整响应体才能确定边界，这会**彻底破坏 SSE 的逐行到达**，
         * 表现为「流式生成变成等全部生成完才一次性刷出来」。
         */
        conn.setRequestProperty("Accept-Encoding", "identity")
        request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(request.body)
            writer.flush()
        }
        return conn
    }

    /** 2xx 读 inputStream，其余读 errorStream；读不到就返回空串而不抛 */
    private fun readBody(conn: HttpURLConnection, code: Int): String = try {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    } catch (_: Exception) {
        ""
    }

    companion object {
        private const val ACCEPT_JSON = "application/json"
        private const val ACCEPT_EVENT_STREAM = "text/event-stream"
    }
}

/**
 * 流式请求收到非 2xx。
 *
 * 单独定义是因为流式路径没有「返回 HttpResponse 给上层判断」的机会，
 * 必须用异常把状态码与错误体带出去，交由 AiClient 统一分类成 AiError。
 */
class HttpErrorStatusException(
    val code: Int,
    val errorBody: String
) : java.io.IOException("HTTP $code")
