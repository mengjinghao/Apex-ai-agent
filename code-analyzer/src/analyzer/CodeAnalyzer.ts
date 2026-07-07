export interface CodeAnalyzer {
  /**
   * 分析代码的语法结构
   * @param code 要分析的代码
   * @returns 语法分析结果
   */
  analyzeSyntax(code: string): SyntaxAnalysisResult;

  /**
   * 识别代码的结构
   * @param code 要分析的代码
   * @returns 结构识别结果
   */
  identifyStructure(code: string): StructureIdentificationResult;

  /**
   * 理解代码的语义
   * @param code 要分析的代码
   * @returns 语义理解结果
   */
  understandSemantics(code: string): SemanticUnderstandingResult;

  /**
   * 获取支持的语言
   * @returns 支持的语言名称
   */
  getSupportedLanguage(): string;
}

/**
 * 语法分析结果接口
 */
export interface SyntaxAnalysisResult {
  /**
   * 是否有语法错误
   */
  hasErrors: boolean;
  
  /**
   * 语法错误列表
   */
  errors: SyntaxError[];
  
  /**
   * 语法树
   */
  syntaxTree: any;
}

/**
 * 语法错误接口
 */
export interface SyntaxError {
  /**
   * 错误消息
   */
  message: string;
  
  /**
   * 错误位置
   */
  position: {
    line: number;
    column: number;
  };
}

/**
 * 结构识别结果接口
 */
export interface StructureIdentificationResult {
  /**
   * 代码中的函数
   */
  functions: FunctionInfo[];
  
  /**
   * 代码中的类
   */
  classes: ClassInfo[];
  
  /**
   * 代码中的变量
   */
  variables: VariableInfo[];
  
  /**
   * 代码中的导入/包含语句
   */
  imports: ImportInfo[];
}

/**
 * 函数信息接口
 */
export interface FunctionInfo {
  /**
   * 函数名称
   */
  name: string;
  
  /**
   * 函数参数
   */
  parameters: ParameterInfo[];
  
  /**
   * 函数返回类型
   */
  returnType: string;
  
  /**
   * 函数位置
   */
  location: {
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
 * 参数信息接口
 */
export interface ParameterInfo {
  /**
   * 参数名称
   */
  name: string;
  
  /**
   * 参数类型
   */
  type: string;
  
  /**
   * 是否为可选参数
   */
  optional: boolean;
}

/**
 * 类信息接口
 */
export interface ClassInfo {
  /**
   * 类名称
   */
  name: string;
  
  /**
   * 父类名称
   */
  parentClass?: string;
  
  /**
   * 实现的接口
   */
  interfaces: string[];
  
  /**
   * 类的方法
   */
  methods: FunctionInfo[];
  
  /**
   * 类的属性
   */
  properties: VariableInfo[];
  
  /**
   * 类的位置
   */
  location: {
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
 * 变量信息接口
 */
export interface VariableInfo {
  /**
   * 变量名称
   */
  name: string;
  
  /**
   * 变量类型
   */
  type: string;
  
  /**
   * 变量初始值
   */
  initialValue?: any;
  
  /**
   * 变量作用域
   */
  scope: 'global' | 'local' | 'class';
  
  /**
   * 变量位置
   */
  location: {
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
 * 导入信息接口
 */
export interface ImportInfo {
  /**
   * 导入的模块
   */
  module: string;
  
  /**
   * 导入的内容
   */
  imports: string[];
  
  /**
   * 导入位置
   */
  location: {
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
 * 语义理解结果接口
 */
export interface SemanticUnderstandingResult {
  /**
   * 代码的主要功能
   */
  mainFunctionality: string;
  
  /**
   * 代码的依赖关系
   */
  dependencies: string[];
  
  /**
   * 代码的执行流程
   */
  executionFlow: ExecutionStep[];
  
  /**
   * 代码的复杂度分析
   */
  complexity: {
    cyclomatic: number;
    cognitive: number;
  };
}

/**
 * 执行步骤接口
 */
export interface ExecutionStep {
  /**
   * 步骤描述
   */
  description: string;
  
  /**
   * 步骤类型
   */
  type: 'function_call' | 'assignment' | 'condition' | 'loop' | 'return' | 'other';
  
  /**
   * 步骤位置
   */
  location: {
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
