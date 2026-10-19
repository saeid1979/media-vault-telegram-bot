package com.javid.downloader;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owner-facing offline analytics dashboard: aggregate counters only. */
public final class DashboardActivity extends Activity {
    private static final int INK = Color.rgb(22, 42, 68);
    private static final int MUTED = Color.rgb(110, 124, 146);
    private static final int TEAL = Color.rgb(24, 159, 134);
    private static final int BLUE = Color.rgb(57, 112, 226);
    private static final int RED = Color.rgb(222, 102, 111);
    private static final int PALE = Color.rgb(242, 246, 253);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout body;
    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }
    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return l;
    }
    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return l;
    }
    private void gap(LinearLayout box, int size) {
        box.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size)));
    }
    private TextView text(String t, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = vertical();
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(background(Color.WHITE, 18));
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }
    private static final class Snapshot {
        int users, active, vip, blocked, importedDownloadHistory, queued;
        long lastDownloadMs, lastUploadMs;
        BotStatsStore.Day totals;
        List<BotStatsStore.Day> sevenDays;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(INK);
        LinearLayout shell = vertical();
        shell.setBackgroundColor(PALE);
        setContentView(shell);
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                bottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        shell.addView(scroll);
        body = vertical();
        body.setPadding(dp(16), dp(20), dp(16), dp(40));
        scroll.addView(body);
    }
    @Override protected void onResume() { super.onResume(); reload(); }

    private void reload() {
        body.removeAllViews();
        body.addView(text("📊 داشبورد مدیریت Downloaderjavid", 22, INK, true));
        gap(body, 6);
        body.addView(text("آمار واقعی ذخیره‌شده روی همین گوشی؛ بدون ثبت لینک‌ها و پیام‌های کاربران.",
            12, MUTED, false));
        gap(body, 13);
        body.addView(text("در حال خواندن آمار…", 14, MUTED, false));
        worker.execute(() -> {
            try (BotUserStore userStore = new BotUserStore(this);
                 BotStatsStore stats = new BotStatsStore(this)) {
                Snapshot data = new Snapshot();
                List<BotUserStore.User> all = userStore.all();
                data.users = all.size();
                for (BotUserStore.User u : all) {
                    if (u.active && !u.blocked) data.active++;
                    if (u.blocked) data.blocked++;
                    if ("vip".equals(BotUserStore.effectiveRole(u)) && u.active && !u.blocked)
                        data.vip++;
                    // Prior imported totals are shown separately from v1.7.0 event counts.
                    data.importedDownloadHistory += Math.max(0, u.totalDownloads);
                }
                data.totals = stats.totals();
                data.sevenDays = stats.lastDays(7);
                SharedPreferences prefs = getSharedPreferences(TelegramBotService.PREFS,
                    Context.MODE_PRIVATE);
                data.queued = prefs.getInt("current_downloads", 0);
                data.lastDownloadMs = prefs.getLong("last_download_ms", 0);
                data.lastUploadMs = prefs.getLong("last_upload_ms", 0);
                main.post(() -> render(data));
            } catch (Exception e) {
                main.post(() -> {
                    body.removeAllViews();
                    body.addView(text("خواندن آمار موفق نبود؛ برنامه را دوباره باز کن.",
                        16, RED, true));
                });
            }
        });
    }

    private void metric(LinearLayout parent, String title, String value, String subtitle, int color) {
        LinearLayout container = card(parent);
        container.addView(text(title, 13, MUTED, false));
        container.addView(text(value, 29, color, true));
        if (subtitle != null && !subtitle.isEmpty())
            container.addView(text(subtitle, 11, MUTED, false));
    }

    private void render(Snapshot data) {
        if (isFinishing() || isDestroyed()) return;
        body.removeAllViews();
        body.addView(text("📊 داشبورد مدیریت Downloaderjavid", 22, INK, true));
        gap(body, 8);
        body.addView(text("اطلاعات محلی گوشی | نسخه 1.7.0", 12, MUTED, false));
        gap(body, 14);
        Button refresh = new Button(this);
        refresh.setText("↻ به‌روزرسانی آمار");
        refresh.setAllCaps(false);
        refresh.setTextColor(Color.WHITE);
        refresh.setBackground(background(INK, 12));
        refresh.setOnClickListener(v -> reload());
        body.addView(refresh, new LinearLayout.LayoutParams(-1, dp(48)));
        gap(body, 14);

        metric(body, "👥 کل کاربران ثبت‌شده", String.valueOf(data.users),
            "فعال " + data.active + " | مسدود " + data.blocked, BLUE);
        gap(body, 8);
        metric(body, "⭐ VIP فعال و معتبر", String.valueOf(data.vip),
            "VIP تاریخ‌گذشته در این عدد حساب نمی‌شود.", TEAL);
        gap(body, 8);
        metric(body, "📦 مجموع دانلودهای ثبت‌شده در پروفایل‌ها",
            String.valueOf(data.importedDownloadHistory),
            "این عدد شامل سابقه انتقالی کاربران هم می‌شود.", INK);
        gap(body, 8);
        metric(body, "⚙️ دانلود در حال انجام یا انتظار", String.valueOf(data.queued),
            "آمار زنده فقط وقتی سرویس بات فعال است.", BLUE);

        gap(body, 19);
        body.addView(text("عملکرد بات از زمان نصب نسخه 1.7.0", 19, INK, true));
        gap(body, 9);
        metric(body, "✅ دانلود موفق", String.valueOf(data.totals.downloadOk),
            "بعد از ارسال موفق فایل به تلگرام", TEAL);
        gap(body, 8);
        metric(body, "⚠️ دانلود ناموفق", String.valueOf(data.totals.downloadFailed),
            "تلاش‌های ناموفق ثبت‌شده", RED);
        gap(body, 8);
        metric(body, "📢 اطلاعیه‌های ارسال‌شده", String.valueOf(data.totals.messageOk),
            "ناموفق " + data.totals.messageFailed +
            " | نوبت ارسال " + data.totals.announcementBatches, BLUE);

        gap(body, 18);
        body.addView(text("📈 نمودار فعالیت ۷ روز اخیر", 19, INK, true));
        gap(body, 6);
        body.addView(text("سبز/آبی: موفق | قرمز: ناموفق", 12, MUTED, false));
        gap(body, 11);
        LinearLayout chart = card(body);
        int maxDownloads = 1, maxMessages = 1;
        for (BotStatsStore.Day d : data.sevenDays) {
            maxDownloads = Math.max(maxDownloads, d.totalDownloads());
            maxMessages = Math.max(maxMessages, d.totalMessages());
        }
        for (BotStatsStore.Day d : data.sevenDays) {
            String shortDate = d.date.length() >= 10 ? d.date.substring(5) : d.date;
            chart.addView(text("📅 " + shortDate, 13, INK, true));
            gap(chart, 3);
            drawBar(chart, "دانلود", d.downloadOk, d.downloadFailed, maxDownloads, TEAL);
            gap(chart, 4);
            drawBar(chart, "اطلاعیه", d.messageOk, d.messageFailed, maxMessages, BLUE);
            gap(chart, 12);
        }
        gap(body, 13);
        LinearLayout performance = card(body);
        performance.addView(text("⏱️ زمان آخرین دانلود و ارسال موفق", 16, INK, true));
        gap(performance, 8);
        performance.addView(text(
            "دانلود: " + (data.lastDownloadMs > 0 ? data.lastDownloadMs / 1000 + " ثانیه" : "هنوز ثبت نشده") +
            "\nارسال تلگرام: " + (data.lastUploadMs > 0 ? data.lastUploadMs / 1000 + " ثانیه" : "هنوز ثبت نشده"),
            13, MUTED, false));
        gap(body, 16);
        body.addView(text(
            "🔒 آمار این صفحه فقط روی گوشی ذخیره می‌شود. تعداد دانلودهای نسخه‌های قبلی از " +
            "فهرست کاربران نمایش داده می‌شود؛ آمار موفق/ناموفق و نمودار جدید از نسخه 1.7.0 آغاز می‌شود.",
            12, MUTED, false));
    }

    private void drawBar(LinearLayout parent, String title, int ok, int fail,
                         int max, int successColor) {
        LinearLayout heading = horizontal();
        TextView name = text(title + "  " + ok + " موفق / " + fail + " ناموفق", 11, MUTED, false);
        heading.addView(name);
        parent.addView(heading);
        gap(parent, 4);
        LinearLayout bar = horizontal();
        bar.setBackground(background(Color.rgb(235, 239, 247), 6));
        int remainder = Math.max(0, max - ok - fail);
        if (ok > 0) addSegment(bar, successColor, ok);
        if (fail > 0) addSegment(bar, RED, fail);
        if (remainder > 0) addSegment(bar, Color.TRANSPARENT, remainder);
        parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(10)));
    }
    private void addSegment(LinearLayout bar, int color, int weight) {
        View segment = new View(this);
        segment.setBackgroundColor(color);
        bar.addView(segment, new LinearLayout.LayoutParams(0, dp(10), weight));
    }
    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
