# 狂暴模式工具集使用指南

## 概述

狂暴模式提供了丰富的工具集，专门为处理超复杂任务而设计。工具集分为多个类别，涵盖代码开发、测试、文档、调试、部署等全流程。

## 工具类别

### 1. 代码质量与分析工具

#### code_quality_analyze
**功能**: 深度分析代码质量，检测代码异味、潜在bug和安全问题

**参数**:
- `path` (必需): 代码路径
- `depth`: 分析深度 (normal/deep)
- `includeSecurity`: 是否包含安全分析

**使用示例**:
```
输入: /path/to/code
深度: normal
安全分析: true
```

#### code_metrics_calculate
**功能**: 计算代码度量指标，包括复杂度、耦合度、内聚度等

**参数**:
- `path` (必需): 代码路径
- `metrics`: 度量类型 (all/cyclomatic/coupling/cohesion)

#### dependency_analyze
**功能**: 分析代码依赖关系，检测循环依赖和未使用依赖

**参数**:
- `path` (必需): 项目路径
- `detectCycles`: 是否检测循环依赖

#### code_complexity_calculate
**功能**: 计算代码圈复杂度，识别需要重构的高复杂度方法

**参数**:
- `path` (必需): 代码路径
- `threshold`: 复杂度阈值 (默认10)

---

### 2. 代码重构工具

#### code_refactor
**功能**: 自动执行代码重构，包括提取方法、重命名变量等

**参数**:
- `path` (必需): 文件路径
- `type` (必需): 重构类型
- `options`: 重构选项

**支持的重构类型**:
- extract_method: 提取方法
- rename_variable: 重命名变量
- inline_temp: 内联临时变量
- move_method: 移动方法
- pull_up: 上拉方法到父类
- push_down: 下推方法到子类

#### code_format
**功能**: 自动格式化代码，保持一致的代码风格

**参数**:
- `path` (必需): 文件路径
- `style`: 代码风格 (kotlin/java/javascript)
- `options`: 格式化选项

#### code_generate
**功能**: 根据规格说明生成代码骨架

**参数**:
- `spec` (必需): 代码规格
- `language` (必需): 编程语言
- `framework`: 框架类型

#### code_template_apply
**功能**: 应用代码模板快速生成标准代码结构

**参数**:
- `template` (必需): 模板名称
- `params` (必需): 模板参数
- `outputPath`: 输出路径

---

### 3. 测试工具

#### unit_test_generate
**功能**: 为代码自动生成单元测试用例

**参数**:
- `path` (必需): 源代码路径
- `framework`: 测试框架 (junit/testng/mockito)
- `coverage`: 覆盖目标 (method/branch/line)

**使用场景**:
- 新功能开发时生成测试用例
- 重构后回归测试生成
- 边界条件测试覆盖

#### test_run
**功能**: 运行测试用例并生成报告

**参数**:
- `path` (必需): 测试路径
- `framework`: 测试框架
- `options`: 运行选项

#### coverage_analyze
**功能**: 分析代码覆盖率，生成详细报告

**参数**:
- `path` (必需): 项目路径
- `format`: 报告格式 (html/json/xml)

#### mock_generate
**功能**: 为接口和类生成Mock对象

**参数**:
- `interface` (必需): 接口名称
- `framework`: Mock框架 (mockito/easymock)

---

### 4. 文档生成工具

#### api_doc_generate
**功能**: 从代码注释生成API文档

**参数**:
- `path` (必需): 代码路径
- `format`: 文档格式 (markdown/html/pdf)
- `includeExamples`: 是否包含示例代码

#### code_doc_generate
**功能**: 为代码生成详细的文档注释

**参数**:
- `path` (必需): 文件路径
- `style`: 注释风格
- `includeParams`: 是否包含参数说明

#### readme_generate
**功能**: 生成项目的README文档

**参数**:
- `path` (必需): 项目路径
- `sections`: 包含章节
- `badges`: 是否包含徽章

**章节选项**:
- description: 项目描述
- installation: 安装说明
- usage: 使用示例
- features: 功能特性
- license: 许可证
- contributing: 贡献指南

#### changelog_generate
**功能**: 从Git提交记录生成变更日志

**参数**:
- `fromTag` (必需): 起始标签
- `toTag`: 结束标签 (默认HEAD)
- `format`: 输出格式 (markdown/json)

---

### 5. 调试和监控工具

#### debugger_attach
**功能**: 附加到运行中的进程进行调试

**参数**:
- `pid` (必需): 进程ID
- `breakpoints`: 断点位置

#### performance_profile
**功能**: 对代码进行性能分析，识别瓶颈

**参数**:
- `path` (必需): 代码路径
- `duration`: 采样时长(秒)
- `interval`: 采样间隔(ms)

**输出指标**:
- 执行时间分布
- 热点函数
- 调用栈分析
- 内存分配统计

#### memory_analyze
**功能**: 分析内存使用情况，检测内存泄漏

**参数**:
- `pid` (必需): 进程ID
- `type`: 分析类型 (heap/dump/stat)

#### log_analyze
**功能**: 分析日志文件，提取关键信息和异常

**参数**:
- `path` (必需): 日志路径
- `pattern`: 匹配模式 (正则表达式)
- `level`: 日志级别 (error/warn/info/debug)

---

### 6. 构建和部署工具

#### build_orchestrate
**功能**: 协调多模块项目的构建流程

**参数**:
- `path` (必需): 项目路径
- `tasks` (必需): 构建任务
- `parallel`: 是否并行执行

**任务类型**:
- compile: 编译
- test: 测试
- package: 打包
- deploy: 部署
- clean: 清理

#### deploy_simulate
**功能**: 模拟部署过程，验证部署配置

**参数**:
- `config` (必需): 部署配置
- `target`: 目标环境 (dev/staging/prod)

#### config_validate
**功能**: 验证配置文件的有效性和一致性

**参数**:
- `path` (必需): 配置文件路径
- `schema`: 配置Schema

#### environment_check
**功能**: 检查运行环境依赖和配置

**参数**:
- `requirements` (必需): 需求列表
- `severity`: 检查严格度 (error/warning/info)

---

### 7. 数据处理工具

#### data_transform
**功能**: 转换数据格式，如JSON、XML、CSV之间互转

**参数**:
- `input` (必需): 输入数据
- `fromFormat` (必需): 源格式
- `toFormat` (必需): 目标格式

**支持格式**:
- JSON ↔ XML
- JSON ↔ CSV
- XML ↔ CSV
- YAML ↔ JSON

#### schema_validate
**功能**: 验证数据是否符合Schema定义

**参数**:
- `data` (必需): 数据内容
- `schema` (必需): Schema定义
- `strict`: 严格模式

#### data_migrate
**功能**: 迁移数据到新的数据模型

**参数**:
- `source` (必需): 源配置
- `target` (必需): 目标配置
- `mapping`: 字段映射

#### query_build
**功能**: 根据自然语言描述构建SQL查询

**参数**:
- `description` (必需): 查询描述
- `schema`: 数据库Schema
- `dialect`: SQL方言 (ansi/mysql/postgresql)

---

### 8. 版本控制工具

#### git_analyze
**功能**: 分析Git仓库统计信息

**参数**:
- `path` (必需): 仓库路径
- `since`: 分析起始点
- `metrics`: 统计指标

**统计指标**:
- 提交频率
- 代码增长趋势
- 开发者活跃度
- 文件变更统计

#### diff_analyze
**功能**: 分析代码差异，预测冲突和影响

**参数**:
- `before` (必需): 原始内容
- `after` (必需): 修改内容
- `context`: 上下文行数

#### merge_advise
**功能**: 提供代码合并建议，预测冲突

**参数**:
- `source` (必需): 源分支
- `target` (必需): 目标分支

#### branch_plan
**功能**: 规划Git分支策略和发布流程

**参数**:
- `project` (必需): 项目类型
- `teamSize`: 团队规模
- `releaseCycle`: 发布周期

---

### 9. AI增强工具

#### context_summarize
**功能**: 对长文本进行压缩摘要，保留关键信息

**参数**:
- `content` (必需): 待摘要内容
- `maxLength`: 最大长度
- `preserve`: 保留元素 (标题/列表/代码块)

#### intent_classify
**功能**: 分类用户意图，优化任务路由

**参数**:
- `input` (必需): 用户输入
- `domains`: 候选领域

#### response_format
**功能**: 格式化AI响应为指定格式

**参数**:
- `content` (必需): 响应内容
- `format`: 目标格式 (markdown/html/json)
- `template`: 输出模板

#### task_decompose
**功能**: 将复杂任务分解为可执行的子任务

**参数**:
- `task` (必需): 任务描述
- `constraints`: 约束条件
- `parallelizable`: 允许并行

---

### 10. 写作创作工具

#### article_write
**功能**: 根据主题和要求撰写文章

**参数**:
- `topic` (必需): 文章主题
- `length`: 文章长度(字)
- `style`: 文章风格 (formal/informal/academic)
- `requirements`: 具体要求

#### copywrite
**功能**: 创作营销文案、产品描述等

**参数**:
- `product` (必需): 产品/服务描述
- `targetAudience`: 目标受众
- `tone`: 文案风格 (professional/friendly/casual)
- `purpose`: 文案目的 (promotion/information/sales)

#### content_edit
**功能**: 编辑和优化现有内容

**参数**:
- `content` (必需): 待编辑内容
- `purpose`: 编辑目的 (improvement/proofreading/rewriting)
- `style`: 目标风格
- `feedback`: 具体反馈

#### translate
**功能**: 翻译文本到指定语言

**参数**:
- `text` (必需): 待翻译文本
- `targetLanguage` (必需): 目标语言
- `sourceLanguage`: 源语言
- `style`: 翻译风格

#### grammar_check
**功能**: 检查和纠正语法错误

**参数**:
- `text` (必需): 待检查文本
- `language`: 语言 (zh/en/ja)
- `level`: 检查级别 (basic/comprehensive)

#### plagiarism_detect
**功能**: 检测文本抄袭情况

**参数**:
- `text` (必需): 待检测文本
- `threshold`: 抄袭阈值
- `sources`: 参考来源

#### seo_optimize
**功能**: 优化内容以提高搜索引擎排名

**参数**:
- `content` (必需): 待优化内容
- `keywords` (必需): 目标关键词
- `target`: 目标平台 (google/baidu)
- `level`: 优化级别 (basic/standard/advanced)

#### title_generate
**功能**: 为文章或内容生成吸引人的标题

**参数**:
- `content` (必需): 内容摘要
- `type`: 标题类型 (engaging/clickbait/professional)
- `count`: 生成数量
- `length`: 标题长度

#### story_write
**功能**: 创作故事、小说等创意内容

**参数**:
- `genre` (必需): 故事类型
- `plot` (必需): 情节概要
- `characters`: 角色描述
- `length`: 故事长度

#### poetry_generate
**功能**: 创作诗歌、韵文等文学作品

**参数**:
- `theme` (必需): 诗歌主题
- `style`: 诗歌风格 (free_verse/sonnet/haiku)
- `length`: 诗歌长度
- `language`: 语言

#### email_write
**功能**: 撰写专业邮件

**参数**:
- `purpose` (必需): 邮件目的
- `recipient`: 收件人
- `content` (必需): 邮件内容要点
- `tone`: 邮件语气 (professional/formal/friendly)

#### report_write
**功能**: 撰写各类报告

**参数**:
- `type` (必需): 报告类型
- `data` (必需): 报告数据
- `purpose`: 报告目的
- `format`: 报告格式 (structured/outline/detailed)

#### resume_build
**功能**: 制作专业简历

**参数**:
- `personalInfo` (必需): 个人信息
- `experience` (必需): 工作经验
- `education`: 教育背景
- `skills`: 技能专长
- `style`: 简历风格 (professional/creative/traditional)

#### social_media_copy
**功能**: 创作社交媒体内容

**参数**:
- `platform` (必需): 社交平台 (weibo/wechat/twitter/facebook)
- `content` (必需): 内容主题
- `tone`: 语气风格 (engaging/formal/casual)
- `length`: 内容长度

#### ad_copy
**功能**: 创作广告文案

**参数**:
- `product` (必需): 产品/服务
- `targetAudience` (必需): 目标受众
- `platform`: 投放平台
- `callToAction`: 行动号召
- `length`: 文案长度

---

### 11. 数据分析工具

#### data_import
**功能**: 导入数据到分析系统

**参数**:
- `data` (必需): 数据内容
- `format` (必需): 数据格式 (json/csv/xml)
- `options`: 导入选项

#### data_clean
**功能**: 清理和预处理数据

**参数**:
- `data` (必需): 原始数据
- `operations`: 清理操作 (remove_empty/normalize/remove_duplicates)
- `format`: 输出格式

#### data_statistics
**功能**: 计算数据统计指标

**参数**:
- `data` (必需): 数据内容
- `metrics`: 统计指标 (mean/median/std/min/max)
- `groupBy`: 分组字段

#### data_visualize
**功能**: 数据可视化

**参数**:
- `data` (必需): 数据内容
- `type` (必需): 图表类型 (bar/line/pie/scatter)
- `options`: 可视化选项

#### chart_generate
**功能**: 生成各种图表

**参数**:
- `data` (必需): 图表数据
- `chartType` (必需): 图表类型
- `title`: 图表标题
- `options`: 图表选项

#### data_report_generate
**功能**: 生成数据分析报告

**参数**:
- `data` (必需): 分析数据
- `title` (必需): 报告标题
- `format`: 报告格式 (markdown/html/pdf)
- `sections`: 报告章节

#### trend_analyze
**功能**: 分析数据趋势

**参数**:
- `data` (必需): 时间序列数据
- `timeField` (必需): 时间字段
- `valueField` (必需): 值字段
- `method`: 分析方法 (moving_average/linear_regression)

#### data_predict
**功能**: 基于历史数据进行预测

**参数**:
- `data` (必需): 历史数据
- `target` (必需): 预测目标
- `method`: 预测方法 (linear_regression/arima)
- `horizon`: 预测 horizon

#### correlation_analyze
**功能**: 分析变量间相关性

**参数**:
- `data` (必需): 数据内容
- `variables` (必需): 变量列表
- `method`: 相关系数方法 (pearson/spearman)
- `threshold`: 相关性阈值

#### anomaly_detect
**功能**: 检测数据异常

**参数**:
- `data` (必需): 数据内容
- `method`: 检测方法 (z_score/iqr/isolation_forest)
- `threshold`: 异常阈值
- `window`: 检测窗口

#### data_export
**功能**: 导出数据到不同格式

**参数**:
- `data` (必需): 数据内容
- `format` (必需): 目标格式
- `options`: 导出选项

#### sql_query
**功能**: 执行SQL查询

**参数**:
- `query` (必需): SQL查询语句
- `connection`: 数据库连接
- `format`: 结果格式 (json/csv)
- `limit`: 结果限制

---

### 12. 多媒体处理工具

#### image_compress
**功能**: 压缩图片文件

**参数**:
- `image` (必需): 图片路径
- `quality`: 压缩质量
- `outputPath`: 输出路径
- `format`: 输出格式

#### image_convert
**功能**: 转换图片格式

**参数**:
- `image` (必需): 图片路径
- `format` (必需): 目标格式
- `outputPath`: 输出路径
- `quality`: 图片质量

#### image_crop
**功能**: 裁剪图片

**参数**:
- `image` (必需): 图片路径
- `x` (必需): 起始X坐标
- `y` (必需): 起始Y坐标
- `width` (必需): 裁剪宽度
- `height` (必需): 裁剪高度
- `outputPath`: 输出路径

#### image_watermark
**功能**: 为图片添加水印

**参数**:
- `image` (必需): 图片路径
- `watermark` (必需): 水印内容
- `position`: 水印位置 (top_left/top_right/bottom_left/bottom_right)
- `opacity`: 水印透明度
- `outputPath`: 输出路径

#### ocr
**功能**: 光学字符识别

**参数**:
- `image` (必需): 图片路径
- `language`: 识别语言 (zh/en/ja)
- `outputFormat`: 输出格式 (text/json)
- `options`: OCR选项

#### audio_to_text
**功能**: 音频转文字

**参数**:
- `audio` (必需): 音频路径
- `language`: 识别语言 (zh/en)
- `model`: 识别模型
- `outputFormat`: 输出格式 (text/json)

#### video_screenshot
**功能**: 视频截图

**参数**:
- `video` (必需): 视频路径
- `time` (必需): 截图时间点(秒)
- `outputPath`: 输出路径
- `format`: 输出格式 (jpg/png)
- `quality`: 图片质量

#### video_compress
**功能**: 压缩视频文件

**参数**:
- `video` (必需): 视频路径
- `quality`: 压缩质量
- `outputPath`: 输出路径
- `format`: 输出格式

#### media_convert
**功能**: 媒体格式转换

**参数**:
- `input` (必需): 输入文件
- `outputFormat` (必需): 输出格式
- `outputPath`: 输出路径
- `options`: 转换选项

#### thumbnail_generate
**功能**: 生成缩略图

**参数**:
- `input` (必需): 输入文件
- `size`: 缩略图尺寸 (widthxheight)
- `outputPath`: 输出路径
- `format`: 输出格式 (jpg/png)
- `quality`: 图片质量

---

### 13. 信息检索工具

#### web_search
**功能**: 网页搜索

**参数**:
- `query` (必需): 搜索关键词
- `engine`: 搜索引擎 (google/baidu/bing)
- `count`: 结果数量
- `language`: 语言

#### news_aggregator
**功能**: 新闻聚合

**参数**:
- `topic` (必需): 新闻主题
- `source`: 新闻来源
- `count`: 新闻数量
- `language`: 语言
- `timeRange`: 时间范围 (24h/7d/30d)

#### knowledge_qa
**功能**: 知识问答

**参数**:
- `question` (必需): 问题
- `domain`: 知识领域
- `sources`: 包含来源
- `detailed`: 详细回答

#### encyclopedia
**功能**: 百科查询

**参数**:
- `topic` (必需): 查询主题
- `language`: 语言
- `detailed`: 详细信息
- `format`: 输出格式 (markdown/text)

#### dictionary
**功能**: 词典翻译

**参数**:
- `word` (必需): 单词或短语
- `sourceLanguage`: 源语言 (auto/zh/en)
- `targetLanguage`: 目标语言
- `detailed`: 详细解释

#### calculator
**功能**: 计算器

**参数**:
- `expression` (必需): 数学表达式
- `precision`: 精度
- `format`: 输出格式 (decimal/fraction)

#### unit_converter
**功能**: 单位换算

**参数**:
- `value` (必需): 数值
- `fromUnit` (必需): 源单位
- `toUnit` (必需): 目标单位
- `category`: 单位类别 (length/weight/time/temperature)

#### timezone
**功能**: 时区查询

**参数**:
- `location` (必需): 地点
- `date`: 日期
- `time`: 时间
- `format`: 输出格式 (iso/local)

#### currency_convert
**功能**: 货币换算

**参数**:
- `amount` (必需): 金额
- `fromCurrency` (必需): 源货币
- `toCurrency` (必需): 目标货币
- `date`: 汇率日期

#### postal_code
**功能**: 邮编查询

**参数**:
- `address` (必需): 地址
- `country`: 国家 (CN/US/UK)
- `format`: 输出格式 (json/text)

---

### 14. 办公效率工具

#### pdf_to_word
**功能**: PDF转Word

**参数**:
- `pdf` (必需): PDF文件路径
- `outputPath`: 输出路径
- `format`: 输出格式 (docx/doc)
- `options`: 转换选项

#### word_to_pdf
**功能**: Word转PDF

**参数**:
- `word` (必需): Word文件路径
- `outputPath`: 输出路径
- `options`: 转换选项

#### excel
**功能**: Excel处理

**参数**:
- `file` (必需): Excel文件路径
- `operation` (必需): 操作类型 (read/write/analyze)
- `sheet`: 工作表
- `range`: 数据范围
- `output`: 输出路径

#### ppt_generate
**功能**: 生成PPT

**参数**:
- `content` (必需): 内容大纲
- `title` (必需): PPT标题
- `template`: 模板选择
- `outputPath`: 输出路径
- `slides`: 幻灯片数量

#### document_merge
**功能**: 文档合并

**参数**:
- `files` (必需): 文件列表
- `outputPath` (必需): 输出路径
- `format` (必需): 输出格式 (pdf/docx)
- `options`: 合并选项

#### document_split
**功能**: 文档分割

**参数**:
- `file` (必需): 源文件
- `splitPoints` (必需): 分割点
- `outputDir` (必需): 输出目录
- `format`: 输出格式

#### document_encrypt
**功能**: 文档加密

**参数**:
- `file` (必需): 源文件
- `password` (必需): 密码
- `outputPath`: 输出路径
- `options`: 加密选项

#### business_card_scan
**功能**: 名片识别

**参数**:
- `image` (必需): 名片图片
- `language`: 识别语言 (zh/en)
- `outputFormat`: 输出格式 (json/vcard)
- `options`: 识别选项

---

### 15. 日常生活工具

#### weather
**功能**: 查询天气

**参数**:
- `location` (必需): 地点
- `days`: 查询天数
- `unit`: 温度单位 (celsius/fahrenheit)
- `language`: 语言

#### schedule
**功能**: 创建日程

**参数**:
- `title` (必需): 日程标题
- `startTime` (必需): 开始时间
- `endTime`: 结束时间
- `description`: 日程描述
- `reminder`: 是否提醒

#### reminder
**功能**: 设置提醒

**参数**:
- `content` (必需): 提醒内容
- `time` (必需): 提醒时间
- `repeat`: 重复方式 (daily/weekly/monthly)
- `priority`: 优先级 (low/normal/high)

#### alarm
**功能**: 设置闹钟

**参数**:
- `time` (必需): 闹钟时间
- `label`: 闹钟标签
- `repeat`: 重复方式 (once/daily/weekly)
- `sound`: 闹钟铃声

#### countdown
**功能**: 设置倒计时

**参数**:
- `seconds` (必需): 倒计时秒数
- `title`: 倒计时标题
- `sound`: 结束声音

#### timer
**功能**: 设置计时器

**参数**:
- `name`: 计时器名称
- `start`: 开始计时
- `lap`: 支持计圈

#### life_unit_convert
**功能**: 生活单位换算

**参数**:
- `value` (必需): 数值
- `fromUnit` (必需): 源单位
- `toUnit` (必需): 目标单位
- `category`: 单位类别

#### address_query
**功能**: 地址查询

**参数**:
- `address` (必需): 地址
- `type`: 查询类型 (geocode/reverse_geocode)
- `format`: 输出格式 (json/text)

---

### 16. 创意工具

#### creative_write
**功能**: 创意写作

**参数**:
- `topic` (必需): 创作主题
- `style` (必需): 创作风格
- `length`: 长度
- `requirements`: 具体要求

#### design_ideas
**功能**: 设计创意

**参数**:
- `project` (必需): 项目描述
- `style`: 设计风格
- `count`: 创意数量
- `requirements`: 具体要求

#### brainstorm
**功能**: 头脑风暴

**参数**:
- `topic` (必需): 主题
- `time`: 时长(分钟)
- `count`: 创意数量
- `format`: 输出格式 (list/mindmap)

#### idea_generate
**功能**: 创意生成

**参数**:
- `domain` (必需): 领域
- `goal`: 目标
- `count`: 创意数量
- `constraints`: 约束条件

#### creative_prompt
**功能**: 创意提示词生成

**参数**:
- `topic` (必需): 主题
- `style`: 风格
- `count`: 提示词数量
- `length`: 提示词长度

#### artistic_style
**功能**: 艺术风格分析与生成

**参数**:
- `style` (必需): 风格名称
- `examples`: 包含示例
- `description`: 包含描述
- `applications`: 应用场景

#### story_ideas
**功能**: 故事创意

**参数**:
- `genre` (必需): 类型
- `theme`: 主题
- `count`: 创意数量
- `length`: 创意长度

#### character_generate
**功能**: 角色生成

**参数**:
- `type` (必需): 角色类型
- `genre`: 体裁
- `details`: 详细信息
- `count`: 生成数量

#### world_build
**功能**: 世界观构建

**参数**:
- `genre` (必需): 体裁
- `focus`: 重点
- `details`: 详细信息
- `format`: 输出格式 (structured/outline)

#### creative_problem_solve
**功能**: 创意问题解决

**参数**:
- `problem` (必需): 问题描述
- `constraints`: 约束条件
- `count`: 解决方案数量
- `approach`: 解决方法 (divergent/convergent)

#### innovation
**功能**: 创新方案生成

**参数**:
- `domain` (必需): 领域
- `challenge`: 挑战
- `count`: 方案数量
- `details`: 详细信息

#### campaign_create
**功能**: 创意营销活动

**参数**:
- `product` (必需): 产品/服务
- `target` (必需): 目标受众
- `goal`: 活动目标
- `budget`: 预算范围

#### brand_identity
**功能**: 品牌识别设计

**参数**:
- `brand` (必需): 品牌名称
- `values`: 品牌价值观
- `target`: 目标受众
- `style`: 设计风格

#### content_strategy
**功能**: 内容策略

**参数**:
- `goal` (必需): 目标
- `audience`: 目标受众
- `platforms`: 平台
- `budget`: 预算范围

#### social_media_strategy
**功能**: 社交媒体策略

**参数**:
- `platforms` (必需): 平台
- `audience`: 目标受众
- `goal`: 目标
- `budget`: 预算范围

#### marketing_ideas
**功能**: 营销创意

**参数**:
- `product` (必需): 产品/服务
- `target`: 目标受众
- `goal`: 目标
- `count`: 创意数量

#### product_ideas
**功能**: 产品创意

**参数**:
- `category` (必需): 产品类别
- `needs`: 用户需求
- `count`: 创意数量
- `constraints`: 约束条件

#### ux_design
**功能**: 用户体验设计

**参数**:
- `product` (必需): 产品/服务
- `audience`: 目标用户
- `goals`: 设计目标
- `format`: 输出格式 (wireframe/prototype)

#### ui_design
**功能**: 用户界面设计

**参数**:
- `product` (必需): 产品/服务
- `style`: 设计风格
- `platform`: 平台
- `components`: 需要设计的组件

#### graphic_design
**功能**: 平面设计

**参数**:
- `type` (必需): 设计类型
- `content` (必需): 设计内容
- `style`: 设计风格
- `format`: 输出格式

#### logo_design
**功能**: 标志设计

**参数**:
- `brand` (必需): 品牌名称
- `values`: 品牌价值观
- `style`: 设计风格
- `count`: 设计方案数量

#### packaging_design
**功能**: 包装设计

**参数**:
- `product` (必需): 产品
- `brand`: 品牌
- `style`: 设计风格
- `requirements`: 具体要求

#### interior_design
**功能**: 室内设计

**参数**:
- `space` (必需): 空间类型
- `style`: 设计风格
- `budget`: 预算范围
- `requirements`: 具体要求

#### fashion_design
**功能**: 服装设计

**参数**:
- `type` (必需): 服装类型
- `style`: 设计风格
- `target`: 目标受众
- `season`: 季节

#### creative_code
**功能**: 创意编程

**参数**:
- `concept` (必需): 创意概念
- `language` (必需): 编程语言
- `platform`: 平台
- `requirements`: 具体要求

---

## 工具使用技巧

### 高效工具组合

1. **代码审查流程**
   - code_quality_analyze → code_metrics_calculate → dependency_analyze
   - 生成完整的代码质量报告

2. **测试驱动开发**
   - code_generate → unit_test_generate → test_run → coverage_analyze
   - 完整的TDD工作流

3. **文档自动化**
   - api_doc_generate → readme_generate → changelog_generate
   - 项目文档一键生成

4. **性能优化**
   - performance_profile → memory_analyze → log_analyze
   - 定位和解决性能问题

5. **内容创作流程**
   - idea_generate → creative_write → content_edit → seo_optimize
   - 从创意到优化的完整内容生产

6. **数据分析流程**
   - data_import → data_clean → data_visualize → data_report_generate
   - 从数据导入到报告生成的完整分析

7. **设计流程**
   - design_ideas → graphic_design → logo_design → packaging_design
   - 从创意到具体设计的完整流程

8. **营销策划流程**
   - marketing_ideas → campaign_create → social_media_strategy → ad_copy
   - 从创意到执行的完整营销策划

### 并行执行

支持并行执行的工具:
- build_orchestrate
- code_generate
- unit_test_generate
- data_migrate
- task_decompose
- data_import
- data_clean
- data_visualize

### 危险操作

以下工具被标记为危险操作，需要谨慎使用:
- shell_execute: 执行Shell命令
- debugger_attach: 附加调试器
- data_migrate: 数据迁移
- document_encrypt: 文档加密
- business_card_scan: 名片识别

---

## 常见问题

### Q: 如何选择合适的工具?
A: 根据任务类型选择对应的工具类别，如需代码分析选择代码质量工具，需要测试选择测试工具。

### Q: 工具执行失败怎么办?
A: 检查参数是否正确，确认文件路径是否存在，查看错误信息进行调试。

### Q: 支持自定义工具吗?
A: 可以通过ToolDispatcher接口注册自定义工具。

### Q: 工具执行有超时限制吗?
A: 默认超时30秒，长时间任务可设置更长的timeoutMs参数。

---

© 2026 Apex Agent