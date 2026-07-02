# Skill System Architecture

## Overview

The android-agent project has two skill systems that need to be unified:

1. **PackageManager Skill System** - Production-ready JS packages
   - Location: `app/src/main/assets/packages/`
   - Format: `.js` with `/* METADATA */` comment header
   - Category: System, Automatic, Toolbox

2. **SkillManager Skill System** - User-defined SKILL.md files
   - Location: `Download/Apex/skills/`
   - Format: `.md` with YAML frontmatter
   - Category: User, Custom

## Unified Architecture

```
SkillSystem
├── AssetSkillProvider
│   └── Loads built-in JS skill packages
├── UserSkillProvider
│   └── Loads user-defined SKILL.md files
├── SkillRegistry
│   └── Unified registry for all skills
├── SkillDiscoverer
│   └── Scans for new/updated skills
└── SkillRunner
    └── Executes skill packages
```

## Skill Package Format (Unified)

### JS Skill Package (Production)

```javascript
/* METADATA
{
  "name": "package_name",
  "display_name": {
    "zh": "中文显示名",
    "en": "English Display Name"
  },
  "description": {
    "zh": "功能描述",
    "en": "Description"
  },
  "version": "1.0.0",
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "tool_name",
      "description": {"zh": "工具描述", "en": "Tool description"},
      "parameters": [
        {
          "name": "param_name",
          "description": {"zh": "参数描述", "en": "Param desc"},
          "type": "string",
          "required": true
        }
      ]
    }
  ]
}
*/

// JS implementation here
```

### SKILL.md (User/Trae IDE)

```markdown
---
name: "android_assistant"
display_name: {
  zh: "Android 助手",
  en: "Android Assistant"
}
description: {
  zh: "Android 综合助手，提供 APK 分析、UI 自动化、设备管理、性能调优等功能。",
  en: "Comprehensive Android assistant for APK analysis, UI automation, device management, performance tuning and more."
}
version: "1.0.0"
enabledByDefault: true
category: "System"
---

# Android Assistant

## Usage

...
```

## Directory Structure

```
.
├── app
│   └── src
│       └── main
│           └── assets
│               └── packages/
│                   ├── android_assistant.ts (New)
│                   ├── browser.ts
│                   └── ...
└── .trae
    └── skills/
        └── android-assistant/
            └── SKILL.md (Trae IDE format)
```

## Skill Features

### Required Fields
- `name` - Unique identifier
- `display_name` - Display name in multiple languages
- `description` - Description in multiple languages
- `version` - Semantic version
- `category` - System/Automatic/Toolbox/User/Custom

### Optional Fields
- `enabledByDefault` - Auto-imported on first launch
- `tools` - Defined tool APIs
- `author` - Author information
- `dependencies` - Other required skills

## Migration Guide

### From Trae IDE SKILL.md to JS Package

1. Copy SKILL.md metadata to JS comment format
2. Add JS implementation
3. Create file in `app/src/main/assets/packages/`

Example:

```javascript
/* METADATA
{
  "name": "android_assistant",
  ...
}
*/
```

## Improvement Roadmap

1. **Phase 1: Unified Discovery**
   - Load from both sources
   - Detect duplicates
   - Prioritize user-defined skills

2. **Phase 2: Skill Import/Export**
   - Export JS package to SKILL.md
   - Import SKILL.md to JS package
   - Zip archive support

3. **Phase 3: Development Tools**
   - Skill template generator
   - Metadata validator
   - Debug console
