package com.ai.assistance.aiterminal.terminal.mascot

/**
 * Apex 终端吉祥物 — "Aura"（极光水母）— 精致优化版 v2
 *
 * 在原版基础上重新打磨的 ASCII 艺术：
 *  - 分层伞盖 `╭─...─╮`，更立体的穹顶
 *  - 生物荧光光点 `·✦*`，呼吸式环境光
 *  - 飘逸触手使用 `║╿╿╽╲╱`，长短交错更灵动
 *  - 表情更细腻 `◉◎●`，情绪区分度更高
 *  - 每种形态附带 accent 强调色 token，用于光晕渲染
 *
 * 14 种形态，每种含多帧高精度 ASCII 动画。
 * 艺术风格：深海生物荧光，半透明伞盖 + 飘逸触手 + 光环系统。
 *
 * # 形态总览
 *
 * | 形态 | 场景 | 帧数 | 强调色 | 特色 |
 * |------|------|------|--------|------|
 * | IDLE | 空闲漂浮 | 4 | cyan | 触手波浪 + 光点闪烁 |
 * | THINKING | 思考 | 4 | sky | 伞盖脉冲 + 光圈扩散 |
 * | TYPING | 打字 | 4 | cyan | 触手交替敲击 |
 * | EXECUTING | 执行 | 3 | pink | 喷射推进 + 气泡尾迹 |
 * | BERSERK | 狂暴 | 4 | rose | 电光爆发 + 光环旋转 |
 * | SUCCESS | 成功 | 3 | mint | 上浮旋转 + 星光散落 |
 * | ERROR | 错误 | 3 | rose | 颤抖 + 触手蜷缩 |
 * | SLEEPING | 休眠 | 2 | violet | 呼吸光 + Z 字符 |
 * | EVOLVING | GA 演化 | 4 | mint | DNA 螺旋 + 光点变异 |
 * | COLLABORATING | 多 Agent 协作 | 4 | sky | 分身 + 连接线 |
 * | LOADING | 加载 | 6 | cyan | 旋转扫描 |
 * | CELEBRATING | 庆祝 | 4 | amber | 礼花 + 跳跃 |
 * | CURIOUS | 好奇 | 3 | amber | 倾斜 + 大眼睛 |
 * | SHIELDING | 防御/安全 | 3 | sky | 光盾展开 |
 */
object AuraMascot {

    /** 强调色 token，与前端 / 主题光晕对应。 */
    enum class AuraAccent { CYAN, PINK, AMBER, MINT, ROSE, VIOLET, SKY }

    enum class AuraForm(val displayName: String, val description: String, val accent: AuraAccent) {
        IDLE("漂浮", "轻柔漂浮 · 触手波浪摆动", AuraAccent.CYAN),
        THINKING("思考", "伞盖脉冲发光 · 光圈扩散", AuraAccent.SKY),
        TYPING("打字", "触手交替快速敲击", AuraAccent.CYAN),
        EXECUTING("执行", "彩色荧光 · 透明外层", AuraAccent.PINK),
        BERSERK("狂暴", "血红双眼 · 电环绕", AuraAccent.ROSE),
        SUCCESS("成功", "开心上浮 · 星光散落", AuraAccent.MINT),
        ERROR("错误", "颤抖下沉 · 触手蜷缩", AuraAccent.ROSE),
        SLEEPING("休眠", "闭合伞盖 · 呼吸微光", AuraAccent.VIOLET),
        EVOLVING("演化", "GA 演化中 · DNA 螺旋光点", AuraAccent.MINT),
        COLLABORATING("协作", "多 Agent 协作 · 分身连接", AuraAccent.SKY),
        LOADING("加载", "旋转扫描 · 进度指示", AuraAccent.CYAN),
        CELEBRATING("庆祝", "礼花跳跃 · 狂欢模式", AuraAccent.AMBER),
        CURIOUS("好奇", "倾斜探头 · 黄身大眼", AuraAccent.AMBER),
        SHIELDING("防御", "光盾展开 · 安全模式", AuraAccent.SKY),
        // 功能扩展 8 形态 — 由软件功能状态自动触发
        REMEMBERING("记忆", "记忆系统 · 神经节点", AuraAccent.MINT),
        ANALYZING("分析", "代码分析 · 放大镜", AuraAccent.CYAN),
        LEARNING("进化", "进化系统 · 自我迭代", AuraAccent.VIOLET),
        NETWORKING("联网", "网络请求 · 数据流动", AuraAccent.SKY),
        ROOT("提权", "Shizuku/Root · 皇冠", AuraAccent.AMBER),
        PLANNING("规划", "任务分解 · 流程图", AuraAccent.CYAN),
        COMPILING("编译", "代码生成 · 齿轮", AuraAccent.PINK),
        CONNECTING("连接", "MCP/插件 · 模块接入", AuraAccent.MINT),
        TOOLING("工具", "调用工具 · 工具箱", AuraAccent.AMBER),
        SKILLING("技能", "Skills 技能 · 模块加载", AuraAccent.CYAN),
        MCPING("MCP", "MCP 协议 · 服务接入", AuraAccent.MINT)
    }

    fun getFrames(form: AuraForm): List<List<String>> = when (form) {
        AuraForm.IDLE -> idleFrames
        AuraForm.THINKING -> thinkingFrames
        AuraForm.TYPING -> typingFrames
        AuraForm.EXECUTING -> executingFrames
        AuraForm.BERSERK -> berserkFrames
        AuraForm.SUCCESS -> successFrames
        AuraForm.ERROR -> errorFrames
        AuraForm.SLEEPING -> sleepingFrames
        AuraForm.EVOLVING -> evolvingFrames
        AuraForm.COLLABORATING -> collaboratingFrames
        AuraForm.LOADING -> loadingFrames
        AuraForm.CELEBRATING -> celebratingFrames
        AuraForm.CURIOUS -> curiousFrames
        AuraForm.SHIELDING -> shieldingFrames
    }

    // ===== IDLE 漂浮（4 帧波浪 + 光点）— 精致版 =====
    private val idleFrames = listOf(
        listOf(
            "            ·  ✧  ·            ",
            "          · ╭─────────╮ ·        ",
            "         · ╱  ◉     ◉  ╲ ·       ",
            "        · │  (  ▽   ▽  )  │ ·     ",
            "         · ╲   ═══╤═══   ╱ ·      ",
            "          · ╰╥╥╥╥╥╥╥╥╥╥╥╯ ·       ",
            "            ╱║║║║║║║║║║╲          ",
            "           ╱ ║╿║╿║╿║╿║ ╲          ",
            "             ║╽║╽║╽║╽║            ",
            "              ╲╱╲╱╲╱╲╱             "
        ),
        listOf(
            "             ·  ✧  ·             ",
            "           · ╭─────────╮ ·        ",
            "          · ╱  ◉     ◉  ╲ ·       ",
            "         · │  (  ▽   ▽  )  │ ·     ",
            "          · ╲   ═══╤═══   ╱ ·      ",
            "           · ╰╥╥╥╥╥╥╥╥╥╥╥╯ ·       ",
            "             ╲║║║║║║║║║║╱          ",
            "              ╲║╿║╿║╿║╿║╱          ",
            "               ║╽║╽║╽║╽║           ",
            "                ╲╱╲╱╲╱╲╱            "
        ),
        listOf(
            "            ·  ✦  ·            ",
            "          · ╭─────────╮ ·        ",
            "         · ╱  ◉  ✦  ◉  ╲ ·       ",
            "        · │  (  ▽   ▽  )  │ ·     ",
            "         · ╲   ═══╤═══   ╱ ·      ",
            "          · ╰╥╥╥╥╥╥╥╥╥╥╥╯ ·       ",
            "            ╱║║║║║║║║║║╲          ",
            "           ╱ ║╿║╿║╿║╿║ ╲          ",
            "             ║╽║╽║╽║╽║            ",
            "              ╲╱╲╱╲╱╲╱             "
        ),
        listOf(
            "           ·  ✧  ·              ",
            "         · ╭─────────╮ ·         ",
            "        · ╱  ◉     ◉  ╲ ·        ",
            "       · │  (  ▽   ▽  )  │ ·      ",
            "        · ╲   ═══╤═══   ╱ ·       ",
            "         · ╰╥╥╥╥╥╥╥╥╥╥╥╯ ·        ",
            "           ╲║║║║║║║║║║╱           ",
            "            ╲║╿║╿║╿║╿║╱           ",
            "              ║╽║╽║╽║╽║            ",
            "               ╲╱╲╱╲╱╲╱             "
        )
    )

    // ===== THINKING 思考（4 帧脉冲扩散）— 精致版 =====
    private val thinkingFrames = listOf(
        listOf(
            "       ╭─────────╮          ",
            "      ╱  ◉     ◉  ╲         ",
            "     │  (  ・   ・  )  │      ",
            "      ╲    ╰─╯    ╱        ",
            "       ╰╥╥╥╥╥╥╥╥╥╥╥╯        ",
            "        ║║║║║║║║║║║         ",
            "         ╰╯╰╯╰╯╰╯╰╯          "
        ),
        listOf(
            "     · ╭─────────╮ ·        ",
            "    · ╱  ◉     ◉  ╲ ·       ",
            "   · │  (  ・   ・  )  │ ·    ",
            "    · ╲    ╰─╯    ╱ ·      ",
            "     · ╰╥╥╥╥╥╥╥╥╥╥╥╯ ·       ",
            "      ║║║║║║║║║║║         ",
            "       ╰╯╰╯╰╯╰╯╰╯          "
        ),
        listOf(
            "   · · ╭─────────╮ · ·      ",
            "  · · ╱  ◉     ◉  ╲ · ·     ",
            " · · │  (  ・   ・  )  │ · ·  ",
            "  · · ╲    ╰─╯    ╱ · ·    ",
            "   · · ╰╥╥╥╥╥╥╥╥╥╥╥╯ · ·     ",
            "    ║║║║║║║║║║║         ",
            "     ╰╯╰╯╰╯╰╯╰╯          "
        ),
        listOf(
            " · · · ╭─────────╮ · · ·    ",
            "· · · ╱  ◉     ◉  ╲ · · ·   ",
            " · · │  (  ・   ・  )  │ · ·  ",
            " · · ╲    ╰─╯    ╱ · ·    ",
            "  · · ╰╥╥╥╥╥╥╥╥╥╥╥╯ · ·     ",
            "   ║║║║║║║║║║║         ",
            "    ╰╯╰╯╰╯╰╯╰╯          "
        )
    )

    // ===== TYPING 打字（4 帧触手交替）— 精致版 =====
    private val typingFrames = listOf(
        listOf(
            "         ╭─────────╮         ",
            "        ╱  ◉     ◉  ╲        ",
            "       │  (  ▽   ▽  )  │     ",
            "        ╲   ═══╤═══   ╱      ",
            "         ╰╥╥╥╥╥╥╥╥╥╥╥╯        ",
            "       ╱╱║║║║║║║║║║║╲╲       ",
            "      ╱╱  ║╿║╿║╿║╿║  ╲╲      ",
            "          ║╽║╽║╽║╽║           "
        ),
        listOf(
            "         ╭─────────╮         ",
            "        ╱  ◉     ◉  ╲        ",
            "       │  (  ▽   ▽  )  │     ",
            "        ╲   ═══╤═══   ╱      ",
            "         ╰╥╥╥╥╥╥╥╥╥╥╥╯        ",
            "      ╲╲║║║║║║║║║║║╱╱       ",
            "     ╲╲  ║╿║╿║╿║╿║  ╱╱      ",
            "         ║╽║╽║╽║╽║            "
        ),
        listOf(
            "         ╭─────────╮         ",
            "        ╱  ◉     ◉  ╲        ",
            "       │  (  ▽   ▽  )  │     ",
            "        ╲   ═══╤═══   ╱      ",
            "         ╰╥╥╥╥╥╥╥╥╥╥╥╯        ",
            "       ╱╱║║║║║║║║║║║╲╲       ",
            "      ╱╱  ║╿║╿║╿║╿║  ╲╲      ",
            "          ║╽║╽║╽║╽║           "
        ),
        listOf(
            "         ╭─────────╮         ",
            "        ╱  ◉     ◉  ╲        ",
            "       │  (  ▽   ▽  )  │     ",
            "        ╲   ═══╤═══   ╱      ",
            "         ╰╥╥╥╥╥╥╥╥╥╥╥╯        ",
            "      ╲╲║║║║║║║║║║║╱╱       ",
            "     ╲╲  ║╿║╿║╿║╿║  ╱╱      ",
            "         ║╽║╽║╽║╽║            "
        )
    )

    // ===== EXECUTING 执行（3 帧喷射 + 气泡）— 精致版 =====
    private val executingFrames = listOf(
        listOf(
            "           ╭─────────╮           ",
            "          ╱  ▶     ▶  ╲          ",
            "         │  (  ══════  )  │       ",
            "          ╲   ═══╤═══   ╱        ",
            "           ╰╥╥╥╥╥╥╥╥╥╥╥╯           ",
            "      ○<<<<║║║║║║║║║║║<<<<○      ",
            "           ║╿║╿║╿║╿║╿║           ",
            "            ║╽║╽║╽║╽║            "
        ),
        listOf(
            "            ╭─────────╮           ",
            "           ╱  ▶     ▶  ╲          ",
            "          │  (  ══════  )  │       ",
            "           ╲   ═══╤═══   ╱        ",
            "            ╰╥╥╥╥╥╥╥╥╥╥╥╯           ",
            "    ○<<<<<<║║║║║║║║║║║<<<<<<○    ",
            "            ║╿║╿║╿║╿║╿║           ",
            "             ║╽║╽║╽║╽║            "
        ),
        listOf(
            "             ╭─────────╮           ",
            "            ╱  ▶     ▶  ╲          ",
            "           │  (  ══════  )  │       ",
            "            ╲   ═══╤═══   ╱        ",
            "             ╰╥╥╥╥╥╥╥╥╥╥╥╯           ",
            "  ○<<<<<<<<║║║║║║║║║║║<<<<<<<<○  ",
            "             ║╿║╿║╿║╿║╿║           ",
            "              ║╽║╽║╽║╽║            "
        )
    )

    // ===== BERSERK 狂暴（4 帧电光爆发 + 光环）— 精致版 =====
    private val berserkFrames = listOf(
        listOf(
            "    ✧  ╭◎◎═════◎◎╮  ✧    ",
            "   ✧  ╱  ◉     ◉  ╲  ✧   ",
            "  ✧  │  (  ✦   ✦  )  │  ✧  ",
            "   ✧  ╲   ═╪═══╪═   ╱  ✧   ",
            "    ✧  ╰╥╥╥╥╥╥╥╥╥╥╥╥╯  ✧    ",
            "       ║║║║║║║║║║║║       ",
            "        ║╿║╿║╿║╿║╿        "
        ),
        listOf(
            "  ✧✧  ╭◎◎═════◎◎╮  ✧✧  ",
            " ✧✧  ╱  ◉     ◉  ╲  ✧✧ ",
            "✧✧  │  (  ✦   ✦  )  │  ✧✧",
            " ✧✧  ╲   ═╪═══╪═   ╱  ✧✧ ",
            "  ✧✧  ╰╥╥╥╥╥╥╥╥╥╥╥╥╯  ✧✧  ",
            "     ║║║║║║║║║║║║     ",
            "      ║╿║╿║╿║╿║╿      "
        ),
        listOf(
            "✧✧✧  ╭◎◎═════◎◎╮  ✧✧✧",
            " ✧  ╱  ◉     ◉  ╲  ✧ ",
            "    │  (  ✦   ✦  )  │    ",
            " ✧  ╲   ═╪═══╪═   ╱  ✧ ",
            "✧✧✧  ╰╥╥╥╥╥╥╥╥╥╥╥╥╯  ✧✧✧",
            "     ║║║║║║║║║║║║     ",
            "      ║╿║╿║╿║╿║╿      "
        ),
        listOf(
            "  ✧✧  ╭◎◎═════◎◎╮  ✧✧  ",
            "   ╱  ◉     ◉  ╲   ",
            "  │  (  ✦   ✦  )  │  ",
            "   ╲   ═╪═══╪═   ╱   ",
            "  ✧✧  ╰╥╥╥╥╥╥╥╥╥╥╥╥╯  ✧✧  ",
            "     ║║║║║║║║║║║║     ",
            "      ║╿║╿║╿║╿║╿      "
        )
    )

    // ===== SUCCESS 成功（3 帧上浮 + 星光）— 精致版 =====
    private val successFrames = listOf(
        listOf(
            "          ╭─────────╮          ",
            "         ╱  ◠     ◠  ╲         ",
            "        │  (  ★   ★  )  │      ",
            "         ╲   ～～～   ╱         ",
            "          ╰╥╥╥╥╥╥╥╥╥╥╥╯          ",
            "        ╱║║║║║║║║║║║║╲        ",
            "       ╱ ║╿║╿║╿║╿║╿║ ╲       ",
            "    ✦     ║╽║╽║╽║╽║     ✦    "
        ),
        listOf(
            "           ╭─────────╮          ",
            "          ╱  ◠     ◠  ╲         ",
            "         │  (  ★   ★  )  │      ",
            "          ╲   ～～～   ╱         ",
            "           ╰╥╥╥╥╥╥╥╥╥╥╥╯          ",
            "         ╱║║║║║║║║║║║║╲        ",
            "        ╱ ║╿║╿║╿║╿║╿║ ╲       ",
            "     ✦      ║╽║╽║╽║╽║      ✦   "
        ),
        listOf(
            "            ╭─────────╮          ",
            "           ╱  ◠     ◠  ╲         ",
            "          │  (  ★   ★  )  │      ",
            "           ╲   ～～～   ╱         ",
            "            ╰╥╥╥╥╥╥╥╥╥╥╥╯          ",
            "          ╱║║║║║║║║║║║║╲        ",
            "         ╱ ║╿║╿║╿║╿║╿║ ╲       ",
            "      ✦     ║╽║╽║╽║╽║     ✦    "
        )
    )

    // ===== ERROR 错误（3 帧颤抖）— 精致版 =====
    private val errorFrames = listOf(
        listOf(
            "       ╭─────────╮       ",
            "      ╱  ○     ○  ╲      ",
            "     │  (  ✕   ✕  )  │   ",
            "      ╲   ───    ╱      ",
            "       ╰╥╥╥╥╥╥╥╥╥╥╥╯       ",
            "        ║║║║║║║║║║║        ",
            "         ╰╯╰╯╰╯╰╯╰╯         "
        ),
        listOf(
            "      ╭─────────╮       ",
            "     ╱  ○     ○  ╲      ",
            "    │  (  ✕   ✕  )  │   ",
            "     ╲   ───    ╱      ",
            "      ╰╥╥╥╥╥╥╥╥╥╥╥╯       ",
            "       ║║║║║║║║║║║        ",
            "        ╰╯╰╯╰╯╰╯╰╯         "
        ),
        listOf(
            "     ╭─────────╮      ",
            "    ╱  ○     ○  ╲     ",
            "   │  (  ✕   ✕  )  │  ",
            "    ╲   ───    ╱     ",
            "     ╰╥╥╥╥╥╥╥╥╥╥╥╯      ",
            "      ║║║║║║║║║║║       ",
            "       ╰╯╰╯╰╯╰╯╰╯        "
        )
    )

    // ===== SLEEPING 休眠（2 帧呼吸）— 精致版 =====
    private val sleepingFrames = listOf(
        listOf(
            "       ╭·─ ─ ─ ─ ─·╮       ",
            "      ╱   -     -   ╲      ",
            "     │   ( z   z )   │     ",
            "      ╲             ╱      ",
            "       ╰─╥─╥─╥─╥─╥─╥╯       ",
            "         ║ ║ ║ ║ ║          "
        ),
        listOf(
            "       ╭·· ─ ─ ─ ··╮       ",
            "      ╱   -     -   ╲      ",
            "     │   ( z   z )   │     ",
            "      ╲             ╱      ",
            "       ╰─╥─╥─╥─╥─╥─╥╯       ",
            "         ║ ║ ║ ║ ║          "
        )
    )

    // ===== EVOLVING 演化（4 帧 DNA 螺旋）— 精致版 =====
    private val evolvingFrames = listOf(
        listOf(
            "       ╭─────────╮       ",
            "      ╱  ◈     ◈  ╲      ",
            "     │  (  ╳   ╳  )  │   ",
            "      ╲   ╱╲ ╱╲ ╱╲  ╱      ",
            "       ╰╥╥╥╥╥╥╥╥╥╥╥╯       ",
            "     ╔═║║║║║║║║║║║═╗     ",
            "     ║  ║╿║╿║╿║╿║  ║     ",
            "     ╚═════════════╝     "
        ),
        listOf(
            "       ╭─────────╮       ",
            "      ╱  ◈    ✦    ◈  ╲      ",
            "     │  (  ╳   ╳  )  │   ",
            "      ╲  ╲╱ ╲╱ ╲╱ ╲╱ ╱      ",
            "       ╰╥╥╥╥╥╥╥╥╥╥╥╯       ",
            "     ╔═║║║║║║║║║║║═╗     ",
            "     ║  ║╿║╿║╿║╿║  ║     ",
            "     ╚═════════════╝     "
        ),
        listOf(
            "       ╭─────────╮       ",
            "      ╱  ◈     ◈  ╲      ",
            "     │  (  ╳   ╳  )  │   ",
            "      ╲   ╱╲ ╱╲ ╱╲  ╱      ",
            "       ╰╥╥╥╥╥╥╥╥╥╥╥╯       ",
            "     ╔═║║║║║║║║║║║═╗     ",
            "     ║  ║╿║╿║╿║╿║  ║     ",
            "     ╚═════════════╝     "
        ),
        listOf(
            "       ╭─────────╮       ",
            "      ╱  ◈    ✧    ◈  ╲      ",
            "     │  (  ╳   ╳  )  │   ",
            "      ╲  ╲╱ ╲╱ ╲╱ ╲╱ ╱      ",
            "       ╰╥╥╥╥╥╥╥╥╥╥╥╯       ",
            "     ╔═║║║║║║║║║║║═╗     ",
            "     ║  ║╿║╿║╿║╿║  ║     ",
            "     ╚═════════════╝     "
        )
    )

    // ===== COLLABORATING 协作（4 帧分身连接）— 精致版 =====
    private val collaboratingFrames = listOf(
        listOf(
            "   ╭───╮       ╭───╮       ╭───╮   ",
            "   │ ◎ │ ═════ │ ◎ │ ═════ │ ◎ │   ",
            "   │(▼)│       │(▼)│       │(▼)│   ",
            "   ╰╥╥╥╯       ╰╥╥╥╯       ╰╥╥╥╯   ",
            "    ║║║         ║║║         ║║║    ",
            "    ║╿║         ║╿║         ║╿║    "
        ),
        listOf(
            "   ╭───╮  ═══  ╭───╮  ═══  ╭───╮   ",
            "   │ ◎ │═══════│ ◈ │═══════│ ◎ │   ",
            "   │(▼)│  ═══  │(▼)│  ═══  │(▼)│   ",
            "   ╰╥╥╥╯       ╰╥╥╥╯       ╰╥╥╥╯   ",
            "    ║║║         ║║║         ║║║    ",
            "    ║╿║         ║╿║         ║╿║    "
        ),
        listOf(
            "   ╭───╮       ╭───╮       ╭───╮   ",
            "   │ ◈ │ ═════ │ ◎ │ ═════ │ ◈ │   ",
            "   │(▼)│       │(▼)│       │(▼)│   ",
            "   ╰╥╥╥╯       ╰╥╥╥╯       ╰╥╥╥╯   ",
            "    ║║║         ║║║         ║║║    ",
            "    ║╿║         ║╿║         ║╿║    "
        ),
        listOf(
            "   ╭───╮  ═══  ╭───╮  ═══  ╭───╮   ",
            "   │ ◎ │═══════│ ◎ │═══════│ ◎ │   ",
            "   │(▼)│  ═══  │(▼)│  ═══  │(▼)│   ",
            "   ╰╥╥╥╯       ╰╥╥╥╯       ╰╥╥╥╯   ",
            "    ║║║         ║║║         ║║║    ",
            "    ║╿║         ║╿║         ║╿║    "
        )
    )

    // ===== LOADING 加载（6 帧旋转扫描）— 精致版 =====
    private val loadingFrames = listOf(
        listOf(
            "       ╭─────────╮       ",
            "      ╱  ◉─────    ╲      ",
            "     │  (       )  │     ",
            "      ╲           ╱      ",
            "       ╰╥╥╥╥╥╥╥╥╥╥╥╯       ",
            "        ║║║║║║║║║║║        "
        ),
        listOf(
            "       ╭─────────╮       ",
            "      ╱      ◉───  ╲      ",
            "     │  (       )  │     ",
            "      ╲           ╱      ",
            "       ╰╥╥╥╥╥╥╥╥╥╥╥╯       ",
            "        ║║║║║║║║║║║        "
        ),
        listOf(
            "       ╭─────────╮       ",
            "      ╱        ◉─  ╲      ",
            "     │  (       )  │     ",
            "      ╲           ╱      ",
            "       ╰╥╥╥╥╥╥╥╥╥╥╥╯       ",
            "        ║║║║║║║║║║║        "
        ),
        listOf(
            "       ╭─────────╮       ",
            "      ╱          ◉  ╲      ",
            "     │  (       )  │     ",
            "      ╲           ╱      ",
            "       ╰╥╥╥╥╥╥╥╥╥╥╥╯       ",
            "        ║║║║║║║║║║║        "
        ),
        listOf(
            "       ╭─────────╮       ",
            "      ╱  ◉         ╲      ",
            "     │  (       )  │     ",
            "      ╲           ╱      ",
            "       ╰╥╥╥╥╥╥╥╥╥╥╥╯       ",
            "        ║║║║║║║║║║║        "
        ),
        listOf(
            "       ╭─────────╮       ",
            "      ╱  ◉──       ╲      ",
            "     │  (       )  │     ",
            "      ╲           ╱      ",
            "       ╰╥╥╥╥╥╥╥╥╥╥╥╯       ",
            "        ║║║║║║║║║║║        "
        )
    )

    // ===== CELEBRATING 庆祝（4 帧礼花跳跃）— 精致版 =====
    private val celebratingFrames = listOf(
        listOf(
            "  ✦    ╭─────────╮    ✦  ",
            " ✦✦  ╱  ◠     ◠  ╲  ✦✦ ",
            "✦✦✦ │  (  ★   ★  )  │ ✦✦✦",
            " ✦✦  ╲   ～～～   ╱  ✦✦ ",
            "  ✦    ╰╥╥╥╥╥╥╥╥╥╥╥╯    ✦  ",
            "       ╱║║║║║║║║║║║╲       "
        ),
        listOf(
            "✦  ✦  ╭─────────╮  ✦  ✦",
            " ✦✦ ╱  ◠     ◠  ╲ ✦✦ ",
            "  ✦│  (  ★   ★  )  │✦  ",
            " ✦✦ ╲   ～～～   ╱ ✦✦ ",
            "✦  ✦  ╰╥╥╥╥╥╥╥╥╥╥╥╥╯  ✦  ✦",
            "      ╱║║║║║║║║║║║╲      "
        ),
        listOf(
            "  ✦    ╭─────────╮    ✦  ",
            " ✦✦  ╱  ◠    ✦  ◠  ╲  ✦✦ ",
            "✦✦✦ │  (  ★   ★  )  │ ✦✦✦",
            " ✦✦  ╲   ～～～   ╱  ✦✦ ",
            "  ✦    ╰╥╥╥╥╥╥╥╥╥╥╥╥╯    ✦  ",
            "       ╱║║║║║║║║║║║╲       "
        ),
        listOf(
            "✦  ✦  ╭─────────╮  ✦  ✦",
            " ✦✦ ╱  ◠     ◠  ╲ ✦✦ ",
            "  ✦│  (  ★   ★  )  │✦  ",
            " ✦✦ ╲   ～～～   ╱ ✦✦ ",
            "✦  ✦  ╰╥╥╥╥╥╥╥╥╥╥╥╥╯  ✦  ✦",
            "      ╱║║║║║║║║║║║╲      "
        )
    )

    // ===== CURIOUS 好奇（3 帧倾斜探头）— 精致版 =====
    private val curiousFrames = listOf(
        listOf(
            "         ╭─────────╮      ",
            "        ╱  ◉     ◉  ╲     ",
            "       │  (  ◠   ◠  )  │    ",
            "        ╲   ═══╤═══   ╱     ",
            "         ╰╥╥╥╥╥╥╥╥╥╥╥╥╯      ",
            "          ║║║║║║║║║║║║       ",
            "           ║╿║╿║╿║╿║╿        "
        ),
        listOf(
            "          ╭─────────╮      ",
            "         ╱  ◉     ◉  ╲     ",
            "        │  (  ◠   ◠  )  │    ",
            "         ╲   ═══╤═══   ╱     ",
            "          ╰╥╥╥╥╥╥╥╥╥╥╥╥╯      ",
            "           ║║║║║║║║║║║║       ",
            "            ║╿║╿║╿║╿║╿        "
        ),
        listOf(
            "           ╭─────────╮      ",
            "          ╱  ◉     ◉  ╲     ",
            "         │  (  ◠   ◠  )  │    ",
            "          ╲   ═══╤═══   ╱     ",
            "           ╰╥╥╥╥╥╥╥╥╥╥╥╥╯      ",
            "            ║║║║║║║║║║║║       ",
            "             ║╿║╿║╿║╿║╿        "
        )
    )

    // ===== SHIELDING 防御（3 帧光盾展开）— 精致版 =====
    private val shieldingFrames = listOf(
        listOf(
            "    ╱╲╱╲╭─────────╮╱╲╱╲    ",
            "   ╱    ╱  ◉     ◉  ╲    ╲   ",
            "  │ ◦  │  (  ─   ─  )  │  ◦ │  ",
            "   ╲    ╲   ═══╤═══   ╱    ╱   ",
            "    ╲╱╲╱╰╥╥╥╥╥╥╥╥╥╥╥╥╥╯╲╱╲╱    ",
            "          ║║║║║║║║║║║║      "
        ),
        listOf(
            "   ╱╲╱╲╱╭─────────╮╱╲╱╲╱   ",
            "  ╱     ╱  ◉     ◉  ╲     ╲  ",
            " │ ◦◦ │  (  ─   ─  )  │ ◦◦ │ ",
            "  ╲     ╲   ═══╤═══   ╱     ╱  ",
            "   ╲╱╲╱╲╰╥╥╥╥╥╥╥╥╥╥╥╥╥╥╥╥╯╲╱╲╱╲   ",
            "         ║║║║║║║║║║║║     "
        ),
        listOf(
            "  ╱╲╱╲╱╲╭─────────╮╱╲╱╲╱╲  ",
            " ╱      ╱  ◉     ◉  ╲      ╲ ",
            " │ ◦◦◦ │  (  ─   ─  )  │ ◦◦◦ │",
            " ╲      ╲   ═══╤═══   ╱      ╱ ",
            "  ╲╱╲╱╲╱╰╥╥╥╥╥╥╥╥╥╥╥╥╥╥╥╥╥╥╯╲╱╲╱╲╱  ",
            "        ║║║║║║║║║║║║    "
        )
    )

    /**
     * 大号 Aura（用于欢迎界面）— 精致版。
     */
    val welcomeArt: List<String> = listOf(
        "            · · ╭═════════════════╮ · ·            ",
        "           · · ╱   ◎         ◎   ╲ · ·           ",
        "          · · ╱   (    ▽    ▽    )   ╲ · ·          ",
        "          · · ╲      ═════════      ╱ · ·          ",
        "           · · ╰╥╥╥╥╥╥╥╥╥╥╥╥╥╥╥╥╥╥╥╥╯ · ·           ",
        "            · ·  ║║║║║║║║║║║║║║║║║║  · ·            ",
        "             · ·  ║╿║╿║╿║╿║╿║╿║╿║╿  · ·             ",
        "              · ·  ║╽║╽║╽║╽║╽║╽║╽  · ·              ",
        "               · ·  ╲╱╲╱╲╱╲╱╲╱╲╱  · ·               ",
        "                · ·   ╲╱╲╱╲╱╲╱   · ·                ",
        "                 · ·    ╲╱╲╱    · ·                 ",
        "                  · ·     ·     · ·                  ",
        "                       A U R A                       "
    )

    fun getFrameCount(form: AuraForm): Int = getFrames(form).size

    fun getAnimationIntervalMs(form: AuraForm): Long = when (form) {
        AuraForm.IDLE -> 900L
        AuraForm.THINKING -> 650L
        AuraForm.TYPING -> 220L
        AuraForm.EXECUTING -> 280L
        AuraForm.BERSERK -> 120L
        AuraForm.SUCCESS -> 500L
        AuraForm.ERROR -> 130L
        AuraForm.SLEEPING -> 2400L
        AuraForm.EVOLVING -> 420L
        AuraForm.COLLABORATING -> 700L
        AuraForm.LOADING -> 160L
        AuraForm.CELEBRATING -> 380L
        AuraForm.CURIOUS -> 600L
        AuraForm.SHIELDING -> 450L,
        AuraForm.REMEMBERING -> 650L,
        AuraForm.ANALYZING -> 500L,
        AuraForm.LEARNING -> 700L,
        AuraForm.NETWORKING -> 400L,
        AuraForm.ROOT -> 600L,
        AuraForm.PLANNING -> 550L,
        AuraForm.COMPILING -> 320L,
        AuraForm.CONNECTING -> 450L,
        AuraForm.TOOLING -> 500L,
        AuraForm.SKILLING -> 480L,
        AuraForm.MCPING -> 460L
    }

    /** 获取形态对应的强调色 token（用于光晕 / 渐变渲染）。 */
    fun getAccent(form: AuraForm): AuraAccent = form.accent

    fun getEmoji(form: AuraForm): String = when (form) {
        AuraForm.IDLE -> "🪼"
        AuraForm.THINKING -> "🤔"
        AuraForm.TYPING -> "⌨️"
        AuraForm.EXECUTING -> "🚀"
        AuraForm.BERSERK -> "🔥"
        AuraForm.SUCCESS -> "✨"
        AuraForm.ERROR -> "💢"
        AuraForm.SLEEPING -> "💤"
        AuraForm.EVOLVING -> "🧬"
        AuraForm.COLLABORATING -> "🎭"
        AuraForm.LOADING -> "⏳"
        AuraForm.CELEBRATING -> "🎉"
        AuraForm.CURIOUS -> "👀"
        AuraForm.SHIELDING -> "🛡️"
        AuraForm.REMEMBERING -> "🧠"
        AuraForm.ANALYZING -> "🔍"
        AuraForm.LEARNING -> "📚"
        AuraForm.NETWORKING -> "🌐"
        AuraForm.ROOT -> "👑"
        AuraForm.PLANNING -> "🗺️"
        AuraForm.COMPILING -> "⚙️"
        AuraForm.CONNECTING -> "🔌"
        AuraForm.TOOLING -> "🛠️"
        AuraForm.SKILLING -> "💎"
        AuraForm.MCPING -> "🔗"
    }

    /**
     * 获取形态对应的 PNG drawable 资源名（用于 ImageView 渲染）。
     * 资源文件位于 res/drawable/aura_<form>.png。
     * Android 端用 resources.getIdentifier(name, "drawable", packageName) 解析。
     */
    fun getDrawableName(form: AuraForm): String = "aura_" + form.name.lowercase()

    /**
     * 获取形态对应的帧动画 AnimationDrawable 资源名。
     *
     * 资源文件位于 res/drawable/aura_anim_<form>.xml,引用 4 帧 PNG(aura_<form>_f1..f4)。
     * Android 端用 resources.getIdentifier(name, "drawable", packageName) 解析后,
     * 加载为 AnimationDrawable 在 ImageView 中播放。
     */
    fun getAnimationDrawableName(form: AuraForm): String = "aura_anim_" + form.name.lowercase()
}
