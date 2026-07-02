export interface ProgrammingAssistant {
  /**
   * 提供代码补全建议
   * @param code 代码
   * @param position 光标位置
   * @param language 代码语言
   * @returns 补全建议列表
   */
  provideCompletion(code: string, position: Position, language: string): CompletionItem[];

  /**
   * 格式化代码
   * @param code 代码
   * @param language 代码语言
   * @param options 格式化选项
   * @returns 格式化后的代码
   */
  formatCode(code: string, language: string, options?: FormatOptions): string;

  /**
   * 检测代码错误
   * @param code 代码
   * @param language 代码语言
   * @returns 错误列表
   */
  detectErrors(code: string, language: string): ErrorItem[];

  /**
   * 重构代码
   * @param code 代码
   * @param refactoringType 重构类型
   * @param language 代码语言
   * @returns 重构后的代码
   */
  refactorCode(code: string, refactoringType: RefactoringType, language: string): string;
}

/**
 * 位置接口
 */
export interface Position {
  /**
   * 行号
   */
  line: number;
  /**
   * 列号
   */
  column: number;
}

/**
 * 补全项接口
 */
export interface CompletionItem {
  /**
   * 补全文本
   */
  text: string;
  /**
   * 补全类型
   */
  type: CompletionType;
  /**
   * 补全描述
   */
  description?: string;
  /**
   * 补全详细信息
   */
  detail?: string;
  /**
   * 补全排序优先级
   */
  priority?: number;
}

/**
 * 补全类型枚举
 */
export enum CompletionType {
  /**
   * 关键字
   */
  KEYWORD = 'keyword',
  /**
   * 变量
   */
  VARIABLE = 'variable',
  /**
   * 函数
   */
  FUNCTION = 'function',
  /**
   * 类
   */
  CLASS = 'class',
  /**
   * 接口
   */
  INTERFACE = 'interface',
  /**
   * 模块
   */
  MODULE = 'module',
  /**
   * 其他
   */
  OTHER = 'other'
}

/**
 * 格式化选项接口
 */
export interface FormatOptions {
  /**
   * 缩进空格数
   */
  indentSize?: number;
  /**
   * 是否使用分号
   */
  useSemicolons?: boolean;
  /**
   * 是否使用单引号
   */
  useSingleQuotes?: boolean;
  /**
   * 是否换行
   */
  lineWidth?: number;
  /**
   * 其他选项
   */
  [key: string]: any;
}

/**
 * 错误项接口
 */
export interface ErrorItem {
  /**
   * 错误消息
   */
  message: string;
  /**
   * 错误类型
   */
  type: ErrorType;
  /**
   * 错误位置
   */
  position: {
    start: Position;
    end: Position;
  };
  /**
   * 错误严重程度
   */
  severity: 'error' | 'warning' | 'info';
  /**
   * 修复建议
   */
  fix?: string;
}

/**
 * 错误类型枚举
 */
export enum ErrorType {
  /**
   * 语法错误
   */
  SYNTAX = 'syntax',
  /**
   * 类型错误
   */
  TYPE = 'type',
  /**
   * 逻辑错误
   */
  LOGIC = 'logic',
  /**
   * 风格错误
   */
  STYLE = 'style',
  /**
   * 其他错误
   */
  OTHER = 'other'
}

/**
 * 重构类型枚举
 */
export enum RefactoringType {
  /**
   * 重命名
   */
  RENAME = 'rename',
  /**
   * 提取函数
   */
  EXTRACT_FUNCTION = 'extract_function',
  /**
   * 提取变量
   */
  EXTRACT_VARIABLE = 'extract_variable',
  /**
   * 内联函数
   */
  INLINE_FUNCTION = 'inline_function',
  /**
   * 内联变量
   */
  INLINE_VARIABLE = 'inline_variable',
  /**
   * 其他重构
   */
  OTHER = 'other'
}
