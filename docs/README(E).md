
&lt;div align="center"&gt;

&lt;a href="README.md"&gt;中文版&lt;/a&gt; | &lt;span&gt;English&lt;/span&gt;

&lt;br&gt;&lt;br&gt;

&lt;img src="https://img.shields.io/badge/Platform-Android_8.0%2B-brightgreen.svg" alt="Platform"&gt;
&lt;a href="docs/CONTRIBUTING.md"&gt;&lt;img src="https://img.shields.io/badge/contributions-welcome-brightgreen.svg" alt="Contributions Welcome"&gt;&lt;/a&gt;
&lt;a href="mailto:mjh4117222@gmail.com"&gt;&lt;img src="https://img.shields.io/badge/📧-Email-red.svg" alt="Email"&gt;&lt;/a&gt;
&lt;a href="https://qm.qq.com/q/Sa4fKEH7sO"&gt;&lt;img src="https://img.shields.io/badge/💬-QQ_Group-blue.svg" alt="QQ Group"&gt;&lt;/a&gt;
&lt;a href="https://discord.gg/YnV9MWurRF"&gt;&lt;img src="https://img.shields.io/badge/🎮-Discord-5865F2.svg" alt="Discord"&gt;&lt;/a&gt;
&lt;a href="https://github.com/mengjinghao/Apex-agent/issues"&gt;&lt;img src="https://img.shields.io/badge/🐛-Issues-orange.svg" alt="Issues"&gt;&lt;/a&gt;

&lt;/div&gt;

&lt;div align="center"&gt;
  &lt;img src="app/src/main/res/playstore-icon.png" width="140" height="140" alt="Apex-Agent Logo"&gt;
  &lt;h1&gt;🚀 Apex-Agent&lt;/h1&gt;
  &lt;p&gt;&lt;b&gt;Full-featured AI Assistant for Android&lt;/b&gt;&lt;/p&gt;
  &lt;p&gt;Featuring Burst Mode with checkpoint resume, batch processing, and fully automated task execution&lt;/p&gt;
&lt;/div&gt;

---

## 🌟 Project Introduction

**Apex-Agent** is a revolutionary Android AI automation platform powered by **Burst Mode**, providing industry-leading checkpoint resume, batch task processing, and fully automated execution capabilities. With advanced Agent architecture, intelligent chat, tool ecosystem, workflow automation, Web session management, MNN/llama.cpp local inference, and MCP/Skill ecosystem - it's an **all-in-one AI assistant** deeply integrated with Android permissions and tools.

### ✨ Core Highlights

| Feature | Description |
|---------|-------------|
| 🖥️ **Ubuntu 24 Environment** | Complete Ubuntu 24 system with vim, MCP, Python support |
| 🧠 **Intelligent Memory** | AI auto-categorization, time queries, import/export, auto-summary |
| 🗣️ **Voice Interaction** | Local/cloud TTS + local STT, custom voice tones, voice wake-up |
| 🤖 **Local AI Models** | MNN/llama.cpp local model support, fully offline |
| 🎭 **Personality &amp; Character Cards** | Custom AI personality, card backup/export, AI-to-AI chat |
| 🔌 **Rich Tool Ecosystem** | 40+ built-in tools + MCP/Skill marketplace plugins |

---

## 🔥 Burst Mode - Flagship Feature

Burst Mode is Apex-Agent's most powerful, revolutionary feature - representing the highest level of mobile AI automation!

### Core Capabilities

- **⚡ Checkpoint Resume** : Seamless recovery after task interruption
- **🔄 Batch Processing** : Handle hundreds of tasks at once
- **🤖 Fully Automated** : No human intervention needed
- **💾 State Persistence** : All progress automatically saved
- **📊 Smart Scheduling** : Dynamic execution strategy adjustment
- **🛡️ Fault Tolerance** : Auto-retry and error recovery
- **🧠 Thinking Visualization** : Real-time decision process display

### Use Cases

- Batch file processing, data collection, automated testing, content generation, system maintenance

---

## 🛠️ Feature Overview

### 📦 Built-in Tool System

| Tool Category | Features |
|---------------|----------|
| 🐧 **Linux Environment** | Complete Ubuntu 24, apt package management, Python/Node.js |
| 📁 **File System** | Read/write, search, compress, Git integration, syntax checking |
| 🌐 **Network Tools** | HTTP requests, web access, file upload/download |
| ⚙️ **System Operations** | App installation, permission management, UI automation |
| 🎬 **Media Processing** | Video conversion, OCR/vision understanding, camera capture |
| 🧑‍💻 **Dev &amp; Terminal** | Web workspace, code editing, SSH connections |
| 🎨 **AI Creation** | Drawing toolkits, image search/download |
| 🔍 **Search Engines** | Deep search, DuckDuckGo, Tavily, Google Scholar |

### 🎨 Interface Customization

- ✨ Theme system (colors, fonts, spacing)
- 🔤 Multi-language UI (Chinese/English)
- 🎭 Desktop pet (WebP animations)
- 📱 Tablet support, status bar hiding
- 🎨 Markdown rendering, LaTeX formulas
- 📊 Token usage statistics

---

## 📱 Quick Start

### System Requirements

| Component | Requirement |
|-----------|-------------|
| **OS** | Android 8.0+ (API 26+) |
| **Memory** | 4GB+ RAM (6GB+ recommended) |
| **Storage** | 500MB+ free space |

### Download &amp; Install

1. Download latest APK from Release Page
2. Install on your Android device
3. Launch app and complete setup
4. Grant necessary permissions
5. Start using!

⚠️ **Security Warning**: Download only from official sources

---

## 🏗️ Development Guide

### Environment Requirements

- **Android Studio**: Hedgehog or later
- **JDK**: 17 or later
- **Gradle**: 8.x
- **Android SDK**: API 34+
- **NDK**: r27+ (for C++ native code)

### Build Steps

```bash
# 1. Clone repository
git clone https://github.com/mengjinghao/Apex-agent.git
cd Apex-agent

# 2. Initialize submodules
git submodule update --init --recursive

# 3. Configure local.properties
sdk.dir=/path/to/android/sdk
ndk.dir=/path/to/android/ndk

# 4. Build project
./gradlew assembleDebug

# 5. Install to device
./gradlew installDebug
```

### Project Structure

```
android-agent/
├── app/                          # Main application
│   └── src/main/java/com/apex/agent/
│       ├── core/                # Core modules
│       ├── data/burstmode/      # Burst Mode core
│       └── ui/                  # UI components
├── ai-terminal/                 # AI terminal module
├── mnn/                         # MNN inference engine
├── llama/                       # Llama.cpp framework
├── engine/                      # Ubuntu environment
└── examples/                    # Example code
```

### Tech Stack

| Domain | Technologies |
|--------|--------------|
| **Languages** | Kotlin, Java, JavaScript, C++ |
| **UI Framework** | Jetpack Compose |
| **Database** | ObjectBox, SQLite |
| **AI Inference** | MNN, Llama.cpp, GGUF |
| **JS Engine** | QuickJS |
| **Build Tool** | Gradle 8.x |

---

## 📚 Documentation

### 🚀 Core Documents

- [Contribution Guide](docs/CONTRIBUTING.md)
- [Plugin System Docs](plugin-system/README.md)

### 🔥 Specialized Guides

- [Llama C++ Engine Guide](app/src/main/cpp/llama/README.md)
- [Performance Optimization Guide](PERFORMANCE_OPTIMIZATION_SUMMARY.md)
- [Quality Assurance Guide](docs/QUALITY_ASSURANCE_GUIDE.md)

---

## 🤝 Contributing

All forms of contributions welcome!

1. Fork this repository
2. Create feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to branch (`git push origin feature/AmazingFeature`)
5. Open Pull Request

See: [Contribution Guide](docs/CONTRIBUTING.md)

---

## 💖 Support Development

If Apex-Agent has been helpful to you, you can voluntarily support ongoing development:

- International users: [Patreon](https://www.patreon.com/c/apex)
- Chinese users: [Afdian](https://afdian.com/a/apex)

Support is entirely voluntary and doesn't unlock any feature limits. You can also use the Sponsor button at the top of this repository.

---

## 📄 License

This project is licensed under [GNU LGPLv3](https://www.gnu.org/licenses/lgpl-3.0.html).

In simple terms:
- You are free to use, modify, and distribute this project
- If you modify and distribute, you must open-source your changes under LGPLv3
- See [LICENSE](LICENSE) for more details

---

## 📞 Contact

- **Email**: mjh4117222@gmail.com
- **QQ Group**: [Join](https://qm.qq.com/q/Sa4fKEH7sO)
- **Discord**: [Join Community](https://discord.gg/YnV9MWurRF)
- **Issues**: [Feedback](https://github.com/mengjinghao/Apex-agent/issues)

---

&lt;div align="center"&gt;
  &lt;h3&gt;⭐ If you find this project helpful, please give us a Star! ⭐&lt;/h3&gt;
  &lt;p&gt;&lt;b&gt;🚀 Help us promote and let more people discover Apex-Agent 🚀&lt;/b&gt;&lt;/p&gt;
  &lt;br&gt;
  &lt;sub&gt;Made with ❤️ by Apex-Agent Team&lt;/sub&gt;
&lt;/div&gt;

