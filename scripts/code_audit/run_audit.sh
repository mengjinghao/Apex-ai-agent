#!/bin/bash
# run_audit.sh
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${1:-.}"

echo "==> scan_empty_shells ..."
bash "$HERE/scan_empty_shells.sh" "$ROOT"
echo ""
echo "==> scan_incomplete ..."
bash "$HERE/scan_incomplete.sh" "$ROOT"
echo ""
echo "==> scan_logic_errors ..."
bash "$HERE/scan_logic_errors.sh" "$ROOT"
echo ""
echo "完成。三段输出可粘贴到 docs/CODE_AUDIT_REPORT.md 的「再跑结果」附录。"
