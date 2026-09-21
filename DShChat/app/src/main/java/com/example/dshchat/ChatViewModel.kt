package com.example.dshchat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一条聊天消息 */
data class ChatMessage(
    val id: Long,
    val role: String,     // "user" / "assistant" / "system"
    val content: String
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** 模拟器访问电脑本机 DSH 的默认 base（不含 /api） */
        const val DEFAULT_BASE = "http://10.0.2.2:3080"

        /** ★ 固定会话：写死，不切换、不从本地配置读 */
        const val SESSION_ID = "session-1fdf2610-15d3-4639-8481-1f317994e13d"
    }

    private val prefs =
        application.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    var serverBase by mutableStateOf(DEFAULT_BASE)
        private set

    private var nextId = 0L

    init {
        // 只记 base（模拟器/真机/穿透会变），会话 ID 永远用写死的 SESSION_ID
        serverBase = prefs.getString("server_base", DEFAULT_BASE) ?: DEFAULT_BASE
    }

    fun saveBase(url: String) {
        serverBase = url
        prefs.edit().putString("server_base", url).apply()
    }

    fun clearChat() {
        _messages.value = emptyList()
    }

    /** 发一条用户消息：入队 → 轮询 DSH 直到回复完成。会话固定为 SESSION_ID。 */
    fun send(text: String) {
        val sessionId = SESSION_ID

        val userMsg = ChatMessage(nextId++, "user", text)
        _messages.update { it + userMsg }

        val assistantMsg = ChatMessage(nextId++, "assistant", "（DSH 正在思考…）")
        _messages.update { it + assistantMsg }

        viewModelScope.launch {
            _isSending.value = true
            try {
                // 发消息前记录 seq 基线
                val before = DshApi.getSession(serverBase, sessionId)?.asOfSeq ?: 0

                DshApi.sendPrompt(serverBase, sessionId, text)

                // 清空占位符，边轮询边追加正文
                replaceLastAssistant("")

                DshApi.waitForReply(serverBase, sessionId, before) { t ->
                    appendToLastAssistant(t.text)
                }

                if (lastAssistantContent().isBlank()) {
                    replaceLastAssistant("（DSH 本轮没有产出新正文）")
                }
            } catch (e: Exception) {
                replaceLastAssistant("⚠️ 请求失败：${e.message}")
            } finally {
                _isSending.value = false
            }
        }
    }

    private fun lastAssistantContent(): String {
        val list = _messages.value
        return if (list.isNotEmpty()) list.last().content else ""
    }

    private fun replaceLastAssistant(newText: String) {
        _messages.update { list ->
            val last = list.lastIndex
            list.mapIndexed { i, m -> if (i == last) m.copy(content = newText) else m }
        }
    }

    private fun appendToLastAssistant(delta: String) {
        _messages.update { list ->
            val last = list.lastIndex
            list.mapIndexed { i, m ->
                if (i == last) {
                    val cur = if (m.content == "（DSH 正在思考…）") "" else m.content
                    m.copy(content = if (cur.isEmpty()) delta else cur + "\n\n" + delta)
                } else m
            }
        }
    }
}
