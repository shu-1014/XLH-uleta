# 🐾星棱狐🐾 推到自己的 GitHub 并在 Actions 里编译出 APK
#
# 全程走 GitHub REST API，不需要装 git！
#
# 用法（在你自己的 PowerShell 里）：
#   .\push-to-github.ps1 -Repo 你的用户名/仓库名 -Token ghp_xxxxxxxx
#   .\push-to-github.ps1 -Repo 你的用户名/starfox -Token github_pat_xxx -Private
#   .\push-to-github.ps1 -Repo 你的用户名/starfox -Token xxx -Build    # 推完自动触发编译并等结果
#
# Token 只要这两类权限就够：
#   classic PAT : repo + workflow
#   fine-grained: Contents(RW) + Workflows(RW) + Actions(R) + Metadata(R)
#   去 https://github.com/settings/tokens 生成

param(
    [Parameter(Mandatory = $true)]
    [string]$Repo,          # owner/name

    [Parameter(Mandatory = $true)]
    [string]$Token,

    [string]$Branch = "main",
    [string]$Message = "🐾星棱狐🐾 初始化：com.iki.xlh + 自研识别 + 一键编译",
    [switch]$Private,
    [switch]$Build,
    [switch]$SkipUpload
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ApiBase = "https://api.github.com"
$Headers = @{
    "Authorization"        = "Bearer $Token"
    "Accept"               = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"           = "starfox-pusher"
}

function Invoke-GH {
    param(
        [string]$Method = "GET",
        [string]$Path,
        $Body = $null,
        [switch]$Raw
    )
    $uri = if ($Path -like "http*") { $Path } else { "$ApiBase$Path" }
    $params = @{ Method = $Method; Uri = $uri; Headers = $Headers; TimeoutSec = 180 }
    if ($Body -ne $null) {
        $params.Body = ($Body | ConvertTo-Json -Depth 12 -Compress)
        $params.ContentType = "application/json; charset=utf-8"
    }
    if ($Raw) { return Invoke-WebRequest @params }
    return Invoke-RestMethod @params
}

# ---------------------------------------------------------------- 0. 校验身份
Write-Host "[0/7] 校验 token ..." -ForegroundColor Cyan
try {
    $me = Invoke-GH -Path "/user"
} catch {
    throw "token 无效：$($_.Exception.Message)"
}
Write-Host "      登录为：$($me.login)" -ForegroundColor Green

$parts = $Repo.Split('/')
if ($parts.Count -ne 2) { throw "-Repo 要写成 owner/repo，你给的是：$Repo" }
$owner = $parts[0]
$name = $parts[1]
$full = "$owner/$name"

# ---------------------------------------------------------------- 1. 建仓库
Write-Host "[1/7] 准备仓库 $full ..." -ForegroundColor Cyan
$repoExists = $false
try {
    Invoke-GH -Path "/repos/$full" | Out-Null
    $repoExists = $true
    Write-Host "      仓库已存在，直接往上推" -ForegroundColor Green
} catch {
    Write-Host "      仓库不存在，创建中 ..." -ForegroundColor Yellow
}

if (-not $repoExists) {
    $api = if ($owner -eq $me.login) { "/user/repos" } else { "/orgs/$owner/repos" }
    try {
        Invoke-GH -Method POST -Path $api -Body @{
            name          = $name
            private       = [bool]$Private
            auto_init     = $false
            description   = "🐾星棱狐🐾 — Android Root 管理器 (com.iki.xlh)"
        } | Out-Null
        Write-Host "      建好了" -ForegroundColor Green
        Start-Sleep -Seconds 3
    } catch {
        throw "建仓库失败：$($_.Exception.Message)"
    }
}

if (-not $SkipUpload) {

    # ---------------------------------------------------------------- 2. 收集文件
    Write-Host "[2/7] 收集文件 ..." -ForegroundColor Cyan

    $excludeDirs = @('\.git\\', '\\build\\', '\.gradle\\', '\.toolchain\\', '\\node_modules\\', '\\dist\\', '\.idea\\', '\\target\\')
    $excludeExt  = @('.apk', '.zip', '.aab')

    $all = Get-ChildItem -Path $root -Recurse -File | Where-Object {
        $p = $_.FullName
        $bad = $false
        foreach ($d in $excludeDirs) { if ($p -match $d) { $bad = $true; break } }
        if (-not $bad -and $excludeExt -contains $_.Extension.ToLower()) { $bad = $true }
        -not $bad
    }

    $list = @()
    foreach ($f in $all) {
        $rel = $f.FullName.Substring($root.Length).TrimStart('\', '/') -replace '\\', '/'
        $list += [pscustomobject]@{ Path = $rel; File = $f }
    }

    $totalBytes = ($list | ForEach-Object { $_.File.Length } | Measure-Object -Sum).Sum
    $totalMb = [math]::Round($totalBytes / 1MB, 2)
    Write-Host ("      共 {0} 个文件，{1} MB" -f $list.Count, $totalMb) -ForegroundColor Green

    # ---------------------------------------------------------------- 3. 建 blob
    Write-Host "[3/7] 上传文件内容（逐个 blob，慢一点但稳）..." -ForegroundColor Cyan
    $treeEntries = New-Object System.Collections.ArrayList
    $i = 0
    foreach ($item in $list) {
        $i++
        if ($i % 50 -eq 0 -or $i -eq $list.Count) {
            Write-Host ("      $i / " + $list.Count) -ForegroundColor DarkGray
        }

        $bytes = [System.IO.File]::ReadAllBytes($item.File.FullName)
        $b64 = [System.Convert]::ToBase64String($bytes)

        $delay = 0
        while ($true) {
            try {
                $blob = Invoke-GH -Method POST -Path "/repos/$full/git/blobs" -Body @{
                    content  = $b64
                    encoding = "base64"
                }
                break
            } catch {
                $msg = $_.Exception.Message
                if ($msg -match 'rate limit|429|403' -and $delay -lt 5) {
                    $delay++
                    Write-Host "      被限流，等 60 秒重试 ..." -ForegroundColor Yellow
                    Start-Sleep -Seconds 60
                    continue
                }
                throw "上传 $($item.Path) 失败：$msg"
            }
        }

        $mode = "100644"
        if ($item.Path -match '\.(sh|py|bat|ps1)$') { $mode = "100755" }
        $null = $treeEntries.Add(@{
            path = $item.Path
            mode = $mode
            type = "blob"
            sha  = $blob.sha
        })
    }

    # ---------------------------------------------------------------- 4. 建 tree
    Write-Host "[4/7] 建目录树 ..." -ForegroundColor Cyan
    $tree = Invoke-GH -Method POST -Path "/repos/$full/git/trees" -Body @{
        tree = @($treeEntries)
    }
    Write-Host "      tree = $($tree.sha.Substring(0,7))" -ForegroundColor Green

    # ---------------------------------------------------------------- 5. 建 commit
    Write-Host "[5/7] 建 commit ..." -ForegroundColor Cyan
    $parents = @()
    try {
        $oldRef = Invoke-GH -Path "/repos/$full/git/ref/heads/$Branch"
        $parents = @($oldRef.object.sha)
        Write-Host "      接在已有的 $Branch 后面" -ForegroundColor DarkGray
    } catch {
        Write-Host "      $Branch 是新的，从头开始" -ForegroundColor DarkGray
    }

    $commitBody = @{ message = $Message; tree = $tree.sha }
    if ($parents.Count -gt 0) { $commitBody.parents = $parents }
    $commit = Invoke-GH -Method POST -Path "/repos/$full/git/commits" -Body $commitBody
    Write-Host "      commit = $($commit.sha.Substring(0,7))" -ForegroundColor Green

    # ---------------------------------------------------------------- 6. 更新分支
    Write-Host "[6/7] 更新 $Branch ..." -ForegroundColor Cyan
    if ($parents.Count -gt 0) {
        Invoke-GH -Method PATCH -Path "/repos/$full/git/refs/heads/$Branch" -Body @{ sha = $commit.sha; force = $true } | Out-Null
    } else {
        Invoke-GH -Method POST -Path "/repos/$full/git/refs" -Body @{ ref = "refs/heads/$Branch"; sha = $commit.sha } | Out-Null
    }
    Write-Host "      推完了 → https://github.com/$full" -ForegroundColor Green

    # 设默认分支（新仓库才需要）
    try {
        Invoke-GH -Method PATCH -Path "/repos/$full" -Body @{ default_branch = $Branch } | Out-Null
    } catch { }
}

# ---------------------------------------------------------------- 7. 触发编译
Write-Host "[7/7] 触发 Actions 编译 ..." -ForegroundColor Cyan
if (-not $Build) {
    Write-Host "      没加 -Build，跳过。要现在就编，加 -Build 重跑一次。" -ForegroundColor Yellow
    Write-Host "      或者手动开：https://github.com/$full/actions" -ForegroundColor DarkGray
    return
}

Start-Sleep -Seconds 5

$wf = "build-release.yml"
try {
    Invoke-GH -Method POST -Path "/repos/$full/actions/workflows/$wf/dispatches" -Body @{
        ref = $Branch
        inputs = @{ ko_repo = ""; ko_tag = "lkm-latest" }
    } | Out-Null
    Write-Host "      已触发 $wf" -ForegroundColor Green
} catch {
    throw "触发失败：$($_.Exception.Message)（workflow 要已经在默认分支上）"
}

Write-Host "      等它跑起来 ..." -ForegroundColor DarkGray
Start-Sleep -Seconds 15

$runId = $null
for ($t = 0; $t -lt 20; $t++) {
    $runs = Invoke-GH -Path "/repos/$full/actions/workflows/$wf/runs?per_page=5"
    $run = $runs.workflow_runs | Sort-Object created_at -Descending | Select-Object -First 1
    if ($run) { $runId = $run.id; break }
    Start-Sleep -Seconds 10
}
if (-not $runId) { throw "没找到 workflow run，去网页看看" }

Write-Host "      run id = $runId" -ForegroundColor Green
Write-Host "      看着它跑（最多 45 分钟）..." -ForegroundColor DarkGray

$deadline = (Get-Date).AddMinutes(45)
$status = "queued"
while ((Get-Date) -lt $deadline) {
    $r = Invoke-GH -Path "/repos/$full/actions/runs/$runId"
    $status = $r.status
    Write-Host ("      " + $r.status + " / " + $r.conclusion) -ForegroundColor DarkGray
    if ($status -eq "completed") { break }
    Start-Sleep -Seconds 30
}

$final = Invoke-GH -Path "/repos/$full/actions/runs/$runId"
if ($final.conclusion -ne "success") {
    Write-Host ("      编译没成功：" + $final.conclusion) -ForegroundColor Red
    Write-Host "      日志：https://github.com/$full/actions/runs/$runId" -ForegroundColor Yellow
    return
}

Write-Host "      编译成功，下产物 ..." -ForegroundColor Green

$arts = Invoke-GH -Path "/repos/$full/actions/runs/$runId/artifacts"
$dest = Join-Path $root "_download"
New-Item -ItemType Directory -Force -Path $dest | Out-Null

foreach ($a in $arts.artifacts) {
    $zip = Join-Path $dest ($a.name + ".zip")
    Write-Host ("      下载 " + $a.name) -ForegroundColor Cyan
    Invoke-WebRequest -UseBasicParsing -Uri $a.archive_download_url -Headers $Headers -OutFile $zip -TimeoutSec 600
    $outDir = Join-Path $dest $a.name
    Expand-Archive -Path $zip -DestinationPath $outDir -Force
    Remove-Item $zip -Force
}

$apks = Get-ChildItem $dest -Recurse -Filter *.apk
if ($apks) {
    Write-Host ""
    Write-Host "=============================================" -ForegroundColor Green
    Write-Host "  APK 到手了 🐾星棱狐🐾" -ForegroundColor Green
    Write-Host "=============================================" -ForegroundColor Green
    foreach ($a in $apks) {
        Write-Host ("  " + $a.FullName) -ForegroundColor White
        Write-Host ("  " + [math]::Round($a.Length / 1MB, 2) + " MB") -ForegroundColor DarkGray
    }
    Write-Host ""
    Write-Host "  装：" -ForegroundColor Cyan
    Write-Host ("    adb install -r `"" + $apks[0].FullName + "`"") -ForegroundColor DarkGray
} else {
    Write-Host "      没在产物里找到 apk，去网页手动下。" -ForegroundColor Yellow
}
