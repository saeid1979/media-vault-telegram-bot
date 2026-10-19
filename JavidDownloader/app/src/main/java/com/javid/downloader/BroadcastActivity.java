package com.javid.downloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Explicit, owner-operated Telegram announcement tool. Sends individual messages
 * only to locally registered, active and unblocked users. It does not join
 * channels, scrape Telegram IDs or run unsolicited/scheduled campaigns.
 */
public final class BroadcastActivity extends Activity {
    private static final int NAVY = Color.rgb(20, 37, 66);
    private static final int BLUE = Color.rgb(62, 104, 233);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BotUserStore users;
    private Spinner audience;
    private EditText messageInput;
    private TextView status, botName, counter;
    private Button sendButton, stopButton;
    private volatile boolean cancelRequested = false;
    private volatile boolean busy = false;

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(15));
        return d;
    }
    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return v;
    }
    private void space(LinearLayout parent, int n) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(1, dp(n)));
    }
    private TextView label(String value, int size, boolean strong) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(NAVY);
        if (strong) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private Button button(String text, int background, int textColor) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(textColor);
        b.setBackground(rounded(background));
        return b;
    }
    private void show(String s) { ui.post(() -> status.setText(s)); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        users = new BotUserStore(this);
        LinearLayout root = column();
        root.setBackgroundColor(Color.rgb(247, 249, 255));
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
        root.addView(scroll);
        LinearLayout body = column();
        body.setPadding(dp(18), dp(22), dp(18), dp(30));
        scroll.addView(body);
        body.addView(label("📢 مرکز اطلاع‌رسانی بات", 24, true));
        space(body, 8);
        body.addView(label(
            "ارسال پیام خصوصی فقط به اعضای فعال که قبلاً بات را در تلگرام شروع کرده‌اند. " +
            "ارسال به اعضای صرفِ کانال ممکن نیست.", 13, false));
        space(body, 15);
        botName = label("بات: در حال بررسی هویت…", 14, true);
        body.addView(botName);
        space(body, 15);
        body.addView(label("انتخاب دریافت‌کنندگان", 16, true));
        audience = new Spinner(this);
        String[] choices = {"تمام اعضای فعال", "فقط VIP فعال", "فقط عادی فعال", "فقط مدیران فعال"};
        audience.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, choices));
        body.addView(audience, new LinearLayout.LayoutParams(-1, dp(55)));
        space(body, 10);
        counter = label("", 13, false);
        body.addView(counter);
        audience.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                 int position, long id) { updateCount(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        space(body, 15);
        body.addView(label("متن اطلاعیه", 16, true));
        messageInput = new EditText(this);
        messageInput.setHint("مثلاً: امکانات دانلود جدید بات فعال شد.");
        messageInput.setMinLines(4);
        messageInput.setMaxLines(8);
        messageInput.setGravity(Gravity.TOP);
        messageInput.setBackground(rounded(Color.WHITE));
        messageInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        body.addView(messageInput, new LinearLayout.LayoutParams(-1, dp(145)));
        space(body, 12);
        sendButton = button("مشاهده و تأیید ارسال", BLUE, Color.WHITE);
        body.addView(sendButton, new LinearLayout.LayoutParams(-1, dp(54)));
        sendButton.setOnClickListener(v -> confirm());
        space(body, 9);
        stopButton = button("توقف ارسال", Color.rgb(235, 238, 247), NAVY);
        stopButton.setEnabled(false);
        body.addView(stopButton, new LinearLayout.LayoutParams(-1, dp(50)));
        stopButton.setOnClickListener(v -> {
            cancelRequested = true;
            show("درخواست توقف ثبت شد؛ پس از پیام جاری متوقف می‌شود.");
        });
        space(body, 15);
        status = label("آماده. هیچ پیامی بدون تأیید شما ارسال نمی‌شود.", 13, false);
        status.setBackground(rounded(Color.WHITE));
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        body.addView(status);
        space(body, 13);
        TextView note = label(
            "نکته: کاربر باید قبلاً /start را برای همین بات فرستاده باشد و بات را مسدود نکرده باشد. " +
            "برنامه پس از ارسال، تعداد موفق و ناموفق را نمایش می‌دهد. " +
            "برای ارسال این صفحه را باز نگه دار. از ارسال پیام تکراری یا ناخواسته خودداری کن.",
            12, false);
        note.setTextColor(Color.rgb(114, 104, 78));
        body.addView(note);
        updateCount();
        verifyBot();
    }

    private List<BotUserStore.User> recipients(int filter) {
        List<BotUserStore.User> result = new ArrayList<>();
        for (BotUserStore.User user : users.all()) {
            if (!user.active || user.blocked) continue;
            String role = BotUserStore.effectiveRole(user);
            if (filter == 1 && !"vip".equals(role)) continue;
            if (filter == 2 && !"normal".equals(role)) continue;
            if (filter == 3 && !"admin".equals(role)) continue;
            result.add(user);
        }
        return result;
    }
    private void updateCount() {
        if (counter == null || audience == null) return;
        counter.setText("دریافت‌کنندگان واجد شرایط: " +
            recipients(audience.getSelectedItemPosition()).size() + " نفر");
    }
    private void verifyBot() {
        worker.execute(() -> {
            try {
                String tok = BotSecrets.read(this);
                if (tok == null || tok.isEmpty()) throw new Exception("Bot token not configured");
                JSONObject me = request("getMe", new JSONObject(), tok);
                String account = me.getJSONObject("result").optString("username", "نامشخص");
                ui.post(() -> botName.setText("بات متصل: @" + account));
            } catch (Exception ignored) {
                ui.post(() -> botName.setText("بات: توکن متصل نیست یا ارتباط برقرار نشد."));
            }
        });
    }
    private void confirm() {
        if (busy) return;
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty() || message.length() > 2000) {
            Toast.makeText(this, "متن اطلاعیه باید ۱ تا ۲۰۰۰ کاراکتر باشد.", Toast.LENGTH_LONG).show();
            return;
        }
        int filter = audience.getSelectedItemPosition();
        List<BotUserStore.User> selected = recipients(filter);
        if (selected.isEmpty()) {
            Toast.makeText(this, "کاربر فعال در این گروه وجود ندارد.", Toast.LENGTH_LONG).show();
            return;
        }
        if (selected.size() > 200) {
            Toast.makeText(this, "حداکثر ۲۰۰ دریافت‌کننده در یک نوبت مجاز است.", Toast.LENGTH_LONG).show();
            return;
        }
        String preview = "تعداد: " + selected.size() + " نفر" +
            "\nگروه: " + audience.getSelectedItem().toString() +
            "\n\nمتن پیام:\n" + message +
            "\n\nپیام‌ها پس از تأیید جداگانه ارسال می‌شوند.";
        new AlertDialog.Builder(this)
            .setTitle("تأیید اطلاعیه")
            .setMessage(preview)
            .setNegativeButton("لغو", null)
            .setPositiveButton("ارسال", (dialog, which) -> deliver(message, selected)).show();
    }
    private void deliver(String message, List<BotUserStore.User> selected) {
        if (busy) return;
        busy = true;
        cancelRequested = false;
        sendButton.setEnabled(false);
        stopButton.setEnabled(true);
        audience.setEnabled(false);
        messageInput.setEnabled(false);
        worker.execute(() -> {
            int ok = 0, fail = 0;
            BotStatsStore audit = new BotStatsStore(this);
            String error = "";
            String bot = "";
            try {
                String tok = BotSecrets.read(this);
                if (tok == null || tok.isEmpty()) throw new IllegalStateException("توکن بات ذخیره نشده است.");
                JSONObject me = request("getMe", new JSONObject(), tok);
                bot = me.getJSONObject("result").optString("username", "UnknownBot");
                try { audit.announcementStarted(); } catch (Exception ignored) { }
                show("در حال ارسال از @" + bot + " ...");
                for (int index = 0; index < selected.size() && !cancelRequested; index++) {
                    BotUserStore.User user = selected.get(index);
                    try {
                        request("sendMessage",
                            new JSONObject()
                                .put("chat_id", user.id)
                                .put("text", message)
                                .put("disable_notification", true), tok);
                        ok++;
                        try { audit.messageDelivered(); } catch (Exception ignored) { }
                    } catch (Exception e) {
                        fail++;
                        try { audit.messageFailed(); } catch (Exception ignored) { }
                        error = "برخی کاربران بات را شروع نکرده یا مسدود کرده‌اند.";
                    }
                    final int done = index + 1, succeeded = ok, failed = fail;
                    ui.post(() -> status.setText("ارسال: " + done + "/" + selected.size() +
                        "\nموفق: " + succeeded + " | ناموفق: " + failed));
                    if (!cancelRequested && index < selected.size() - 1)
                        try { Thread.sleep(1150); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt(); break;
                        }
                }
            } catch (Exception exception) {
                error = "ارسال برقرار نشد. هویت بات و اتصال اینترنت را بررسی کن.";
            }
            audit.close();
            final int succeeded = ok, failed = fail;
            final String summary = error, username = bot;
            ui.post(() -> {
                busy = false;
                sendButton.setEnabled(true);
                stopButton.setEnabled(false);
                audience.setEnabled(true);
                messageInput.setEnabled(true);
                status.setText((cancelRequested ? "ارسال متوقف شد." : "ارسال پایان یافت.") +
                    "\nبات: @" + username +
                    "\nموفق: " + succeeded + " | ناموفق: " + failed +
                    (summary.isEmpty() ? "" : "\n" + summary));
            });
        });
    }
    private JSONObject request(String method, JSONObject body, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(
            "https://api.telegram.org/bot" + token + "/" + method).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(25000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int statusCode = conn.getResponseCode();
            InputStream response = statusCode >= 200 && statusCode < 300
                ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder result = new StringBuilder();
            if (response != null) try (InputStream in = response) {
                byte[] buffer = new byte[2048]; int length, total = 0;
                while ((length = in.read(buffer)) != -1) {
                    total += length;
                    if (total > 65536) break;
                    result.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
                }
            }
            JSONObject parsed = new JSONObject(result.toString());
            if (!parsed.optBoolean("ok")) throw new Exception("Telegram response " + statusCode);
            return parsed;
        } finally { conn.disconnect(); }
    }
    @Override protected void onDestroy() {
        // Do not continue sending from a destroyed activity.
        cancelRequested = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
