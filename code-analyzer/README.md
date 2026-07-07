# 代码分析系统

一个用于分析不同编程语言代码的系统，包括代码语法分析、代码结构识别和代码语义理解。

## 功能特性

- **语法分析**：分析代码的语法结构，检测语法错误
- **结构识别**：识别代码中的函数、类、变量和导入语句
- **语义理解**：理解代码的主要功能、依赖关系和执行流程
- **多语言支持**：支持 TypeScript 和 JavaScript 代码分析

## 项目结构

```
code-analyzer/
├── src/
│   ├── analyzer/
│   │   ├── CodeAnalyzer.ts         # 代码分析器接口
│   │   ├── TypeScriptAnalyzer.ts    # TypeScript 分析器实现
│   │   ├── JavaScriptAnalyzer.ts    # JavaScript 分析器实现
│   │   └── CodeAnalyzerFactory.ts   # 分析器工厂类
│   └── index.ts                     # 主入口文件
├── example.ts                       # 示例文件
├── package.json                     # 项目配置
├── tsconfig.json                    # TypeScript 配置
└── README.md                        # 项目说明
```

## 安装和使用

### 安装依赖

```bash
npm install
```

### 构建项目

```bash
npm run build
```

### 运行示例

```bash
node dist/index.js
```

## 使用方法

### 基本用法

```typescript
import { CodeAnalyzerFactory } from './src/index';

// 创建 TypeScript 分析器
const tsAnalyzer = CodeAnalyzerFactory.createAnalyzer('typescript');

// 分析 TypeScript 代码
const tsCode = `
class Example {
  private value: number;
  
  constructor(value: number) {
    this.value = value;
  }
  
  getValue(): number {
    return this.value;
  }
}
`;

// 分析语法
const syntaxResult = tsAnalyzer.analyzeSyntax(tsCode);
console.log('语法分析结果:', syntaxResult);

// 识别结构
const structureResult = tsAnalyzer.identifyStructure(tsCode);
console.log('结构识别结果:', structureResult);

// 理解语义
const semanticsResult = tsAnalyzer.understandSemantics(tsCode);
console.log('语义理解结果:', semanticsResult);
```

### 支持的语言

- TypeScript (ts)
- JavaScript (js)

## 扩展系统

要添加对新语言的支持，需要：

1. 创建一个实现 `CodeAnalyzer` 接口的新分析器类
2. 在 `CodeAnalyzerFactory` 中添加对新语言的支持

## 注意事项

- 本系统目前使用正则表达式进行代码分析，适用于简单的代码结构
- 对于复杂的代码，建议使用专门的解析器（如 TypeScript 编译器 API）进行更准确的分析
- 本系统主要用于代码分析，为后续的代码生成功能做准备
