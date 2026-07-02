import { CodeGenerationSystem, CodeType } from './src/index';

// 创建代码生成系统实例
const codeGenSystem = new CodeGenerationSystem('typescript');

console.log('=== 代码生成系统示例 ===\n');

// 示例 1: 根据需求生成函数代码
console.log('1. 根据需求生成函数代码:');
const functionRequirements = '创建一个名为 calculateSum 的函数，接收两个数字参数，返回它们的和';
const functionCode = codeGenSystem.generateCode(functionRequirements, {
  addComments: true,
  indentSize: 2,
  useSemicolons: true
});
console.log(functionCode.code);
console.log('\n');

// 示例 2: 根据需求生成类代码
console.log('2. 根据需求生成类代码:');
const classRequirements = '创建一个名为 Person 的类，包含 name 和 age 属性，以及一个 greet 方法';
const classCode = codeGenSystem.generateCode(classRequirements, {
  addComments: true,
  indentSize: 2,
  useSemicolons: true
});
console.log(classCode.code);
console.log('\n');

// 示例 3: 生成特定类型的代码
console.log('3. 生成特定类型的代码:');
const interfaceCode = codeGenSystem.generateSpecificCode(CodeType.INTERFACE, {
  name: 'User',
  properties: [
    { name: 'id', type: 'number', description: '用户 ID' },
    { name: 'name', type: 'string', description: '用户名' },
    { name: 'email', type: 'string', description: '邮箱地址', optional: true }
  ],
  description: '用户接口'
});
console.log(interfaceCode.code);
console.log('\n');

// 示例 4: 分析代码并提供优化建议
console.log('4. 分析代码并提供优化建议:');
const codeToOptimize = `
function calculateSum(a: number, b: number): number {
  let result = 0;
  for (let i = 0; i < 1000; i++) {
    result += a + b;
  }
  return result;
}
`;
const optimizationResult = codeGenSystem.analyzeAndOptimize(codeToOptimize);
console.log('优化建议:');
optimizationResult.suggestions.forEach((suggestion, index) => {
  console.log(`${index + 1}. ${suggestion.description} (${suggestion.priority})`);
  if (suggestion.suggestedChange) {
    console.log(`   建议修改: ${suggestion.suggestedChange}`);
  }
});
console.log('\n');

// 示例 5: 应用优化建议
console.log('5. 应用优化建议:');
const optimizedCode = codeGenSystem.applyOptimizations(codeToOptimize, optimizationResult.suggestions);
console.log('优化后的代码:');
console.log(optimizedCode);
console.log('\n');

// 示例 6: 提供代码补全建议
console.log('6. 提供代码补全建议:');
const codeToComplete = 'function ';
const completions = codeGenSystem.provideCompletion(codeToComplete, { line: 1, column: 9 });
console.log('补全建议:');
completions.forEach((completion, index) => {
  console.log(`${index + 1}. ${completion.text} - ${completion.description}`);
});
console.log('\n');

// 示例 7: 格式化代码
console.log('7. 格式化代码:');
const unformattedCode = 'function test(){let x=1;return x;}';
const formattedCode = codeGenSystem.formatCode(unformattedCode, {
  indentSize: 2,
  useSemicolons: true,
  useSingleQuotes: true
});
console.log('格式化后的代码:');
console.log(formattedCode);
console.log('\n');

// 示例 8: 检测代码错误
console.log('8. 检测代码错误:');
const codeWithErrors = 'function test() { console.log("Hello world" }';
const errors = codeGenSystem.detectErrors(codeWithErrors);
console.log('检测到的错误:');
errors.forEach((error, index) => {
  console.log(`${index + 1}. ${error.message} (${error.severity})`);
  console.log(`   位置: 行 ${error.position.start.line}, 列 ${error.position.start.column}`);
});
console.log('\n');

// 示例 9: 重构代码
console.log('9. 重构代码:');
const codeToRefactor = 'const result = 1 + 2 + 3 + 4 + 5;';
const refactoredCode = codeGenSystem.refactorCode(codeToRefactor, 'extract_variable');
console.log('重构后的代码:');
console.log(refactoredCode);
console.log('\n');

console.log('=== 示例结束 ===');
