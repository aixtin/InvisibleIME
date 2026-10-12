package com.aixtin.invisime.http

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import com.aixtin.invisime.state.ImeState
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.Socket

/**
 * 注入器 HTTP 服务端
 *
 * 固定端口 8767，提供以下端点：
 * - POST /         → 统一指令入口（cmd 字段分发）
 * - GET  /state    → 查询当前状态
 * - POST /inject   → 快捷文本注入（等价于 cmd=type）
 */
class InvisHttpServer(
    private val port: Int = 8767,
    private val authToken: String,
    private val stateProvider: () -> ImeState,
    private val connectionProvider: () -> InputConnection?,
    private val editorInfoProvider: () -> android.view.inputmethod.EditorInfo?
) : NanoHTTPD("127.0.0.1", port) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var seqCounter = 0L

    init {
        start(SOCKET_READ_TIMEOUT, false)
        android.util.Log.i(TAG, "InvisHttpServer started on port $port")
    }

    override fun serve(session: IHTTPSession): Response {
        val auth = session.headers["x-auth"] ?: ""
        if (auth != authToken) {
            return jsonResponse(Response.Status.FORBIDDEN, errorJson("FORBIDDEN", "未授权: 缺少或错误的 X-Auth 令牌"))
        }

        val uri = session.uri
        val method = session.method

        return try {
            when {
                uri == "/" && method == Method.GET -> handleHello()
                uri == "/" && method == Method.POST -> handleCommand(session)
                uri == "/state" && method == Method.GET -> handleState()
                uri == "/inject" && method == Method.POST -> handleInjectShortcut(session)
                else -> jsonResponse(Response.Status.NOT_FOUND, errorJson("NOT_FOUND", "未知端点: $method $uri"))
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "HTTP handler error", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, errorJson("INTERNAL_ERROR", e.message ?: "未知错误"))
        }
    }

    // ─── HELLO 握手 ───────────────────────────────────────────
    private fun handleHello(): Response {
        val json = JSONObject().apply {
            put("name", "InvisibleIME")
            put("protocol_version", "1.0")
            put("capabilities", listOf("type", "backspace", "enter", "get_text", "select", "composition"))
            put("auth", "token")
            put("status", stateProvider().name)
        }
        return jsonResponse(Response.Status.OK, json.toString())
    }

    // ─── 请求体读取与编码修复 ─────────────────────────────────

    /**
     * 读取 POST 请求体。
     * NanoHTTPD 在 Content-Type 未声明 charset 时可能按 ISO-8859-1 解码，
     * 中文会变 mojibake（1 字节 = 1 字符）。此处做自动还原。
     */
    private fun readBody(session: IHTTPSession): String? {
        return try {
            val len = session.headers["content-length"]?.toIntOrNull() ?: return null
            val bytes = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = session.inputStream.read(bytes, off, len - off)
                if (n < 0) break
                off += n
            }
            val raw = String(bytes, 0, off, Charsets.UTF_8)
            fixMojibake(raw)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * mojibake 还原：ISO-8859-1 bytes → UTF-8。
     * - 原串已含 >255 字符 → 解码本来就正确，原样返回
     * - 全部 ≤255 且 roundtrip 解出合法 UTF-8 → 是 mojibake，返回还原结果
     * - 还原失败（出现 U+FFFD）→ 原样返回
     */
    private fun fixMojibake(s: String): String {
        if (s.isEmpty() || s.any { it.code > 255 }) return s
        return try {
            val decoded = String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            if (decoded.contains('\uFFFD')) s else decoded
        } catch (e: Exception) {
            s
        }
    }

    // ─── 统一指令入口 ─────────────────────────────────────────
    private fun handleCommand(session: IHTTPSession): Response {
        val postBody = readBody(session) ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            errorJson("BAD_REQUEST", "请求体为空")
        )

        val json = try {
            JSONObject(postBody)
        } catch (e: Exception) {
            return jsonResponse(Response.Status.BAD_REQUEST, errorJson("BAD_REQUEST", "JSON 解析失败: ${e.message}"))
        }

        val cmd = json.optString("cmd", "").uppercase()
        val seq = json.optLong("seq", nextSeq())

        return when (cmd) {
            "TYPE" -> handleType(json, seq)
            "BACKSPACE" -> handleBackspace(seq)
            "ENTER" -> handleEnter(seq)
            "GET_TEXT" -> handleGetText(json, seq)
            "SELECT" -> handleSelect(json, seq)
            "COMPOSITION" -> handleComposition(json, seq)
            else -> jsonResponse(Response.Status.BAD_REQUEST, errorJson("UNKNOWN_CMD", "未知指令: $cmd"))
        }
    }

    // ─── /inject 快捷入口 ─────────────────────────────────────
    private fun handleInjectShortcut(session: IHTTPSession): Response {
        val postBody = readBody(session) ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            errorJson("BAD_REQUEST", "请求体为空")
        )

        val json = try {
            JSONObject(postBody)
        } catch (e: Exception) {
            return jsonResponse(Response.Status.BAD_REQUEST, errorJson("BAD_REQUEST", "JSON 解析失败"))
        }

        val text = json.optString("text", "")
        if (text.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, errorJson("BAD_REQUEST", "text 字段为空"))
        }

        return executeType(text, nextSeq())
    }

    // ─── GET /state ───────────────────────────────────────────
    private fun handleState(): Response {
        val state = stateProvider()
        val info = editorInfoProvider()
        val json = JSONObject().apply {
            put("state", state.name)
            put("input_ready", state == ImeState.INPUT_READY)
            put("package_name", info?.packageName ?: "unknown")
            put("field_id", info?.fieldId ?: 0)
            put("input_type", info?.inputType ?: 0)
        }
        return jsonResponse(Response.Status.OK, json.toString())
    }

    // ─── TYPE 指令 ────────────────────────────────────────────
    private fun handleType(json: JSONObject, seq: Long): Response {
        val text = json.optString("text", "")
        if (text.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, errorJson("BAD_REQUEST", "text 字段为空", seq))
        }
        return executeType(text, seq)
    }

    private fun executeType(text: String, seq: Long): Response {
        val conn = connectionProvider()
        if (conn == null) {
            return jsonResponse(Response.Status.OK, errorJson("INPUT_LOST", "输入焦点已丢失", seq))
        }

        // 在主线程执行 commitText，用 CountDownLatch 同步等待结果
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = booleanArrayOf(false)
        mainHandler.post {
            result[0] = conn.commitText(text, 1)
            latch.countDown()
        }
        latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)

        return if (result[0]) {
            jsonResponse(Response.Status.OK, okJson(seq, JSONObject().put("committed", text.length)))
        } else {
            jsonResponse(Response.Status.OK, errorJson("INPUT_FAILED", "commitText 返回 false", seq))
        }
    }

    // ─── BACKSPACE 指令 ───────────────────────────────────────
    private fun handleBackspace(seq: Long): Response {
        val conn = connectionProvider() ?: return jsonResponse(
            Response.Status.OK, errorJson("INPUT_LOST", "输入焦点已丢失", seq)
        )

        val latch = java.util.concurrent.CountDownLatch(1)
        val result = booleanArrayOf(false)
        mainHandler.post {
            // 删除光标前一个字符
            result[0] = conn.deleteSurroundingText(1, 0)
            latch.countDown()
        }
        latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)

        return jsonResponse(Response.Status.OK, okJson(seq, JSONObject().put("deleted", result[0])))
    }

    // ─── ENTER 指令（换行，不发送） ──────────────────────────
    private fun handleEnter(seq: Long): Response {
        val conn = connectionProvider() ?: return jsonResponse(
            Response.Status.OK, errorJson("INPUT_LOST", "输入焦点已丢失", seq)
        )

        val latch = java.util.concurrent.CountDownLatch(1)
        val result = booleanArrayOf(false)
        mainHandler.post {
            // 注入换行符（\n），不触发发送
            result[0] = conn.commitText("\n", 1)
            latch.countDown()
        }
        latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)

        return jsonResponse(Response.Status.OK, okJson(seq, JSONObject().put("enter", result[0])))
    }

    // ─── GET_TEXT 指令 ────────────────────────────────────────
    private fun handleGetText(json: JSONObject, seq: Long): Response {
        val conn = connectionProvider() ?: return jsonResponse(
            Response.Status.OK, errorJson("INPUT_LOST", "输入焦点已丢失", seq)
        )

        val beforeLen = json.optInt("before", 100)
        val afterLen = json.optInt("after", 0)

        val latch = java.util.concurrent.CountDownLatch(1)
        val textHolder = arrayOf<CharSequence?>(null)
        mainHandler.post {
            textHolder[0] = conn.getTextBeforeCursor(beforeLen, 0)
            latch.countDown()
        }
        latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)

        return jsonResponse(Response.Status.OK, okJson(seq, JSONObject().put("text", textHolder[0]?.toString() ?: "")))
    }

    // ─── SELECT 指令 ──────────────────────────────────────────
    private fun handleSelect(json: JSONObject, seq: Long): Response {
        val conn = connectionProvider() ?: return jsonResponse(
            Response.Status.OK, errorJson("INPUT_LOST", "输入焦点已丢失", seq)
        )

        val start = json.optInt("start", 0)
        val end = json.optInt("end", 0)

        val latch = java.util.concurrent.CountDownLatch(1)
        val result = booleanArrayOf(false)
        mainHandler.post {
            result[0] = conn.setSelection(start, end)
            latch.countDown()
        }
        latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)

        return jsonResponse(Response.Status.OK, okJson(seq, JSONObject().put("selected", result[0])))
    }

    // ─── COMPOSITION 指令 ─────────────────────────────────────
    private fun handleComposition(json: JSONObject, seq: Long): Response {
        val conn = connectionProvider() ?: return jsonResponse(
            Response.Status.OK, errorJson("INPUT_LOST", "输入焦点已丢失", seq)
        )

        val text = json.optString("text", "")
        val newCursorPosition = json.optInt("newCursorPosition", 1)

        val latch = java.util.concurrent.CountDownLatch(1)
        val result = booleanArrayOf(false)
        mainHandler.post {
            result[0] = conn.setComposingText(text, newCursorPosition)
            latch.countDown()
        }
        latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)

        return jsonResponse(Response.Status.OK, okJson(seq, JSONObject().put("composing", result[0])))
    }

    // ─── 工具方法 ─────────────────────────────────────────────
    private fun nextSeq(): Long = ++seqCounter

    private fun okJson(seq: Long, result: JSONObject): String {
        return JSONObject().apply {
            put("ok", true)
            put("seq", seq)
            put("result", result)
        }.toString()
    }

    private fun errorJson(code: String, msg: String, seq: Long = 0): String {
        return JSONObject().apply {
            put("ok", false)
            put("seq", seq)
            put("error", JSONObject().apply {
                put("code", code)
                put("msg", msg)
            })
        }.toString()
    }

    private fun jsonResponse(status: Response.Status, body: String): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body)
    }

    fun shutdown() {
        stop()
        android.util.Log.i(TAG, "InvisHttpServer stopped")
    }

    companion object {
        private const val TAG = "InvisHttpServer"
        private const val SOCKET_READ_TIMEOUT = 5000
    }
}
