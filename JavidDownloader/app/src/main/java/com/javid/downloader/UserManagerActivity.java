package com.javid.downloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Manual migration screen for users from the older standalone Android app.
 * No attempt to access the other app's sandbox or to guess its encryption key.
 */
public final class UserManagerActivity extends Activity {
    private static final int IMPORT = 601;
    private static final int EXPORT = 602;
    private static final int NAVY = Color.rgb(20, 37, 66);
    private static final int BLUE = Color.rgb(62, 104, 233);
    private final int SPACE = 14;
    private BotUserStore store;
    private LinearLayout listContainer;
    private TextView summary;

    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }
    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }
    private LinearLayout vertical() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return v;
    }
    private TextView label(String text, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextColor(NAVY); t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private Button button(String text, int color, int fg) {
        Button b = new Button(this);
        b.setText(text); b.setTextSize(13); b.setAllCaps(false);
        b.setTextColor(fg); b.setBackground(bg(color, 13));
        b.setMinHeight(dp(48));
        return b;
    }
    private void gap(LinearLayout box, int height) {
        View v = new View(this);
        box.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
    }
    private void notifyUser(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new BotUserStore(this);
        LinearLayout root = vertical();
        root.setBackgroundColor(Color.rgb(249, 250, 255));
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
        root.addView(scroll);
        LinearLayout body = vertical();
        body.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(body);
        body.addView(label("انتقال و مدیریت کاربران", 23, true));
        gap(body, 9);
        body.addView(label("کاربران بات قدیمی را با شناسه عددی تلگرام ثبت کن. تا وقتی تأیید نشوند دسترسی دانلود ندارند.", 13, false));
        gap(body, 14);
        LinearLayout box = vertical();
        box.setBackground(bg(Color.WHITE, 18));
        box.setPadding(dp(14), dp(16), dp(14), dp(16));
        summary = label("", 16, true);
        box.addView(summary);
        gap(box, 10);
        Button add = button("＋  افزودن کاربر با Telegram ID", BLUE, Color.WHITE);
        box.addView(add);
        add.setOnClickListener(v -> showEditor(null));
        gap(box, 9);
        Button importFile = button("⬆  ورود فایل کاربران JSON", Color.rgb(231, 236, 252), NAVY);
        box.addView(importFile);
        importFile.setOnClickListener(v -> pickImport());
        gap(box, 9);
        Button exportFile = button("⬇  پشتیبان‌گیری از کاربران", Color.rgb(233, 246, 241), NAVY);
        box.addView(exportFile);
        exportFile.setOnClickListener(v -> pickExport());
        gap(box, 9);
        Button dashboard = button("📊 داشبورد فعالیت بات", Color.rgb(234, 237, 252), NAVY);
        box.addView(dashboard);
        dashboard.setOnClickListener(v ->
            startActivity(new Intent(this, DashboardActivity.class)));
        gap(box, 9);
        Button broadcast = button("📢 ارسال اطلاعیه به اعضای فعال بات", Color.rgb(230, 238, 255), NAVY);
        box.addView(broadcast);
        broadcast.setOnClickListener(v ->
            startActivity(new Intent(this, BroadcastActivity.class)));
        gap(box, 13);
        TextView note = label(
            "انتقال خودکار از APK قدیمی ممکن نیست؛ برنامه‌ها به پایگاه داده خصوصی یکدیگر دسترسی ندارند. " +
            "فقط فایل JSON با قالب javid-users-v1 پذیرفته می‌شود، نه فایل رمزگذاری‌شده mdbak. " +
            "داده‌ها روی گوشی می‌مانند؛ خروجی JSON بدون رمز است، آن را خصوصی نگه دار.",
            12, false);
        note.setTextColor(Color.rgb(121, 98, 53));
        box.addView(note);
        body.addView(box);
        gap(body, 19);
        body.addView(label("فهرست اعضا", 19, true));
        gap(body, 12);
        listContainer = vertical();
        body.addView(listContainer);
        refresh();
    }

    private void refresh() {
        List<BotUserStore.User> users = store.all();
        int vip = 0, active = 0;
        for (BotUserStore.User u : users) {
            if ("vip".equals(BotUserStore.effectiveRole(u))) vip++;
            if (u.active && !u.blocked) active++;
        }
        summary.setText("کاربران ثبت‌شده: " + users.size() + "  |  VIP: " + vip +
            "\nکاربران فعال: " + active + "  |  شمارش VIP با درنظرگرفتن انقضا");
        listContainer.removeAllViews();
        if (users.isEmpty()) {
            listContainer.addView(label("هنوز کاربری وارد نشده. برای شروع، Telegram ID اولین عضو را ثبت کن.", 13, false));
            return;
        }
        for (BotUserStore.User u : users) {
            LinearLayout card = vertical();
            card.setPadding(dp(14), dp(14), dp(14), dp(14));
            card.setBackground(bg(Color.WHITE, 16));
            TextView head = label((u.username.isEmpty() ? "کاربر " + u.id : u.username) +
                "  ·  " + BotUserStore.effectiveRole(u).toUpperCase(Locale.ROOT) +
                (BotUserStore.vipExpired(u) ? " (VIP منقضی)" : ""), 15, true);
            card.addView(head);
            gap(card, 6);
            TextView info = label("Telegram ID: " + u.id +
                "\n" + (u.blocked ? "مسدود" : u.active ? "فعال" : "در انتظار تأیید") +
                " · سهمیه روزانه: " + (u.dailyLimit == null ? "پیش‌فرض " : "") +
                BotUserStore.quota(u) +
                "\nدانلودهای ثبت‌شده: " + u.totalDownloads +
                ("vip".equals(u.role) ? "\nاعتبار VIP: " +
                    (u.vipExpiresOn == null || u.vipExpiresOn.isEmpty() ?
                     "بدون انقضا" : u.vipExpiresOn) : ""), 12, false);
            card.addView(info);
            gap(card, 9);
            Button edit = button("ویرایش نقش، سهمیه و دسترسی", Color.rgb(234, 240, 252), NAVY);
            card.addView(edit);
            edit.setOnClickListener(v -> showEditor(u));
            listContainer.addView(card);
            gap(listContainer, 10);
        }
    }

    private EditText field(LinearLayout parent, String hint, String value, boolean number) {
        EditText e = new EditText(this);
        e.setTextSize(14);
        e.setText(value == null ? "" : value);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        parent.addView(e, new LinearLayout.LayoutParams(-1, dp(49)));
        return e;
    }

    private void showEditor(BotUserStore.User original) {
        LinearLayout form = vertical();
        form.setPadding(dp(18), dp(8), dp(18), dp(3));
        EditText id = field(form, "Telegram ID (عدد)", original == null ? "" :
            String.valueOf(original.id), true);
        id.setEnabled(original == null);
        EditText username = field(form, "نام یا Username", original == null ? "" : original.username, false);
        TextView roleLabel = label("نقش کاربر", 13, true);
        form.addView(roleLabel);
        Spinner roles = new Spinner(this);
        String[] options = {"عادی", "VIP", "مدیر (سهمیه نامحدود)"};
        roles.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options));
        roles.setSelection(original == null ? 0 : "vip".equals(original.role) ? 1 :
            "admin".equals(original.role) ? 2 : 0);
        form.addView(roles);
        EditText expiry = field(form, "تاریخ انقضای VIP: YYYY-MM-DD (خالی = دائمی)",
            original == null ? "" : original.vipExpiresOn, false);
        Button chooseDate = button("📅 انتخاب تاریخ پایان VIP", Color.rgb(229, 237, 252), NAVY);
        form.addView(chooseDate);
        chooseDate.setOnClickListener(v -> {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            new android.app.DatePickerDialog(this, (view, y, month, day) ->
                expiry.setText(String.format(java.util.Locale.US, "%04d-%02d-%02d",
                    y, month + 1, day)),
                cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
        });
        EditText limit = field(form, "سهمیه روزانه (خالی = پیش‌فرض نقش)",
            original == null || original.dailyLimit == null ? "" :
                String.valueOf(original.dailyLimit), true);
        EditText history = field(form, "تعداد دانلود گذشته (اختیاری)",
            original == null ? "0" : String.valueOf(original.totalDownloads), true);
        CheckBox active = new CheckBox(this);
        active.setText("کاربر تأییدشده و فعال");
        active.setChecked(original != null && original.active);
        form.addView(active);
        CheckBox blocked = new CheckBox(this);
        blocked.setText("کاربر مسدود باشد");
        blocked.setChecked(original != null && original.blocked);
        form.addView(blocked);
        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form);
        new AlertDialog.Builder(this).setTitle(original == null ? "افزودن کاربر" : "ویرایش کاربر")
            .setView(formScroll).setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره", (dialog, which) -> {
                try {
                    BotUserStore.User user = original == null ? new BotUserStore.User() : original;
                    long number = Long.parseLong(id.getText().toString().trim());
                    if (number <= 0) throw new IllegalArgumentException();
                    user.id = number;
                    user.username = username.getText().toString().trim();
                    user.role = roles.getSelectedItemPosition() == 1 ? "vip" :
                        roles.getSelectedItemPosition() == 2 ? "admin" : "normal";
                    user.vipExpiresOn = expiry.getText().toString().trim();
                    if (!BotUserStore.validExpiry(user.vipExpiresOn))
                        throw new IllegalArgumentException("تاریخ نامعتبر است");
                    String q = limit.getText().toString().trim();
                    user.dailyLimit = q.isEmpty() ? null : Integer.parseInt(q);
                    String c = history.getText().toString().trim();
                    user.totalDownloads = c.isEmpty() ? 0 : Integer.parseInt(c);
                    if (user.dailyLimit != null && (user.dailyLimit < 0 || user.dailyLimit > 1000000))
                        throw new IllegalArgumentException();
                    if (user.totalDownloads < 0) throw new IllegalArgumentException();
                    user.active = active.isChecked();
                    user.blocked = blocked.isChecked();
                    store.save(user);
                    refresh();
                    notifyUser("اطلاعات کاربر ذخیره شد");
                } catch (Exception e) { notifyUser("شناسه، تاریخ VIP، سهمیه یا تعداد دانلود معتبر نیست"); }
            }).show();
    }

    private void pickImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        startActivityForResult(intent, IMPORT);
    }
    private void pickExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "Javid-users-backup.json");
        startActivityForResult(intent, EXPORT);
    }
    private byte[] read(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new Exception("No stream");
            byte[] buffer = new byte[8192]; int n, total = 0;
            while ((n = input.read(buffer)) != -1) {
                total += n;
                if (total > 2_000_000) throw new Exception("File too large");
                output.write(buffer, 0, n);
            }
            return output.toByteArray();
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == IMPORT) {
            try {
                byte[] bytes = read(uri);
                JSONObject obj = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                if (!"javid-users-v1".equals(obj.optString("format"))) {
                    notifyUser("فایل پشتیبان برنامه قدیمی مستقیماً پشتیبانی نمی‌شود. از فرم افزودن کاربر استفاده کن.");
                    return;
                }
                JSONArray users = obj.getJSONArray("users");
                int vip = 0;
                for (int i = 0; i < users.length(); i++)
                    if ("vip".equals(users.getJSONObject(i).optString("role"))) vip++;
                String prompt = "انتقال " + users.length() + " کاربر، شامل " + vip +
                    " عضو VIP؟\nداده‌های کاربران دارای شناسه یکسان جایگزین می‌شوند. " +
                    "ابتدا از اطلاعات موجود پشتیبان بگیر.";
                new AlertDialog.Builder(this).setTitle("تأیید انتقال").setMessage(prompt)
                    .setNegativeButton("لغو", null)
                    .setPositiveButton("انتقال", (dialog, which) -> {
                        try {
                            int count = store.importJson(bytes);
                            refresh();
                            notifyUser(count + " کاربر وارد شد. نقش‌ها و محدودیت‌ها را بررسی کن.");
                        } catch (Exception e) {
                            notifyUser("ورود داده ناموفق بود؛ فایل و شناسه‌ها را بررسی کن.");
                        }
                    }).show();
            } catch (Exception e) { notifyUser("این فایل JSON معتبر نیست یا قابل خواندن نیست."); }
        } else if (req == EXPORT) {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new Exception("No file");
                out.write(store.exportJson());
                out.flush();
                notifyUser("فهرست کاربران پشتیبان‌گیری شد. فایل رمزنگاری نشده است.");
            } catch (Exception e) { notifyUser("ذخیره پشتیبان ناموفق بود."); }
        }
    }
}
