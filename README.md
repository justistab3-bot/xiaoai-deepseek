# 让小爱音箱用上你自己的 AI

> 小爱触屏音箱 Pro 8（X08A）实测可用。
> 唤醒词、语音识别、屏幕界面、说话的声音——**全都还是原来那个小爱**。
> 只有"回答"这一段，换成了你自己的大模型（DeepSeek、通义、Kimi、任何 OpenAI 兼容接口都行）。

---

## 它到底改了什么

你对着音箱说「小爱同学，你叫什么名字」：

| 环节 | 原来 | 改完 |
|---|---|---|
| 唤醒 | 小爱 | 小爱（没变） |
| 听你说话 | 小米云端识别 | 小米云端识别（没变） |
| **想答案** | 小米大模型 | **你的 DeepSeek** |
| 屏幕上的字 | 小米的回答 | **你的 DeepSeek 的回答** |
| 念出来的声音 | 小爱原声 | **还是小爱原声**（内容换了） |
| 开灯关空调 | 小爱控制 | 小爱控制（还能用，甚至更聪明） |

一句话：**换脑不换脸**。

---

## 开始之前，你需要

| 需要什么 | 说明 |
|---|---|
| 小爱触屏音箱 Pro 8 | 型号 X08A。别的型号没试过，可能也行，但风险自负 |
| 一台 Windows 电脑 | 用来装东西和推配置 |
| 一根 USB 数据线 | 电脑连音箱 |
| 音箱已经 root | 装了 Magisk（面具） |
| 一个 API Key | [DeepSeek 开放平台](https://platform.deepseek.com/) 注册就有，充 10 块钱能用很久 |

> **还没 root？** 这一步因设备和固件版本而异，需要你自己搜教程。
> 判断标准：`adb shell su -c id` 返回 `uid=0(root)` 就算好了。

---

## 五步搞定

### 第 1 步：电脑能连上音箱

1. 音箱上：设置 → 关于 → 连点版本号 7 次，打开开发者选项
2. 开发者选项里打开「USB 调试」
3. 用数据线连电脑
4. 电脑上装 [ADB 工具](https://developer.android.com/studio/releases/platform-tools)，解压后在该目录打开命令行

验证：

```powershell
adb devices
```

看到一行 `xxxxxx    device` 就对了。如果显示 `unauthorized`，去音箱屏幕上点「允许」。

---

### 第 2 步：装 LSPosed

LSPosed 是一个"往 App 里插代码"的框架，我们的桥接模块跑在它上面。

1. 确认 Magisk 里 **Zygisk 已开启**（Magisk 设置里找 Zygisk 开关）
2. 下载 [LSPosed v1.9.2](https://github.com/LSPosed/LSPosed/releases/download/v1.9.2/LSPosed-v1.9.2-7024-zygisk-release.zip)
3. 装进 Magisk：

```powershell
adb push LSPosed-v1.9.2-7024-zygisk-release.zip /data/local/tmp/
adb shell su -c "magisk --install-module /data/local/tmp/LSPosed-v1.9.2-7024-zygisk-release.zip"
adb reboot
```

4. 重启后，从那个 zip 里解出 `manager.apk` 装上：

```powershell
adb install manager.apk
```

---

### 第 3 步：装桥接模块

```powershell
adb install release/xiaoai-bridge.apk
```

然后在音箱上打开 **LSPosed 管理器** → 模块 → 找到「小爱DS桥接」→ 启用 → 勾选作用域里的 **超级小爱**（`com.xiaomi.soundboxassist`）→ 重启音箱。

重启后验证：

```powershell
adb shell cat /sdcard/xiaoai_bridge.log
```

看到 `=== XiaoAiBridge loaded` 就说明模块跑起来了。

---

### 第 4 步：填你的 API Key

**方法一：在音箱上填（推荐）**

桌面 → 我的应用 → **小爱DS 控制台** → 「接口」页：

1. API 地址保持默认 `https://api.deepseek.com/v1/chat/completions`
2. Key 填你的 `sk-xxxxxxxx`
3. 模型保持 `deepseek-chat`
4. 点右下角「保存并生效」

**方法二：电脑上填**

```powershell
cd scripts
.\set-key.ps1 sk-你的密钥
```

---

### 第 5 步：说话测试

对着音箱说：

> 小爱同学，你叫什么名字

正常的话：

- 屏幕先显示「正在思考」
- 大概 2 秒后，屏幕出现 DeepSeek 的回答
- 然后用小爱原来的声音念出来

不满意？看日志：

```powershell
adb shell cat /sdcard/xiaoai_bridge.log
```

---

## 换个性格

默认它是个普通助手。想换风格：

**音箱上**：小爱DS 控制台 → 「人设」页 → 点一个预设 → 保存

**电脑上**：

```powershell
cd scripts
.\persona.ps1
```

内置 10 个：毒舌损友、温柔管家、二次元萌妹、冷酷效率、相声演员、东北大哥、学术教授、赛博朋克、猫、讲道理的。

也可以自己写，比如：

> 你是一个说话很慢、喜欢用比喻的老先生

后面那段「回答要口语化、简短、不要用列表」的约束**别删**，不然模型会输出一堆星号和井号，念出来很难听。

---

## 让它控制家电

已经支持了，不用额外配置。

**简单指令**直接交给小爱，零延迟：

> 关灯 → 小爱自己执行，自己说「关喽」

**复杂场景**交给 DeepSeek 拆解：

> 我要睡觉了，帮我关灯、把空调调到26度

DeepSeek 会拆成两条指令，逐条发给小爱执行，最后汇总一句「灯关了，空调也调到26度了，好好睡吧」。

**执行仍然走小米官方链路**，不需要设备 token，不需要米家账号，不会碰任何私有协议。

> 如果你的设备不支持某个指令，先试试直接对小爱说，能执行就说明桥接也能转过去。

---

## 常见问题

**Q：说了话没反应 / 卡在「正在思考」**

看日志里有没有 `[llm]` 那行。没有就是 DeepSeek 没连上：
- Key 填错了？日志会写 `HTTP 401`
- 网络不通？日志会写 `fail`

**Q：屏幕有字，但没声音**

日志里找 `[play]`。如果只有「下载完成」没有「开始播放」，可能是媒体音量为 0：

```powershell
adb shell media volume --stream 3 --set 8
```

**Q：念出来的内容跟屏幕上的不太一样**

正常的。声音是小米 TTS 服务生成的，它会把 DeepSeek 的话"理解后复述"一遍。陈述句基本一致，问句偶尔会自己发挥。

**Q：关灯的时候声音被换成了 DeepSeek 的话**

那是旧版本的问题。现在短指令会直接交给小爱，不会再被顶掉。

**Q：会不会把音箱变砖**

不会。所有改动都在 `/data` 分区，不进系统分区。最坏情况：`adb shell su -c "magisk --remove-modules"` 然后重启。

**Q：小米推送系统更新后失效了**

大概率。小爱升级后内部结构会变，需要重新适配。到时候把新的 APK 提 issue。

---

## 不想要了，怎么恢复

**临时关掉**（秒级，随时能开回来）：

```powershell
cd scripts
.\disable.ps1
```

或者在音箱上：小爱DS 控制台 → 高级 → 关掉「启用桥接」。

**彻底卸载**：

```powershell
adb uninstall com.yg.xiaoai.bridge
adb uninstall com.yg.xiaoai.console
adb shell su -c "magisk --remove-modules zygisk_lsposed"
adb reboot
```

---

## 技术细节

想了解原理、协议、hook 点、已知限制，看 [docs/TECH.md](docs/TECH.md)。

---

## 项目结构

```
release/          编译好的 APK，直接装
bridge/           Xposed 模块源码
console/          设备端控制台 App 源码
scripts/          电脑端脚本
docs/TECH.md      技术文档
push.ps1          一键推送到 GitHub / Gitee（维护者用）
```

## 自己编译

需要 JDK 8+ 和 Android SDK（build-tools 34+、platform 33+）。

```powershell
cd bridge;  .\build.ps1     # 生成 xiaoai-bridge.apk
cd console; .\build.ps1     # 生成 xiaoai-console.apk
```

无 Gradle，纯 aapt2 + javac + d8 手工构建。这个模块只有几百行，拉一套 Gradle 不划算。

---

## 许可

MIT

