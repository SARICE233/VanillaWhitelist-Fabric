<#
.SYNOPSIS
  VanillaWhitelist 三端一致性校验（发布前把关）

.DESCRIPTION
  三个实现（Paper / Fabric / NeoForge）相互独立，但必须同源同名。
  本脚本校验它们是否仍然保持一致：
    1. 三份 PROTOCOL.md 内容完全一致
    2. 三端 PROTOCOL_VERSION 相等
    3. 三端 IMPL 分别为 paper / fabric / neoforge
    4. 三端版本号一致

.EXAMPLE
  .\check-protocol.ps1
#>
param(
    [string]$Paper,
    [string]$Fabric,
    [string]$NeoForge
)

$ErrorActionPreference = 'Stop'
$parent = Split-Path $PSScriptRoot -Parent
if (-not $Paper)    { $Paper    = Join-Path $parent 'VanillaWhitelist' }
if (-not $Fabric)   { $Fabric   = Join-Path $parent 'VanillaWhitelist-Fabric' }
if (-not $NeoForge) { $NeoForge = Join-Path $parent 'VanillaWhitelist-NeoForge' }

$dirs = @{ paper = $Paper; fabric = $Fabric; neoforge = $NeoForge }
$problems = New-Object System.Collections.Generic.List[string]
$keys = @('paper','fabric','neoforge')

function Say($msg, $color) { Write-Host $msg -ForegroundColor $color }

Say 'VanillaWhitelist 三端一致性校验' 'Cyan'
Say '' 'Gray'

$missing = $false
foreach ($k in $keys) {
    if (-not (Test-Path $dirs[$k])) {
        Say ('  [FAIL] 找不到 ' + $k + ' 仓库: ' + $dirs[$k]) 'Red'
        $missing = $true
    }
}
if ($missing) { exit 1 }

Say '[1] PROTOCOL.md 三份内容一致' 'White'
$hashes = @{}
foreach ($k in $keys) {
    $p = Join-Path $dirs[$k] 'PROTOCOL.md'
    if (Test-Path $p) { $hashes[$k] = (Get-FileHash $p -Algorithm SHA256).Hash }
    else { $problems.Add($k + ' 缺少 PROTOCOL.md') }
}
if ($hashes.Count -eq 3) {
    $uniq = @($hashes.Values | Select-Object -Unique)
    if ($uniq.Count -eq 1) {
        Say ('  [OK]   三份一致  SHA256=' + $uniq[0].Substring(0,16) + '...') 'Green'
    } else {
        foreach ($k in $keys) { Say ('         ' + $k.PadRight(9) + $hashes[$k].Substring(0,16) + '...') 'Gray' }
        $problems.Add('PROTOCOL.md 三份内容不一致 —— 改协议时必须三端同步')
    }
}

Say '[2] PROTOCOL_VERSION 与 IMPL' 'White'
$src = @{
    paper    = (Join-Path $dirs['paper']    'src/main/kotlin/com/vanillawhitelist/plugin/Protocol.kt')
    fabric   = (Join-Path $dirs['fabric']   'src/main/java/com/vanillawhitelist/VanillaWhitelistMod.java')
    neoforge = (Join-Path $dirs['neoforge'] 'src/main/java/com/vanillawhitelist/VanillaWhitelistMod.java')
}
$pv = @{}
$impl = @{}
foreach ($k in $keys) {
    $f = $src[$k]
    if (-not (Test-Path $f)) { $problems.Add($k + ' 找不到 ' + $f); continue }
    $text = Get-Content $f -Raw
    $m = [regex]::Match($text, 'PROTOCOL_VERSION\s*=\s*(\d+)')
    if ($m.Success) { $pv[$k] = [int]$m.Groups[1].Value } else { $problems.Add($k + ' 中未找到 PROTOCOL_VERSION') }
    $m2 = [regex]::Match($text, 'IMPL\s*=\s*"([a-z]+)"')
    if ($m2.Success) { $impl[$k] = $m2.Groups[1].Value } else { $problems.Add($k + ' 中未找到 IMPL') }
}
$pvU = @($pv.Values | Select-Object -Unique)
if ($pv.Count -eq 3 -and $pvU.Count -eq 1) {
    Say ('  [OK]   PROTOCOL_VERSION 三端一致 = ' + $pvU[0]) 'Green'
} elseif ($pv.Count -gt 0) {
    $problems.Add('PROTOCOL_VERSION 不一致: ' + (($pv.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value }) -join ', '))
}
$implOk = $true
foreach ($k in $keys) {
    if (-not $impl.ContainsKey($k) -or $impl[$k] -ne $k) {
        $implOk = $false
        $problems.Add($k + ' 的 IMPL 应为 ' + $k + '，实际 ' + $impl[$k])
    }
}
if ($implOk) { Say '  [OK]   IMPL 三端分别为 paper / fabric / neoforge' 'Green' }

Say '[3] 版本号一致' 'White'
$ver = @{}
$vp = [regex]::Match((Get-Content (Join-Path $dirs['paper'] 'build.gradle.kts') -Raw), '(?m)^version\s*=\s*"([^"]+)"')
if ($vp.Success) { $ver['paper'] = $vp.Groups[1].Value }
$vf = [regex]::Match((Get-Content (Join-Path $dirs['fabric'] 'gradle.properties') -Raw), '(?m)^version=(.+)$')
if ($vf.Success) { $ver['fabric'] = $vf.Groups[1].Value.Trim() }
$vn = [regex]::Match((Get-Content (Join-Path $dirs['neoforge'] 'gradle.properties') -Raw), '(?m)^mod_version=(.+)$')
if ($vn.Success) { $ver['neoforge'] = $vn.Groups[1].Value.Trim() }
$verU = @($ver.Values | Select-Object -Unique)
if ($ver.Count -eq 3 -and $verU.Count -eq 1) {
    Say ('  [OK]   三端版本一致 = ' + $verU[0]) 'Green'
} else {
    $problems.Add('版本号不一致: ' + (($ver.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value }) -join ', '))
}

Say '' 'Gray'
if ($problems.Count -eq 0) {
    Say '全部通过 —— 三端保持同源同名' 'Green'
    exit 0
} else {
    Say ('发现 ' + $problems.Count + ' 个问题:') 'Red'
    foreach ($p in $problems) { Say ('  - ' + $p) 'Red' }
    exit 1
}