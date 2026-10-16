# AI 悬浮翻译 (Translate Overlay)

一个 Android 悬浮窗翻译工具：把可拖动的悬浮球停在屏幕边缘，随时点开调用**大模型**翻译，
并用一键复制把译文贴到任何输入框。**面板打开时不影响操作其他应用。**

> 与机翻不同，这里走的是 OpenAI 兼容接口（默认 DeepSeek），译文质量和上下文理解明显更好。

---

## 功能

| 功能 | 说明 |
|---|---|
| 悬浮球 | 可自由拖动；拖到屏幕边缘会自动吸附，只露出一小条，不挡视线 |
| 位置记忆 | 悬浮球位置持久化保存，重启后回到原处 |
| 翻译面板 | 输入原文 → 调用大模型 → 展示译文 → 一键复制 |
| 触摸穿透 | **面板打开时，面板外的触摸会正常传递给下层应用**，可以一边翻译一边操作微信 / Discord |
| 语言互换 | 点「互换」即可反转翻译方向（如 中文→English 变成 English→中文） |
| 剪贴板读取 | 可选：打开面板时自动把剪贴板内容填进输入框 |
| 结果记忆 | 记住上次译文，误关面板后可恢复 |
| 键盘避让 | 输入框获得焦点时面板自动上移，不会被输入法遮住 |

---

## 快速开始

### 1. 安装

下载 `app-debug.apk` 直接安装（或自行编译，见下文）。

### 2. 配置

打开应用，填写：

| 字段 | 示例 |
|---|---|
| API Key | `sk-xxxxxxxx`（你的服务商密钥） |
| Base URL | `https://api.deepseek.com` |
| 模型名称 | `deepseek-flash` |
| 源语言 | `中文` |
| 目标语言 | `English` / `日本語` / ... |

点「测试连接」确认可用后，再点「保存配置」。

### 3. 启用悬浮框

打开「悬浮翻译框」开关 → 按系统提示授予**「显示在其他应用上层」**权限。

### 4. 使用

回到桌面或任意应用，点屏幕边缘的紫色「译」球 → 输入内容 → 点「翻译」→ 点「复制」→ 粘贴。

---

## 兼容性

- **最低版本**：Android 8.0 (API 26)
- **目标版本**：Android 12 (API 31)
- 已在 **vivo V2282A / Android 13** 上实测

---

## 自行编译

### 环境

- JDK 17
- Android SDK（platform-31 + build-tools）
- Gradle 7.5.1

### 步骤

```bash
# 1. 在项目根目录创建 local.properties，写上你的 SDK 路径
#    注意用正斜杠，别用反斜杠（Java Properties 会把 \ 当转义符吃掉）
echo 'sdk.dir=/path/to/android/sdk' > local.properties

# 2. 编译 debug 包
./gradlew :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

---

## 实现要点

几个踩过的坑，供二次开发参考：

### 面板必须带 `FLAG_NOT_TOUCH_MODAL`

```kotlin
WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
```

没有 `FLAG_NOT_TOUCH_MODAL` 时，可聚焦窗口会**独占整个屏幕的触摸事件** ——
表现就是"开了翻译面板之后，别的什么都点不动"。加上它之后，
窗口范围之外的触摸会继续下发给后面的应用窗口。

### 悬浮球用 `FLAG_NOT_FOCUSABLE`

球不需要键盘焦点。`FLAG_NOT_FOCUSABLE` 会隐含 `FLAG_NOT_TOUCH_MODAL`，
所以球本身天然支持触摸穿透。

### 贴边吸附要保留足够的可见宽度

允许球"贴边半隐藏"能避免挡视线，但可见宽度太小时用户会点不中。
本项目的做法是：吸附时保留球直径的 40%（约 22dp），
并在恢复保存位置时校验可见宽度，不足则重新吸附。

### 网络层用原生 `HttpURLConnection`

不引入 OkHttp 等依赖，APK 更小；而且 4xx/5xx 时能读 `errorStream`
拿到服务端返回的真实错误信息，而不是被上层库归一化成无意义的 `"closed"`。

### 前台服务保活

Android 8+ 对后台进程管控严格，普通 Service 退到后台后会被回收、悬浮窗消失。
所以用前台服务 + 常驻通知让悬浮球稳定存活。

---

## 隐私

- API Key 只保存在本机 `SharedPreferences`，**不会上传到任何第三方**
- 除你配置的翻译服务商外，应用不向任何其他服务器发送数据
- 源码中不含任何真实密钥（默认值是占位符 `sk-你的DeepSeek密钥`）

---

## 许可

个人自用项目，可自由修改使用。
