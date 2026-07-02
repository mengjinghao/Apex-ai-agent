import { CodeAnalyzerFactory } from './analyzer/CodeAnalyzerFactory';

/**
 * 代码分析系统示例
 */
function main() {
  console.log('=== 代码分析系统 ===\n');
  
  // 支持的语言
  const supportedLanguages = CodeAnalyzerFactory.getSupportedLanguages();
  console.log(`支持的语言: ${supportedLanguages.join(', ')}\n`);
  
  // 分析 TypeScript 代码
  console.log('=== 分析 TypeScript 代码 ===');
  const tsCode = `
import { CodeAnalyzer } from './CodeAnalyzer';

export class TypeScriptAnalyzer implements CodeAnalyzer {
  analyzeSyntax(code: string) {
    return { hasErrors: false, errors: [], syntaxTree: {} };
  }
  
  identifyStructure(code: string) {
    return { functions: [], classes: [], variables: [], imports: [] };
  }
  
  understandSemantics(code: string) {
    return { 
      mainFunctionality: 'TypeScript analyzer', 
      dependencies: ['./CodeAnalyzer'], 
      executionFlow: [], 
      complexity: { cyclomatic: 1, cognitive: 1 } 
    };
  }
  
  getSupportedLanguage() {
    return 'TypeScript';
  }
}
`;
  
  const tsAnalyzer = CodeAnalyzerFactory.createAnalyzer('typescript');
  const tsSyntaxResult = tsAnalyzer.analyzeSyntax(tsCode);
  const tsStructureResult = tsAnalyzer.identifyStructure(tsCode);
  const tsSemanticsResult = tsAnalyzer.understandSemantics(tsCode);
  
  console.log('语法分析结果:', tsSyntaxResult.hasErrors ? '有错误' : '无错误');
  console.log('结构识别结果:');
  console.log('  函数数量:', tsStructureResult.functions.length);
  console.log('  类数量:', tsStructureResult.classes.length);
  console.log('  变量数量:', tsStructureResult.variables.length);
  console.log('  导入数量:', tsStructureResult.imports.length);
  console.log('语义理解结果:');
  console.log('  主要功能:', tsSemanticsResult.mainFunctionality);
  console.log('  依赖关系:', tsSemanticsResult.dependencies);
  console.log('  复杂度:', `圈复杂度 ${tsSemanticsResult.complexity.cyclomatic}, 认知复杂度 ${tsSemanticsResult.complexity.cognitive}`);
  console.log('');
  
  // 分析 JavaScript 代码
  console.log('=== 分析 JavaScript 代码 ===');
  const jsCode = `
const fs = require('fs');

function readFile(filename) {
  return fs.readFileSync(filename, 'utf8');
}

class FileHandler {
  constructor() {
    this.files = [];
  }
  
  addFile(filename) {
    this.files.push(filename);
  }
  
  readAll() {
    return this.files.map(file => readFile(file));
  }
}

const handler = new FileHandler();
handler.addFile('example.txt');
const contents = handler.readAll();
console.log(contents);
`;
  
  const jsAnalyzer = CodeAnalyzerFactory.createAnalyzer('javascript');
  const jsSyntaxResult = jsAnalyzer.analyzeSyntax(jsCode);
  const jsStructureResult = jsAnalyzer.identifyStructure(jsCode);
  const jsSemanticsResult = jsAnalyzer.understandSemantics(jsCode);
  
  console.log('语法分析结果:', jsSyntaxResult.hasErrors ? '有错误' : '无错误');
  console.log('结构识别结果:');
  console.log('  函数数量:', jsStructureResult.functions.length);
  console.log('  类数量:', jsStructureResult.classes.length);
  console.log('  变量数量:', jsStructureResult.variables.length);
  console.log('  导入数量:', jsStructureResult.imports.length);
  console.log('语义理解结果:');
  console.log('  主要功能:', jsSemanticsResult.mainFunctionality);
  console.log('  依赖关系:', jsSemanticsResult.dependencies);
  console.log('  复杂度:', `圈复杂度 ${jsSemanticsResult.complexity.cyclomatic}, 认知复杂度 ${jsSemanticsResult.complexity.cognitive}`);
  console.log('');
  
  console.log('=== 分析完成 ===');
}

// 运行示例
if (require.main === module) {
  main();
}

// 导出主要类和函数
export { CodeAnalyzerFactory };
