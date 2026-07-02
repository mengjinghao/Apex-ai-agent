import { CodeGenerator } from './CodeGenerator';
import { TypeScriptGenerator } from './TypeScriptGenerator';

export class CodeGeneratorFactory {
  /**
   * 创建代码生成器
   * @param language 语言类型
   * @returns 代码生成器实例
   */
  static createGenerator(language: string): CodeGenerator {
    switch (language.toLowerCase()) {
      case 'typescript':
      case 'ts':
        return new TypeScriptGenerator();
      case 'javascript':
      case 'js':
        // 未来可以实现 JavaScript 生成器
        return new TypeScriptGenerator();
      default:
        throw new Error(`Unsupported language: ${language}`);
    }
  }

  /**
   * 获取支持的语言列表
   * @returns 支持的语言列表
   */
  static getSupportedLanguages(): string[] {
    return ['TypeScript', 'JavaScript'];
  }
}
