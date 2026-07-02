import { CodeGenerator, GenerateOptions, GeneratedCode, CodeType } from './generator/CodeGenerator';
import { CodeGeneratorFactory } from './generator/CodeGeneratorFactory';
import { CodeOptimizer, OptimizationResult } from './optimizer/CodeOptimizer';
import { TypeScriptOptimizer } from './optimizer/TypeScriptOptimizer';
import { ProgrammingAssistant, CompletionItem, ErrorItem, RefactoringType } from './assistant/ProgrammingAssistant';
import { TypeScriptAssistant } from './assistant/TypeScriptAssistant';

/**
 * 代码生成系统
 */
export class CodeGenerationSystem {
  private generator: CodeGenerator;
  private optimizer: CodeOptimizer;
  private assistant: ProgrammingAssistant;

  /**
   * 构造函数
   * @param language 代码语言
   */
  constructor(language: string = 'typescript') {
    this.generator = CodeGeneratorFactory.createGenerator(language);
    this.optimizer = new TypeScriptOptimizer();
    this.assistant = new TypeScriptAssistant();
  }

  /**
   * 根据用户需求生成代码
   * @param requirements 用户需求描述
   * @param options 生成选项
   * @returns 生成的代码
   */
  generateCode(requirements: string, options?: GenerateOptions): GeneratedCode {
    return this.generator.generateCode(requirements, options);
  }

  /**
   * 生成特定类型的代码
   * @param type 代码类型
   * @param parameters 生成参数
   * @returns 生成的代码
   */
  generateSpecificCode(type: CodeType, parameters: any): GeneratedCode {
    return this.generator.generateSpecificCode(type, parameters);
  }

  /**
   * 分析代码并提供优化建议
   * @param code 要分析的代码
   * @returns 优化建议
   */
  analyzeAndOptimize(code: string): OptimizationResult {
    return this.optimizer.analyzeAndOptimize(code, this.generator.getSupportedLanguage());
  }

  /**
   * 应用优化建议到代码
   * @param code 原始代码
   * @param suggestions 优化建议
   * @returns 优化后的代码
   */
  applyOptimizations(code: string, suggestions: any[]): string {
    return this.optimizer.applyOptimizations(code, suggestions);
  }

  /**
   * 提供代码补全建议
   * @param code 代码
   * @param position 光标位置
   * @returns 补全建议列表
   */
  provideCompletion(code: string, position: { line: number; column: number }): CompletionItem[] {
    return this.assistant.provideCompletion(code, position, this.generator.getSupportedLanguage());
  }

  /**
   * 格式化代码
   * @param code 代码
   * @param options 格式化选项
   * @returns 格式化后的代码
   */
  formatCode(code: string, options?: any): string {
    return this.assistant.formatCode(code, this.generator.getSupportedLanguage(), options);
  }

  /**
   * 检测代码错误
   * @param code 代码
   * @returns 错误列表
   */
  detectErrors(code: string): ErrorItem[] {
    return this.assistant.detectErrors(code, this.generator.getSupportedLanguage());
  }

  /**
   * 重构代码
   * @param code 代码
   * @param refactoringType 重构类型
   * @returns 重构后的代码
   */
  refactorCode(code: string, refactoringType: RefactoringType): string {
    return this.assistant.refactorCode(code, refactoringType, this.generator.getSupportedLanguage());
  }

  /**
   * 获取支持的语言列表
   * @returns 支持的语言列表
   */
  static getSupportedLanguages(): string[] {
    return CodeGeneratorFactory.getSupportedLanguages();
  }
}

// 导出核心功能
export {
  CodeGenerator,
  GenerateOptions,
  GeneratedCode,
  CodeType,
  CodeGeneratorFactory,
  CodeOptimizer,
  OptimizationResult,
  ProgrammingAssistant,
  CompletionItem,
  ErrorItem,
  RefactoringType
};
