#!/usr/bin/env pwsh
# scan_incomplete.ps1
# 扫描「代码不全」模式，输出 Markdown 到 stdout。
[CmdletBinding()]
param(
    [string]$Root = "."
)

$patterns = @(
    @{ Name = "EmptyCatch";  Re = "catch\s*\([^)]+\)\s*\{\s*\}" },
    @{ Name = "Truncated";   Re = "//\s*\.\.\.|//\s*TODO" },
    @{ Name = "NotBang";     Re = "!!" }
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
                    File    = $rel
                    Line    = $i + 1
                    Pattern = $p.Name
                    Snippet = $lines[$i].Trim()
                }
            }
        }
    }
}

Write-Host "## 代码不全扫描结果"
Write-Host ""
Write-Host "- 命中文件数: $($results | Select-Object -ExpandProperty File -Unique | Count)"
Write-Host "- 总命中行数: $($results.Count)"
Write-Host ""
$results | Group-Object Pattern | ForEach-Object {
    Write-Host "### $($_.Name) ($($_.Count) 处)"
    Write-Host ""
    $_.Group | Select-Object -First 30 | ForEach-Object {
        Write-Host "- $($_.File):$($_.Line)  ``$($_.Snippet)``"
    }
    Write-Host ""
}
