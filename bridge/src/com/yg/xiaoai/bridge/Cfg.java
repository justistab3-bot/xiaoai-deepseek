package com.yg.xiaoai.bridge;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 配置文件 + 日志。都放在 /sdcard 上，PC 端 adb push 就能改，
 * soundboxassist 已经有 READ_EXTERNAL_STORAGE 授权，能直接读。
 */
public final class Cfg {

    public static final String CONF = "/sdcard/xiaoai_bridge.json";
    public static final String LOG = "/sdcard/xiaoai_bridge.log";
    public static final String TAG = "XiaoAiBridge";

    private static volatile long sLastLoad = 0L;
    private static volatile JSONObject sCache = null;

    public static final String DEF_SYS =
            "你是小爱同学，一台放在家里的智能音箱。回答口语化、简短、自然，"
            + "通常一两句话，不要用 Markdown、列表、代码块、表情符号，"
            + "因为你说的话会被直接念出来。";

    private Cfg() {}

    public static JSONObject load() {
        long now = System.currentTimeMillis();
        if (sCache != null && now - sLastLoad < 2000L) return sCache;
        JSONObject o = new JSONObject();
        try {
            File f = new File(CONF);
            if (f.exists()) {
                byte[] b = readAll(new FileInputStream(f));
                String s = new String(b, "UTF-8");
                if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
                o = new JSONObject(s);
            }
        } catch (Throwable t) {
            log("read conf fail: " + t);
        }
        sCache = o;
        sLastLoad = now;
        return o;
    }

    public static boolean enabled() {
        return load().optBoolean("enabled", true);
    }

    public static String str(String key, String def) {
        String v = load().optString(key, "");
        return (v == null || v.length() == 0) ? def : v;
    }

    public static String llmUrl() {
        return str("llm_url", "https://api.deepseek.com/v1/chat/completions");
    }

    public static String llmKey() {
        return str("llm_key", "");
    }

    public static String llmModel() {
        return str("llm_model", "deepseek-chat");
    }

    public static String sysPrompt() {
        return str("sys_prompt", DEF_SYS);
    }

    public static int history() {
        return load().optInt("history", 6);
    }

    public static boolean debug() {
        return load().optBoolean("debug", true);
    }

    public static void log(String msg) {
        if (!debug()) return;
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg + "\n";
        Log.i(TAG, msg);
        try {
            File f = new File(LOG);
            if (f.exists() && f.length() > 512 * 1024) {
                File old = new File(LOG + ".1");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            FileOutputStream fos = new FileOutputStream(LOG, true);
            fos.write(line.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    public static void logE(String msg, Throwable t) {
        log(msg + " :: " + (t == null ? "" : (t.getClass().getSimpleName() + ": " + t.getMessage())));
    }

    public static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    /** 极简 POST JSON，返回响应体 */
    public static String postJson(String url, String key, String body, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        if (key != null && key.length() > 0) c.setRequestProperty("Authorization", "Bearer " + key);
        byte[] payload = body.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(payload.length);
        OutputStream os = c.getOutputStream();
        os.write(payload);
        os.flush();
        os.close();
        int code = c.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : new String(readAll(in), "UTF-8");
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " " + trim(text));
        return text;
    }

    public static String trim(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    public static String quote(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }
}
