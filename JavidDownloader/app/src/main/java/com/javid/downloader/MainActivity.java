package com.javid.downloader;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

public class MainActivity extends Activity {
    private final int NAVY = Color.rgb(16, 44, 53);
    private final int TEAL = Color.rgb(0, 149, 130);
    private final int PALE = Color.rgb(244, 247, 249);
    private final int TEXT = Color.rgb(24, 48, 57);
    private final int MUTED = Color.rgb(109, 127, 138);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private EditText linkBox;
    private TextView state, detail;
    private ProgressBar progress;
    private Button startButton, checkButton, cancelButton;
    private Spinner quality;
    private LinearLayout downloadPanel, historyPanel, historyItems;
    private volatile boolean engineReady = false;
    private volatile boolean busy = false;
    private volatile boolean isDownloading = false;
    private final String processId = "javid-active-download";

    @Override public void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(PALE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildUi();
        acceptSharedLink(getIntent());
        executor.execute(() -> {
            try {
                YoutubeDL.getInstance().init(getApplicationContext());
                FFmpeg.getInstance().init(getApplicationContext());
                engineReady = true;
                runOnUiThread(() -> setStatus("آماده دریافت لینک", false));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("راه‌اندازی موتور دانلود ناموفق بود: " + message(e), true));
            }
        });
    }

    private void buildUi() {
        LinearLayout app = new LinearLayout(this);
        app.setOrientation(LinearLayout.VERTICAL);
        app.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        app.setBackgroundColor(PALE);
        setContentView(app);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        app.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout content = new LinearLayout(this);
        content.setPadding(dp(17), dp(18), dp(17), dp(20));
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(content);

        LinearLayout hero = vertical();
        hero.setPadding(dp(19), dp(24), dp(19), dp(23));
        hero.setBackground(round(NAVY, 23));
        TextView icon = label("↓   JAVID", 23, Color.rgb(255, 198, 103), true);
        icon.setGravity(Gravity.START);
        hero.addView(icon);
        hero.addView(gap(16));
        hero.addView(label("جاوید دانلودر", 28, Color.WHITE, true));
        hero.addView(gap(8));
        hero.addView(label("دانلود ویدیو و موسیقی از لینک‌های مجاز", 14, Color.rgb(186, 213, 215), false));
        content.addView(hero, margin(0, 0, 0, 19));

        downloadPanel = vertical();
        LinearLayout card = vertical();
        card.setPadding(dp(17), dp(19), dp(17), dp(17));
        card.setBackground(round(Color.WHITE, 18));
        card.addView(label("لینک ویدیو را وارد کن", 19, TEXT, true));
        card.addView(gap(11));
        linkBox = new EditText(this);
        linkBox.setSingleLine(false);
        linkBox.setMinLines(2);
        linkBox.setMaxLines(3);
        linkBox.setTextSize(14);
        linkBox.setTextColor(TEXT);
        linkBox.setHintTextColor(MUTED);
        linkBox.setHint("لینک YouTube یا Instagram...");
        linkBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkBox.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        linkBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        linkBox.setBackground(round(PALE, 11));
        card.addView(linkBox, new LinearLayout.LayoutParams(-1, dp(75)));
        card.addView(gap(11));

        Button paste = button("📋  چسباندن لینک", PALE, TEXT);
        card.addView(paste);
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                String str = String.valueOf(clip.getItemAt(0).coerceToText(this));
                linkBox.setText(str);
            } else { toast("متنی در کلیپ‌بورد نیست"); }
        });
        card.addView(gap(16));
        card.addView(label("کیفیت دانلود", 16, TEXT, true));
        quality = new Spinner(this);
        String[] options = {"بهترین کیفیت", "1080p", "720p", "480p", "360p", "فقط صدا (MP3)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options);
        quality.setAdapter(adapter);
        card.addView(quality, new LinearLayout.LayoutParams(-1, dp(53)));
        card.addView(gap(8));

        checkButton = button("بررسی عنوان لینک", PALE, NAVY);
        card.addView(checkButton);
        checkButton.setOnClickListener(v -> inspect());
        card.addView(gap(9));
        startButton = button("⬇  شروع دانلود", TEAL, Color.WHITE);
        card.addView(startButton);
        startButton.setOnClickListener(v -> download());

        cancelButton = button("توقف دانلود", PALE, Color.rgb(170, 70, 64));
        cancelButton.setVisibility(View.GONE);
        card.addView(cancelButton);
        cancelButton.setOnClickListener(v -> {
            if (isDownloading) {
                YoutubeDL.getInstance().destroyProcessById(processId);
                setStatus("درخواست توقف ارسال شد", true);
            }
        });

        card.addView(gap(16));
        state = label("در حال آماده‌سازی موتور دانلود...", 14, MUTED, false);
        card.addView(state);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setProgressTintList(ColorStateList.valueOf(TEAL));
        card.addView(progress, margin(0, 10, 0, 0));
        detail = label("ابتدا لینک را وارد کن. برای شروع برنامه را باز نگه دار.", 12, MUTED, false);
        card.addView(detail, margin(0, 8, 0, 0));
        downloadPanel.addView(card);
        TextView note = label("فقط ویدیوها و فایل‌هایی را دریافت کن که حق دانلودشان را داری. بعضی لینک‌های خصوصی یا محدود قابل دریافت نیستند.", 12, MUTED, false);
        downloadPanel.addView(note, margin(10, 16, 10, 0));
        content.addView(downloadPanel);

        historyPanel = vertical();
        historyPanel.setVisibility(View.GONE);
        LinearLayout histCard = vertical();
        histCard.setPadding(dp(15), dp(17), dp(15), dp(17));
        histCard.setBackground(round(Color.WHITE, 18));
        histCard.addView(label("فایل‌های دانلودشده", 19, TEXT, true));
        histCard.addView(gap(10));
        historyItems = vertical();
        histCard.addView(historyItems);
        historyPanel.addView(histCard);
        content.addView(historyPanel);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(dp(15), dp(8), dp(15), dp(12));
        bottom.setBackgroundColor(Color.WHITE);
        bottom.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        app.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        Button downTab = button("↓  دانلود", NAVY, Color.WHITE);
        Button histTab = button("◷  تاریخچه", PALE, NAVY);
        bottom.addView(downTab, new LinearLayout.LayoutParams(0, dp(49), 1f));
        bottom.addView(gapHorizontal(10));
        bottom.addView(histTab, new LinearLayout.LayoutParams(0, dp(49), 1f));
        downTab.setOnClickListener(v -> {
            downloadPanel.setVisibility(View.VISIBLE);
            historyPanel.setVisibility(View.GONE);
            downTab.setBackground(round(NAVY, 12));
            downTab.setTextColor(Color.WHITE);
            histTab.setBackground(round(PALE, 12));
            histTab.setTextColor(NAVY);
            scroll.smoothScrollTo(0, 0);
        });
        histTab.setOnClickListener(v -> {
            refreshHistory();
            downloadPanel.setVisibility(View.GONE);
            historyPanel.setVisibility(View.VISIBLE);
            histTab.setBackground(round(NAVY, 12));
            histTab.setTextColor(Color.WHITE);
            downTab.setBackground(round(PALE, 12));
            downTab.setTextColor(NAVY);
            scroll.smoothScrollTo(0, 0);
        });
    }

    private void inspect() {
        String link = validatedLink();
        if (link == null || !begin()) return;
        setStatus("در حال بررسی لینک...", false);
        executor.execute(() -> {
            try {
                VideoInfo meta = YoutubeDL.getInstance().getInfo(link);
                runOnUiThread(() -> {
                    setStatus("لینک معتبر است", false);
                    detail.setText(meta.getTitle() == null ? "اطلاعات عنوان پیدا نشد" : meta.getTitle());
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("بررسی لینک: " + message(e), true));
            } finally {
                runOnUiThread(() -> end());
            }
        });
    }

    private void download() {
        String link = validatedLink();
        if (link == null || !begin()) return;
        final int chosen = quality.getSelectedItemPosition();
        setStatus("در حال آماده‌سازی دانلود...", false);
        progress.setProgress(0);
        cancelButton.setVisibility(View.VISIBLE);
        isDownloading = true;
        executor.execute(() -> {
            File job = new File(getExternalFilesDir(null), "work_" + UUID.randomUUID());
            try {
                if (!job.mkdirs()) throw new Exception("پوشه موقت ساخته نشد");
                YoutubeDLRequest req = new YoutubeDLRequest(link);
                req.addOption("--no-playlist");
                req.addOption("--no-mtime");
                req.addOption("-o", new File(job, "%(title).130B [%(id)s].%(ext)s").getAbsolutePath());
                if (chosen == 5) {
                    req.addOption("-x");
                    req.addOption("--audio-format", "mp3");
                    req.addOption("--audio-quality", "0");
                } else {
                    String fmt;
                    if (chosen == 0) fmt = "bv*+ba/b";
                    else {
                        int height = chosen == 1 ? 1080 : chosen == 2 ? 720 : chosen == 3 ? 480 : 360;
                        fmt = "bv*[height<=" + height + "]+ba/b[height<=" + height + "]";
                    }
                    req.addOption("-f", fmt);
                    req.addOption("--merge-output-format", "mp4");
                }
                final long[] last = {0};
                YoutubeDL.getInstance().execute(req, processId, new Function3<Float, Long, String, Unit>() {
                    @Override public Unit invoke(Float p, Long eta, String output) {
                        long now = System.currentTimeMillis();
                        if (now - last[0] > 280 || p >= 100f) {
                            last[0] = now;
                            runOnUiThread(() -> {
                                progress.setProgress(Math.max(0, Math.min(100, p.intValue())));
                                state.setText("در حال دانلود: " + Math.round(p) + "%");
                                detail.setText("زمان تقریبی باقیمانده: " + (eta < 0 ? "نامشخص" : eta + " ثانیه"));
                            });
                        }
                        return Unit.INSTANCE;
                    }
                });
                File[] saved = job.listFiles((dir, name) -> !name.endsWith(".part") &&
                    !name.endsWith(".ytdl") && !name.endsWith(".json") &&
                    !name.endsWith(".webp") && !name.endsWith(".description"));
                if (saved == null || saved.length == 0) throw new Exception("فایل خروجی یافت نشد");
                Arrays.sort(saved, Comparator.comparingLong(File::lastModified).reversed());
                File target = null;
                for (File f : saved) { if (f.isFile()) { target = f; break; } }
                if (target == null) throw new Exception("فایل نهایی یافت نشد");
                Uri uri = publishToDownloads(target);
                String name = target.getName();
                recordHistory(name, uri);
                runOnUiThread(() -> {
                    progress.setProgress(100);
                    setStatus("دانلود تمام شد ✓", false);
                    detail.setText("ذخیره شد در Downloads/JavidDownloader/" + name);
                    toast("دانلود با موفقیت ذخیره شد");
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("خطا در دانلود: " + message(e), true));
            } finally {
                deleteRecursive(job);
                runOnUiThread(() -> { isDownloading = false; cancelButton.setVisibility(View.GONE); end(); });
            }
        });
    }

    private Uri publishToDownloads(File file) throws Exception {
        String name = file.getName();
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String mime = extension.equals("mp3") ? "audio/mpeg" :
            extension.equals("m4a") ? "audio/mp4" :
            extension.equals("webm") ? "video/webm" :
            extension.equals("mkv") ? "video/x-matroska" : "video/mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JavidDownloader");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri out = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (out == null) throw new Exception("فضای ذخیره‌سازی در دسترس نیست");
        boolean success = false;
        try (InputStream in = new FileInputStream(file); OutputStream stream = getContentResolver().openOutputStream(out)) {
            if (stream == null) throw new Exception("ذخیره فایل ناموفق بود");
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) stream.write(buf, 0, n);
            success = true;
        } finally {
            if (!success) getContentResolver().delete(out, null, null);
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(out, values, null, null);
        return out;
    }

    private void recordHistory(String name, Uri uri) {
        try {
            String old = getPreferences(MODE_PRIVATE).getString("history", "[]");
            JSONArray prior = new JSONArray(old);
            JSONArray next = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("uri", uri.toString());
            item.put("date", new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(new Date()));
            next.put(item);
            for (int i = 0; i < prior.length() && i < 99; i++) next.put(prior.getJSONObject(i));
            getPreferences(MODE_PRIVATE).edit().putString("history", next.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void refreshHistory() {
        historyItems.removeAllViews();
        try {
            JSONArray items = new JSONArray(getPreferences(MODE_PRIVATE).getString("history", "[]"));
            if (items.length() == 0) {
                historyItems.addView(label("هنوز فایلی دانلود نشده است.", 14, MUTED, false));
                return;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                final Uri uri = Uri.parse(item.getString("uri"));
                LinearLayout row = vertical();
                row.setPadding(dp(10), dp(13), dp(10), dp(13));
                row.setBackground(round(PALE, 12));
                row.addView(label(item.optString("name", "فایل"), 14, TEXT, true));
                row.addView(gap(4));
                row.addView(label(item.optString("date", ""), 12, MUTED, false));
                Button open = button("باز کردن فایل", TEAL, Color.WHITE);
                row.addView(open, margin(0, 8, 0, 0));
                open.setOnClickListener(v -> {
                    try {
                        String type = getContentResolver().getType(uri);
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(uri, type == null ? "*/*" : type);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(intent);
                    } catch (Exception e) { toast("برای این فایل برنامه مناسب نصب نیست"); }
                });
                historyItems.addView(row, margin(0, 0, 0, 9));
            }
        } catch (Exception e) {
            historyItems.addView(label("خواندن تاریخچه ممکن نبود.", 14, MUTED, false));
        }
    }

    private String validatedLink() {
        String pasted = linkBox.getText().toString().trim();
        Matcher matcher = Pattern.compile("https?://[^\\s<>\\\"]+", Pattern.CASE_INSENSITIVE).matcher(pasted);
        String url = matcher.find() ? matcher.group() : pasted;
        url = url.replaceAll("[),;]+$", "");
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null || !("https".equalsIgnoreCase(uri.getScheme())) ||
            !isSupportedHost(host)) {
            toast("لینک معتبر YouTube یا Instagram با https وارد کن");
            return null;
        }
        return url;
    }

    private boolean isSupportedHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("youtu.be") || h.equals("youtube.com") || h.endsWith(".youtube.com")
            || h.equals("instagram.com") || h.endsWith(".instagram.com");
    }

    private boolean begin() {
        if (!engineReady) { toast("موتور دانلود هنوز آماده نیست"); return false; }
        if (busy) { toast("یک عملیات دیگر در حال اجراست"); return false; }
        busy = true;
        startButton.setEnabled(false);
        checkButton.setEnabled(false);
        return true;
    }

    private void end() {
        busy = false;
        startButton.setEnabled(true);
        checkButton.setEnabled(true);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptSharedLink(intent);
    }
    private void acceptSharedLink(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null && linkBox != null) linkBox.setText(shared);
        }
    }
    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) for (File f : files) deleteRecursive(f);
        }
        file.delete();
    }
    private String message(Exception e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) return e.getClass().getSimpleName();
        return m.length() > 150 ? m.substring(0, 150) : m;
    }
    private void setStatus(String s, boolean error) {
        if (state != null) {
            state.setText(s);
            state.setTextColor(error ? Color.rgb(182, 58, 54) : TEAL);
        }
        if (error && detail != null) detail.setText("ممکن است لینک محدود یا سرویس موقتاً در دسترس نباشد.");
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private View gap(int h) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h))); return v; }
    private View gapHorizontal(int w) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), 1)); return v; }
    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b)); return lp;
    }
    private LinearLayout vertical() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return ll;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
    private TextView label(String text, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setGravity(Gravity.START);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private Button button(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackground(round(bg, 12));
        b.setMinHeight(dp(48));
        return b;
    }
}
