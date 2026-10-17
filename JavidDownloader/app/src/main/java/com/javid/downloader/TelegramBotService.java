package com.javid.downloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.ffmpeg.FFmpeg;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Device-hosted companion mode for the user's EXISTING Telegram bot identity.
 *
 * This is not the original Python server application: it is a small on-device
 * Telegram Bot API polling/download engine with owner-only pairing. It must not
 * run alongside the existing Python poller or webhook receiver for the same bot.
 * Notifications and a foreground service make execution visible to the owner.
 */
public final class TelegramBotService extends Service {
    public static final String ACTION_START = "com.javid.downloader.BOT_START";
    public static final String ACTION_STOP = "com.javid.downloader.BOT_STOP";
    public static final String PREFS = "javid_telegram_bot";
    public static final String KEY_STATUS = "status";
    private static final String CHANNEL = "javid_telegram_bot_foreground";
    private static final int NOTIFICATION_ID = 32780;
    private static final long MAX_UPLOAD = 45L * 1024 * 1024;
    private static final Pattern LINK = Pattern.compile("https://\\S+", Pattern.CASE_INSENSITIVE);
    private final ExecutorService pollExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, PendingDownload> pending = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private volatile HttpURLConnection pollConnection;

    private static final class PendingDownload {
        final long owner;
        final String url;
        final long expires;
        PendingDownload(long owner, String url) {
            this.owner = owner;
            this.url = url;
            this.expires = System.currentTimeMillis() + 15L * 60 * 1000;
        }
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void status(String message) {
        preferences().edit().putString(KEY_STATUS, message).apply();
        if (running) {
            NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, notification(message));
        }
    }

    private Notification notification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent click = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, TelegramBotService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Javid Downloader · ربات تلگرام")
            .setContentText(message)
            .setContentIntent(click)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "توقف ربات", stopAction)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel chan = new NotificationChannel(CHANNEL, "ربات تلگرام جاوید",
                NotificationManager.IMPORTANCE_LOW);
            chan.setDescription("ربات در حال فعالیت روی همین گوشی است");
            manager.createNotificationChannel(chan);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopBot();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        running = true;
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification("در حال اتصال به تلگرام..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification("در حال اتصال به تلگرام..."));
        }
        preferences().edit().putBoolean("enabled", true).apply();
        status("در حال اتصال به بات تلگرام...");
        pollExecutor.execute(this::loop);
        return START_NOT_STICKY;
    }

    private void stopBot() {
        running = false;
        HttpURLConnection c = pollConnection;
        if (c != null) c.disconnect();
        preferences().edit().putBoolean("enabled", false).apply();
        status("ربات متوقف است");
        downloadExecutor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        HttpURLConnection c = pollConnection;
        if (c != null) c.disconnect();
        pollExecutor.shutdownNow();
        downloadExecutor.shutdownNow();
        preferences().edit().putBoolean("enabled", false).apply();
        super.onDestroy();
    }

    @Override public void onTimeout(int startId, int fgsType) {
        status("محدودیت زمانی اجرای پس‌زمینه اندروید؛ ربات متوقف شد");
        stopBot();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private String token() throws Exception {
        String result = BotSecrets.read(this);
        if (result.isEmpty()) throw new IllegalStateException("توکن ذخیره نشده");
        return result;
    }

    private void loop() {
        long cursor = preferences().getLong("update_id", 0L);
        try {
            String apiToken = token();
            JSONObject me = call("getMe", new JSONObject(), 10000, apiToken);
            String username = me.getJSONObject("result").optString("username", "UnknownBot");
            preferences().edit().putString("bot_username", username).apply();
            status("متصل به @" + username + " · در انتظار پیام‌ها");
            ensurePairCode();
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    JSONObject params = new JSONObject();
                    params.put("timeout", 20);
                    params.put("offset", cursor);
                    params.put("limit", 20);
                    params.put("allowed_updates", new JSONArray().put("message").put("callback_query"));
                    JSONObject response = call("getUpdates", params, 35000, apiToken);
                    JSONArray updates = response.getJSONArray("result");
                    for (int i = 0; i < updates.length() && running; i++) {
                        JSONObject update = updates.getJSONObject(i);
                        long id = update.getLong("update_id");
                        cursor = Math.max(cursor, id + 1);
                        preferences().edit().putLong("update_id", cursor).apply();
                        try { handle(update, apiToken); }
                        catch (Exception ignored) { status("خطا در پردازش پیام، ربات در حال تلاش مجدد است"); }
                    }
                    status("متصل به @" + username + " · آماده دریافت لینک");
                } catch (Exception failure) {
                    if (!running) break;
                    String desc = failure.getMessage() == null ? "" : failure.getMessage();
                    if (desc.contains("409") || desc.toLowerCase().contains("conflict")) {
                        status("تداخل: بات روی سرور یا گوشی دیگری فعال است. اجرای قبلی را متوقف کن.");
                    } else {
                        status("اتصال تلگرام قطع شد؛ تلاش مجدد...");
                    }
                    Thread.sleep(4000);
                }
            }
        } catch (Exception ignored) {
            status("اتصال برقرار نشد؛ توکن بات و اینترنت را بررسی کن");
        } finally {
            running = false;
            preferences().edit().putBoolean("enabled", false).apply();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private static String allowedHost(String url) {
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
            String h = uri.getHost();
            if (h == null) return null;
            h = h.toLowerCase(java.util.Locale.ROOT);
            String[][] websites = {
                {"youtube.com", "YouTube"}, {"youtu.be", "YouTube"},
                {"instagram.com", "Instagram"}, {"facebook.com", "Facebook"},
                {"fb.watch", "Facebook"}, {"tiktok.com", "TikTok"},
                {"x.com", "X"}, {"twitter.com", "X"},
                {"pinterest.com", "Pinterest"}, {"pin.it", "Pinterest"},
                {"vimeo.com", "Vimeo"}, {"reddit.com", "Reddit"},
                {"redd.it", "Reddit"}, {"dailymotion.com", "Dailymotion"},
                {"dai.ly", "Dailymotion"}, {"twitch.tv", "Twitch"}
            };
            for (String[] record : websites) {
                if (h.equals(record[0]) || h.endsWith("." + record[0])) return record[1];
            }
            return null;
        } catch (Exception e) { return null; }
    }

    private void ensurePairCode() {
        if (preferences().getLong("owner_id", 0) != 0) return;
        if (System.currentTimeMillis() < preferences().getLong("pair_expires", 0L)
            && !preferences().getString("pair_code", "").isEmpty()) return;
        String code = String.format(java.util.Locale.US, "%06d", new SecureRandom().nextInt(1000000));
        preferences().edit().putString("pair_code", code)
            .putLong("pair_expires", System.currentTimeMillis() + 10L * 60 * 1000).putInt("pair_attempts", 0)
            .apply();
    }

    private void handle(JSONObject update, String tok) throws Exception {
        JSONObject callback = update.optJSONObject("callback_query");
        if (callback != null) {
            String cbId = callback.optString("id");
            api("answerCallbackQuery", new JSONObject().put("callback_query_id", cbId), tok);
            JSONObject from = callback.optJSONObject("from");
            JSONObject message = callback.optJSONObject("message");
            if (from == null || message == null) return;
            long who = from.optLong("id");
            long chat = message.getJSONObject("chat").optLong("id");
            if (who != preferences().getLong("owner_id", 0)) return;
            String data = callback.optString("data", "");
            String[] parts = data.split(":");
            if (parts.length < 2) return;
            PendingDownload item = pending.get(parts[1]);
            if (item == null || item.owner != who || System.currentTimeMillis() > item.expires) {
                sendText(chat, "درخواست منقضی شده، لینک را دوباره بفرست.", tok);
                return;
            }
            if (data.startsWith("rights:")) {
                JSONObject buttons = keyboard(
                    new JSONArray().put(new JSONObject().put("text", "ویدیو (تا 720p)")
                        .put("callback_data", "fmt:" + parts[1] + ":v")),
                    new JSONArray().put(new JSONObject().put("text", "فقط صدا MP3")
                        .put("callback_data", "fmt:" + parts[1] + ":a"))
                );
                sendText(chat, "کدام فرمت را می‌خواهی؟", buttons, tok);
            } else if (data.startsWith("fmt:") && parts.length == 3) {
                pending.remove(parts[1]);
                boolean audio = "a".equals(parts[2]);
                sendText(chat, "درخواست " + (audio ? "MP3" : "ویدیو") +
                    " دریافت شد؛ دانلود در گوشی شروع می‌شود.", tok);
                downloadExecutor.execute(() -> downloadAndSend(chat, item.url, audio, tok));
            } else if (data.startsWith("cancel:")) {
                pending.remove(parts[1]);
                sendText(chat, "لغو شد.", tok);
            }
            return;
        }

        JSONObject message = update.optJSONObject("message");
        if (message == null) return;
        JSONObject from = message.optJSONObject("from");
        JSONObject chatObj = message.optJSONObject("chat");
        if (from == null || chatObj == null || !"private".equals(chatObj.optString("type"))) return;
        long user = from.optLong("id");
        long chat = chatObj.optLong("id");
        String text = message.optString("text", "").trim();
        long owner = preferences().getLong("owner_id", 0);
        if (owner == 0) {
            ensurePairCode();
            if (text.startsWith("/pair ")) {
                String entered = text.substring(6).trim();
                int tries = preferences().getInt("pair_attempts", 0) + 1;
                preferences().edit().putInt("pair_attempts", tries).apply();
                if (tries <= 5 && System.currentTimeMillis() < preferences().getLong("pair_expires", 0)
                    && entered.equals(preferences().getString("pair_code", ""))) {
                    preferences().edit().putLong("owner_id", user)
                        .remove("pair_code").remove("pair_expires").putInt("pair_attempts", 0).apply();
                    sendText(chat, "✅ گوشی شما به بات متصل شد. اکنون یک لینک مجاز بفرست.", tok);
                    status("مالک بات متصل شد · آماده دریافت لینک");
                } else {
                    if (tries >= 5) preferences().edit().remove("pair_code").remove("pair_expires").apply();
                    sendText(chat, "کد نامعتبر یا منقضی شده است. از داخل برنامه کد جدید بگیر.", tok);
                }
            } else if (text.startsWith("/start")) {
                sendText(chat, "سلام! این بات اکنون روی یک گوشی اندرویدی اجرا می‌شود. مالک باید با دستور /pair کد اتصال را وارد کند.", tok);
            }
            return;
        }
        if (owner != user) {
            if (text.startsWith("/start")) sendText(chat, "این نسخه آزمایشی فعلاً فقط برای مالک دستگاه فعال است.", tok);
            return;
        }
        if (text.startsWith("/start") || text.startsWith("/help")) {
            sendText(chat, "سلام 👋\nلینک ویدیوی عمومی و مجاز را بفرست، حق دانلود را تأیید کن و ویدیو یا MP3 را انتخاب کن.\n"
                    + "/status: وضعیت بات\nاین نسخه موبایلی فعلاً مخصوص مالک است.", tok);
            return;
        }
        if (text.startsWith("/status")) {
            sendText(chat, "✅ بات روی گوشی Javid Downloader فعال است.", tok);
            return;
        }
        Matcher matcher = LINK.matcher(text);
        String url = matcher.find() ? matcher.group().replaceAll("[),;]+$", "") : "";
        String source = allowedHost(url);
        if (source == null) {
            sendText(chat, "لینک HTTPS از یکی از شبکه‌های پشتیبانی‌شده بفرست.", tok);
            return;
        }
        String id = UUID.randomUUID().toString().substring(0, 12);
        pending.put(id, new PendingDownload(user, url));
        JSONObject rights = keyboard(
            new JSONArray().put(new JSONObject().put("text", "✅ اجازه دانلود دارم")
                .put("callback_data", "rights:" + id)),
            new JSONArray().put(new JSONObject().put("text", "لغو")
                .put("callback_data", "cancel:" + id))
        );
        sendText(chat, "منبع: " + source +
            "\nفقط در صورت مالکیت یا داشتن اجازه دانلود ادامه بده.", rights, tok);
    }

    private static JSONObject keyboard(JSONArray... rows) throws Exception {
        JSONArray all = new JSONArray();
        for (JSONArray row : rows) all.put(row);
        return new JSONObject().put("inline_keyboard", all);
    }

    private boolean onWifi() {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : cm.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private void downloadAndSend(long chat, String link, boolean audio, String tok) {
        File folder = new File(getExternalFilesDir(null), "bot-work/" + UUID.randomUUID());
        try {
            if (preferences().getBoolean("wifi_only", true) && !onWifi()) {
                sendText(chat, "برای جلوگیری از مصرف اینترنت سیم‌کارت، دانلود بات فقط روی Wi-Fi فعال است. می‌توانی این تنظیم را در اپ تغییر دهی.", tok);
                return;
            }
            if (!folder.mkdirs()) throw new Exception("Cannot create temporary folder");
            YoutubeDL.getInstance().init(getApplicationContext());
            FFmpeg.getInstance().init(getApplicationContext());
            YoutubeDLRequest request = new YoutubeDLRequest(link);
            request.addOption("--no-playlist");
            request.addOption("--no-mtime");
            request.addOption("--max-filesize", "45M");
            request.addOption("-o", new File(folder, "%(title).110B [%(id)s].%(ext)s").getAbsolutePath());
            if (audio) {
                request.addOption("-x");
                request.addOption("--audio-format", "mp3");
            } else {
                request.addOption("-f", "bv*[height<=720]+ba/b[height<=720]/best");
                request.addOption("--merge-output-format", "mp4");
            }
            YoutubeDL.getInstance().execute(request, "telegram-" + UUID.randomUUID(), null);
            File[] files = folder.listFiles((dir, name) -> {
                String n = name.toLowerCase(java.util.Locale.ROOT);
                return !n.endsWith(".part") && !n.endsWith(".ytdl") && !n.endsWith(".json")
                    && !n.endsWith(".webp") && !n.endsWith(".description");
            });
            File best = null;
            if (files != null) for (File candidate : files) {
                if (candidate.isFile() && (best == null || candidate.length() > best.length()))
                    best = candidate;
            }
            if (best == null || best.length() == 0) throw new Exception("No output media");
            if (best.length() > MAX_UPLOAD) {
                sendText(chat, "فایل بزرگ‌تر از سقف ۴۵ مگابایت ارسال بات است. کیفیت پایین‌تر را انتخاب کن یا از دانلود داخل اپ استفاده کن.", tok);
                return;
            }
            sendText(chat, "دانلود تمام شد. فایل در حال ارسال به تلگرام است...", tok);
            uploadDocument(chat, best, tok);
            preferences().edit().putInt("delivered", preferences().getInt("delivered", 0) + 1).apply();
        } catch (Exception e) {
            try { sendText(chat, "این لینک قابل دریافت یا ارسال نبود. عمومی بودن ویدیو، حجم فایل و اینترنت گوشی را بررسی کن.", tok); }
            catch (Exception ignored) { }
        } finally {
            deleteRecursive(folder);
        }
    }

    private void uploadDocument(long chat, File file, String tok) throws Exception {
        String boundary = "Javid" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection conn = (HttpURLConnection)new URL("https://api.telegram.org/bot" + tok + "/sendDocument").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(90000);
        conn.setDoOutput(true);
        conn.setChunkedStreamingMode(64 * 1024);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream output = new BufferedOutputStream(conn.getOutputStream())) {
            String first = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n"
                + chat + "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"document\"; filename=\""
                + file.getName().replace("\"", "_") + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
            output.write(first.getBytes(StandardCharsets.UTF_8));
            try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[64 * 1024];
                int count;
                while ((count = input.read(buf)) != -1) output.write(buf, 0, count);
            }
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        try (InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream()) {
            byte[] bytes = new byte[65536];
            int size = stream == null ? 0 : stream.read(bytes);
            if (size <= 0 || !new JSONObject(new String(bytes, 0, size, StandardCharsets.UTF_8)).optBoolean("ok"))
                throw new Exception("Telegram upload failed: " + code);
        } finally { conn.disconnect(); }
    }

    private void sendText(long chat, String text, String tok) throws Exception {
        sendText(chat, text, null, tok);
    }
    private void sendText(long chat, String text, JSONObject markup, String tok) throws Exception {
        JSONObject params = new JSONObject().put("chat_id", chat).put("text", text);
        if (markup != null) params.put("reply_markup", markup);
        api("sendMessage", params, tok);
    }
    private JSONObject api(String method, JSONObject params, String tok) throws Exception {
        return call(method, params, 30000, tok);
    }
    private JSONObject call(String method, JSONObject params, int timeout, String tok) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL("https://api.telegram.org/bot" + tok + "/" + method).openConnection();
        if ("getUpdates".equals(method)) pollConnection = conn;
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(timeout);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try {
            try (OutputStream output = conn.getOutputStream()) {
                output.write(params.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder body = new StringBuilder();
            if (stream != null) try (InputStream input = stream) {
                byte[] b = new byte[4096]; int n;
                while ((n = input.read(b)) != -1 && body.length() < 32000)
                    body.append(new String(b, 0, n, StandardCharsets.UTF_8));
            }
            JSONObject result = new JSONObject(body.toString());
            if (!result.optBoolean("ok")) {
                // Do not expose bot token in exceptions or logs.
                throw new Exception("Telegram " + code + ": " + result.optString("description", "unknown"));
            }
            return result;
        } finally {
            if ("getUpdates".equals(method)) pollConnection = null;
            conn.disconnect();
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }
}
