
# llama.cpp 手动下载指南

## 当前状态
- saba, ufbx, quickjs: ✅ 已成功下载
- llama.cpp: ❌ 需要手动下载

## 手动下载步骤

### 方法 1: 直接下载 Zip（推荐）
1. 打开浏览器访问：
   ```
   https://github.com/ggml-org/llama.cpp/archive/refs/heads/master.zip
   ```

2. 下载完成后，将 `llama.cpp-master.zip` 文件复制到项目根目录：
   ```
   C:\Users\18119\Desktop\android-agent\
   ```

3. 解压zip文件，会生成 `llama.cpp-master` 文件夹

4. 将 `llama.cpp-master` 文件夹内的**所有内容**移动到：
   ```
   C:\Users\18119\Desktop\android-agent\llama\third_party\llama.cpp\
   ```

5. 删除 `llama.cpp-master` 文件夹和 zip 文件

### 方法 2: Git Clone（如果网络允许）
```bash
cd C:\Users\18119\Desktop\android-agent\llama\third_party
git clone --depth 1 https://github.com/ggml-org/llama.cpp.git
```

## 验证下载成功
下载完成后，运行以下命令检查：
```bash
python count.py
```

应该显示：
```
saba: 896
bullet3: 0
ufbx: 3095
quickjs: 79
llama.cpp: xxx  (大于100表示成功)
```

## 完成后
恢复 settings.gradle.kts 和 app/build.gradle.kts 中的模块注释（如果之前有注释的话），然后就可以构建项目了。
