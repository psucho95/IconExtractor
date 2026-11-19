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
    private static final int BG = Palette.BG;
    private static final int PANEL = Palette.PANEL;
    private static final int GREEN = Palette.GREEN;
    private static final int DIM = Palette.DIM;
    private static final int AMBER = Palette.AMBER;
    private static final int RED = Palette.RED;

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
    private final Button[] tabButtons = new Button[3];
    private final View[] panes = new View[3];

    // charts state
    private LogData chartData;
    private long rangeSec = 86400;
    private boolean chartsLoaded = false;
    private final Button[] rangeButtons = new Button[4];
    private static final long[] RANGES = {86400, 3 * 86400, 7 * 86400, -1};
    private TextView chartSummary, chartInsight, chartIncidents, chartNote, chartState;
    private TimelineView timeline;
    private BarChartView daysChart, hoursChart;
    private Button chartRefresh;
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

        // tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, 0, 0, dp(8));
        String[] tabNames = {"Команды", "Графики", "FAQ"};
        for (int i = 0; i < tabNames.length; i++) {
            final int idx = i;
            Button t = smallButton(tabNames[i]);
            t.setOnClickListener(v -> showTab(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            tabs.addView(t, lp);
            tabButtons[i] = t;
        }
        root.addView(tabs);

        LinearLayout commandsPane = new LinearLayout(this);
        commandsPane.setOrientation(LinearLayout.VERTICAL);
        panes[0] = commandsPane;
        panes[1] = buildChartsPane();
        panes[2] = buildFaqPane();
        for (View pane : panes) root.addView(pane, new LinearLayout.LayoutParams(-1, 0, 1));

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
        commandsPane.addView(actionsScroll, new LinearLayout.LayoutParams(-1, 0, 0.9f));

        // output
        output = text("Готово. Нажмите «Статус».\n", 12, GREEN);
        output.setTextIsSelectable(true);
        output.setPadding(dp(10), dp(10), dp(10), dp(10));
        outputScroll = new ScrollView(this);
        outputScroll.setBackground(box(PANEL, DIM, 1));
        outputScroll.addView(output);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, 0, 1.4f);
        olp.topMargin = dp(10);
        commandsPane.addView(outputScroll, olp);

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
        commandsPane.addView(tools);

        setContentView(root);
        showTab(0);
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

    // ------------------------------------------------------------------ tabs

    private void showTab(int idx) {
        for (int i = 0; i < panes.length; i++) {
            panes[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabButtons[i].setBackground(box(i == idx ? Palette.UP : PANEL, i == idx ? GREEN : DIM, 1));
        }
        if (idx == 1 && !chartsLoaded) loadCharts();
    }

    // ------------------------------------------------------------------ charts

    private View buildChartsPane() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout ranges = new LinearLayout(this);
        String[] names = {"24 ч", "3 дня", "7 дней", "Всё"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button b = smallButton(names[i]);
            b.setOnClickListener(v -> {
                rangeSec = RANGES[idx];
                renderCharts();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            ranges.addView(b, lp);
            rangeButtons[i] = b;
        }
        chartRefresh = smallButton("⟳");
        chartRefresh.setOnClickListener(v -> loadCharts());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(dp(48), -2);
        rl.setMargins(dp(2), 0, dp(2), 0);
        ranges.addView(chartRefresh, rl);
        col.addView(ranges);

        chartState = text("", 11, DIM);
        chartState.setPadding(dp(2), dp(6), 0, 0);
        col.addView(chartState);

        chartSummary = text("", 13, GREEN);
        chartSummary.setBackground(box(PANEL, DIM, 1));
        chartSummary.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(6);
        col.addView(chartSummary, sl);

        chartInsight = text("", 12, AMBER);
        chartInsight.setPadding(dp(2), dp(6), dp(2), 0);
        col.addView(chartInsight);

        col.addView(section("ХРОНОЛОГИЯ"));
        timeline = new TimelineView(this);
        col.addView(timeline);
        col.addView(legend());

        col.addView(section("ОТВАЛЫ ПО ДНЯМ (7 ДНЕЙ, ПОДПИСЬ — ПРОСТОЙ)"));
        daysChart = new BarChartView(this);
        col.addView(daysChart);

        col.addView(section("ПО ЧАСАМ СУТОК (ЗА ВЫБРАННЫЙ ПЕРИОД)"));
        hoursChart = new BarChartView(this);
        col.addView(hoursChart);

        col.addView(section("ИНЦИДЕНТЫ (НОВЫЕ СВЕРХУ)"));
        chartIncidents = text("", 12, GREEN);
        chartIncidents.setTextIsSelectable(true);
        col.addView(chartIncidents);

        chartNote = text("", 10, DIM);
        chartNote.setPadding(0, dp(12), 0, dp(12));
        col.addView(chartNote);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        return sv;
    }

    private View legend() {
        TextView t = new TextView(this);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(10);
        t.setPadding(dp(2), dp(4), 0, 0);
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        legendItem(b, Palette.UP, "работает  ");
        legendItem(b, RED, "отвал  ");
        legendItem(b, Palette.NODATA, "нет данных  ");
        legendItem(b, AMBER, "▼ сброс сессии провайдером");
        t.setText(b);
        return t;
    }

    private void legendItem(android.text.SpannableStringBuilder b, int color, String label) {
        int st = b.length();
        b.append("■ ");
        b.setSpan(new android.text.style.ForegroundColorSpan(color == Palette.NODATA ? Color.GRAY : color),
                st, st + 1, 0);
        int st2 = b.length();
        b.append(label);
        b.setSpan(new android.text.style.ForegroundColorSpan(DIM), st2, b.length(), 0);
    }

    private void loadCharts() {
        if (busy) { toast("Подождите, команда ещё выполняется"); return; }
        if (!checkSettings()) return;
        setBusy(true);
        chartState.setText("Загрузка логов с роутера…");
        chartState.setTextColor(AMBER);
        String host = hostEdit.getText().toString().trim();
        String user = userEdit.getText().toString().trim();
        String pass = passEdit.getText().toString();
        String script = readRaw(R.raw.charts);
        executor.submit(() -> {
            Session s = null;
            ChannelExec ch = null;
            try {
                s = connect(host, user, pass);
                Exec x = exec(s, script);
                ch = x.ch;
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = x.out.read(b)) > 0) buf.write(b, 0, n);
                LogData d = LogData.parse(buf.toString(StandardCharsets.UTF_8.name()));
                ui.post(() -> {
                    chartData = d;
                    chartsLoaded = true;
                    chartState.setText("Обновлено " + now());
                    chartState.setTextColor(DIM);
                    renderCharts();
                });
            } catch (Exception e) {
                final String msg = describe(e);
                ui.post(() -> {
                    chartState.setText("ОШИБКА: " + msg);
                    chartState.setTextColor(RED);
                });
            } finally {
                if (ch != null) ch.disconnect();
                if (s != null) s.disconnect();
                ui.post(() -> setBusy(false));
            }
        });
    }

    private void renderCharts() {
        for (int i = 0; i < rangeButtons.length; i++) {
            boolean on = RANGES[i] == rangeSec;
            rangeButtons[i].setBackground(box(on ? Palette.UP : PANEL, on ? GREEN : DIM, 1));
        }
        LogData d = chartData;
        if (d == null) return;
        long to = d.now;
        long earliest = Math.min(d.coverageStart[LogData.LANE_LINK], d.coverageStart[LogData.LANE_PPP]);
        if (earliest == Long.MAX_VALUE) earliest = to - 86400;
        long from = rangeSec > 0 ? to - rangeSec : Math.min(earliest, to - 3600);

        // summary
        LogData.Stats st = d.stats(from, to);
        StringBuilder sb = new StringBuilder();
        sb.append("Отвалов: ").append(st.count);
        if (st.count > 0) sb.append("  (кабель ").append(st.physical).append(", только PPPoE ").append(st.pppOnly).append(")");
        sb.append("\nПростой: ").append(dur(st.downtime));
        if (st.count > 0) sb.append("  ·  самый долгий: ").append(dur(st.longest));
        sb.append(String.format(Locale.US, "\nДоступность: %.2f %%", st.availability * 100));
        LogData.Incident last = d.incidents.isEmpty() ? null : d.incidents.get(d.incidents.size() - 1);
        if (last != null) {
            sb.append("\nПоследний: ").append(fmt(last.start, "dd.MM HH:mm"));
            if (last.ongoing) sb.append(" — продолжается!");
            else sb.append(" (").append(dur(to - last.end)).append(" назад)");
        }
        boolean linkUp = d.lanes.get(LogData.LANE_LINK).isEmpty()
                || !d.lanes.get(LogData.LANE_LINK).get(d.lanes.get(LogData.LANE_LINK).size() - 1).ongoing;
        sb.append("\nСейчас: кабель ").append(linkUp ? "OK" : "НЕТ ЛИНКА");
        chartSummary.setText(sb.toString());
        chartSummary.setTextColor(linkUp ? GREEN : RED);

        // insight
        String insight = "";
        if (st.count == 0) insight = "За период отвалов не было.";
        else if (st.pppOnly == 0) insight = "Все отвалы начинались с потери линка по кабелю — это физика: "
                + "коннекторы, переходник, кабель или порт. Провайдер и PPPoE тут ни при чём.";
        else if (st.physical == 0) insight = "Кабель держался, рвалась только PPPoE-сессия — "
                + "вопрос к провайдеру или настройкам PPPoE.";
        else if (st.physical >= st.pppOnly) insight = "Большинство отвалов — потеря линка по кабелю (физика), "
                + "часть — только PPPoE.";
        else insight = "Чаще рвётся только PPPoE при живом кабеле, реже пропадает сам линк.";
        chartInsight.setText(insight);

        timeline.set(d, from, to);

        // days chart: last 7 local days
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDate today = java.time.Instant.ofEpochSecond(to).atZone(zone).toLocalDate();
        int[] dp1 = new int[7], dp2 = new int[7];
        long[] dDown = new long[7];
        String[] dl = new String[7], dc = new String[7];
        for (int i = 0; i < 7; i++) {
            java.time.LocalDate day = today.minusDays(6 - i);
            long a = day.atStartOfDay(zone).toEpochSecond();
            long b = day.plusDays(1).atStartOfDay(zone).toEpochSecond();
            dl[i] = String.format(Locale.US, "%02d.%02d", day.getDayOfMonth(), day.getMonthValue());
            for (LogData.Incident inc : d.incidents) {
                if (inc.start >= a && inc.start < b) {
                    if (inc.physical) dp1[i]++; else dp2[i]++;
                    dDown[i] += inc.dur();
                }
            }
            dc[i] = dDown[i] > 0 ? shortDur(dDown[i]) : "";
        }
        daysChart.set(dp1, dp2, dl, dc, 1);

        // hours of day
        int[] h1 = new int[24], h2 = new int[24];
        String[] hl = new String[24];
        for (int h = 0; h < 24; h++) hl[h] = String.format(Locale.US, "%02d", h);
        for (LogData.Incident inc : d.incidents) {
            if (inc.start < from || inc.start > to) continue;
            int h = java.time.Instant.ofEpochSecond(inc.start).atZone(zone).getHour();
            if (inc.physical) h1[h]++; else h2[h]++;
        }
        hoursChart.set(h1, h2, hl, null, 3);

        // incident list
        android.text.SpannableStringBuilder list = new android.text.SpannableStringBuilder();
        int shown = 0;
        for (int i = d.incidents.size() - 1; i >= 0 && shown < 60; i--) {
            LogData.Incident inc = d.incidents.get(i);
            if (inc.end < from) continue;
            int st0 = list.length();
            list.append("■ ");
            list.setSpan(new android.text.style.ForegroundColorSpan(inc.physical ? RED : AMBER), st0, st0 + 1, 0);
            list.append(fmt(inc.start, "dd.MM HH:mm:ss")).append("  ")
                    .append(pad(dur(inc.dur()), 10))
                    .append(inc.physical ? "кабель" : "PPPoE");
            if (inc.flaps > 1) list.append(" ×").append(String.valueOf(inc.flaps));
            if (inc.ongoing) list.append("  — продолжается");
            list.append("\n");
            shown++;
        }
        if (shown == 0) list.append("Нет инцидентов за выбранный период.\n");
        chartIncidents.setText(list);

        // data coverage note
        String zoneName = zone.getId();
        StringBuilder note = new StringBuilder();
        note.append("Время — в часовом поясе телефона (").append(zoneName).append(").\n");
        if (d.coverageStart[LogData.LANE_LINK] != Long.MAX_VALUE)
            note.append("Кабель WAN: данные с ").append(fmt(d.coverageStart[LogData.LANE_LINK], "dd.MM HH:mm"))
                    .append(" (журнал монитора линка + системный лог).\n");
        else
            note.append("Кабель WAN: данных нет — установите монитор линка (вкладка «Команды»).\n");
        if (d.coverageStart[LogData.LANE_PPP] != Long.MAX_VALUE)
            note.append("PPPoE и порт ПК: с последней загрузки роутера, ")
                    .append(fmt(d.coverageStart[LogData.LANE_PPP], "dd.MM HH:mm")).append(".\n");
        note.append("Моргания линка с паузой меньше ").append(LogData.MERGE_GAP)
                .append(" с считаются одним инцидентом (×N — сколько раз моргнул).");
        chartNote.setText(note.toString());
    }

    private String fmt(long epoch, String pattern) {
        return java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern(pattern));
    }

    private static String dur(long s) {
        if (s < 60) return s + " с";
        if (s < 3600) return (s / 60) + " мин " + (s % 60) + " с";
        if (s < 86400) return (s / 3600) + " ч " + (s % 3600 / 60) + " мин";
        return (s / 86400) + " д " + (s % 86400 / 3600) + " ч";
    }

    private static String shortDur(long s) {
        if (s < 60) return s + "с";
        if (s < 3600) return (s / 60) + "м";
        return (s / 3600) + "ч" + (s % 3600 / 60) + "м";
    }

    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.append(' ').toString();
    }

    // ------------------------------------------------------------------ FAQ

    private View buildFaqPane() {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        String[][] faq = {
                {"Что это за приложение?",
                        "Router Tool ходит по SSH в роутер Xiaomi AX6000 и выполняет на нём те же диагностические "
                                + "команды, что меню router.ps1 на ПК. Телефон должен быть в домашней сети (Wi-Fi роутера)."},
                {"Как подключиться?",
                        "Кнопка SSH в правом верхнем углу: адрес 192.168.31.1, пользователь root и пароль SSH роутера. "
                                + "Пароль можно не запоминать — тогда снимите галочку, он не будет храниться на телефоне."},
                {"Команды → Диагностика",
                        "• Статус — интерфейс WAN, состояние кабеля (физический линк), PPPoE, IP, наличие интернета "
                                + "и последние записи монитора линка.\n"
                                + "• Журнал линка — все моменты, когда кабель провайдера терял и возвращал линк.\n"
                                + "• Ошибки порта — счётчики FCS/CRC и др. Если растут — плохая физика (кабель, обжим).\n"
                                + "• Системный лог и Лог PPPoE — что видела прошивка. Логин и пароль PPPoE заменяются на ***.\n"
                                + "• Линк live — каждые 2 с показывает кабель и PPPoE; отвал подсвечивается красным. "
                                + "Удобно, когда трогаете кабель или переходник. Остановка — «■ Стоп»."},
                {"Команды → Настройка",
                        "• Монитор линка — ставит на роутер /data/linkmon.sh с автозапуском. Он пишет каждое изменение "
                                + "линка в /data/linkmon.log, который переживает перезагрузку. Без него графики по кабелю "
                                + "есть только с последней загрузки роутера.\n"
                                + "• Настройки PPPoE — persist=1, maxfail=0 (роутер не перестаёт переподключаться) и лог pppd "
                                + "в /tmp/ppp.log. Сохранение страницы WAN в веб-интерфейсе их стирает — тогда нажмите снова.\n"
                                + "• Часовой пояс — ставит роутеру UTC+5, чтобы время в его логах совпадало с вашим.\n"
                                + "• Удалить watchdog — убирает старый скрипт перезапуска, если он остался."},
                {"Команды → Действия",
                        "• Переподключить PPPoE — разрыв на ~10 с.\n"
                                + "• Перезагрузить — сеть пропадёт на 1–2 минуты. Обе кнопки спрашивают подтверждение."},
                {"Кнопки под выводом",
                        "Копировать и Поделиться — текст из окна вывода. «Все логи → файл» собирает статус, журнал линка, "
                                + "ошибки порта, лог PPPoE и полный системный лог в один .txt в «Загрузках» "
                                + "(пароль PPPoE замаскирован) — его удобно отправить или приложить к заявке провайдеру."},
                {"Графики: что на них",
                        "• Сводка — число отвалов, суммарный простой, самый долгий, доступность в %, когда был последний.\n"
                                + "• Хронология — три дорожки: Кабель WAN (физический линк), PPPoE (сессия интернета), "
                                + "Порт ПК (2.5G). Красное — отвал, тёмно-зелёное — работало, серое — данных нет. "
                                + "▼ — провайдер сам завершил сессию (плановый сброс).\n"
                                + "• Отвалы по дням — за последние 7 дней, подпись над столбцом — суммарный простой.\n"
                                + "• По часам суток — в какое время суток чаще отваливается (например, по вечерам при игре).\n"
                                + "• Инциденты — список с длительностью. Красный — пропал кабель, жёлтый — только PPPoE."},
                {"Как читать результат",
                        "• Отвалы начинаются с «кабель» — проблема физическая: коннекторы RJ45, переходник, розетка, "
                                + "кабель или порт роутера. Провайдер и настройки тут ни при чём.\n"
                                + "• Только PPPoE при живом кабеле — вопрос к провайдеру (сбросы сессий) или настройкам PPPoE.\n"
                                + "• Отвалы по часам совпадают с игрой и роутер стоит на ПК — проверьте нагрев: переставьте роутер.\n"
                                + "• Порт ПК моргает на 1–3 с — часто это энергосбережение сетевой карты в Windows "
                                + "(Energy-Efficient Ethernet / Green Ethernet в свойствах адаптера)."},
                {"Откуда данные и про время",
                        "Кабель — из журнала монитора линка (переживает перезагрузку) и системного лога. "
                                + "PPPoE и порт ПК — из системного лога, он хранится только до перезагрузки роутера. "
                                + "Все времена в графиках пересчитываются в часовой пояс телефона, даже если на роутере "
                                + "стоит другой. Обновить графики — кнопка ⟳."},
                {"Если не подключается",
                        "• «Неверный логин или пароль» — проверьте пароль root.\n"
                                + "• «Роутер не отвечает» — телефон не в домашнем Wi-Fi или неверный адрес.\n"
                                + "• «SSH не запущен» — после прошивки стоковой прошивки SSH обычно пропадает, "
                                + "тогда приложение работать не сможет."},
        };
        for (String[] item : faq) {
            int st = b.length();
            b.append("> ").append(item[0]).append("\n");
            b.setSpan(new android.text.style.ForegroundColorSpan(AMBER), st, b.length(), 0);
            b.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, b.length(), 0);
            int st2 = b.length();
            b.append(item[1]).append("\n\n");
            b.setSpan(new android.text.style.ForegroundColorSpan(GREEN), st2, b.length(), 0);
        }
        b.append("Router Tool ").append(versionName());
        TextView t = new TextView(this);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(12.5f);
        t.setLineSpacing(0, 1.12f);
        t.setText(b);
        t.setTextIsSelectable(true);
        t.setPadding(dp(4), dp(4), dp(4), dp(16));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        return sv;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
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
        if (chartRefresh != null) { chartRefresh.setEnabled(!b); chartRefresh.setAlpha(b ? 0.45f : 1f); }
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
