package com.example.dshchat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 一个 DSH 会话 */
data class DshSession(
    val sessionId: String,
    val title: String,
    /** [title] 是真人/自动取的真标题，还是拿 sessionId 尾巴凑的占位 */
    val named: Boolean = false,
    val running: Boolean,
    val asOfSeq: Int,
    /** 这个会话的工作目录（新建会话时拿它当默认值） */
    val cwd: String? = null,
    /** 这个会话当前用的模型 */
    val modelProvider: String? = null,
    val modelId: String? = null,
    val modelEffort: String? = null
)

/** 一次模型选择（provider + model + 可选思考档位） */
data class ModelRef(
    val provider: String,
    val model: String,
    val effort: String? = null
)

/** 一个人格预设（agentPreset）。system 是内置的，user 是自己写在 .agent-presets 里的 */
data class AgentPreset(
    val id: String,
    val name: String,
    val description: String? = null,
    /** 不指定预设时服务器会用的那一个 */
    val isDefault: Boolean = false,
    /** 组装有问题时这里带原因，这种预设不能选 */
    val broken: String? = null
)

/** 新建会话的结果 */
data class CreatedSession(
    val sessionId: String,
    /** 服务端最终解析出来的预设 id（没指定时就是默认那个） */
    val agentPreset: String? = null
)

/** 用「启动令牌」换浏览器会话 cookie 的结果 */
sealed class TokenExchange {
    /** 换到了，[cookie] 是 `名字=值` 那一截 */
    data class Ok(val cookie: String) : TokenExchange()

    /** 服务端根本没开认证（装了 dsh-local-no-auth 就是这种） */
    object NoAuthRequired : TokenExchange()

    /** 服务端要求认证，但这张令牌不认（过期了？抄漏了？） */
    object Rejected : TokenExchange()

    /** 连不上 / 其它错误 */
    data class Failed(val message: String) : TokenExchange()
}

/** 当前地址的认证状态 */
enum class AuthState {
    /** 还没探过 */
    UNKNOWN,

    /** 服务端不要求认证 —— 说明它只绑了 loopback，或者装了免认证插件 */
    NOT_REQUIRED,

    /** 已认证（手里有一张能用的 cookie） */
    AUTHENTICATED,

    /** 服务端要求认证，但我们没有（或已失效）—— 要粘贴带 token 的地址 */
    NEEDS_TOKEN,

    /** 连不上 */
    OFFLINE
}

/** 事件类型（用于 UI 分色显示） */
enum class EventType { THINKING, REPLY, TOOL_CALL, CONTEXT }

/** 一条从流里解析出的实时事件 */
data class StreamEvent(
    val seq: Int,
    val type: EventType,
    val text: String,
    val toolName: String? = null,
    /** CONTEXT：生产者名（plugin 名 / skill-catalog / agent-instructions / 路径） */
    val label: String? = null,
    /** CONTEXT：一行摘要（notice 形态自带，否则取正文首行） */
    val summary: String? = null,
    /** CONTEXT：source.form（instructions / catalog / snapshot / notice / relay / recall） */
    val form: String? = null
)

/**
 * 聊天里的一张图。
 * - 刚发出去的：有 [localPath]，直接从本地文件画出来
 * - 从历史读回来的：只有 [attachmentId]，要向服务端换回 base64
 */
data class ChatImage(
    val localPath: String? = null,
    val attachmentId: String? = null,
    val mediaType: String? = null,
    val name: String? = null
)

/** 待发送的一张图：本地副本 + 已经编码好的上传数据 */
data class PendingImage(
    val localPath: String,
    val mediaType: String,
    val base64: String,
    val name: String = ""
)

/**
 * 从历史里解析出来的一「行」。顺序与 seq 一致。
 * 一条消息可能展开成多行：思考行 + 工具行 + 正文行。
 */
data class HistoryRow(
    val seq: Int,
    val kind: MsgKind,
    val text: String,
    val label: String? = null,
    val summary: String? = null,
    val form: String? = null,
    val toolName: String? = null,
    /** 附带的图片（历史里只有 attachmentId） */
    val images: List<ChatImage> = emptyList()
)

/**
 * DSH 本地 API 客户端（JSON-RPC）。
 *
 * 真实协议：
 *   POST {base}/api/<method>
 *   请求：{"type":"client-request","rpcId":"<uuid>","method":"<method>","payload":{"args":{...}}}
 *   响应：{"type":"server-response","rpcId":"<uuid>","result":{"ok":true,"value":...}}
 *
 * 实时流：
 *   WS  {wsbase}/api/remote.mux
 *   →   {"type":"open","streamId":"<uuid>","endpoint":"session/follow","payload":{"args":{"request":{...}}}}
 *   ←   {"type":"item","streamId":"...","value":{"type":"snapshot",...}}   开窗快照
 *   ←   {"type":"item","streamId":"...","value":{"type":"event","event":{...}}}  实时事件
 *
 * 会话日志里「上下文注入」的判据（与网页版一致）：
 *   user/message 事件的 data.source.kind !== "user" 就是注入，
 *   不是真人说的话。label 按 source 形态取：
 *     plugin             → source.plugin
 *     agent-instructions → source.changes[].path 拼接
 *     session-reference  → source.references[].label 拼接
 *     skill-invocation   → source.name
 *     其它               → source.kind
 */
object DshApi {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** DSH 写入的浏览器会话 cookie 前缀（client-connection/browser-auth.js） */
    private const val COOKIE_PREFIX = "dsh-auth-"

    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    /**
     * 换令牌专用：**不跟随重定向**。
     * 上游的 `?token=` 握手靠的就是一个 303 + Set-Cookie，
     * 跟过去的话（OkHttp 默认不存 cookie）只会撞上 401。
     */
    private val authClient = client.newBuilder()
        .followRedirects(false)
        .build()

    /**
     * 认证 cookie，按 base 地址分开存。
     *
     * 为什么不用 OkHttp 的 CookieJar：cookie 名是服务端按 **authority**
     * （host:port）算出来的（`dsh-auth-<sha256(authority)>`），
     * 而我们要把同一个地址的 cookie 持久化到 SharedPreferences 里。
     * 自己拿字符串保管最简单也最可控。
     */
    private val authCookies = ConcurrentHashMap<String, String>()

    /** base 归一化：去空格、去尾斜杠 —— 用作 cookie 的键 */
    fun normalizeBase(base: String): String = base.trim().trimEnd('/')

    /** 给某个地址装上认证 cookie（传 null / 空串 = 清掉） */
    fun setAuthCookie(base: String, cookie: String?) {
        val key = normalizeBase(base)
        if (key.isEmpty()) return
        if (cookie.isNullOrBlank()) authCookies.remove(key) else authCookies[key] = cookie
    }

    /** 查某个地址的 cookie */
    fun authCookie(base: String): String? = authCookies[normalizeBase(base)]

    /** 导出全部 cookie（给 SharedPreferences 持久化用） */
    fun exportCookies(): Map<String, String> = HashMap(authCookies)

    /** 导入全部 cookie（启动时从 SharedPreferences 恢复） */
    fun importCookies(map: Map<String, String>) {
        authCookies.clear()
        for ((k, v) in map) {
            val key = normalizeBase(k)
            if (key.isNotEmpty() && v.isNotBlank()) authCookies[key] = v
        }
    }

    /** 给请求带上这个地址的 cookie（有的话） */
    private fun Request.Builder.withAuth(base: String): Request.Builder {
        val gw = activeGateway
        if (gw != null && normalizeBase(base) == normalizeBase(gw.origin)) {
            // dsh-mobile 网关：会话 cookie + 网关强制的 Origin 头
            addHeader("Cookie", "dsh_ma_session=${gw.sessionToken}")
            addHeader("Origin", gw.origin)
            addHeader("Sec-Fetch-Site", "same-origin")
            return this
        }
        authCookie(base)?.let { addHeader("Cookie", it) }
        return this
    }

    //#region dsh-mobile 网关

    /** 当前生效的网关会话（为空 = 直连 DSH 本机口） */
    @Volatile
    private var activeGateway: GatewaySession? = null

    /** 固定了网关 CA 的 OkHttp 客户端 */
    @Volatile
    private var gatewayClient: OkHttpClient? = null

    val gateway: GatewaySession? get() = activeGateway

    /** 切到网关模式：之后对该地址的请求会带会话与 Origin，并走固定的 CA 校验 */
    fun useGateway(session: GatewaySession, caDer: ByteArray) {
        activeGateway = session
        gatewayClient = PinnedTls.pinnedClient(caDer)
    }

    /** 只换会话（续期时用），不动 TLS 配置 */
    fun updateGatewaySession(session: GatewaySession) {
        if (activeGateway?.origin == session.origin) activeGateway = session
    }

    fun clearGateway() {
        activeGateway = null
        gatewayClient = null
    }

    /** 某个 base 该用哪个客户端 */
    private fun clientFor(base: String): OkHttpClient {
        val gw = activeGateway
        return if (gw != null && normalizeBase(base) == normalizeBase(gw.origin)) {
            gatewayClient ?: client
        } else {
            client
        }
    }

    //#endregion

    /**
     * 用户很可能直接把 `dsh web` 打印的那一整条地址粘进来（尾巴带 `?token=`）。
     * 这里把它拆成「干净的 base」+「令牌」两半。
     * 没有 token 参数时令牌为 null。
     */
    fun splitTokenUrl(raw: String): Pair<String, String?> {
        val t = raw.trim()
        val q = t.indexOf('?')
        if (q < 0) return normalizeBase(t) to null
        val base = normalizeBase(t.substring(0, q))
        var token: String? = null
        for (kv in t.substring(q + 1).split('&')) {
            val i = kv.indexOf('=')
            if (i > 0 && kv.substring(0, i).trim() == "token") {
                token = kv.substring(i + 1).trim().ifBlank { null }
            }
        }
        return base to token
    }

    /**
     * 从用户输入里抠出访问令牌。两种写法都认：
     *  - 整条地址：`http://127.0.0.1:3080/?token=XXX`（或 ngrok 域名那条）→ 取 `token=`
     *  - 裸令牌：`Xk9vQ2m...`（DSH 的启动令牌是 32 字节 base64url，43 个字符）
     */
    fun extractToken(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        splitTokenUrl(t).second?.let { return it }
        return t.takeIf { it.length in 8..512 && Regex("^[A-Za-z0-9_-]+$").matches(it) }
    }

    /**
     * 用启动令牌换一张签名 cookie —— 这就是 DSH 官方的浏览器认证握手。
     *
     * 上游行为（dsh-client-connection/lib/browser-auth.js）：
     * ```
     * GET /?token=<每进程随机 32 字节>   且 Host 匹配
     *   → 303 Location: /  +  Set-Cookie: dsh-auth-<sha256(authority)>=v1.<body>.<sig>
     *                        HttpOnly; SameSite=Strict; Max-Age=30 天
     * ```
     * 以后每个请求带上这张 cookie 就能过 `/api` 的认证闸门。
     * 交换密钥存在服务端 `.credentials.yaml` 里，**跨重启不变**，
     * 所以 cookie 能撑满 30 天，即使 `dsh web` 重启过也一样有效。
     *
     * @param base  DSH 服务地址（不带 `?token=`）
     * @param token 启动令牌
     */
    suspend fun exchangeToken(base: String, token: String): TokenExchange =
        withContext(Dispatchers.IO) {
            val b = normalizeBase(base)
            val url = "$b/?token=" + URLEncoder.encode(token, "UTF-8")
            val req = Request.Builder()
                .url(url)
                .addHeader("ngrok-skip-browser-warning", "true")
                .get()
                .build()
            try {
                authClient.newCall(req).execute().use { resp ->
                    val raw = resp.headers("Set-Cookie")
                        .firstOrNull { it.startsWith(COOKIE_PREFIX) }
                    if (raw != null) {
                        val pair = raw.substringBefore(';').trim()
                        if (pair.isNotEmpty()) return@withContext TokenExchange.Ok(pair)
                    }
                    // 没拿到 cookie，只能按状态码判因
                    when (resp.code) {
                        401 -> TokenExchange.Rejected
                        200 -> TokenExchange.NoAuthRequired
                        else -> TokenExchange.Failed("HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                TokenExchange.Failed(e.message ?: "连接失败")
            }
        }

    /**
     * 探一下这个地址的认证状态。
     * 判据很朴素：打一个最轻的 RPC，看会不会吃 401。
     */
    suspend fun probeAuth(base: String): AuthState = withContext(Dispatchers.IO) {
        try {
            rpc(base, "session/list", JSONObject().put("_request", JSONObject()))
            if (authCookie(base) != null) AuthState.AUTHENTICATED else AuthState.NOT_REQUIRED
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            when {
                msg.contains("401") -> AuthState.NEEDS_TOKEN
                else -> AuthState.OFFLINE
            }
        }
    }

    /** 打一个 RPC 端点，返回 result.value。失败抛 IOException。 */
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
                .addHeader("ngrok-skip-browser-warning", "true")
                .withAuth(base)
                .post(body.toRequestBody(JSON_MEDIA))
                .build()

            val envelope: JSONObject = clientFor(base).newCall(req).execute().use { resp ->
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
        val value = rpc(base, "session/list", JSONObject().put("_request", JSONObject()))
        val items = value.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<DshSession>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            // 子代理会话不出现在聊天列表里
            if (it.optString("origin") == "subagent") continue
            val proj = it.optJSONObject("projections") ?: JSONObject()
            val values = proj.optJSONObject("values")
            // 空会话的 title 是 JSON null，optString 会给出字符串 "null"，这里要挡住
            val rawTitle = if (values != null && !values.isNull("title"))
                values.optString("title", "") else ""
            val named = rawTitle.isNotBlank() && rawTitle != "null"
            val title = if (named) rawTitle else it.optString("sessionId").takeLast(12)
            // 当前模型：优先「已选待用」的 next，其次最近用过的 lastUsed
            val ms = values?.optJSONObject("modelSelection")
            val picked = ms?.optJSONObject("next") ?: ms?.optJSONObject("lastUsed")
            out.add(
                DshSession(
                    sessionId = it.optString("sessionId"),
                    title = title,
                    named = named,
                    running = it.optBoolean("running", false),
                    asOfSeq = proj.optInt("asOfSeq", 0),
                    cwd = it.optString("cwd", "").ifBlank { null },
                    modelProvider = picked?.optString("provider")?.ifBlank { null },
                    modelId = picked?.optString("model")?.ifBlank { null },
                    modelEffort = picked?.optString("reasoningEffort")?.ifBlank { null }
                )
            )
        }
        out
    }

    /** 找指定会话的最新状态 */
    suspend fun getSession(base: String, sessionId: String): DshSession? =
        listSessions(base).firstOrNull { it.sessionId == sessionId }

    /**
     * 新建一个会话。
     *
     * 注意参数名：这个端点的 request 挂在 `args.request` 上，
     * 不是 session/list 那种 `args._request`。传错会得到
     * gateway/arguments-invalid（missing "request"; unexpected "_request"）。
     *
     * @param cwd         工作目录；留空用服务器默认目录
     * @param agentPreset 人格预设 id；留空由服务器解析默认预设
     */
    suspend fun createSession(
        base: String,
        cwd: String? = null,
        agentPreset: String? = null
    ): CreatedSession = withContext(Dispatchers.IO) {
        val request = JSONObject()
        if (!cwd.isNullOrBlank()) request.put("cwd", cwd.trim())
        if (!agentPreset.isNullOrBlank()) request.put("agentPreset", agentPreset.trim())
        val value = rpc(base, "session/create", JSONObject().put("request", request))
        val id = value.optString("sessionId", "")
        if (id.isBlank()) throw IllegalStateException("session/create 没返回 sessionId")
        CreatedSession(id, value.optString("agentPreset", "").ifBlank { null })
    }

    /**
     * 给会话起个文字名字（替换掉那个 sessionId 尾巴）。
     *
     * 约束来自 dsh-base 的 session-title 配置：
     *   maxTitleBytes: 80  —— 上限按 **UTF-8 字节**算，一个汉字 3 字节
     * 全是空白字符会被服务端拒绝：session/title-invalid。
     * 另外这个端点是给「活着的」会话用的 —— 冷会话它会先加载，一般也能成。
     *
     * @return 服务端归一化之后真正落盘的标题
     */
    suspend fun renameSession(base: String, sessionId: String, title: String): String {
        val value = rpc(base, "session/rename", JSONObject().put("request", JSONObject().apply {
            put("sessionId", sessionId)
            put("title", title)
        }))
        return value.optString("title", "").ifBlank { title }
    }

    /**
     * 列出可用的人格预设。这个端点不吃参数（args 传空对象就行）。
     * 默认那一个排最前，其余保持服务端顺序。
     */
    suspend fun listAgentPresets(base: String): List<AgentPreset> = withContext(Dispatchers.IO) {
        val value = rpc(base, "agentPresets/list", JSONObject())
        val arr = value.optJSONArray("presets") ?: return@withContext emptyList<AgentPreset>()
        val out = ArrayList<AgentPreset>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isBlank()) continue
            out.add(
                AgentPreset(
                    id = id,
                    name = o.optString("name", "").ifBlank { id },
                    description = o.optString("description", "").ifBlank { null },
                    isDefault = o.optBoolean("isDefault", false),
                    broken = o.optString("broken", "").ifBlank { null }
                )
            )
        }
        // 稳定排序：默认的提到最前，其余保持原顺序
        out.sortedByDescending { it.isDefault }
    }

    /**
     * 从模型目录里挑一个「能看图」的模型。
     * 判据是 id 里带 vision —— 目录本身不暴露模态，这是目前最可靠的信号。
     */
    suspend fun findVisionModel(base: String): ModelRef? = try {
        val value = rpc(base, "session/modelCatalog", JSONObject())
        val groups = value.optJSONArray("groups")
        var found: ModelRef? = null
        if (groups != null) {
            outer@ for (g in 0 until groups.length()) {
                val group = groups.optJSONObject(g) ?: continue
                val provider = group.optString("id", "")
                val models = group.optJSONArray("models") ?: continue
                for (m in 0 until models.length()) {
                    val obj = models.optJSONObject(m) ?: continue
                    val id = obj.optString("id", "")
                    if (id.contains("vision", ignoreCase = true)) {
                        found = ModelRef(provider, id, null)
                        break@outer
                    }
                }
            }
        }
        found
    } catch (_: Exception) {
        null
    }

    /** 给会话换模型 */
    suspend fun selectModel(base: String, sessionId: String, ref: ModelRef) {
        val request = JSONObject().apply {
            put("sessionId", sessionId)
            put("provider", ref.provider)
            put("model", ref.model)
            if (!ref.effort.isNullOrBlank()) put("reasoningEffort", ref.effort)
        }
        rpc(base, "session/selectModel", JSONObject().put("request", request))
    }

    /**
     * 发消息（可带图）。只入队，回复走 WebSocket。
     * 图片是直接把 base64 塞进 content 里的，没有单独的上传接口。
     */
    suspend fun sendPrompt(
        base: String,
        sessionId: String,
        text: String,
        images: List<PendingImage> = emptyList(),
        mode: String = "queue"
    ) {
        val content = JSONArray()
        if (text.isNotBlank()) {
            content.put(JSONObject().put("type", "text").put("text", text))
        }
        for (img in images) {
            content.put(JSONObject().apply {
                put("type", "image")
                put("mediaType", img.mediaType)
                put("data", img.base64)
                if (img.name.isNotBlank()) put("name", img.name)
            })
        }
        if (content.length() == 0) return

        val request = JSONObject().apply {
            put("requestId", UUID.randomUUID().toString())
            put("sessionId", sessionId)
            put("mode", mode)
            put("clientTimeZone", "Asia/Shanghai")
            put("content", content)
        }
        rpc(base, "session/prompt", JSONObject().put("request", request))
    }

    /**
     * 把历史里的一张图换回 base64。
     * attachmentId 是日志里那个不透明 id，不是文件路径。
     */
    suspend fun readAttachment(
        base: String,
        sessionId: String,
        attachmentId: String
    ): String? = try {
        val value = rpc(base, "session/attachment", JSONObject().apply {
            put("request", JSONObject().apply {
                put("sessionId", sessionId)
                put("attachmentId", attachmentId)
            })
        })
        value.optString("data", "").ifBlank { null }
    } catch (e: Exception) {
        null
    }

    /** 打断当前回合 */
    suspend fun cancel(base: String, sessionId: String): Boolean = try {
        rpc(base, "session/cancel", JSONObject().put("request", JSONObject().apply {
            put("sessionId", sessionId)
        }))
        true
    } catch (e: Exception) {
        false
    }

    /** 一页历史结果 */
    data class HistoryPage(
        val records: JSONArray,
        val hasMore: Boolean,
        val minSeq: Int
    )

    /**
     * 读会话记录（支持向前翻页）。
     * @param throughSeq 终点 seq（必填，一般填 asOfSeq）
     * @param beforeSeq 向前翻页游标：返回该 seq 之前的记录
     */
    suspend fun pageRecords(
        base: String,
        sessionId: String,
        throughSeq: Int,
        beforeSeq: Int? = null,
        maxMessages: Int = 20
    ): HistoryPage {
        val request = JSONObject().apply {
            put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
            put("throughSeq", throughSeq)
            put("maxMessages", maxMessages)
            if (beforeSeq != null) put("beforeSeq", beforeSeq)
        }
        val value = rpc(base, "session/page", JSONObject().put("request", request))
        val records = value.optJSONArray("records") ?: JSONArray()
        val hasMore = value.optBoolean("hasMore", false)
        var minSeq = Int.MAX_VALUE
        for (i in 0 until records.length()) {
            val rec = records.optJSONObject(i) ?: continue
            val seq = rec.optJSONObject("event")?.optInt("seq", Int.MAX_VALUE) ?: Int.MAX_VALUE
            if (seq in 1 until minSeq) minSeq = seq
        }
        return HistoryPage(records, hasMore, if (minSeq == Int.MAX_VALUE) 0 else minSeq)
    }

    //#region 上下文注入的判定与命名

    /** 这条 user/message 是不是「注入的上下文」而不是真人发言 */
    fun isContextInjection(source: JSONObject?): Boolean {
        if (source == null) return false
        val kind = source.optString("kind", "")
        return kind.isNotEmpty() && kind != "user"
    }

    /** 按 source 形态取生产者名，规则与网页版 contextProvenance 一致 */
    fun contextLabel(source: JSONObject?): String {
        if (source == null) return "context"
        val kind = source.optString("kind", "")
        return when (kind) {
            "plugin" -> source.optString("plugin", "").ifBlank { kind }
            "agent-instructions" -> joinField(source.optJSONArray("changes"), "path").ifBlank { kind }
            "session-reference" -> joinField(source.optJSONArray("references"), "label").ifBlank { kind }
            "skill-invocation" -> source.optString("name", "").ifBlank { kind }
            else -> kind.ifBlank { "context" }
        }
    }

    private fun joinField(arr: JSONArray?, field: String): String {
        if (arr == null) return ""
        val parts = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i)?.optString(field, "") ?: ""
            if (v.isNotEmpty()) parts.add(v)
        }
        return parts.joinToString(", ")
    }

    /** 折叠状态下跟在标题后面的那一小行说明 */
    fun contextSummary(source: JSONObject?, bodyText: String): String? {
        source?.takeIf { !it.isNull("summary") }?.optString("summary", "")?.let {
            if (it.isNotBlank()) return it
        }
        when (source?.optString("form", "")) {
            "catalog" -> source.optJSONArray("entries")?.let { return "共 ${it.length()} 个 skill" }
            "snapshot" -> {
                val sections = source.optJSONArray("sections")
                if (sections != null && sections.length() > 0) {
                    val names = ArrayList<String>()
                    for (i in 0 until sections.length()) {
                        val n = sections.optJSONObject(i)?.optString("name", "") ?: ""
                        if (n.isNotEmpty()) names.add(n)
                    }
                    if (names.isNotEmpty()) return names.joinToString(" · ")
                }
            }
        }
        return previewOneLine(bodyText)
    }

    /** 取正文第一段有内容的文字，压成一行 */
    fun previewOneLine(text: String, limit: Int = 70): String? {
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isNotEmpty()) {
                return if (t.length > limit) t.take(limit) + "…" else t
            }
        }
        return null
    }

    //#endregion

    /**
     * 把历史 records 解析成按时间排好的行。
     * - user/message：source.kind == user → 真人发言；否则 → 上下文注入
     * - assistant/message：按 content 块拆成 思考 / 工具 / 正文 行
     */
    fun parseHistoryRows(records: JSONArray): List<HistoryRow> {
        val out = ArrayList<HistoryRow>()

        for (i in 0 until records.length()) {
            val rec = records.optJSONObject(i) ?: continue
            if (rec.optString("type") != "event") continue
            val event = rec.optJSONObject("event") ?: continue
            val seq = event.optInt("seq", 0)

            when (event.optString("type")) {
                "user/message" -> {
                    val data = event.optJSONObject("data") ?: continue
                    val source = data.optJSONObject("source")
                    val content = data.optJSONArray("content") ?: continue
                    val text = extractText(content)
                    val images = extractImages(content)
                    if (text.isEmpty() && images.isEmpty()) continue
                    if (isContextInjection(source)) {
                        out.add(
                            HistoryRow(
                                seq = seq,
                                kind = MsgKind.CONTEXT,
                                text = text,
                                label = contextLabel(source),
                                summary = contextSummary(source, text),
                                form = source?.optString("form", "")?.ifBlank { null }
                            )
                        )
                    } else {
                        out.add(HistoryRow(seq, MsgKind.USER, text, images = images))
                    }
                }
                "assistant/message" -> {
                    val data = event.optJSONObject("data") ?: continue
                    val msg = data.optJSONObject("message") ?: data
                    val content = msg.optJSONArray("content") ?: continue
                    emitAssistantBlocks(content, seq, out)
                }
            }
        }
        // seq 相同的行保持产出顺序（Kotlin 的 sortedBy 是稳定排序）
        return out.sortedBy { it.seq }
    }

    /** 一条 assistant/message 里的块按原顺序展开：思考 → 工具 → 正文 */
    private fun emitAssistantBlocks(content: JSONArray, seq: Int, out: MutableList<HistoryRow>) {
        val pendingText = StringBuilder()

        fun flushText() {
            val t = pendingText.toString().trim()
            pendingText.setLength(0)
            if (t.isNotEmpty()) out.add(HistoryRow(seq, MsgKind.ASSISTANT, t))
        }

        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "reasoning" -> {
                    flushText()
                    val t = block.optString("text", "").trim()
                    if (t.isNotEmpty()) out.add(HistoryRow(seq, MsgKind.THINKING, t))
                }
                "tool-call" -> {
                    flushText()
                    val name = block.optString("name", "")
                    val raw = block.optString("arguments", "")
                    out.add(
                        HistoryRow(
                            seq = seq,
                            kind = MsgKind.TOOL,
                            text = if (raw.length > 4000) raw.take(4000) + "…" else raw,
                            toolName = name
                        )
                    )
                }
                "text" -> pendingText.append(block.optString("text", ""))
            }
        }
        flushText()
    }

    private fun extractText(content: JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text", ""))
        }
        return sb.toString().trim()
    }

    /**
     * 从消息内容里挑出图片块。
     * 落盘之后图片是「引用」形态：{type:"image", attachment:{attachmentId, mediaType, ...}}，
     * 真正的字节要用 attachmentId 再换回来。
     */
    private fun extractImages(content: JSONArray?): List<ChatImage> {
        if (content == null) return emptyList()
        val out = ArrayList<ChatImage>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") != "image") continue
            val ref = block.optJSONObject("attachment") ?: continue
            val id = ref.optString("attachmentId", "")
            if (id.isBlank()) continue
            out.add(
                ChatImage(
                    attachmentId = id,
                    mediaType = ref.optString("mediaType", "").ifBlank { null },
                    name = ref.optString("name", "").ifBlank { null }
                )
            )
        }
        return out
    }

    /** 把 chunkrow 里那段被切碎的字符串原样拼回来（token 边界就是数据，不能 join） */
    private fun joinTexts(arr: JSONArray?): String {
        if (arr == null) return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) sb.append(arr.optString(i, ""))
        return sb.toString()
    }

    /** 把一条实时的 user/message 事件转成注入行；真人发言返回 null（本地已经先行显示过了） */
    fun contextRowFromUserEvent(event: JSONObject): HistoryRow? {
        if (event.optString("type") != "user/message") return null
        val data = event.optJSONObject("data") ?: return null
        val source = data.optJSONObject("source")
        if (!isContextInjection(source)) return null
        val text = extractText(data.optJSONArray("content"))
        if (text.isEmpty()) return null
        return HistoryRow(
            seq = event.optInt("seq", 0),
            kind = MsgKind.CONTEXT,
            text = text,
            label = contextLabel(source),
            summary = contextSummary(source, text),
            form = source?.optString("form", "")?.ifBlank { null }
        )
    }

    /**
     * 轮询兜底：WebSocket 不可用时用。正常路径不用它。
     */
    suspend fun waitForEvents(
        base: String,
        sessionId: String,
        afterSeq: Int,
        timeoutMs: Long = 300_000,
        intervalMs: Long = 800,
        onEvent: (StreamEvent) -> Unit
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSeq = afterSeq
        var emptyRounds = 0
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            val item = getSession(base, sessionId) ?: break
            val records = pageRecords(base, sessionId, item.asOfSeq, maxMessages = 200).records
            val rows = parseHistoryRows(records).filter { it.seq > lastSeq }
            for (row in rows) {
                lastSeq = maxOf(lastSeq, row.seq)
                val type = when (row.kind) {
                    MsgKind.THINKING -> EventType.THINKING
                    MsgKind.TOOL -> EventType.TOOL_CALL
                    MsgKind.CONTEXT -> EventType.CONTEXT
                    else -> EventType.REPLY
                }
                onEvent(StreamEvent(row.seq, type, row.text, row.toolName, row.label, row.summary, row.form))
            }
            if (!item.running) {
                emptyRounds = if (rows.isEmpty()) emptyRounds + 1 else 0
                if (emptyRounds >= 3) break
            } else {
                emptyRounds = 0
            }
        }
    }

    /**
     * WebSocket 订阅会话实时事件（真正逐字流式）。
     *
     * @param onSnapshot 开窗快照到达 —— 说明订阅已经建立，此时再发 prompt 就不会漏事件
     * @param onEvent    增量事件
     * @param onTurnEnd  本轮结束（流里的 turn/end）
     * @param onDone     服务端结束该流
     * @param onError    出错
     */
    fun followSession(
        base: String,
        sessionId: String,
        onSnapshot: () -> Unit,
        onEvent: (StreamEvent) -> Unit,
        onTurnEnd: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ): WebSocket {
        val wsUrl = base.trimEnd('/')
            .replace("http://", "ws://")
            .replace("https://", "wss://") + "/api/remote.mux"
        val streamId = UUID.randomUUID().toString()

        val req = Request.Builder()
            .url(wsUrl)
            .addHeader("ngrok-skip-browser-warning", "true")
            // WebSocket 也走 /api 前缀，同样要过认证闸门
            .withAuth(base)
            .build()

        return clientFor(base).newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val openMsg = JSONObject().apply {
                    put("type", "open")
                    put("streamId", streamId)
                    put("endpoint", "session/follow")
                    put("payload", JSONObject().put("args", JSONObject().put("request", JSONObject().apply {
                        put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
                        put("maxMessages", 20)
                    })))
                }
                webSocket.send(openMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "item" -> {
                            val value = msg.optJSONObject("value") ?: return
                            when (value.optString("type")) {
                                "snapshot" -> onSnapshot()
                                "event" -> {
                                    val event = value.optJSONObject("event") ?: return
                                    val evType = event.optString("type")
                                    val seq = event.optInt("seq", 0)
                                    val data = event.optJSONObject("data")

                                    when (evType) {
                                        // 本轮结束，这就是最可靠的完成信号
                                        "turn/end" -> onTurnEnd()

                                        // 本轮开始时注入的上下文
                                        "user/message" -> {
                                            val row = contextRowFromUserEvent(event) ?: return
                                            onEvent(
                                                StreamEvent(
                                                    seq = row.seq,
                                                    type = EventType.CONTEXT,
                                                    text = row.text,
                                                    label = row.label,
                                                    summary = row.summary,
                                                    form = row.form
                                                )
                                            )
                                        }

                                        "chunkrow/reasoning-chunks" -> {
                                            val joined = joinTexts(data?.optJSONArray("texts"))
                                            if (joined.isNotEmpty()) {
                                                onEvent(StreamEvent(seq, EventType.THINKING, joined))
                                            }
                                        }
                                        "chunkrow/text-chunks" -> {
                                            val joined = joinTexts(data?.optJSONArray("texts"))
                                            if (joined.isNotEmpty()) {
                                                onEvent(StreamEvent(seq, EventType.REPLY, joined))
                                            }
                                        }
                                        "assistant/chunk" -> {
                                            val chunk = data?.optJSONObject("chunk") ?: return
                                            when (chunk.optString("type")) {
                                                "text-delta" -> {
                                                    val t = chunk.optString("text", "")
                                                    if (t.isNotEmpty()) {
                                                        onEvent(StreamEvent(seq, EventType.REPLY, t))
                                                    }
                                                }
                                                "reasoning-delta" -> {
                                                    val t = chunk.optString("text", "")
                                                    if (t.isNotEmpty()) {
                                                        onEvent(StreamEvent(seq, EventType.THINKING, t))
                                                    }
                                                }
                                                "block-end" -> {
                                                    val block = chunk.optJSONObject("block") ?: return
                                                    if (block.optString("type") == "tool-call") {
                                                        val name = block.optString("name", "")
                                                        val raw = block.optString("arguments", "")
                                                        val summary =
                                                            if (raw.length > 4000) raw.take(4000) + "…" else raw
                                                        onEvent(
                                                            StreamEvent(seq, EventType.TOOL_CALL, summary, name)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "error" -> {
                            val err = msg.optJSONObject("error")
                            onError(err?.optString("message") ?: "WebSocket error")
                        }
                        "end" -> onDone()
                    }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError(t.message ?: "WebSocket 连接失败")
            }
        })
    }
}
