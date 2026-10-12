package com.aixtin.invisime.state

/**
 * 注入器状态机
 *
 * IDLE        → 服务未启动 / 输入法未激活
 * IME_SHOWN   → onWindowShown 触发，键盘窗口已显示
 * INPUT_READY → onStartInput 触发，InputConnection 已攥住，可注入
 * INPUT_LOST  → onFinishInput / 焦点丢失，注入通道断开
 */
enum class ImeState {
    IDLE,
    IME_SHOWN,
    INPUT_READY,
    INPUT_LOST;

    fun toJson(): String = "\"$name\""
}
