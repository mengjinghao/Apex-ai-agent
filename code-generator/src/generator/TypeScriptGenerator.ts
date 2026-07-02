import { CodeGenerator, GenerateOptions, GeneratedCode, CodeType } from './CodeGenerator';

export class TypeScriptGenerator implements CodeGenerator {
  /**
   * 根据用户需求生成代码
   * @param requirements 用户需求描述
   * @param options 生成选项
   * @returns 生成的代码
   */
  generateCode(requirements: string, options?: GenerateOptions): GeneratedCode {
    // 解析用户需求，确定代码类型
    const codeType = this.determineCodeType(requirements);
    
    // 根据代码类型生成相应的代码
    switch (codeType) {
      case CodeType.FUNCTION:
        return this.generateFunctionCode(requirements, options);
      case CodeType.CLASS:
        return this.generateClassCode(requirements, options);
      case CodeType.INTERFACE:
        return this.generateInterfaceCode(requirements, options);
      case CodeType.MODULE:
        return this.generateModuleCode(requirements, options);
      default:
        return this.generateScriptCode(requirements, options);
    }
  }

  /**
   * 生成特定类型的代码
   * @param type 代码类型
   * @param parameters 生成参数
   * @returns 生成的代码
   */
  generateSpecificCode(type: CodeType, parameters: any): GeneratedCode {
    switch (type) {
      case CodeType.FUNCTION:
        return this.generateFunctionFromParameters(parameters);
      case CodeType.CLASS:
        return this.generateClassFromParameters(parameters);
      case CodeType.INTERFACE:
        return this.generateInterfaceFromParameters(parameters);
      case CodeType.MODULE:
        return this.generateModuleFromParameters(parameters);
      default:
        return {
          code: '',
          type: CodeType.OTHER,
          generatedAt: new Date(),
          description: 'Unknown code type'
        };
    }
  }

  /**
   * 获取支持的语言
   * @returns 支持的语言名称
   */
  getSupportedLanguage(): string {
    return 'TypeScript';
  }

  /**
   * 确定代码类型
   * @param requirements 用户需求描述
   * @returns 代码类型
   */
  private determineCodeType(requirements: string): CodeType {
    const lowerRequirements = requirements.toLowerCase();
    
    if (lowerRequirements.includes('function') || lowerRequirements.includes('方法')) {
      return CodeType.FUNCTION;
    } else if (lowerRequirements.includes('class') || lowerRequirements.includes('类')) {
      return CodeType.CLASS;
    } else if (lowerRequirements.includes('interface') || lowerRequirements.includes('接口')) {
      return CodeType.INTERFACE;
    } else if (lowerRequirements.includes('module') || lowerRequirements.includes('模块')) {
      return CodeType.MODULE;
    } else {
      return CodeType.SCRIPT;
    }
  }

  /**
   * 生成函数代码
   * @param requirements 用户需求描述
   * @param options 生成选项
   * @returns 生成的代码
   */
  private generateFunctionCode(requirements: string, options?: GenerateOptions): GeneratedCode {
    const indent = ' '.repeat(options?.indentSize || 2);
    const semicolon = options?.useSemicolons !== false ? ';' : '';
    const addComments = options?.addComments !== false;
    
    // 提取函数名和参数
    const functionName = this.extractFunctionName(requirements) || 'generatedFunction';
    const parameters = this.extractParameters(requirements);
    const returnType = this.extractReturnType(requirements) || 'void';
    
    let code = '';
    
    if (addComments) {
      code += `/**\n`;
      code += ` * ${requirements}\n`;
      parameters.forEach(param => {
        code += ` * @param ${param.name} ${param.description || ''}\n`;
      });
      code += ` * @returns ${returnType}\n`;
      code += ` */\n`;
    }
    
    code += `function ${functionName}(${parameters.map(p => `${p.name}: ${p.type}`).join(', ')})`: ${returnType} {\n`;
    code += `${indent}// 实现逻辑\n`;
    code += `${indent}return ${this.getDefaultReturnValue(returnType)}${semicolon}\n`;
    code += `}\n`;
    
    return {
      code,
      type: CodeType.FUNCTION,
      generatedAt: new Date(),
      description: requirements,
      dependencies: []
    };
  }

  /**
   * 生成类代码
   * @param requirements 用户需求描述
   * @param options 生成选项
   * @returns 生成的代码
   */
  private generateClassCode(requirements: string, options?: GenerateOptions): GeneratedCode {
    const indent = ' '.repeat(options?.indentSize || 2);
    const semicolon = options?.useSemicolons !== false ? ';' : '';
    const addComments = options?.addComments !== false;
    
    // 提取类名和属性
    const className = this.extractClassName(requirements) || 'GeneratedClass';
    const properties = this.extractClassProperties(requirements);
    const methods = this.extractClassMethods(requirements);
    
    let code = '';
    
    if (addComments) {
      code += `/**\n`;
      code += ` * ${requirements}\n`;
      code += ` */\n`;
    }
    
    code += `class ${className} {\n`;
    
    // 添加属性
    properties.forEach(prop => {
      if (addComments) {
        code += `${indent}/**\n`;
        code += `${indent} * ${prop.description || ''}\n`;
        code += `${indent} */\n`;
      }
      code += `${indent}private ${prop.name}: ${prop.type}${prop.initialValue ? ` = ${prop.initialValue}` : ''}${semicolon}\n`;
    });
    
    if (properties.length > 0) {
      code += `\n`;
    }
    
    // 添加构造函数
    if (properties.length > 0) {
      if (addComments) {
        code += `${indent}/**\n`;
        code += `${indent} * 构造函数\n`;
        properties.forEach(prop => {
          code += `${indent} * @param ${prop.name} ${prop.description || ''}\n`;
        });
        code += `${indent} */\n`;
      }
      code += `${indent}constructor(${properties.map(p => `${p.name}: ${p.type}`).join(', ')}) {\n`;
      properties.forEach(prop => {
        code += `${indent}${indent}this.${prop.name} = ${prop.name}${semicolon}\n`;
      });
      code += `${indent}}\n\n`;
    }
    
    // 添加方法
    methods.forEach(method => {
      if (addComments) {
        code += `${indent}/**\n`;
        code += `${indent} * ${method.description || ''}\n`;
        method.parameters?.forEach(param => {
          code += `${indent} * @param ${param.name} ${param.description || ''}\n`;
        });
        if (method.returnType) {
          code += `${indent} * @returns ${method.returnType}\n`;
        }
        code += `${indent} */\n`;
      }
      code += `${indent}${method.name}(${method.parameters?.map(p => `${p.name}: ${p.type}`).join(', ') || ''})${method.returnType ? `: ${method.returnType}` : ''} {\n`;
      code += `${indent}${indent}// 实现逻辑\n`;
      if (method.returnType && method.returnType !== 'void') {
        code += `${indent}${indent}return ${this.getDefaultReturnValue(method.returnType)}${semicolon}\n`;
      }
      code += `${indent}}\n\n`;
    });
    
    code += `}\n`;
    
    return {
      code,
      type: CodeType.CLASS,
      generatedAt: new Date(),
      description: requirements,
      dependencies: []
    };
  }

  /**
   * 生成接口代码
   * @param requirements 用户需求描述
   * @param options 生成选项
   * @returns 生成的代码
   */
  private generateInterfaceCode(requirements: string, options?: GenerateOptions): GeneratedCode {
    const indent = ' '.repeat(options?.indentSize || 2);
    const addComments = options?.addComments !== false;
    
    // 提取接口名和属性
    const interfaceName = this.extractInterfaceName(requirements) || 'GeneratedInterface';
    const properties = this.extractInterfaceProperties(requirements);
    
    let code = '';
    
    if (addComments) {
      code += `/**\n`;
      code += ` * ${requirements}\n`;
      code += ` */\n`;
    }
    
    code += `interface ${interfaceName} {\n`;
    
    properties.forEach(prop => {
      if (addComments) {
        code += `${indent}/**\n`;
        code += `${indent} * ${prop.description || ''}\n`;
        code += `${indent} */\n`;
      }
      code += `${indent}${prop.name}${prop.optional ? '?' : ''}: ${prop.type};\n`;
    });
    
    code += `}\n`;
    
    return {
      code,
      type: CodeType.INTERFACE,
      generatedAt: new Date(),
      description: requirements,
      dependencies: []
    };
  }

  /**
   * 生成模块代码
   * @param requirements 用户需求描述
   * @param options 生成选项
   * @returns 生成的代码
   */
  private generateModuleCode(requirements: string, options?: GenerateOptions): GeneratedCode {
    const indent = ' '.repeat(options?.indentSize || 2);
    const semicolon = options?.useSemicolons !== false ? ';' : '';
    const addComments = options?.addComments !== false;
    
    // 提取模块名
    const moduleName = this.extractModuleName(requirements) || 'generatedModule';
    
    let code = '';
    
    if (addComments) {
      code += `/**\n`;
      code += ` * ${requirements}\n`;
      code += ` */\n`;
    }
    
    code += `namespace ${moduleName} {\n`;
    code += `${indent}// 模块内容\n`;
    code += `}\n`;
    
    return {
      code,
      type: CodeType.MODULE,
      generatedAt: new Date(),
      description: requirements,
      dependencies: []
    };
  }

  /**
   * 生成脚本代码
   * @param requirements 用户需求描述
   * @param options 生成选项
   * @returns 生成的代码
   */
  private generateScriptCode(requirements: string, options?: GenerateOptions): GeneratedCode {
    const indent = ' '.repeat(options?.indentSize || 2);
    const semicolon = options?.useSemicolons !== false ? ';' : '';
    const addComments = options?.addComments !== false;
    
    let code = '';
    
    if (addComments) {
      code += `/**\n`;
      code += ` * ${requirements}\n`;
      code += ` */\n`;
    }
    
    code += `// 脚本代码\n`;
    code += `console.log('Generated script for: ${requirements}')${semicolon}\n`;
    
    return {
      code,
      type: CodeType.SCRIPT,
      generatedAt: new Date(),
      description: requirements,
      dependencies: []
    };
  }

  /**
   * 从参数生成函数代码
   * @param parameters 生成参数
   * @returns 生成的代码
   */
  private generateFunctionFromParameters(parameters: any): GeneratedCode {
    const indent = '  ';
    const semicolon = ';';
    
    const { name, parameters: params, returnType, body, description } = parameters;
    
    let code = '';
    
    if (description) {
      code += `/**\n`;
      code += ` * ${description}\n`;
      params?.forEach((param: any) => {
        code += ` * @param ${param.name} ${param.description || ''}\n`;
      });
      if (returnType) {
        code += ` * @returns ${returnType}\n`;
      }
      code += ` */\n`;
    }
    
    code += `function ${name}(${params?.map((p: any) => `${p.name}: ${p.type}`).join(', ') || ''})${returnType ? `: ${returnType}` : ''} {\n`;
    code += body ? body.split('\n').map(line => `${indent}${line}`).join('\n') : `${indent}// 实现逻辑\n`;
    if (returnType && returnType !== 'void' && !body?.includes('return')) {
      code += `${indent}return ${this.getDefaultReturnValue(returnType)}${semicolon}\n`;
    }
    code += `}\n`;
    
    return {
      code,
      type: CodeType.FUNCTION,
      generatedAt: new Date(),
      description,
      dependencies: []
    };
  }

  /**
   * 从参数生成类代码
   * @param parameters 生成参数
   * @returns 生成的代码
   */
  private generateClassFromParameters(parameters: any): GeneratedCode {
    const indent = '  ';
    const semicolon = ';';
    
    const { name, properties, methods, description } = parameters;
    
    let code = '';
    
    if (description) {
      code += `/**\n`;
      code += ` * ${description}\n`;
      code += ` */\n`;
    }
    
    code += `class ${name} {\n`;
    
    // 添加属性
    properties?.forEach((prop: any) => {
      if (prop.description) {
        code += `${indent}/**\n`;
        code += `${indent} * ${prop.description}\n`;
        code += `${indent} */\n`;
      }
      code += `${indent}${prop.accessModifier || 'private'} ${prop.name}: ${prop.type}${prop.initialValue ? ` = ${prop.initialValue}` : ''}${semicolon}\n`;
    });
    
    if (properties?.length > 0) {
      code += `\n`;
    }
    
    // 添加构造函数
    if (properties?.length > 0) {
      code += `${indent}constructor(${properties.map((p: any) => `${p.name}: ${p.type}`).join(', ')}) {\n`;
      properties.forEach((prop: any) => {
        code += `${indent}${indent}this.${prop.name} = ${prop.name}${semicolon}\n`;
      });
      code += `${indent}}\n\n`;
    }
    
    // 添加方法
    methods?.forEach((method: any) => {
      if (method.description) {
        code += `${indent}/**\n`;
        code += `${indent} * ${method.description}\n`;
        method.parameters?.forEach((param: any) => {
          code += `${indent} * @param ${param.name} ${param.description || ''}\n`;
        });
        if (method.returnType) {
          code += `${indent} * @returns ${method.returnType}\n`;
        }
        code += `${indent} */\n`;
      }
      code += `${indent}${method.accessModifier || 'public'} ${method.name}(${method.parameters?.map((p: any) => `${p.name}: ${p.type}`).join(', ') || ''})${method.returnType ? `: ${method.returnType}` : ''} {\n`;
      code += method.body ? method.body.split('\n').map(line => `${indent}${indent}${line}`).join('\n') : `${indent}${indent}// 实现逻辑\n`;
      if (method.returnType && method.returnType !== 'void' && !method.body?.includes('return')) {
        code += `${indent}${indent}return ${this.getDefaultReturnValue(method.returnType)}${semicolon}\n`;
      }
      code += `${indent}}\n\n`;
    });
    
    code += `}\n`;
    
    return {
      code,
      type: CodeType.CLASS,
      generatedAt: new Date(),
      description,
      dependencies: []
    };
  }

  /**
   * 从参数生成接口代码
   * @param parameters 生成参数
   * @returns 生成的代码
   */
  private generateInterfaceFromParameters(parameters: any): GeneratedCode {
    const indent = '  ';
    
    const { name, properties, description } = parameters;
    
    let code = '';
    
    if (description) {
      code += `/**\n`;
      code += ` * ${description}\n`;
      code += ` */\n`;
    }
    
    code += `interface ${name} {\n`;
    
    properties?.forEach((prop: any) => {
      if (prop.description) {
        code += `${indent}/**\n`;
        code += `${indent} * ${prop.description}\n`;
        code += `${indent} */\n`;
      }
      code += `${indent}${prop.name}${prop.optional ? '?' : ''}: ${prop.type};\n`;
    });
    
    code += `}\n`;
    
    return {
      code,
      type: CodeType.INTERFACE,
      generatedAt: new Date(),
      description,
      dependencies: []
    };
  }

  /**
   * 从参数生成模块代码
   * @param parameters 生成参数
   * @returns 生成的代码
   */
  private generateModuleFromParameters(parameters: any): GeneratedCode {
    const indent = '  ';
    
    const { name, content, description } = parameters;
    
    let code = '';
    
    if (description) {
      code += `/**\n`;
      code += ` * ${description}\n`;
      code += ` */\n`;
    }
    
    code += `namespace ${name} {\n`;
    code += content ? content.split('\n').map(line => `${indent}${line}`).join('\n') : `${indent}// 模块内容\n`;
    code += `}\n`;
    
    return {
      code,
      type: CodeType.MODULE,
      generatedAt: new Date(),
      description,
      dependencies: []
    };
  }

  /**
   * 提取函数名
   * @param requirements 用户需求描述
   * @returns 函数名
   */
  private extractFunctionName(requirements: string): string | null {
    // 简单的函数名提取逻辑
    const match = requirements.match(/(function|方法)\s+名为?\s*([a-zA-Z_$][a-zA-Z0-9_$]*)/i);
    return match ? match[2] : null;
  }

  /**
   * 提取参数
   * @param requirements 用户需求描述
   * @returns 参数列表
   */
  private extractParameters(requirements: string): Array<{ name: string; type: string; description?: string }> {
    // 简单的参数提取逻辑
    const params: Array<{ name: string; type: string; description?: string }> = [];
    const matches = requirements.match(/参数\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*[:：]\s*([a-zA-Z]+)/gi);
    
    if (matches) {
      matches.forEach(match => {
        const parts = match.split(/[:：]/);
        if (parts.length === 2) {
          const name = parts[0].replace(/参数\s*/, '').trim();
          const type = parts[1].trim();
          params.push({ name, type });
        }
      });
    }
    
    return params.length > 0 ? params : [{ name: 'param', type: 'any' }];
  }

  /**
   * 提取返回类型
   * @param requirements 用户需求描述
   * @returns 返回类型
   */
  private extractReturnType(requirements: string): string | null {
    // 简单的返回类型提取逻辑
    const match = requirements.match(/返回\s*[:：]\s*([a-zA-Z]+)/i);
    return match ? match[1] : null;
  }

  /**
   * 提取类名
   * @param requirements 用户需求描述
   * @returns 类名
   */
  private extractClassName(requirements: string): string | null {
    // 简单的类名提取逻辑
    const match = requirements.match(/(class|类)\s+名为?\s*([a-zA-Z_$][a-zA-Z0-9_$]*)/i);
    return match ? match[2] : null;
  }

  /**
   * 提取类属性
   * @param requirements 用户需求描述
   * @returns 属性列表
   */
  private extractClassProperties(requirements: string): Array<{ name: string; type: string; initialValue?: string; description?: string }> {
    // 简单的属性提取逻辑
    const properties: Array<{ name: string; type: string; initialValue?: string; description?: string }> = [];
    const matches = requirements.match(/属性\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*[:：]\s*([a-zA-Z]+)/gi);
    
    if (matches) {
      matches.forEach(match => {
        const parts = match.split(/[:：]/);
        if (parts.length === 2) {
          const name = parts[0].replace(/属性\s*/, '').trim();
          const type = parts[1].trim();
          properties.push({ name, type });
        }
      });
    }
    
    return properties.length > 0 ? properties : [{ name: 'value', type: 'any' }];
  }

  /**
   * 提取类方法
   * @param requirements 用户需求描述
   * @returns 方法列表
   */
  private extractClassMethods(requirements: string): Array<{ name: string; parameters?: Array<{ name: string; type: string }>; returnType?: string; description?: string }> {
    // 简单的方法提取逻辑
    const methods: Array<{ name: string; parameters?: Array<{ name: string; type: string }>; returnType?: string; description?: string }> = [];
    const matches = requirements.match(/方法\s+([a-zA-Z_$][a-zA-Z0-9_$]*)/gi);
    
    if (matches) {
      matches.forEach(match => {
        const name = match.replace(/方法\s*/, '').trim();
        methods.push({ name, returnType: 'void' });
      });
    }
    
    return methods.length > 0 ? methods : [{ name: 'doSomething', returnType: 'void' }];
  }

  /**
   * 提取接口名
   * @param requirements 用户需求描述
   * @returns 接口名
   */
  private extractInterfaceName(requirements: string): string | null {
    // 简单的接口名提取逻辑
    const match = requirements.match(/(interface|接口)\s+名为?\s*([a-zA-Z_$][a-zA-Z0-9_$]*)/i);
    return match ? match[2] : null;
  }

  /**
   * 提取接口属性
   * @param requirements 用户需求描述
   * @returns 属性列表
   */
  private extractInterfaceProperties(requirements: string): Array<{ name: string; type: string; optional?: boolean; description?: string }> {
    // 简单的属性提取逻辑
    const properties: Array<{ name: string; type: string; optional?: boolean; description?: string }> = [];
    const matches = requirements.match(/属性\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*[:：]\s*([a-zA-Z]+)/gi);
    
    if (matches) {
      matches.forEach(match => {
        const parts = match.split(/[:：]/);
        if (parts.length === 2) {
          const name = parts[0].replace(/属性\s*/, '').trim();
          const type = parts[1].trim();
          properties.push({ name, type });
        }
      });
    }
    
    return properties.length > 0 ? properties : [{ name: 'id', type: 'number' }, { name: 'name', type: 'string' }];
  }

  /**
   * 提取模块名
   * @param requirements 用户需求描述
   * @returns 模块名
   */
  private extractModuleName(requirements: string): string | null {
    // 简单的模块名提取逻辑
    const match = requirements.match(/(module|模块)\s+名为?\s*([a-zA-Z_$][a-zA-Z0-9_$]*)/i);
    return match ? match[2] : null;
  }

  /**
   * 获取默认返回值
   * @param returnType 返回类型
   * @returns 默认返回值
   */
  private getDefaultReturnValue(returnType: string): string {
    switch (returnType.toLowerCase()) {
      case 'number':
        return '0';
      case 'string':
        return "''";
      case 'boolean':
        return 'false';
      case 'array':
      case 'any[]':
        return '[]';
      case 'object':
      case '{}':
        return '{}';
      case 'null':
        return 'null';
      case 'undefined':
        return 'undefined';
      default:
        return 'undefined';
    }
  }
}
