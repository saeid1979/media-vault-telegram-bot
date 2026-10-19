package com.javid.downloader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Owner-only, on-device audit of downloads requested through this bot instance.
 * Intentionally excludes full Telegram messages, raw extractor diagnostics,
 * cookies, credentials and URL query strings (except YouTube's public video id).
 * Retains 30 days / at most 1000 recent records. Never uploads audit history.
 */
public final class BotDownloadLogStore extends SQLiteOpenHelper {
    private static final String DB = "javid_download_audit_v1.db";
    private static final int VERSION = 1;
    private static final long RETENTION_MS = 30L * 86400_000L;
    private static final int LIMIT = 1000;

    public static final class Entry {
        public long createdAt, userId, fileBytes, durationMs;
        public String userLabel = "", platform = "", title = "", publicUrl = "";
        public String requestedFormat = "", status = "", issue = "";
    }
    public static final class Summary {
        public int total, successful, failed;
        public long deliveredBytes;
        public final HashMap<String, Integer> byPlatform = new HashMap<>();
    }

    public BotDownloadLogStore(Context context) { super(context, DB, null, VERSION); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE audit (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "created_at INTEGER NOT NULL," +
            "telegram_id INTEGER NOT NULL," +
            "user_label TEXT NOT NULL DEFAULT ''," +
            "platform TEXT NOT NULL DEFAULT ''," +
            "media_title TEXT NOT NULL DEFAULT ''," +
            "public_url TEXT NOT NULL DEFAULT ''," +
            "requested_format TEXT NOT NULL DEFAULT ''," +
            "status TEXT NOT NULL," +
            "issue TEXT NOT NULL DEFAULT ''," +
            "file_bytes INTEGER NOT NULL DEFAULT 0," +
            "duration_ms INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX audit_recent ON audit(created_at DESC)");
        db.execSQL("CREATE INDEX audit_user ON audit(telegram_id,created_at DESC)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    private static String clean(String text, int limit) {
        if (text == null) return "";
        String value = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
            .replaceAll("[\\p{Cntrl}]", "").trim();
        if (value.length() > limit) value = value.substring(0, limit);
        return value;
    }
    public static String titleFromFilename(String name) {
        String value = clean(name, 240);
        // yt-dlp naming scheme: %(title).110B [%(id)s].%(ext)s
        value = value.replaceFirst(" \\[[^\\]]{1,100}\\]\\.[A-Za-z0-9]{1,8}$", "");
        if (value.equals(name)) value = value.replaceFirst("\\.[a-zA-Z0-9]{1,8}$", "");
        return clean(value, 150);
    }
    public static String safePublicUrl(String original) {
        try {
            Uri uri = Uri.parse(original);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
                return "";
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            // Only supported public providers. Never include credentials/fragment
            // or arbitrary query parameters (which may contain private tokens).
            String[] domains = {"youtube.com", "youtu.be", "instagram.com",
                "facebook.com", "fb.watch", "tiktok.com", "x.com", "twitter.com",
                "pinterest.com", "pin.it", "vimeo.com", "reddit.com", "redd.it",
                "dailymotion.com", "dai.ly", "twitch.tv"};
            boolean supported = false;
            for (String d : domains) {
                if (host.equals(d) || host.endsWith("." + d)) { supported = true; break; }
            }
            if (!supported) return "";
            String path = uri.getEncodedPath();
            if (path == null) path = "";
            if (path.length() > 280) path = path.substring(0, 280);
            StringBuilder sanitized = new StringBuilder("https://").append(host).append(path);
            if (host.equals("youtube.com") || host.endsWith(".youtube.com")) {
                String id = uri.getQueryParameter("v");
                if (id != null && id.matches("[A-Za-z0-9_-]{8,16}"))
                    sanitized.append("?v=").append(id);
            }
            return clean(sanitized.toString(), 400);
        } catch (Exception ignored) { return ""; }
    }
    public synchronized void record(Entry item) {
        if (item == null || item.userId <= 0) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("created_at", item.createdAt <= 0 ? System.currentTimeMillis() : item.createdAt);
            v.put("telegram_id", item.userId);
            v.put("user_label", clean(item.userLabel, 100));
            v.put("platform", clean(item.platform, 32));
            v.put("media_title", clean(item.title, 150));
            v.put("public_url", safePublicUrl(item.publicUrl));
            v.put("requested_format", clean(item.requestedFormat, 24));
            v.put("status", "success".equals(item.status) ? "success" : "failed");
            v.put("issue", clean(item.issue, 40));
            v.put("file_bytes", Math.max(0, item.fileBytes));
            v.put("duration_ms", Math.max(0, item.durationMs));
            db.insertOrThrow("audit", null, v);
            prune(db);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private static void prune(SQLiteDatabase db) {
        db.delete("audit", "created_at < ?",
            new String[]{String.valueOf(System.currentTimeMillis() - RETENTION_MS)});
        db.execSQL("DELETE FROM audit WHERE id NOT IN " +
            "(SELECT id FROM audit ORDER BY created_at DESC,id DESC LIMIT " + LIMIT + ")");
    }
    private static Entry read(Cursor c) {
        Entry v = new Entry();
        v.createdAt = c.getLong(0);
        v.userId = c.getLong(1);
        v.userLabel = c.getString(2);
        v.platform = c.getString(3);
        v.title = c.getString(4);
        v.publicUrl = c.getString(5);
        v.requestedFormat = c.getString(6);
        v.status = c.getString(7);
        v.issue = c.getString(8);
        v.fileBytes = c.getLong(9);
        v.durationMs = c.getLong(10);
        return v;
    }
    public synchronized List<Entry> recent(int count, String filter) {
        String where = " WHERE created_at >= ?";
        ArrayList<String> args = new ArrayList<>();
        args.add(String.valueOf(System.currentTimeMillis() - RETENTION_MS));
        if ("success".equals(filter) || "failed".equals(filter)) {
            where += " AND status=?";
            args.add(filter);
        }
        int bounded = Math.max(1, Math.min(count, 250));
        String sql = "SELECT created_at,telegram_id,user_label,platform,media_title," +
            "public_url,requested_format,status,issue,file_bytes,duration_ms " +
            "FROM audit" + where + " ORDER BY created_at DESC,id DESC LIMIT " + bounded;
        List<Entry> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]))) {
            while (c.moveToNext()) result.add(read(c));
        }
        return result;
    }
    /** Self-service deletion of one Telegram user's detailed 30-day activity. */
    public synchronized int deleteForUser(long userId) {
        if (userId <= 0) return 0;
        return getWritableDatabase().delete("audit", "telegram_id=?",
            new String[]{String.valueOf(userId)});
    }
    public synchronized Summary summary() {
        Summary sum = new Summary();
        String after = String.valueOf(System.currentTimeMillis() - RETENTION_MS);
        try (Cursor c = getReadableDatabase().rawQuery(
            "SELECT status,COUNT(*),COALESCE(SUM(file_bytes),0) " +
            "FROM audit WHERE created_at >= ? GROUP BY status", new String[]{after})) {
            while (c.moveToNext()) {
                int n = c.getInt(1);
                sum.total += n;
                if ("success".equals(c.getString(0))) {
                    sum.successful += n;
                    sum.deliveredBytes += c.getLong(2);
                } else sum.failed += n;
            }
        }
        try (Cursor c = getReadableDatabase().rawQuery(
            "SELECT platform,COUNT(*) FROM audit WHERE created_at >= ? " +
            "GROUP BY platform ORDER BY COUNT(*) DESC", new String[]{after})) {
            while (c.moveToNext())
                sum.byPlatform.put(c.getString(0), c.getInt(1));
        }
        return sum;
    }
}
