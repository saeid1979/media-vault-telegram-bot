package com.javid.downloader;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.ImageView;
import android.widget.FrameLayout;
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
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
    private TextView state, detail, sourceInfo, previewTitle, previewMeta, previewDuration, engineUpdateStatus;
    private ProgressBar progress, updateProgress;
    private Button startButton, checkButton, cancelButton, updateButton, videoButton, audioButton;
    private Spinner quality;
    private LinearLayout downloadPanel, historyPanel, morePanel, historyItems, previewPanel, botPanel;
    private ImageView previewImage;
    private EditText botTokenInput;
    private TextView botStatus, botIdentity, pairingInfo;
    private android.widget.Switch wifiOnly;
    private final Handler botUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable botUiTick = new Runnable() {
        @Override public void run() { refreshBotUi(); botUiHandler.postDelayed(this, 4000); }
    };
    private volatile String previewUrl = "";
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
                    engineUpdateStatus.setText("موتور آماده است. برای دریافت جدیدترین نسخه دکمه بالا را بزن.");
                    engineUpdateStatus.setTextColor(TEAL);
                    long lastCheck = getPreferences(MODE_PRIVATE).getLong("last_engine_check", 0L);
                    if (System.currentTimeMillis() - lastCheck > 24L * 60 * 60 * 1000) {
                        updateEngine(true);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("راه‌اندازی موتور دانلود ناموفق بود: " + message(e), true);
                    engineUpdateStatus.setText("راه‌اندازی موتور ناموفق بود: " + message(e));
                    engineUpdateStatus.setTextColor(Color.rgb(178, 50, 52));
                });
            }
        });
    }


    private void buildUi() {
        // Clean media-card design inspired by the reference, with original branding.
        final int LAVENDER = Color.rgb(229, 234, 252);
        final int INK = Color.rgb(31, 39, 62);
        LinearLayout root = vertical();
        root.setBackgroundColor(Color.rgb(251, 251, 255));
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
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(12), dp(18), dp(25));
        content.setFocusableInTouchMode(true);
        scroll.addView(content);
        content.requestFocus();

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label("JAVID", 26, Color.rgb(214, 64, 78), true));
        header.addView(label("dl", 26, INK, true));
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        header.addView(badge("v1.9", LAVENDER, INK));
        content.addView(header, margin(0, 9, 0, 24));

        downloadPanel = vertical();
        content.addView(downloadPanel);
        LinearLayout search = horizontal();
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(7), dp(4), dp(7), dp(4));
        search.setBackground(round(LAVENDER, 38));
        checkButton = styledButton("⌕", Color.TRANSPARENT, INK, 30, 28);
        checkButton.setContentDescription("جستجو و نمایش پیش‌نمایش");
        search.addView(checkButton, new LinearLayout.LayoutParams(dp(52), dp(55)));
        linkBox = new EditText(this);
        linkBox.setSingleLine(true);
        linkBox.setTextSize(15);
        linkBox.setTextColor(INK);
        linkBox.setHintTextColor(Color.rgb(109, 119, 145));
        linkBox.setHint("جستجو یا وارد کردن لینک");
        linkBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkBox.setGravity(Gravity.CENTER_VERTICAL);
        linkBox.setBackgroundColor(Color.TRANSPARENT);
        linkBox.setPadding(dp(3), 0, dp(3), 0);
        search.addView(linkBox, new LinearLayout.LayoutParams(0, dp(55), 1));
        Button paste = styledButton("⋮", Color.TRANSPARENT, INK, 28, 28);
        paste.setContentDescription("چسباندن لینک");
        search.addView(paste, new LinearLayout.LayoutParams(dp(40), dp(55)));
        downloadPanel.addView(search);
        checkButton.setOnClickListener(v -> inspect());
        paste.setOnClickListener(v -> pasteLink());
        linkBox.setOnEditorActionListener((v, actionId, event) -> { inspect(); return true; });
        sourceInfo = label("لینک یک ویدیوی مجاز را وارد و بررسی کن.", 12, MUTED, false);
        downloadPanel.addView(sourceInfo, margin(0, 11, 0, 4));
        linkBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                showDetectedPlatform(s.toString());
                if (!extractLink(s.toString()).equals(previewUrl) && previewPanel != null)
                    previewPanel.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        downloadPanel.addView(gap(21));

        // Real preview card populated from the yt-dlp metadata of the input link.
        previewPanel = vertical();
        previewPanel.setVisibility(View.GONE);
        previewPanel.setBackground(round(Color.WHITE, 23));
        previewPanel.setElevation(dp(3));
        FrameLayout media = new FrameLayout(this);
        media.setBackground(round(Color.rgb(38, 48, 81), 19));
        media.setClipToOutline(true);
        previewImage = new ImageView(this);
        previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewImage.setBackground(gradient(Color.rgb(44, 58, 96), Color.rgb(18, 26, 47), 19));
        media.addView(previewImage, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout shade = vertical();
        shade.setPadding(dp(14), dp(12), dp(14), dp(12));
        shade.setBackgroundColor(Color.argb(195, 19, 23, 41));
        previewTitle = label("در حال بررسی ویدیو...", 16, Color.WHITE, true);
        previewTitle.setMaxLines(2);
        previewTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        shade.addView(previewTitle);
        previewMeta = label("", 12, Color.rgb(232, 235, 247), false);
        shade.addView(previewMeta, margin(0, 5, 0, 0));
        media.addView(shade, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        previewDuration = badge("—", Color.argb(207, 30, 35, 55), Color.WHITE);
        FrameLayout.LayoutParams dur = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        dur.setMargins(dp(12), 0, dp(12), dp(12));
        media.addView(previewDuration, dur);
        previewPanel.addView(media, new LinearLayout.LayoutParams(-1, dp(212)));
        LinearLayout actions = horizontal();
        actions.setPadding(dp(12), dp(11), dp(12), dp(12));
        videoButton = styledButton("▣  ویدیو", LAVENDER, INK, 15, 14);
        audioButton = styledButton("♫  MP3", LAVENDER, INK, 15, 14);
        actions.addView(videoButton, new LinearLayout.LayoutParams(0, dp(55), 1));
        actions.addView(gapHorizontal(10));
        actions.addView(audioButton, new LinearLayout.LayoutParams(0, dp(55), 1));
        previewPanel.addView(actions);
        videoButton.setOnClickListener(v -> download(quality.getSelectedItemPosition() == 5 ? 0 : quality.getSelectedItemPosition()));
        audioButton.setOnClickListener(v -> download(5));
        downloadPanel.addView(previewPanel);
        downloadPanel.addView(gap(14));

        LinearLayout formatCard = vertical();
        formatCard.setPadding(dp(16), dp(15), dp(16), dp(17));
        formatCard.setBackground(outline(Color.WHITE, Color.rgb(231, 234, 243), 19));
        formatCard.addView(label("کیفیت ویدیو", 15, INK, true));
        quality = new Spinner(this);
        String[] options = {"بهترین کیفیت", "1080p", "720p", "480p", "360p", "فقط صدا (MP3)"};
        quality.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options));
        quality.setSelection(getPreferences(MODE_PRIVATE).getInt("quality", 0));
        quality.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                getPreferences(MODE_PRIVATE).edit().putInt("quality", pos).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        formatCard.addView(quality, new LinearLayout.LayoutParams(-1, dp(50)));
        startButton = styledButton("↓   شروع دانلود", NAVY, Color.WHITE, 16, 14);
        formatCard.addView(startButton, new LinearLayout.LayoutParams(-1, dp(49)));
        startButton.setOnClickListener(v -> download());
        downloadPanel.addView(formatCard);
        downloadPanel.addView(gap(16));

        LinearLayout status = vertical();
        status.setBackground(round(Color.WHITE, 19));
        status.setPadding(dp(15), dp(15), dp(15), dp(15));
        status.addView(label("وضعیت دانلود", 16, INK, true));
        state = label("آماده‌سازی موتور دانلود...", 13, TEAL, true);
        status.addView(state, margin(0, 9, 0, 9));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(TEAL));
        status.addView(progress, new LinearLayout.LayoutParams(-1, dp(7)));
        detail = label("عنوان و تصویر ویدیو بعد از بررسی لینک نمایش داده می‌شود.", 12, MUTED, false);
        status.addView(detail, margin(0, 10, 0, 0));
        cancelButton = styledButton("توقف دانلود", Color.rgb(255, 237, 237), Color.rgb(167, 60, 60), 13, 12);
        cancelButton.setVisibility(View.GONE);
        status.addView(cancelButton, margin(0, 11, 0, 0));
        cancelButton.setOnClickListener(v -> { if (isDownloading) YoutubeDL.getInstance().destroyProcessById(processId); });
        downloadPanel.addView(status);
        downloadPanel.addView(label("فقط محتوای مجاز و قابل دریافت را دانلود کن.", 12, MUTED, false), margin(0, 16, 0, 0));

        historyPanel = vertical();
        historyPanel.setVisibility(View.GONE);
        historyPanel.addView(label("دانلودها", 24, INK, true));
        historyPanel.addView(gap(15));
        LinearLayout past = vertical();
        past.setBackground(round(Color.WHITE, 20));
        past.setPadding(dp(13), dp(13), dp(13), dp(13));
        historyItems = vertical();
        past.addView(historyItems);
        historyPanel.addView(past);
        content.addView(historyPanel);

        // Phone-hosted Telegram bot companion. No token is included in the APK.
        botPanel = vertical();
        botPanel.setVisibility(View.GONE);
        botPanel.addView(label("بات تلگرام", 24, INK, true));
        botPanel.addView(gap(7));
        botPanel.addView(label("بات فعلی خودت را روی همین گوشی اجرا و مدیریت کن.", 13, MUTED, false));
        botPanel.addView(gap(14));
        LinearLayout botCard = vertical();
        botCard.setPadding(dp(16), dp(18), dp(16), dp(18));
        botCard.setBackground(round(Color.WHITE, 20));
        botCard.addView(label("اتصال بات موجود", 17, INK, true));
        botCard.addView(gap(8));
        botCard.addView(label("توکن فعلی BotFather را فقط اینجا وارد کن؛ به‌صورت رمزنگاری‌شده روی گوشی ذخیره می‌شود.",
            12, MUTED, false));
        botCard.addView(gap(13));
        botTokenInput = new EditText(this);
        botTokenInput.setTextSize(13);
        botTokenInput.setSingleLine(true);
        botTokenInput.setHint("توکن بات موجود...");
        botTokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        botTokenInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        botTokenInput.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        botTokenInput.setPadding(dp(13), 0, dp(13), 0);
        botTokenInput.setBackground(outline(Color.rgb(247, 249, 252), Color.rgb(225, 230, 242), 13));
        botCard.addView(botTokenInput, new LinearLayout.LayoutParams(-1, dp(54)));
        botCard.addView(gap(10));
        Button saveToken = styledButton("🔒  ذخیره امن توکن", Color.rgb(231, 238, 252), INK, 13, 13);
        botCard.addView(saveToken, new LinearLayout.LayoutParams(-1, dp(50)));
        saveToken.setOnClickListener(v -> saveBotToken());
        botCard.addView(gap(14));
        botIdentity = label("هنوز توکن ثبت نشده", 13, INK, true);
        botCard.addView(botIdentity);
        botCard.addView(gap(6));
        botStatus = label("وضعیت: خاموش", 12, MUTED, false);
        botCard.addView(botStatus);
        botCard.addView(gap(12));
        LinearLayout controls = horizontal();
        Button run = styledButton("▶  روشن کردن بات", TEAL, Color.WHITE, 13, 13);
        Button stop = styledButton("■  خاموش کردن", Color.rgb(243, 232, 232), Color.rgb(154, 51, 54), 13, 13);
        controls.addView(run, new LinearLayout.LayoutParams(0, dp(53), 1f));
        controls.addView(gapHorizontal(9));
        controls.addView(stop, new LinearLayout.LayoutParams(0, dp(53), 1f));
        botCard.addView(controls);
        run.setOnClickListener(v -> startTelegramBot());
        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, TelegramBotService.class).setAction(TelegramBotService.ACTION_STOP);
            startService(intent);
            toast("دستور توقف ارسال شد");
            botUiHandler.postDelayed(this::refreshBotUi, 900);
        });
        botCard.addView(gap(17));
        botCard.addView(label("اتصال حساب مالک", 15, INK, true));
        pairingInfo = label("پس از روشن کردن بات، کد اتصال را از این قسمت بردار و در تلگرام به بات بفرست.",
            12, MUTED, false);
        botCard.addView(pairingInfo, margin(0, 9, 0, 9));
        Button resetPair = styledButton("↻  ساخت کد اتصال جدید", Color.rgb(242, 244, 250), INK, 12, 13);
        botCard.addView(resetPair, new LinearLayout.LayoutParams(-1, dp(45)));
        resetPair.setOnClickListener(v -> {
            SharedPreferences p = getSharedPreferences(TelegramBotService.PREFS, MODE_PRIVATE);
            if (p.getLong("owner_id", 0L) != 0L) {
                toast("این بات قبلاً به مالک متصل شده است");
                return;
            }
            p.edit().remove("pair_code").remove("pair_expires").apply();
            if (p.getBoolean("enabled", false)) toast("بات را یک‌بار خاموش و روشن کن تا کد جدید ساخته شود");
            else toast("حالا بات را روشن کن تا کد اتصال نمایش داده شود");
            refreshBotUi();
        });
        botCard.addView(gap(17));
        wifiOnly = new android.widget.Switch(this);
        wifiOnly.setText("دانلودهای بات فقط با Wi-Fi");
        wifiOnly.setTextSize(13);
        wifiOnly.setTextColor(INK);
        wifiOnly.setChecked(getSharedPreferences(TelegramBotService.PREFS, MODE_PRIVATE)
            .getBoolean("wifi_only", true));
        wifiOnly.setOnCheckedChangeListener((btn, checked) -> {
            getSharedPreferences(TelegramBotService.PREFS, MODE_PRIVATE).edit()
                .putBoolean("wifi_only", checked).apply();
        });
        botCard.addView(wifiOnly);
        botCard.addView(gap(13));
        botCard.addView(label("اعضا و انتقال کاربران قبلی", 15, INK, true));
        botCard.addView(gap(6));
        botCard.addView(label("ثبت کاربران قدیمی با Telegram ID، حفظ VIP و سهمیه و پشتیبان‌گیری از اعضا", 12, MUTED, false));
        botCard.addView(gap(9));
        Button manageUsers = styledButton("👥  مدیریت و انتقال کاربران", Color.rgb(221, 235, 252), INK, 14, 13);
        botCard.addView(manageUsers, new LinearLayout.LayoutParams(-1, dp(55)));
        manageUsers.setOnClickListener(v -> startActivity(new Intent(this, UserManagerActivity.class)));
        botCard.addView(gap(9));
        Button dashboard = styledButton("📊  داشبورد مدیریتی و آمار هفتگی", Color.rgb(235, 237, 255), INK, 14, 13);
        botCard.addView(dashboard, new LinearLayout.LayoutParams(-1, dp(55)));
        dashboard.setOnClickListener(v -> startActivity(new Intent(this, DashboardActivity.class)));
        botCard.addView(gap(9));
        Button sendAnnouncement = styledButton("📢  ارسال اطلاعیه به اعضای بات", Color.rgb(231, 246, 240), INK, 14, 13);
        botCard.addView(sendAnnouncement, new LinearLayout.LayoutParams(-1, dp(55)));
        sendAnnouncement.setOnClickListener(v -> startActivity(new Intent(this, BroadcastActivity.class)));
        botCard.addView(gap(17));
        botCard.addView(label("📰 خبرنامه خودکار هر ساعت", 17, INK, true));
        botCard.addView(gap(7));
        botCard.addView(label("نتایج مهم فوتبال جهان + هواشناسی سالامانکا و اردبیل + تیترهای مهم ایران، با ذکر منبع. " +
            "انتشار در کانال فقط پس از تأیید مالک فعال می‌شود و ارسال خصوصی فقط برای اعضایی است که با /digestjoin مشترک شده‌اند.",
            12, MUTED, false));
        botCard.addView(gap(9));
        botCard.addView(label("از تلگرام (فقط مدیر):\n" +
            "/digestto @ChannelName  ← تنظیم آدرس کانال\n" +
            "/digestpreview  ← آزمایش پیش‌نمایش\n" +
            "/digeston  ← شروع انتشار ساعتی\n" +
            "/digestnow  ← ارسال همین حالا\n" +
            "/digeststatus  ← وضعیت ارسال\n" +
            "/digestoff  ← خاموش کردن\n\n" +
            "اعضا: /digestjoin برای عضویت خصوصی و /digestleave برای توقف.",
            12, INK, false));
        botCard.addView(gap(13));
        botCard.addView(label("⚠️ این برنامه روی گوشی اجرا می‌شود؛ با توقف سرویس، قطع اینترنت یا محدودیت اندروید، انتشار ساعتی هم متوقف می‌شود. " +
            "برای ارسال همیشگی ۲۴ساعته، یک سرویس زمان‌بندی سرور لازم است.",
            12, Color.rgb(146, 97, 41), false));
        botCard.addView(gap(13));
        botCard.addView(label("کاربران ثبت‌شده و تأییدشده اکنون می‌توانند از بات استفاده کنند. انتقال داده‌های برنامه قدیمی نیاز به ورود اطلاعات یا فایل پشتیبان سازگار دارد.",
            12, MUTED, false));
        botCard.addView(gap(8));
        botCard.addView(label("برای جلوگیری از خطای Telegram 409، بات را هم‌زمان روی سرور قبلی اجرا نکن. روشن‌ماندن گوشی و محدودیت‌های پس‌زمینه اندروید بر دسترس‌پذیری بات اثر دارند.",
            12, Color.rgb(146, 97, 41), false));
        botPanel.addView(botCard);
        content.addView(botPanel);
        refreshBotUi();

        morePanel = vertical();
        morePanel.setVisibility(View.GONE);
        morePanel.addView(label("بیشتر", 24, INK, true));
        morePanel.addView(gap(15));
        LinearLayout settings = vertical();
        settings.setBackground(round(Color.WHITE, 20));
        settings.setPadding(dp(17), dp(17), dp(17), dp(17));
        settings.addView(label("تنظیمات", 18, INK, true));
        settings.addView(gap(12));
        updateButton = styledButton("↻  بررسی و به‌روزرسانی موتور", NAVY, Color.WHITE, 13, 13);
        settings.addView(updateButton, new LinearLayout.LayoutParams(-1, dp(52)));
        updateButton.setOnClickListener(v -> updateEngine(false));
        updateProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateProgress.setIndeterminate(true);
        updateProgress.setProgressTintList(ColorStateList.valueOf(TEAL));
        updateProgress.setIndeterminateTintList(ColorStateList.valueOf(TEAL));
        updateProgress.setVisibility(View.GONE);
        settings.addView(updateProgress, margin(0, 10, 0, 3));
        engineUpdateStatus = label("وضعیت موتور: در حال آماده‌سازی...", 12, MUTED, false);
        settings.addView(engineUpdateStatus, margin(0, 10, 0, 0));
        settings.addView(gap(17));
        settings.addView(label("محل ذخیره فایل‌ها", 15, INK, true));
        settings.addView(label("Downloads / JavidDownloader", 13, MUTED, false));
        settings.addView(gap(16));
        settings.addView(label("پلتفرم‌های پشتیبانی‌شده", 15, INK, true));
        settings.addView(label("YouTube · Instagram · Facebook · TikTok · X\nPinterest · Vimeo · Reddit · Dailymotion · Twitch", 12, MUTED, false));
        settings.addView(gap(16));
        settings.addView(label("Javid Downloader 1.7.0", 13, INK, true));
        settings.addView(label("بدون حساب کاربری و بدون سرور اختصاصی", 12, MUTED, false));
        morePanel.addView(settings);
        content.addView(morePanel);

        LinearLayout nav = horizontal();
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(7), dp(7), dp(7), dp(7));
        nav.setBackgroundColor(Color.rgb(233, 236, 251));
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(84)));
        LinearLayout home = navTab("⌂", "خانه", true);
        LinearLayout downloads = navTab("↓", "دانلودها", false);
        LinearLayout telegram = navTab("✈", "بات", false);
        LinearLayout more = navTab("•••", "بیشتر", false);
        nav.addView(home, new LinearLayout.LayoutParams(0, -1, 1f));
        nav.addView(downloads, new LinearLayout.LayoutParams(0, -1, 1f));
        nav.addView(telegram, new LinearLayout.LayoutParams(0, -1, 1f));
        nav.addView(more, new LinearLayout.LayoutParams(0, -1, 1f));
        home.setOnClickListener(v -> selectTab(0, home, downloads, telegram, more, scroll));
        downloads.setOnClickListener(v -> {
            refreshHistory();
            selectTab(1, home, downloads, telegram, more, scroll);
        });
        telegram.setOnClickListener(v -> {
            refreshBotUi();
            selectTab(2, home, downloads, telegram, more, scroll);
        });
        more.setOnClickListener(v -> selectTab(3, home, downloads, telegram, more, scroll));
    }

    private LinearLayout navTab(String icon, String title, boolean selected) {
        LinearLayout tab = vertical();
        tab.setGravity(Gravity.CENTER);
        TextView symbol = label(icon, 23, Color.rgb(34, 42, 69), true);
        symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(round(selected ? Color.rgb(209, 219, 251) : Color.TRANSPARENT, 30));
        tab.addView(symbol, new LinearLayout.LayoutParams(-1, dp(42)));
        TextView name = label(title, 11, Color.rgb(34, 42, 69), selected);
        name.setGravity(Gravity.CENTER);
        tab.addView(name);
        return tab;
    }
    private void selectTab(int index, LinearLayout home, LinearLayout downloads,
                           LinearLayout telegram, LinearLayout more, ScrollView scroll) {
        downloadPanel.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        historyPanel.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        botPanel.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        morePanel.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        LinearLayout[] tabs = {home, downloads, telegram, more};
        for (int i = 0; i < tabs.length; i++) {
            TextView symbol = (TextView)tabs[i].getChildAt(0);
            TextView text = (TextView)tabs[i].getChildAt(1);
            symbol.setBackground(round(i == index ? Color.rgb(209, 219, 251) : Color.TRANSPARENT, 30));
            text.setTypeface(Typeface.DEFAULT, i == index ? Typeface.BOLD : Typeface.NORMAL);
        }
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void saveBotToken() {
        String token = botTokenInput.getText().toString().trim();
        if (!token.matches("[0-9]{7,12}:[A-Za-z0-9_-]{30,}")) {
            toast("فرمت توکن معتبر نیست. توکن اصلی BotFather را وارد کن.");
            return;
        }
        try {
            if (getSharedPreferences(TelegramBotService.PREFS, MODE_PRIVATE)
                .getBoolean("enabled", false)) {
                toast("برای تغییر توکن ابتدا بات را خاموش کن");
                return;
            }
            BotSecrets.save(this, token);
            botTokenInput.setText("");
            getSharedPreferences(TelegramBotService.PREFS, MODE_PRIVATE).edit()
                .remove("owner_id").remove("pair_code").remove("pair_expires")
                .remove("update_id").remove("bot_username").apply();
            toast("توکن بات امن ذخیره شد؛ حالا بات را روشن کن.");
            refreshBotUi();
        } catch (Exception e) {
            toast("ذخیره امن توکن ناموفق بود");
        }
    }

    private void startTelegramBot() {
        if (!BotSecrets.hasToken(this)) {
            toast("ابتدا توکن بات فعلی را وارد و ذخیره کن");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2109);
        }
        try {
            Intent start = new Intent(this, TelegramBotService.class);
            start.setAction(TelegramBotService.ACTION_START);
            startForegroundService(start);
            toast("ربات روی گوشی در حال راه‌اندازی است");
            botUiHandler.postDelayed(this::refreshBotUi, 1500);
        } catch (Exception e) {
            toast("اجازه اجرای بات در پس‌زمینه داده نشد: " + e.getClass().getSimpleName());
        }
    }

    private void refreshBotUi() {
        if (botStatus == null || botIdentity == null || pairingInfo == null) return;
        SharedPreferences p = getSharedPreferences(TelegramBotService.PREFS, MODE_PRIVATE);
        botIdentity.setText(BotSecrets.hasToken(this)
            ? "توکن ذخیره شده · " + (p.getString("bot_username", "").isEmpty()
                ? "در انتظار اتصال" : "@" + p.getString("bot_username", ""))
            : "هنوز توکن ذخیره نشده");
        botStatus.setText(p.getString(TelegramBotService.KEY_STATUS, "بات خاموش است"));
        botStatus.setTextColor(p.getBoolean("enabled", false) ? TEAL : MUTED);
        long owner = p.getLong("owner_id", 0L);
        if (owner != 0L) {
            pairingInfo.setText("✅ مالک تأیید شد. شناسه تلگرام: " + owner);
            return;
        }
        String code = p.getString("pair_code", "");
        if (!code.isEmpty() && System.currentTimeMillis() < p.getLong("pair_expires", 0L)) {
            pairingInfo.setText("کد ۱۰ دقیقه‌ای: " + code +
                "\nدر گفتگوی خصوصی بات خودت بنویس:\n/pair " + code);
        } else pairingInfo.setText("کد اتصال هنوز آماده نیست. برای دریافت کد بات را روشن کن.");
    }

    @Override protected void onResume() {
        super.onResume();
        botUiHandler.removeCallbacks(botUiTick);
        botUiHandler.post(botUiTick);
    }
    @Override protected void onPause() {
        botUiHandler.removeCallbacks(botUiTick);
        super.onPause();
    }

    private void pasteLink() {
        ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            linkBox.setText(String.valueOf(clip.getItemAt(0).coerceToText(this)));
            inspect();
        } else toast("لینکی در کلیپ‌بورد نیست");
    }

    private String durationText(int seconds) {
        if (seconds <= 0) return "—";
        int m = seconds / 60, rem = seconds % 60;
        if (m >= 60) return String.format(Locale.US, "%d:%02d:%02d", m / 60, m % 60, rem);
        return String.format(Locale.US, "%d:%02d", m, rem);
    }

    private void loadThumbnail(String url, String expectedLink) {
        if (url == null || !url.startsWith("https://")) return;
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection)new URL(url).openConnection();
                conn.setConnectTimeout(9000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                if (conn.getResponseCode() != 200 || conn.getContentLength() > 4_000_000) return;
                Bitmap bitmap;
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] bytes = new byte[8192];
                    int n, count = 0;
                    while ((n = in.read(bytes)) != -1) {
                        count += n;
                        if (count > 4_000_000) return;
                        out.write(bytes, 0, n);
                    }
                    byte[] data = out.toByteArray();
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 2;
                    bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                }
                if (bitmap != null) runOnUiThread(() -> {
                    if (expectedLink.equals(previewUrl)) previewImage.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
                // Some providers intentionally block remote thumbnail requests.
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void updateEngine(boolean automatic) {
        if (!engineReady) {
            engineUpdateStatus.setText("موتور هنوز آماده نیست. چند لحظه بعد دوباره امتحان کن.");
            toast("در حال آماده‌سازی موتور دانلود");
            return;
        }
        if (busy) {
            engineUpdateStatus.setText("یک عملیات دیگر در حال انجام است؛ پس از پایان دوباره امتحان کن.");
            toast("لطفاً تا پایان عملیات فعلی منتظر بمان");
            return;
        }
        if (!begin()) return;
        String pending = automatic
            ? "در حال بررسی خودکار به‌روزرسانی موتور..."
            : "در حال بررسی نسخه جدید و دریافت به‌روزرسانی...";
        setStatus(pending, false);
        detail.setText("برای این عملیات اتصال اینترنت لازم است.");
        engineUpdateStatus.setText(pending);
        engineUpdateStatus.setTextColor(NAVY);
        updateProgress.setVisibility(View.VISIBLE);
        updateButton.setText("◌  در حال به‌روزرسانی...");
        executor.execute(() -> {
            try {
                YoutubeDL.UpdateStatus result = YoutubeDL.getInstance()
                    .updateYoutubeDL(getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
                String version = YoutubeDL.getInstance().version(getApplicationContext());
                long now = System.currentTimeMillis();
                getPreferences(MODE_PRIVATE).edit().putLong("last_engine_check", now).apply();
                String outcome = result == YoutubeDL.UpdateStatus.DONE
                    ? "موتور دانلود با موفقیت به‌روز شد ✓"
                    : "موتور دانلود از قبل به‌روز بود ✓";
                String visible = outcome + (version == null || version.isEmpty() ? ""
                    : "\nنسخه: " + version);
                runOnUiThread(() -> {
                    setStatus(outcome, false);
                    detail.setText("موتور آماده دانلود است.");
                    engineUpdateStatus.setText(visible);
                    engineUpdateStatus.setTextColor(TEAL);
                    if (!automatic) toast(outcome);
                });
            } catch (Exception e) {
                String error = message(e);
                // A failed check must not be marked successful for the next 24h.
                runOnUiThread(() -> {
                    String failure = "به‌روزرسانی انجام نشد: " + error;
                    setStatus(failure, true);
                    detail.setText("اتصال اینترنت را بررسی و دوباره امتحان کن.");
                    engineUpdateStatus.setText(failure + "\nبرای تلاش دوباره روی دکمه بالا بزن.");
                    engineUpdateStatus.setTextColor(Color.rgb(178, 50, 52));
                    if (!automatic) toast("به‌روزرسانی انجام نشد");
                });
            } finally {
                runOnUiThread(() -> {
                    updateProgress.setVisibility(View.GONE);
                    updateButton.setText("↻  بررسی و به‌روزرسانی موتور");
                    end();
                });
            }
        });
    }

    private void inspect() {
        String link = validatedLink();
        if (link == null || !begin()) return;
        setStatus("در حال دریافت اطلاعات و تصویر ویدیو...", false);
        executor.execute(() -> {
            try {
                YoutubeDLRequest request = new YoutubeDLRequest(link);
                request.addOption("--no-playlist");
                VideoInfo info = YoutubeDL.getInstance().getInfo(request);
                String title = info.getTitle() == null ? "ویدیوی " + platformName(link) : info.getTitle();
                String uploader = info.getUploader() == null ? "" : info.getUploader();
                String thumb = info.getThumbnail();
                String length = durationText(info.getDuration());
                runOnUiThread(() -> {
                    if (!link.equals(extractLink(linkBox.getText().toString()))) return;
                    previewUrl = link;
                    previewTitle.setText(title);
                    previewMeta.setText(platformName(link) + (uploader.isEmpty() ? "" : "   ·   " + uploader));
                    previewDuration.setText(length);
                    previewImage.setImageDrawable(null);
                    previewPanel.setVisibility(View.VISIBLE);
                    setStatus("پیش‌نمایش آماده است ✓", false);
                    detail.setText("روی ویدیو یا MP3 بزن تا فایل ذخیره شود.");
                    loadThumbnail(thumb, link);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    previewPanel.setVisibility(View.GONE);
                    setStatus("خطای پیش‌نمایش: " + message(e), true);
                });
            } finally {
                runOnUiThread(() -> end());
            }
        });
    }

    private void download() {
        download(-1);
    }
    private void download(int forcedQuality) {
        String link = validatedLink();
        if (link == null || !begin()) return;
        final int chosen = forcedQuality < 0 ? quality.getSelectedItemPosition() : forcedQuality;
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
                        failure.contains("requested format not available") ||
                        failure.contains("requested formats") ||
                        failure.contains("no video formats found");
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
                    // Prefer the closest resolution when a provider does not
                    // report its format height (common in Instagram reels).
                    int desired = chosen == 1 ? 1080 : chosen == 2 ? 720 :
                        chosen == 3 ? 480 : 360;
                    fallback.addOption("-f", "b/bv*+ba");
                    fallback.addOption("-S", "res:" + desired + ",ext:mp4");
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
        videoButton.setEnabled(false);
        audioButton.setEnabled(false);
        return true;
    }

    private void end() {
        busy = false;
        startButton.setEnabled(true);
        checkButton.setEnabled(true);
        updateButton.setEnabled(true);
        videoButton.setEnabled(true);
        audioButton.setEnabled(true);
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
