# 🐾星棱狐🐾 内核模块流水线
#
# 三个任务：
#   .\ko-pipeline.ps1 -Task Rename                把内核模块名 kernelsu -> xlh（会先 dry-run）
#   .\ko-pipeline.ps1 -Task Rename -Apply         真改
#   .\ko-pipeline.ps1 -Task Fetch -Repo you/repo  从你的仓库拉编译好的 .ko
#   .\ko-pipeline.ps1 -Task Status                看当前识别到了什么

param(
    [ValidateSet("Rename", "Fetch", "Status")]
    [string]$Task = "Status",

    [string]$Repo = "",
    [string]$Tag  = "lkm-latest",
    [switch]$Apply
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$kernelDir = Join-Path $root "kernel"

function Get-Text([string]$p) {
    return [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($p))
}
function Set-Text([string]$p, [string]$t) {
    [System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($false)))
}

# ---------------------------------------------------------------- Rename
function Invoke-Rename {
    Write-Host "== 内核模块改名：kernelsu -> xlh ==" -ForegroundColor Cyan
    Write-Host "   扫描目录：$kernelDir"

    # 只动内核侧这些符号，用户态 / APK 的 libkernelsu.so 不碰
    $patterns = @(
        'kernelsu_init',
        'kernelsu_exit',
        'kernelsu_cache',
        'MODULE_NAME\s+"kernelsu"'
    )

    $files = Get-ChildItem $kernelDir -Recurse -Include *.c,*.h,Kbuild,Makefile,*.mk -File
    $hits = @()
    foreach ($f in $files) {
        $t = Get-Text $f.FullName
        foreach ($pat in $patterns) {
            if ($t -match $pat) {
                $hits += [pscustomobject]@{ File = $f.FullName; Pattern = $pat }
            }
        }
    }

    if ($hits.Count -eq 0) {
        Write-Host "   没找到需要改的点。" -ForegroundColor Green
        return
    }

    $hits | Format-Table -AutoSize

    if (-not $Apply) {
        Write-Host ""
        Write-Host "   [dry-run] 加 -Apply 才会真改。改完记得同步：" -ForegroundColor Yellow
        Write-Host "   1) manager 的 StarFoxDetector.KNOWN_MODULE_NAMES 里加上 xlh"
        Write-Host "   2) userspace/ksud 里 modprobe 的模块名"
        Write-Host "   3) 重新编内核，把 .ko 传到自己的仓库"
        return
    }

    foreach ($f in ($hits.File | Select-Object -Unique)) {
        $t = Get-Text $f
        $t = $t -replace 'kernelsu_init', 'xlh_init'
        $t = $t -replace 'kernelsu_exit', 'xlh_exit'
        $t = $t -replace 'kernelsu_cache', 'xlh_cache'
        $t = $t -replace 'MODULE_NAME\s+"kernelsu"', 'MODULE_NAME "xlh"'
        Set-Text $f $t
        Write-Host "   已改：$f" -ForegroundColor Green
    }
    Write-Host "   改完了，重新编内核。" -ForegroundColor Green
}

# ---------------------------------------------------------------- Fetch
function Invoke-Fetch {
    if ([string]::IsNullOrWhiteSpace($Repo)) {
        Write-Host "需要 -Repo owner/repo，例如：.\ko-pipeline.ps1 -Task Fetch -Repo myname/my-kernel" -ForegroundColor Yellow
        return
    }
    $dest = Join-Path $root "manager\app\src\main\assets\lkm"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null

    $ProgressPreference = "SilentlyContinue"
    $api = "https://api.github.com/repos/$Repo/releases/tags/$Tag"
    Write-Host "== 从 $Repo @ $Tag 拉 .ko ==" -ForegroundColor Cyan

    try {
        $rel = Invoke-RestMethod -Uri $api -Headers @{ "User-Agent" = "starfox" } -TimeoutSec 40
    } catch {
        Write-Host "   拿不到 Release：$($_.Exception.Message)" -ForegroundColor Red
        Write-Host "   换个 -Tag，或者先在你的仓库发个 Release 带上 .ko。" -ForegroundColor Yellow
        return
    }

    $assets = $rel.assets | Where-Object { $_.name -like "*.ko" }
    if (-not $assets) {
        Write-Host "   这个 Release 里没有 .ko。" -ForegroundColor Yellow
        return
    }

    foreach ($a in $assets) {
        $out = Join-Path $dest $a.name
        Invoke-WebRequest -Uri $a.browser_download_url -OutFile $out -TimeoutSec 120
        Write-Host ("   下载 " + $a.name + "  " + [math]::Round((Get-Item $out).Length/1KB,1) + " KB") -ForegroundColor Green
    }
    Write-Host "   落在：$dest" -ForegroundColor Green
}

# ---------------------------------------------------------------- Status
function Invoke-Status {
    Write-Host "== 本机识别状态 ==" -ForegroundColor Cyan
    Write-Host "   设备要走 adb，这里只看仓库侧配置"
    Write-Host ""

    $detector = Join-Path $root "manager\app\src\main\java\com\iki\xlh\starfox\StarFoxDetector.kt"
    if (Test-Path $detector) {
        $t = Get-Text $detector
        $m = [regex]::Match($t, 'KNOWN_MODULE_NAMES\s*=\s*listOf\(([\s\S]*?)\)')
        if ($m.Success) {
            Write-Host "   认的模块名：" -ForegroundColor Green
            $m.Groups[1].Value -split "`n" | ForEach-Object {
                $s = $_.Trim().TrimEnd(',')
                if ($s -and -not $s.StartsWith("//")) { Write-Host "     $s" }
            }
        }
    }

    $assets = Join-Path $root "manager\app\src\main\assets\lkm"
    if (Test-Path $assets) {
        $ko = Get-ChildItem $assets -Filter *.ko
        if ($ko) {
            Write-Host ""
            Write-Host "   已就位的 .ko：" -ForegroundColor Green
            $ko | ForEach-Object { Write-Host ("     " + $_.Name + "  " + [math]::Round($_.Length/1KB,1) + " KB") }
        } else {
            Write-Host ""
            Write-Host "   assets\lkm 里还没有 .ko" -ForegroundColor Yellow
        }
    } else {
        Write-Host ""
        Write-Host "   assets\lkm 目录还没建，跑一次 Fetch 就有了。" -ForegroundColor Yellow
    }
}

switch ($Task) {
    "Rename" { Invoke-Rename }
    "Fetch"  { Invoke-Fetch }
    "Status" { Invoke-Status }
}
