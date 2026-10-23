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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;
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
    public static final String ACTION_RESUME = "com.javid.downloader.BOT_RESUME";
    public static final String PREF_DESIRED = "bot_desired_running";
    public static final String PREF_AUTO_RESUME = "bot_auto_resume";
    public static final String PREFS = "javid_telegram_bot";
    public static final String KEY_STATUS = "status";
    private static final String BUILD_LABEL = "Javid Downloader Android 2.1.2 · expanded Iranian goal scorer coverage";
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
    // Never edit Telegram for every yt-dlp callback: throttle progress to avoid flood limits.
    private final ScheduledExecutorService progressExecutor =
        Executors.newScheduledThreadPool(2);
    private final ScheduledExecutorService digestExecutor =
        Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean digestSending =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final class ProgressReporter {
        final long chat;
        final int messageId;
        final String tok;
        volatile int percent = 0;
        volatile String stage = "download";
        volatile String lastSent = "";
        volatile String notice = "";
        private ScheduledFuture<?> ticker;
        private volatile boolean ended = false;
        ProgressReporter(long chat, int messageId, String tok) {
            this.chat = chat; this.messageId = messageId; this.tok = tok;
        }
        void start() {
            if (messageId <= 0) return;
            ticker = progressExecutor.scheduleAtFixedRate(this::tick, 0, 2, TimeUnit.SECONDS);
        }
        String statusText() {
            int p = Math.max(0, Math.min(100, percent));
            String label = "upload".equals(stage) ? "📤 ارسال به تلگرام" :
                "processing".equals(stage) ? "🔄 آماده‌سازی فایل" :
                "confirming".equals(stage) ? "⌛ در انتظار تأیید تلگرام" :
                "waiting".equals(stage) ? "⏳ در صف دانلود" : "⬇️ دانلود";
            if ("processing".equals(stage) || "waiting".equals(stage) ||
                "confirming".equals(stage)) return label + "…";
            int blocks = Math.min(10, p / 10);
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < 10; i++) bar.append(i < blocks ? "■" : "□");
            return label + ": " + p + "%\n" + bar +
                (notice.isEmpty() ? "" : "\n" + notice);
        }
        private void tick() {
            if (ended) return;
            String now = statusText();
            if (now.equals(lastSent)) return;
            try {
                editText(chat, messageId, now, tok);
                lastSent = now;
            } catch (Exception ignored) {
                // A transient Telegram error must never cancel a successful download.
            }
        }
        void phase(String value) { stage = value; }
        void percent(float value) {
            if (Float.isNaN(value) || Float.isInfinite(value)) return;
            percent = Math.max(0, Math.min(100, Math.round(value)));
        }
        void finish(boolean success, String problem) {
            ended = true;
            if (ticker != null) ticker.cancel(false);
            if (messageId <= 0) return;
            if (success) {
                // Leave the delivered file; remove only this bot-created temporary message.
                try { deleteText(chat, messageId, tok); }
                catch (Exception ignored) {
                    try { editText(chat, messageId, "✅ فایل ارسال شد.", tok); }
                    catch (Exception ignoredAgain) { }
                }
            } else {
                try { editText(chat, messageId, "⚠️ " + problem, tok); }
                catch (Exception ignored) { }
            }
        }
    }
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
    private ConnectivityManager connections;
    private ConnectivityManager.NetworkCallback networkWatcher;
    private void watchNetwork() {
        if (Build.VERSION.SDK_INT < 24) return;
        connections = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connections == null) return;
        networkWatcher = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (!running) return;
                status("🌐 اینترنت وصل شد؛ اتصال مجدد تلگرام...");
                // Interrupt current long poll so it retries with the new network.
                HttpURLConnection active = pollConnection;
                if (active != null) active.disconnect();
            }
            @Override public void onLost(Network network) {
                if (running) status("📴 اینترنت قطع شد؛ در انتظار اتصال مجدد...");
            }
        };
        try { connections.registerDefaultNetworkCallback(networkWatcher); }
        catch (Exception ignored) { networkWatcher = null; }
    }
    private void unwatchNetwork() {
        if (connections != null && networkWatcher != null) {
            try { connections.unregisterNetworkCallback(networkWatcher); }
            catch (Exception ignored) { }
        }
        networkWatcher = null;
    }
    private BotUserStore userStore;
    private BotStatsStore botStats;
    private BotDownloadLogStore audit;
    private HourlyDigestPublisher digests;
    private FootballLiveTracker football;
    private RainAlerts rainAlerts;
    private void publishQueue() {
        preferences().edit().putInt("current_downloads",
            Math.max(0, outstandingDownloads.get())).apply();
    }
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
        botStats = new BotStatsStore(this);
        audit = new BotDownloadLogStore(this);
        digests = new HourlyDigestPublisher(this, preferences(), userStore);
        football = new FootballLiveTracker(preferences());
        rainAlerts = new RainAlerts(preferences());
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
            stopBot(true);
            return START_NOT_STICKY;
        }
        boolean isManual = intent != null && ACTION_START.equals(intent.getAction());
        // START_STICKY may redeliver a null intent after Android reclaims the
        // process. Only restart if owner previously requested it.
        if (!isManual && !preferences().getBoolean(PREF_DESIRED, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        // specialUse is appropriate for a continuously visible, owner-controlled
        // bot; dataSync is limited to 6h and forbidden at boot on Android 15.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification("در حال اتصال به تلگرام..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification("در حال اتصال به تلگرام..."));
        }
        running = true;
        publishQueue();
        SharedPreferences.Editor desiredState = preferences().edit()
            .putBoolean("enabled", true).remove("auto_resume_issue");
        if (isManual) desiredState.putBoolean(PREF_DESIRED, true);
        desiredState.apply();
        watchNetwork();
        progressExecutor.scheduleAtFixedRate(() -> {
            if (running) preferences().edit()
                .putLong("service_heartbeat", System.currentTimeMillis()).apply();
        }, 0, 30, TimeUnit.SECONDS);
        status("در حال اتصال به بات تلگرام...");
        // Prime the media libraries in the background instead of repeating setup for each download.
        downloadExecutor.execute(() -> {
            try { ensureMediaReady(); } catch (Exception ignored) { /* surfaced at download time */ }
        });
        pollExecutor.execute(this::loop);
        // In-app hourly publication depends on the Android foreground bot
        // continuing to run. Database last-hour marker survives service restart.
        digestExecutor.scheduleAtFixedRate(() -> {
            if (!running || !preferences().getBoolean(HourlyDigestPublisher.ENABLED, false)) return;
            try { publishDigest(false, 0L, null); }
            catch (Exception ignored) { }
        }, HourlyDigestPublisher.millisUntilNextHour(),
           HourlyDigestPublisher.HOUR_MS, TimeUnit.MILLISECONDS);
        // 2-minute checking when live, otherwise 10 minutes to conserve data.
        digestExecutor.scheduleWithFixedDelay(() -> {
            if (!running || !preferences().getBoolean(HourlyDigestPublisher.ENABLED,false))
                return;
            long last=preferences().getLong(FootballLiveTracker.LAST_CHECK,0);
            boolean inPlay=preferences().getBoolean("football_was_live",false);
            long window=inPlay?TimeUnit.MINUTES.toMillis(2):TimeUnit.MINUTES.toMillis(10);
            if (System.currentTimeMillis()-last<window) return;
            try {
                FootballLiveTracker.Update update=football.check();
                boolean live=false;
                for(FootballLiveTracker.Match m:update.snapshot.games)if(m.live){live=true;break;}
                preferences().edit().putBoolean("football_was_live",live).apply();
                if(!update.alerts.isEmpty()) {
                    String secret=token();
                    for(String alert:update.alerts) {
                        if(!running || !preferences().getBoolean(HourlyDigestPublisher.ENABLED,false))
                            break;
                        digests.sendAlert(secret,alert);
                    }
                }
            }catch(Exception ignored){
                preferences().edit().putString("football_poll_issue",
                    "بررسی فوتبال کامل نشد؛ در تلاش بعدی دوباره بررسی می‌شود.").apply();
            }
        }, 12, 120, TimeUnit.SECONDS);
        // No weather report unless Salamanca or Ardabil transitions to rain.
        digestExecutor.scheduleWithFixedDelay(() -> {
            if (!running || !preferences().getBoolean(HourlyDigestPublisher.ENABLED,false))
                return;
            try {
                RainAlerts.Report report=rainAlerts.check(true);
                if(!report.notices.isEmpty()) {
                    String secret=token();
                    for(String alert:report.notices)
                        if(running && preferences().getBoolean(HourlyDigestPublisher.ENABLED,false))
                            digests.sendAlert(secret,alert);
                }
            }catch(Exception ignored){}
        }, 40, 15*60, TimeUnit.SECONDS);
        return START_STICKY;
    }

    private void stopBot(boolean manual) {
        running = false;
        HttpURLConnection c = pollConnection;
        if (c != null) c.disconnect();
        SharedPreferences.Editor stopped = preferences().edit()
            .putBoolean("enabled", false).putInt("current_downloads", 0);
        if (manual) stopped.putBoolean(PREF_DESIRED, false);
        stopped.apply();
        status(manual ? "بات توسط مالک خاموش شد" : "بات توسط اندروید متوقف شد");
        unwatchNetwork();
        preferences().edit().putLong("service_heartbeat", 0L).apply();
        downloadExecutor.shutdownNow();
        progressExecutor.shutdownNow();
        digestExecutor.shutdownNow();
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
        progressExecutor.shutdownNow();
        digestExecutor.shutdownNow();
        unwatchNetwork();
        preferences().edit().putLong("service_heartbeat", 0L).apply();
        if (audit != null) audit.close();
        if (botStats != null) botStats.close();
        if (userStore != null) userStore.close();
        for (ExecutorService lane : replyLanes) lane.shutdownNow();
        preferences().edit().putBoolean("enabled", false).putInt("current_downloads", 0).apply();
        super.onDestroy();
    }

    @Override public void onTimeout(int startId, int fgsType) {
        status("محدودیت زمانی اجرای پس‌زمینه اندروید؛ ربات متوقف شد");
        stopBot(false);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private String token() throws Exception {
        String result = BotSecrets.read(this);
        if (result.isEmpty()) throw new IllegalStateException("توکن ذخیره نشده");
        return result;
    }

    /**
     * Runs only on digestExecutor. testChat sends the post-run summary to the
     * owner, whereas the hourly task quietly stores status in preferences.
     */
    private void publishDigest(boolean manual, long testChat, String token) {
        if (!digestSending.compareAndSet(false, true)) {
            if (manual && token != null) {
                try { sendText(testChat, "خبرنامه در حال آماده‌سازی یا ارسال است.", token); }
                catch (Exception ignored) { }
            }
            return;
        }
        try {
            String secret = token != null ? token : token();
            HourlyDigestPublisher.Report result = digests.publish(secret, manual);
            if (manual) sendText(testChat, "📬 نتیجه ارسال خبرنامه:\n" +
                result.status() + "\n" +
                preferences().getString(HourlyDigestPublisher.LAST_RESULT,
                    result.content == null ? "بدون نتیجه" : result.content), secret);
        } catch (Exception failure) {
            preferences().edit().putString(HourlyDigestPublisher.LAST_RESULT,
                "خطا در ساخت یا ارسال گزارش؛ اتصال داده‌ها و بات را بررسی کنید.").apply();
            if (manual && token != null)
                try { sendText(testChat, "⚠️ گزارش آماده یا ارسال نشد. اتصال اینترنت را بررسی کن.", token); }
                catch (Exception ignored) { }
        } finally {
            digestSending.set(false);
        }
    }

    /**
     * Persistent polling: network outages, temporary Telegram errors and
     * startup without connectivity retry until the owner stops the bot.
     * Critically, getMe must be INSIDE the retry loop.
     */
    private void loop() {
        long cursor = preferences().getLong("update_id", 0L);
        String username = preferences().getString("bot_username", "");
        int errors = 0;
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    String apiToken = token();
                    if (username.isEmpty() || errors > 0) {
                        JSONObject me = call("getMe", new JSONObject(), 10000, apiToken);
                        username = me.getJSONObject("result").optString("username", "UnknownBot");
                        preferences().edit().putString("bot_username", username).apply();
                        ensurePairCode();
                    }
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
                        } catch (java.util.concurrent.RejectedExecutionException stopping) {
                            if (!running) break;
                            throw stopping;
                        }
                    }
                    errors = 0;
                    status("✅ متصل به @" + username + " · v2.1.2 · بات فعال است");
                } catch (Exception failure) {
                    if (!running) break;
                    errors++;
                    String desc = failure.getMessage() == null ? "" : failure.getMessage();
                    if (desc.contains("409") || desc.toLowerCase(java.util.Locale.ROOT).contains("conflict"))
                        status("بات روی دستگاه یا سرور دیگری هم فعال است (Telegram 409)");
                    else if (desc.contains("401"))
                        status("توکن بات معتبر نیست؛ در تنظیمات بررسی کن");
                    else status("📴 اتصال تلگرام قطع است؛ تلاش خودکار برای اتصال مجدد...");
                    try {
                        Thread.sleep(Math.min(30000L, 3000L * Math.min(8, errors)));
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
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
            int promptId = message.optInt("message_id", 0);
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
            if (data.startsWith("cancel:")) {
                if (pending.remove(parts[1], item)) {
                    try { deleteText(chat, promptId, tok); }
                    catch (Exception e) { editText(chat, promptId, "❌ لغو شد.", tok); }
                }
                return;
            }
            if (data.startsWith("fmt:") && parts.length == 3) {
                if ("v".equals(parts[2])) {
                    JSONObject qualities = keyboard(
                        new JSONArray()
                            .put(new JSONObject().put("text", "1080p").put("callback_data", "quality:" + parts[1] + ":1080"))
                            .put(new JSONObject().put("text", "720p").put("callback_data", "quality:" + parts[1] + ":720"))
                            .put(new JSONObject().put("text", "480p").put("callback_data", "quality:" + parts[1] + ":480")),
                        new JSONArray()
                            .put(new JSONObject().put("text", "360p").put("callback_data", "quality:" + parts[1] + ":360")),
                        new JSONArray()
                            .put(new JSONObject().put("text", "🎵 MP3").put("callback_data", "fmt:" + parts[1] + ":a"))
                            .put(new JSONObject().put("text", "❌ لغو").put("callback_data", "cancel:" + parts[1]))
                    );
                    editText(chat, promptId, "🎬 کیفیت ویدیو را انتخاب کن (حداکثر کیفیت در دسترس):", qualities, tok);
                    return;
                }
                if (!"a".equals(parts[2])) return;
            } else if (!data.startsWith("quality:") || parts.length != 3) {
                return;
            }
            final boolean audio = data.startsWith("fmt:");
            final int height;
            if (audio) height = 0;
            else {
                try { height = Integer.parseInt(parts[2]); }
                catch (NumberFormatException e) { return; }
                if (height != 1080 && height != 720 && height != 480 && height != 360) return;
            }
            if (userDownloads.putIfAbsent(who, true) != null) {
                editText(chat, promptId, "⏳ دانلود قبلی شما هنوز در حال انجام است.", tok);
                return;
            }
            int queued = outstandingDownloads.incrementAndGet();
            publishQueue();
            if (queued > 8) {
                outstandingDownloads.decrementAndGet();
                publishQueue();
                userDownloads.remove(who);
                editText(chat, promptId, "⏳ صف دانلود پر است. کمی بعد دوباره تلاش کن.", tok);
                return;
            }
            if (!pending.remove(parts[1], item)) {
                outstandingDownloads.decrementAndGet();
                userDownloads.remove(who);
                return;
            }
            String userLabel = from.optString("username", "");
            if (!userLabel.isEmpty()) userLabel = "@" + userLabel;
            else userLabel = from.optString("first_name", "") + " " +
                from.optString("last_name", "");
            if (userLabel.trim().isEmpty() && isOwner(who)) userLabel = "مالک بات";
            final String senderLabel = userLabel.trim();
            ProgressReporter reporter = new ProgressReporter(chat, promptId, tok);
            reporter.phase(queued > 2 ? "waiting" : "download");
            // Immediately remove old inline buttons; subsequent edits update this SAME message.
            try {
                editText(chat, promptId, "⏳ " + (audio ? "MP3" : "ویدیو تا " + height + "p") +
                    "\nدر حال آماده‌سازی دانلود...", tok);
            } catch (Exception ignored) { }
            reporter.start();
            try {
                downloadExecutor.execute(() -> {
                    try { downloadAndSend(who, senderLabel, chat, item.url, audio, height, tok, reporter); }
                    finally {
                        userDownloads.remove(who);
                        outstandingDownloads.decrementAndGet();
                        publishQueue();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                outstandingDownloads.decrementAndGet();
                userDownloads.remove(who);
                reporter.finish(false, "بات متوقف شد؛ درخواست را دوباره ارسال کن.");
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
        if (text.equals("/privacy") || text.startsWith("/privacy@")) {
            sendText(chat, "🔒 شفافیت گزارش دانلود Downloaderjavid" +
                "\nبرای مدیریت بات، شناسه و نام نمایشی تلگرام، عنوان فایل، " +
                "سایت مبدأ، کیفیت درخواستی، تاریخ، حجم و نتیجه دانلود " +
                "حداکثر ۳۰ روز در گوشی مدیر ثبت می‌شود." +
                "\nمتن پیام‌ها، رمزها و پارامترهای خصوصی لینک ذخیره نمی‌شوند." +
                "\nمی‌توانی با /forgetmyhistory تاریخچه جزئی خود را پاک کنی. " +
                "شمارش‌های کلی و اطلاعات حساب عضو باقی می‌مانند.", tok);
            return;
        }
        if (text.equals("/forgetmyhistory") || text.startsWith("/forgetmyhistory@")) {
            int removed = audit.deleteForUser(user);
            sendText(chat, "✅ " + removed +
                " مورد از تاریخچه جزئی دانلودهای شما از این گوشی حذف شد. " +
                "آمار کلی و مشخصات حساب دست‌نخورده است.", tok);
            return;
        }
        // Private digest is opt-in, and only an already approved member
        // can subscribe. Channel publication is configured separately by owner.
        if ((text.equals("/footballiran") || text.startsWith("/footballiran@")) && isOwner(user)) {
            sendText(chat,"⏳ در حال بررسی اختصاصی لیگ برتر ایران و گل‌زن‌ها...",tok);
            digestExecutor.execute(() -> {
                try {
                    FootballLiveTracker.Snapshot snapshot=football.collect();
                    sendText(chat,FootballLiveTracker.iranSummary(snapshot),tok);
                }catch(Exception unavailable){
                    try {sendText(chat,"⚠️ دریافت لیگ ایران موفق نبود. /footballstatus را بررسی کن.",tok);}
                    catch(Exception ignored){}
                }
            });
            return;
        }
        if ((text.equals("/footballnow") || text.startsWith("/footballnow@")) && isOwner(user)) {
            sendText(chat,"⏳ در حال بررسی نتایج مهم اروپا و ایران از چند منبع...",tok);
            digestExecutor.execute(() -> {
                try {
                    FootballLiveTracker.Snapshot snapshot=football.collect();
                    sendText(chat,FootballLiveTracker.summary(snapshot),tok);
                }catch(Exception ignored){
                    try {sendText(chat,"⚠️ دریافت نتایج فوتبال موفق نبود.",tok);}
                    catch(Exception ignoredAgain){}
                }
            });
            return;
        }
        if ((text.equals("/footballstatus") || text.startsWith("/footballstatus@")) && isOwner(user)) {
            sendText(chat,"⚽ وضعیت منبع فوتبال\n" +
                preferences().getString(FootballLiveTracker.LAST_STATUS,
                    "هنوز پایش نشده") +
                "\n" + (preferences().getBoolean("football_was_live",false) ?
                    "فاصله بررسی: حدود ۲ دقیقه" : "فاصله بررسی: حدود ۱۰ دقیقه") +
                "\n🌧 وضعیت هواشناسی: " +
                preferences().getString(RainAlerts.STATUS,"هنوز بررسی نشده"),tok);
            return;
        }
        if (text.equals("/digestjoin") || text.startsWith("/digestjoin@")) {
            BotUserStore.User member = userStore.find(user);
            if (!isOwner(user) && (member == null || !member.active || member.blocked)) {
                sendText(chat, "عضویت در خبرنامه بعد از تأیید حساب بات ممکن است.", tok);
                return;
            }
            String username = from.optString("username", from.optString("first_name", ""));
            userStore.setDigestOptIn(user, true, username);
            sendText(chat, "✅ خبرنامه ساعتی فعال شد. گل‌ها و نتایج مهم فوتبال اروپا و ایران و هشدار بارندگی سالامانکا و اردبیل را دریافت می‌کنی. اخبار حذف شده‌اند.\nبرای توقف: /digestleave", tok);
            return;
        }
        if (text.equals("/digestleave") || text.startsWith("/digestleave@")) {
            BotUserStore.User member = userStore.find(user);
            if (member != null) userStore.setDigestOptIn(user, false, member.username);
            sendText(chat, "🔕 خبرنامه خصوصی برای شما خاموش شد.", tok);
            return;
        }
        if (text.equals("/digesthelp") || text.startsWith("/digesthelp@")) {
            sendText(chat, "⚽ نتایج فوتبال و هشدار بارندگی Downloaderjavid\n" +
                "/digestjoin عضویت داوطلبانه\n/digestleave لغو پیام خصوصی\n" +
                (isOwner(user) ? "مدیریت:\n/digestto @ChannelName تنظیم کانال\n" +
                    "/digeston فعال‌سازی ساعتی\n/digestoff توقف انتشار\n" +
                    "/digestpreview پیش‌نمایش نتایج فوتبال\n/digestnow ارسال فوری\n/digeststatus وضعیت\n/footballnow نتایج جهان\n/footballiran لیگ برتر ایران و گل‌زن‌ها\n/footballstatus سلامت منابع" :
                    "فقط نتایج فوتبال و هشدار بارندگی، بدون اخبار عمومی."), tok);
            return;
        }
        if (text.startsWith("/digest") && isOwner(user)) {
            if (text.startsWith("/digestto ")) {
                String target = text.substring("/digestto ".length()).trim();
                if (!HourlyDigestPublisher.channelValid(target)) {
                    sendText(chat, "نام کانال باید مانند @Downloaderjavid یا شناسه -100... باشد.", tok);
                    return;
                }
                preferences().edit().putString(HourlyDigestPublisher.TARGET, target).apply();
                sendText(chat, "✅ کانال ذخیره شد: " + target +
                    "\nبات باید مدیر کانال با دسترسی انتشار پست باشد.\n" +
                    "برای امتحان: /digestpreview سپس /digeston", tok);
                return;
            }
            if (text.equals("/digestoff")) {
                preferences().edit().putBoolean(HourlyDigestPublisher.ENABLED, false).apply();
                sendText(chat, "⏸ انتشار خبرنامه ساعتی متوقف شد.", tok);
                return;
            }
            if (text.equals("/digeston")) {
                boolean channel = HourlyDigestPublisher.channelValid(
                    preferences().getString(HourlyDigestPublisher.TARGET, ""));
                if (!channel && userStore.digestRecipients().isEmpty()) {
                    sendText(chat, "ابتدا کانال را با /digestto @ChannelName تعیین کن " +
                        "یا یک کاربر تأییدشده با /digestjoin عضو شود.", tok);
                    return;
                }
                preferences().edit().putBoolean(HourlyDigestPublisher.ENABLED, true).apply();
                sendText(chat, "✅ نتایج فوتبال و هشدار بارندگی روشن شدند. خلاصه فوتبال ابتدای ساعت بعد، " +
                    "به وقت اسپانیا است؛ تا وقتی سرویس بات روی گوشی فعال بماند.\n" +
                    "برای ارسال همین حالا: /digestnow", tok);
                return;
            }
            if (text.equals("/digeststatus")) {
                String target = preferences().getString(HourlyDigestPublisher.TARGET, "");
                long last = preferences().getLong(HourlyDigestPublisher.LAST_SENT, 0);
                String when = last == 0 ? "هنوز ارسال نشده" :
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(last));
                sendText(chat, "📰 وضعیت خبرنامه\nساعتی: " +
                    (preferences().getBoolean(HourlyDigestPublisher.ENABLED, false) ? "روشن" : "خاموش") +
                    "\nکانال: " + (target.isEmpty() ? "تنظیم نشده" : target) +
                    "\nدریافت‌کنندگان خصوصی: " + userStore.digestRecipients().size() +
                    "\nآخرین ارسال: " + when +
                    "\nنتیجه: " + preferences().getString(HourlyDigestPublisher.LAST_RESULT, "—"), tok);
                return;
            }
            if (text.equals("/digestpreview")) {
                sendText(chat, "⏳ در حال بررسی نتایج فوتبال؛ اخبار حذف شده‌اند...", tok);
                digestExecutor.execute(() -> {
                    try {
                        HourlyDigestPublisher.Report report = digests.build();
                        sendText(chat, report.content, tok);
                    } catch (Exception ignored) {
                        try { sendText(chat, "⚠️ ساخت پیش‌نمایش موفق نبود. اتصال اینترنت را بررسی کن.", tok); }
                        catch (Exception ignoredAgain) { }
                    }
                });
                return;
            }
            if (text.equals("/digestnow")) {
                if (!preferences().getBoolean(HourlyDigestPublisher.ENABLED, false)) {
                    sendText(chat, "ابتدا خبرنامه را با /digeston فعال کن.", tok);
                    return;
                }
                sendText(chat, "⏳ انتشار فوری در حال اجراست؛ نتیجه بعد از ارسال می‌آید.", tok);
                digestExecutor.execute(() -> publishDigest(true, chat, tok));
                return;
            }
            sendText(chat, "برای مشاهده دستورات خبرنامه: /digesthelp", tok);
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
        if (text.equals("/version") || text.startsWith("/version@")) {
            sendText(chat, "✅ " + BUILD_LABEL +
                "\nاین پاسخ از موتور جدید اندروید است. بات قدیمی نباید هم‌زمان فعال باشد.", tok);
            return;
        }
        if (text.startsWith("/start") || text.startsWith("/help")) {
            sendText(chat, "سلام 👋\nلینک ویدیوی عمومی و مجاز را بفرست، حق دانلود را تأیید کن و ویدیو یا MP3 را انتخاب کن.\n"
                    + "/status: وضعیت بات\n/profile: حساب و سهمیه" +
                    "\n/privacy: نحوه ثبت تاریخچه\n/forgetmyhistory: حذف گزارش جزئی خود" +
                    "\n/digesthelp: نتایج فوتبال و هشدار بارندگی، بدون اخبار", tok);
            return;
        }
        if (text.startsWith("/status")) {
            sendText(chat, "✅ بات روی گوشی Javid Downloader فعال است.\nنسخه موتور: " + BUILD_LABEL, tok);
            return;
        }
        if ((text.equals("/diag") || text.startsWith("/diag@")) && isOwner(user)) {
            sendText(chat, "🔎 آخرین خطای دانلود (بدون ثبت لینک یا مشخصات کاربران):\n" +
                preferences().getString("last_download_issue", "هنوز خطایی ثبت نشده است.") +
                "\nبرای دیدن علت جدید، یک دانلود آزمایشی انجام بده.", tok);
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
                "نقش: " + BotUserStore.effectiveRole(info) +
                (BotUserStore.vipExpired(info) ? " (VIP منقضی)" : "") +
                ("vip".equals(info.role) ? "\nانقضای VIP: " +
                    (info.vipExpiresOn.isEmpty() ? "بدون انقضا" : info.vipExpiresOn) : "") +
                "\nدانلودهای ثبت‌شده: " + info.totalDownloads +
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
        JSONObject formats = keyboard(
            new JSONArray()
                .put(new JSONObject().put("text", "🎬 ویدیو").put("callback_data", "fmt:" + id + ":v"))
                .put(new JSONObject().put("text", "🎵 MP3").put("callback_data", "fmt:" + id + ":a")),
            new JSONArray()
                .put(new JSONObject().put("text", "❌ لغو").put("callback_data", "cancel:" + id))
        );
        // Exactly one format question. Selecting an option edits this SAME message.
        sendText(chat, "📎 " + source + "\nچه فرمتی می‌خواهی؟", formats, tok);
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

    /** Optional bounded selection, followed by safe nearest-quality fallback. */
    private YoutubeDLRequest mediaRequest(String link, File folder, boolean audio,
                                          int height, boolean fallback) {
        YoutubeDLRequest request = new YoutubeDLRequest(link);
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("--max-filesize", "45M");
        request.addOption("--concurrent-fragments", "4");
        request.addOption("--retries", "3");
        request.addOption("--fragment-retries", "3");
        request.addOption("--socket-timeout", "20");
        request.addOption("-o", new File(folder,
            "%(title).110B [%(id)s].%(ext)s").getAbsolutePath());
        if (audio) {
            request.addOption("-x");
            request.addOption("--audio-format", "mp3");
            request.addOption("--audio-quality", "5");
        } else {
            String bound = String.valueOf(height);
            if (fallback) {
                // Sorting by target resolution avoids failing on missing
                // height metadata, but the actual source quality may differ.
                request.addOption("-f", "b/bv*+ba");
                request.addOption("-S", "res:" + bound + ",ext:mp4");
            } else {
                String formats = "b[height<=" + bound + "][ext=mp4]" +
                    "/bv*[height<=" + bound + "][ext=mp4]+ba[ext=m4a]" +
                    "/b[height<=" + bound + "]" +
                    "/bv*[height<=" + bound + "]+ba";
                request.addOption("-f", formats);
            }
            request.addOption("--merge-output-format", "mp4");
        }
        return request;
    }

    private static final class MediaFailure extends Exception {
        final String details;
        MediaFailure(Exception cause, String details) {
            super(cause);
            this.details = details == null ? "" : details;
        }
    }
    private static String getErrorSummary(Throwable failure, String output) {
        StringBuilder summary = new StringBuilder();
        for (Throwable e = failure; e != null; e = e.getCause()) {
            if (e.getMessage() != null) summary.append(' ').append(e.getMessage());
        }
        if (output != null) summary.append(' ').append(output);
        String text = summary.toString().toLowerCase(java.util.Locale.ROOT);
        return text.substring(Math.max(0, text.length() - 5000));
    }
    private static boolean isFormatUnavailable(String detail) {
        return detail.contains("requested format is not available") ||
            detail.contains("requested format not available") ||
            (detail.contains("requested format") && detail.contains("not available"));
    }
    private static boolean isAccessFailure(String detail) {
        return detail.contains("login required") || detail.contains("log in") ||
            detail.contains("sign in") || detail.contains("private") ||
            detail.contains("drm") || detail.contains("403") ||
            detail.contains("429") || detail.contains("captcha") ||
            detail.contains("not available in your country");
    }
    private String friendlyFailure(Throwable error, String details, boolean uploadPhase) {
        if (error instanceof IllegalStateException && error.getMessage() != null)
            return error.getMessage();
        if (uploadPhase) {
            if (details.contains("413")) return "تلگرام حجم این فایل را نمی‌پذیرد. کیفیت پایین‌تر را انتخاب کن.";
            return "فایل دریافت شد، اما ارسال آن به تلگرام ناموفق بود. اتصال گوشی را بررسی کن.";
        }
        if (isFormatUnavailable(details))
            return "این کیفیت در ویدیو موجود نیست؛ دریافت خودکار با فرمت دیگر هم موفق نشد.";
        if (details.contains("429") || details.contains("too many requests"))
            return "سایت موقتاً درخواست‌های زیاد را محدود کرده است. کمی بعد دوباره تلاش کن.";
        if (details.contains("login") || details.contains("sign in") ||
            details.contains("private") || details.contains("403") || details.contains("captcha"))
            return "سایت به این ویدیو دسترسی عمومی نمی‌دهد یا ورود به حساب می‌خواهد.";
        if (details.contains("unsupported url") || details.contains("not supported"))
            return "این آدرس در موتور دانلود فعلی پشتیبانی نمی‌شود.";
        if (details.contains("404") || details.contains("not found") ||
            details.contains("unavailable"))
            return "ویدیو در دسترس نیست یا لینک آن تغییر کرده است.";
        if (details.contains("timed out") || details.contains("timeout") ||
            details.contains("connection"))
            return "اتصال سایت قطع شد یا پاسخ آن دیر رسید. اینترنت و لینک را بررسی کن.";
        if (details.contains("file is larger") || details.contains("max-filesize"))
            return "حجم فایل از سقف ۴۵ مگابایت بیشتر است؛ کیفیت پایین‌تر را انتخاب کن.";
        return "دریافت از منبع ناموفق بود. عمومی بودن لینک و به‌روز بودن موتور دانلود را بررسی کن.";
    }
    private static String categoryCode(String details, boolean uploadPhase) {
        if (uploadPhase) return "TELEGRAM_UPLOAD";
        if (isFormatUnavailable(details)) return "FORMAT_UNAVAILABLE";
        if (details.contains("429")) return "SOURCE_RATE_LIMIT";
        if (isAccessFailure(details)) return "SOURCE_ACCESS";
        if (details.contains("404")) return "SOURCE_NOT_FOUND";
        if (details.contains("unsupported")) return "UNSUPPORTED_URL";
        if (details.contains("timeout") || details.contains("connection")) return "NETWORK";
        return "SOURCE_OTHER";
    }

    private void downloadAndSend(long who, String senderLabel, long chat, String link,
                                 boolean audio, int height, String tok, ProgressReporter reporter) {
        File folder = new File(getExternalFilesDir(null), "bot-work/" + UUID.randomUUID());
        long startedAt = SystemClock.elapsedRealtime();
        boolean uploadPhase = false;
        boolean delivered = false;
        String itemTitle = "";
        String issueCategory = "";
        long itemSize = 0;
        final String[] lastExtractorIssue = {""};
        try {
            reporter.phase("download");
            reporter.percent(0);
            if (!canDownload(who))
                throw new IllegalStateException(accessMessage(who));
            if (preferences().getBoolean("wifi_only", true) && !onWifi())
                throw new IllegalStateException("دانلود فقط روی Wi-Fi فعال است. تنظیمات بات را بررسی کن.");
            if (!folder.mkdirs()) throw new Exception("Cannot create temporary folder");
            ensureMediaReady();
            long startedDownload = SystemClock.elapsedRealtime();
            YoutubeDLRequest request = mediaRequest(link, folder, audio, height, false);
            final String[] latestError = lastExtractorIssue;
            Function3<Float, Long, String, Unit> track = (p, eta, output) -> {
                if (output != null) {
                    String lower = output.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("error:") || lower.contains("requested format") ||
                        lower.contains("login") || lower.contains("no video formats") ||
                        lower.contains("http error") || lower.contains("sign in")) {
                        latestError[0] = output.length() > 3000 ?
                            output.substring(output.length() - 3000) : output;
                    }
                    if (output.contains("[ExtractAudio]") || output.contains("[Merger]") ||
                        output.contains("[VideoConvertor]")) {
                        reporter.phase("processing");
                    } else if (!"processing".equals(reporter.stage)) {
                        reporter.phase("download");
                        reporter.percent(p == null ? 0 : p);
                    }
                } else if (!"processing".equals(reporter.stage)) {
                    reporter.percent(p == null ? 0 : p);
                }
                return Unit.INSTANCE;
            };
            try {
                YoutubeDL.getInstance().execute(request, "telegram-" + UUID.randomUUID(), track);
            } catch (Exception first) {
                String reason = getErrorSummary(first, latestError[0]);
                // Instagram and other hosts may omit 'height' in their format
                // metadata. Only retry a genuinely missing-format case, not
                // a private video, DRM, login failure or rate-limit response.
                if (audio || !isFormatUnavailable(reason) || isAccessFailure(reason))
                    throw new MediaFailure(first, reason);
                reporter.phase("download");
                reporter.percent(0);
                reporter.notice = "فرمت دقیق موجود نبود؛ تلاش با کیفیت نزدیک‌تر.";
                File[] partials = folder.listFiles();
                if (partials != null) for (File partial : partials) deleteRecursive(partial);
                latestError[0] = "";
                YoutubeDLRequest fallback = mediaRequest(link, folder, false, height, true);
                try {
                    YoutubeDL.getInstance().execute(fallback,
                        "telegram-fallback-" + UUID.randomUUID(), track);
                } catch (Exception second) {
                    throw new MediaFailure(second, getErrorSummary(second, latestError[0]));
                }
            }
            reporter.phase("processing");
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
            itemTitle = BotDownloadLogStore.titleFromFilename(best.getName());
            itemSize = best.length();
            if (best.length() > MAX_UPLOAD)
                throw new IllegalStateException("فایل بیش از ۴۵ مگابایت است. کیفیت پایین‌تر را امتحان کن.");
            reporter.phase("upload");
            uploadPhase = true;
            reporter.percent(0);
            long uploadStart = SystemClock.elapsedRealtime();
            uploadDocument(chat, best, tok, reporter);
            delivered = true;
            long uploadMs = SystemClock.elapsedRealtime() - uploadStart;
            preferences().edit().putLong("last_download_ms", downloadMs)
                .putLong("last_upload_ms", uploadMs).apply();
            try { if (!isOwner(who)) userStore.recordDelivered(who); }
            catch (Exception ignored) { /* delivered file remains a success */ }
            preferences().edit().putInt("delivered", preferences().getInt("delivered", 0) + 1).apply();
            try { botStats.downloadDelivered(); } catch (Exception ignored) { }
            reporter.finish(true, "");
        } catch (Exception e) {
            if (!delivered) {
                try { botStats.downloadFailed(); } catch (Exception ignored) { }
                String detail = e instanceof MediaFailure ? ((MediaFailure)e).details :
                    getErrorSummary(e, lastExtractorIssue[0]);
                issueCategory = categoryCode(detail, uploadPhase);
                preferences().edit().putString("last_download_issue",
                    issueCategory + " · " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.US).format(new java.util.Date())).apply();
                reporter.finish(false, friendlyFailure(e, detail, uploadPhase));
            } else {
                reporter.finish(true, "");
            }
        } finally {
            // Metadata is retained for 30 days at most. No private query
            // params, Telegram message bodies or raw error text are logged.
            try {
                BotDownloadLogStore.Entry entry = new BotDownloadLogStore.Entry();
                entry.userId = who;
                entry.userLabel = senderLabel;
                entry.platform = allowedHost(link);
                entry.title = itemTitle;
                entry.publicUrl = link;
                entry.requestedFormat = audio ? "MP3" : height + "p";
                entry.status = delivered ? "success" : "failed";
                entry.issue = delivered ? "" : issueCategory;
                entry.fileBytes = delivered ? itemSize : 0;
                entry.durationMs = SystemClock.elapsedRealtime() - startedAt;
                audit.record(entry);
            } catch (Exception ignored) { }
            deleteRecursive(folder);
        }
    }

    private void uploadDocument(long chat, File file, String tok, ProgressReporter reporter)
        throws Exception {
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
                long uploaded = 0;
                long total = Math.max(1, file.length());
                while ((count = input.read(buf)) != -1) {
                    output.write(buf, 0, count);
                    uploaded += count;
                    reporter.percent(100f * uploaded / total);
                }
            }
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        reporter.phase("confirming");
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

    private void editText(long chat, int messageId, String text, String tok) throws Exception {
        editText(chat, messageId, text, null, tok);
    }
    private void editText(long chat, int messageId, String text, JSONObject markup, String tok)
        throws Exception {
        if (messageId <= 0) return;
        JSONObject params = new JSONObject()
            .put("chat_id", chat).put("message_id", messageId).put("text", text);
        // editMessageText clears the old inline keyboard when markup is absent.
        if (markup != null) params.put("reply_markup", markup);
        api("editMessageText", params, tok);
    }
    private void deleteText(long chat, int messageId, String tok) throws Exception {
        if (messageId <= 0) return;
        api("deleteMessage", new JSONObject()
            .put("chat_id", chat)
            .put("message_id", messageId), tok);
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
