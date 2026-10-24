package com.pocketblocks.iconexporter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private static final int ICON_SIZE = 192;       // основная иконка
    private static final int LAYER_SIZE = 432;      // слои adaptive-иконки (108dp @ xxxhdpi)

    private static final String PREFS = "exporter";
    private static final String KEY_EXPORTED = "exported_keys";

    // UI
    private EditText searchField;
    private CheckBox onlyNewCheck;
    private CheckBox allAppsCheck;
    private CheckBox layersCheck;
    private Button selectNewButton;
    private Button selectAllButton;
    private Button selectNoneButton;
    private ListView listView;
    private TextView countText;
    private Button resetHistoryButton;
    private Button exportButton;
    private Button shareButton;
    private ProgressBar progressBar;
    private TextView statusText;

    // Данные
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<AppEntry> allEntries = new ArrayList<>();
    private final List<AppEntry> visibleEntries = new ArrayList<>();
    private final Set<String> selectedKeys = new HashSet<>();
    private Set<String> exportedKeys = new HashSet<>();
    private AppAdapter adapter;
    private boolean busy;
    private boolean loadedOnce;
    private Uri lastZipUri;

    /** Одна запись = один ярлык (пакет + activity) или приложение без ярлыка. */
    private static class AppEntry {
        String pkg;
        String activity;   // null, если у приложения нет ярлыка в лаунчере
        String label;
        int iconRes;
        ApplicationInfo appInfo;
        ResolveInfo resolveInfo;
        Bitmap thumb;
        boolean multi;     // у пакета несколько ярлыков
        boolean isNew;     // ещё ни разу не выгружалось

        String key() {
            return pkg + "/" + (activity == null ? "" : activity);
        }

        String shortActivity() {
            return activity == null ? "" : activity.substring(activity.lastIndexOf('.') + 1);
        }
    }

    // ================================================================ Жизненный цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        setContentView(buildLayout());
        exportedKeys = loadExportedKeys();
        reloadApps();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // вернулись в приложение — вдруг что-то установили или удалили
        if (!busy) reloadApps();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    // ================================================================ UI

    private ViewGroup buildLayout() {
        final int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
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

        TextView title = new TextView(this);
        title.setText("Icon Exporter");
        title.setTextSize(22);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        searchField = new EditText(this);
        searchField.setHint("Поиск по названию или пакету");
        searchField.setSingleLine(true);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        root.addView(searchField, matchWrap());

        onlyNewCheck = new CheckBox(this);
        onlyNewCheck.setText("Показывать только новые");
        onlyNewCheck.setOnCheckedChangeListener((b, checked) -> applyFilter());
        root.addView(onlyNewCheck, matchWrap());

        allAppsCheck = new CheckBox(this);
        allAppsCheck.setText("Включить приложения без ярлыка");
        allAppsCheck.setOnCheckedChangeListener((b, checked) -> reloadApps());
        root.addView(allAppsCheck, matchWrap());

        layersCheck = new CheckBox(this);
        layersCheck.setText("Сохранять слои adaptive-иконок");
        layersCheck.setChecked(true);
        root.addView(layersCheck, matchWrap());

        LinearLayout selRow = new LinearLayout(this);
        selRow.setOrientation(LinearLayout.HORIZONTAL);
        selectNewButton = smallButton("Новые", v -> selectNew());
        selectAllButton = smallButton("Все", v -> selectVisible(true));
        selectNoneButton = smallButton("Снять", v -> selectVisible(false));
        selRow.addView(selectNewButton, weighted());
        selRow.addView(selectAllButton, weighted());
        selRow.addView(selectNoneButton, weighted());
        root.addView(selRow, matchWrap());

        adapter = new AppAdapter();
        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (busy) return;
            AppEntry e = visibleEntries.get(position);
            if (!selectedKeys.remove(e.key())) selectedKeys.add(e.key());
            adapter.notifyDataSetChanged();
            updateCount();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(listView, lp);

        LinearLayout countRow = new LinearLayout(this);
        countRow.setOrientation(LinearLayout.HORIZONTAL);
        countRow.setGravity(Gravity.CENTER_VERTICAL);
        countText = new TextView(this);
        countRow.addView(countText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        resetHistoryButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        resetHistoryButton.setText("Сбросить историю");
        resetHistoryButton.setAllCaps(false);
        resetHistoryButton.setTextSize(13);
        resetHistoryButton.setOnClickListener(v -> confirmResetHistory());
        countRow.addView(resetHistoryButton);
        root.addView(countRow, matchWrap());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        root.addView(progressBar, matchWrap());

        statusText = new TextView(this);
        statusText.setGravity(Gravity.CENTER);
        statusText.setTextSize(13);
        root.addView(statusText, matchWrap());

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        exportButton = new Button(this);
        exportButton.setText("Выгрузить выбранные");
        exportButton.setOnClickListener(v -> startExport());
        actionRow.addView(exportButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        shareButton = new Button(this);
        shareButton.setText("Поделиться");
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(v -> shareZip());
        actionRow.addView(shareButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(actionRow, matchWrap());

        return root;
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setBusy(boolean b) {
        busy = b;
        exportButton.setEnabled(!b);
        allAppsCheck.setEnabled(!b);
        layersCheck.setEnabled(!b);
        selectNewButton.setEnabled(!b);
        selectAllButton.setEnabled(!b);
        selectNoneButton.setEnabled(!b);
        resetHistoryButton.setEnabled(!b);
        if (b) shareButton.setEnabled(false);
        else shareButton.setEnabled(lastZipUri != null);
    }

    private void postStatus(String text, int percent) {
        runOnUiThread(() -> {
            statusText.setText(text);
            if (percent >= 0) progressBar.setProgress(percent);
        });
    }

    private void updateCount() {
        int newCount = 0;
        for (AppEntry e : allEntries) if (e.isNew) newCount++;
        String s = "Выбрано: " + selectedKeys.size() + " из " + allEntries.size();
        if (!exportedKeys.isEmpty()) s += " · новых: " + newCount;
        countText.setText(s);
        exportButton.setText(selectedKeys.isEmpty() ? "Выгрузить выбранные" : "Выгрузить (" + selectedKeys.size() + ")");
    }

    // ================================================================ Список и выбор

    private void applyFilter() {
        String q = searchField.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean onlyNew = onlyNewCheck.isChecked();
        visibleEntries.clear();
        for (AppEntry e : allEntries) {
            if (onlyNew && !e.isNew) continue;
            if (!q.isEmpty()
                    && !e.label.toLowerCase(Locale.ROOT).contains(q)
                    && !e.pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
            visibleEntries.add(e);
        }
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void selectVisible(boolean select) {
        for (AppEntry e : visibleEntries) {
            if (select) selectedKeys.add(e.key());
            else selectedKeys.remove(e.key());
        }
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void selectNew() {
        if (exportedKeys.isEmpty()) {
            Toast.makeText(this, "Выгрузок ещё не было — все приложения считаются новыми", Toast.LENGTH_SHORT).show();
            for (AppEntry e : allEntries) selectedKeys.add(e.key());
        } else {
            selectedKeys.clear();
            for (AppEntry e : allEntries) if (e.isNew) selectedKeys.add(e.key());
        }
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void refreshNewFlags() {
        boolean hasHistory = !exportedKeys.isEmpty();
        for (AppEntry e : allEntries) e.isNew = hasHistory && !exportedKeys.contains(e.key());
    }

    private void confirmResetHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Сбросить историю?")
                .setMessage("Приложение забудет, какие иконки уже выгружались, и отметки «НОВОЕ» пропадут до следующей выгрузки.")
                .setPositiveButton("Сбросить", (d, w) -> {
                    exportedKeys = new HashSet<>();
                    saveExportedKeys();
                    refreshNewFlags();
                    applyFilter();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private Set<String> loadExportedKeys() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        return new HashSet<>(sp.getStringSet(KEY_EXPORTED, new HashSet<>()));
    }

    private void saveExportedKeys() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putStringSet(KEY_EXPORTED, new HashSet<>(exportedKeys))
                .apply();
    }

    private void reloadApps() {
        final boolean includeAll = allAppsCheck.isChecked();
        final int thumbPx = dp(40);
        setBusy(true);
        postStatus("Загружаю список приложений…", 0);

        executor.execute(() -> {
            try {
                PackageManager pm = getPackageManager();
                List<AppEntry> list = collectApps(includeAll);
                int total = list.size();
                for (int i = 0; i < total; i++) {
                    AppEntry e = list.get(i);
                    try {
                        e.thumb = toBitmap(loadOriginalIcon(pm, e), thumbPx);
                    } catch (Throwable ignored) { }
                    if (i % 10 == 0) postStatus("Загружаю список… " + i + " / " + total, (i * 100) / Math.max(total, 1));
                }
                runOnUiThread(() -> onAppsLoaded(list));
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("Ошибка: " + ex);
                });
            }
        });
    }

    private void onAppsLoaded(List<AppEntry> list) {
        Set<String> previousKeys = new HashSet<>();
        for (AppEntry e : allEntries) previousKeys.add(e.key());

        allEntries.clear();
        allEntries.addAll(list);
        refreshNewFlags();

        Set<String> currentKeys = new HashSet<>();
        for (AppEntry e : allEntries) currentKeys.add(e.key());
        selectedKeys.retainAll(currentKeys);

        boolean hasHistory = !exportedKeys.isEmpty();
        for (AppEntry e : allEntries) {
            boolean appearedNow = !loadedOnce || !previousKeys.contains(e.key());
            if (appearedNow && (!hasHistory || e.isNew)) selectedKeys.add(e.key());
        }
        loadedOnce = true;

        setBusy(false);
        progressBar.setProgress(0);
        statusText.setText("");
        applyFilter();
    }

    @SuppressWarnings("deprecation")
    private List<AppEntry> collectApps(boolean includeAll) {
        PackageManager pm = getPackageManager();
        List<AppEntry> result = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        Set<String> packagesWithLauncher = new HashSet<>();
        Map<String, Integer> perPackage = new HashMap<>();

        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
        for (ResolveInfo ri : resolved) {
            String pkg = ri.activityInfo.packageName;
            String act = ri.activityInfo.name;
            if (!seenKeys.add(pkg + "/" + act)) continue;
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.activity = act;
            e.label = safeLabel(ri.loadLabel(pm));
            e.iconRes = ri.activityInfo.getIconResource();
            e.appInfo = ri.activityInfo.applicationInfo;
            e.resolveInfo = ri;
            result.add(e);
            packagesWithLauncher.add(pkg);
            Integer c = perPackage.get(pkg);
            perPackage.put(pkg, c == null ? 1 : c + 1);
        }
        for (AppEntry e : result) {
            Integer c = perPackage.get(e.pkg);
            e.multi = c != null && c > 1;
        }

        if (includeAll) {
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                if (packagesWithLauncher.contains(ai.packageName)) continue;
                AppEntry e = new AppEntry();
                e.pkg = ai.packageName;
                e.activity = null;
                e.label = safeLabel(ai.loadLabel(pm));
                e.iconRes = ai.icon;
                e.appInfo = ai;
                result.add(e);
            }
        }

        result.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    private static String safeLabel(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    // ================================================================ Адаптер списка

    private static class Holder {
        CheckBox check;
        ImageView icon;
        TextView label;
        TextView pkg;
        TextView badge;
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleEntries.size(); }
        @Override public Object getItem(int position) { return visibleEntries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(6), dp(4), dp(6));

                h = new Holder();
                h.check = new CheckBox(MainActivity.this);
                h.check.setFocusable(false);
                h.check.setClickable(false);
                row.addView(h.check);

                h.icon = new ImageView(MainActivity.this);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
                ip.leftMargin = dp(4);
                ip.rightMargin = dp(12);
                row.addView(h.icon, ip);

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                h.label = new TextView(MainActivity.this);
                h.label.setTextSize(16);
                h.label.setSingleLine(true);
                h.label.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(h.label);
                h.pkg = new TextView(MainActivity.this);
                h.pkg.setTextSize(12);
                h.pkg.setAlpha(0.65f);
                h.pkg.setSingleLine(true);
                h.pkg.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                texts.addView(h.pkg);
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                h.badge = new TextView(MainActivity.this);
                h.badge.setText("НОВОЕ");
                h.badge.setTextSize(10);
                h.badge.setTypeface(Typeface.DEFAULT_BOLD);
                h.badge.setTextColor(0xFFFFFFFF);
                h.badge.setPadding(dp(6), dp(2), dp(6), dp(2));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF2E7D32);
                bg.setCornerRadius(dp(4));
                h.badge.setBackground(bg);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bp.leftMargin = dp(8);
                row.addView(h.badge, bp);

                row.setTag(h);
                convertView = row;
            } else {
                h = (Holder) convertView.getTag();
            }

            AppEntry e = visibleEntries.get(position);
            h.check.setChecked(selectedKeys.contains(e.key()));
            h.icon.setImageBitmap(e.thumb);
            h.label.setText(e.label.isEmpty() ? e.pkg : e.label);
            String sub = e.pkg;
            if (e.activity == null) sub += " · без ярлыка";
            else if (e.multi) sub += " · " + e.shortActivity();
            h.pkg.setText(sub);
            h.badge.setVisibility(e.isNew ? View.VISIBLE : View.GONE);
            return convertView;
        }
    }

    // ================================================================ Иконки

    /**
     * Сначала пытается взять «чистую» иконку прямо из ресурсов приложения
     * (без наложения текущей темы HyperOS), иначе — то, что отдаёт система.
     */
    private Drawable loadOriginalIcon(PackageManager pm, AppEntry e) {
        if (e.iconRes != 0) {
            try {
                Resources res = pm.getResourcesForApplication(e.appInfo);
                Drawable d = res.getDrawableForDensity(e.iconRes, DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) return d;
            } catch (Exception ignored) { }
        }
        return loadSystemIcon(pm, e);
    }

    private Drawable loadSystemIcon(PackageManager pm, AppEntry e) {
        return e.resolveInfo != null ? e.resolveInfo.loadIcon(pm) : e.appInfo.loadIcon(pm);
    }

    private static Bitmap toBitmap(Drawable d, int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return bmp;
    }

    private static byte[] png(Bitmap bmp) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        bmp.recycle();
        return bos.toByteArray();
    }

    // ================================================================ Экспорт

    private void startExport() {
        final List<AppEntry> toExport = new ArrayList<>();
        for (AppEntry e : allEntries) if (selectedKeys.contains(e.key())) toExport.add(e);
        if (toExport.isEmpty()) {
            Toast.makeText(this, "Ничего не выбрано", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean full = toExport.size() == allEntries.size();
        final boolean saveLayers = layersCheck.isChecked();
        setBusy(true);
        postStatus("Готовлю архив…", 0);

        executor.execute(() -> {
            try {
                Uri uri = writeZip(toExport, saveLayers, full);
                runOnUiThread(() -> {
                    lastZipUri = uri;
                    for (AppEntry e : toExport) exportedKeys.add(e.key());
                    saveExportedKeys();
                    refreshNewFlags();
                    setBusy(false);
                    progressBar.setProgress(100);
                    statusText.setText("Готово: " + toExport.size() + " шт. Архив лежит в «Загрузках».");
                    applyFilter();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("Ошибка: " + ex);
                });
            }
        });
    }

    private static void putFile(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static String fileBase(AppEntry e, Set<String> usedNames) {
        String base = e.pkg;
        if (!usedNames.add(base)) {
            // у пакета несколько ярлыков — добавляем короткое имя activity
            String act = e.activity == null ? "app" : e.shortActivity();
            base = e.pkg + "__" + act.replaceAll("[^A-Za-z0-9_$]", "_");
            int n = 2;
            String candidate = base;
            while (!usedNames.add(candidate)) candidate = base + "_" + n++;
            base = candidate;
        }
        return base;
    }

    private Uri writeZip(List<AppEntry> entries, boolean saveLayers, boolean full) throws IOException {
        PackageManager pm = getPackageManager();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "app_icons_" + stamp + (full ? "" : "_part") + ".zip";

        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        cv.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        cv.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("Не удалось создать файл в «Загрузках»");

        StringBuilder json = new StringBuilder();
        StringBuilder csv = new StringBuilder("﻿package;activity;label;system;icon\n");
        json.append("{\n");
        json.append("  \"device\": {")
                .append("\"manufacturer\": ").append(q(Build.MANUFACTURER))
                .append(", \"model\": ").append(q(Build.MODEL))
                .append(", \"android\": ").append(q(Build.VERSION.RELEASE))
                .append(", \"sdk\": ").append(Build.VERSION.SDK_INT)
                .append(", \"build\": ").append(q(Build.VERSION.INCREMENTAL))
                .append("},\n");
        json.append("  \"generated\": ").append(q(stamp)).append(",\n");
        json.append("  \"selection\": ").append(q(full ? "all" : "partial")).append(",\n");
        json.append("  \"count\": ").append(entries.size()).append(",\n");
        json.append("  \"apps\": [\n");

        Set<String> usedNames = new HashSet<>();
        int total = entries.size();

        try (OutputStream os = cr.openOutputStream(uri);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os))) {

            for (int i = 0; i < total; i++) {
                AppEntry e = entries.get(i);
                postStatus((i + 1) + " / " + total + " · " + e.label, (i * 100) / Math.max(total, 1));

                String base = fileBase(e, usedNames);
                String iconPath = "icons/" + base + ".png";
                boolean adaptive = false;
                boolean hasMono = false;
                boolean themedSaved = false;
                String error = null;

                try {
                    Drawable original = loadOriginalIcon(pm, e);
                    byte[] originalPng = png(toBitmap(original, ICON_SIZE));
                    putFile(zos, iconPath, originalPng);

                    // Иконка так, как её сейчас показывает система (с учётом текущей темы)
                    Drawable themed = loadSystemIcon(pm, e);
                    if (themed != null) {
                        byte[] themedPng = png(toBitmap(themed, ICON_SIZE));
                        if (!Arrays.equals(originalPng, themedPng)) {
                            putFile(zos, "icons_current_theme/" + base + ".png", themedPng);
                            themedSaved = true;
                        }
                    }

                    if (original instanceof AdaptiveIconDrawable) {
                        adaptive = true;
                        if (saveLayers) {
                            AdaptiveIconDrawable aid = (AdaptiveIconDrawable) original;
                            if (aid.getBackground() != null)
                                putFile(zos, "adaptive/" + base + "_background.png", png(toBitmap(aid.getBackground(), LAYER_SIZE)));
                            if (aid.getForeground() != null)
                                putFile(zos, "adaptive/" + base + "_foreground.png", png(toBitmap(aid.getForeground(), LAYER_SIZE)));
                            if (Build.VERSION.SDK_INT >= 33 && aid.getMonochrome() != null) {
                                putFile(zos, "adaptive/" + base + "_monochrome.png", png(toBitmap(aid.getMonochrome(), LAYER_SIZE)));
                                hasMono = true;
                            }
                        }
                    }
                } catch (Throwable t) {
                    error = t.getClass().getSimpleName() + ": " + t.getMessage();
                    iconPath = null;
                }

                String versionName = "";
                long versionCode = 0;
                try {
                    PackageInfo pi = pm.getPackageInfo(e.pkg, 0);
                    versionName = pi.versionName == null ? "" : pi.versionName;
                    versionCode = pi.getLongVersionCode();
                } catch (Exception ignored) { }

                boolean system = (e.appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

                json.append("    {")
                        .append("\"package\": ").append(q(e.pkg))
                        .append(", \"activity\": ").append(q(e.activity))
                        .append(", \"label\": ").append(q(e.label))
                        .append(", \"version\": ").append(q(versionName))
                        .append(", \"versionCode\": ").append(versionCode)
                        .append(", \"system\": ").append(system)
                        .append(", \"adaptive\": ").append(adaptive)
                        .append(", \"monochrome\": ").append(hasMono)
                        .append(", \"icon\": ").append(q(iconPath))
                        .append(", \"themedIcon\": ").append(themedSaved ? q("icons_current_theme/" + base + ".png") : "null");
                if (error != null) json.append(", \"error\": ").append(q(error));
                json.append("}").append(i < total - 1 ? ",\n" : "\n");

                csv.append(e.pkg).append(';')
                        .append(e.activity == null ? "" : e.activity).append(';')
                        .append(e.label.replace(";", ",").replace("\n", " ")).append(';')
                        .append(system ? "1" : "0").append(';')
                        .append(iconPath == null ? "" : iconPath).append('\n');
            }

            json.append("  ]\n}\n");
            putFile(zos, "apps.json", json.toString().getBytes(StandardCharsets.UTF_8));
            putFile(zos, "apps.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
            putFile(zos, "README.txt", readme(total, full).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            cr.delete(uri, null, null);
            throw ex;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        return uri;
    }

    private String readme(int total, boolean full) {
        return "Icon Exporter — выгрузка приложений для создания темы\n"
                + "Устройство: " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (" + Build.VERSION.INCREMENTAL + ")\n"
                + "Записей: " + total + (full ? " (все приложения)" : " (только выбранные — это дополнение к прошлой выгрузке)") + "\n\n"
                + "apps.json             — пакет, activity ярлыка, название, версия, флаги\n"
                + "apps.csv              — то же самое таблицей\n"
                + "icons/                — оригинальная иконка приложения, " + ICON_SIZE + "x" + ICON_SIZE + "\n"
                + "icons_current_theme/  — как иконка выглядит в текущей теме (если отличается)\n"
                + "adaptive/             — слои adaptive-иконок, " + LAYER_SIZE + "x" + LAYER_SIZE
                + " (видимая зона — центральные 2/3)\n";
    }

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ================================================================ Шаринг

    private void shareZip() {
        if (lastZipUri == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/zip");
        send.putExtra(Intent.EXTRA_STREAM, lastZipUri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Отправить архив"));
    }
}
