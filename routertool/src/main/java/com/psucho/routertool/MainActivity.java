package com.psucho.routertool;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Router Tool: runs diagnostic shell scripts on the Xiaomi AX6000 over SSH
 * (same actions as router.ps1 on the PC). Scripts live in res/raw.
 */
public class MainActivity extends Activity {

    // ---- palette: phosphor terminal ----
    private static final int BG = Color.parseColor("#0A0F0A");
    private static final int PANEL = Color.parseColor("#111A11");
    private static final int GREEN = Color.parseColor("#7CFC8A");
    private static final int DIM = Color.parseColor("#4E8F57");
    private static final int AMBER = Color.parseColor("#FFC857");
    private static final int RED = Color.parseColor("#FF6B6B");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private EditText hostEdit, userEdit, passEdit;
    private CheckBox rememberBox;
    private LinearLayout settingsPanel;
    private TextView statusLine, output;
    private ScrollView outputScroll;
    private Button liveButton;
    private final List<Button> actionButtons = new ArrayList<>();

    private volatile boolean busy = false;
    private volatile ChannelExec liveChannel = null;
    private volatile Session liveSession = null;

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(pad, pad, pad, pad);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets s = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                l = s.left; t = s.top; r = s.right; b = s.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(pad + l, pad + t, pad + r, pad + b);
            return insets;
        });

        // header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("> AX6000 ROUTER TOOL", 18, GREEN);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button gear = smallButton("SSH");
        gear.setOnClickListener(v -> settingsPanel.setVisibility(
                settingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        header.addView(gear);
        root.addView(header);

        statusLine = text("", 12, DIM);
        statusLine.setPadding(0, dp(2), 0, dp(8));
        root.addView(statusLine);

        root.addView(buildSettings());

        // actions (scrollable block with fixed max share of the screen)
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);

        actions.addView(section("ДИАГНОСТИКА"));
        addRow(actions,
                action("Статус", () -> runRaw("Статус", R.raw.status, false)),
                action("Журнал линка", () -> runRaw("Журнал физического линка", R.raw.linklog, false)));
        addRow(actions,
                action("Ошибки порта", () -> runRaw("Счётчики ошибок порта WAN", R.raw.mib, false)),
                action("Системный лог", () -> runRaw("Системный лог (WAN/PPP)", R.raw.syslog, true)));
        liveButton = action("Линк live", this::toggleLive);
        addRow(actions,
                action("Лог PPPoE", () -> runRaw("Лог PPPoE", R.raw.ppplog, true)),
                liveButton);

        actions.addView(section("НАСТРОЙКА"));
        addRow(actions,
                action("Монитор линка", () -> runRaw("Установка монитора линка", R.raw.linkmon_install, false)),
                action("Настройки PPPoE", () -> runRaw("Настройки PPPoE (persist/maxfail/лог)", R.raw.pppfix, false)));
        addRow(actions,
                action("Часовой пояс", () -> runRaw("Часовой пояс UTC+5", R.raw.timezone, false)),
                action("Удалить watchdog", () -> runRaw("Удаление старого watchdog", R.raw.cleanup, false)));

        actions.addView(section("ДЕЙСТВИЯ"));
        Button reconnect = action("Переподключить PPPoE", () -> confirm(
                "Интернет пропадёт примерно на 10 секунд. Переподключить?",
                () -> runRaw("Переподключение PPPoE", R.raw.reconnect, false)));
        Button reboot = action("Перезагрузить", () -> confirm(
                "Перезагрузить роутер? Сеть пропадёт на 1–2 минуты.",
                () -> runRaw("Перезагрузка роутера", R.raw.reboot, false)));
        reboot.setTextColor(RED);
        addRow(actions, reconnect, reboot);

        ScrollView actionsScroll = new ScrollView(this);
        actionsScroll.addView(actions);
        root.addView(actionsScroll, new LinearLayout.LayoutParams(-1, 0, 0.9f));

        // output
        output = text("Готово. Нажмите «Статус».\n", 12, GREEN);
        output.setTextIsSelectable(true);
        output.setPadding(dp(10), dp(10), dp(10), dp(10));
        outputScroll = new ScrollView(this);
        outputScroll.setBackground(box(PANEL, DIM, 1));
        outputScroll.addView(output);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, 0, 1.4f);
        olp.topMargin = dp(10);
        root.addView(outputScroll, olp);

        // output tools
        LinearLayout tools = new LinearLayout(this);
        tools.setPadding(0, dp(8), 0, 0);
        Button copy = smallButton("Копировать");
        copy.setOnClickListener(v -> copyOutput());
        Button share = smallButton("Поделиться");
        share.setOnClickListener(v -> shareOutput());
        Button save = smallButton("Все логи → файл");
        save.setOnClickListener(v -> saveAllLogs());
        Button clear = smallButton("Очистить");
        clear.setOnClickListener(v -> output.setText(""));
        for (Button b : new Button[]{copy, share, save, clear}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            tools.addView(b, lp);
        }
        actionButtons.add(save);
        root.addView(tools);

        setContentView(root);
        updateStatusLine();
        if (passEdit.getText().length() == 0) settingsPanel.setVisibility(View.VISIBLE);
    }

    private View buildSettings() {
        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setBackground(box(PANEL, DIM, 1));
        settingsPanel.setPadding(dp(10), dp(6), dp(10), dp(10));
        settingsPanel.setVisibility(View.GONE);

        hostEdit = field("Адрес роутера", prefs.getString("host", "192.168.31.1"), false);
        userEdit = field("Пользователь", prefs.getString("user", "root"), false);
        passEdit = field("Пароль SSH", prefs.getString("pass", ""), true);
        rememberBox = new CheckBox(this);
        rememberBox.setText("Запомнить пароль на телефоне");
        rememberBox.setTextColor(GREEN);
        rememberBox.setChecked(prefs.getBoolean("remember", true));

        Button saveBtn = smallButton("Сохранить");
        saveBtn.setOnClickListener(v -> {
            saveSettings();
            settingsPanel.setVisibility(View.GONE);
            toast("Сохранено");
        });

        settingsPanel.addView(hostEdit);
        settingsPanel.addView(userEdit);
        settingsPanel.addView(passEdit);
        settingsPanel.addView(rememberBox);
        settingsPanel.addView(saveBtn);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        settingsPanel.setLayoutParams(lp);
        return settingsPanel;
    }

    private void saveSettings() {
        SharedPreferences.Editor e = prefs.edit()
                .putString("host", hostEdit.getText().toString().trim())
                .putString("user", userEdit.getText().toString().trim())
                .putBoolean("remember", rememberBox.isChecked());
        if (rememberBox.isChecked()) e.putString("pass", passEdit.getText().toString());
        else e.remove("pass");
        e.apply();
        updateStatusLine();
    }

    private void updateStatusLine() {
        String state = busy ? "  [выполняется…]" : "";
        statusLine.setText(userEdit.getText() + "@" + hostEdit.getText() + ":22" + state);
        statusLine.setTextColor(busy ? AMBER : DIM);
    }

    // ------------------------------------------------------------------ actions

    private void runRaw(String title, int rawId, boolean withMask) {
        String script = readRaw(rawId);
        if (withMask) script = readRaw(R.raw.mask) + "\n" + script;
        runScript(title, script);
    }

    private void runScript(String title, String script) {
        if (busy) { toast("Подождите, команда ещё выполняется"); return; }
        if (!checkSettings()) return;
        setBusy(true);
        appendLine("");
        appendColored("=== " + title + " — " + now() + " ===", AMBER);
        String host = hostEdit.getText().toString().trim();
        String user = userEdit.getText().toString().trim();
        String pass = passEdit.getText().toString();
        executor.submit(() -> {
            Session s = null;
            ChannelExec ch = null;
            try {
                s = connect(host, user, pass);
                Exec x = exec(s, script);
                ch = x.ch;
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(x.out, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    final String l = line;
                    ui.post(() -> appendLine(l));
                }
            } catch (Exception e) {
                final String msg = describe(e);
                ui.post(() -> appendColored("ОШИБКА: " + msg, RED));
            } finally {
                if (ch != null) ch.disconnect();
                if (s != null) s.disconnect();
                ui.post(() -> setBusy(false));
            }
        });
    }

    private void toggleLive() {
        if (liveChannel != null || liveSession != null) {
            stopLive();
            return;
        }
        if (busy) { toast("Подождите, команда ещё выполняется"); return; }
        if (!checkSettings()) return;
        setBusy(true);
        liveButton.setEnabled(true);
        liveButton.setAlpha(1f);
        liveButton.setText("■ Стоп");
        appendLine("");
        appendColored("=== Линк в реальном времени (каждые 2 с, до 1 ч) — " + now() + " ===", AMBER);
        String host = hostEdit.getText().toString().trim();
        String user = userEdit.getText().toString().trim();
        String pass = passEdit.getText().toString();
        String script = readRaw(R.raw.live);
        executor.submit(() -> {
            try {
                liveSession = connect(host, user, pass);
                Exec x = exec(liveSession, script);
                liveChannel = x.ch;
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(x.out, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    final String l = line;
                    final boolean down = l.contains("link:down") || l.contains("carrier=0");
                    ui.post(() -> {
                        if (down) appendColored(l, RED); else appendLine(l);
                    });
                }
            } catch (Exception e) {
                if (liveChannel != null || liveSession != null) {
                    final String msg = describe(e);
                    ui.post(() -> appendColored("ОШИБКА: " + msg, RED));
                }
            } finally {
                stopLive();
            }
        });
    }

    private void stopLive() {
        ChannelExec ch = liveChannel;
        Session s = liveSession;
        liveChannel = null;
        liveSession = null;
        // disconnect off the UI thread
        new Thread(() -> {
            if (ch != null) ch.disconnect();
            if (s != null) s.disconnect();
        }).start();
        ui.post(() -> {
            liveButton.setText("Линк live");
            if (busy) appendColored("— наблюдение остановлено —", DIM);
            setBusy(false);
        });
    }

    private void saveAllLogs() {
        if (busy) { toast("Подождите, команда ещё выполняется"); return; }
        if (!checkSettings()) return;
        setBusy(true);
        appendLine("");
        appendColored("=== Сбор всех логов — " + now() + " ===", AMBER);
        String script = readRaw(R.raw.mask) + "\n"
                + "echo '===== STATUS'\n" + readRaw(R.raw.status) + "\n"
                + "echo; echo '===== LINK MONITOR (/data/linkmon.log)'\n" + readRaw(R.raw.linklog) + "\n"
                + "echo; echo '===== PORT ERRORS'\n" + readRaw(R.raw.mib) + "\n"
                + "echo; echo '===== PPP LOG (/tmp/ppp.log)'\n"
                + "cat /tmp/ppp.log 2>/dev/null | mask\n"
                + "echo; echo '===== SYSTEM LOG (/tmp/messages)'\n"
                + "cat /tmp/messages 2>/dev/null | mask\n";
        String host = hostEdit.getText().toString().trim();
        String user = userEdit.getText().toString().trim();
        String pass = passEdit.getText().toString();
        executor.submit(() -> {
            Session s = null;
            ChannelExec ch = null;
            try {
                s = connect(host, user, pass);
                Exec x = exec(s, script);
                ch = x.ch;
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                InputStream in = x.out;
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                String name = "router_logs_"
                        + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + ".txt";
                writeDownload(name, buf.toByteArray());
                final int kb = buf.size() / 1024;
                ui.post(() -> appendColored("Сохранено в «Загрузки»: " + name + " (" + kb + " КБ)\n"
                        + "Логин и пароль PPPoE заменены на ***", GREEN));
            } catch (Exception e) {
                final String msg = describe(e);
                ui.post(() -> appendColored("ОШИБКА: " + msg, RED));
            } finally {
                if (ch != null) ch.disconnect();
                if (s != null) s.disconnect();
                ui.post(() -> setBusy(false));
            }
        });
    }

    // ------------------------------------------------------------------ SSH

    private Session connect(String host, String user, String pass) throws JSchException {
        JSch jsch = new JSch();
        Session s = jsch.getSession(user, host, 22);
        s.setPassword(pass);
        s.setConfig("StrictHostKeyChecking", "no");   // the router's host key changes on every reboot
        s.setConfig("PreferredAuthentications", "password");
        // the router runs an old Dropbear: allow legacy algorithms in addition to modern ones
        s.setConfig("server_host_key", s.getConfig("server_host_key") + ",ssh-rsa");
        s.setConfig("PubkeyAcceptedAlgorithms", s.getConfig("PubkeyAcceptedAlgorithms") + ",ssh-rsa");
        s.setConfig("kex", s.getConfig("kex") + ",diffie-hellman-group14-sha1,diffie-hellman-group1-sha1");
        s.setConfig("cipher.s2c", s.getConfig("cipher.s2c") + ",aes128-cbc,aes256-cbc");
        s.setConfig("cipher.c2s", s.getConfig("cipher.c2s") + ",aes128-cbc,aes256-cbc");
        s.setConfig("mac.s2c", s.getConfig("mac.s2c") + ",hmac-sha1");
        s.setConfig("mac.c2s", s.getConfig("mac.c2s") + ",hmac-sha1");
        s.setServerAliveInterval(15000);
        s.connect(8000);
        return s;
    }

    /** A running remote command: its channel and its stdout (stderr is merged in). */
    private static final class Exec {
        final ChannelExec ch;
        final InputStream out;
        Exec(ChannelExec ch, InputStream out) { this.ch = ch; this.out = out; }
    }

    /** Runs the script through `sh` on the router: script goes to stdin, stderr is merged into stdout. */
    private Exec exec(Session s, String script) throws JSchException, IOException {
        ChannelExec ch = (ChannelExec) s.openChannel("exec");
        ch.setCommand("sh");
        InputStream out = ch.getInputStream(); // must be requested before connect, and only once
        OutputStream stdin = ch.getOutputStream();
        ch.connect(8000);
        stdin.write(("exec 2>&1\n" + script.replace("\r", "") + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        stdin.close();
        return new Exec(ch, out);
    }

    private String describe(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("Auth fail")) return "неверный логин или пароль SSH";
        if (m.contains("timeout") || m.contains("timed out")) return "роутер не отвечает (вы в домашней сети Wi-Fi?)";
        if (m.contains("Connection refused")) return "SSH на роутере не запущен";
        if (m.contains("UnknownHost") || m.contains("unreachable")) return "адрес роутера недоступен";
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private boolean checkSettings() {
        if (passEdit.getText().length() == 0 || hostEdit.getText().length() == 0) {
            settingsPanel.setVisibility(View.VISIBLE);
            toast("Укажите адрес и пароль SSH");
            return false;
        }
        if (rememberBox.isChecked()) saveSettings();
        return true;
    }

    private void setBusy(boolean b) {
        busy = b;
        for (Button btn : actionButtons) {
            if (btn == liveButton && (liveChannel != null || liveSession != null)) continue;
            btn.setEnabled(!b);
            btn.setAlpha(b ? 0.45f : 1f);
        }
        if (!b) { liveButton.setEnabled(true); liveButton.setAlpha(1f); }
        updateStatusLine();
    }

    private void confirm(String msg, Runnable yes) {
        new AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton("Да", (d, w) -> yes.run())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void appendLine(String s) {
        output.append(s + "\n");
        scrollDown();
    }

    private void appendColored(String s, int color) {
        android.text.SpannableString sp = new android.text.SpannableString(s + "\n");
        sp.setSpan(new android.text.style.ForegroundColorSpan(color), 0, sp.length(), 0);
        output.append(sp);
        scrollDown();
    }

    private void scrollDown() {
        outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void copyOutput() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("router", output.getText()));
        toast("Скопировано");
    }

    private void shareOutput() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, output.getText().toString());
        startActivity(Intent.createChooser(i, "Поделиться выводом"));
    }

    private void writeDownload(String name, byte[] data) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        cv.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("не удалось создать файл в «Загрузках»");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("не удалось открыть файл");
            os.write(data);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(uri, done, null, null);
    }

    private String readRaw(int id) {
        try (InputStream in = getResources().openRawResource(id)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "echo 'cannot read script'";
        }
    }

    private String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private TextView section(String s) {
        TextView t = text("// " + s, 11, DIM);
        t.setPadding(dp(2), dp(10), 0, dp(4));
        return t;
    }

    private GradientDrawable box(int fill, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(strokeDp), stroke);
        g.setCornerRadius(dp(2));
        return g;
    }

    private Button action(String label, Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(GREEN);
        b.setTypeface(Typeface.MONOSPACE);
        b.setBackground(box(PANEL, GREEN, 1));
        b.setMinHeight(dp(44));
        b.setOnClickListener(v -> r.run());
        actionButtons.add(b);
        return b;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setTextColor(GREEN);
        b.setTypeface(Typeface.MONOSPACE);
        b.setBackground(box(PANEL, DIM, 1));
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private void addRow(LinearLayout parent, Button a, Button b) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, -2, 1);
        la.setMargins(0, dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, -2, 1);
        lb.setMargins(dp(3), dp(3), 0, dp(3));
        row.addView(a, la);
        row.addView(b, lb);
        parent.addView(row);
    }

    private EditText field(String hint, String value, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(GREEN);
        e.setHintTextColor(DIM);
        e.setTypeface(Typeface.MONOSPACE);
        e.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (password) e.setTypeface(Typeface.MONOSPACE);
        return e;
    }

    @Override
    protected void onDestroy() {
        stopLive();
        executor.shutdownNow();
        super.onDestroy();
    }
}
