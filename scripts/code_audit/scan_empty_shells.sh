#!/bin/bash
# scan_empty_shells.sh
# 扫描「代码空壳」模式，输出 Markdown 到 stdout。
# 用法：在仓库根目录执行  bash scripts/code_audit/scan_empty_shells.sh
set -u
ROOT="${1:-.}"

# 跳过目录
SKIP='-path "*/build/*" -o -path "*/third_party/*" -o -path "*/dist/*" -o -path "*/node_modules/*" -o -path "*/.gradle/*"'

echo "## 空壳扫描结果 (Kotlin/Java)"
echo ""

# TODO/NotImplemented/占位
HITS=$(find "$ROOT" \( $SKIP \) -prune -o \( -name "*.kt" -o -name "*.java" \) -type f -print 2>/dev/null | xargs grep -nE "TODO\(|NotImplementedError|not implemented|not yet implemented|//\s*(占位|稍后实现|先这样)|//\s*placeholder" 2>/dev/null || true)

if [ -z "$HITS" ]; then
    echo "- 命中: 0"
    exit 0
fi

COUNT=$(echo "$HITS" | wc -l)
FILES=$(echo "$HITS" | cut -d: -f1 | sort -u | wc -l)
echo "- 命中文件数: $FILES"
echo "- 总命中行数: $COUNT"
echo ""
echo "### 详细命中（每文件最多 20 行）"
echo ""
echo "$HITS" | awk -F: '{ f=$1; sub(/.*\//,"",f); printf "- %s:%s  `%s`\n",$1,$2,substr($0, index($0,$3)) }' | head -200
