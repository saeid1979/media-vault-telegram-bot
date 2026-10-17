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
import android.text.Editable;
import android.text.TextWatcher;
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
import com.yausername.ffmpeg.FFmpeg;
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
    private TextView state, detail, sourceInfo;
    private ProgressBar progress;
    private Button startButton, checkButton, cancelButton, updateButton;
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
                runOnUiThread(() -> {
                    setStatus("موتور دانلود آماده است", false);
                    long lastCheck = getPreferences(MODE_PRIVATE).getLong("last_engine_check", 0L);
                    if (System.currentTimeMillis() - lastCheck > 24L * 60 * 60 * 1000) {
                        updateEngine(true);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("راه‌اندازی موتور دانلود ناموفق بود: " + message(e), true));
            }
        });
    }

    private void buildUi() {
        // Persian-first visual language: expressive navy hero, muted backgrounds,
        // generous spacing, a single strong call to action, and an anchored nav.
        LinearLayout root = vertical();
        root.setBackgroundColor(PALE);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                bottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(20), dp(18), dp(29));
        content.setFocusableInTouchMode(true);
        scroll.addView(content);
        content.requestFocus();

        // Identity / hero header
        LinearLayout hero = vertical();
        hero.setPadding(dp(21), dp(22), dp(21), dp(23));
        hero.setBackground(gradient(Color.rgb(10, 36, 51), Color.rgb(19, 91, 101), 26));
        hero.setElevation(dp(5));

        LinearLayout brand = horizontal();
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView emblem = label("↓", 28, Color.rgb(251, 201, 104), true);
        emblem.setGravity(Gravity.CENTER);
        emblem.setBackground(outline(Color.rgb(27, 81, 92), Color.rgb(76, 135, 139), 15));
        brand.addView(emblem, new LinearLayout.LayoutParams(dp(55), dp(55)));
        LinearLayout brandText = vertical();
        brandText.addView(label("JAVID  DOWNLOADER", 15, Color.WHITE, true));
        brandText.addView(gap(3));
        brandText.addView(label("جاوید دانلودر", 18, Color.WHITE, true));
        LinearLayout.LayoutParams bt = new LinearLayout.LayoutParams(0, -2, 1f);
        bt.setMargins(dp(12), 0, 0, 0);
        brand.addView(brandText, bt);
        hero.addView(brand);

        hero.addView(gap(22));
        hero.addView(label("لینک بده، فایل بگیر.", 23, Color.WHITE, true));
        hero.addView(gap(7));
        hero.addView(label("از ۱۰ پلتفرم مختلف، فایل‌های مجاز را دریافت کن", 13,
            Color.rgb(206, 230, 232), false));
        hero.addView(gap(17));
        LinearLayout tags = horizontal();
        tags.addView(badge("۱۰ پلتفرم", Color.rgb(255, 207, 119), NAVY));
        tags.addView(gapHorizontal(8));
        tags.addView(badge("تشخیص خودکار لینک", Color.rgb(31, 96, 105), Color.WHITE));
        tags.addView(gapHorizontal(8));
        tags.addView(badge("نسخه ۱.۲", Color.rgb(31, 96, 105), Color.WHITE));
        hero.addView(tags);
        content.addView(hero);
        content.addView(gap(14));
        LinearLayout sourcesCard = vertical();
        sourcesCard.setPadding(dp(15), dp(13), dp(15), dp(13));
        sourcesCard.setBackground(round(Color.WHITE, 17));
        sourcesCard.addView(label("پلتفرم‌های قابل بررسی", 13, TEXT, true));
        sourcesCard.addView(gap(7));
        TextView sourceList = label("YouTube  •  Instagram  •  Facebook  •  TikTok  •  X\\n"
            + "Pinterest  •  Vimeo  •  Reddit  •  Dailymotion  •  Twitch", 11, MUTED, false);
        sourceList.setTextDirection(View.TEXT_DIRECTION_LTR);
        sourceList.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        sourcesCard.addView(sourceList);
        content.addView(sourcesCard);
        content.addView(gap(23));

        LinearLayout sectionHeading = horizontal();
        sectionHeading.setGravity(Gravity.CENTER_VERTICAL);
        sectionHeading.addView(label("دانلود جدید", 20, TEXT, true),
            new LinearLayout.LayoutParams(0, -2, 1f));
        sectionHeading.addView(badge("لینک مستقیم", Color.rgb(227, 243, 241), TEAL));
        content.addView(sectionHeading);
        content.addView(gap(13));

        downloadPanel = vertical();
        LinearLayout card = vertical();
        card.setPadding(dp(17), dp(20), dp(17), dp(20));
        card.setBackground(round(Color.WHITE, 22));
        card.setElevation(dp(3));

        card.addView(label("آدرس ویدیو", 15, TEXT, true));
        card.addView(gap(5));
        card.addView(label("لینک یکی از ۱۰ پلتفرم پشتیبانی‌شده را وارد کن", 12, MUTED, false));
        card.addView(gap(13));

        linkBox = new EditText(this);
        linkBox.setSingleLine(false);
        linkBox.setMinLines(2);
        linkBox.setMaxLines(3);
        linkBox.setTextSize(14);
        linkBox.setTextColor(TEXT);
        linkBox.setHintTextColor(MUTED);
        linkBox.setHint("https://www.tiktok.com/...\nیا لینک شبکه‌های دیگر...");
        linkBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkBox.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        linkBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        linkBox.setBackground(outline(Color.rgb(249, 251, 252), Color.rgb(220, 232, 235), 13));
        card.addView(linkBox, new LinearLayout.LayoutParams(-1, dp(90)));
        card.addView(gap(10));
        sourceInfo = label("شبکه را پس از وارد کردن لینک تشخیص می‌دهم.", 12, MUTED, false);
        sourceInfo.setPadding(dp(9), dp(8), dp(9), dp(8));
        sourceInfo.setBackground(round(Color.rgb(244, 247, 249), 10));
        card.addView(sourceInfo);
        linkBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                showDetectedPlatform(text.toString());
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        card.addView(gap(12));

        Button paste = styledButton("⎘  چسباندن لینک", Color.rgb(232, 245, 243),
            Color.rgb(0, 126, 110), 13, 12);
        card.addView(paste, new LinearLayout.LayoutParams(-1, dp(45)));
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                linkBox.setText(String.valueOf(clip.getItemAt(0).coerceToText(this)));
            } else { toast("متنی در کلیپ‌بورد نیست"); }
        });

        card.addView(gap(22));
        card.addView(label("کیفیت خروجی", 15, TEXT, true));
        card.addView(gap(9));
        LinearLayout qualityWrap = horizontal();
        qualityWrap.setPadding(dp(12), 0, dp(12), 0);
        qualityWrap.setGravity(Gravity.CENTER_VERTICAL);
        qualityWrap.setBackground(outline(Color.rgb(249, 251, 252), Color.rgb(220, 232, 235), 13));
        TextView resolutionIcon = label("▣", 22, TEAL, true);
        qualityWrap.addView(resolutionIcon);
        quality = new Spinner(this);
        String[] options = {"بهترین کیفیت", "1080p", "720p", "480p", "360p", "فقط صدا (MP3)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options);
        quality.setAdapter(adapter);
        qualityWrap.addView(quality, new LinearLayout.LayoutParams(0, dp(54), 1f));
        card.addView(qualityWrap);

        card.addView(gap(18));
        LinearLayout utilities = horizontal();
        checkButton = styledButton("بررسی لینک", Color.rgb(244, 247, 250), NAVY, 12, 12);
        updateButton = styledButton("↻  به‌روزرسانی موتور", Color.rgb(244, 247, 250), NAVY, 12, 12);
        utilities.addView(checkButton, new LinearLayout.LayoutParams(0, dp(47), 1f));
        utilities.addView(gapHorizontal(8));
        utilities.addView(updateButton, new LinearLayout.LayoutParams(0, dp(47), 1f));
        card.addView(utilities);
        checkButton.setOnClickListener(v -> inspect());
        updateButton.setOnClickListener(v -> updateEngine(false));

        card.addView(gap(14));
        startButton = styledButton("↓    شروع دانلود", TEAL, Color.WHITE, 17, 15);
        startButton.setBackground(gradient(Color.rgb(4, 163, 143), Color.rgb(0, 128, 118), 15));
        startButton.setElevation(dp(2));
        card.addView(startButton, new LinearLayout.LayoutParams(-1, dp(56)));
        startButton.setOnClickListener(v -> download());

        cancelButton = styledButton("توقف دانلود", Color.rgb(255, 241, 239),
            Color.rgb(182, 61, 57), 14, 12);
        cancelButton.setVisibility(View.GONE);
        card.addView(cancelButton, margin(0, 10, 0, 0));
        cancelButton.setOnClickListener(v -> {
            if (isDownloading) {
                YoutubeDL.getInstance().destroyProcessById(processId);
                setStatus("درخواست توقف ارسال شد", true);
            }
        });
        downloadPanel.addView(card);

        downloadPanel.addView(gap(17));
        LinearLayout statusCard = vertical();
        statusCard.setPadding(dp(17), dp(17), dp(17), dp(18));
        statusCard.setBackground(round(Color.WHITE, 19));
        statusCard.setElevation(dp(1));
        LinearLayout statusHeading = horizontal();
        statusHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView smallDot = label("●", 15, TEAL, true);
        statusHeading.addView(smallDot);
        TextView statusTitle = label("  وضعیت دانلود", 16, TEXT, true);
        statusHeading.addView(statusTitle);
        statusCard.addView(statusHeading);
        statusCard.addView(gap(12));
        state = label("در حال آماده‌سازی موتور دانلود...", 13, TEAL, true);
        statusCard.addView(state);
        statusCard.addView(gap(12));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setProgressTintList(ColorStateList.valueOf(TEAL));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(228, 237, 239)));
        statusCard.addView(progress, new LinearLayout.LayoutParams(-1, dp(7)));
        detail = label("بعد از وارد کردن لینک، روی شروع دانلود بزن.", 12, MUTED, false);
        statusCard.addView(detail, margin(0, 13, 0, 0));
        downloadPanel.addView(statusCard);
        downloadPanel.addView(gap(15));

        LinearLayout hintCard = horizontal();
        hintCard.setGravity(Gravity.CENTER_VERTICAL);
        hintCard.setPadding(dp(13), dp(14), dp(13), dp(14));
        hintCard.setBackground(round(Color.rgb(231, 242, 245), 16));
        hintCard.addView(label("ⓘ", 21, Color.rgb(35, 112, 127), true));
        TextView hint = label("دانلود فقط برای محتوای مجاز و در دسترس عمومی است. لینک‌های خصوصی، حفاظت‌شده یا محدود ممکن است کار نکنند.",
            12, Color.rgb(74, 103, 114), false);
        LinearLayout.LayoutParams hintParam = new LinearLayout.LayoutParams(0, -2, 1f);
        hintParam.setMargins(dp(9), 0, 0, 0);
        hintCard.addView(hint, hintParam);
        downloadPanel.addView(hintCard);
        content.addView(downloadPanel);

        historyPanel = vertical();
        historyPanel.setVisibility(View.GONE);
        historyPanel.addView(label("دانلودهای من", 21, TEXT, true));
        historyPanel.addView(gap(5));
        historyPanel.addView(label("فایل‌های ذخیره‌شده روی گوشی", 12, MUTED, false));
        historyPanel.addView(gap(15));
        LinearLayout histCard = vertical();
        histCard.setPadding(dp(14), dp(17), dp(14), dp(14));
        histCard.setBackground(round(Color.WHITE, 21));
        histCard.setElevation(dp(2));
        historyItems = vertical();
        histCard.addView(historyItems);
        historyPanel.addView(histCard);
        content.addView(historyPanel);

        // Persistent bottom navigation, with correct Android window inset padding.
        LinearLayout bottom = horizontal();
        bottom.setPadding(dp(18), dp(11), dp(18), dp(13));
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackgroundColor(Color.WHITE);
        bottom.setElevation(dp(12));
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        Button downTab = styledButton("↓   دانلود", NAVY, Color.WHITE, 15, 13);
        Button histTab = styledButton("◷   تاریخچه", Color.rgb(241, 246, 247), NAVY, 15, 13);
        bottom.addView(downTab, new LinearLayout.LayoutParams(0, dp(51), 1f));
        bottom.addView(gapHorizontal(10));
        bottom.addView(histTab, new LinearLayout.LayoutParams(0, dp(51), 1f));
        downTab.setOnClickListener(v -> {
            downloadPanel.setVisibility(View.VISIBLE);
            historyPanel.setVisibility(View.GONE);
            downTab.setBackground(round(NAVY, 15));
            downTab.setTextColor(Color.WHITE);
            histTab.setBackground(round(Color.rgb(241, 246, 247), 15));
            histTab.setTextColor(NAVY);
            scroll.smoothScrollTo(0, 0);
        });
        histTab.setOnClickListener(v -> {
            refreshHistory();
            downloadPanel.setVisibility(View.GONE);
            historyPanel.setVisibility(View.VISIBLE);
            histTab.setBackground(round(NAVY, 15));
            histTab.setTextColor(Color.WHITE);
            downTab.setBackground(round(Color.rgb(241, 246, 247), 15));
            downTab.setTextColor(NAVY);
            scroll.smoothScrollTo(0, 0);
        });
    }

    private void updateEngine(boolean automatic) {
        if (!begin()) return;
        setStatus(automatic ? "در حال بررسی نسخه جدید موتور دانلود..." : "در حال به‌روزرسانی موتور دانلود...", false);
        detail.setText("برای دریافت نسخه جدید اینترنت لازم است. لطفاً منتظر بمان.");
        executor.execute(() -> {
            try {
                YoutubeDL.UpdateStatus result = YoutubeDL.getInstance()
                    .updateYoutubeDL(getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
                getPreferences(MODE_PRIVATE).edit().putLong("last_engine_check", System.currentTimeMillis()).apply();
                String version = YoutubeDL.getInstance().version(getApplicationContext());
                String text = result == YoutubeDL.UpdateStatus.DONE
                    ? "موتور دانلود با موفقیت به‌روز شد ✓"
                    : "موتور دانلود به‌روز است ✓";
                runOnUiThread(() -> {
                    setStatus(text, false);
                    detail.setText(version == null ? "اکنون دانلود را امتحان کن" : "نسخه موتور: " + version);
                });
            } catch (Exception e) {
                getPreferences(MODE_PRIVATE).edit().putLong("last_engine_check", System.currentTimeMillis()).apply();
                runOnUiThread(() -> {
                    setStatus("به‌روزرسانی انجام نشد: " + message(e), true);
                    detail.setText("اتصال اینترنت را بررسی کن یا دوباره دکمه به‌روزرسانی را بزن.");
                });
            } finally {
                runOnUiThread(() -> end());
            }
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
                    setStatus("لینک " + platformName(link) + " قابل دریافت است", false);
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
        setStatus("در حال آماده‌سازی دانلود از " + platformName(link) + "...", false);
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
                        // Some hosts provide only a combined video+audio stream.
                        // Fall back to the nearest available format up to selected height.
                        fmt = "bv*[height<=" + height + "]+ba/"
                            + "bv*[height<=" + height + "]/"
                            + "b[height<=" + height + "]/best[height<=" + height + "]";
                    }
                    req.addOption("-f", fmt);
                    req.addOption("--merge-output-format", "mp4");
                }
                final long[] last = {0};
                Function3<Float, Long, String, Unit> onProgress = new Function3<Float, Long, String, Unit>() {
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
                };
                try {
                    YoutubeDL.getInstance().execute(req, processId, onProgress);
                } catch (Exception firstError) {
                    String failure = firstError.getMessage() == null ? "" :
                        firstError.getMessage().toLowerCase(Locale.ROOT);
                    // The host may omit numerical height metadata for the user's
                    // selected format. Retry once using the best available format.
                    // Do not retry authentication, private, restricted, or DRM errors.
                    boolean unavailable = failure.contains("requested format is not available") ||
                        failure.contains("requested format not available");
                    if (chosen == 5 || chosen == 0 || !unavailable) throw firstError;
                    runOnUiThread(() -> {
                        progress.setProgress(0);
                        setStatus("کیفیت انتخابی در این ویدیو موجود نیست؛ تلاش با کیفیت موجود...", false);
                        detail.setText("ممکن است کیفیت فایل نهایی با انتخاب شما متفاوت باشد.");
                    });
                    File[] partialFiles = job.listFiles();
                    if (partialFiles != null) {
                        for (File partial : partialFiles) deleteRecursive(partial);
                    }
                    YoutubeDLRequest fallback = new YoutubeDLRequest(link);
                    fallback.addOption("--no-playlist");
                    fallback.addOption("--no-mtime");
                    fallback.addOption("-o", new File(job, "%(title).130B [%(id)s].%(ext)s").getAbsolutePath());
                    fallback.addOption("-f", "bv*+ba/b");
                    fallback.addOption("--merge-output-format", "mp4");
                    last[0] = 0;
                    YoutubeDL.getInstance().execute(fallback, processId, onProgress);
                }
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
                TextView emptyIcon = label("↓", 42, TEAL, true);
                emptyIcon.setGravity(Gravity.CENTER);
                emptyIcon.setBackground(round(Color.rgb(230, 247, 242), 22));
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(76), dp(76));
                iconParams.gravity = Gravity.CENTER_HORIZONTAL;
                iconParams.setMargins(0, dp(24), 0, dp(17));
                historyItems.addView(emptyIcon, iconParams);
                TextView emptyTitle = label("هنوز دانلودی ثبت نشده", 17, TEXT, true);
                emptyTitle.setGravity(Gravity.CENTER);
                historyItems.addView(emptyTitle);
                TextView emptyText = label("وقتی فایلی دانلود کنی، اینجا نمایش داده می‌شود.", 12, MUTED, false);
                emptyText.setGravity(Gravity.CENTER);
                historyItems.addView(emptyText, margin(0, 10, 0, 32));
                return;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                final Uri uri = Uri.parse(item.getString("uri"));
                LinearLayout row = vertical();
                row.setPadding(dp(12), dp(14), dp(12), dp(14));
                row.setBackground(outline(Color.rgb(250, 252, 253), Color.rgb(228, 235, 238), 15));
                LinearLayout top = horizontal();
                top.setGravity(Gravity.CENTER_VERTICAL);
                TextView fileIcon = label("♫", 22, TEAL, true);
                if (!item.optString("name", "").toLowerCase(Locale.ROOT).endsWith(".mp3")) {
                    fileIcon.setText("▣");
                }
                fileIcon.setGravity(Gravity.CENTER);
                fileIcon.setBackground(round(Color.rgb(229, 244, 242), 13));
                top.addView(fileIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
                LinearLayout info = vertical();
                TextView title = label(item.optString("name", "فایل"), 13, TEXT, true);
                title.setMaxLines(2);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                info.addView(title);
                info.addView(gap(5));
                info.addView(label(item.optString("date", ""), 11, MUTED, false));
                LinearLayout.LayoutParams infoParam = new LinearLayout.LayoutParams(0, -2, 1f);
                infoParam.setMargins(dp(10), 0, 0, 0);
                top.addView(info, infoParam);
                row.addView(top);
                Button open = styledButton("باز کردن فایل ←", Color.rgb(230, 247, 243), TEAL, 13, 11);
                row.addView(open, margin(0, 10, 0, 0));
                open.setOnClickListener(v -> {
                    try {
                        String type = getContentResolver().getType(uri);
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(uri, type == null ? "*/*" : type);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(intent);
                    } catch (Exception e) { toast("برای این فایل برنامه مناسب نصب نیست"); }
                });
                historyItems.addView(row, margin(0, 0, 0, 10));
            }
        } catch (Exception e) {
            historyItems.addView(label("خواندن تاریخچه ممکن نبود.", 14, MUTED, false));
        }
    }

    // Recognise only explicit supported domains. Suffix matching must include the
    // leading dot so e.g. "evilfacebook.com" is never treated as Facebook.
    private boolean isDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private String platformFromHost(String host) {
        if (host == null) return null;
        String h = host.toLowerCase(Locale.ROOT);
        if (isDomain(h, "youtube.com") || isDomain(h, "youtu.be") ||
            isDomain(h, "youtube-nocookie.com")) return "YouTube";
        if (isDomain(h, "instagram.com") || isDomain(h, "instagr.am")) return "Instagram";
        if (isDomain(h, "facebook.com") || isDomain(h, "fb.watch") ||
            isDomain(h, "fb.com")) return "Facebook";
        if (isDomain(h, "tiktok.com")) return "TikTok";
        if (isDomain(h, "x.com") || isDomain(h, "twitter.com")) return "X / Twitter";
        if (isDomain(h, "pinterest.com") || isDomain(h, "pin.it")) return "Pinterest";
        if (isDomain(h, "vimeo.com")) return "Vimeo";
        if (isDomain(h, "reddit.com") || isDomain(h, "redd.it")) return "Reddit";
        if (isDomain(h, "dailymotion.com") || isDomain(h, "dai.ly")) return "Dailymotion";
        if (isDomain(h, "twitch.tv")) return "Twitch";
        return null;
    }

    private String extractLink(String input) {
        Matcher matcher = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE)
            .matcher(input.trim());
        String candidate = matcher.find() ? matcher.group() : input.trim();
        // Share-sheet captions often end the copied URL with punctuation.
        return candidate.replaceAll("[),;]+$", "");
    }

    private String platformName(String url) {
        try {
            Uri parsed = Uri.parse(url);
            String result = platformFromHost(parsed.getHost());
            return result == null ? "نامشخص" : result;
        } catch (Exception ex) { return "نامشخص"; }
    }

    private void showDetectedPlatform(String input) {
        if (sourceInfo == null) return;
        String url = extractLink(input);
        Uri uri = Uri.parse(url);
        String name = platformFromHost(uri.getHost());
        if (name != null && "https".equalsIgnoreCase(uri.getScheme())) {
            sourceInfo.setText("✓   " + name + " شناسایی شد. آماده بررسی لینک.");
            sourceInfo.setTextColor(TEAL);
            sourceInfo.setBackground(round(Color.rgb(233, 248, 241), 10));
        } else if (input.trim().isEmpty()) {
            sourceInfo.setText("شبکه را پس از وارد کردن لینک تشخیص می‌دهم.");
            sourceInfo.setTextColor(MUTED);
            sourceInfo.setBackground(round(Color.rgb(244, 247, 249), 10));
        } else {
            sourceInfo.setText("لینک معتبرِ یکی از ۱۰ پلتفرم را با https وارد کن.");
            sourceInfo.setTextColor(Color.rgb(170, 101, 39));
            sourceInfo.setBackground(round(Color.rgb(253, 247, 231), 10));
        }
    }

    private String validatedLink() {
        String url = extractLink(linkBox.getText().toString());
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) ||
            platformFromHost(uri.getHost()) == null) {
            toast("لینک HTTPS از یکی از ۱۰ پلتفرم پشتیبانی‌شده وارد کن");
            return null;
        }
        return url;
    }

    private boolean begin() {
        if (!engineReady) { toast("موتور دانلود هنوز آماده نیست"); return false; }
        if (busy) { toast("یک عملیات دیگر در حال اجراست"); return false; }
        busy = true;
        startButton.setEnabled(false);
        checkButton.setEnabled(false);
        updateButton.setEnabled(false);
        return true;
    }

    private void end() {
        busy = false;
        startButton.setEnabled(true);
        checkButton.setEnabled(true);
        updateButton.setEnabled(true);
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
        // A yt-dlp warning may precede the actual failure. Show the last real ERROR line.
        int pos = m.lastIndexOf("ERROR:");
        if (pos >= 0) m = m.substring(pos);
        m = m.trim();
        int newline = m.indexOf('\n');
        if (newline > 0) m = m.substring(0, newline);
        return m.length() > 290 ? m.substring(0, 290) : m;
    }
    private void setStatus(String s, boolean error) {
        if (state != null) {
            state.setText(s);
            state.setTextColor(error ? Color.rgb(182, 58, 54) : TEAL);
        }
        if (error && detail != null) detail.setText("اگر خطا ادامه داشت، موتور دانلود را به‌روز کن و لینک را دوباره امتحان کن.");
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private View gap(int h) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h))); return v; }
    private View gapHorizontal(int w) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), 1)); return v; }
    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b)); return lp;
    }
    private LinearLayout horizontal() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return ll;
    }
    private GradientDrawable outline(int fill, int stroke, int radius) {
        GradientDrawable bg = round(fill, radius);
        bg.setStroke(dp(1), stroke);
        return bg;
    }
    private GradientDrawable gradient(int from, int to, int radius) {
        GradientDrawable bg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[]{from, to});
        bg.setCornerRadius(dp(radius));
        return bg;
    }
    private TextView badge(String title, int bg, int fg) {
        TextView t = label(title, 11, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(11), dp(7), dp(11), dp(7));
        t.setBackground(round(bg, 50));
        return t;
    }
    private Button styledButton(String title, int bg, int fg, int textSize, int radius) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(textSize);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setIncludeFontPadding(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(7), 0, dp(7), 0);
        b.setBackground(round(bg, radius));
        b.setMinHeight(dp(44));
        b.setStateListAnimator(null);
        return b;
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
