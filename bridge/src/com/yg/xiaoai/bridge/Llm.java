package com.yg.xiaoai.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** DeepSeek / 任意 OpenAI 兼容 chat 接口，支持 function calling。 */
public final class Llm {

    /** 执行一条设备控制指令，返回给模型看的执行结果描述 */
    public interface ToolExecutor {
        String execute(String name, String args);
    }

    private static final List<String[]> HISTORY = new ArrayList<String[]>();

    private Llm() {}

    public static String chat(String userText) throws Exception {
        return chat(userText, null);
    }

    public static String chat(String userText, ToolExecutor exec) throws Exception {
        String key = Cfg.llmKey();
        if (key == null || key.length() == 0) {
            throw new IllegalStateException("没有配置 llm_key，见 /sdcard/xiaoai_bridge.json");
        }

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", Cfg.sysPrompt()));
        synchronized (HISTORY) {
            for (String[] m : HISTORY) {
                messages.put(new JSONObject().put("role", m[0]).put("content", m[1]));
            }
        }
        messages.put(new JSONObject().put("role", "user").put("content", userText));

        String answer = null;
        boolean usedTool = false;

        for (int round = 0; round < 4; round++) {
            JSONObject body = new JSONObject();
            body.put("model", Cfg.llmModel());
            body.put("stream", false);
            body.put("temperature", 0.7);
            body.put("max_tokens", 600);
            body.put("messages", messages);
            if (exec != null && !usedTool) {
                body.put("tools", tools());
                body.put("tool_choice", "auto");
            }

            String resp = Cfg.postJson(Cfg.llmUrl(), key, body.toString(), 60000);
            JSONObject o = new JSONObject(resp);
            JSONArray ch = o.optJSONArray("choices");
            if (ch == null || ch.length() == 0) throw new IllegalStateException("模型返回为空");
            JSONObject msg = ch.getJSONObject(0).optJSONObject("message");
            if (msg == null) throw new IllegalStateException("模型返回为空");

            JSONArray toolCalls = msg.optJSONArray("tool_calls");
            String content = msg.optString("content", "");

            if (toolCalls == null || toolCalls.length() == 0 || exec == null) {
                answer = content;
                break;
            }

            usedTool = true;
            Cfg.log("[tool] 模型要调用 " + toolCalls.length() + " 个工具");

            // 把 assistant 的 tool_calls 消息加回去
            messages.put(msg);

            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                String id = tc.optString("id", "");
                JSONObject fn = tc.optJSONObject("function");
                String name = fn == null ? "" : fn.optString("name", "");
                String args = fn == null ? "{}" : fn.optString("arguments", "{}");
                String result;
                try {
                    result = exec.execute(name, args);
                } catch (Throwable e) {
                    result = "执行失败：" + e.getMessage();
                }
                Cfg.log("[tool] " + name + "(" + args + ") -> " + result);
                messages.put(new JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", result));
            }
            // 下一轮拿最终回答
        }

        if (answer == null) answer = "";
        answer = clean(answer);
        if (answer.trim().length() == 0) {
            throw new IllegalStateException("模型返回为空");
        }

        synchronized (HISTORY) {
            HISTORY.add(new String[]{"user", userText});
            HISTORY.add(new String[]{"assistant", answer});
            int max = Math.max(2, Cfg.history() * 2);
            while (HISTORY.size() > max) HISTORY.remove(0);
        }
        return answer;
    }

    /** 给模型的家居控制工具 */
    private static JSONArray tools() throws Exception {
        JSONObject cmdProp = new JSONObject();
        cmdProp.put("type", "string");
        cmdProp.put("description",
                "要执行的中文指令，用自然口语，就像对小爱说话一样。"
                + "例如：关灯 / 把空调调到26度 / 打开客厅窗帘 / 把音量调小一点");

        JSONObject props = new JSONObject();
        props.put("command", cmdProp);

        JSONObject params = new JSONObject();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", new JSONArray().put("command"));

        JSONObject fn = new JSONObject();
        fn.put("name", "control_device");
        fn.put("description",
                "控制家里的智能设备：开关灯、开关空调、调温度、开关窗帘、开关插座、"
                + "调音量亮度、开关扫地机风扇加湿器等。"
                + "用户想操作设备时必须调用它，不要只在回答里描述。"
                + "一句话里有多个动作就调用多次。");
        fn.put("parameters", params);

        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        tool.put("function", fn);

        JSONArray arr = new JSONArray();
        arr.put(tool);
        return arr;
    }

    /** 念出来要干净：去掉 markdown / 表情 / 多余换行 */
    public static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("```[\\s\\S]*?```", " ");
        s = s.replaceAll("[`*_#>\\[\\]]", "");
        s = s.replaceAll("[\\uD800-\\uDFFF]", "");
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("\\n{2,}", "\n");
        s = s.replace('\n', '，');
        s = s.replaceAll("[，,]{2,}", "，");
        s = s.trim();
        if (s.length() > 220) {
            int cut = -1;
            for (int i = 120; i < 220 && i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '。' || c == '！' || c == '？' || c == '；') {
                    cut = i + 1;
                    break;
                }
            }
            if (cut > 0) s = s.substring(0, cut);
            else s = s.substring(0, 220) + "。";
        }
        return s;
    }

    public static void resetHistory() {
        synchronized (HISTORY) {
            HISTORY.clear();
        }
    }
}
