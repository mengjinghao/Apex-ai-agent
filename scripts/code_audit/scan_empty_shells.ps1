#!/usr/bin/env pwsh
# scan_empty_shells.ps1
# 扫描「代码空壳」模式，输出 Markdown 到 stdout。
# 用法：在仓库根目录执行  pwsh scripts/code_audit/scan_empty_shells.ps1
[CmdletBinding()]
param(
    [string]$Root = "."
)

$ErrorActionPreference = "Continue"
$patterns = @(
    @{ Name = "TODO";           Re = "TODO\(|TODO\(" },
    @{ Name = "NotImplemented"; Re = "NotImplementedError|not implemented|not yet implemented" },
    @{ Name = "EmptyBody";      Re = "fun\s+\w+\s*\([^)]*\)\s*\{\s*\}" },
    @{ Name = "Placeholder";    Re = "//\s*(占位|稍后实现|先这样)|//\s*placeholder" }
)

$ktFiles = Get-ChildItem -Path $Root -Recurse -Include *.kt,*.java -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\build\\' -and $_.FullName -notmatch '\\third_party\\' }

$results = @()
foreach ($f in $ktFiles) {
    $rel = $f.FullName.Substring((Resolve-Path $Root).Path.Length).TrimStart('\','/')
    $lines = Get-Content $f.FullName -Encoding UTF8
    for ($i = 0; $i -lt $lines.Count; $i++) {
        foreach ($p in $patterns) {
            if ($lines[$i] -match $p.Re) {
                $results += [pscustomobject]@{
                    File     = $rel
                    Line     = $i + 1
                    Pattern  = $p.Name
                    Snippet  = $lines[$i].Trim()
                }
            }
        }
    }
}

Write-Host "## 空壳扫描结果 (Kotlin/Java)"
Write-Host ""
Write-Host "- 命中文件数: $($results | Select-Object -ExpandProperty File -Unique | Count)"
Write-Host "- 总命中行数: $($results.Count)"
Write-Host ""
$results | Group-Object Pattern | ForEach-Object {
    Write-Host "### $($_.Name) ($($_.Count) 处)"
    Write-Host ""
    $_.Group | Select-Object -First 20 | ForEach-Object {
        Write-Host "- $($_.File):$($_.Line)  ``$($_.Snippet)``"
    }
    Write-Host ""
}
