package com.javid.downloader;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** An explicitly enabled, hourly Telegram bulletin, executed by the phone's foreground bot.
 * Weather: Open-Meteo (CC BY 4.0 attribution), football: ESPN site API (best effort),
 * Iran: headline-only RSS news attribution. No scores or weather values are invented.
 */
final class HourlyDigestPublisher {
    static final String ENABLED = "digest_enabled";
    static final String TARGET = "digest_channel";
    static final String LAST_HOUR = "digest_last_hour";
    static final String LAST_SENT = "digest_last_sent";
    static final String LAST_RESULT = "digest_last_result";
    static final String SEEN_NEWS = "digest_seen_news";
    static final ZoneId SPAIN = ZoneId.of("Europe/Madrid");
    static final long HOUR_MS = TimeUnit.HOURS.toMillis(1);

    private final Context ctx;
    private final SharedPreferences prefs;
    private final BotUserStore users;

    HourlyDigestPublisher(Context context, SharedPreferences prefs, BotUserStore users) {
        this.ctx = context.getApplicationContext();
        this.prefs = prefs;
        this.users = users;
    }

    static boolean channelValid(String chat) {
        return chat != null && (chat.matches("@[A-Za-z][A-Za-z0-9_]{4,31}") ||
            chat.matches("-100[0-9]{5,16}"));
    }

    static long millisUntilNextHour() {
        ZonedDateTime now = ZonedDateTime.now(SPAIN);
        return Math.max(1000, java.time.Duration.between(now,
            now.plusHours(1).withMinute(0).withSecond(0).withNano(0)).toMillis());
    }

    static String hourKey() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString();
    }

    static final class Report {
        String content;
        final List<String> newsIds = new ArrayList<>();
        int channelSent, privateSent, failed;
        String status() {
            return "کانال: " + channelSent + " | خصوصی: " + privateSent +
                " | ناموفق: " + failed;
        }
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        value = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return value.length() <= max ? value : value.substring(0, max);
    }
    private static String getText(String url, int byteLimit) throws Exception {
        if (!url.startsWith("https://")) throw new IllegalArgumentException("HTTPS only");
        HttpURLConnection conn = (HttpURLConnection)new URL(url).openConnection();
        conn.setConnectTimeout(8500);
        conn.setReadTimeout(10500);
        conn.setRequestProperty("User-Agent", "JavidDownloader-Digest/1.9 (+Android)");
        conn.setRequestProperty("Accept", "application/json, application/rss+xml, application/xml, text/xml, */*");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IllegalStateException("Feed HTTP " + code);
            try (InputStream input = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = input.read(buf)) >= 0) {
                    if (out.size() + n > byteLimit) throw new IllegalStateException("Feed too large");
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String condition(int code) {
        if (code == 0) return "صاف ☀️";
        if (code <= 3) return "نیمه‌ابری ⛅";
        if (code == 45 || code == 48) return "مه‌آلود 🌫️";
        if (code >= 51 && code <= 67) return "بارانی 🌧️";
        if (code >= 71 && code <= 77) return "برفی ❄️";
        if (code >= 80 && code <= 82) return "رگبار 🌦️";
        if (code >= 95) return "طوفانی ⛈️";
        return "نامشخص";
    }
    private static String weather(String city, String lat, String lon, String tz) {
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                "&longitude=" + lon +
                "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&timezone=" + URLEncoder.encode(tz, "UTF-8");
            JSONObject root = new JSONObject(getText(url, 200_000));
            JSONObject cur = root.getJSONObject("current");
            if (cur.isNull("temperature_2m")) throw new Exception("No weather observation");
            String stamp = cur.optString("time", "");
            String time = stamp.contains("T") ? stamp.substring(stamp.indexOf('T') + 1) : "";
            return "📍 " + city + ": " +
                String.format(Locale.US, "%.1f", cur.getDouble("temperature_2m")) +
                "°C، " + condition(cur.optInt("weather_code", -1)) +
                " | رطوبت " + cur.optInt("relative_humidity_2m") + "٪" +
                " | باد " + Math.round(cur.optDouble("wind_speed_10m")) + " km/h" +
                (time.isEmpty() ? "" : " | " + time + " محلی");
        } catch (Exception unavailable) {
            return "📍 " + city + ": داده هواشناسی فعلاً در دسترس نیست.";
        }
    }

    private static final class Game {
        String line, key;
        int priority;
        long when;
    }
    private static String football() {
        // Public, undocumented ESPN site endpoint. Treat outages as unavailable.
        String[][] comps = {
            {"uefa.champions", "لیگ قهرمانان"},
            {"fifa.world", "جام جهانی"},
            {"eng.1", "انگلیس"},
            {"esp.1", "اسپانیا"},
            {"ger.1", "آلمان"},
            {"ita.1", "ایتالیا"},
            {"fra.1", "فرانسه"}
        };
        String from = LocalDate.now(ZoneId.of("UTC")).minusDays(1)
            .format(DateTimeFormatter.BASIC_ISO_DATE);
        String to = LocalDate.now(ZoneId.of("UTC"))
            .format(DateTimeFormatter.BASIC_ISO_DATE);
        long now = System.currentTimeMillis();
        ArrayList<Game> games = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int working = 0;
        for (String[] comp : comps) {
            try {
                String url = "https://site.api.espn.com/apis/site/v2/sports/soccer/" +
                    comp[0] + "/scoreboard?dates=" + from + "-" + to + "&limit=40";
                JSONObject root = new JSONObject(getText(url, 1_400_000));
                JSONArray events = root.optJSONArray("events");
                if (events == null) continue;
                working++;
                for (int i = 0; i < events.length(); i++) {
                    JSONObject match = events.optJSONObject(i);
                    if (match == null) continue;
                    String key = match.optString("id", "");
                    if (key.isEmpty() || !ids.add(key)) continue;
                    JSONObject status = match.optJSONObject("status");
                    JSONObject type = status == null ? null : status.optJSONObject("type");
                    String state = type == null ? "" : type.optString("state", "");
                    if (!"post".equals(state) && !"in".equals(state)) continue;
                    String date = match.optString("date", "");
                    long when;
                    try { when = Instant.parse(date).toEpochMilli(); }
                    catch (Exception ignored) { continue; }
                    // Live games, plus matches finished/started in the last 30 h.
                    if (!"in".equals(state) && (now - when > TimeUnit.HOURS.toMillis(32) ||
                        when > now + TimeUnit.HOURS.toMillis(2))) continue;
                    JSONArray rounds = match.optJSONArray("competitions");
                    JSONObject details = rounds == null ? null : rounds.optJSONObject(0);
                    JSONArray teams = details == null ? null : details.optJSONArray("competitors");
                    if (teams == null || teams.length() < 2) continue;
                    String home = "", away = "", hs = "", as = "";
                    for (int t=0; t<teams.length(); t++) {
                        JSONObject side = teams.optJSONObject(t);
                        if (side == null) continue;
                        JSONObject team = side.optJSONObject("team");
                        String name = team == null ? "" : team.optString("displayName", "");
                        if ("home".equals(side.optString("homeAway"))) {
                            home = name; hs = side.optString("score", "-");
                        } else {
                            away = name; as = side.optString("score", "-");
                        }
                    }
                    if (home.isEmpty() || away.isEmpty()) continue;
                    Game game = new Game();
                    game.key = key;
                    game.when = when;
                    game.priority = "in".equals(state) ? 0 : 1;
                    game.line = ("in".equals(state) ? "🔴 " : "⚽ ") + comp[1] +
                        ": " + trim(home, 36) + " " + hs + "–" + as + " " +
                        trim(away, 36) + ("in".equals(state) ? " (زنده)" : " (پایان)");
                    games.add(game);
                }
            } catch (Exception ignored) { /* report unavailable sections, never invent scores */ }
        }
        if (working == 0) return "⚽ نتایج فوتبال: منبع نتایج فعلاً در دسترس نیست.";
        if (games.isEmpty()) return "⚽ فوتبال: نتیجه مهم تازه‌ای در بازه بررسی‌شده ثبت نشده است.";
        games.sort((a,b) -> {
            int priority = Integer.compare(a.priority, b.priority);
            return priority != 0 ? priority : Long.compare(b.when, a.when);
        });
        StringBuilder out = new StringBuilder("⚽ نتایج مهم فوتبال جهان:");
        for (int i = 0; i < Math.min(6, games.size()); i++)
            out.append("\n").append(games.get(i).line);
        return out.toString();
    }

    private static final class Headline {
        String title, url, id;
        long published;
    }
    private static List<Headline> parseRss(String xml, boolean iranOnly) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new java.io.StringReader(xml));
        ArrayList<Headline> items = new ArrayList<>();
        Headline current = null;
        String element = "";
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT && items.size() < 60) {
            if (event == XmlPullParser.START_TAG) {
                element = parser.getName().toLowerCase(Locale.ROOT);
                if ("item".equals(element)) current = new Headline();
            } else if (event == XmlPullParser.TEXT && current != null) {
                String v = parser.getText();
                if ("title".equals(element)) current.title = (current.title == null ? "" : current.title) + v;
                if ("link".equals(element)) current.url = (current.url == null ? "" : current.url) + v;
                if ("pubdate".equals(element)) {
                    try {
                        current.published = ZonedDateTime.parse(v.trim(),
                            DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                    } catch (Exception ignored) { }
                }
            } else if (event == XmlPullParser.END_TAG) {
                String name = parser.getName().toLowerCase(Locale.ROOT);
                if ("item".equals(name) && current != null) {
                    current.title = trim(current.title, 170);
                    current.url = trim(current.url, 400);
                    if (current.title.length() >= 8 && current.url.startsWith("https://")) {
                        boolean relevant = !iranOnly || current.title.contains("ایران") ||
                            current.title.contains("تهران") || current.title.contains("جمهوری اسلامی") ||
                            current.title.contains("مجلس") || current.title.contains("خامنه‌ای") ||
                            current.title.contains("ارزی") || current.title.contains("سپاه");
                        if (relevant && current.published > 0 &&
                            System.currentTimeMillis() - current.published <
                                TimeUnit.HOURS.toMillis(36) &&
                            current.published <= System.currentTimeMillis() +
                                TimeUnit.HOURS.toMillis(1)) {
                            current.id = Integer.toHexString(
                                (current.title + "\n" + current.url).hashCode());
                            items.add(current);
                        }
                    }
                    current = null;
                }
                element = "";
            }
        }
        items.sort((a,b) -> Long.compare(b.published, a.published));
        return items;
    }

    private static List<Headline> iranNews() {
        try {
            String q = URLEncoder.encode("ایران when:1d", "UTF-8");
            String url = "https://news.google.com/rss/search?q=" + q +
                "&hl=fa&gl=IR&ceid=IR:fa";
            List<Headline> news = parseRss(getText(url, 650_000), false);
            if (!news.isEmpty()) return news;
        } catch (Exception ignored) { }
        try {
            // Fallback carries some worldwide headlines: select Iran-related only.
            return parseRss(getText("https://feeds.bbci.co.uk/persian/rss.xml", 650_000), true);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static Set<String> seen(SharedPreferences prefs) {
        Set<String> result = new LinkedHashSet<>();
        String raw = prefs.getString(SEEN_NEWS, "");
        for (String row : raw.split(",")) if (!row.isEmpty()) result.add(row);
        return result;
    }
    private static void remember(SharedPreferences prefs, List<String> fresh) {
        Set<String> all = seen(prefs);
        all.addAll(fresh);
        while (all.size() > 120) {
            String first = all.iterator().next();
            all.remove(first);
        }
        prefs.edit().putString(SEEN_NEWS, android.text.TextUtils.join(",", all)).apply();
    }

    Report build() {
        Report report = new Report();
        ZonedDateTime date = ZonedDateTime.now(SPAIN);
        String when = date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.US));
        StringBuilder body = new StringBuilder("📰 خبرنامه ساعتی Downloaderjavid\n" +
            "🕒 " + when + " به وقت اسپانیا\n\n" +
            "🌦 آب‌وهوا (Open-Meteo)\n" +
            weather("سالامانکا، اسپانیا", "40.9701", "-5.6639", "Europe/Madrid") + "\n" +
            weather("اردبیل، ایران", "38.2498", "48.2933", "Asia/Tehran") + "\n\n" +
            football() + "\n\n" +
            "🗞 تیترهای تازه درباره ایران:\n");
        List<Headline> headlines = iranNews();
        Set<String> deliveredBefore = seen(prefs);
        int shown = 0;
        for (Headline h : headlines) {
            if (deliveredBefore.contains(h.id)) continue;
            if (shown++ >= 3) break;
            body.append("• ").append(trim(h.title, 128)).append("\n")
                .append(h.url).append("\n");
            report.newsIds.add(h.id);
        }
        if (shown == 0) body.append("خبر تازه و تأییدشده‌ای از منابع فعلی دریافت نشد.\n");
        body.append("\nمنابع: Open-Meteo، ESPN، Google News / BBC Persian.")
            .append("\nربات ممکن است با تأخیر منابع روبه‌رو شود؛ لینک‌ها را بررسی کنید.")
            .append("\n🔕 توقف پیام خصوصی: /digestleave");
        report.content = body.length() > 3500 ? body.substring(0, 3490) + "…" : body.toString();
        return report;
    }

    private static JSONObject telegram(String token, String method, JSONObject payload) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(
            "https://api.telegram.org/bot" + token + "/" + method).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(23000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream input = code >= 200 && code < 300 ?
                conn.getInputStream() : conn.getErrorStream();
            if (input == null) throw new Exception("Telegram " + code);
            try (InputStream stream = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n=stream.read(buf))>=0) {
                    if (bytes.size()+n > 35000) break;
                    bytes.write(buf,0,n);
                }
                JSONObject result = new JSONObject(bytes.toString("UTF-8"));
                if (!result.optBoolean("ok")) {
                    // No token or private user data in returned error.
                    throw new Exception("Telegram " + code + ": " +
                        trim(result.optString("description", "failed"), 100));
                }
                return result;
            }
        } finally { conn.disconnect(); }
    }
    static void send(String token, String destination, String content) throws Exception {
        telegram(token, "sendMessage", new JSONObject().put("chat_id", destination)
            .put("text", content).put("disable_web_page_preview", true));
    }

    /** Send only when enabled with at least one configured, consented destination.
     * On restart the last successful hour prevents duplicate channel updates.
     */
    Report publish(String token, boolean forced) {
        Report report = new Report();
        if (!prefs.getBoolean(ENABLED, false)) {
            report.content = "خبرنامه خاموش است.";
            return report;
        }
        String hour = hourKey();
        if (!forced && hour.equals(prefs.getString(LAST_HOUR, ""))) {
            report.content = "خبرنامه این ساعت قبلاً ارسال شده است.";
            return report;
        }
        String channel = prefs.getString(TARGET, "");
        List<BotUserStore.User> recipients = users.digestRecipients();
        if (!channelValid(channel) && recipients.isEmpty()) {
            report.content = "مقصد معتبر تنظیم نشده و عضو خصوصی داوطلبی وجود ندارد.";
            prefs.edit().putString(LAST_RESULT, report.content).apply();
            return report;
        }
        Report built = build();
        report.content = built.content;
        report.newsIds.addAll(built.newsIds);
        if (channelValid(channel)) {
            try {
                send(token, channel, report.content);
                report.channelSent++;
            } catch (Exception ignored) {
                report.failed++;
            }
        }
        for (BotUserStore.User u : recipients) {
            try {
                // Check again in case recipient opted out while a long digest was prepared.
                BotUserStore.User now = users.find(u.id);
                if (now == null || !now.active || now.blocked || !now.digestSubscribed)
                    continue;
                send(token, String.valueOf(u.id), report.content);
                report.privateSent++;
            } catch (Exception ignored) {
                report.failed++;
            }
            try { Thread.sleep(250); }
            catch (InterruptedException stop) {
                Thread.currentThread().interrupt(); break;
            }
        }
        if (report.channelSent + report.privateSent > 0) {
            prefs.edit().putString(LAST_HOUR, hour)
                .putLong(LAST_SENT, System.currentTimeMillis())
                .putString(LAST_RESULT, report.status()).apply();
            remember(prefs, report.newsIds);
        } else prefs.edit().putString(LAST_RESULT,
            "ارسال ناموفق بود؛ دسترسی مدیر بات به کانال یا گیرنده‌ها را بررسی کنید.").apply();
        return report;
    }
}