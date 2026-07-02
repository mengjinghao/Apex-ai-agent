# Apex 模块配置指南（不依赖 Git）

本指南帮助您手动配置项目所需的所有模块，完全不依赖 Git。

---

## 📋 模块状态

| 模块 | 状态 | 需要下载 |
|------|------|----------|
| terminal | ❌ 缺失 | 需要 |
| mnn | ⚠️ 部分缺失 | 需要 |
| llama | ⚠️ 部分缺失 | 需要 |
| mmd | ⚠️ 部分缺失 | 需要 |
| fbx | ⚠️ 部分缺失 | 需要 |
| showerclient | ✅ 完整 | 不需要 |
| quickjs | ⚠️ 部分缺失 | 需要 |
| dragonbones | ✅ 完整 | 不需要 |

---

## 📦 需要下载的内容

### 1. 核心依赖库（必需）

从 Google Drive 下载以下文件并解压到对应位置：

| 文件名 | 下载地址 | 解压位置 |
|--------|----------|----------|
| `models.zip` | https://drive.google.com/drive/folders/1g-Q_i7cf6Ua4KX9ZM6V282EEZvTVVfF7?usp=sharing | `app\src\main\assets\models\` |
| `subpack.zip` | https://drive.google.com/drive/folders/1g-Q_i7cf6Ua4KX9ZM6V282EEZvTVVfF7?usp=sharing | `app\src\main\assets\subpack\` |
| `jniLibs.zip` | https://drive.google.com/drive/folders/1g-Q_i7cf6Ua4KX9ZM6V282EEZvTVVfF7?usp=sharing | `app\src\main\jniLibs\` |
| `libs.zip` | https://drive.google.com/drive/folders/1g-Q_i7cf6Ua4KX9ZM6V282EEZvTVVfF7?usp=sharing | `app\libs\` |

---

### 2. 各模块子依赖

#### Terminal 模块
- 下载地址：https://github.com/AAswordman/ApexTerminalCore/archive/refs/heads/main.zip
- 解压后重命名文件夹为 `terminal`
- 放置位置：项目根目录下的 `terminal\`

#### MNN 模块
- 下载地址：https://github.com/alibaba/MNN/archive/refs/heads/master.zip
- 解压后重命名文件夹为 `MNN`
- 放置位置：`mnn\src\main\cpp\MNN\`

#### Llama 模块
- 下载地址：https://github.com/ggml-org/llama.cpp/archive/refs/heads/master.zip
- 解压后重命名文件夹为 `llama.cpp`
- 放置位置：`llama\third_party\llama.cpp\`

#### MMD 模块 - Saba
- 下载地址：https://github.com/benikabocha/saba/archive/refs/heads/master.zip
- 解压后重命名文件夹为 `saba`
- 放置位置：`mmd\third_party\saba\`

#### MMD 模块 - Bullet3
- 下载地址：https://github.com/bulletphysics/bullet3/archive/refs/heads/master.zip
- 解压后重命名文件夹为 `bullet3`
- 放置位置：`mmd\third_party\bullet3\`

#### FBX 模块
- 下载地址：https://github.com/ufbx/ufbx/archive/refs/heads/master.zip
- 解压后重命名文件夹为 `ufbx`
- 放置位置：`fbx\third_party\ufbx\`

#### QuickJS 模块
- 下载地址：https://github.com/bellard/quickjs/archive/refs/heads/master.zip
- 解压后重命名文件夹为 `quickjs`
- 放置位置：`quickjs\thirdparty\quickjs\`

---

## 📂 目录结构完成后应该如下：

```
Apex/
├── terminal/                          ← 新增
├── mnn/
│   └── src/main/cpp/
│       └── MNN/                      ← 新增
├── llama/
│   └── third_party/
│       └── llama.cpp/                 ← 新增
├── mmd/
│   └── third_party/
│       ├── saba/                      ← 新增
│       └── bullet3/                 ← 新增
├── fbx/
│   └── third_party/
│       └── ufbx/                      ← 新增
├── quickjs/
│   └── thirdparty/
│       └── quickjs/                   ← 新增
└── app/
    ├── libs/                        ← 解压 libs.zip
    └── src/main/
        ├── assets/
        │   ├── models/               ← 解压 models.zip
        │   └── subpack/              ← 解压 subpack.zip
        └── jniLibs/                ← 解压 jniLibs.zip
```

---

## 🔧 配置步骤

1. 创建必要的文件夹结构
2. 下载所有需要的 zip 文件
3. 解压并放置到正确位置
4. 配置 local.properties（参考 local.properties.example

---

## 📝 注意

