# Apex-Agent 网站完整设计提示词

## 🎯 项目概述

**项目名称**: Apex-Agent - 移动端AI自动化终极平台
**核心定位**: 以"狂暴模式"为核心，融合多Agent协作、深度记忆系统与网页自动化的全能型AI助手
**目标用户**: 技术开发者、AI爱好者、自动化工作流程构建者、Android高级用户

---

## 🎨 设计系统规范

### 双主题系统

#### 1. 液态玻璃主题 (Liquid Glass) - 浅色主题
**色彩方案**:
- 主色 (Primary): `#00486f` - 深海蓝
- 主色固定 (Primary Fixed): `#cce5ff`
- 次色 (Secondary): `#006875` - 深蓝绿
- 次色固定 (Secondary Fixed): `#9cf0ff`
- 强调色 (Tertiary): `#4d00ca` - 紫色
- 强调色固定 (Tertiary Fixed): `#e8deff`
- 背景色 (Background): `#faf8ff` - 浅灰蓝
- 表面色 (Surface): `#fcf9f8`
- 表面容器 (Surface Container): `#f0eded`
- 发光青 (Glow Cyan): `#00F5FF`
- 脉冲紫 (Pulse Violet): `#BD10E0`

**视觉效果**:
- 玻璃拟态 (Glassmorphism) 设计风格
- 毛玻璃模糊效果: `backdrop-filter: blur(20-25px)`
- 半透明白色背景: `rgba(255, 255, 255, 0.7-0.85)`
- 半透明边框: `rgba(255, 255, 255, 0.4-0.6)`
- 柔和渐变背景: 径向渐变从青色到紫色
- 精致阴影: 不刺眼的柔和阴影

#### 2. 霓虹东京主题 (Neon Tokyo) - 深色主题
**色彩方案**:
- 主色 (Primary): `#ff2d78` - 霓虹粉
- 次色 (Secondary): `#00ffcc` - 霓虹青
- 强调色 (Tertiary): `#ffe04a` - 霓虹黄
- 背景色 (Background): `#0a0a12` - 深蓝黑
- 表面色 (Surface): `#0f0f1a`
- 表面容器 (Surface Container): `#141422`
- 深色背景 (Dark BG): `#131b2e`

**视觉效果**:
- 赛博朋克 (Cyberpunk) 风格
- 动态扫描线效果
- 霓虹发光边框和阴影
- CRT显示器质感
- 像素网格背景
- 数字雨装饰元素

### 主题切换
- 右上角放置主题切换按钮
- 支持三个选项: 液态玻璃（浅色）、霓虹东京（深色）、跟随系统
- 切换时应用平滑过渡动画: `transition: all 0.5s ease`
- 主题状态持久化到本地存储

---

## 🏗️ 网站结构规划

### 1. 首页 (Landing Page)

#### 导航栏 (Navbar)
- 固定顶部导航
- 玻璃拟态效果
- Logo区域: "APEX AGENT" 加粗文字，带发光效果
- 导航链接: Features | Tech Stack | Burst Mode | Documentation | Download
- 右侧: 主题切换按钮 + "Get Started" 主按钮
- 移动端: 汉堡菜单 + 底部导航

#### Hero区域 (首屏)
- 全屏高度，居中布局
- 左侧:
  - 动态标签: "NEW: BURST MODE V2.0 IS LIVE" + 脉冲点
  - 主标题: "🚀 Apex-Agent"（大字号，霓虹发光效果）
  - 副标题: "移动端AI自动化终极平台"
  - 描述: 介绍核心功能的简短文案
  - 主CTA按钮: "立即下载"（主色调渐变，带脉冲发光）
  - 次CTA按钮: "查看文档"（幽灵按钮）
- 右侧:
  - 动态动画展示或手机模拟界面
  - 玻璃卡片容器，带浮动效果
- 背景: 径向渐变光晕 + 扫描线效果

#### 核心功能特性 (Features)
- 4列网格布局（移动端堆叠）
- 每个功能卡片:
  - 玻璃拟态容器
  - 图标 + 标题 + 简短描述
  - 相关图片展示
  - 悬停时轻微上浮 + 发光边框
- 功能包括:
  1. 🔥 狂暴模式 (Burst Mode)
  2. 🤖 多Agent协作 (Multi-Agent)
  3. 🧠 深度记忆系统 (Deep Memory)
  4. 🌐 网页自动化 (Web Automation)

#### 技术栈展示 (Tech Stack)
- 标题: "Core Engine Technologies"
- 横向滚动或流动展示
- 技术标签卡片:
  - Kotlin
  - Jetpack Compose
  - MNN Engine
  - llama.cpp
  - Webkit Automation
  - ObjectBox
  - QuickJS
- 每个标签带图标和悬停效果

#### 产品截图展示 (Screenshots)
- 3-4个主要界面截图轮播
- 双主题对比展示
- 可选点击交互查看大图

#### 下载区域 (Download)
- 居中CTA区域
- 大标题: "Ready to scale your AI Workflow?"
- 描述文案
- 下载按钮: Android APK
- 系统要求: Android 8.0+, 4GB RAM+, 500MB+ 存储
- 安全提示

### 2. 功能详情页面

#### 狂暴模式页面 (Burst Mode)
- 详细介绍狂暴模式的核心能力
- 技术架构图
- 断点续传机制说明
- 批量任务处理演示
- 动画效果展示
- 使用场景和案例

#### 多Agent协作页面
- Agent系统架构图
- 任务分解和并行执行说明
- 协作流程演示
- 实际案例

#### 深度记忆系统页面
- 记忆系统架构
- 用户画像和知识图谱
- 个性化展示
- 实际效果展示

#### 网页自动化页面
- 浏览器控制能力
- 数据采集功能
- 自动化流程展示
- 实际案例

### 3. 文档页面 (Documentation)
- 左侧多级导航菜单
- 右侧内容区域
- 代码高亮显示
- 交互式示例
- 复制代码按钮
- 版本切换器
- 目录包括:
  - 快速开始
  - 安装指南
  - 功能详解
  - API参考
  - 示例代码
  - 故障排除

### 4. 下载页面 (Download)
- APK下载按钮
- 版本历史
- 更新日志
- 系统要求
- 安装教程
- 常见问题

### 5. 社区页面 (Community)
- GitHub仓库链接
- Discord服务器邀请
- 贡献指南
- 贡献者展示
- 常见问题FAQ

### 6. 关于页面 (About)
- 项目介绍
- 团队成员
- 发展历程时间线
- 许可证信息
- 联系方式

---

## 🎯 交互设计规范

### 导航系统
- 桌面端: 顶部导航栏（玻璃风格）
- 移动端: 底部导航栏 + 汉堡菜单
- 当前页面高亮显示 + 下划线动画
- 滚动时导航栏轻微模糊 + 阴影增强

### 滚动效果
- 页面元素随滚动渐入动画
- 视差滚动效果
- 进度指示器（页面右侧）
- 返回顶部按钮（滚动超过1屏后出现）

### 按钮交互
- 常规按钮: 液态玻璃效果
- 主CTA按钮: 渐变色 + 光晕 + 悬停放大
- 次要按钮: 幽灵按钮（透明背景 + 边框）
- 点击反馈: 缩小90-95% + 颜色加深
- 悬停效果: 光晕扩散

### 卡片交互
- 悬停: 轻微上浮 (4-8px) + 边框发光 + 阴影增强
- 点击: 再缩小一点 + 波纹效果
- 加载: 骨架屏 + 脉冲动画
- 鼠标跟踪: 卡片内光晕跟随鼠标移动

### 输入交互
- 输入框聚焦效果
- 验证状态显示
- 建议提示

---

## 📱 响应式设计规范

### 断点设置
```css
- 移动端 (Mobile): < 640px
- 平板端 (Tablet): 640px - 1024px
- 桌面端 (Desktop): 1024px - 1440px
- 大屏端 (Large Desktop): > 1440px
```

### 移动端优化
- 垂直堆叠布局
- 增大触摸目标: 最小48px
- 简化导航
- 优化性能（减少动画复杂度）
- 底部导航栏

### 平板端优化
- 网格布局（2-3列）
- 侧边栏导航可选
- 保持触摸友好

### 桌面端优化
- 4列网格布局
- 固定宽度容器（max-width: 1200-1440px）
- 丰富的动画效果
- 充分利用屏幕空间

---

## 🎬 动画与动效规范

### 核心动画原则
- 时长: 200-500ms
- 缓动: `cubic-bezier(0.4, 0, 0.2, 1)` 或 `ease-in-out`
- z-index层级管理清晰

### 关键动画列表

#### 页面加载动画
- Logo渐入
- 内容依次从下往上滑入
- 背景光晕缓慢流动
- 延迟序列: 100-200ms间隔

#### 导航交互动画
- 悬停: 文字颜色渐变 + 下划线生长
- 点击: 轻微缩放
- 切换: 平滑过渡

#### 卡片交互动画
- 悬停: 上浮 (translateY) + 发光
- 点击: 按压效果 (scale)
- 加载: 骨架屏脉冲

#### 按钮交互动画
- 悬停: 光晕扩散 + 轻微放大
- 点击: 缩小 + 波纹
- 加载: 旋转动画 + 进度指示

#### 滚动动画
- 元素渐入 (opacity + translateY)
- 视差效果
- 进度条动画

#### 主题切换动画
- 3-5秒平滑过渡
- 所有元素同时变色
- 可选开关式瞬间切换

#### 背景装饰动画
- 浮动光晕: 20s周期的缓慢移动
- 扫描线: 持续缓慢滚动
- 脉冲光晕: 呼吸式发光

---

## 🎨 视觉元素规范

### 图标系统
- 风格: Material Symbols Outlined / Filled
- 尺寸: 24px, 32px, 48px, 64px
- 颜色: 随主题自动切换
- 动画: 悬停时轻微放大 + 颜色变化

### 排版系统
```css
- 超大标题 (Headline XL): 48-64px, 700 weight, line-height 1.2
- 大标题 (Headline L): 32-48px, 600-700 weight, line-height 1.3
- 中标题 (Headline M): 24-32px, 600 weight, line-height 1.4
- 小标题 (Headline S): 18-24px, 500-600 weight, line-height 1.5
- 正文 (Body): 16px, 400 weight, line-height 1.6
- 小文字 (Label): 14px, 500 weight
- 标注 (Caption): 12px, 600 weight
```

### 字体选择
- 中文字体: Inter + 系统默认字体
- 英文字体: Inter (主要), Space Grotesk (标签), Sora (标题)
- 等宽字体: JetBrains Mono (代码块)

### 间距系统
- 基础单位: 8px
- 常用间距: 8, 16, 24, 32, 48, 64, 96px
- 组件间距: 16-24px
- 区域间距: 48-96px

### 圆角系统
- 小 (Small): 4-8px
- 中 (Medium/Default): 12-16px
- 大 (Large): 20-24px
- 完全圆角 (Full): 9999px

### 阴影系统
- 小阴影: 0 2px 4px rgba(0, 0, 0, 0.1)
- 中阴影: 0 4px 12px rgba(0, 0, 0, 0.15)
- 大阴影: 0 8px 32px rgba(0, 0, 0, 0.2)
- 发光阴影: 0 0 20px rgba(primary, 0.4)

---

## 🛠️ 技术实现建议

### 技术栈推荐
- **框架**: Next.js 14+ (App Router) 或 React + Vite
- **样式**: Tailwind CSS 3.4+ + CSS变量
- **动画**: Framer Motion 或 CSS animations
- **图标**: Material Symbols + 自定义SVG
- **代码高亮**: shiki / rehype-pretty-code
- **主题**: next-themes
- **部署**: Vercel / Cloudflare Pages / Netlify

### 核心实现思路

#### CSS变量主题系统
```css
:root {
  /* 液态玻璃主题 (浅色) */
  --color-primary: #00486f;
  --color-secondary: #006875;
  --color-tertiary: #4d00ca;
  --color-background: #faf8ff;
  --color-surface: #fcf9f8;
  --color-surface-container: rgba(255, 255, 255, 0.7);
  --color-border: rgba(255, 255, 255, 0.4);
  --color-glow-cyan: #00F5FF;
  --color-pulse-violet: #BD10E0;
  --blur-amount: 20px;
}

[data-theme="neon-tokyo"] {
  /* 霓虹东京主题 (深色) */
  --color-primary: #ff2d78;
  --color-secondary: #00ffcc;
  --color-tertiary: #ffe04a;
  --color-background: #0a0a12;
  --color-surface: #0f0f1a;
  --color-surface-container: rgba(20, 20, 34, 0.6);
  --color-border: rgba(255, 45, 120, 0.3);
  --blur-amount: 24px;
}
```

#### 玻璃效果组件
```tsx
const GlassCard = ({ children, className, level = 1 }) => {
  const levelClasses = {
    1: "bg-white/70 backdrop-blur-[20px] border-white/40",
    2: "bg-white/85 backdrop-blur-[25px] border-white/60",
  };

  return (
    <div className={`
      ${levelClasses[level]}
      border rounded-xl shadow-lg
      transition-all duration-300
      hover:shadow-xl hover:-translate-y-1
      ${className}
    `}>
      {children}
    </div>
  );
};
```

#### 主题切换动画
- 使用CSS transition
- 所有使用CSS变量的元素自动过渡
- 3-5秒的duration确保流畅

---

## 📄 内容文案指南

### 语调风格
- 专业但友好
- 技术但易懂
- 现代、充满科技感
- 积极向上的语气

### 关键词汇（必须包含）
- Apex-Agent
- 狂暴模式 (Burst Mode)
- 多Agent协作 (Multi-Agent Collaboration)
- 深度记忆系统 (Deep Memory System)
- 网页自动化 (Web Automation)
- 液态玻璃 (Liquid Glass)
- 霓虹东京 (Neon Tokyo)
- 本地推理 (Local Inference)
- MNN / llama.cpp
- MCP协议

### SEO关键词
- Android AI助手
- 移动端自动化
- AI Agent平台
- 狂暴模式
- 本地推理
- MCP协议
- llama.cpp
- 多Agent协作

---

## 🎯 设计检查清单

### 完成前检查
- [ ] 双主题完整实现（液态玻璃 + 霓虹东京）
- [ ] 所有页面响应式适配（移动/平板/桌面）
- [ ] 动画流畅无卡顿
- [ ] 可访问性检查（对比度、键盘导航、ARIA标签）
- [ ] 性能优化（Lighthouse 90+分）
- [ ] 品牌一致性检查
- [ ] 所有链接可跳转
- [ ] 跨浏览器测试（Chrome, Firefox, Safari, Edge）
- [ ] 移动端真机测试
- [ ] SEO优化完成

### 交付物清单
- [ ] 完整的网站源代码
- [ ] 设计系统文档
- [ ] 组件库/组件展示
- [ ] 部署配置
- [ ] 维护指南

---

## 💡 额外创意建议

### 3D元素
- 背景添加缓慢旋转的3D几何体
- Hero区域添加动态粒子效果
- 功能展示使用3D旋转动画
- 使用Three.js或React Three Fiber实现

### 交互演示
- 添加轻量级的功能演示模拟器
- 狂暴模式执行过程可视化
- 多Agent协作流程图动画
- 记忆系统知识图谱可视化

### 用户案例
- 真实用户使用场景展示
- 工作流程展示
- 效果对比图表
- 客户评价轮播

### 开发日志
- 项目开发历程时间线
- 重要里程碑展示
- 技术决策说明
- 幕后故事

### 性能优化
- 图片懒加载
- 代码分割
- 预加载关键资源
- CDN加速

---

## 📞 沟通与反馈

### 设计流程
1. 先完成首页设计并确认
2. 再逐步实现其他页面
3. 保持每个阶段的反馈收集
4. 关键设计决策需要确认

### 迭代改进
- 保持灵活性，随时调整
- 根据反馈快速迭代
- 优先实现核心功能
- 逐步完善细节

---

## 📚 参考资源

### 项目文档
- README.md - 完整项目介绍
- SKILL_SYSTEM.md - 技能系统说明
- INTEGRATION_GUIDE.md - 集成指南
- CURRENT_STATUS.md - 当前状态

### 现有文件
- web/index.html - 已整理的首页示例
- WEBSITE_DESIGN_PROMPT.md - 之前的设计提示词

### 设计灵感
- 赛博朋克风格参考: Blade Runner 2049, Ghost in the Shell
- 玻璃拟态参考: Apple macOS, Windows 11
- 现代科技网站参考: Vercel, Linear, Raycast

---

*此提示词旨在为AI设计工具提供极其详细的指导。请根据实际情况灵活调整，并确保最终设计能够完美展现Apex-Agent的技术实力和产品魅力！*
