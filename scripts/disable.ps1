param(
    [string]$Adb = "C:\Users\Tab3\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Adb)) { $Adb = "adb" }

Write-Host "关闭桥接（小爱立刻回到原生状态）..."
& $Adb shell "echo '{\"enabled\":false}' > /sdcard/xiaoai_bridge.json"
& $Adb shell "am force-stop com.xiaomi.soundboxassist"
Write-Host "已关闭。要彻底卸载：" -ForegroundColor Yellow
Write-Host "  1. 打开 LSPosed 管理器，关掉「小爱DS桥接」模块"
Write-Host "  2. 卸载： adb uninstall com.yg.xiaoai.bridge"
Write-Host "  3. 或者整体移除 LSPosed： magisk --remove-modules zygisk_lsposed && adb reboot"
