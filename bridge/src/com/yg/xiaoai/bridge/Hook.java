package com.yg.xiaoai.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 只换后端，前端全原生。
 *
 * 拦截：
 *   A) AIVSEngineWrapper$4.process(Instruction)      普通指令 / 我们的 TTS 回包
 *   B) AIVSEngineWrapper$5.onStreamInstruction(...)  大模型流式回答（文本走这里）
 *
 * 播报：走官方接口链
 *   ConversationManager.createConversationFarField(requestType=3)
 *     -> setQueryText(DeepSeek 文本)
 *     -> addConversation(...)          注册会话，回包时才能查到
 *     -> AIVSEngineWrapper.startRequest(...)
 *   之后小米云端用官方音色合成，官方播放器播放，官方 UI 显示文本。
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TARGET = "com.xiaomi.soundboxassist";

    private static ClassLoader sCl;
    private static volatile Object sWrapper;

    private static volatile boolean sBlockOriginal = false;
    private static volatile String sOurAnswer = null;
    private static volatile long sBlockStart = 0L;
    private static volatile String sLastQuery = null;
    private static volatile long sLastQueryAt = 0L;

    private static volatile String sLastInstKey = null;
    private static volatile long sLastInstAt = 0L;
    private static volatile int sDumpCount = 0;

    /** 我们自己发起的那个 TTS 会话，用于补 conversation */
    private static volatile Object sOurConv = null;
    /** 我们自己发起的 NLP 请求的 dialogId，用来认领它回来的 ToastStream */
    private static volatile String sOurDialogId = null;
    /** 执行设备控制时，这些 dialog 的播报要全部压掉 */
    private static final java.util.Set<String> SUPPRESS_DLG =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** 语音提问那一刻的活跃对话，用来继承 callback */
    private static volatile Object sCurrentConv = null;
    private static volatile boolean sFixLogged = false;
    /** 本轮对话进行中（从识别到最后一条 Dialog/Finish） */
    private static volatile boolean sRoundActive = false;
    private static volatile boolean sToastReplaced = false;
    /** 小米这一轮回答所属的 dialogId —— 用它精确区分"小米的"和"我们的" */
    private static volatile String sMicoDialogId = null;

    static String dialogIdOf(Object inst) {
        return optStr(call(inst, "getDialogId"));
    }

    /** com.xiaomi.common.Optional<String> -> String */
    static String optStr(Object opt) {
        if (opt == null) return null;
        try {
            Object present = call(opt, "isPresent");
            if (Boolean.TRUE.equals(present)) return String.valueOf(call(opt, "get"));
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!TARGET.equals(lp.packageName)) return;
        sCl = lp.classLoader;
        Cfg.log("=== XiaoAiBridge loaded: " + lp.packageName);

        Class<?> wrapperCls = cls("com.xiaomi.aivse.internal.engine.AIVSEngineWrapper");
        if (wrapperCls != null) {
            XposedBridge.hookAllConstructors(wrapperCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    sWrapper = p.thisObject;
                    Cfg.log("wrapper captured");
                }
            });
        }

        Class<?> instCap = cls("com.xiaomi.ai.android.capability.InstructionCapability");
        Class<?> largeCap = cls("com.xiaomi.ai.android.capability.LargeModelCapability");

        int n = 0;
        for (int i = 1; i <= 24; i++) {
            Class<?> c = cls("com.xiaomi.aivse.internal.engine.AIVSEngineWrapper$" + i);
            if (c == null) continue;
            if (instCap != null && instCap.isAssignableFrom(c)) {
                XposedBridge.hookAllMethods(c, "process", new InstHook());
                n++;
                Cfg.log("hook " + c.getName() + ".process");
            }
            if (largeCap != null && largeCap.isAssignableFrom(c)) {
                XposedBridge.hookAllMethods(c, "onStreamInstruction", new StreamHook());
                n++;
                Cfg.log("hook " + c.getName() + ".onStreamInstruction");
            }
            // 音频回调：只探测，不拦截
            XposedBridge.hookAllMethods(c, "onTtsData", new AudioProbe());
            XposedBridge.hookAllMethods(c, "onPcmData", new AudioProbe());
        }
        Cfg.log("hooks installed = " + n);

        // 音频最终交给 ITtsListenerImpl.onPcmData(conversation, pcm)。
        // conversation 为 null 时它直接 return，音频就没了。
        // 这里在参数为 null 时补上我们自己的会话，音频仍然进官方播放器。
        Class<?> ttsImpl = cls("com.xiaomi.xiaoaiframework.ecsimpl.abilities.ITtsListenerImpl");
        if (ttsImpl != null) {
            XC_MethodHook fixConv = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args != null && p.args.length > 0 && p.args[0] == null && sOurConv != null) {
                            p.args[0] = sOurConv;
                            if (!sFixLogged) {
                                sFixLogged = true;
                                Cfg.log("[fix] 补 conversation（后续不再刷屏）");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedBridge.hookAllMethods(ttsImpl, "onPcmData", fixConv);
            XposedBridge.hookAllMethods(ttsImpl, "onPlayStart", fixConv);
            XposedBridge.hookAllMethods(ttsImpl, "onFinishSpeakStream", fixConv);
            Cfg.log("hook ITtsListenerImpl 补 conversation");
        }

        startInjectThread();
    }

    static class AudioProbe extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                Object a0 = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                if (!(a0 instanceof byte[])) return;
                sAudioBytes += ((byte[]) a0).length;
                if (sAudioBytes / 20000 != sAudioLogged / 20000) {
                    sAudioLogged = sAudioBytes;
                    Cfg.log("[audio] 累计 " + (sAudioBytes / 1024) + " KB");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static volatile long sAudioBytes = 0L;
    private static volatile long sAudioLogged = 0L;

    // ================= 入口 A =================

    static class InstHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                Object inst = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                if (inst == null) return;
                sCurInst = inst;
                if (sWrapper == null) sWrapper = findWrapper(p.thisObject);

                String ns = (String) call(inst, "getNamespace");
                String name = (String) call(inst, "getName");
                Object payload = call(inst, "getPayload");
                if (payload == null) return;
                String pcn = payload.getClass().getName();

                maybeLogInst("[A] " + ns + "/" + name, pcn, payload);

                if ("SpeechRecognizer".equals(ns) && pcn.endsWith("$RecognizeResult")) {
                    Object fin = call(payload, "isFinal");
                    if (Boolean.TRUE.equals(fin)) {
                        String text = firstResultText(payload);
                        if (text != null && text.trim().length() > 0) {
                            sMicoDialogId = dialogIdOf(inst);
                            onUserSaid(text.trim());
                        }
                    }
                    return;
                }

                if (pcn.endsWith("$Speak") || pcn.endsWith("$SpeakStream")) {
                    String t = payloadText(payload);
                    String dlg = dialogIdOf(inst);
                    boolean mico = sMicoDialogId != null && sMicoDialogId.equals(dlg);
                    if (mico) {
                        Cfg.log("[block-A] 小米 Speak: " + cut(t));
                        p.setResult(Boolean.TRUE);
                        return;
                    }
                    if (isOurs(t)) {
                        String u = optStr(call(payload, "getUrl"));
                        Cfg.log("[ours] 放行我们的 Speak: " + cut(t) + " url=" + (u == null ? "无" : cut(u)));
                        sBlockOriginal = false;
                    }
                }
            } catch (Throwable t) {
                Cfg.logE("InstHook", t);
            }
        }
    }

    // ================= 入口 B：大模型流式（屏幕文本在这） =================

    static class StreamHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                Object inst = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                if (inst == null) return;
                sCurInst = inst;
                String ns = null, name = null;
                Object payload = null;
                try {
                    ns = (String) call(inst, "getNamespace");
                    name = (String) call(inst, "getName");
                    payload = call(inst, "getPayload");
                } catch (Throwable ignored) {
                }
                String pcn = payload == null ? "" : payload.getClass().getName();
                String t = payload == null ? "" : payloadText(payload);

                maybeLogInst("[B] " + ns + "/" + name, pcn, payload);

                String dlg = dialogIdOf(inst);
                boolean mico = sMicoDialogId != null && sMicoDialogId.equals(dlg);
                boolean ours = sOurDialogId != null && sOurDialogId.equals(dlg);

                // 小米的回答比我们的 TTS 快 2-3 秒。如果让它照常结束，
                // UI 会在我们的内容到达前就退出对话。这里扣住结束指令，
                // 等屏幕内容显示后再放行。
                if (mico) {
                    if (sOurAnswer == null && sToastReplaced == false) {
                        if ("Nlp".equals(ns) && "FinishStream".equals(name)) {
                            PENDING_FINISH.add(inst);
                            p.setResult(null);
                            Cfg.log("[hold] 暂缓 Nlp/FinishStream");
                            return;
                        }
                        if ("Dialog".equals(ns) && "Finish".equals(name)) {
                            PENDING_FINISH.add(inst);
                            p.setResult(null);
                            Cfg.log("[hold] 暂缓 Dialog/Finish");
                            return;
                        }
                    }
                }

                // 执行设备控制的那几条请求，播报和界面全部压掉，只留 DeepSeek 的总结
                if (dlg != null && SUPPRESS_DLG.contains(dlg)) {
                    if (pcn.endsWith("$Speak") || pcn.endsWith("$SpeakStream") || pcn.endsWith("$ToastStream")) {
                        Cfg.log("[suppress] " + ns + "/" + name);
                        p.setResult(null);
                    }
                    return;
                }

                // 屏幕正文走 Template/ToastStream 的 markdown_text（UI 是追加渲染）。
                // 小米那条整条丢掉：它比 DeepSeek 先到，首片是空的话 UI 会认为气泡没内容，
                // 后面的文本就没地方追加了 —— 这就是"有时候屏幕加载不出来"的原因。
                if (pcn.endsWith("$ToastStream")) {
                    if (mico) {
                        // 小米的回答比 DeepSeek 快。这里原地等一会儿，
                        // 把文本换成我们的再放行 —— UI 收到的是同一条 dialog 的正常指令，
                        // 渲染一定生效，也不会卡在"正在思考"。
                        if (sOurAnswer == null) {
                            long t0 = System.currentTimeMillis();
                            while (sOurAnswer == null && System.currentTimeMillis() - t0 < 6000L) {
                                try {
                                    Thread.sleep(50);
                                } catch (InterruptedException ignored) {
                                }
                            }
                        }
                        if (sOurAnswer != null && sOurAnswer.length() > 0) {
                            try {
                                if (!sToastReplaced) {
                                    sToastReplaced = true;
                                    invoke(payload, "setMarkdownText", sOurAnswer);
                                    Cfg.log("[toast] 同步替换: " + cut(sOurAnswer));
                                } else {
                                    invoke(payload, "setMarkdownText", "");
                                }
                            } catch (Throwable e) {
                                Cfg.logE("[toast] 替换失败", e);
                            }
                        } else {
                            Cfg.log("[toast] 等待超时，放行原文");
                        }
                        return;
                    }
                    if (ours) {
                        // 我们自己那条流丢掉，屏幕统一由上面那条驱动
                        p.setResult(null);
                    }
                    return;
                }

                // 只有 Speak / SpeakStream 承载"回答正文 + 播报"，其余控制流必须放行，
                // 否则状态机会卡在"正在思考"直到超时。
                boolean isSpeak = pcn.endsWith("$Speak") || pcn.endsWith("$SpeakStream");
                if (!isSpeak) return;

                if (mico) {
                    Cfg.log("[block-B] 小米回答: " + cut(t));
                    p.setResult(null);
                    return;
                }
                if (isOurs(t)) {
                    Cfg.log("[ours-B] 放行: " + cut(t));
                    sBlockOriginal = false;
                }
                String u = optStr(call(payload, "getUrl"));
                if (u != null) {
                    probeUrl(u);
                    playUrl(u);
                }
            } catch (Throwable t) {
                Cfg.logE("StreamHook", t);
            }
        }
    }

    // ================= 业务 =================

    static void onUserSaid(final String text) {
        if (!Cfg.enabled()) return;
        long now = System.currentTimeMillis();
        if (text.equals(sLastQuery) && now - sLastQueryAt < 1200L) return;
        sLastQuery = text;
        sLastQueryAt = now;

        if (shouldPassthrough(text)) {
            Cfg.log("[pass] 交回原生: " + text);
            sBlockOriginal = false;
            // 这一轮不拦，让小米自己执行 + 自己播报
            sMicoDialogId = null;
            return;
        }

        sBlockOriginal = true;
        sBlockStart = now;
        sOurAnswer = null;
        sFixLogged = false;
        sRoundActive = true;
        sToastReplaced = false;
        sProbeDone.set(false);
        sPlayDone.set(false);
        SUPPRESS_DLG.clear();
        PENDING_FINISH.clear();
        PENDING_TOAST.clear();
        stopMp();
        sCurrentConv = call(sWrapper, "getCurrentConversation");
        Cfg.log("[user] " + text + "  (conv=" + (sCurrentConv != null) + ")");

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String ans = Llm.chat(text, new Llm.ToolExecutor() {
                        @Override
                        public String execute(String name, String args) {
                            if (!"control_device".equals(name)) return "不支持的工具：" + name;
                            try {
                                org.json.JSONObject o = new org.json.JSONObject(args);
                                String cmd = o.optString("command", "").trim();
                                if (cmd.length() == 0) return "指令为空，没有执行";
                                execCommand(cmd);
                                return "已执行：" + cmd;
                            } catch (Throwable e) {
                                return "执行失败：" + e.getMessage();
                            }
                        }
                    });
                    sOurAnswer = ans;
                    Cfg.log("[llm ] " + ans);
                    // 先把扣住的屏幕内容填上，再发起 TTS
                    flushToast();
                    speak(ans);
                } catch (Throwable e) {
                    Cfg.logE("[llm ] fail", e);
                    sBlockOriginal = false;
                    sOurAnswer = null;
                    speak(String.valueOf(e.getMessage()).contains("llm_key")
                            ? "后端还没配置密钥。"
                            : "后端连接失败了。");
                }
            }
        }, "xiaoai-bridge");
        t.setDaemon(true);
        t.start();
    }

    /** 把一条中文指令交给小爱原生执行（走官方 NLP 链路，不碰设备协议） */
    static void execCommand(String cmd) {
        try {
            Object wrapper = sWrapper;
            if (wrapper == null) throw new IllegalStateException("wrapper 未就绪");
            Object cm = XposedHelpers.getObjectField(wrapper, "mConversationManager");
            Object node = XposedHelpers.getObjectField(wrapper, "mNode");

            String rid = UUID.randomUUID().toString().replace("-", "");
            String dlgId = UUID.randomUUID().toString().replace("-", "");
            SUPPRESS_DLG.add(rid);   // 同上

            Object conv = call(cm, "createConversationFarField", (String) call(node, "getUuid"), 0, 2, rid, dlgId, 0);
            if (conv == null) throw new IllegalStateException("createConversationFarField 返回 null");
            invoke(conv, "setQueryText", cmd);
            try {
                Object ok = invoke(cm, "addConversation", (String) call(wrapper, "getEngineId"), conv,
                        (String) call(wrapper, "getEngineId"));
                Cfg.log("[exec] addConversation=" + ok);
            } catch (Throwable ignored) {
            }

            Class<?> nlpReqCls = Class.forName("com.xiaomi.ai.api.Nlp$Request", false, sCl);
            Object nlpReq = nlpReqCls.getConstructor(String.class).newInstance(cmd);

            Class<?> apiUtils = Class.forName("com.xiaomi.ai.api.common.APIUtils", false, sCl);
            Method buildEvent = null;
            for (Method m : apiUtils.getMethods()) {
                if (m.getName().equals("buildEvent") && m.getParameterTypes().length == 3) {
                    buildEvent = m;
                    break;
                }
            }
            if (buildEvent == null) throw new IllegalStateException("找不到 buildEvent");
            Object event = buildEvent.invoke(null, nlpReq, null, rid);
            invoke(wrapper, "postEvent", event);
            Cfg.log("[exec] 已下发：" + cmd);
        } catch (Throwable e) {
            Cfg.logE("[exec] 失败", e);
        }
    }

    /** L1：明确的设备控制说法，直接交回原生，零延迟 */
    private static final String DEF_PASSTHROUGH =
            "打开,关闭,关掉,开一下,关一下,调高,调低,调大,调小,静音,"
            + "关灯,开灯,关空调,开空调,关窗帘,开窗帘,关插座,开插座,"
            + "音量调到,亮度调到,温度调到,风速调到";

    static boolean shouldPassthrough(String text) {
        // 长句里有可能是「我要睡觉了，帮我关灯…」这种复合场景，
        // 交给 DeepSeek 拆解，别被单个关键词拦走
        if (text.length() > 12) return false;
        String kw = Cfg.str("passthrough_keywords", DEF_PASSTHROUGH);
        if (kw.length() == 0) return false;
        for (String k : kw.split("[,，]")) {
            k = k.trim();
            if (k.length() > 0 && text.contains(k)) return true;
        }
        return false;
    }

    static void watchdog() {
        if (sBlockOriginal && sBlockStart > 0 && System.currentTimeMillis() - sBlockStart > 40000L) {
            Cfg.log("[warn] 拦截超时，复位");
            sBlockOriginal = false;
            sOurAnswer = null;
        }
    }

    /**
     * 官方接口链：建会话(requestType=3) -> 注册 -> startRequest
     * 这样云端回包时能查到会话，音频进官方播放器，文本进官方 UI。
     */
    /** 构造带 audioType 的 TtsConfig，服务器看到才会推音频 */
    static Object buildTtsConfig(Object node) throws Exception {
        Class<?> builderCls = Class.forName("com.xiaomi.aivse.api.engine.request.TtsRequest$Builder", false, sCl);
        Object b = builderCls.getConstructor().newInstance();
        Object initConfig = call(node, "getInitConfig");
        if (initConfig != null) {
            Object superX = call(initConfig, "isSuperXiaoAi");
            if (superX != null) b = invoke(b, "setSuperXiaoAi", superX);
            Object vendorCfg = call(initConfig, "getTtsVendorConfig");
            if (vendorCfg != null) {
                Object vendor = call(vendorCfg, "getVendor");
                Object speaker = call(vendorCfg, "getSpeaker");
                if (vendor != null && !"".equals(vendor)) b = invoke(b, "setVendor", vendor);
                b = invoke(b, "setSpeak", speaker == null ? "" : speaker);
            }
        }
        b = invoke(b, "setLang", "cmn-Hans-CN");

        Class<?> audioTypeCls = Class.forName("com.xiaomi.aivse.api.engine.request.TtsRequest$AudioType", false, sCl);
        Class<?> ttsAudioTypeCls = Class.forName("com.xiaomi.ai.api.Settings$TtsAudioType", false, sCl);
        Object urlType = Enum.valueOf((Class) ttsAudioTypeCls, "URL");
        Object streamType = Enum.valueOf((Class) ttsAudioTypeCls, "STREAM");
        Object at = audioTypeCls.getConstructor(ttsAudioTypeCls, ttsAudioTypeCls).newInstance(urlType, streamType);
        b = invoke(b, "setAudioType", at);

        Object req = invoke(b, "build");
        Object ttsConfig = invoke(req, "create");
        try {
            invoke(ttsConfig, "setCodec", "MP3");
        } catch (Throwable ignored) {
        }
        return ttsConfig;
    }

    static void speak(String text) {
        try {
            Object wrapper = sWrapper;
            if (wrapper == null) throw new IllegalStateException("wrapper 未就绪");

            Object cm = XposedHelpers.getObjectField(wrapper, "mConversationManager");
            Object node = XposedHelpers.getObjectField(wrapper, "mNode");
            if (cm == null) throw new IllegalStateException("ConversationManager 为空");
            if (node == null) throw new IllegalStateException("node 为空");

            String engineId = (String) call(wrapper, "getEngineId");
            String nodeUuid = (String) call(node, "getUuid");
            Cfg.log("[tts ] engineId=" + engineId + " nodeUuid=" + nodeUuid);

            String rid = UUID.randomUUID().toString().replace("-", "");
            String dlgId = UUID.randomUUID().toString().replace("-", "");

            // createConversationFarField(nodeUuid, direction, requestType, requestId, dialogId, wakeupZone)
            Object conv = call(cm, "createConversationFarField", nodeUuid, 0, 5, rid, dlgId, 0);
            if (conv == null) throw new IllegalStateException("createConversationFarField 返回 null");

            // 只设 queryText：buildTtsEvent 在 textWithTag 为空时才会用 PLAIN 文本
            invoke(conv, "setQueryText", "跟我说：" + text);
            invoke(conv, "setTtsFromAppName", "XiaoAiDS");

            // 把当前活跃对话的 TtsCallback 传过去，否则回包时拿不到回调，音频被丢
            try {
                Object cur = call(wrapper, "getCurrentConversation");
                if (cur == null) cur = sCurrentConv;
                if (cur != null) {
                    Object cb = call(cur, "getCallback");
                    if (cb != null) {
                        invoke(conv, "setCallback", cb);
                        Cfg.log("[tts ] callback 已继承");
                    } else {
                        Cfg.log("[tts ] 当前对话没有 callback");
                    }
                }
            } catch (Throwable e) {
                Cfg.logE("[tts ] 继承 callback 失败", e);
            }

            // addConversation(by, conv, engineId) —— 必须注册，否则回包查不到会话
            try {
                Object ok = invoke(cm, "addConversation", engineId, conv, engineId);
                Cfg.log("[tts ] addConversation ok=" + ok);
            } catch (Throwable e) {
                Cfg.logE("[tts ] addConversation fail", e);
            }
            sOurConv = conv;

            // 服务器只在 NLP 流程里生成带 url 的 TTS。
            // 改发 NLP 请求，用「跟我说：xxx」让小米把我们的文本念出来。
            sOurDialogId = rid;   // 服务器回包的 dialog_id 就是 requestId
            Class<?> nlpReqCls = Class.forName("com.xiaomi.ai.api.Nlp$Request", false, sCl);
            Object nlpReq = nlpReqCls.getConstructor(String.class).newInstance("跟我说：" + text);
            Object ttsConfig = buildTtsConfig(node);
            invoke(nlpReq, "setTts", ttsConfig);
            try {
                invoke(nlpReq, "setLocale", "cmn-Hans-CN");
            } catch (Throwable ignored) {
            }
            Class<?> apiUtils2 = Class.forName("com.xiaomi.ai.api.common.APIUtils", false, sCl);
            Method buildEvent2 = null;
            for (Method m : apiUtils2.getMethods()) {
                if (m.getName().equals("buildEvent") && m.getParameterTypes().length == 3) {
                    buildEvent2 = m;
                    break;
                }
            }
            if (buildEvent2 == null) throw new IllegalStateException("找不到 buildEvent");
            Object event2 = buildEvent2.invoke(null, nlpReq, null, rid);
            invoke(wrapper, "postEvent", event2);
            Cfg.log("[tts ] Nlp.Request 已发 rid=" + rid + " q=跟我说：" + cut(text));
        } catch (Throwable t) {
            Cfg.logE("[tts ] fail", t);
            sBlockOriginal = false;
        }
    }

    /** 只探测：把 Speak.url 拉一次，看能不能直接拿到音频字节（默认关，用 probe_url 打开） */
    static void probeUrl(final String url) {
        if (url == null || url.length() == 0) return;
        if (!Cfg.load().optBoolean("probe_url", false)) return;
        if (!sProbeDone.compareAndSet(false, true)) return;
        Cfg.log("[probe] url=" + (url.length() > 90 ? url.substring(0, 90) + "…" : url));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                java.net.HttpURLConnection c = null;
                try {
                    c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    c.setRequestMethod("GET");
                    c.setRequestProperty("User-Agent", "okhttp/3.12.0");
                    int code = c.getResponseCode();
                    Cfg.log("[probe] code=" + code + " ct=" + c.getContentType() + " len=" + c.getContentLength());
                    java.io.InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
                    if (in == null) {
                        Cfg.log("[probe] 没有响应体");
                        return;
                    }
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0 && total < 3000000) {
                        bos.write(buf, 0, n);
                        total += n;
                    }
                    in.close();
                    byte[] data = bos.toByteArray();
                    Cfg.log("[probe] 收到 " + data.length + " 字节 head=" + hexHead(data));
                } catch (Throwable e) {
                    Cfg.logE("[probe] fail", e);
                } finally {
                    if (c != null) {
                        try {
                            c.disconnect();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "xiaoai-probe");
        t.setDaemon(true);
        t.start();
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sProbeDone =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    static String hexHead(byte[] d) {
        if (d == null || d.length == 0) return "空";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(16, d.length);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02X ", d[i]));
        }
        StringBuilder asc = new StringBuilder();
        for (int i = 0; i < n; i++) {
            char ch = (char) (d[i] & 0xff);
            asc.append(ch >= 32 && ch < 127 ? ch : '.');
        }
        return sb.toString() + " | " + asc;
    }

    /** 把官方 TTS 生成的 MP3 拉下来交给系统播放器播（音频本身是小米服务生成的） */
    static void playUrl(final String url) {
        if (url == null || url.length() == 0) return;
        if (!sPlayDone.compareAndSet(false, true)) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                java.net.HttpURLConnection c = null;
                try {
                    c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    c.setRequestMethod("GET");
                    c.setRequestProperty("User-Agent", "okhttp/3.12.0");
                    int code = c.getResponseCode();
                    if (code < 200 || code >= 300) {
                        Cfg.log("[play] HTTP " + code + "，放弃");
                        return;
                    }
                    byte[] mp3 = Cfg.readAll(c.getInputStream());
                    Cfg.log("[play] 下载完成 " + mp3.length + " 字节");

                    Object ctx = appCtx();
                    if (ctx == null) {
                        Cfg.log("[play] 拿不到 Context");
                        return;
                    }
                    java.io.File dir = (java.io.File) call(ctx, "getCacheDir");
                    final java.io.File f = new java.io.File(dir, "bridge_tts.mp3");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    fos.write(mp3);
                    fos.close();

                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            startMp(f);
                        }
                    });
                } catch (Throwable e) {
                    Cfg.logE("[play] 失败", e);
                } finally {
                    if (c != null) {
                        try {
                            c.disconnect();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "xiaoai-play");
        t.setDaemon(true);
        t.start();
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sPlayDone =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile Object sMp = null;

    static void startMp(java.io.File f) {
        try {
            stopMp();
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(android.media.MediaPlayer m) {
                    Cfg.log("[play] 播放结束");
                }
            });
            mp.prepare();
            mp.start();
            sMp = mp;
            Cfg.log("[play] 开始播放");
        } catch (Throwable e) {
            Cfg.logE("[play] MediaPlayer 失败", e);
        }
    }

    static void stopMp() {
        Object o = sMp;
        sMp = null;
        if (o != null) {
            try {
                android.media.MediaPlayer mp = (android.media.MediaPlayer) o;
                if (mp.isPlaying()) mp.stop();
                mp.release();
            } catch (Throwable ignored) {
            }
        }
    }

    static Object appCtx() {
        try {
            Object w = sWrapper;
            if (w != null) {
                Object ctx = XposedHelpers.getObjectField(w, "mContext");
                if (ctx != null) return ctx;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> h = Class.forName("android.app.AndroidAppHelper", false, sCl);
            return call(null, "currentApplication");
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 被扣住的小米结束指令，等内容显示后再放行 */
    private static final java.util.List<Object> PENDING_FINISH =
            java.util.Collections.synchronizedList(new java.util.ArrayList<Object>());
    /** 被扣住的小米 ToastStream（屏幕正文），等 DeepSeek 回来再填内容 */
    private static final java.util.List<Object> PENDING_TOAST =
            java.util.Collections.synchronizedList(new java.util.ArrayList<Object>());

    /** 把扣住的 ToastStream 填上我们的文本投回去，再放行结束指令 */
    static void flushToast() {
        java.util.List<Object> copy;
        synchronized (PENDING_TOAST) {
            copy = new java.util.ArrayList<Object>(PENDING_TOAST);
            PENDING_TOAST.clear();
        }
        if (copy.isEmpty()) {
            releaseFinish();
            return;
        }
        boolean first = true;
        for (Object inst : copy) {
            try {
                Object payload = call(inst, "getPayload");
                if (first) {
                    first = false;
                    sToastReplaced = true;
                    invoke(payload, "setMarkdownText", sOurAnswer == null ? "" : sOurAnswer);
                    Cfg.log("[flush] 首片=" + cut(sOurAnswer));
                } else {
                    invoke(payload, "setMarkdownText", "");
                }
                Object pia = XposedHelpers.getObjectField(sWrapper, "mProduceInstAbility");
                String eid = (String) call(sWrapper, "getEngineId");
                invoke(pia, "putStreamInst", eid, inst);
            } catch (Throwable e) {
                Cfg.logE("[flush] 失败", e);
            }
        }
        releaseFinish();
    }

    /** 把扣住的结束指令重新投进 UI 管线 */
    static void releaseFinish() {
        if (PENDING_FINISH.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                }
                java.util.List<Object> copy;
                synchronized (PENDING_FINISH) {
                    copy = new java.util.ArrayList<Object>(PENDING_FINISH);
                    PENDING_FINISH.clear();
                }
                for (Object inst : copy) {
                    try {
                        Object pia = XposedHelpers.getObjectField(sWrapper, "mProduceInstAbility");
                        String eid = (String) call(sWrapper, "getEngineId");
                        invoke(pia, "putStreamInst", eid, inst);
                        Cfg.log("[release] 放行结束指令");
                    } catch (Throwable e) {
                        Cfg.logE("[release] 失败", e);
                    }
                }
            }
        }, "xiaoai-release").start();
    }

    // ================= 注入 / 工具 =================

    static void startInjectThread() {
        Thread inj = new Thread(new Runnable() {
            @Override
            public void run() {
                java.io.File f = new java.io.File("/sdcard/xiaoai_bridge_inject");
                while (true) {
                    try {
                        Thread.sleep(1500);
                        watchdog();
                        if (!f.exists()) continue;
                        byte[] b = Cfg.readAll(new java.io.FileInputStream(f));
                        f.delete();
                        String s = new String(b, "UTF-8").trim();
                        if (s.length() > 0) {
                            Cfg.log("[inj ] " + s);
                            onUserSaid(s);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "xiaoai-inject");
        inj.setDaemon(true);
        inj.start();
    }

    static Class<?> cls(String name) {
        try {
            return Class.forName(name, false, sCl);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object findWrapper(Object inner) {
        if (inner == null) return null;
        Class<?> c = inner.getClass();
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(inner);
                    if (v != null && v.getClass().getName().endsWith("AIVSEngineWrapper")) return v;
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    static Object call(Object obj, String name, Object... args) {
        if (obj == null) return null;
        try {
            Method m = findMethod(obj.getClass(), name, args);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(obj, args);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object invoke(Object obj, String name, Object... args) throws Exception {
        Method m = findMethod(obj.getClass(), name, args);
        if (m == null) throw new NoSuchMethodException(obj.getClass().getName() + "." + name);
        m.setAccessible(true);
        return m.invoke(obj, args);
    }

    static Method findMethod(Class<?> c, String name, Object... args) {
        int argc = args == null ? 0 : args.length;
        Class<?> k = c;
        while (k != null) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterTypes().length != argc) continue;
                if (!matchTypes(m.getParameterTypes(), args)) continue;
                return m;
            }
            k = k.getSuperclass();
        }
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == argc
                    && matchTypes(m.getParameterTypes(), args)) return m;
        }
        return null;
    }

    static boolean matchTypes(Class<?>[] pt, Object[] args) {
        if (args == null) return pt.length == 0;
        for (int i = 0; i < pt.length; i++) {
            Object a = args[i];
            if (a == null) continue;
            Class<?> p = pt[i];
            if (p.isPrimitive()) {
                if (p == int.class && !(a instanceof Integer)) return false;
                if (p == boolean.class && !(a instanceof Boolean)) return false;
                if (p == long.class && !(a instanceof Long)) return false;
                continue;
            }
            if (!p.isAssignableFrom(a.getClass())) return false;
        }
        return true;
    }

    static String firstResultText(Object payload) {
        try {
            Object results = call(payload, "getResults");
            if (!(results instanceof List)) return null;
            for (Object item : (List<?>) results) {
                Object t = call(item, "getText");
                if (t instanceof String && ((String) t).trim().length() > 0) return (String) t;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static String payloadText(Object payload) {
        Object t = call(payload, "getText");
        if (t instanceof String) return (String) t;
        t = call(payload, "getTextWithTag");
        if (t instanceof String) return (String) t;
        return "";
    }

    static boolean isOurs(String t) {
        String a = sOurAnswer;
        if (a == null || t == null || t.length() == 0) return false;
        String x = a.replaceAll("\\s+", "");
        String y = t.replaceAll("\\s+", "");
        if (y.length() == 0) return false;
        int n = Math.min(10, Math.min(x.length(), y.length()));
        if (n <= 0) return false;
        return x.substring(0, n).equals(y.substring(0, n)) || y.contains(x) || x.contains(y);
    }

    static String cut(String s) {
        if (s == null) return "null";
        s = s.replace('\n', ' ');
        return s.length() > 70 ? s.substring(0, 70) + "…" : s;
    }

    static void maybeLogInst(String key, String payloadClass, Object payload) {
        long now = System.currentTimeMillis();
        if (Cfg.load().optBoolean("dump_inst", false)) {
            if (sDumpCount < 500) {
                sDumpCount++;
                // 打整条 instruction 的 JSON，才能看到文本挂在哪个字段
                String js = String.valueOf(sCurInst);
                if (js.length() > 700) js = js.substring(0, 700) + "…";
                Cfg.log("[dump] " + key + " " + js.replace('\n', ' '));
            }
            return;
        }
        if (key.equals(sLastInstKey) && now - sLastInstAt < 2000L) return;
        sLastInstKey = key;
        sLastInstAt = now;
        Cfg.log("[inst] " + key + " (" + payloadClass + ")");
    }

    private static volatile Object sCurInst = null;
}
