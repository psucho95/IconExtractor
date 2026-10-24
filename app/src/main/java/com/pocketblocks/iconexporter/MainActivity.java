package com.pocketblocks.iconexporter;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private static final int ICON_SIZE = 192;       // основная иконка
    private static final int LAYER_SIZE = 432;      // слои adaptive-иконки (108dp @ xxxhdpi)

    private Button exportButton;
    private Button shareButton;
    private CheckBox allAppsCheck;
    private CheckBox layersCheck;
    private ProgressBar progressBar;
    private TextView statusText;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Uri lastZipUri;

    /** Одна запись = один ярлык (пакет + activity) или приложение без ярлыка. */
    private static class AppEntry {
        String pkg;
        String activity;   // null, если у приложения нет ярлыка в лаунчере
        String label;
        int iconRes;
        ApplicationInfo appInfo;
        ResolveInfo resolveInfo;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private ViewGroup buildLayout() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Icon Exporter");
        title.setTextSize(26);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("Собирает имена пакетов, ярлыки и иконки всех приложений в ZIP (папка «Загрузки»).");
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, dp(24));
        root.addView(hint, matchWrap());

        allAppsCheck = new CheckBox(this);
        allAppsCheck.setText("Включить приложения без ярлыка на рабочем столе");
        root.addView(allAppsCheck, matchWrap());

        layersCheck = new CheckBox(this);
        layersCheck.setText("Сохранять слои adaptive-иконок (фон / передний план / монохром)");
        layersCheck.setChecked(true);
        root.addView(layersCheck, matchWrap());

        exportButton = new Button(this);
        exportButton.setText("Собрать иконки");
        exportButton.setOnClickListener(v -> startExport());
        LinearLayout.LayoutParams bp = matchWrap();
        bp.topMargin = dp(24);
        root.addView(exportButton, bp);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams pp = matchWrap();
        pp.topMargin = dp(16);
        root.addView(progressBar, pp);

        statusText = new TextView(this);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusText, matchWrap());

        shareButton = new Button(this);
        shareButton.setText("Поделиться архивом");
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(v -> shareZip());
        root.addView(shareButton, matchWrap());

        return root;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setBusy(boolean busy) {
        exportButton.setEnabled(!busy);
        allAppsCheck.setEnabled(!busy);
        layersCheck.setEnabled(!busy);
        if (busy) shareButton.setEnabled(false);
    }

    private void postStatus(String text, int percent) {
        runOnUiThread(() -> {
            statusText.setText(text);
            if (percent >= 0) progressBar.setProgress(percent);
        });
    }

    // ---------------------------------------------------------------- Экспорт

    private void startExport() {
        final boolean includeAll = allAppsCheck.isChecked();
        final boolean saveLayers = layersCheck.isChecked();
        setBusy(true);
        postStatus("Ищу приложения…", 0);

        executor.execute(() -> {
            try {
                List<AppEntry> entries = collectApps(includeAll);
                Uri uri = writeZip(entries, saveLayers);
                lastZipUri = uri;
                runOnUiThread(() -> {
                    setBusy(false);
                    shareButton.setEnabled(true);
                    progressBar.setProgress(100);
                    statusText.setText("Готово: " + entries.size()
                            + " записей.\nАрхив лежит в «Загрузках».");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("Ошибка: " + e);
                });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private List<AppEntry> collectApps(boolean includeAll) {
        PackageManager pm = getPackageManager();
        List<AppEntry> result = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        Set<String> packagesWithLauncher = new HashSet<>();

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
        return e.resolveInfo != null ? e.resolveInfo.loadIcon(pm) : e.appInfo.loadIcon(pm);
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

    private static void putFile(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static String fileBase(AppEntry e, Set<String> usedNames) {
        String base = e.pkg;
        if (!usedNames.add(base)) {
            // у пакета несколько ярлыков — добавляем короткое имя activity
            String act = e.activity == null ? "app" : e.activity.substring(e.activity.lastIndexOf('.') + 1);
            base = e.pkg + "__" + act.replaceAll("[^A-Za-z0-9_$]", "_");
            int n = 2;
            String candidate = base;
            while (!usedNames.add(candidate)) candidate = base + "_" + n++;
            base = candidate;
        }
        return base;
    }

    private Uri writeZip(List<AppEntry> entries, boolean saveLayers) throws IOException {
        PackageManager pm = getPackageManager();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "app_icons_" + stamp + ".zip";

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
        json.append("  \"apps\": [\n");

        Set<String> usedNames = new HashSet<>();
        int total = entries.size();

        try (OutputStream os = cr.openOutputStream(uri);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os))) {

            for (int i = 0; i < total; i++) {
                AppEntry e = entries.get(i);
                postStatus((i + 1) + " / " + total + "\n" + e.label, (i * 100) / Math.max(total, 1));

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
                        if (!java.util.Arrays.equals(originalPng, themedPng)) {
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
            putFile(zos, "README.txt", readme(total).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            cr.delete(uri, null, null);
            throw ex;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        return uri;
    }

    private String readme(int total) {
        return "Icon Exporter — выгрузка приложений для создания темы\n"
                + "Устройство: " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (" + Build.VERSION.INCREMENTAL + ")\n"
                + "Записей: " + total + "\n\n"
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

    // ---------------------------------------------------------------- Шаринг

    private void shareZip() {
        if (lastZipUri == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/zip");
        send.putExtra(Intent.EXTRA_STREAM, lastZipUri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Отправить архив"));
    }
}
