#!/bin/bash
# scan_logic_errors.sh
set -u
ROOT="${1:-.}"
SKIP='-path "*/build/*" -o -path "*/third_party/*" -o -path "*/dist/*" -o -path "*/node_modules/*" -o -path "*/.gradle/*"'

echo "## 逻辑错误粗扫结果"
echo ""

HITS=$(find "$ROOT" \( $SKIP \) -prune -o \( -name "*.kt" -o -name "*.java" \) -type f -print 2>/dev/null | xargs grep -nE "AsyncTask|GlobalScope\.launch|org\.apache\.http" 2>/dev/null || true)

if [ -n "$HITS" ]; then
    echo "$HITS" | head -200 | awk -F: '{ printf "- %s:%s  `%s`\n",$1,$2,substr($0, index($0,$3)) }'
else
    echo "- 命中: 0"
fi

echo ""
echo "## C/C++ 关键模式"
echo ""
CPPHITS=$(find "$ROOT" \( $SKIP \) -prune -o \( -name "*.cpp" -o -name "*.h" \) -type f -print 2>/dev/null | xargs grep -nE "not implemented|return 0;|return nullptr;|Java_com_" 2>/dev/null | grep -v "/third_party/" | grep -v "streamnative/plugins/" || true)
if [ -n "$CPPHITS" ]; then
    echo "$CPPHITS" | head -200 | awk -F: '{ printf "- %s:%s  `%s`\n",$1,$2,substr($0, index($0,$3)) }'
else
    echo "- 命中: 0"
fi
