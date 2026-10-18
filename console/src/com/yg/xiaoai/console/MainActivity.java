package com.yg.xiaoai.console;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class MainActivity extends Activity {

    private static final String CONF = "/sdcard/xiaoai_bridge.json";

    private static final int C_BG = Color.rgb(14, 20, 32);
    private static final int C_CARD = Color.rgb(26, 34, 51);
    private static final int C_ACCENT = Color.rgb(77, 208, 225);
    private static final int C_TEXT = Color.rgb(232, 238, 250);
    private static final int C_DIM = Color.rgb(140, 154, 181);

    private JSONObject cfg = new JSONObject();
    private LinearLayout contentBox;
    private TextView status;
    private final TextView[] tabs = new TextView[3];

    // API 页
    private EditText etUrl, etKey, etModel;
    // 人设页
    private EditText etPrompt;
    private int presetSel = -1;
    private TextView[] presetBtns;
    // 高级页
    private EditText etPass, etHistory;
    private CheckBox cbEnabled, cbDebug;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        loadCfg();
        buildUi();
    }

    // ================= 配置读写 =================

    private void loadCfg() {
        try {
            File f = new File(CONF);
            if (f.exists()) {
                String s = new String(readAll(f), "UTF-8");
                if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
                cfg = new JSONObject(s);
            }
        } catch (Throwable t) {
            cfg = new JSONObject();
            toast("配置读取失败：" + t.getMessage());
        }
    }

    private void saveCfg() {
        try {
            // 保留未知字段，只更新界面上的
            cfg.put("llm_url", txt(etUrl));
            cfg.put("llm_key", txt(etKey));
            cfg.put("llm_model", txt(etModel));
            cfg.put("sys_prompt", txt(etPrompt));
            cfg.put("passthrough_keywords", txt(etPass));
            try {
                cfg.put("history", Integer.parseInt(txt(etHistory)));
            } catch (Throwable ignored) {
            }
            cfg.put("enabled", cbEnabled.isChecked());
            cfg.put("debug", cbDebug.isChecked());

            File f = new File(CONF);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(cfg.toString(2).getBytes("UTF-8"));
            fos.close();
            status.setText("已保存，2 秒内生效");
            toast("已保存");
        } catch (Throwable t) {
            toast("保存失败：" + t.getMessage());
            status.setText("保存失败");
        }
    }

    private static String txt(EditText e) {
        return e == null ? "" : e.getText().toString().trim();
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private String str(String k, String def) {
        String v = cfg.optString(k, "");
        return v.length() == 0 ? def : v;
    }

    // ================= 界面 =================

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), dp(8), dp(24), dp(8));

        TextView title = new TextView(this);
        title.setText("小爱DS 控制台");
        title.setTextColor(C_ACCENT);
        title.setTextSize(20);
        header.addView(title);

        View sp = new View(this);
        header.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        String[] names = {"接口", "人设", "高级"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(16);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(22), dp(8), dp(22), dp(8));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    switchTab(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(6), 0, 0, 0);
            header.addView(t, lp);
            tabs[i] = t;
        }
        col.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        // content
        contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(contentBox, new LinearLayout.LayoutParams(-1, 0, 1f));

        // footer
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(24), dp(8), dp(24), dp(12));

        status = new TextView(this);
        status.setTextColor(C_DIM);
        status.setTextSize(14);
        status.setText("改完点保存，模块 2 秒内自动重读");
        footer.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));

        Button save = new Button(this);
        save.setText("保存并生效");
        save.setTextSize(16);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveCfg();
            }
        });
        footer.addView(save, new LinearLayout.LayoutParams(dp(180), dp(48)));

        col.addView(footer, new LinearLayout.LayoutParams(-1, dp(64)));

        setContentView(root);
        switchTab(0);
    }

    private void switchTab(int idx) {
        for (int i = 0; i < 3; i++) {
            boolean on = (i == idx);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(14));
            d.setColor(on ? C_ACCENT : C_CARD);
            tabs[i].setBackground(d);
            tabs[i].setTextColor(on ? Color.rgb(14, 20, 32) : C_TEXT);
        }
        contentBox.removeAllViews();
        if (idx == 0) buildApi();
        else if (idx == 1) buildPersona();
        else buildAdvanced();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_DIM);
        t.setTextSize(14);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private EditText field(String value, int inputType) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(C_TEXT);
        e.setTextSize(16);
        e.setInputType(inputType);
        e.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        e.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable d = new GradientDrawable();
        d.setColor(C_CARD);
        d.setCornerRadius(dp(10));
        e.setBackground(d);
        e.setPadding(dp(14), dp(8), dp(14), dp(8));
        return e;
    }

    private ScrollView scroll(LinearLayout inner) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        inner.setPadding(dp(24), dp(4), dp(24), dp(16));
        sv.addView(inner, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    // ================= 接口页 =================

    private void buildApi() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(label("API 地址（OpenAI 兼容）"));
        etUrl = field(str("llm_url", "https://api.deepseek.com/v1/chat/completions"),
                InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(etUrl);

        box.addView(label("API Key"));
        etKey = field(str("llm_key", ""), InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(etKey);

        box.addView(label("模型名"));
        etModel = field(str("llm_model", "deepseek-chat"), InputType.TYPE_CLASS_TEXT);
        box.addView(etModel);

        box.addView(label("快捷填充"));
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        addQuick(quick, "DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat");
        addQuick(quick, "硅基流动", "https://api.siliconflow.cn/v1/chat/completions", "deepseek-ai/DeepSeek-V3");
        addQuick(quick, "阿里百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "deepseek-v3");
        box.addView(quick);

        TextView tip = new TextView(this);
        tip.setText("\n提示：填完点右下角保存。Key 只存在设备本地 /sdcard/xiaoai_bridge.json。");
        tip.setTextColor(C_DIM);
        tip.setTextSize(13);
        box.addView(tip);

        contentBox.addView(scroll(box), new LinearLayout.LayoutParams(-1, -1));
    }

    private void addQuick(LinearLayout parent, final String name, final String url, final String model) {
        Button b = new Button(this);
        b.setText(name);
        b.setTextSize(14);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                etUrl.setText(url);
                etModel.setText(model);
                toast("已填入 " + name + "，记得保存");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
        lp.setMargins(0, dp(6), dp(10), 0);
        parent.addView(b, lp);
    }

    // ================= 人设页 =================

    private void buildPersona() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        String cur = str("sys_prompt", "");
        presetSel = Presets.match(cur);

        box.addView(label("预设（点一下填入）"));
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        presetBtns = new TextView[Presets.NAMES.length];

        for (int i = 0; i < Presets.NAMES.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 2 && j < Presets.NAMES.length; j++) {
                final int idx = j;
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setPadding(dp(14), dp(10), dp(14), dp(10));

                TextView n = new TextView(this);
                n.setText(Presets.NAMES[j]);
                n.setTextSize(16);
                n.setTextColor(C_TEXT);
                cell.addView(n);

                TextView d = new TextView(this);
                d.setText(Presets.DESC[j]);
                d.setTextSize(12);
                d.setTextColor(C_DIM);
                cell.addView(d);

                cell.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        presetSel = idx;
                        etPrompt.setText(Presets.full(idx));
                        refreshPresetHighlight();
                        toast("已选 " + Presets.NAMES[idx]);
                    }
                });

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(0, 0, dp(10), dp(10));
                row.addView(cell, lp);
                presetBtns[j] = n;
            }
            grid.addView(row);
        }
        box.addView(grid);

        box.addView(label("完整人设（可自由修改）"));
        etPrompt = field(cur, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etPrompt.setMinLines(4);
        etPrompt.setGravity(Gravity.TOP);
        box.addView(etPrompt, new LinearLayout.LayoutParams(-1, dp(150)));

        Button reset = new Button(this);
        reset.setText("恢复默认约束");
        reset.setTextSize(14);
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                etPrompt.setText(Presets.full(8));
                presetSel = 8;
                refreshPresetHighlight();
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, dp(44));
        rlp.topMargin = dp(8);
        box.addView(reset, rlp);

        contentBox.addView(scroll(box), new LinearLayout.LayoutParams(-1, -1));
        refreshPresetHighlight();
    }

    private void refreshPresetHighlight() {
        if (presetBtns == null) return;
        for (int i = 0; i < presetBtns.length; i++) {
            if (presetBtns[i] == null) continue;
            presetBtns[i].setTextColor(i == presetSel ? C_ACCENT : C_TEXT);
        }
    }

    // ================= 高级页 =================

    private void buildAdvanced() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        cbEnabled = new CheckBox(this);
        cbEnabled.setText("启用桥接（关掉立刻恢复原生小爱）");
        cbEnabled.setTextColor(C_TEXT);
        cbEnabled.setTextSize(16);
        cbEnabled.setChecked(cfg.optBoolean("enabled", true));
        box.addView(cbEnabled);

        cbDebug = new CheckBox(this);
        cbDebug.setText("写调试日志 /sdcard/xiaoai_bridge.log");
        cbDebug.setTextColor(C_TEXT);
        cbDebug.setTextSize(16);
        cbDebug.setChecked(cfg.optBoolean("debug", true));
        box.addView(cbDebug);

        box.addView(label("设备控制直通关键词（逗号分隔，短句命中就走原生）"));
        etPass = field(cfg.optString("passthrough_keywords", ""), InputType.TYPE_CLASS_TEXT);
        etPass.setHint("打开,关闭,关掉,调高,调低");
        etPass.setHintTextColor(C_DIM);
        box.addView(etPass);

        box.addView(label("多轮记忆轮数"));
        etHistory = field(String.valueOf(cfg.optInt("history", 6)),
                InputType.TYPE_CLASS_NUMBER);
        box.addView(etHistory);

        box.addView(label("操作"));
        Button restart = new Button(this);
        restart.setText("重启小爱进程（立即生效）");
        restart.setTextSize(15);
        restart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                restartXiaoai();
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(48));
        rlp.topMargin = dp(6);
        box.addView(restart, rlp);

        Button log = new Button(this);
        log.setText("查看最近日志");
        log.setTextSize(15);
        log.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showLog();
            }
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, dp(48));
        llp.topMargin = dp(8);
        box.addView(log, llp);

        contentBox.addView(scroll(box), new LinearLayout.LayoutParams(-1, -1));
    }

    private void restartXiaoai() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "am force-stop com.xiaomi.soundboxassist"
            });
            int code = p.waitFor();
            if (code == 0) {
                toast("已重启小爱进程");
            } else {
                toast("重启失败，需要 root");
            }
        } catch (Throwable t) {
            toast("重启失败：" + t.getMessage());
        }
    }

    private void showLog() {
        String text;
        try {
            File f = new File("/sdcard/xiaoai_bridge.log");
            if (!f.exists()) {
                text = "还没有日志";
            } else {
                byte[] all = readAll(f);
                int start = Math.max(0, all.length - 4000);
                text = new String(all, start, all.length - start, "UTF-8");
            }
        } catch (Throwable t) {
            text = "读取失败：" + t.getMessage();
        }
        new AlertDialog.Builder(this)
                .setTitle("最近日志")
                .setMessage(text)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
