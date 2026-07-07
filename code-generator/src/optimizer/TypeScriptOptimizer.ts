import { CodeOptimizer, OptimizationResult, OptimizationSuggestion, OptimizationType } from './CodeOptimizer';

export class TypeScriptOptimizer implements CodeOptimizer {
  /**
   * 分析代码并提供优化建议
   * @param code 要分析的代码
   * @param language 代码语言
   * @returns 优化建议
   */
  analyzeAndOptimize(code: string, language: string): OptimizationResult {
    const suggestions: OptimizationSuggestion[] = [];
    
    // 分析代码长度
    const originalLength = code.length;
    
    // 分析代码复杂度
    const originalComplexity = this.analyzeComplexity(code);
    
    // 检查性能优化机会
    suggestions.push(...this.checkPerformanceOptimizations(code));
    
    // 检查代码风格问题
    suggestions.push(...this.checkCodeStyle(code));
    
    // 检查安全性问题
    suggestions.push(...this.checkSecurityIssues(code));
    
    // 检查可读性问题
    suggestions.push(...this.checkReadability(code));
    
    // 检查维护性问题
    suggestions.push(...this.checkMaintainability(code));
    
    // 计算优化后的代码复杂度和长度
    const optimizedCode = this.applyOptimizations(code, suggestions);
    const optimizedLength = optimizedCode.length;
    const optimizedComplexity = this.analyzeComplexity(optimizedCode);
    
    return {
      suggestions,
      complexityComparison: {
        before: originalComplexity,
        after: optimizedComplexity
      },
      lengthComparison: {
        before: originalLength,
        after: optimizedLength
      }
    };
  }

  /**
   * 应用优化建议到代码
   * @param code 原始代码
   * @param suggestions 优化建议
   * @returns 优化后的代码
   */
  applyOptimizations(code: string, suggestions: OptimizationSuggestion[]): string {
    let optimizedCode = code;
    
    // 按优先级排序建议
    const sortedSuggestions = [...suggestions].sort((a, b) => {
      const priorityOrder = { high: 0, medium: 1, low: 2 };
      return priorityOrder[a.priority] - priorityOrder[b.priority];
    });
    
    // 应用建议
    sortedSuggestions.forEach(suggestion => {
      if (suggestion.suggestedChange && suggestion.location) {
        const lines = optimizedCode.split('\n');
        const startLine = suggestion.location.start.line - 1;
        const endLine = suggestion.location.end.line - 1;
        
        if (startLine === endLine) {
          // 单行修改
          const line = lines[startLine];
          const newLine = line.substring(0, suggestion.location.start.column) + 
                        suggestion.suggestedChange + 
                        line.substring(suggestion.location.end.column);
          lines[startLine] = newLine;
        } else {
          // 多行修改
          const newLines = suggestion.suggestedChange.split('\n');
          lines.splice(startLine, endLine - startLine + 1, ...newLines);
        }
        
        optimizedCode = lines.join('\n');
      }
    });
    
    return optimizedCode;
  }

  /**
   * 分析代码复杂度
   * @param code 代码
   * @returns 复杂度分析结果
   */
  private analyzeComplexity(code: string): { cyclomatic: number; cognitive: number } {
    // 简化的复杂度分析
    const cyclomatic = (code.match(/if|for|while|switch|case|&&|\|\|/g) || []).length + 1;
    const cognitive = code.split('\n').length;
    
    return { cyclomatic, cognitive };
  }

  /**
   * 检查性能优化机会
   * @param code 代码
   * @returns 优化建议列表
   */
  private checkPerformanceOptimizations(code: string): OptimizationSuggestion[] {
    const suggestions: OptimizationSuggestion[] = [];
    
    // 检查重复计算
    if (code.includes('for (let i = 0; i < arr.length; i++')) {
      suggestions.push({
        type: OptimizationType.PERFORMANCE,
        description: '避免在循环中重复计算数组长度',
        suggestedChange: 'const length = arr.length;\nfor (let i = 0; i < length; i++)',
        priority: 'medium',
        location: {
          start: { line: 1, column: 0 },
          end: { line: 1, column: code.indexOf('for (let i = 0; i < arr.length; i++') + 'for (let i = 0; i < arr.length; i++'.length }
        }
      });
    }
    
    // 检查不必要的类型转换
    if (code.includes('Number(') || code.includes('String(') || code.includes('Boolean(')) {
      suggestions.push({
        type: OptimizationType.PERFORMANCE,
        description: '考虑使用更高效的类型转换方式',
        suggestedChange: code.includes('Number(') ? '+' : code.includes('String(') ? '"" +' : '!!',
        priority: 'low',
        location: {
          start: { line: 1, column: 0 },
          end: { line: 1, column: 10 }
        }
      });
    }
    
    return suggestions;
  }

  /**
   * 检查代码风格问题
   * @param code 代码
   * @returns 优化建议列表
   */
  private checkCodeStyle(code: string): OptimizationSuggestion[] {
    const suggestions: OptimizationSuggestion[] = [];
    
    // 检查缩进
    const lines = code.split('\n');
    lines.forEach((line, index) => {
      const trimmedLine = line.trim();
      if (trimmedLine && !trimmedLine.startsWith('//') && !trimmedLine.startsWith('*')) {
        const indent = line.length - trimmedLine.length;
        if (indent % 2 !== 0) {
          suggestions.push({
            type: OptimizationType.CODE_STYLE,
            description: '使用一致的缩进（建议使用 2 空格）',
            suggestedChange: ' '.repeat(Math.floor(indent / 2) * 2) + trimmedLine,
            priority: 'low',
            location: {
              start: { line: index + 1, column: 0 },
              end: { line: index + 1, column: line.length }
            }
          });
        }
      }
    });
    
    // 检查分号
    if (!code.includes(';')) {
      suggestions.push({
        type: OptimizationType.CODE_STYLE,
        description: '考虑使用分号提高代码可读性',
        priority: 'low'
      });
    }
    
    // 检查大括号风格
    if (code.includes('if (') && !code.includes('if () {')) {
      suggestions.push({
        type: OptimizationType.CODE_STYLE,
        description: '考虑使用一致的大括号风格',
        priority: 'low'
      });
    }
    
    return suggestions;
  }

  /**
   * 检查安全性问题
   * @param code 代码
   * @returns 优化建议列表
   */
  private checkSecurityIssues(code: string): OptimizationSuggestion[] {
    const suggestions: OptimizationSuggestion[] = [];
    
    // 检查 eval 使用
    if (code.includes('eval(')) {
      suggestions.push({
        type: OptimizationType.SECURITY,
        description: '避免使用 eval 函数，可能导致安全漏洞',
        priority: 'high',
        location: {
          start: { line: 1, column: 0 },
          end: { line: 1, column: code.indexOf('eval(') + 'eval('.length }
        }
      });
    }
    
    // 检查未转义的用户输入
    if (code.includes('document.write(') || code.includes('innerHTML =')) {
      suggestions.push({
        type: OptimizationType.SECURITY,
        description: '避免直接将用户输入插入到 DOM 中，可能导致 XSS 攻击',
        priority: 'high',
        location: {
          start: { line: 1, column: 0 },
          end: { line: 1, column: 20 }
        }
      });
    }
    
    return suggestions;
  }

  /**
   * 检查可读性问题
   * @param code 代码
   * @returns 优化建议列表
   */
  private checkReadability(code: string): OptimizationSuggestion[] {
    const suggestions: OptimizationSuggestion[] = [];
    
    // 检查变量命名
    const variableRegex = /(var|let|const)\s+([a-zA-Z_$][a-zA-Z0-9_$]*)/g;
    let match;
    while ((match = variableRegex.exec(code)) !== null) {
      const variableName = match[2];
      if (variableName.length < 2 || variableName === 'x' || variableName === 'y' || variableName === 'temp') {
        suggestions.push({
          type: OptimizationType.READABILITY,
          description: '使用更具描述性的变量名',
          priority: 'medium',
          location: {
            start: { line: 1, column: match.index },
            end: { line: 1, column: match.index + match[0].length }
          }
        });
      }
    }
    
    // 检查函数长度
    const functionRegex = /function\s+[a-zA-Z_$][a-zA-Z0-9_$]*\s*\([^)]*\)\s*[:]?\s*[^\{]*\s*\{([\s\S]*?)\}/g;
    while ((match = functionRegex.exec(code)) !== null) {
      const functionBody = match[1];
      const lines = functionBody.split('\n').filter(line => line.trim());
      if (lines.length > 20) {
        suggestions.push({
          type: OptimizationType.READABILITY,
          description: '考虑将长函数拆分为多个 smaller 函数',
          priority: 'medium',
          location: {
            start: { line: 1, column: match.index },
            end: { line: 1, column: match.index + match[0].length }
          }
        });
      }
    }
    
    return suggestions;
  }

  /**
   * 检查维护性问题
   * @param code 代码
   * @returns 优化建议列表
   */
  private checkMaintainability(code: string): OptimizationSuggestion[] {
    const suggestions: OptimizationSuggestion[] = [];
    
    // 检查重复代码
    const lines = code.split('\n');
    const lineMap = new Map<string, number>();
    
    lines.forEach((line, index) => {
      const trimmedLine = line.trim();
      if (trimmedLine && !trimmedLine.startsWith('//') && !trimmedLine.startsWith('*')) {
        if (lineMap.has(trimmedLine)) {
          suggestions.push({
            type: OptimizationType.MAINTAINABILITY,
            description: '避免重复代码，考虑提取为函数',
            priority: 'medium',
            location: {
              start: { line: index + 1, column: 0 },
              end: { line: index + 1, column: line.length }
            }
          });
        } else {
          lineMap.set(trimmedLine, index);
        }
      }
    });
    
    // 检查未使用的变量
    const declaredVariables = new Set<string>();
    const usedVariables = new Set<string>();
    
    // 提取声明的变量
    const varRegex = /(var|let|const)\s+([a-zA-Z_$][a-zA-Z0-9_$]*)/g;
    while ((match = varRegex.exec(code)) !== null) {
      declaredVariables.add(match[2]);
    }
    
    // 提取使用的变量
    const usageRegex = /\b([a-zA-Z_$][a-zA-Z0-9_$]*)\b/g;
    while ((match = usageRegex.exec(code)) !== null) {
      usedVariables.add(match[1]);
    }
    
    // 检查未使用的变量
    declaredVariables.forEach(variable => {
      if (!usedVariables.has(variable)) {
        suggestions.push({
          type: OptimizationType.MAINTAINABILITY,
          description: `删除未使用的变量: ${variable}`,
          priority: 'low'
        });
      }
    });
    
    return suggestions;
  }
}
