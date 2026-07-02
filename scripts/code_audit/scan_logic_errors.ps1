#!/usr/bin/env pwsh
# scan_logic_errors.ps1
# 扫描「逻辑错误」模式（粗筛），输出 Markdown 到 stdout。
[CmdletBinding()]
param(
    [string]$Root = "."
)

$patterns = @(
    @{ Name = "AsyncTask";   Re = "AsyncTask" },
    @{ Name = "GlobalScope"; Re = "GlobalScope\.launch" },
    @{ Name = "ApacheHTTP";  Re = "org\.apache\.http" }
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

Write-Host "## 逻辑错误粗扫结果"
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

Write-Host "## C/C++ 关键模式"
Write-Host ""
$cppFiles = Get-ChildItem -Path $Root -Recurse -Include *.cpp,*.h -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\build\\' -and $_.FullName -notmatch '\\third_party\\' -and $_.FullName -notmatch '\\streamnative\\plugins\\' }
foreach ($f in $cppFiles) {
    $rel = $f.FullName.Substring((Resolve-Path $Root).Path.Length).TrimStart('\','/')
    $lines = Get-Content $f.FullName -Encoding UTF8
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match "Not implemented|not implemented" -or
            $line -match '\breturn\s+0\s*;\s*//' -or
            $line -match '\breturn\s+nullptr\s*;\s*//' -or
            $line -match '\bJava_com_') {
            Write-Host "- $rel:$($i+1)  ``$($line.Trim())``"
        }
    }
}
