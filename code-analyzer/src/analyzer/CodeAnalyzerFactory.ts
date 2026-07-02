import { CodeAnalyzer } from './CodeAnalyzer';
import { TypeScriptAnalyzer } from './TypeScriptAnalyzer';
import { JavaScriptAnalyzer } from './JavaScriptAnalyzer';

/**
 * 代码分析器工厂类
 * 用于根据编程语言类型创建相应的分析器实例
 */
export class CodeAnalyzerFactory {
  /**
   * 根据语言类型创建分析器实例
   * @param language 编程语言类型
   * @returns 对应语言的分析器实例
   * @throws 当语言不支持时抛出错误
   */
  static createAnalyzer(language: string): CodeAnalyzer {
    switch (language.toLowerCase()) {
      case 'typescript':
      case 'ts':
        return new TypeScriptAnalyzer();
      case 'javascript':
      case 'js':
        return new JavaScriptAnalyzer();
      default:
        throw new Error(`Unsupported language: ${language}`);
    }
  }

  /**
   * 获取所有支持的语言
   * @returns 支持的语言列表
   */
  static getSupportedLanguages(): string[] {
    return ['TypeScript', 'JavaScript'];
  }
}
