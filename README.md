# InvisibleIME


> **免责声明**
>
> 本项目为**实验性作品**，仅供测试与研究，**不保证任何内容**。
>
> - 不保证稳定性、安全性、兼容性或正确性，不适用任何生产环境；
> - 因使用本项目造成的任何损失（含数据丢失、设备异常、被滥用后果），作者**概不负责**；
> - 请勿用于恶意目的、自动化滥用或绕过安全机制。

AI 专属隐形注入器 —— 不是输入法，是挂载在 Android InputMethodService 上的系统级"无 UI 终端注入通道"。

## 核心特性

- **隐形**：1px 透明 View，不弹软键盘，不遮挡屏幕
- **纯粹**：只做文本注入（commitText），永不点击任何按钮
- **自描述**：HELLO 握手协议，AI 连上就知道怎么用
- **双通道**：HTTP（主，带回执）+ 广播（辅，快速盲发）

## 构建

```bash
cd InvisibleIME
./gradlew assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`

## 安装与激活

```bash
# 安装 APK
adb install app/build/outputs/apk/debug/app-debug.apk

# 查看已安装的输入法
adb shell ime list -s

# 应该能看到：com.aixtin.invisime/.InvisImeService

# 切换到 InvisibleIME（需要手动在系统设置中操作，或执行）
adb shell ime set com.aixtin.invisime/.InvisImeService
```

## 使用

### 1. 握手（发现协议）

```bash
adb forward tcp:8767 localabstract:httpd
curl http://127.0.0.1:8767
```

响应：
```json
{
  "name": "InvisibleIME",
  "protocol_version": "1.0",
  "capabilities": ["type", "backspace", "enter", "get_text", "select", "composition"],
  "auth": "none",
  "status": "INPUT_READY"
}
```

### 2. 注入文本

```bash
# 通过统一指令入口
curl -X POST http://127.0.0.1:8767 \
  -H "Content-Type: application/json" \
  -d '{"cmd":"type","text":"你好"}'

# 或通过 /inject 快捷入口
curl -X POST http://127.0.0.1:8767/inject \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello World"}'

# 通过广播（快速盲发，无回执）
adb shell am broadcast -a com.aixtin.invisime.INJECT_TEXT --es text "你好"
```

### 3. 查询状态

```bash
curl http://127.0.0.1:8767/state
```

响应：
```json
{
  "state": "INPUT_READY",
  "input_ready": true,
  "package_name": "com.example.app",
  "field_id": 123,
  "input_type": 1
}
```

### 4. 其他指令

```bash
# 退格
curl -X POST http://127.0.0.1:8767 -d '{"cmd":"backspace"}'

# 换行（不发送）
curl -X POST http://127.0.0.1:8767 -d '{"cmd":"enter"}'

# 读取当前输入框内容
curl -X POST http://127.0.0.1:8767 -d '{"cmd":"get_text","before":100}'

# 设置选区
curl -X POST http://127.0.0.1:8767 -d '{"cmd":"select","start":0,"end":5}'

# 设置组合文本（拼音输入中间状态）
curl -X POST http://127.0.0.1:8767 -d '{"cmd":"composition","text":"ni"}'
```

## 协议规范

### 状态机

```
IDLE → IME_SHOWN（onWindowShown）
     → INPUT_READY（onStartInput + connection valid）
     → [注入循环：type / backspace / enter / ...]
     → INPUT_LOST（焦点丢失 / onFinishInput）
```

### 响应格式

成功：
```json
{"ok": true, "seq": 1, "result": {"committed": 10}}
```

失败：
```json
{"ok": false, "seq": 1, "error": {"code": "INPUT_LOST", "msg": "输入焦点已丢失"}}
```

### 错误码

| 错误码 | 说明 |
|--------|------|
| INPUT_LOST | 输入焦点已丢失 |
| INPUT_FAILED | commitText 返回 false |
| BAD_REQUEST | 请求参数错误 |
| UNKNOWN_CMD | 未知指令 |
| NOT_FOUND | 未知端点 |
| INTERNAL_ERROR | 内部错误 |

## 安全边界

- **只写不点**：注入器只负责 commitText，永不执行点击/发送
- **enter = 换行**：不替应用决定"回车是否发送"
- **内容不改写**：注入器绝不改写文本内容
- **沙箱隔离**：调用方只能通过注入器写文本，无法越权
- **HTTP 无鉴权提醒**：8767 端口默认不鉴权，同一设备上任意应用都可注入文本；请勿将端口暴露到网络，生产使用前先加鉴权 token 与 TLS

## 架构

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  任意客户端   │ ──► │  注入器（独立App）  │ ──► │  任意应用输入框   │
│  curl / 广播  │     │  HTTP + Broadcast │     │  微信 / 便签     │
│  其他AI/脚本  │     │  纯协议服务        │     │  浏览器 / 任何app│
└─────────────┘     └──────────────────┘     └─────────────────┘
```

## 命名规范

- **包名**：`com.aixtin.invisime`（固定标识）
- **服务名**：`InvisImeService`（代码内部）
- **系统 ID**：`com.aixtin.invisime/.InvisImeService`
- **Label**：`隐形注入器 (8767/HTTP)`（人类可读）
- **端口**：`8767`（well-known，固定）

## 已知限制

- Compose TextField 在 Android 12+ 的行为与 View 体系有差异
- 长文本建议分块提交（type_stream，待实现）
- 手机同时只能保留一个 adb 连接

## 版本

- **v1.0.0** — M1 + M2 + M3 完成
  - InputMethodService + 隐形 View
  - HTTP 端点（8767）+ 全部指令
  - 广播入口（辅助）
  - 状态机 + 错误码

## License

MIT
