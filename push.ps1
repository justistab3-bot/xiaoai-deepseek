param(
    [string]$GitHubToken = "",
    [string]$GiteeToken  = "",
    [string]$RepoName    = "xiaoai-deepseek",
    [string]$Tag         = "v1.0.0"
)

$ErrorActionPreference = 'Stop'
$proj = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $proj ".git"))) { $proj = $PSScriptRoot }
Set-Location $proj

$desc = "让小爱触屏音箱用上自己的 AI，前端零改动"
$zip  = Join-Path $proj "release\xiaoai-deepseek-$Tag.zip"

function Log($m) { Write-Host $m -ForegroundColor Cyan }

# ============ GitHub ============
if ($GitHubToken) {
    Log "`n=== GitHub ==="
    $h = @{ Authorization = "token $GitHubToken"; "User-Agent" = "xiaoai-deepseek" }
    try {
        $me = Invoke-RestMethod -Uri "https://api.github.com/user" -Headers $h -TimeoutSec 30
        $user = $me.login
        Log "账号: $user"

        $body = @{ name = $RepoName; description = $desc; private = $false; has_issues = $true } | ConvertTo-Json
        try {
            Invoke-RestMethod -Uri "https://api.github.com/user/repos" -Method Post -Headers $h `
                -Body $body -ContentType "application/json" -TimeoutSec 30 | Out-Null
            Log "仓库已创建"
        } catch {
            Log "仓库已存在或创建失败，继续推送"
        }

        $remote = "https://$GitHubToken@github.com/$user/$RepoName.git"
        git remote remove github 2>$null
        git remote add github $remote
        git push -u github main --force 2>&1 | Select-Object -Last 3

        Log "创建 release $Tag ..."
        $rel = @{
            tag_name = $Tag
            name     = "小爱音箱接入自建大模型 $Tag"
            body     = "详见 README。下载下方 zip 解压即用。"
            draft    = $false
            prerelease = $false
        } | ConvertTo-Json

        $r = Invoke-RestMethod -Uri "https://api.github.com/repos/$user/$RepoName/releases" `
            -Method Post -Headers $h -Body $rel -ContentType "application/json" -TimeoutSec 30
        Log "release 已创建: $($r.html_url)"

        if (Test-Path $zip) {
            $uploadUrl = ($r.upload_url -replace '\{.*\}', '') + "?name=xiaoai-deepseek-$Tag.zip"
            $bytes = [System.IO.File]::ReadAllBytes($zip)
            Invoke-RestMethod -Uri $uploadUrl -Method Post `
                -Headers @{ Authorization = "token $GitHubToken"; "User-Agent" = "xiaoai-deepseek" } `
                -Body $bytes -ContentType "application/zip" -TimeoutSec 120 | Out-Null
            Log "附件已上传"
        }
        Log "GitHub 完成: https://github.com/$user/$RepoName"
    } catch {
        Write-Host "GitHub 失败: $($_.Exception.Message)" -ForegroundColor Red
    }
}

# ============ Gitee ============
if ($GiteeToken) {
    Log "`n=== Gitee ==="
    try {
        $me = Invoke-RestMethod -Uri "https://gitee.com/api/v5/user?access_token=$GiteeToken" -TimeoutSec 30
        $user = $me.login
        Log "账号: $user"

        $body = @{
            access_token = $GiteeToken
            name         = $RepoName
            description  = $desc
            private      = "false"
            auto_init    = "false"
        }
        try {
            Invoke-RestMethod -Uri "https://gitee.com/api/v5/user/repos" -Method Post `
                -Body $body -TimeoutSec 30 | Out-Null
            Log "仓库已创建"
        } catch {
            Log "仓库已存在或创建失败，继续推送"
        }

        $remote = "https://${user}:${GiteeToken}@gitee.com/${user}/${RepoName}.git"
        git remote remove gitee 2>$null
        git remote add gitee $remote
        git push -u gitee main --force 2>&1 | Select-Object -Last 3

        if (Test-Path $zip) {
            try {
                $relBody = @{
                    access_token = $GiteeToken
                    tag_name     = $Tag
                    name         = "小爱音箱接入自建大模型 $Tag"
                    body         = "详见 README"
                    target_commitish = "main"
                }
                $r = Invoke-RestMethod -Uri "https://gitee.com/api/v5/repos/$user/$RepoName/releases" `
                    -Method Post -Body $relBody -TimeoutSec 30
                Log "release 已创建: $($r.html_url)"

                $attBody = @{
                    access_token = $GiteeToken
                    file         = Get-Item $zip
                }
                Invoke-RestMethod -Uri "https://gitee.com/api/v5/repos/$user/$RepoName/releases/$($r.id)/attach_files" `
                    -Method Post -Form $attBody -TimeoutSec 120 | Out-Null
                Log "附件已上传"
            } catch {
                Log "Gitee release 失败（不影响代码推送）: $($_.Exception.Message)"
            }
        }
        Log "Gitee 完成: https://gitee.com/$user/$RepoName"
    } catch {
        Write-Host "Gitee 失败: $($_.Exception.Message)" -ForegroundColor Red
    }
}

if (-not $GitHubToken -and -not $GiteeToken) {
    Write-Host "用法：" -ForegroundColor Yellow
    Write-Host "  .\push.ps1 -GitHubToken ghp_xxx"
    Write-Host "  .\push.ps1 -GiteeToken xxx"
    Write-Host "  .\push.ps1 -GitHubToken ghp_xxx -GiteeToken xxx"
    Write-Host ""
    Write-Host "Token 获取："
    Write-Host "  GitHub: https://github.com/settings/tokens  (勾 repo 权限)"
    Write-Host "  Gitee : https://gitee.com/profile/personal_access_tokens  (勾 projects 权限)"
}
