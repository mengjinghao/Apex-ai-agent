export interface CodeOptimizer {
  /**
   * 分析代码并提供优化建议
   * @param code 要分析的代码
   * @param language 代码语言
   * @returns 优化建议
   */
  analyzeAndOptimize(code: string, language: string): OptimizationResult;

  /**
   * 应用优化建议到代码
   * @param code 原始代码
   * @param suggestions 优化建议
   * @returns 优化后的代码
   */
  applyOptimizations(code: string, suggestions: OptimizationSuggestion[]): string;
}

/**
 * 优化结果接口
 */
export interface OptimizationResult {
  /**
   * 优化建议列表
   */
  suggestions: OptimizationSuggestion[];
  /**
   * 优化前后的代码复杂度对比
   */
  complexityComparison?: {
    before: {
      cyclomatic: number;
      cognitive: number;
    };
    after: {
      cyclomatic: number;
      cognitive: number;
    };
  };
  /**
   * 优化前后的代码长度对比
   */
  lengthComparison?: {
    before: number;
    after: number;
  };
}

/**
 * 优化建议接口
 */
export interface OptimizationSuggestion {
  /**
   * 建议类型
   */
  type: OptimizationType;
  /**
   * 建议描述
   */
  description: string;
  /**
   * 建议的修改
   */
  suggestedChange?: string;
  /**
   * 建议的优先级
   */
  priority: 'high' | 'medium' | 'low';
  /**
   * 建议的位置
   */
  location?: {
    start: {
      line: number;
      column: number;
    };
    end: {
      line: number;
      column: number;
    };
  };
}

/**
 * 优化类型枚举
 */
export enum OptimizationType {
  /**
   * 性能优化
   */
  PERFORMANCE = 'performance',
  /**
   * 代码风格
   */
  CODE_STYLE = 'code_style',
  /**
   * 安全性
   */
  SECURITY = 'security',
  /**
   * 可读性
   */
  READABILITY = 'readability',
  /**
   * 维护性
   */
  MAINTAINABILITY = 'maintainability',
  /**
   * 其他
   */
  OTHER = 'other'
}
