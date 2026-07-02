# Aura Terminal — Web 预览页

这是 Apex Auto Agent 终端吉祥物 **Aura(深海极光水母)** 的 Web 预览展示页。

> ⚠️ **这是设计展示页,不是 Android 软件本体。** Android 软件用 Kotlin/Compose 开发,
> 水母的真实状态对接代码在 `ai-terminal/` 和 `app/` 模块。这个 Web 页只用来预览水母形态设计
> 和终端视觉效果,方便分享给他人查看。

## 预览内容

- **22 种水母形态**(14 基础 + 8 功能扩展)的 PNG 图像
- **深海极光主题终端窗口**(深空黑 + 电光青 + 珊瑚粉)
- **可交互命令行**(help / status / burst / agent / form / remember / analyze 等)
- **形态切换按钮**(底部 22 个,点击即切换)
- **浮动呼吸动画**(水母轻微上下浮动 + 缩放)

## 本地运行

需要 Node.js 18+ 或 [Bun](https://bun.sh)。

```bash
cd web
bun install        # 或 npm install
bun run dev        # 或 npm run dev
```

浏览器打开 http://localhost:3000 即可预览。

## 可用命令(终端输入框)

| 命令 | 作用 |
|------|------|
| `help` | 显示所有命令 |
| `status` | 查看当前状态 |
| `burst` | 触发狂暴模式(水母→血红眼+电环绕) |
| `agent <任务>` | 触发 Agent 协作流程(思考→执行→成功) |
| `remember` / `analyze` / `learn` | 记忆/分析/学习形态 |
| `net` / `root` / `plan` / `compile` / `connect` | 联网/提权/规划/编译/连接形态 |
| `form <名称>` | 直接切换到指定形态(如 `form berserk`) |
| `clear` | 清屏 |
| `about` | 关于信息 |

也可以直接点击底部的 22 个形态按钮切换。

## 22 种形态

### 基础 14 形态
IDLE 漂浮 / THINKING 思考 / TYPING 打字 / EXECUTING 执行 / BERSERK 狂暴 / SUCCESS 成功 / ERROR 错误 / SLEEPING 休眠 / EVOLVING 演化 / COLLABORATING 协作 / LOADING 加载 / CELEBRATING 庆祝 / CURIOUS 好奇 / SHIELDING 防御

### 功能扩展 8 形态(由软件功能状态触发)
REMEMBERING 记忆 / ANALYZING 分析 / LEARNING 学习 / NETWORKING 联网 / ROOT 提权 / PLANNING 规划 / COMPILING 编译 / CONNECTING 连接

## 文件结构

```
web/
├── public/aura-*.png      # 22 张水母形态 PNG
├── src/
│   ├── app/               # Next.js App Router 页面
│   ├── components/        # 终端 + 水母组件
│   └── lib/               # 形态定义 + 主题
├── package.json
└── README.md
```

## 技术栈

- Next.js 16 (App Router)
- TypeScript
- Tailwind CSS 4
- 纯展示,无后端,无数据库

## 与 Android 端的关系

| 项目 | Web 预览页(本目录) | Android 软件(仓库主体) |
|------|---------------------|------------------------|
| 用途 | 设计展示 | 真实软件 |
| 语言 | TypeScript/React | Kotlin/Compose |
| 数据 | 模拟假数据 | 真实状态对接 |
| 水母渲染 | `<img>` PNG | `ImageView`/Compose `Image` PNG |
| 形态触发 | 手动点击/命令 | 软件功能状态自动触发 |

Android 端的水母 PNG 资源在 `app/src/main/res/drawable/aura_*.png`(与这里的 `public/aura-*.png` 同源)。
真实状态对接代码在 `ai-terminal/.../mascot/MascotStateBinder.kt`。
