#!/usr/bin/env pwsh
# run_audit.ps1
# 一键运行空壳/不全/逻辑错误三个扫描脚本，并把结果汇总到 docs/CODE_AUDIT_REPORT.md 的附录中。
[CmdletBinding()]
param(
    [string]$Root = ".",
    [string]$Out   = "docs/CODE_AUDIT_REPORT.md"
)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Write-Host "==> scan_empty_shells ..."
& pwsh "$here/scan_empty_shells.ps1" $Root
Write-Host ""
Write-Host "==> scan_incomplete ..."
& pwsh "$here/scan_incomplete.ps1" $Root
Write-Host ""
Write-Host "==> scan_logic_errors ..."
& pwsh "$here/scan_logic_errors.ps1" $Root
Write-Host ""
Write-Host "完成。三段输出可粘贴到 $Out 的「再跑结果」附录。"
