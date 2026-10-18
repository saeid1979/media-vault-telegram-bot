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
import java.util.concurrent.atomic.AtomicInteger;
import android.os.SystemClock;
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
    // Polling must never wait for Telegram replies or long-running yt-dlp jobs.
    // Stable chat-to-lane routing preserves message order for each Telegram user.
    private final ExecutorService[] replyLanes = new ExecutorService[] {
        Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor(),
        Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()
    };
    private final AtomicInteger queuedReplies = new AtomicInteger();
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(2);
    private final AtomicInteger outstandingDownloads = new AtomicInteger();
    private final Map<Long, Boolean> userDownloads = new ConcurrentHashMap<>();
    private volatile boolean mediaInitialized = false;
    private final Object mediaLock = new Object();
    private void ensureMediaReady() throws Exception {
        if (mediaInitialized) return;
        synchronized (mediaLock) {
            if (mediaInitialized) return;
            YoutubeDL.getInstance().init(getApplicationContext());
            FFmpeg.getInstance().init(getApplicationContext());
            mediaInitialized = true;
        }
    }
    private long updateUser(JSONObject update) {
        JSONObject part = update.optJSONObject("callback_query");
        if (part != null && part.optJSONObject("from") != null)
            return part.optJSONObject("from").optLong("id", 0);
        part = update.optJSONObject("message");
        if (part != null && part.optJSONObject("from") != null)
            return part.optJSONObject("from").optLong("id", 0);
        return 0;
    }
    private void dispatch(JSONObject update, String tok) throws InterruptedException {
        // Backpressure prevents unbounded memory growth if Telegram is unavailable.
        while (running && queuedReplies.get() >= 80) Thread.sleep(100);
        if (!running) return;
        queuedReplies.incrementAndGet();
        long received = SystemClock.elapsedRealtime();
        int lane = (int)Math.floorMod(updateUser(update), replyLanes.length);
        try {
            replyLanes[lane].execute(() -> {
                try {
                    long delay = SystemClock.elapsedRealtime() - received;
                    preferences().edit().putLong("last_reply_queue_ms", delay).apply();
                    handle(update, tok);
                } catch (Exception failure) {
                    status("خطا در پاسخ به یک پیام؛ بات فعال است");
                } finally {
                    queuedReplies.decrementAndGet();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            queuedReplies.decrementAndGet();
            throw stopped;
        }
    }
    private final Map<String, PendingDownload> pending = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private volatile HttpURLConnection pollConnection;
    private BotUserStore userStore;
    private boolean isOwner(long who) { return who == preferences().getLong("owner_id", 0L); }
    private boolean canDownload(long who) {
        return isOwner(who) || BotUserStore.allowed(userStore.find(who));
    }
    private String accessMessage(long who) {
        BotUserStore.User u = userStore.find(who);
        if (u == null || !u.active) return "حساب شما هنوز توسط مدیر بات تأیید نشده است.";
        if (u.blocked) return "حساب شما برای دریافت فایل مسدود شده است.";
        if (!BotUserStore.allowed(u)) return "سهمیه دانلود امروز شما تمام شده است.";
        return "";
    }

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
        userStore = new BotUserStore(this);
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
        // Prime the media libraries in the background instead of repeating setup for each download.
        downloadExecutor.execute(() -> {
            try { ensureMediaReady(); } catch (Exception ignored) { /* surfaced at download time */ }
        });
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
        for (ExecutorService lane : replyLanes) lane.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        HttpURLConnection c = pollConnection;
        if (c != null) c.disconnect();
        pollExecutor.shutdownNow();
        downloadExecutor.shutdownNow();
        for (ExecutorService lane : replyLanes) lane.shutdownNow();
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
                        try {
                            dispatch(update, apiToken);
                            preferences().edit().putLong("update_id", cursor).apply();
                        } catch (java.util.concurrent.RejectedExecutionException stopped) {
                            if (!running) break;
                            throw stopped;
                        }
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
            if (!canDownload(who)) {
                sendText(chat, accessMessage(who), tok);
                return;
            }
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
                if (userDownloads.putIfAbsent(who, true) != null) {
                    sendText(chat, "یک دانلود دیگر برای شما در حال انجام یا انتظار است. بعد از پایان آن دوباره درخواست بده.", tok);
                    return;
                }
                int queued = outstandingDownloads.incrementAndGet();
                if (queued > 8) {
                    outstandingDownloads.decrementAndGet();
                    userDownloads.remove(who);
                    sendText(chat, "صف دانلود فعلاً پر است. لطفاً کمی بعد دوباره تلاش کن.", tok);
                    return;
                }
                pending.remove(parts[1]);
                boolean audio = "a".equals(parts[2]);
                try {
                    downloadExecutor.execute(() -> {
                        try { downloadAndSend(who, chat, item.url, audio, tok); }
                        finally {
                            userDownloads.remove(who);
                            outstandingDownloads.decrementAndGet();
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException stopped) {
                    outstandingDownloads.decrementAndGet();
                    userDownloads.remove(who);
                    sendText(chat, "بات در حال توقف است؛ درخواست را بعداً تکرار کن.", tok);
                    return;
                }
                sendText(chat, "✅ درخواست " + (audio ? "MP3" : "ویدیو") +
                    " ثبت شد. " + (queued > 2 ? "در صف انتظار: " + (queued - 2) :
                    "در حال دریافت فایل روی گوشی") + ".", tok);
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
            String uname = from.optString("username", from.optString("first_name", ""));
            userStore.pending(user, uname);
            if (!canDownload(user)) {
                // Registered users keep their old Telegram IDs; approval must be explicit.
                if (text.startsWith("/start") || text.startsWith("/status") ||
                    text.startsWith("/profile")) {
                    sendText(chat, accessMessage(user) +
                        "\nدر صورت نیاز با مدیر بات تماس بگیر.", tok);
                } else sendText(chat, accessMessage(user), tok);
                return;
            }
        }
        if (text.startsWith("/start") || text.startsWith("/help")) {
            sendText(chat, "سلام 👋\nلینک ویدیوی عمومی و مجاز را بفرست، حق دانلود را تأیید کن و ویدیو یا MP3 را انتخاب کن.\n"
                    + "/status: وضعیت بات\n/profile: حساب و سهمیه", tok);
            return;
        }
        if (text.startsWith("/status")) {
            sendText(chat, "✅ بات روی گوشی Javid Downloader فعال است.", tok);
            return;
        }
        if (text.startsWith("/speed") && isOwner(user)) {
            SharedPreferences p = preferences();
            sendText(chat, "🧪 وضعیت عملکرد بات روی گوشی" +
                "\nدرخواست‌های منتظر پاسخ: " + queuedReplies.get() +
                "\nدانلودهای فعال و در صف: " + outstandingDownloads.get() +
                "\nانتظار آخرین پیام: " + p.getLong("last_reply_queue_ms", 0) + " ms" +
                "\nزمان آخرین دانلود موفق: " + p.getLong("last_download_ms", 0) / 1000 + " ثانیه" +
                "\nزمان آخرین آپلود موفق: " + p.getLong("last_upload_ms", 0) / 1000 + " ثانیه", tok);
            return;
        }
        if (text.startsWith("/profile") || text.startsWith("/limits")) {
            BotUserStore.User info = userStore.find(user);
            String profile = isOwner(user) ? "مالک · نامحدود" : info == null ? "ثبت نشده" :
                "نقش: " + info.role + "\nدانلودهای ثبت‌شده: " + info.totalDownloads +
                "\nسهمیه روزانه: " + BotUserStore.quota(info) +
                "\nمصرف امروز: " + (new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.US).format(new java.util.Date()).equals(info.counterDay)
                    ? info.todayDownloads : 0);
            sendText(chat, "📊 وضعیت حساب\n" + profile, tok);
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

    private void downloadAndSend(long who, long chat, String link, boolean audio, String tok) {
        File folder = new File(getExternalFilesDir(null), "bot-work/" + UUID.randomUUID());
        try {
            if (!canDownload(who)) {
                sendText(chat, accessMessage(who), tok);
                return;
            }
            if (preferences().getBoolean("wifi_only", true) && !onWifi()) {
                sendText(chat, "برای جلوگیری از مصرف اینترنت سیم‌کارت، دانلود بات فقط روی Wi-Fi فعال است. می‌توانی این تنظیم را در اپ تغییر دهی.", tok);
                return;
            }
            if (!folder.mkdirs()) throw new Exception("Cannot create temporary folder");
            ensureMediaReady();
            long startedDownload = SystemClock.elapsedRealtime();
            YoutubeDLRequest request = new YoutubeDLRequest(link);
            request.addOption("--no-playlist");
            request.addOption("--no-mtime");
            request.addOption("--max-filesize", "45M");
            request.addOption("--concurrent-fragments", "4");
            request.addOption("--retries", "3");
            request.addOption("--fragment-retries", "3");
            request.addOption("--socket-timeout", "20");
            request.addOption("-o", new File(folder, "%(title).110B [%(id)s].%(ext)s").getAbsolutePath());
            if (audio) {
                request.addOption("-x");
                request.addOption("--audio-format", "mp3");
                request.addOption("--audio-quality", "5");
            } else {
                // Prefer one-file MP4 when available; it avoids a costly phone-side merge.
                request.addOption("-f", "b[height<=720][ext=mp4]/b[height<=720]/bv*[height<=720]+ba/b[height<=720]/best");
                request.addOption("--merge-output-format", "mp4");
            }
            YoutubeDL.getInstance().execute(request, "telegram-" + UUID.randomUUID(), null);
            long downloadMs = SystemClock.elapsedRealtime() - startedDownload;
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
            long uploadStart = SystemClock.elapsedRealtime();
            uploadDocument(chat, best, tok);
            long uploadMs = SystemClock.elapsedRealtime() - uploadStart;
            preferences().edit().putLong("last_download_ms", downloadMs)
                .putLong("last_upload_ms", uploadMs).apply();
            if (!isOwner(who)) userStore.recordDelivered(who);
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
                + file.getName().replaceAll("[\\r\\n\"\\\\]", "_") + "\"\r\n"
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
            if (stream == null) throw new Exception("Telegram upload failed: " + code);
            java.io.ByteArrayOutputStream response = new java.io.ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int n, total = 0;
            while ((n = stream.read(data)) != -1) {
                total += n;
                if (total > 1024 * 1024) throw new Exception("Telegram response too large");
                response.write(data, 0, n);
            }
            if (!new JSONObject(response.toString("UTF-8")).optBoolean("ok"))
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
