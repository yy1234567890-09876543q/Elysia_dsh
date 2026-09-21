package com.example.dshchat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 一个 DSH 会话 */
data class DshSession(
    val sessionId: String,
    val title: String,
    val running: Boolean,
    val asOfSeq: Int
)

/** 一条助手正文：带 seq，用于轮询去重 */
data class AssistantText(val seq: Int, val text: String)

/**
 * DSH 本地 API 客户端（JSON-RPC）。
 *
 * 真实协议（依据 dsh_api.py）：
 *   POST {base}/api/<method>
 *   请求：{"type":"client-request","rpcId":"<uuid>","method":"<method>","payload":{"args":{...}}}
 *   响应：{"type":"server-response","rpcId":"<uuid>","result":{"ok":true,"value":...}}
 *
 * 注意：
 * 1. session/prompt 只返回 {"accepted":true}，不返回正文；正文靠轮询 session/page。
 * 2. 本机 loopback 已装 dsh-local-no-auth，免鉴权。
 * 3. 必须绕开系统代理（对应 Python 里的 ProxyHandler({})）。
 */
object DshApi {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)              // 关键：不走系统代理，否则 127.0.0.1 也会被带走
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    /** 打一个 RPC 端点，返回 result.value（org.json.JSONObject）。失败抛 IOException。 */
    suspend fun rpc(base: String, method: String, args: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val rpcId = UUID.randomUUID().toString()
            val body = JSONObject().apply {
                put("type", "client-request")
                put("rpcId", rpcId)
                put("method", method)
                put("payload", JSONObject().put("args", args))
            }.toString()

            val req = Request.Builder()
                .url("${base.trimEnd('/')}/api/$method")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()

            val envelope: JSONObject = client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}: ${raw.take(300)}")
                }
                try {
                    JSONObject(raw)
                } catch (e: JSONException) {
                    throw IOException("返回不是 JSON: ${raw.take(300)}")
                }
            }

            if (envelope.optString("rpcId") != rpcId) {
                throw IOException("rpcId 不匹配")
            }
            val result = envelope.optJSONObject("result")
                ?: throw IOException("响应缺少 result: $envelope")
            if (!result.optBoolean("ok", false)) {
                val err = result.optJSONObject("error")
                throw IOException("${err?.optString("code")}: ${err?.optString("message")}")
            }
            result.optJSONObject("value") ?: JSONObject()
        }

    /** 列出全部会话 */
    suspend fun listSessions(base: String): List<DshSession> = withContext(Dispatchers.IO) {
        // 注意：这个端点的字段名是 _request（其他端点多为 request）
        val value = rpc(base, "session/list", JSONObject().put("_request", JSONObject()))
        val items = value.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<DshSession>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val proj = it.optJSONObject("projections") ?: JSONObject()
            val title = proj.optJSONObject("values")?.optString("title")
                ?: it.optString("sessionId").takeLast(8)
            out.add(
                DshSession(
                    sessionId = it.optString("sessionId"),
                    title = title,
                    running = it.optBoolean("running", false),
                    asOfSeq = proj.optInt("asOfSeq", 0)
                )
            )
        }
        out
    }

    /** 在会话列表里找指定会话的最新状态 */
    suspend fun getSession(base: String, sessionId: String): DshSession? =
        listSessions(base).firstOrNull { it.sessionId == sessionId }

    /**
     * 发一条用户消息（只入队，不返回正文）。
     * mode: "queue" 排队等下一轮 / "steer" 插进正在跑的那轮。
     */
    suspend fun sendPrompt(base: String, sessionId: String, text: String, mode: String = "queue") {
        val request = JSONObject().apply {
            put("requestId", UUID.randomUUID().toString())
            put("sessionId", sessionId)
            put("mode", mode)
            put("clientTimeZone", "Asia/Shanghai")
            put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", text)
            ))
        }
        rpc(base, "session/prompt", JSONObject().put("request", request))
    }

    /** 读会话记录，返回 records 数组 */
    suspend fun pageRecords(base: String, sessionId: String, throughSeq: Int): JSONArray {
        val request = JSONObject().apply {
            put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
            put("throughSeq", throughSeq)
            put("maxMessages", 200)
        }
        return rpc(base, "session/page", JSONObject().put("request", request))
            .optJSONArray("records") ?: JSONArray()
    }

    /** 从 records 里抽出所有 assistant/message 的正文：(seq, text) */
    fun extractAssistantTexts(records: JSONArray): List<AssistantText> {
        val out = ArrayList<AssistantText>()
        for (i in 0 until records.length()) {
            val rec = records.optJSONObject(i) ?: continue
            if (rec.optString("type") != "event") continue
            val event = rec.optJSONObject("event") ?: continue
            if (event.optString("type") != "assistant/message") continue

            val message = event.optJSONObject("data")?.optJSONObject("message") ?: continue
            val content = message.optJSONArray("content") ?: continue
            val parts = ArrayList<String>()
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                if (block.optString("type") == "text") {
                    block.optString("text").takeIf { it.isNotBlank() }?.let { parts.add(it) }
                }
            }
            val text = parts.joinToString("\n").trim()
            if (text.isNotEmpty()) {
                out.add(AssistantText(event.optInt("seq", 0), text))
            }
        }
        return out
    }

    /**
     * 轮询等待回复完成：从 afterSeq 起，把新出现的助手正文逐条回调出来。
     * 直到会话 running=false 且没有新正文为止。
     *
     * @param onDelta 每发现一段新正文就回调一次（在轮询协程里）
     * @return 收集到的全部新正文（拼接）
     */
    suspend fun waitForReply(
        base: String,
        sessionId: String,
        afterSeq: Int,
        timeoutMs: Long = 300_000,
        intervalMs: Long = 1500,
        onDelta: (AssistantText) -> Unit
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSeq = afterSeq
        val collected = ArrayList<String>()

        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            val item = getSession(base, sessionId) ?: break
            val records = pageRecords(base, sessionId, item.asOfSeq)
            val newOnes = extractAssistantTexts(records).filter { it.seq > lastSeq }
            for (t in newOnes) {
                lastSeq = t.seq
                collected.add(t.text)
                onDelta(t)
            }
            // 已拿到新正文，且会话不再 running → 这轮结束
            if (newOnes.isEmpty() && !item.running && lastSeq > afterSeq) break
            if (newOnes.isEmpty() && !item.running && System.currentTimeMillis() > deadline) break
        }
        return collected.joinToString("\n\n")
    }
}
