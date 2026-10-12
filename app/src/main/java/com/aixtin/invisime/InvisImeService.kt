package com.aixtin.invisime

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import com.aixtin.invisime.http.InvisHttpServer
import com.aixtin.invisime.state.ImeState
import java.util.UUID

/**
 * InvisibleIME —— AI 专属隐形注入器
 *
 * 实现策略（ColorOS Android 14 实测定稿）：
 * - 标准 InputMethodService 流程
 * - 输入视图物理高 900px → 骗过 OPPO"键盘是否真实存在"的窗口高度门控，
 *   IME 进程内嵌的系统底栏（chevron + 输入法切换器）才会被完整渲染
 * - onComputeInsets 上报 contentTop/visibleTop = 窗口全高 → App 收到的
 *   ime insets 为 0（设备 dex 实证 IME source frame 只认 content insets）
 * - 900px 内容区透明 + touchableRegion 仅底部 navbar 条 → 零遮挡、触摸点穿
 * - OPPO ext 会因 content insets 全高而藏底栏按钮，由 OnPreDrawListener
 *   每帧守护，强制恢复可见
 *
 * 安全边界：
 * - 只做"写"（文本注入），永远不碰"点"（发送按钮、点击动作）
 * - enter = 换行，不触发发送
 * - 不改写文本内容
 * - 不静默切换输入法（切换器只负责显示系统选择器入口）
 */
class InvisImeService : InputMethodService() {

    private var currentState = ImeState.IDLE
    private var currentEditorInfo: EditorInfo? = null
    private var httpServer: InvisHttpServer? = null

    companion object {
        private const val TAG = "InvisImeService"
        private const val HTTP_PORT = 8767

        /**
         * IME 输入视图物理高度（900 内容区 + 132 系统底栏 = 1032 窗口）。
         *
         * 为什么不是 1px（ColorOS 实测根因）：
         * OPPO SystemUI 按 IME 窗口物理高度判断"键盘是否真实存在"——
         * 窗口高度 ≈ navbar 高度（空壳）时，隐藏 IME 进程自绘的
         * NavigationBarFrame（chevron + 输入法切换器）；
         * 窗口带有键盘内容区时才完整渲染。
         *
         * 为什么 App 仍能零推移：
         * onComputeInsets 把 contentTopInsets/visibleTopInsets 上报为窗口全高，
         * server 端 IME insets source frame 被 inset 到 0 高（设备 dex 实证：
         * DisplayPolicy 的 IME frame provider 只认 mGivenContentInsets，
         * 不认 providedInsets），App 收到的 ime insets 为 0。
         * OPPO 此时会把底栏子 View 设 INVISIBLE + alpha=0，由
         * [navbarGuard] 在每帧绘制前强制恢复。
         */
        private const val IME_CONTENT_HEIGHT = 900

        /** OPPO 会藏起来、但本产品要求始终可见的系统底栏控件（android 包资源 id）。 */
        private val NAV_KEY_VIEW_IDS = listOf(
            "input_method_navigation_bar_view", // 整个底栏容器（OPPO 主要藏它）
            "input_method_nav_back",            // 左侧收起箭头
            "input_method_nav_ime_switcher"     // 右侧输入法切换器
        )

        @Volatile
        var instance: InvisImeService? = null
            private set
    }

    // ─── 生命周期 ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "InvisibleIME created")

        // 首次装机自动生成随机访问令牌并持久化：每台设备拥有唯一默认令牌，
        // 之后每次启动读取同一令牌；卸载重装会重新生成
        val prefs = getSharedPreferences("invisime_prefs", MODE_PRIVATE)
        var authToken = prefs.getString("auth_token", null)
        if (authToken.isNullOrEmpty()) {
            authToken = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("auth_token", authToken).apply()
        }

        // 启动 HTTP 服务端
        // connectionProvider 直接用系统实时提供的 currentInputConnection，
        // 不缓存 → 永远是活的连接，杜绝"连接失效"问题
        httpServer = InvisHttpServer(
            port = HTTP_PORT,
            authToken = authToken,
            stateProvider = { currentState },
            connectionProvider = { currentInputConnection },
            editorInfoProvider = { currentEditorInfo }
        )
    }

    override fun onDestroy() {
        httpServer?.shutdown()
        instance = null
        currentState = ImeState.IDLE
        Log.i(TAG, "InvisibleIME destroyed")
        super.onDestroy()
    }

    // ─── 输入视图（900px 透明占位） ───────────────────────────

    /**
     * 标准 IME 流程：wrap_content 窗口由输入视图的测量高度决定。
     *
     * 关键坑（AOSP 源码验证）：
     * 1. setInputView() 会把视图参数强制替换成 (MATCH_PARENT, WRAP_CONTENT)，
     *    代码里设的 LayoutParams 会被丢弃
     * 2. 裸 View 的默认 onMeasure 在 WRAP_CONTENT(AT_MOST) 下返回全部可用高度，
     *    视图会被量成全屏
     *
     * 解法：覆写 onMeasure 强制量出 [IME_CONTENT_HEIGHT]。
     * 窗口物理够高 → OPPO 渲染完整系统底栏；App 零推移由 onComputeInsets
     * 上报全高 content insets 实现；视图本身透明且不可触。
     */
    override fun onCreateInputView(): View {
        Log.d(TAG, "Created invisible input view (height=$IME_CONTENT_HEIGHT, transparent)")
        return object : View(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), IME_CONTENT_HEIGHT)
            }
        }
    }

    // ─── 隐形窗口：零推移 / 零遮挡 / 触摸点穿 ─────────────────

    /**
     * 设备 dex 实证（DisplayPolicy.lambda$getImeSourceFrameProvider$2）：
     * server 端 IME source frame = IME 窗口 frame，再 inset(mGivenContentInsets)。
     * 即 App 收到的 ime insets 高度 = 窗口高 - contentTopInsets。
     *
     * contentTopInsets = visibleTopInsets = 窗口全高 → source frame 0 高
     * → 现代 App（WindowInsets.ime()）与老式 resize/pan App（visibleDisplayFrame）
     * 都零推移。
     *
     * 代价：OPPO ext 据此判定"键盘无内容区"，会把底栏 chevron/切换器设
     * INVISIBLE——由 [installNavbarGuard] 的 OnPreDrawListener 每帧扳回。
     *
     * 触摸：仅底部 navbar 条可触，其余区域触摸事件直穿到下层 App。
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val winH = window?.window?.decorView?.height
            ?.takeIf { it > 0 } ?: (IME_CONTENT_HEIGHT + navBarHeight())
        // 底栏可触区以 NavigationBarFrame 在窗口中的实际位置为准
        // （ColorOS 手势模式下 navigation_bar_height 资源只返回 48px，
        // 而 IME navbar frame 实际高 132px，不能信资源）。
        val cached = navbarFrameRect
        val navTop = if (cached != null && cached.bottom <= winH) cached.top
                     else IME_CONTENT_HEIGHT
        val regionLeft = cached?.left ?: 0
        val regionRight = cached?.right ?: resources.displayMetrics.widthPixels
        outInsets.contentTopInsets = winH
        outInsets.visibleTopInsets = winH
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(regionLeft, navTop, regionRight, winH)
    }

    private fun navBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 132
    }

    // ─── 输入焦点管理（核心） ────────────────────────────────

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)

        currentEditorInfo = info

        if (currentInputConnection != null) {
            currentState = ImeState.INPUT_READY
            Log.i(TAG, "Input started → INPUT_READY (package=${info?.packageName}, fieldId=${info?.fieldId})")
        } else {
            currentState = ImeState.INPUT_LOST
            Log.w(TAG, "Input started but connection is null → INPUT_LOST")
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        currentEditorInfo = null
        currentState = ImeState.INPUT_LOST
        Log.d(TAG, "Input finished → INPUT_LOST")
    }

    // ─── 注入接口（供广播接收器调用） ────────────────────────

    /**
     * 从广播通道注入文本。
     * BroadcastReceiver.onReceive 运行在主线程，可直接调用 commitText。
     */
    fun injectFromBroadcast(text: String) {
        if (currentState != ImeState.INPUT_READY) {
            Log.w(TAG, "Cannot inject: state=$currentState (not INPUT_READY)")
            return
        }

        val conn = currentInputConnection
        if (conn == null) {
            Log.w(TAG, "Cannot inject: connection is null")
            return
        }

        val success = conn.commitText(text, 1)
        if (success) {
            Log.d(TAG, "Broadcast inject success: ${text.take(30)}...")
        } else {
            Log.w(TAG, "Broadcast inject failed: commitText returned false")
        }
    }

    // ─── 系统底栏（chevron + 输入法切换器）守护 ───────────────

    /**
     * Android 11+ 的 IME 窗口内嵌一个 NavigationBarFrame（系统底栏：
     * 收起箭头 + 手势条 + 输入法切换器），由框架的 NavigationBarController
     * 自动 inflate 到我们的 decorView 里。
     *
     * ColorOS 的私有 ext（InputMethodServiceExtImpl）在
     * contentTopInsets=窗口全高 时，会把 chevron/切换器两个 KeyButtonView
     * 设为 INVISIBLE 并把 frame alpha 压成 0（设备 oplus-framework dex 实证）。
     * 这些都是**我们自己进程内的普通 View**，用公开 API 在每帧绘制前
     * （OnPreDrawListener）扳回即可：OPPO 改一次，下一帧必恢复，
     * 视觉无闪烁，且不触碰系统输入法选择逻辑。
     */
    override fun onWindowShown() {
        super.onWindowShown()
        installNavbarGuard()
    }

    private var navbarFrame: View? = null
    private var navbarKeys: List<View> = emptyList()
    private var guardInstalled = false
    private var guardFixLogged = 0

    /** NavigationBarFrame 相对 IME 窗口的实际区域，每帧刷新，供 onComputeInsets 使用。 */
    private var navbarFrameRect: android.graphics.Rect? = null

    private val navbarGuard = android.view.ViewTreeObserver.OnPreDrawListener {
        navbarFrame?.let { enforceNavbarVisible(it) }
        true
    }

    private fun installNavbarGuard(retry: Int = 0) {
        val decor = window?.window?.decorView as? android.view.ViewGroup
        if (decor == null) {
            if (retry < 5) decorPostInstall(100L, retry + 1)
            return
        }
        decor.post {
            val frame = findNavbarFrame(decor)
            if (frame == null) {
                if (retry < 8) decor.postDelayed({ installNavbarGuard(retry + 1) }, 100L)
                else Log.w(TAG, "NavbarFrame not found, guard not installed")
                return@post
            }
            navbarFrame = frame
            navbarKeys = NAV_KEY_VIEW_IDS.mapNotNull { name ->
                val id = resources.getIdentifier(name, "id", "android")
                if (id != 0) frame.findViewById<View>(id) else null
            }
            Log.i(TAG, "NavbarGuard installed on ${frame.javaClass.simpleName} " +
                    "(keys=${navbarKeys.size})")
            enforceNavbarVisible(frame)
            if (!guardInstalled) {
                frame.viewTreeObserver.addOnPreDrawListener(navbarGuard)
                guardInstalled = true
            }
        }
    }

    private fun decorPostInstall(delay: Long, retry: Int) {
        (window?.window?.decorView)?.postDelayed({ installNavbarGuard(retry) }, delay)
    }

    /** 把 OPPO 藏起来的底栏元素强制恢复；已经正常时什么都不做（不触发重绘）。 */
    private fun enforceNavbarVisible(frame: View) {
        val loc = IntArray(2)
        frame.getLocationInWindow(loc)
        navbarFrameRect = android.graphics.Rect(
            loc[0], loc[1], loc[0] + frame.width, loc[1] + frame.height)
        var changed = false
        if (frame.alpha < 0.99f) {
            frame.alpha = 1.0f
            changed = true
        }
        for (key in navbarKeys) {
            if (key.visibility != View.VISIBLE) {
                key.visibility = View.VISIBLE
                changed = true
            }
        }
        if (changed && guardFixLogged < 5) {
            guardFixLogged++
            Log.i(TAG, "Navbar visibility restored by guard (#$guardFixLogged)")
        }
    }

    private fun findNavbarFrame(root: android.view.ViewGroup): View? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.javaClass.name.contains("NavigationBarFrame")) return child
            if (child is android.view.ViewGroup) {
                findNavbarFrame(child)?.let { return it }
            }
        }
        return null
    }

    /**
     * 获取当前状态（供调试/外部查询）
     */
    fun getState(): ImeState = currentState
}
