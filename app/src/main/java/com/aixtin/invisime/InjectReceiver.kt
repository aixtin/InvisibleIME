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
 */
class InjectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INJECT_TEXT) return

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
    }
}
