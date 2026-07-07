
# 子模块下载指南

## 当前状态

### ✅ 已完整的模块（无需下载）
- `app/` - 主应用
- `engine/` - 核心引擎
- `ai-terminal/` - AI增强终端（替代旧 terminal）
- `dragonbones/` - 2D骨骼动画Avatar
- `showerclient/` - 核心Shell服务
- `mnn/src/main/cpp/MNN/` - MNN神经网络引擎（已下载）

### ❌ 需要下载的子模块

| 模块 | 需要的内容 | 下载链接 | 目标路径 |
|------|------------|----------|----------|
| **llama.cpp** | Llama.cpp本地推理 | https://github.com/ggml-org/llama.cpp/archive/refs/heads/master.zip | `llama/third_party/llama.cpp/` |
| **saba** | MMD渲染引擎 | https://github.com/benikabocha/saba/archive/refs/heads/master.zip | `mmd/third_party/saba/` |
| **bullet3** | 物理引擎 | https://github.com/bulletphysics/bullet3/archive/refs/heads/master.zip | `mmd/third_party/bullet3/` |
| **ufbx** | FBX解析库 | https://github.com/ufbx/ufbx/archive/refs/heads/master.zip | `fbx/third_party/ufbx/` |
| **quickjs** | JavaScript引擎 | https://github.com/bellard/quickjs/archive/refs/heads/master.zip | `quickjs/thirdparty/quickjs/` |

## 下载方法（任选一种）

### 方法1: 手动下载（推荐，最可靠）
1. 点击上表中的下载链接
2. 下载 zip 文件
3. 解压到对应的目标路径
4. 确保解压后直接是内容文件夹（不是包含在另一个文件夹里）

### 方法2: 使用 PowerShell 脚本
```powershell
# 在项目根目录运行
# 下载 llama.cpp
Invoke-WebRequest -Uri "https://github.com/ggml-org/llama.cpp/archive/refs/heads/master.zip" -OutFile "llama.zip"
Expand-Archive -Path "llama.zip" -DestinationPath "llama/third_party"
# 移动内容到正确位置
Get-ChildItem -Path "llama/third_party/llama.cpp-master" | Move-Item -Destination "llama/third_party/llama.cpp"
Remove-Item -Path "llama.zip"

# 下载其他模块类似...
```

### 方法3: 重新克隆完整仓库
```bash
cd ..
git clone --recursive https://github.com/AAswordman/Apex.git
# (如果这是原始仓库的地址)
```

## 当前可以尝试构建吗？

**是的！** 即使缺少几个子模块，你仍然可以构建项目，因为：
- `llama/` 模块有 stub 回退机制（CMakeLists.txt 中有判断）
- `mmd/`、`fbx/`、`quickjs/` 目前已在 settings.gradle.kts 中注释掉
- 核心功能不受影响

## 如果需要完整功能，需要恢复的模块
在 `settings.gradle.kts` 和 `app/build.gradle.kts` 中取消注释：
- `:mmd`
- `:fbx`
- `:quickjs`
