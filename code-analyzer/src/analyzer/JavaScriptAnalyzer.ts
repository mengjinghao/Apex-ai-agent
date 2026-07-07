import { CodeAnalyzer, SyntaxAnalysisResult, StructureIdentificationResult, SemanticUnderstandingResult } from './CodeAnalyzer';

export class JavaScriptAnalyzer implements CodeAnalyzer {
  /**
   * 分析 JavaScript 代码的语法结构
   * @param code 要分析的 JavaScript 代码
   * @returns 语法分析结果
   */
  analyzeSyntax(code: string): SyntaxAnalysisResult {
    // 简单的语法分析实现
    const errors: any[] = [];
    
    // 检查基本语法错误
    if (code.includes('function') && !code.includes('{') && !code.includes('}')) {
      errors.push({
        message: 'Function missing body',
        position: {
          line: code.indexOf('function') > -1 ? code.substring(0, code.indexOf('function')).split('\n').length : 1,
          column: 0
        }
      });
    }
    
    return {
      hasErrors: errors.length > 0,
      errors,
      syntaxTree: this.parseSyntaxTree(code)
    };
  }

  /**
   * 识别 JavaScript 代码的结构
   * @param code 要分析的 JavaScript 代码
   * @returns 结构识别结果
   */
  identifyStructure(code: string): StructureIdentificationResult {
    return {
      functions: this.extractFunctions(code),
      classes: this.extractClasses(code),
      variables: this.extractVariables(code),
      imports: this.extractImports(code)
    };
  }

  /**
   * 理解 JavaScript 代码的语义
   * @param code 要分析的 JavaScript 代码
   * @returns 语义理解结果
   */
  understandSemantics(code: string): SemanticUnderstandingResult {
    return {
      mainFunctionality: this.analyzeMainFunctionality(code),
      dependencies: this.analyzeDependencies(code),
      executionFlow: this.analyzeExecutionFlow(code),
      complexity: this.analyzeComplexity(code)
    };
  }

  /**
   * 获取支持的语言
   * @returns 支持的语言名称
   */
  getSupportedLanguage(): string {
    return 'JavaScript';
  }

  /**
   * 解析语法树
   * @param code 代码
   * @returns 语法树
   */
  private parseSyntaxTree(code: string): any {
    // 简化的语法树解析
    return {
      type: 'Program',
      body: code.split('\n').map((line, index) => ({
        type: 'Line',
        lineNumber: index + 1,
        content: line.trim()
      }))
    };
  }

  /**
   * 提取函数
   * @param code 代码
   * @returns 函数信息列表
   */
  private extractFunctions(code: string): any[] {
    const functions: any[] = [];
    
    // 函数声明
    const functionDeclarationRegex = /function\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*\(([^)]*)\)\s*\{/g;
    let match;
    
    while ((match = functionDeclarationRegex.exec(code)) !== null) {
      const startLine = code.substring(0, match.index).split('\n').length;
      functions.push({
        name: match[1],
        parameters: this.parseParameters(match[2]),
        returnType: 'any', // JavaScript 没有显式返回类型
        location: {
          start: {
            line: startLine,
            column: code.substring(0, match.index).split('\n').pop()?.length || 0
          },
          end: {
            line: startLine,
            column: match.index + match[0].length
          }
        }
      });
    }
    
    // 函数表达式
    const functionExpressionRegex = /const\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*function\s*(?:\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*)?\(([^)]*)\)\s*\{/g;
    
    while ((match = functionExpressionRegex.exec(code)) !== null) {
      const startLine = code.substring(0, match.index).split('\n').length;
      functions.push({
        name: match[1] || match[2] || 'anonymous',
        parameters: this.parseParameters(match[3]),
        returnType: 'any',
        location: {
          start: {
            line: startLine,
            column: code.substring(0, match.index).split('\n').pop()?.length || 0
          },
          end: {
            line: startLine,
            column: match.index + match[0].length
          }
        }
      });
    }
    
    // 箭头函数
    const arrowFunctionRegex = /const\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*\(([^)]*)\)\s*=>/g;
    
    while ((match = arrowFunctionRegex.exec(code)) !== null) {
      const startLine = code.substring(0, match.index).split('\n').length;
      functions.push({
        name: match[1],
        parameters: this.parseParameters(match[2]),
        returnType: 'any',
        location: {
          start: {
            line: startLine,
            column: code.substring(0, match.index).split('\n').pop()?.length || 0
          },
          end: {
            line: startLine,
            column: match.index + match[0].length
          }
        }
      });
    }
    
    return functions;
  }

  /**
   * 解析参数
   * @param paramsStr 参数字符串
   * @returns 参数信息列表
   */
  private parseParameters(paramsStr: string): any[] {
    return paramsStr.split(',').map(param => {
      const trimmedParam = param.trim();
      return {
        name: trimmedParam,
        type: 'any', // JavaScript 没有类型注解
        optional: trimmedParam.endsWith('?')
      };
    }).filter(param => param.name);
  }

  /**
   * 提取类
   * @param code 代码
   * @returns 类信息列表
   */
  private extractClasses(code: string): any[] {
    const classes: any[] = [];
    const classRegex = /class\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*(?:extends\s+([a-zA-Z_$][a-zA-Z0-9_$]*))?\s*\{/g;
    let match;
    
    while ((match = classRegex.exec(code)) !== null) {
      const startLine = code.substring(0, match.index).split('\n').length;
      classes.push({
        name: match[1],
        parentClass: match[2],
        interfaces: [], // JavaScript 没有接口
        methods: [],
        properties: [],
        location: {
          start: {
            line: startLine,
            column: code.substring(0, match.index).split('\n').pop()?.length || 0
          },
          end: {
            line: startLine,
            column: match.index + match[0].length
          }
        }
      });
    }
    
    return classes;
  }

  /**
   * 提取变量
   * @param code 代码
   * @returns 变量信息列表
   */
  private extractVariables(code: string): any[] {
    const variables: any[] = [];
    const variableRegex = /(var|let|const)\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*(?:=\s*([^;]+))?\s*;/g;
    let match;
    
    while ((match = variableRegex.exec(code)) !== null) {
      const startLine = code.substring(0, match.index).split('\n').length;
      variables.push({
        name: match[2],
        type: 'any', // JavaScript 没有类型注解
        initialValue: match[3]?.trim(),
        scope: match[1] === 'var' ? 'global' : 'local',
        location: {
          start: {
            line: startLine,
            column: code.substring(0, match.index).split('\n').pop()?.length || 0
          },
          end: {
            line: startLine,
            column: match.index + match[0].length
          }
        }
      });
    }
    
    return variables;
  }

  /**
   * 提取导入语句
   * @param code 代码
   * @returns 导入信息列表
   */
  private extractImports(code: string): any[] {
    const imports: any[] = [];
    const importRegex = /import\s+(?:\{([^}]+)\}|\*\s+as\s+([a-zA-Z_$][a-zA-Z0-9_$]*)|([a-zA-Z_$][a-zA-Z0-9_$]*))?\s+from\s+['"]([^'"]+)['"]/g;
    let match;
    
    while ((match = importRegex.exec(code)) !== null) {
      const startLine = code.substring(0, match.index).split('\n').length;
      let importItems: string[] = [];
      
      if (match[1]) {
        importItems = match[1].split(',').map(item => item.trim());
      } else if (match[2]) {
        importItems = [`* as ${match[2]}`];
      } else if (match[3]) {
        importItems = [match[3]];
      }
      
      imports.push({
        module: match[4],
        imports: importItems,
        location: {
          start: {
            line: startLine,
            column: code.substring(0, match.index).split('\n').pop()?.length || 0
          },
          end: {
            line: startLine,
            column: match.index + match[0].length
          }
        }
      });
    }
    
    return imports;
  }

  /**
   * 分析主要功能
   * @param code 代码
   * @returns 主要功能描述
   */
  private analyzeMainFunctionality(code: string): string {
    // 简化的功能分析
    if (code.includes('class')) {
      return 'Object-oriented code with classes';
    } else if (code.includes('function')) {
      return 'Functional code with functions';
    } else {
      return 'Simple script code';
    }
  }

  /**
   * 分析依赖关系
   * @param code 代码
   * @returns 依赖关系列表
   */
  private analyzeDependencies(code: string): string[] {
    const dependencies: string[] = [];
    const importRegex = /from\s+['"]([^'"]+)['"]/g;
    let match;
    
    while ((match = importRegex.exec(code)) !== null) {
      dependencies.push(match[1]);
    }
    
    // 检查 require 语句
    const requireRegex = /require\(['"]([^'"]+)['"]\)/g;
    
    while ((match = requireRegex.exec(code)) !== null) {
      dependencies.push(match[1]);
    }
    
    return dependencies;
  }

  /**
   * 分析执行流程
   * @param code 代码
   * @returns 执行步骤列表
   */
  private analyzeExecutionFlow(code: string): any[] {
    const steps: any[] = [];
    const lines = code.split('\n');
    
    lines.forEach((line, index) => {
      const trimmedLine = line.trim();
      
      if (trimmedLine.startsWith('if')) {
        steps.push({
          description: trimmedLine,
          type: 'condition',
          location: {
            start: {
              line: index + 1,
              column: line.indexOf(trimmedLine)
            },
            end: {
              line: index + 1,
              column: line.indexOf(trimmedLine) + trimmedLine.length
            }
          }
        });
      } else if (trimmedLine.startsWith('for') || trimmedLine.startsWith('while')) {
        steps.push({
          description: trimmedLine,
          type: 'loop',
          location: {
            start: {
              line: index + 1,
              column: line.indexOf(trimmedLine)
            },
            end: {
              line: index + 1,
              column: line.indexOf(trimmedLine) + trimmedLine.length
            }
          }
        });
      } else if (trimmedLine.includes('=')) {
        steps.push({
          description: trimmedLine,
          type: 'assignment',
          location: {
            start: {
              line: index + 1,
              column: line.indexOf(trimmedLine)
            },
            end: {
              line: index + 1,
              column: line.indexOf(trimmedLine) + trimmedLine.length
            }
          }
        });
      } else if (trimmedLine.startsWith('return')) {
        steps.push({
          description: trimmedLine,
          type: 'return',
          location: {
            start: {
              line: index + 1,
              column: line.indexOf(trimmedLine)
            },
            end: {
              line: index + 1,
              column: line.indexOf(trimmedLine) + trimmedLine.length
            }
          }
        });
      } else if (trimmedLine.includes('(') && trimmedLine.includes(')')) {
        steps.push({
          description: trimmedLine,
          type: 'function_call',
          location: {
            start: {
              line: index + 1,
              column: line.indexOf(trimmedLine)
            },
            end: {
              line: index + 1,
              column: line.indexOf(trimmedLine) + trimmedLine.length
            }
          }
        });
      }
    });
    
    return steps;
  }

  /**
   * 分析复杂度
   * @param code 代码
   * @returns 复杂度分析结果
   */
  private analyzeComplexity(code: string): { cyclomatic: number; cognitive: number } {
    // 简化的复杂度分析
    const cyclomatic = (code.match(/if|for|while|switch|case|&&|\|\|/g) || []).length + 1;
    const cognitive = code.split('\n').length;
    
    return {
      cyclomatic,
      cognitive
    };
  }
}
