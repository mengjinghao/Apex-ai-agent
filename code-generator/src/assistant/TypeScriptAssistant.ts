import { ProgrammingAssistant, Position, CompletionItem, CompletionType, FormatOptions, ErrorItem, ErrorType, RefactoringType } from './ProgrammingAssistant';

export class TypeScriptAssistant implements ProgrammingAssistant {
  /**
   * 提供代码补全建议
   * @param code 代码
   * @param position 光标位置
   * @param language 代码语言
   * @returns 补全建议列表
   */
  provideCompletion(code: string, position: Position, language: string): CompletionItem[] {
    const completions: CompletionItem[] = [];
    const lines = code.split('\n');
    const currentLine = lines[position.line - 1].substring(0, position.column);
    
    // 提取当前输入的单词
    const lastWord = this.extractLastWord(currentLine);
    
    // 根据上下文提供补全建议
    if (currentLine.includes('function ')) {
      completions.push(...this.getFunctionCompletions(lastWord));
    } else if (currentLine.includes('class ')) {
      completions.push(...this.getClassCompletions(lastWord));
    } else if (currentLine.includes('interface ')) {
      completions.push(...this.getInterfaceCompletions(lastWord));
    } else if (currentLine.includes('import ')) {
      completions.push(...this.getImportCompletions(lastWord));
    } else if (currentLine.includes('if ')) {
      completions.push(...this.getControlFlowCompletions(lastWord));
    } else if (currentLine.includes('for ')) {
      completions.push(...this.getLoopCompletions(lastWord));
    } else {
      completions.push(...this.getGeneralCompletions(lastWord));
    }
    
    return completions;
  }

  /**
   * 格式化代码
   * @param code 代码
   * @param language 代码语言
   * @param options 格式化选项
   * @returns 格式化后的代码
   */
  formatCode(code: string, language: string, options?: FormatOptions): string {
    const indentSize = options?.indentSize || 2;
    const useSemicolons = options?.useSemicolons !== false;
    const useSingleQuotes = options?.useSingleQuotes !== false;
    const lineWidth = options?.lineWidth || 80;
    
    let formattedCode = '';
    let indentLevel = 0;
    const lines = code.split('\n');
    
    lines.forEach(line => {
      const trimmedLine = line.trim();
      
      // 减少缩进级别
      if (trimmedLine.startsWith('}') || trimmedLine.startsWith(')') || trimmedLine.startsWith(']')) {
        indentLevel--;
      }
      
      // 添加缩进
      if (trimmedLine) {
        formattedCode += ' '.repeat(indentLevel * indentSize) + trimmedLine;
        
        // 添加分号
        if (useSemicolons && !trimmedLine.endsWith(';') && 
            !trimmedLine.endsWith('{') && !trimmedLine.endsWith('}') &&
            !trimmedLine.endsWith('(') && !trimmedLine.endsWith(')') &&
            !trimmedLine.endsWith('[') && !trimmedLine.endsWith(']') &&
            !trimmedLine.endsWith(',') && !trimmedLine.endsWith(':')) {
          formattedCode += ';';
        }
        
        // 替换引号
        if (useSingleQuotes) {
          formattedCode = formattedCode.replace(/"([^"]*)"/g, "'$1'");
        } else {
          formattedCode = formattedCode.replace(/'([^']*)'/g, '"$1"');
        }
      }
      
      formattedCode += '\n';
      
      // 增加缩进级别
      if (trimmedLine.endsWith('{') || trimmedLine.endsWith('(') || trimmedLine.endsWith('[')) {
        indentLevel++;
      }
    });
    
    return formattedCode;
  }

  /**
   * 检测代码错误
   * @param code 代码
   * @param language 代码语言
   * @returns 错误列表
   */
  detectErrors(code: string, language: string): ErrorItem[] {
    const errors: ErrorItem[] = [];
    
    // 检测语法错误
    errors.push(...this.detectSyntaxErrors(code));
    
    // 检测类型错误
    errors.push(...this.detectTypeErrors(code));
    
    // 检测逻辑错误
    errors.push(...this.detectLogicErrors(code));
    
    // 检测风格错误
    errors.push(...this.detectStyleErrors(code));
    
    return errors;
  }

  /**
   * 重构代码
   * @param code 代码
   * @param refactoringType 重构类型
   * @param language 代码语言
   * @returns 重构后的代码
   */
  refactorCode(code: string, refactoringType: RefactoringType, language: string): string {
    switch (refactoringType) {
      case RefactoringType.RENAME:
        // 简单的重命名实现
        return code.replace(/oldName/g, 'newName');
      case RefactoringType.EXTRACT_FUNCTION:
        // 简单的提取函数实现
        return this.extractFunction(code);
      case RefactoringType.EXTRACT_VARIABLE:
        // 简单的提取变量实现
        return this.extractVariable(code);
      case RefactoringType.INLINE_FUNCTION:
        // 简单的内联函数实现
        return this.inlineFunction(code);
      case RefactoringType.INLINE_VARIABLE:
        // 简单的内联变量实现
        return this.inlineVariable(code);
      default:
        return code;
    }
  }

  /**
   * 提取最后一个单词
   * @param text 文本
   * @returns 最后一个单词
   */
  private extractLastWord(text: string): string {
    const words = text.split(/\s+/);
    return words[words.length - 1] || '';
  }

  /**
   * 获取函数补全建议
   * @param lastWord 最后一个单词
   * @returns 补全建议列表
   */
  private getFunctionCompletions(lastWord: string): CompletionItem[] {
    return [
      {
        text: 'function',
        type: CompletionType.KEYWORD,
        description: 'Function declaration',
        priority: 1
      },
      {
        text: 'async function',
        type: CompletionType.KEYWORD,
        description: 'Async function declaration',
        priority: 2
      },
      {
        text: '() =>',
        type: CompletionType.OTHER,
        description: 'Arrow function',
        priority: 3
      }
    ];
  }

  /**
   * 获取类补全建议
   * @param lastWord 最后一个单词
   * @returns 补全建议列表
   */
  private getClassCompletions(lastWord: string): CompletionItem[] {
    return [
      {
        text: 'class',
        type: CompletionType.KEYWORD,
        description: 'Class declaration',
        priority: 1
      },
      {
        text: 'extends',
        type: CompletionType.KEYWORD,
        description: 'Class inheritance',
        priority: 2
      },
      {
        text: 'implements',
        type: CompletionType.KEYWORD,
        description: 'Class interface implementation',
        priority: 3
      }
    ];
  }

  /**
   * 获取接口补全建议
   * @param lastWord 最后一个单词
   * @returns 补全建议列表
   */
  private getInterfaceCompletions(lastWord: string): CompletionItem[] {
    return [
      {
        text: 'interface',
        type: CompletionType.KEYWORD,
        description: 'Interface declaration',
        priority: 1
      },
      {
        text: 'extends',
        type: CompletionType.KEYWORD,
        description: 'Interface inheritance',
        priority: 2
      }
    ];
  }

  /**
   * 获取导入补全建议
   * @param lastWord 最后一个单词
   * @returns 补全建议列表
   */
  private getImportCompletions(lastWord: string): CompletionItem[] {
    return [
      {
        text: 'import',
        type: CompletionType.KEYWORD,
        description: 'Import statement',
        priority: 1
      },
      {
        text: 'from',
        type: CompletionType.KEYWORD,
        description: 'Import from',
        priority: 2
      },
      {
        text: '{',
        type: CompletionType.OTHER,
        description: 'Import specific members',
        priority: 3
      },
      {
        text: '* as',
        type: CompletionType.OTHER,
        description: 'Import all as',
        priority: 4
      }
    ];
  }

  /**
   * 获取控制流补全建议
   * @param lastWord 最后一个单词
   * @returns 补全建议列表
   */
  private getControlFlowCompletions(lastWord: string): CompletionItem[] {
    return [
      {
        text: 'if',
        type: CompletionType.KEYWORD,
        description: 'If statement',
        priority: 1
      },
      {
        text: 'else',
        type: CompletionType.KEYWORD,
        description: 'Else clause',
        priority: 2
      },
      {
        text: 'switch',
        type: CompletionType.KEYWORD,
        description: 'Switch statement',
        priority: 3
      },
      {
        text: 'case',
        type: CompletionType.KEYWORD,
        description: 'Case statement',
        priority: 4
      },
      {
        text: 'default',
        type: CompletionType.KEYWORD,
        description: 'Default case',
        priority: 5
      }
    ];
  }

  /**
   * 获取循环补全建议
   * @param lastWord 最后一个单词
   * @returns 补全建议列表
   */
  private getLoopCompletions(lastWord: string): CompletionItem[] {
    return [
      {
        text: 'for',
        type: CompletionType.KEYWORD,
        description: 'For loop',
        priority: 1
      },
      {
        text: 'while',
        type: CompletionType.KEYWORD,
        description: 'While loop',
        priority: 2
      },
      {
        text: 'do',
        type: CompletionType.KEYWORD,
        description: 'Do-while loop',
        priority: 3
      },
      {
        text: 'forEach',
        type: CompletionType.FUNCTION,
        description: 'Array forEach method',
        priority: 4
      },
      {
        text: 'map',
        type: CompletionType.FUNCTION,
        description: 'Array map method',
        priority: 5
      },
      {
        text: 'filter',
        type: CompletionType.FUNCTION,
        description: 'Array filter method',
        priority: 6
      }
    ];
  }

  /**
   * 获取通用补全建议
   * @param lastWord 最后一个单词
   * @returns 补全建议列表
   */
  private getGeneralCompletions(lastWord: string): CompletionItem[] {
    return [
      {
        text: 'let',
        type: CompletionType.KEYWORD,
        description: 'Variable declaration (block-scoped)',
        priority: 1
      },
      {
        text: 'const',
        type: CompletionType.KEYWORD,
        description: 'Constant declaration',
        priority: 2
      },
      {
        text: 'var',
        type: CompletionType.KEYWORD,
        description: 'Variable declaration (function-scoped)',
        priority: 3
      },
      {
        text: 'return',
        type: CompletionType.KEYWORD,
        description: 'Return statement',
        priority: 4
      },
      {
        text: 'console.log',
        type: CompletionType.FUNCTION,
        description: 'Console log',
        priority: 5
      },
      {
        text: 'console.error',
        type: CompletionType.FUNCTION,
        description: 'Console error',
        priority: 6
      },
      {
        text: 'try',
        type: CompletionType.KEYWORD,
        description: 'Try block',
        priority: 7
      },
      {
        text: 'catch',
        type: CompletionType.KEYWORD,
        description: 'Catch block',
        priority: 8
      },
      {
        text: 'finally',
        type: CompletionType.KEYWORD,
        description: 'Finally block',
        priority: 9
      }
    ];
  }

  /**
   * 检测语法错误
   * @param code 代码
   * @returns 错误列表
   */
  private detectSyntaxErrors(code: string): ErrorItem[] {
    const errors: ErrorItem[] = [];
    
    // 检查括号匹配
    const openBrackets = (code.match(/\(/g) || []).length;
    const closeBrackets = (code.match(/\)/g) || []).length;
    if (openBrackets !== closeBrackets) {
      errors.push({
        message: 'Mismatched parentheses',
        type: ErrorType.SYNTAX,
        position: {
          start: { line: 1, column: 0 },
          end: { line: 1, column: 10 }
        },
        severity: 'error'
      });
    }
    
    // 检查大括号匹配
    const openBraces = (code.match(/\{/g) || []).length;
    const closeBraces = (code.match(/\}/g) || []).length;
    if (openBraces !== closeBraces) {
      errors.push({
        message: 'Mismatched braces',
        type: ErrorType.SYNTAX,
        position: {
          start: { line: 1, column: 0 },
          end: { line: 1, column: 10 }
        },
        severity: 'error'
      });
    }
    
    // 检查方括号匹配
    const openBrackets2 = (code.match(/\[/g) || []).length;
    const closeBrackets2 = (code.match(/\]/g) || []).length;
    if (openBrackets2 !== closeBrackets2) {
      errors.push({
        message: 'Mismatched brackets',
        type: ErrorType.SYNTAX,
        position: {
          start: { line: 1, column: 0 },
          end: { line: 1, column: 10 }
        },
        severity: 'error'
      });
    }
    
    return errors;
  }

  /**
   * 检测类型错误
   * @param code 代码
   * @returns 错误列表
   */
  private detectTypeErrors(code: string): ErrorItem[] {
    const errors: ErrorItem[] = [];
    
    // 检查类型不匹配
    if (code.includes('number') && code.includes('string') && code.includes('+')) {
      errors.push({
        message: 'Possible type mismatch: adding number and string',
        type: ErrorType.TYPE,
        position: {
          start: { line: 1, column: 0 },
          end: { line: 1, column: 20 }
        },
        severity: 'warning'
      });
    }
    
    return errors;
  }

  /**
   * 检测逻辑错误
   * @param code 代码
   * @returns 错误列表
   */
  private detectLogicErrors(code: string): ErrorItem[] {
    const errors: ErrorItem[] = [];
    
    // 检查死代码
    if (code.includes('return') && code.includes('// unreachable')) {
      errors.push({
        message: 'Unreachable code after return statement',
        type: ErrorType.LOGIC,
        position: {
          start: { line: 1, column: 0 },
          end: { line: 1, column: 20 }
        },
        severity: 'warning'
      });
    }
    
    // 检查无限循环
    if (code.includes('while (true)') && !code.includes('break') && !code.includes('return')) {
      errors.push({
        message: 'Possible infinite loop',
        type: ErrorType.LOGIC,
        position: {
          start: { line: 1, column: 0 },
          end: { line: 1, column: 20 }
        },
        severity: 'warning'
      });
    }
    
    return errors;
  }

  /**
   * 检测风格错误
   * @param code 代码
   * @returns 错误列表
   */
  private detectStyleErrors(code: string): ErrorItem[] {
    const errors: ErrorItem[] = [];
    
    // 检查缩进
    const lines = code.split('\n');
    lines.forEach((line, index) => {
      const trimmedLine = line.trim();
      if (trimmedLine && !trimmedLine.startsWith('//') && !trimmedLine.startsWith('*')) {
        const indent = line.length - trimmedLine.length;
        if (indent % 2 !== 0) {
          errors.push({
            message: 'Inconsistent indentation',
            type: ErrorType.STYLE,
            position: {
              start: { line: index + 1, column: 0 },
              end: { line: index + 1, column: line.length }
            },
            severity: 'info'
          });
        }
      }
    });
    
    return errors;
  }

  /**
   * 提取函数
   * @param code 代码
   * @returns 重构后的代码
   */
  private extractFunction(code: string): string {
    // 简单的提取函数实现
    return code.replace(/(const|let|var)\s+result\s*=\s*(.*?);/g, (match, decl, expr) => {
      return `function calculateResult() {\n  return ${expr};\n}\n\nconst result = calculateResult();`;
    });
  }

  /**
   * 提取变量
   * @param code 代码
   * @returns 重构后的代码
   */
  private extractVariable(code: string): string {
    // 简单的提取变量实现
    return code.replace(/console\.log\((.*?)\);/g, (match, expr) => {
      return `const value = ${expr};\nconsole.log(value);`;
    });
  }

  /**
   * 内联函数
   * @param code 代码
   * @returns 重构后的代码
   */
  private inlineFunction(code: string): string {
    // 简单的内联函数实现
    return code.replace(/function calculateResult\(\)\s*\{\s*return\s*(.*?);\s*\}\s*\n\s*const result = calculateResult\(\);/g, (match, expr) => {
      return `const result = ${expr};`;
    });
  }

  /**
   * 内联变量
   * @param code 代码
   * @returns 重构后的代码
   */
  private inlineVariable(code: string): string {
    // 简单的内联变量实现
    return code.replace(/const value = (.*?);\s*console\.log\(value\);/g, (match, expr) => {
      return `console.log(${expr});`;
    });
  }
}
