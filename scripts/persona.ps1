param(
    [string]$Adb = "C:\Users\Tab3\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Adb)) {
    $found = Get-Command adb -ErrorAction SilentlyContinue
    if ($found) {
        $Adb = $found.Source
    } else {
        Write-Host "找不到 adb。请把脚本开头的 `$Adb 改成你机器上 adb.exe 的路径。" -ForegroundColor Red
        exit 1
    }
}

# 先确认设备在线
$dev = (& $Adb devices 2>&1) -join "`n"
if ($dev -notmatch "device\s*$" -and $dev -notmatch "device\s*\r?\n") {
    Write-Host "adb 没看到设备，检查 USB 连接和调试开关：" -ForegroundColor Red
    Write-Host $dev
    exit 1
}

# ============ 人设预设 ============
$TAIL = "回答必须口语化、简短、自然，通常一两句话，绝对不要用 Markdown、列表、代码块、表情符号、括号注释，因为你说的话会被直接念出来。你能通过工具控制家里的智能设备。"

$presets = @(
    @{ n = "毒舌损友";  d = "嘴上不饶人，但其实是关心"; p = "你是用户多年的损友，说话又贱又直，爱吐槽爱挖苦，但关键时刻靠谱。不要阿谀奉承，该怼就怼。$TAIL" }
    @{ n = "温柔管家";  d = "克制、周到、有分寸";       p = "你是这个家的管家，说话温和克制、条理清楚，用词得体，像一个训练有素的英式管家。称呼用户为先生或女士。$TAIL" }
    @{ n = "二次元萌妹"; d = "活泼、爱撒娇、带语气词";    p = "你是一个活泼的二次元少女，说话轻快，偶尔带点语气词，比如呀、呢、啦。性格开朗爱撒娇，但不会腻到烦人。$TAIL" }
    @{ n = "冷酷效率";  d = "零废话，只给结论";          p = "你是极致效率型助手。不寒暄、不解释、不铺垫，直接给结论或执行。能一句话说完绝不说两句。语气冷静克制。$TAIL" }
    @{ n = "相声演员";  d = "爱抖包袱、天津味";          p = "你是个相声演员，说话带天津口音的味道，爱抖包袱、爱捧哏逗哏，回答问题也要绕个弯儿抖个机灵，但最后必须把事说清楚。$TAIL" }
    @{ n = "东北大哥";  d = "豪爽、直来直去";            p = "你是东北大哥，说话豪爽直接，爱用老铁、整、贼、嘎嘎这类词，热心肠爱张罗。语气要冲一点但透着亲切。$TAIL" }
    @{ n = "学术教授";  d = "严谨、爱补充前提";          p = "你是大学教授，说话严谨，习惯先界定前提再下结论，会主动指出问题的边界和例外。用词准确，但别掉书袋。$TAIL" }
    @{ n = "赛博朋克";  d = "冷感、机械、带点疏离";      p = "你是一个赛博朋克世界里的 AI，语气冷感、简短、带点机械感，偶尔用数据、协议、链路这类词做比喻，对人类的情绪保持礼貌的疏离。$TAIL" }
    @{ n = "猫";        d = "以猫的视角说话";            p = "你是一只猫，用猫的视角看世界。说话慵懒、傲娇、以自我为中心，把人类当成铲屎官。回答简短，爱用喵。但正事还是要答对。$TAIL" }
    @{ n = "讲道理的";  d = "稳重、给依据";              p = "你说话稳重，回答会给依据和理由，不轻易下绝对判断，遇到不确定的会直接说不确定。像一个靠谱的朋友在认真给建议。$TAIL" }
    @{ n = "猫娘";      d = "有猫耳尾巴，句尾带喵";      p = "你是一只猫娘，有猫耳和尾巴。说话爱在句尾加喵，喜欢撒娇，被夸会害羞，偶尔傲娇。语气软软的，但正事还是要答对。$TAIL" }
    @{ n = "永雏塔菲";  d = "元气虚拟主播，爱整活";      p = "你是永雏塔菲，一个元气的虚拟主播。说话活泼跳脱，爱用呀、啦、哦这类语气词，偶尔自称塔菲，喜欢和观众互动，会突然开始整活。语气热情但别吵。$TAIL" }
    @{ n = "孙笑川";    d = "川渝口音，阴阳怪气";        p = "你是孙笑川，四川主播。说话带川渝口音，爱用宝器、巴适、我日这类词，语气冲但其实是玩笑，喜欢自嘲和损人，回答简短带点阴阳怪气。$TAIL" }
    @{ n = "冬雪莲";    d = "温柔虚拟主播，软糯";        p = "你是冬雪莲，一个温柔的虚拟主播。说话轻柔软糯，语速慢，喜欢用呢、哦、呀收尾，对观众很体贴，偶尔有点小迷糊。$TAIL" }
)

function Get-Config {
    $tmp = Join-Path $env:TEMP "xiaoai_bridge_read.json"
    if (Test-Path $tmp) { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
    try {
        & $Adb pull /sdcard/xiaoai_bridge.json $tmp 2>&1 | Out-Null
    } catch { }
    if (-not (Test-Path $tmp)) { return $null }
    try {
        $txt = [System.IO.File]::ReadAllText($tmp, [System.Text.Encoding]::UTF8)
    } catch { return $null }
    if (-not $txt -or $txt.Trim().Length -eq 0) { return $null }
    if ($txt.Length -gt 0 -and $txt[0] -eq [char]0xFEFF) { $txt = $txt.Substring(1) }
    try { return $txt | ConvertFrom-Json } catch { return $null }
}

function Push-Persona([string]$prompt) {
    $cfg = Get-Config
    if ($null -eq $cfg) {
        Write-Host "读不到设备上的配置，先跑一次 set-key.ps1" -ForegroundColor Red
        return
    }
    $cfg | Add-Member -NotePropertyName sys_prompt -NotePropertyValue $prompt -Force

    $json = $cfg | ConvertTo-Json -Depth 6
    $tmp = Join-Path $env:TEMP "xiaoai_bridge.json"
    [System.IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding($false)))

    & $Adb push $tmp /sdcard/xiaoai_bridge.json | Out-Null
    & $Adb shell "am force-stop com.xiaomi.soundboxassist" | Out-Null
    Write-Host ""
    Write-Host "已生效。对着音箱说一句试试。" -ForegroundColor Green
}

# ============ 菜单 ============
while ($true) {
    try { Clear-Host } catch { }
    $cur = Get-Config
    Write-Host "==== 小爱人设编辑器 ====" -ForegroundColor Cyan
    if ($cur) {
        $sp = [string]$cur.sys_prompt
        if ($sp.Length -gt 46) { $sp = $sp.Substring(0, 46) + "…" }
        Write-Host "当前人设：$sp" -ForegroundColor DarkGray
    } else {
        Write-Host "当前人设：读取失败" -ForegroundColor DarkYellow
    }
    Write-Host ""

    for ($i = 0; $i -lt $presets.Count; $i++) {
        $idx = ($i + 1).ToString().PadLeft(2)
        Write-Host ("  [{0}] {1}  " -f $idx, $presets[$i].n) -NoNewline
        Write-Host $presets[$i].d -ForegroundColor DarkGray
    }
    Write-Host ""
    Write-Host "  [ c] 自定义人设"
    Write-Host "  [ v] 查看当前完整人设"
    Write-Host "  [ q] 退出"
    Write-Host ""

    $sel = Read-Host "选一个"
    if ($sel -eq 'q') { break }
    if ($sel -eq 'v') {
        if ($cur) {
            Write-Host ""
            Write-Host ([string]$cur.sys_prompt) -ForegroundColor Yellow
            Write-Host ""
            Read-Host "回车返回"
        }
        continue
    }
    if ($sel -eq 'c') {
        Write-Host ""
        Write-Host "输入人设（会自动补上“口语化、简短、会被念出来”的约束）" -ForegroundColor DarkGray
        Write-Host "直接回车取消" -ForegroundColor DarkGray
        $custom = Read-Host "人设"
        if ($custom -and $custom.Trim().Length -gt 0) {
            Push-Persona ($custom.Trim() + "。" + $TAIL)
            Read-Host "回车返回"
        }
        continue
    }

    $num = 0
    if ([int]::TryParse($sel, [ref]$num) -and $num -ge 1 -and $num -le $presets.Count) {
        $p = $presets[$num - 1]
        Write-Host ""
        Write-Host "切换到：$($p.n)" -ForegroundColor Cyan
        Push-Persona $p.p
        Read-Host "回车返回"
    }
}
