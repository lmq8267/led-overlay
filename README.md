<p align="center">
  <img src="docs/assets/icon.svg" width="96" height="96" alt="LED Overlay icon">
</p>

<h1 align="center">LED Overlay</h1>

<p align="center">
  远程 LED 悬浮屏消息下发系统：一个原生 C 服务端后台 + 一个 Android 悬浮窗客户端。通过服务端下发消息，安卓客户端悬浮滚动显示消息。
</p>

<p align="center">
  <a href="https://deepwiki.com/lmq8267/led-overlay"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a>
  <img alt="Server" src="https://img.shields.io/badge/server-C_single_binary-2563eb.svg?style=flat-square">
  <img alt="Android" src="https://img.shields.io/badge/android-minSdk_14-16a34a.svg?style=flat-square">
  <img alt="Protocol" src="https://img.shields.io/badge/protocol-WebSocket%20%2B%20HTTP-0f766e.svg?style=flat-square">
</p>

## 项目概览

LED Overlay 用于把服务端管理页面创建的短文本消息实时下发到 Android 设备，并在设备上以全屏悬浮窗形式显示。它适合局域网提示屏、临时通知屏、测试用 LED Overlay、简易排队提示等场景。

当前仓库由两部分组成：

- `server/`：原生 C 写成的服务端，内嵌 Web 管理页面，提供客户端 WebSocket、后台 WebSocket、HTTP ACK 兜底接口和状态持久化。
- `android/app/`：Android 客户端，负责连接服务端、接收消息、显示悬浮窗、记录本机日志和历史消息。

## UI 预览

| 服务端主页 | Android 首页 | 悬浮屏显示 |
| --- | --- | --- |
| <img width="1892" height="834" alt="图片" src="https://github.com/user-attachments/assets/8174ba4d-0a0b-4891-8bbc-1b2105936baa" /> | <img width="429" height="899" alt="图片" src="https://github.com/user-attachments/assets/191c5234-37d6-4913-882d-3bd42a2efe42" /> | <img width="427" height="928" alt="图片" src="https://github.com/user-attachments/assets/179e4839-f93c-474b-8503-48177d8288aa" /> |

## 核心能力

- 服务端单文件二进制部署，默认监听 `0.0.0.0:12366`。
- 管理主页内嵌进服务端二进制，不依赖 Node.js、Nginx 或外部静态文件。
- Android 客户端通过 WebSocket 接收消息，通过 HTTP `/client-ack` 上报完成状态。
- 支持消息状态流转：`pending`、`sent`、`received`、`displaying`、`completed`、`failed`。
- 支持客户端在线/离线状态、任务列表、日志抽屉、历史消息记录。
- 支持管理密码、登录会话、登录失败锁定和隐藏主页路径。
- 支持 Android 悬浮窗权限、通知权限、电池优化提示、开机自启开关。
- 服务端状态写入 `/var/lib/led-overlay/state.db`，日志保留最近 300 条。

## 架构

```text
┌─────────────────────┐
│   Web 管理页面       │
│   / 或 --home-path   │
└──────────┬──────────┘
           │ WebSocket /admin
           │ HTTP /change-password
┌──────────▼──────────┐
│   led_server         │
│   C 单二进制服务端    │
└──────────┬──────────┘
           │ WebSocket /client
           │ HTTP POST /client-ack
┌──────────▼──────────┐
│   Android 客户端      │
│   前台服务 + 悬浮窗    │
└─────────────────────┘
```

## 服务端接口

| 路径 | 类型 | 认证 | 用途 |
| --- | --- | --- | --- |
| `/` 或 `--home-path` | HTTP | 需要登录 | 管理主页入口 |
| `/login` | HTTP POST | 否 | 管理登录 |
| `/admin` | WebSocket | 需要会话 | 管理页面实时状态与操作 |
| `/client` | WebSocket | 否 | Android 客户端连接与接收消息 |
| `/client-ack` | HTTP POST | 否 | 客户端完成 ACK 兜底上报 |
| `/change-password` | HTTP POST | 需要会话 | 修改管理密码 |

客户端消息示例：

```json
{
  "type": "message",
  "taskId": "t42",
  "text": "欢迎使用 LED Overlay",
  "background": "#000000",
  "foreground": "#ff0000",
  "speed": 90,
  "loops": 1
}
```

客户端 ACK 示例：

```json
{
  "type": "ack",
  "clientId": "android-client-id",
  "taskId": "t42",
  "status": "completed",
  "detail": "客户端显示完成"
}
```

## 构建服务端

服务端只依赖系统 C 工具链和 POSIX/pthread 能力。

```bash
cd server
make
```

构建最小体积版本：

```bash
cd server
make min
```

清理产物：

```bash
cd server
make clean
```

运行服务端：

```bash
./led_server -a 0.0.0.0 -p 12366
```

常用参数：

```bash
./led_server --help
./led_server --version
./led_server --reset-password 新密码
./led_server --home-path /hidden-admin
```

首次启动如果没有持久化密码，会创建默认密码 `admin`。正式使用前应立即在页面中修改密码，或使用 `--reset-password` 初始化。

## 构建 Android 客户端

项目使用 Android Gradle Plugin `8.5.2`，模块名为 `:app`。

```bash
gradle :app:assembleDebug
```

安装后在APP首页填写服务端地址，例如：

```text
192.168.8.185:12366
ws://192.168.8.185:12366/client
http://192.168.8.185:12366
```

客户端会自动规范化为 WebSocket 候选地址，并在 HTTP ACK 时使用对应的 `/client-ack`。

## Android 权限

客户端需要以下权限才能稳定工作：

- `INTERNET`：连接服务端。
- `SYSTEM_ALERT_WINDOW`：显示悬浮窗。
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`：保持后台连接。
- `POST_NOTIFICATIONS`：Android 13+ 前台服务通知。
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：提示用户关闭电池优化。
- `RECEIVE_BOOT_COMPLETED`：开机后自动启动。

不同厂商系统对后台运行、开机自启、悬浮窗权限限制不同。客户端首页的权限状态卡片用于快速检查这些前置条件。

## 状态与持久化

服务端状态文件：

```text
/var/lib/led-overlay/state.db
```

该文件保存客户端、任务、日志和管理密码哈希。它是服务端自维护的文本状态文件，不是 SQLite 数据库。服务端日志会写入该状态文件，并限制为最近 300 条，避免无限增长。

Android 客户端本地使用 `SharedPreferences` 保存：

- 服务端地址和最近 3 个历史地址。
- 运行状态和连接状态。
- 已接收/已完成任务记忆。
- 待补发 ACK。

任务去重按“规范化服务端地址 + taskId”记录，避免切换服务端后因为任务编号重复而跳过显示。

## 连接与 ACK 策略

客户端通过 `/client` WebSocket 保持连接并接收消息。收到消息后会进入本地显示队列，显示完成后通过 HTTP `/client-ack` 上报 `completed`。

这样处理的原因是：Android 后台、悬浮窗显示和厂商省电策略可能让 WebSocket 写入不稳定，但 HTTP 短连接上报完成状态更可靠。WebSocket 仍用于服务端实时下发消息和连接在线状态。

## 常见问题

### 首页显示连接中，但服务端显示在线

- 通常是客户端 UI 状态没有刷新，或旧连接的关闭回调覆盖了新连接状态。当前实现中首页每秒读取一次连接状态，服务端同地址重复启动时也会按真实 socket 状态刷新。

### 消息没有显示却立即完成

- 优先检查是否切换过服务端并复用了相同 `taskId`。当前实现已经按服务端地址隔离任务记忆。如果服务端以后下发 `createdAt`，可以进一步升级为“服务端地址 + taskId + 创建时间”的去重键。

### 颜色解析失败

- 消息颜色必须是 Android 可解析的颜色值，例如 `#000000`、`#ff0000`。类似 `#ffoo0o` 这种包含字母 `o` 的值不是合法十六进制颜色。

### 默认密码提示仍出现

- 服务端只有在状态文件中没有密码哈希时才会创建默认密码并提示。请确认运行中的服务端读取的是同一个 `/var/lib/led-overlay/state.db`，以及修改密码后服务端有权限写回该文件。

