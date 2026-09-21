# DShChat 使用说明

手机上的 DSH 聊天客户端，通过 ngrok 隧道连到你电脑上跑的 DSH 服务。

---

## 一、第一次用（按顺序来）

### 1. 装 DSH
电脑上装好 `dsh`（`dsh web` 命令能跑就行）。

### 2. 装 dsh-local-no-auth 插件
DSH 默认绑 `127.0.0.1` 不允许外网访问，ngrok 转发过来会被拒。需要装个插件关掉本地认证：

```bash
dsh plugin install dsh-local-no-auth
```

### 3. 配置 ngrok authtoken
**双击 `配置ngrok.bat`**，然后：
1. 打开 https://dashboard.ngrok.com/get-started/your-authtoken
2. 复制你页面上那串 token
3. 粘贴到命令行里，按回车
4. 看到 `Done!` 就配好了

---

## 二、每次用（三步走）

### 第 1 步：启动 DSH 服务
```bash
dsh web
```
正常会看到 `ready -> http://127.0.0.1:3080`。

### 第 2 步：启动 ngrok 隧道
**双击 `启动隧道.bat`**。

启动后会看到一行：
```
Forwarding  https://xxxx.ngrok-free.dev -> http://localhost:3080
```
复制那个 `https://xxxx.ngrok-free.dev` 地址。

### 第 3 步：连 APP
1. 打开 APP → 右上角设置
2. 「DSH 服务地址」粘贴刚才复制的 ngrok 地址
3. 点「保存并连接」
4. 顶部会加载会话列表，选一个会话就能聊天了

---

## 三、功能

| 功能 | 说明 |
|---|---|
| 聊天 | 发消息、逐字流式回复 |
| 新建会话 | 点顶部「切换」→「＋ 新建会话」 |
| 切换会话 | 点顶部「切换」选别的会话 |
| 思考过程 | AI 想的时候会显示灰色折叠的思考块 |
| 工具调用 | AI 调工具时会显示工具名和参数 |
| 历史加载 | 自动加载最近历史，下拉加载更早 |
| Markdown | 支持代码块、表格、列表等 |
| 换壁纸 | 设置里选一张图当聊天背景 |
| 任务完成通知 | AI 回复完会弹通知+响铃+振动 |
| 通知声音 | 可在设置里切换：跟随系统 / 静音 / 自定义铃声 |
| 只在后台提醒 | 开了之后，你正在看 APP 时不弹通知，切走才提醒 |

---

## 四、常见问题

**Q: 连不上 / 502 / 浏览器警告页**
- 确认 `dsh web` 在跑
- 确认 `启动隧道.bat` 没关
- ngrok 地址别带 `/` 结尾

**Q: 发消息没反应**
- 检查地址对不对
- 检查 ngrok 隧道还活着（那个黑窗口没关）

**Q: 启动隧道报错 `authtoken` 不对**
- 重新双击 `配置ngrok.bat`，粘贴正确的 token

**==Q: 通知没声音**==

- ==手机设置 → 应用 → DShChat → 通知 → DSH 任务完成 → 声音，确保开了==
- ==确认手机没开静音/勿扰模式==

**Q: ngrok 地址每次都变？**

- 免费版每次启动地址都不一样，每次都要重新复制粘贴
- 想要固定地址得买 ngrok 付费版

---

## 五、文件说明

```
DShChat/
├── app/                    Android 源码
├── ngrok.exe               ngrok 程序
├── ngrok.yml                ngrok 配置（不用手动改，用 bat）
├── 配置ngrok.bat            第一次用：双击粘贴 authtoken
├── 启动隧道.bat             每次用：双击启动隧道
├── gradlew.bat             命令行编译用（不用管）
└── README.md               本文件
```
