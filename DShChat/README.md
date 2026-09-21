# Elysia_dsh — 安卓端 DSH 聊天客户端（Kotlin + Jetpack Compose）

> ⚠️ **这份是早期原型的开发笔记，内容已经过时**（那时只有轮询、会话还是写死的）。
> 现行的使用说明请看仓库根目录的 [`README.md`](../README.md)。

通过 DSH **JSON-RPC 本地接口**（`dsh web`，默认 `http://127.0.0.1:3080`）发消息并轮询回复。
协议依据你自己的 `dsh_api.py` 实现，不是 OpenAI 兼容接口。

## 协议说明（已按真实实现）
```
POST {base}/api/<method>
请求：{"type":"client-request","rpcId":"<uuid>","method":"<method>","payload":{"args":{...}}}
响应：{"type":"server-response","rpcId":"<uuid>","result":{"ok":true,"value":...}}
```

用到的端点：
| 端点 | 作用 |
|---|---|
| `session/list` | 列会话（args 里字段名是 `_request`） |
| `session/prompt` | 发消息（只入队，返回 `{accepted:true}`） |
| `session/page` | 读会话记录，从 `assistant/message` 事件里抽正文 |

发消息后**正文靠轮询**：每 1.5s 拉一次 `session/page`，直到 `running=false`。

## 功能
- **会话已固定写死**：`session-1fdf2610-15d3-4639-8481-1f317994e13d`，UI 不可切换、不从本地配置读
- 发消息后轮询显示 DSH 回复正文（多段自动拼接）
- 设置里只改 base 地址（模拟器/真机/穿透），并自动保存

## 一、打开项目
1. Android Studio → File → Open → 选桌面 `Elysia_dsh` 文件夹
2. 首次同步需联网下依赖，等完成后点 ▶ 运行

## 二、DSH 侧准备
本机 loopback 已装 `@falling-ts/dsh-local-no-auth`，**免鉴权**，直接：
```bash
dsh web
```
- 模拟器访问：base 填 `http://10.0.2.2:3080`（已预填），DSH 视为本机访问
- 真机同 WiFi：base 填 `http://电脑局域网IP:3080`，防火墙放行 3080
- 真机外网：`ngrok tcp 3080`，base 填 `http://0.tcp.ap.ngrok.io:端口`

## 三、先用浏览器工具验证
双击 `dsh-test.html`（JSON-RPC 版）：
1. 点「加载会话」→ 能看到你的会话列表就说明通
2. 选中会话 → 发送 → 应看到轮询出来的回复正文

> 若浏览器报 CORS / Failed to fetch：用桌面「Chrome(调试模式)」快捷方式打开本文件；
> 或直接命令行跑你自己的 `python dsh_api.py list` 验证。

## 常见问题
- **拉不到会话列表**：DSH 没启动 / base 填错 / 真机防火墙没放行 3080
- **发了消息没回复**：正常，DSH 是轮询制，等几秒；agent 正在跑时会显示 ●
- **连不上 127.0.0.1**：安卓 APP 里 `127.0.0.1` 指手机自己，**必须用 `10.0.2.2`（模拟器）或电脑局域网 IP（真机）**
- **请求失败但能开网页**：多半是系统代理拦截，APP 代码里已用 `Proxy.NO_PROXY` 绕开

## 项目结构
```
Elysia_dsh/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── app/
│   ├── build.gradle.kts                 # Compose + OkHttp
│   └── src/main/
│       ├── AndroidManifest.xml          # INTERNET + 明文 http
│       ├── java/com/example/dshchat/
│       │   ├── MainActivity.kt          # UI：会话下拉 + 聊天界面
│       │   ├── ChatViewModel.kt        # 状态、发送、轮询、设置持久化
│       │   └── DshApi.kt               # JSON-RPC 客户端（对应 dsh_api.py）
│       └── res/values/                  # 主题、字符串
├── dsh-test.html                        # 浏览器版调试工具
└── README.md
```
