export interface CodeGenerator {
  /**
   * 根据用户需求生成代码
   * @param requirements 用户需求描述
   * @param options 生成选项
   * @returns 生成的代码
   */
  generateCode(requirements: string, options?: GenerateOptions): GeneratedCode;

  /**
   * 生成特定类型的代码
   * @param type 代码类型
   * @param parameters 生成参数
   * @returns 生成的代码
   */
  generateSpecificCode(type: CodeType, parameters: any): GeneratedCode;

  /**
   * 获取支持的语言
   * @returns 支持的语言名称
   */
  getSupportedLanguage(): string;
}

/**
 * 代码生成选项接口
 */
export interface GenerateOptions {
  /**
   * 代码风格
   */
  style?: 'functional' | 'object-oriented' | 'procedural';
  /**
   * 是否添加注释
   */
  addComments?: boolean;
  /**
   * 代码缩进空格数
   */
  indentSize?: number;
  /**
   * 是否使用分号
   */
  useSemicolons?: boolean;
  /**
   * 其他选项
   */
  [key: string]: any;
}

/**
 * 生成的代码接口
 */
export interface GeneratedCode {
  /**
   * 生成的代码内容
   */
  code: string;
  /**
   * 代码类型
   */
  type: CodeType;
  /**
   * 生成时间
   */
  generatedAt: Date;
  /**
   * 代码说明
   */
  description?: string;
  /**
   * 依赖项
   */
  dependencies?: string[];
}

/**
 * 代码类型枚举
 */
export enum CodeType {
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
   * 脚本
   */
  SCRIPT = 'script',
  /**
   * 其他
   */
  OTHER = 'other'
}
