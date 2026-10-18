param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Key,
    [string]$Model = "deepseek-chat",
    [string]$Url = "https://api.deepseek.com/v1/chat/completions",
    [string]$Adb = "C:\Users\Tab3\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Adb)) { $Adb = "adb" }

$cfg = [ordered]@{
    enabled              = $true
    probe_url            = $false
    dump_inst            = $false
    llm_url              = $Url
    llm_key              = $Key
    llm_model            = $Model
    sys_prompt           = "你是 DeepSeek，由深度求索开发的 AI 助手。回答口语化、简短、自然，通常一两句话，不要用 Markdown、列表、代码块、表情符号，因为你说的话会被直接念出来。你能通过工具控制家里的智能设备。"
    history              = 6
    passthrough_keywords = ""
    debug                = $true
}
$json = $cfg | ConvertTo-Json -Depth 4

$tmp = Join-Path $env:TEMP "xiaoai_bridge.json"
[System.IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding($false)))

& $Adb push $tmp /sdcard/xiaoai_bridge.json
& $Adb shell "am force-stop com.xiaomi.soundboxassist"

Write-Host ""
Write-Host "已写入配置并重启小爱进程。" -ForegroundColor Green
Write-Host "现在对着音箱说：小爱同学，你叫什么名字" -ForegroundColor Cyan
Write-Host "看日志： $Adb shell cat /sdcard/xiaoai_bridge.log"
