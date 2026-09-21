package com.example.dshchat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 一轮对话进行中的「保活」前台服务。
 *
 * 为什么非要有它：
 *   安卓从 12 起会把缓存进程**冻结**（App Freezer），网络回调和 WebSocket
 *   全都收不到数据。所以 App 一挂到后台，等回复的那条连接就被掐断了 ——
 *   `turn/end` 永远不来，「任务完成」的通知自然也就发不出来，
 *   非要人点进 App 才看得到。
 *
 *   起一个前台服务能让进程保持活跃（同时给用户一条"正在等回复"的可见提示），
 *   等回复期间连接就不会被系统冻掉；回合结束后立刻停掉，不留常驻通知。
 */
class TurnWatchService : Service() {

    companion object {
        /**
         * 常驻通知的频道 id（低优先级、静音，不打扰）。
         *
         * ⚠️ 注意：**不能以 `dsh_chat` 开头** —— ChatViewModel.ensureChannel()
         * 会清理所有 `dsh_chat*` 前缀的旧频道，而正在被前台服务使用的频道
         * 是禁止删除的（会抛 SecurityException 直接崩掉 App）。
         */
        const val CHANNEL_ID = "dsh_turn_watch"
        private const val CHANNEL_NAME = "正在等待回复"
        private const val NOTIF_ID = 90001

        /** 开始一轮对话时调用 */
        fun start(ctx: Context) {
            val intent = Intent(ctx, TurnWatchService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (_: Exception) {
                // 起不来也不影响正常收发，只是后台可能收不到通知
            }
        }

        /** 回合结束（成功或失败）时调用 */
        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, TurnWatchService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
            stopSelf()
        }
        // 系统若回收了它，不要用空 intent 重启
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW // 静音、不弹横幅
                ).apply {
                    description = "等 DSH 回复期间的常驻提示，回合结束会自动消失"
                    setShowBadge(false)
                }
            )
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("正在等 DSH 回复…")
            .setContentText("回好了会另外提醒你，这个提示会自动消失")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}
