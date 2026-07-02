#!/bin/bash
# scan_incomplete.sh
set -u
ROOT="${1:-.}"
SKIP='-path "*/build/*" -o -path "*/third_party/*" -o -path "*/dist/*" -o -path "*/node_modules/*" -o -path "*/.gradle/*"'

echo "## 代码不全扫描结果"
echo ""

HITS=$(find "$ROOT" \( $SKIP \) -prune -o \( -name "*.kt" -o -name "*.java" \) -type f -print 2>/dev/null | xargs grep -nE "catch\s*\([^)]+\)\s*\{\s*\}|//\s*\.\.\." 2>/dev/null || true)

if [ -z "$HITS" ]; then
    echo "- 命中: 0"
    exit 0
fi

COUNT=$(echo "$HITS" | wc -l)
FILES=$(echo "$HITS" | cut -d: -f1 | sort -u | wc -l)
echo "- 命中文件数: $FILES"
echo "- 总命中行数: $COUNT"
echo ""
echo "$HITS" | head -200 | awk -F: '{ printf "- %s:%s  `%s`\n",$1,$2,substr($0, index($0,$3)) }'
