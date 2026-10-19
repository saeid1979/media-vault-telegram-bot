package com.javid.downloader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Owner-visible local history of bot downloads. */
public final class DownloadLogActivity extends Activity {
    private static final int INK = Color.rgb(20, 42, 64);
    private static final int MUTED = Color.rgb(102, 122, 140);
    private LinearLayout results;
    private TextView totals;
    private Spinner filter;
    private BotDownloadLogStore logs;
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(16)); return d;
    }
    private void space(LinearLayout p, int n) {
        p.addView(new View(this), new LinearLayout.LayoutParams(1, dp(n)));
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        logs = new BotDownloadLogStore(this);
        LinearLayout root = column();
        root.setBackgroundColor(Color.rgb(245, 248, 252));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
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
        root.addView(scroll);
        LinearLayout body = column();
        body.setPadding(dp(18), dp(20), dp(18), dp(30));
        scroll.addView(body);
        body.addView(text("📋 گزارش دانلود کاربران", 23, INK, true));
        space(body, 7);
        body.addView(text("گزارش محلی ۳۰ روز اخیر، از نسخه 1.8.0 به بعد", 13, MUTED, false));
        space(body, 15);
        totals = text("", 16, INK, true);
        body.addView(totals);
        space(body, 12);
        filter = new Spinner(this);
        filter.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"همه", "موفق", "ناموفق"}));
        body.addView(filter);
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int i, long id) { showLogs(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        Button update = new Button(this);
        update.setAllCaps(false); update.setText("↻ به‌روزرسانی");
        body.addView(update);
        update.setOnClickListener(v -> showLogs());
        space(body, 13);
        results = column();
        body.addView(results);
        showLogs();
    }
    private String bytes(long value) {
        return value > 0 ? String.format(java.util.Locale.US, "%.1f MB", value / 1048576.0) : "—";
    }
    private void showLogs() {
        if (results == null) return;
        results.removeAllViews();
        if (getSharedPreferences(TelegramBotService.PREFS, MODE_PRIVATE).getLong("owner_id", 0) <= 0) {
            results.addView(text("ابتدا بات را با حساب مالک جفت کن.", 14, MUTED, true));
            return;
        }
        try {
            int selected = filter.getSelectedItemPosition();
            String where = selected == 1 ? "success" : selected == 2 ? "failed" : "";
            BotDownloadLogStore.Summary overview = logs.summary();
            totals.setText("درخواست‌ها: " + overview.total + " | موفق: " +
                overview.successful + " | ناموفق: " + overview.failed +
                "\nحجم ارسال‌شده: " + bytes(overview.deliveredBytes));
            List<BotDownloadLogStore.Entry> items = logs.recent(100, where);
            if (items.isEmpty()) {
                results.addView(text("هنوز در این بخش دانلودی ثبت نشده است.", 14, MUTED, false));
                return;
            }
            for (BotDownloadLogStore.Entry record : items) {
                LinearLayout card = column();
                card.setPadding(dp(15), dp(14), dp(15), dp(14));
                card.setBackground(rounded(Color.WHITE));
                results.addView(card);
                String name = record.userLabel.isEmpty() ?
                    "شناسه " + record.userId : record.userLabel;
                card.addView(text(("success".equals(record.status) ? "✅ " : "⚠️ ") +
                    name, 16, INK, true));
                card.addView(text("Telegram ID: " + record.userId +
                    "\nزمان: " + DateFormat.getDateTimeInstance().format(new Date(record.createdAt)) +
                    "\nاز: " + record.platform + " | فرمت: " + record.requestedFormat, 12, MUTED, false));
                space(card, 8);
                card.addView(text("محتوا: " + (record.title.isEmpty() ?
                    "عنوان دریافت نشد" : record.title), 14, INK, true));
                card.addView(text("نتیجه: " +
                    ("success".equals(record.status) ? "ارسال موفق" : "ناموفق") +
                    " | حجم: " + bytes(record.fileBytes) +
                    (record.issue.isEmpty() ? "" : "\nخطا: " + record.issue), 12, MUTED, false));
                if (!record.publicUrl.isEmpty()) {
                    Button link = new Button(this);
                    link.setText("🔗 مشاهده لینک عمومی");
                    link.setAllCaps(false); link.setTextSize(12);
                    card.addView(link);
                    link.setOnClickListener(v -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(record.publicUrl)));
                        } catch (Exception ex) {
                            Toast.makeText(this, "بازکردن لینک ممکن نیست", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                space(results, 10);
            }
            results.addView(text("حداکثر ۱۰۰ رکورد اخیر نمایش داده می‌شود. " +
                "تاریخچه حداکثر ۳۰ روز نگهداری می‌شود.", 12, MUTED, false));
        } catch (Exception e) {
            results.addView(text("گزارش قابل خواندن نیست؛ دوباره تلاش کن.", 13, MUTED, false));
        }
    }
    @Override protected void onResume() { super.onResume(); showLogs(); }
    @Override protected void onDestroy() { if (logs != null) logs.close(); super.onDestroy(); }
}
