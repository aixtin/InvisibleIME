package com.aixtin.invisime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 广播入口（辅助通道）
 *
 * 接收 com.aixtin.invisime.INJECT_TEXT 广播，将文本转发给 InvisImeService 处理。
 * 单向盲发、无回执，仅作快速入口。严肃场景走 HTTP。
 *
 * 与 HTTP 同一鉴权：extra 必须携带 auth 令牌（每设备随机，见 README 获取方式），
 * 无令牌/令牌错误一律丢弃。普通 App 无法读取令牌，无法通过广播注入。
 */
class InjectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INJECT_TEXT) return

        // 令牌校验：与 HTTP X-Auth 同一把锁，无令牌/错误令牌直接丢弃
        val auth = intent.getStringExtra(EXTRA_AUTH)
        val prefs = context.getSharedPreferences("invisime_prefs", Context.MODE_PRIVATE)
        val expect = prefs.getString("auth_token", null)
        if (auth.isNullOrEmpty() || expect.isNullOrEmpty() || auth != expect) {
            Log.w(TAG, "Rejected broadcast: missing/wrong auth token")
            return
        }

        val text = intent.getStringExtra(EXTRA_TEXT)
        if (text.isNullOrEmpty()) {
            Log.w(TAG, "Received broadcast with empty text")
            return
        }

        Log.d(TAG, "Received broadcast inject: ${text.take(50)}...")

        // 通过静态引用调用当前 IME 服务的注入方法
        InvisImeService.instance?.injectFromBroadcast(text)
    }

    companion object {
        private const val TAG = "InjectReceiver"
        const val ACTION_INJECT_TEXT = "com.aixtin.invisime.INJECT_TEXT"
        const val EXTRA_TEXT = "text"
        const val EXTRA_AUTH = "auth"
    }
}
