package com.example.dshchat

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.WebSocket
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** 消息类型，决定 UI 显示样式 */
enum class MsgKind {
    /** 真人发言 */
    USER,

    /** 助手正文 */
    ASSISTANT,

    /** 思考过程（可折叠） */
    THINKING,

    /** 工具调用（可折叠） */
    TOOL,

    /** 注入的上下文（可折叠） */
    CONTEXT,

    /** 本地提示 / 报错 */
    SYSTEM;
}

/** 哪些类型算「过程行」，本轮结束后默认收起 */
val PROCESS_KINDS = setOf(MsgKind.THINKING, MsgKind.TOOL, MsgKind.CONTEXT)

/** 一条聊天消息 */
data class ChatMessage(
    val id: Long,
    val kind: MsgKind,
    val content: String,
    /** TOOL：工具名 */
    val toolName: String? = null,
    /** CONTEXT：生产者名 */
    val label: String? = null,
    /** CONTEXT：折叠时跟在标题后的一行说明 */
    val summary: String? = null,
    /** CONTEXT：source.form */
    val form: String? = null,
    /** 过程行是否展开。流式进行中为 true，回合结束自动收起；历史行默认 false */
    val open: Boolean = false,
    /** 附带的图片 */
    val images: List<ChatImage> = emptyList()
)

/** App 是否在前台 —— 「只在后台提醒」要用 */
object AppForeground {
    @Volatile
    var visible: Boolean = false
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** 默认地址：模拟器访问宿主机的写法。真机/穿透地址请在 App 设置里改，这里不要动。 */
        const val DEFAULT_BASE = ""
        const val DEFAULT_SESSION_ID = ""

        private const val HISTORY_PAGE_MESSAGES = 20
        private const val SNAPSHOT_WAIT_MS = 6_000L
        private const val TURN_TIMEOUT_MS = 900_000L
        private const val NOTIFY_CHANNEL_PREFIX = "dsh_chat"

        /** 记住多少个用过的工作目录 */
        private const val MAX_RECENT_CWD = 8
    }

    private val prefs =
        application.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE)

    /** deviceToken 的安全存储（Keystore 加密）。必须声明在 init 之前 */
    private val gatewayStore = GatewayStore(application)

    // 局域网直连的状态 —— 这些必须声明在 init 之前，否则 init 里赋值会 NPE
    var lanStatus by mutableStateOf("未配对")
        private set
    var lanWorking by mutableStateOf(false)
        private set
    var lanOrigin by mutableStateOf<String?>(null)
        private set
    var lanDeviceExpiresAt by mutableStateOf(0L)
        private set

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore

    private val _sessions = MutableStateFlow<List<DshSession>>(emptyList())
    val sessions: StateFlow<List<DshSession>> = _sessions

    private val _selectedSessionId = MutableStateFlow(DEFAULT_SESSION_ID)
    val selectedSessionId: StateFlow<String> = _selectedSessionId

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    //#region 新建会话

    /** 可用的人格预设（打开新建框时拉一次） */
    var presets by mutableStateOf<List<AgentPreset>>(emptyList())
        private set

    /** 最近用过的工作目录，最新的排最前 */
    var recentCwds by mutableStateOf<List<String>>(emptyList())
        private set

    //#region 认证

    /** 当前地址的认证状态（设置页显示用） */
    var authState by mutableStateOf(AuthState.UNKNOWN)
        private set

    /** 上一次认证动作的说明，给界面提示用 */
    var authHint by mutableStateOf<String?>(null)
        private set

    /** 探一次当前地址的认证状态 */
    fun refreshAuth() {
        viewModelScope.launch {
            authState = DshApi.probeAuth(serverBase)
        }
    }

    /** 忘了这个地址的 cookie（下次请求会吃 401，需要重新粘地址） */
    fun clearAuth() {
        DshApi.setAuthCookie(serverBase, null)
        persistAuthCookies()
        authState = AuthState.NEEDS_TOKEN
        authHint = "已清除这张令牌，下次要用得把带 token 的地址重新粘一次哦~"
        _toast.value = authHint
    }

    private fun restoreAuthCookies() {
        val raw = prefs.getString("auth_cookies", null) ?: return
        val map = HashMap<String, String>()
        runCatching {
            val obj = org.json.JSONObject(raw)
            for (k in obj.keys()) {
                val v = obj.optString(k, "")
                if (v.isNotBlank()) map[k] = v
            }
        }
        if (map.isNotEmpty()) DshApi.importCookies(map)
    }

    private fun persistAuthCookies() {
        val obj = org.json.JSONObject()
        for ((k, v) in DshApi.exportCookies()) obj.put(k, v)
        prefs.edit().putString("auth_cookies", obj.toString()).apply()
    }

    /**
     * 用一张启动令牌换 cookie 并落库。
     * @return 换成功了没有
     */
    private suspend fun applyToken(base: String, token: String): Boolean {
        return when (val r = DshApi.exchangeToken(base, token)) {
            is TokenExchange.Ok -> {
                DshApi.setAuthCookie(base, r.cookie)
                persistAuthCookies()
                authState = AuthState.AUTHENTICATED
                authHint = "认证成功~ 这张凭证能用 30 天♪"
                true
            }
            TokenExchange.NoAuthRequired -> {
                authState = AuthState.NOT_REQUIRED
                authHint = "这个服务器不要求认证（它只绑了本机，或者装了免认证插件）"
                true
            }
            TokenExchange.Rejected -> {
                authState = AuthState.NEEDS_TOKEN
                authHint = "这张令牌服务端不认 —— 多半是 dsh web 重启过，令牌换新的了"
                false
            }
            is TokenExchange.Failed -> {
                authState = AuthState.OFFLINE
                authHint = "换令牌失败：${r.message}"
                false
            }
        }
    }

    //#endregion

    /** 正在新建会话（按钮转圈用） */
    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating

    /** 当前会话的工作目录 —— 新建会话时的默认值 */
    val currentCwd: String?
        get() = _sessions.value
            .firstOrNull { it.sessionId == _selectedSessionId.value }?.cwd
            ?.ifBlank { null }

    /** 弹一个提示给界面消费（消费完要 clearToast） */
    fun postToast(text: String) {
        _toast.value = text
    }

    fun clearToast() {
        _toast.value = null
    }

    /** 拉一次人格预设名单；失败就静默留空，界面上会退回「（用默认）」 */
    fun loadPresets(force: Boolean = false) {
        if (!force && presets.isNotEmpty()) return
        viewModelScope.launch {
            presets = runCatching { DshApi.listAgentPresets(serverBase) }
                .getOrDefault(emptyList())
        }
    }

    /**
     * 新建会话：建好之后自动切过去。
     * @param cwd         工作目录，空串/null 表示用服务器默认目录
     * @param agentPreset 人格预设 id，null 表示让服务器解析默认预设
     * @param title       会话名称，非空就直接起好名字（省得显示成一串编号）
     */
    fun createSession(cwd: String? = null, agentPreset: String? = null, title: String? = null) {
        if (_creating.value) return
        _creating.value = true
        val dir = cwd?.trim().orEmpty()
        val name = title?.trim().orEmpty()
        viewModelScope.launch {
            try {
                val created = DshApi.createSession(serverBase, dir.ifBlank { null }, agentPreset)
                // 起名字失败不影响会话本身，所以单独兜住
                val named = if (name.isNotEmpty()) {
                    runCatching { DshApi.renameSession(serverBase, created.sessionId, name) }.getOrNull()
                } else null
                if (dir.isNotEmpty()) {
                    recentCwds = (listOf(dir) + recentCwds.filter { it != dir }).take(MAX_RECENT_CWD)
                    prefs.edit().putString("recent_cwds", recentCwds.joinToString("\n")).apply()
                }
                // 先把列表刷出来，再切过去（这样标题栏才找得到新会话）
                _sessions.value = DshApi.listSessions(serverBase)
                selectSession(created.sessionId)
                val who = presets.firstOrNull { it.id == created.agentPreset }?.name
                    ?: created.agentPreset
                _toast.value = when {
                    named != null && !who.isNullOrBlank() -> "「$named」建好啦~ 由「$who」陪你哦♪"
                    named != null -> "「$named」建好啦~♪"
                    !who.isNullOrBlank() -> "新会话建好啦~ 由「$who」陪你哦♪"
                    else -> "新会话建好啦~♪"
                }
            } catch (e: Exception) {
                _toast.value = "新建失败：${e.message}"
            } finally {
                _creating.value = false
            }
        }
    }

    /** 给某个会话改名字（标题用文字，不再是一串编号） */
    fun renameSession(sessionId: String, title: String) {
        val name = title.trim()
        if (name.isEmpty()) {
            _toast.value = "名字不能是空的哦，爱莉没法给它取名啦~"
            return
        }
        viewModelScope.launch {
            try {
                val accepted = DshApi.renameSession(serverBase, sessionId, name)
                _sessions.value = DshApi.listSessions(serverBase)
                _toast.value = "改好啦~ 现在它叫「$accepted」♪"
            } catch (e: Exception) {
                _toast.value = "改名失败：${e.message}"
            }
        }
    }

    //#endregion

    var serverBase by mutableStateOf(DEFAULT_BASE)
        private set

    /** 壁纸文件路径（拷贝到 App 私有目录的副本，重启也在） */
    var wallpaperPath by mutableStateOf<String?>(null)
        private set

    /** 壁纸不透明度 */
    var wallpaperAlpha by mutableStateOf(0.45f)
        private set

    /** 外观 / 通知设置 */
    var ui by mutableStateOf(UiSettings())
        private set

    /** 访问地址历史，最近的排最前 */
    var addresses by mutableStateOf<List<AddressEntry>>(emptyList())
        private set

    private val nextId = AtomicLong(0L)
    private var historyMinSeq = 0

    /** 本轮流式消息的锚点：新的一轮必须新建气泡，不能拼到上一轮去 */
    private var activeAssistantId: Long? = null
    private var activeThinkingId: Long? = null

    /** 发图时临时切到的「能看图」的模型，以及切之前用的是什么 */
    private var visionModel: ModelRef? = null
    private var visionProbed = false
    private var modelToRestore: ModelRef? = null

    init {
        serverBase = prefs.getString("server_base", DEFAULT_BASE) ?: DEFAULT_BASE
        prefs.getString("session_id", DEFAULT_SESSION_ID)?.let {
            _selectedSessionId.value = it
        }
        wallpaperPath = prefs.getString("wallpaper_path", null)
        wallpaperAlpha = prefs.getFloat("wallpaper_alpha", 0.45f)
        addresses = AddressBook.load(prefs.getString("address_book", null))
        if (addresses.isEmpty()) {
            addresses = AddressBook.push(emptyList(), serverBase)
        }
        recentCwds = prefs.getString("recent_cwds", null)
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        // 恢复各地址的认证 cookie（上游的签名密钥跨重启不变，所以这张 cookie 能撑 30 天）
        restoreAuthCookies()
        // 老版本默认「只在后台提醒」，很多人因此以为铃声坏了 —— 升级时统一放开一次，
        // 想让它在你看 App 时闭嘴，去设置里把那个开关打开就好。
        if (!prefs.getBoolean("notify_bg_migrated", false)) {
            prefs.edit()
                .putBoolean("notify_only_background", false)
                .putBoolean("notify_bg_migrated", true)
                .apply()
        }
        ui = UiSettings(
            userBubbleColor = prefs.getInt("user_bubble_color", 0),
            assistantBubbleColor = prefs.getInt("assistant_bubble_color", 0),
            bubbleAlpha = prefs.getFloat("bubble_alpha", 1f),
            weight = WeightOption.of(prefs.getString("font_weight", null)),
            assistantAvatarPath = prefs.getString("chat_avatar_assistant", null),
            userAvatarPath = prefs.getString("chat_avatar_user", null),
            notifyEnabled = prefs.getBoolean("notify_enabled", true),
            notifyOnlyBackground = prefs.getBoolean("notify_only_background", false),
            notifyShowContent = prefs.getBoolean("notify_show_content", true),
            soundMode = SoundMode.of(prefs.getString("notify_sound_mode", null)),
            notifySoundUri = prefs.getString("notify_sound_uri", null)
        )
        // 有局域网配对凭据、而且当前选的就是它 → 先续期再连（省得先报一次 401），否则直接连。
        if (gatewayStore.load() != null && prefs.getBoolean("lan_active", true)) restoreLan() else loadSessions()
    }

    private fun newId(): Long = nextId.incrementAndGet()

    //#region 设置

    /** 改设置并落盘 */
    fun applyUi(transform: (UiSettings) -> UiSettings) {
        val next = transform(ui)
        ui = next
        prefs.edit()
            .putInt("user_bubble_color", next.userBubbleColor)
            .putInt("assistant_bubble_color", next.assistantBubbleColor)
            .putFloat("bubble_alpha", next.bubbleAlpha)
            .putString("font_weight", next.weight.name)
            .putBoolean("notify_enabled", next.notifyEnabled)
            .putBoolean("notify_only_background", next.notifyOnlyBackground)
            .putBoolean("notify_show_content", next.notifyShowContent)
            .putString("chat_avatar_assistant", next.assistantAvatarPath)
            .putString("chat_avatar_user", next.userAvatarPath)
            .putString("notify_sound_mode", next.soundMode.name)
            .putString("notify_sound_uri", next.notifySoundUri)
            .apply()
    }

    fun setWallpaper(path: String?) {
        wallpaperPath = path
        prefs.edit().putString("wallpaper_path", path).apply()
    }

    fun applyWallpaperAlpha(value: Float) {
        val v = value.coerceIn(0.05f, 1f)
        wallpaperAlpha = v
        prefs.edit().putFloat("wallpaper_alpha", v).apply()
    }

    /** 聊天里的机器人（助手）头像 */
    fun setAssistantAvatar(path: String?) = applyUi { it.copy(assistantAvatarPath = path) }

    /** 聊天里我自己的头像 */
    fun setUserAvatar(path: String?) = applyUi { it.copy(userAvatarPath = path) }

    /** 通知里要不要显示回复内容 */
    fun setNotifyShowContent(on: Boolean) = applyUi { it.copy(notifyShowContent = on) }

    fun setSoundMode(mode: SoundMode) = applyUi { it.copy(soundMode = mode) }

    fun setSoundUri(uri: String?) =
        applyUi { it.copy(soundMode = SoundMode.CUSTOM, notifySoundUri = uri) }

    //#endregion

    //#region 局域网直连（dsh-mobile 网关）

    private fun updateLanStatus(text: String) {
        lanStatus = text
    }

    /**
     * 启动时恢复局域网连接：有凭据就续一次会话再切过去。
     * 恢复失败不影响 App 用别的方式连（会给出提示）。
     */
    fun restoreLan() {
        val stored = gatewayStore.load() ?: return
        lanOrigin = stored.origin
        lanDeviceExpiresAt = stored.deviceExpiresAt
        val origin = GatewayOrigin.parse(stored.origin) ?: return
        viewModelScope.launch {
            lanWorking = true
            try {
                val session = GatewayAuthClient.renew(origin, stored.deviceToken, stored.caDer)
                DshApi.useGateway(session, stored.caDer)
                serverBase = stored.origin
                prefs.edit().putString("server_base", stored.origin).apply()
                updateLanStatus("已连接（${origin.host}）")
                loadSessions()
            } catch (e: Exception) {
                DshApi.clearGateway()
                updateLanStatus("连接已失效：${e.message ?: "未知原因"}")
                _messages.value = listOf(
                    ChatMessage(
                        newId(), MsgKind.SYSTEM,
                        "⚠️ 局域网直连失效了（${e.message ?: "未知原因"}）\n去设置里重新配对一次就好~"
                    )
                )
            } finally {
                lanWorking = false
            }
        }
    }

    /**
     * 用配对密钥 + 电脑地址完成配对，然后切到局域网直连。
     *
     * @param rawKey  形如 `dsh1.<64位实例ID>.<43位令牌>`；也接受直接粘整条配对链接
     * @param rawHost 电脑地址，如 `192.168.1.3`（端口可省略，默认 3443）
     */
    fun pairLan(rawKey: String, rawHost: String) {
        if (lanWorking) return
        // 允许用户把整条配对链接粘进密钥框
        val keyText = Regex("dsh1\\.[a-f0-9]{64}\\.[A-Za-z0-9_-]{43}").find(rawKey)?.value
        val key = keyText?.let { PairingKey.parse(it) }
        if (key == null) {
            _toast.value = GatewayAuthError.KeyInvalid.message
            return
        }
        val hostText = rawHost.trim().ifBlank {
            Regex("https?://[^/\\s\"']+").find(rawKey)?.value.orEmpty()
        }
        val origin = GatewayOrigin.parse(hostText)
        if (origin == null) {
            _toast.value = "电脑地址填一下呀，比如 192.168.1.3"
            return
        }

        viewModelScope.launch {
            lanWorking = true
            updateLanStatus("正在连接 ${origin.host} …")
            try {
                // ① 发现：确认能连上，并核对实例ID
                val discovery = GatewayAuthClient.fetchDiscovery(origin)
                val advertised = discovery.optString("instanceId", "")
                if (advertised.isNotEmpty() && advertised != key.instanceId) {
                    throw GatewayAuthError.CaMismatch
                }
                // ② 取 CA 并做指纹绑定
                updateLanStatus("正在校验证书指纹…")
                val caDer = GatewayAuthClient.fetchCa(origin)
                if (!PinnedTls.validateCa(caDer, key.instanceId)) {
                    throw GatewayAuthError.CaMismatch
                }
                // ③ 配对
                updateLanStatus("正在配对…")
                val session = GatewayAuthClient.pair(origin, key, "Elysia_dsh", caDer)
                if (session.instanceId != key.instanceId) throw GatewayAuthError.CaMismatch

                // ④ 落盘 + 切换
                gatewayStore.save(session, caDer)
                DshApi.useGateway(session, caDer)
                lanOrigin = session.origin
                lanDeviceExpiresAt = session.deviceExpiresAt ?: 0L
                serverBase = session.origin
                addresses = AddressBook.push(addresses, session.origin)
                prefs.edit()
                    .putString("server_base", session.origin)
                    .putString("address_book", AddressBook.save(addresses))
                    .putBoolean("lan_active", true)
                    .apply()
                updateLanStatus("已连接（${origin.host}）")
                _toast.value = "配对成功~ 现在不用连数据线啦♪"
                loadSessions()
                refreshAuth()
            } catch (e: GatewayAuthError) {
                DshApi.clearGateway()
                updateLanStatus("配对失败：${e.message}")
                _toast.value = e.message
            } catch (e: Exception) {
                DshApi.clearGateway()
                updateLanStatus("配对失败：${e.message ?: "未知原因"}")
                _toast.value = "配对失败：${e.message ?: "未知原因"}"
            } finally {
                lanWorking = false
            }
        }
    }

    /** 忘掉这台电脑的配对（电脑端仍保留设备记录，可在那边撤销） */
    fun forgetLan() {
        gatewayStore.clear()
        DshApi.clearGateway()
        prefs.edit().putBoolean("lan_active", false).apply()
        lanOrigin = null
        lanDeviceExpiresAt = 0L
        updateLanStatus("未配对")
        _toast.value = "已忘掉这台电脑，需要时重新配对就好~"
    }

    //#endregion

    //#region 地址历史

    /**
     * 保存访问地址：当前生效 + 记进历史。
     *
     * 令牌可以两种方式给：
     *  - 直接拼在地址里（`http://127.0.0.1:3080/?token=XXX`）
     *  - 或者走 [rawToken] 这个独立入参：整条地址、或者裸令牌都认
     *
     * 拿到令牌后会换一张 30 天有效的签名 cookie，只把**干净的地址**存下来 ——
     * 历史里不会塞满一次性令牌，认证也不用每次都重来。
     */
    fun saveBase(url: String, rawToken: String? = null) {
        val (clean, addrToken) = DshApi.splitTokenUrl(url)
        val token = addrToken ?: rawToken?.let { DshApi.extractToken(it) }
        if (clean.isEmpty()) return
        viewModelScope.launch {
            if (token != null) {
                authHint = "正在用令牌换凭证…"
                applyToken(clean, token)
            }
            // 手动填了别的地址 → 说明不用局域网直连了，别让它在下次启动时又切回去
            val gwOrigin = DshApi.gateway?.origin
            if (gwOrigin != null && DshApi.normalizeBase(gwOrigin) != DshApi.normalizeBase(clean)) {
                DshApi.clearGateway()
                prefs.edit().putBoolean("lan_active", false).apply()
                updateLanStatus("已停用（当前用 ${clean} ）")
            }
            serverBase = clean
            addresses = AddressBook.push(addresses, clean)
            prefs.edit()
                .putString("server_base", clean)
                .putString("address_book", AddressBook.save(addresses))
                .apply()
            loadSessions()
            if (token == null) refreshAuth()
        }
    }

    /** 直接切到历史里的某个地址 */
    fun useAddress(url: String) {
        saveBase(url)
    }

    fun forgetAddress(url: String) {
        addresses = AddressBook.remove(addresses, url)
        prefs.edit().putString("address_book", AddressBook.save(addresses)).apply()
    }

    //#endregion

    fun reload() {
        loadSessions()
    }

    fun loadSessions() {
        viewModelScope.launch {
            android.util.Log.d("DshApi", "loadSessions 开始 base=$serverBase")
            try {
                val list = DshApi.listSessions(serverBase)
                android.util.Log.d("DshApi", "loadSessions 拿到 ${list.size} 个会话")
                _sessions.value = list
                if (list.isEmpty()) {
                    // 全新的服务器（或者刚认证完第一次连上）
                    _messages.value = listOf(
                        ChatMessage(
                            newId(), MsgKind.SYSTEM,
                            "这个服务器上还没有会话，点右上角「＋」建一个吧~♪"
                        )
                    )
                    _hasMore.value = false
                    return@launch
                }
                // 选中的会话不在了（换了服务器、或者被删了）→ 自动落到第一个
                if (list.none { it.sessionId == _selectedSessionId.value }) {
                    selectSession(list.first().sessionId)
                    return@launch
                }
                loadHistory()
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                val hint = if (msg.contains("401")) {
                    "⚠️ 服务器要求认证（401）—— 去设置里把 `dsh web` 打印的整条地址（带 ?token=）粘一次吧"
                } else {
                    "⚠️ 拉取会话失败：${e.message}"
                }
                _messages.update { it + ChatMessage(newId(), MsgKind.SYSTEM, hint) }
            }
        }
    }

    fun selectSession(sessionId: String) {
        _selectedSessionId.value = sessionId
        prefs.edit().putString("session_id", sessionId).apply()
        _messages.value = emptyList()
        // 翻页游标和流式锚点都属于上一个会话，换会话必须一起清掉
        historyMinSeq = 0
        _hasMore.value = false
        activeAssistantId = null
        activeThinkingId = null
        loadHistory()
    }

    fun clearChat() {
        _messages.value = emptyList()
    }

    /** 手动展开/收起某一条过程行 */
    fun toggleRow(id: Long) {
        _messages.update { list ->
            list.map { if (it.id == id) it.copy(open = !it.open) else it }
        }
    }

    //#region 历史

    /** 读最近历史 */
    fun loadHistory() {
        val sid = _selectedSessionId.value
        viewModelScope.launch {
            _isLoadingHistory.value = true
            try {
                val session = DshApi.getSession(serverBase, sid)
                if (session == null) {
                    _messages.update {
                        it + ChatMessage(newId(), MsgKind.SYSTEM, "⚠️ 找不到会话 $sid")
                    }
                    return@launch
                }
                val page = DshApi.pageRecords(
                    serverBase, sid, session.asOfSeq, maxMessages = HISTORY_PAGE_MESSAGES
                )
                _messages.value = DshApi.parseHistoryRows(page.records).map { it.toMessage() }
                historyMinSeq = page.minSeq
                _hasMore.value = page.hasMore
            } catch (e: Exception) {
                _messages.update {
                    it + ChatMessage(newId(), MsgKind.SYSTEM, "⚠️ 加载历史失败：${e.message}")
                }
            } finally {
                _isLoadingHistory.value = false
            }
        }
    }

    /** 往上翻页加载更早历史 */
    fun loadOlder() {
        if (!_hasMore.value || _isLoadingHistory.value) return
        val sid = _selectedSessionId.value
        val before = historyMinSeq
        if (before <= 0) {
            _hasMore.value = false
            return
        }
        viewModelScope.launch {
            _isLoadingHistory.value = true
            try {
                val session = DshApi.getSession(serverBase, sid) ?: return@launch
                val page = DshApi.pageRecords(
                    serverBase, sid, session.asOfSeq,
                    beforeSeq = before, maxMessages = HISTORY_PAGE_MESSAGES
                )
                val older = DshApi.parseHistoryRows(page.records)
                if (older.isNotEmpty()) {
                    _messages.value = older.map { it.toMessage() } + _messages.value
                }
                historyMinSeq = page.minSeq
                _hasMore.value = page.hasMore && page.minSeq > 0 && page.minSeq < before
            } catch (e: Exception) {
                _hasMore.value = false
            } finally {
                _isLoadingHistory.value = false
            }
        }
    }

    private fun HistoryRow.toMessage() = ChatMessage(
        id = newId(),
        kind = kind,
        content = text,
        toolName = toolName,
        label = label,
        summary = summary,
        form = form,
        open = false,
        images = images
    )

    //#region 历史图片

    /** 已经取回来的历史图片，按访问顺序做 LRU，最多留 24 张免得吃内存 */
    private val imageCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 24
    }
    private val imageRequested = HashSet<String>()

    private val _imageTick = MutableStateFlow(0)

    /** 图片缓存版本号：变一下就代表有新图到位了，UI 用它触发重绘 */
    val imageTick: StateFlow<Int> = _imageTick

    fun cachedImage(attachmentId: String): Bitmap? = imageCache[attachmentId]

    /**
     * 按需向服务端换回一张历史图片。
     * 落盘之后日志里只有 attachmentId，真正的字节要用 session/attachment 换。
     */
    fun requestImage(attachmentId: String) {
        if (imageCache.containsKey(attachmentId)) return
        if (!imageRequested.add(attachmentId)) return
        val sid = _selectedSessionId.value
        viewModelScope.launch {
            val b64 = DshApi.readAttachment(serverBase, sid, attachmentId)
            if (b64 == null) {
                imageRequested.remove(attachmentId)
                return@launch
            }
            val bmp = withContext(Dispatchers.Default) {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) {
                    null
                }
            }
            if (bmp != null) {
                imageCache[attachmentId] = bmp
                _imageTick.value = _imageTick.value + 1
            }
        }
    }

    //#endregion

    //#endregion

    /**
     * 发消息。
     * 顺序很关键：先开 WebSocket 等它把开窗快照推过来，再发 prompt —— 这样开头的事件一条都不会漏。
     * 轮次结束以流里的 `turn/end` 为准，不再靠轮询猜。
     */
    /** 待发送的图片：放在 VM 里，界面被系统重建也不会丢 */
    private val _pending = MutableStateFlow<List<PendingImage>>(emptyList())
    val pending: StateFlow<List<PendingImage>> = _pending

    fun addPending(items: List<PendingImage>) {
        if (items.isEmpty()) return
        _pending.update { (it + items).take(8) }
    }

    fun removePending(index: Int) {
        _pending.update { list -> list.filterIndexed { i, _ -> i != index } }
    }

    fun clearPending() {
        _pending.value = emptyList()
    }

    fun send(text: String) {
        val sessionId = _selectedSessionId.value
        val message = text.trim()
        val images = _pending.value
        if ((message.isEmpty() && images.isEmpty()) || _isSending.value) return
        _pending.value = emptyList()

        _messages.update {
            it + ChatMessage(
                newId(), MsgKind.USER, message,
                images = images.map { p ->
                    ChatImage(localPath = p.localPath, mediaType = p.mediaType, name = p.name)
                }
            )
        }
        _isSending.value = true
        activeAssistantId = null
        activeThinkingId = null
        // 起前台服务保活：不然 App 一挂后台，安卓就把进程冻了，连接断掉、完成信号收不到
        TurnWatchService.start(getApplication())

        viewModelScope.launch {
            var ws: WebSocket? = null
            var failure: String? = null
            try {
                val ready = CompletableDeferred<Unit>()
                val turnEnded = CompletableDeferred<Unit>()

                ws = DshApi.followSession(
                    base = serverBase,
                    sessionId = sessionId,
                    onSnapshot = { ready.complete(Unit) },
                    onEvent = { e -> applyStreamEvent(e) },
                    onTurnEnd = { turnEnded.complete(Unit) },
                    onDone = { turnEnded.complete(Unit) },
                    onError = { err ->
                        failure = err
                        ready.complete(Unit)
                        turnEnded.complete(Unit)
                    }
                )

                withTimeoutOrNull(SNAPSHOT_WAIT_MS) { ready.await() }

                if (failure == null) {
                    try {
                        switchToVisionIfNeeded(sessionId, images.isNotEmpty())
                        DshApi.sendPrompt(serverBase, sessionId, message, images)
                        withTimeoutOrNull(TURN_TIMEOUT_MS) { turnEnded.await() }
                    } catch (e: Exception) {
                        failure = "请求失败：${e.message}"
                    }
                }
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
            } finally {
                try {
                    ws?.close(1000, "done")
                } catch (_: Exception) {
                }
                _isSending.value = false
                // 本轮结束：过程行（思考 / 工具 / 上下文注入）自动收起
                collapseProcessRows()
                // 发图临时换的模型，用完切回去
                modelToRestore?.let { back ->
                    runCatching { DshApi.selectModel(serverBase, sessionId, back) }
                    modelToRestore = null
                }
                if (failure != null) {
                    _messages.update {
                        it + ChatMessage(newId(), MsgKind.SYSTEM, "⚠️ $failure")
                    }
                    // 流断了就回读历史兜底，保证内容完整
                    loadHistory()
                }
                notifyDone(failure)
                // 通知发完了再撤掉保活服务，顺序反了的话进程可能先被冻住
                TurnWatchService.stop(getApplication())
            }
        }
    }

    /**
     * 要发图就临时切到「能看图」的模型 —— 不然当前模型看不见图，白传。
     * 切之前用的是什么记在 [modelToRestore]，本轮结束再切回去。
     */
    private suspend fun switchToVisionIfNeeded(sessionId: String, needVision: Boolean) {
        if (!needVision) return
        try {
            if (!visionProbed) {
                visionModel = DshApi.findVisionModel(serverBase)
                visionProbed = true
            }
            val vision = visionModel ?: return
            val before = DshApi.getSession(serverBase, sessionId) ?: return
            val currentId = before.modelId
            // 本来就是视觉模型，不用动
            if (currentId == vision.model) return
            if (currentId != null && before.modelProvider != null) {
                modelToRestore = ModelRef(before.modelProvider, currentId, before.modelEffort)
            }
            DshApi.selectModel(serverBase, sessionId, vision)
        } catch (_: Exception) {
            // 切不过去就算了，别把发消息这件事弄挂
        }
    }

    /** 把流里的事件落到消息列表 */
    @Synchronized
    private fun applyStreamEvent(e: StreamEvent) {
        when (e.type) {
            EventType.THINKING -> {
                activeThinkingId = appendOrNew(activeThinkingId, MsgKind.THINKING, e.text)
            }
            EventType.REPLY -> {
                activeAssistantId = appendOrNew(activeAssistantId, MsgKind.ASSISTANT, e.text)
            }
            EventType.TOOL_CALL -> {
                _messages.update {
                    it + ChatMessage(
                        newId(), MsgKind.TOOL, e.text,
                        toolName = e.toolName, open = true
                    )
                }
            }
            EventType.CONTEXT -> {
                // 同一种注入在同一轮里可能重复出现，去重一下更清爽
                val label = e.label ?: "context"
                val dup = _messages.value.lastOrNull()?.let {
                    it.kind == MsgKind.CONTEXT && it.label == label && it.content == e.text
                } ?: false
                if (!dup) {
                    _messages.update {
                        it + ChatMessage(
                            newId(), MsgKind.CONTEXT, e.text,
                            label = label,
                            summary = e.summary,
                            form = e.form,
                            open = true
                        )
                    }
                }
            }
        }
    }

    private fun collapseProcessRows() {
        _messages.update { list ->
            list.map { if (it.open && it.kind in PROCESS_KINDS) it.copy(open = false) else it }
        }
    }

    //#region 通知

    /** 任务完成提醒 */
    private fun notifyDone(failure: String?) {
        if (!ui.notifyEnabled) return
        if (ui.notifyOnlyBackground && AppForeground.visible) return

        val title = if (failure == null) "DSH 回复完成" else "DSH 出错了"
        // 用户可以选择通知里不暴露内容，只提示「有新回复」
        val body = if (!ui.notifyShowContent) {
            if (failure == null) "爱莉回复好啦，点开看看吧~" else "好像出了点小状况，点开看看？"
        } else if (failure != null) {
            failure
        } else {
            _messages.value.lastOrNull { it.kind == MsgKind.ASSISTANT }
                ?.content?.let { DshApi.previewOneLine(it, 60) }
                ?: "你的消息已经回复好了，点此查看"
        }
        post(title, body, System.currentTimeMillis().toInt())
    }

    /** 立刻发一条，用来试听 / 试看效果。每次都换 id，保证一定响 */
    fun previewNotification() {
        post(
            "DSH 通知预览",
            "图标、铃声就是这样的效果啦~",
            (System.currentTimeMillis() % 100000).toInt() + 1000
        )
    }

    /** 真正发通知的地方 */
    private fun post(title: String, body: String, id: Int) {
        val ctx = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return

        val channelId = ensureChannel(nm)
        val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = Notification.Builder(ctx, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)

        // 状态栏小图标只能是单色的（系统铁律），ColorOS 的通知卡片又会用「应用图标」，
        // 所以这里用默认的就好，彩色交给 App 图标去表现。
        builder.setSmallIcon(Icon.createWithResource(ctx, android.R.drawable.ic_dialog_info))

        nm.notify(id, builder.build())
    }

    /** 渠道 id 由「声音配置」决定 —— 配置一变就是新渠道，这样声音才改得动 */
    private fun channelId(): String {
        val key = "${ui.soundMode.name}|${ui.notifySoundUri ?: ""}"
        return "${NOTIFY_CHANNEL_PREFIX}_" + Math.abs(key.hashCode()).toString(16)
    }

    /** 当前设置想要的铃声 Uri；静音就是 null */
    private fun desiredSound(): Uri? = when (ui.soundMode) {
        SoundMode.SILENT -> null
        SoundMode.SYSTEM -> android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
        SoundMode.CUSTOM -> {
            val uri = ui.notifySoundUri
            // 选了「自定义」却没挑曲子时退回系统默认，别让它变成哑巴
            if (uri.isNullOrBlank()) android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
            else Uri.parse(uri)
        }
    }

    /**
     * 通知渠道一旦建好，声音就改不动了（createNotificationChannel 对已存在的 id 是空操作）。
     * 所以：渠道 id 带上声音配置 + 发现实际铃声跟想要的不一致就删掉重建。
     */
    private fun ensureChannel(nm: NotificationManager): String {
        val id = channelId()
        val want = desiredSound()

        // 清掉别的声音配置留下的旧渠道。
        // 注意：正在被前台服务使用的频道禁止删除，删它会抛 SecurityException，
        // 所以这里必须兜住 —— 清理只是尽力而为，失败了不能影响发通知。
        runCatching {
            nm.notificationChannels?.forEach { existing ->
                if (existing.id.startsWith(NOTIFY_CHANNEL_PREFIX) && existing.id != id) {
                    nm.deleteNotificationChannel(existing.id)
                }
            }
        }

        val current = nm.getNotificationChannel(id)
        if (current != null && current.sound != want) {
            // 声音改不了，只能删了重建
            runCatching { nm.deleteNotificationChannel(id) }
        }

        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, "DSH 任务完成", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "DSH 回复完成提醒"
            if (want == null) {
                ch.setSound(null, null)
                ch.enableVibration(false)
            } else {
                ch.setSound(want ?: android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, notificationAudioAttributes())
                ch.enableVibration(true)
            }
            nm.createNotificationChannel(ch)
        }
        return id
    }

    private fun notificationAudioAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** 设置页里显示的一行诊断，肉眼就能看出为什么没响 */
    fun notificationSummary(): String {
        val ctx = getApplication<Application>()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        val enabled = granted && nm.areNotificationsEnabled()
        val ch = nm.getNotificationChannel(channelId())
        val importance = when (ch?.importance) {
            NotificationManager.IMPORTANCE_HIGH -> "高"
            NotificationManager.IMPORTANCE_DEFAULT -> "默认"
            NotificationManager.IMPORTANCE_LOW -> "低"
            NotificationManager.IMPORTANCE_MIN -> "最低"
            NotificationManager.IMPORTANCE_NONE -> "被关掉了"
            else -> "还没建（发一条就建好了）"
        }
        val sound = when {
            ui.soundMode == SoundMode.SILENT -> "静音"
            ch == null -> "还没建（发一条就建好了）"
            ch.sound == null -> "系统默认"
            else -> ch.sound.toString().substringAfterLast('/')
        }
        return "通知权限：${if (enabled) "已开启" else "被系统关掉了"}\n" +
            "渠道：${channelId()}\n" +
            "重要级别：$importance\n" +
            "铃声：$sound"
    }

    /** 读本地图并按需降采样（聊天里显示图片也用得上） */
    private fun decodeFileScaled(path: String?, maxSide: Int): Bitmap? {
        if (path.isNullOrEmpty()) return null
        return try {
            val f = File(path)
            if (!f.exists() || f.length() == 0L) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
    }

    //#endregion

    /**
     * 追加到指定气泡；锚点为 null 或气泡已不在列表里时新建一个。
     * 返回新的锚点 id。
     */
    @Synchronized
    private fun appendOrNew(anchor: Long?, kind: MsgKind, delta: String): Long {
        if (delta.isEmpty()) return anchor ?: 0L
        if (anchor != null && _messages.value.any { it.id == anchor }) {
            _messages.update { msgs ->
                msgs.map { if (it.id == anchor) it.copy(content = it.content + delta) else it }
            }
            return anchor
        }
        val id = newId()
        _messages.update { it + ChatMessage(id, kind, delta, open = kind in PROCESS_KINDS) }
        return id
    }
}
