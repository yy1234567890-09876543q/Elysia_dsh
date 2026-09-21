package com.example.dshchat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONArray
import org.json.JSONObject

/** 字体粗细档位 */
enum class WeightOption(val label: String, val weight: FontWeight) {
    LIGHT("细", FontWeight.Light),
    NORMAL("常规", FontWeight.Normal),
    MEDIUM("中粗", FontWeight.Medium),
    BOLD("粗", FontWeight.Bold);

    companion object {
        fun of(name: String?): WeightOption =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/** 通知声音模式 */
enum class SoundMode(val label: String) {
    SYSTEM("跟随系统"),
    SILENT("静音"),
    CUSTOM("自定义");

    companion object {
        fun of(name: String?): SoundMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** 外观 + 通知的可调项 */
data class UiSettings(
    /** 我的气泡颜色，0 = 跟随主题 */
    val userBubbleColor: Int = 0,
    /** 助手气泡颜色，0 = 跟随主题 */
    val assistantBubbleColor: Int = 0,
    /** 气泡整体透明度 */
    val bubbleAlpha: Float = 1f,
    /** 正文字体粗细 */
    val weight: WeightOption = WeightOption.NORMAL,

    /** 聊天里机器人（助手）头像 */
    val assistantAvatarPath: String? = null,
    /** 聊天里我自己的头像 */
    val userAvatarPath: String? = null,

    /** 任务完成提醒总开关 */
    val notifyEnabled: Boolean = true,
    /** 只在你没看 App 的时候提醒 */
    val notifyOnlyBackground: Boolean = false,
    /** 通知里是否显示回复内容（关掉就只显示「有新回复」） */
    val notifyShowContent: Boolean = true,
    val soundMode: SoundMode = SoundMode.SYSTEM,
    /** soundMode == CUSTOM 时的铃声 Uri（系统铃声或本地音频都行） */
    val notifySoundUri: String? = null
)

/** 预设气泡配色，0 表示跟随主题 */
val BUBBLE_PRESETS: List<Pair<String, Int>> = listOf(
    "默认" to 0,
    "樱粉" to 0xFFFFD9E8.toInt(),
    "天蓝" to 0xFFBBDEFB.toInt(),
    "薄荷" to 0xFFB2DFDB.toInt(),
    "淡紫" to 0xFFD1C4E9.toInt(),
    "奶油" to 0xFFFFF9C4.toInt(),
    "蜜桃" to 0xFFFFCCBC.toInt(),
    "浅灰" to 0xFFE0E0E0.toInt(),
    "黛蓝" to 0xFF37474F.toInt(),
    "墨黑" to 0xFF212121.toInt()
)

/** 颜色偏暗就自动配白字，否则配深字 */
fun contrastOn(color: Int): Color {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return if (luminance < 0.55) Color.White else Color(0xFF1B1B1B)
}

/** 一条访问地址历史 */
data class AddressEntry(val url: String, val lastUsedAt: Long)

/** 地址历史的序列化与维护 */
object AddressBook {
    private const val MAX_ENTRIES = 12

    fun load(raw: String?): List<AddressEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<AddressEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url", "")
                if (url.isNotBlank()) {
                    out.add(AddressEntry(url, o.optLong("lastUsedAt", 0L)))
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(list: List<AddressEntry>): String {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("url", e.url)
                put("lastUsedAt", e.lastUsedAt)
            })
        }
        return arr.toString()
    }

    /** 放到最前面，重复的只留一条 */
    fun push(list: List<AddressEntry>, url: String): List<AddressEntry> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return list
        val rest = list.filter { it.url != trimmed }
        return (listOf(AddressEntry(trimmed, System.currentTimeMillis())) + rest)
            .take(MAX_ENTRIES)
    }

    fun remove(list: List<AddressEntry>, url: String): List<AddressEntry> =
        list.filter { it.url != url }
}
