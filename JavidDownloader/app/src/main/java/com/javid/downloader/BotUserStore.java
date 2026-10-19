package com.javid.downloader;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Private device database. Accounts start inactive until approved by the owner. */
public final class BotUserStore extends SQLiteOpenHelper {
    private static final String NAME = "javid_bot_users_v1.db";
    private static final int VERSION = 3;
    public BotUserStore(Context context) { super(context, NAME, null, VERSION); }

    public static final class User {
        public long id;
        public String username = "";
        public String role = "normal"; // normal, vip, admin
        public boolean active;
        public boolean blocked;
        public Integer dailyLimit; // null: use role default
        public int totalDownloads;
        public int todayDownloads;
        public String counterDay = "";
        public String source = "manual";
        public String vipExpiresOn = ""; // YYYY-MM-DD, empty = no expiry
        public boolean digestSubscribed; // voluntary Telegram private update opt-in
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE bot_users (" +
            "user_id INTEGER PRIMARY KEY,username TEXT NOT NULL DEFAULT ''," +
            "role TEXT NOT NULL DEFAULT 'normal',active INTEGER NOT NULL DEFAULT 0," +
            "blocked INTEGER NOT NULL DEFAULT 0,daily_limit INTEGER," +
            "total_downloads INTEGER NOT NULL DEFAULT 0," +
            "today_downloads INTEGER NOT NULL DEFAULT 0,counter_day TEXT NOT NULL DEFAULT ''," +
            "source TEXT NOT NULL DEFAULT 'manual',updated_at INTEGER NOT NULL DEFAULT 0," +
            "vip_expires_on TEXT NOT NULL DEFAULT ''," +
            "digest_subscribed INTEGER NOT NULL DEFAULT 0)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Non-destructive schema upgrade: preserve the existing 18 imported users,
        // their roles, quotas and counters. Existing VIP accounts remain indefinite.
        if (oldVersion < 2) db.execSQL(
            "ALTER TABLE bot_users ADD COLUMN vip_expires_on TEXT NOT NULL DEFAULT ''");
        if (oldVersion < 3) db.execSQL(
            "ALTER TABLE bot_users ADD COLUMN digest_subscribed INTEGER NOT NULL DEFAULT 0");
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
    }
    private static User from(Cursor c) {
        User u = new User();
        u.id = c.getLong(0); u.username = c.getString(1);
        u.role = c.getString(2); u.active = c.getInt(3) != 0; u.blocked = c.getInt(4) != 0;
        u.dailyLimit = c.isNull(5) ? null : c.getInt(5);
        u.totalDownloads = c.getInt(6);
        u.todayDownloads = c.getInt(7);
        u.counterDay = c.getString(8); u.source = c.getString(9);
        u.vipExpiresOn = c.getString(10);
        u.digestSubscribed = c.getInt(11) != 0;
        return u;
    }
    private static final String SELECT = "SELECT user_id,username,role,active,blocked,daily_limit," +
        "total_downloads,today_downloads,counter_day,source,vip_expires_on,digest_subscribed FROM bot_users";

    public synchronized User find(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(SELECT + " WHERE user_id=?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? from(c) : null;
        }
    }
    public synchronized User pending(long id, String name) {
        User u = find(id);
        if (u != null) return u;
        User user = new User();
        user.id = id;
        user.username = name == null ? "" : name;
        user.active = false; // never grant downloads to an unknown Telegram ID
        save(user);
        return user;
    }
    public static boolean validExpiry(String text) {
        if (text == null || text.trim().isEmpty()) return true;
        try {
            java.time.LocalDate date = java.time.LocalDate.parse(text);
            return date.getYear() >= 2020 && date.getYear() <= 2100;
        } catch (Exception invalid) { return false; }
    }
    public static boolean vipExpired(User u) {
        return u != null && "vip".equals(u.role) && u.vipExpiresOn != null &&
            !u.vipExpiresOn.isEmpty() && today().compareTo(u.vipExpiresOn) > 0;
    }
    public static String effectiveRole(User u) {
        return vipExpired(u) ? "normal" : u.role;
    }
    public static int quota(User u) {
        // Expired VIP accounts fall back to the normal daily quota. An explicitly
        // configured daily limit remains in effect until the owner changes it.
        if (u.dailyLimit != null) return Math.max(0, u.dailyLimit);
        if ("admin".equals(effectiveRole(u))) return Integer.MAX_VALUE;
        return "vip".equals(effectiveRole(u)) ? 50 : 10;
    }
    public static boolean allowed(User u) {
        return u != null && u.active && !u.blocked &&
            (u.todayDownloads < quota(u) || !today().equals(u.counterDay));
    }
    private void put(SQLiteDatabase db, User u) {
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("user_id", u.id);
        v.put("username", u.username == null ? "" : u.username);
        v.put("role", "vip".equals(u.role) || "admin".equals(u.role) ? u.role : "normal");
        v.put("active", u.active ? 1 : 0); v.put("blocked", u.blocked ? 1 : 0);
        if (u.dailyLimit == null) v.putNull("daily_limit");
        else v.put("daily_limit", Math.max(0, u.dailyLimit));
        v.put("total_downloads", Math.max(0, u.totalDownloads));
        v.put("today_downloads", Math.max(0, u.todayDownloads));
        v.put("counter_day", u.counterDay == null ? "" : u.counterDay);
        v.put("source", u.source == null ? "manual" : u.source);
        v.put("vip_expires_on", u.vipExpiresOn == null ? "" : u.vipExpiresOn);
        v.put("digest_subscribed", u.digestSubscribed ? 1 : 0);
        v.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict("bot_users", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized void save(User u) {
        if (u.id <= 0) throw new IllegalArgumentException("Invalid Telegram user id");
        if (!validExpiry(u.vipExpiresOn)) throw new IllegalArgumentException("Invalid VIP date");
        put(getWritableDatabase(), u);
    }
    public synchronized int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM bot_users", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
    public synchronized int countVip() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM bot_users WHERE role='vip' AND " +
            "(vip_expires_on='' OR vip_expires_on>=date('now','localtime'))", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
    public synchronized List<User> all() {
        List<User> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(SELECT + " ORDER BY active DESC,role DESC,user_id DESC", null)) {
            while (c.moveToNext()) result.add(from(c));
        }
        return result;
    }
    public synchronized void recordDelivered(long id) {
        User u = find(id);
        if (u == null) return;
        String day = today();
        if (!day.equals(u.counterDay)) { u.todayDownloads = 0; u.counterDay = day; }
        u.totalDownloads++;
        u.todayDownloads++;
        save(u);
    }
    /** Only active, unblocked members with an explicit private-feed opt-in. */
    public synchronized java.util.List<User> digestRecipients() {
        java.util.List<User> list = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
            SELECT + " WHERE active=1 AND blocked=0 AND digest_subscribed=1 ORDER BY user_id",
            null)) {
            while (c.moveToNext()) list.add(from(c));
        }
        return list;
    }
    public synchronized void setDigestOptIn(long userId, boolean enabled, String username) {
        User u = find(userId);
        if (u == null) u = pending(userId, username);
        u.digestSubscribed = enabled;
        save(u);
    }
    public static JSONObject toJson(User u) throws Exception {
        JSONObject row = new JSONObject();
        row.put("user_id", u.id);
        row.put("username", u.username);
        row.put("role", u.role);
        row.put("active", u.active);
        row.put("is_blocked", u.blocked);
        row.put("daily_limit", u.dailyLimit == null ? JSONObject.NULL : u.dailyLimit);
        row.put("downloads_count", u.totalDownloads);
        row.put("today_count", u.todayDownloads);
        row.put("counter_day", u.counterDay);
        row.put("vip_expires_on", u.vipExpiresOn);
        row.put("digest_subscribed", u.digestSubscribed);
        return row;
    }
    public synchronized byte[] exportJson() throws Exception {
        JSONArray users = new JSONArray();
        for (User u : all()) users.put(toJson(u));
        JSONObject root = new JSONObject().put("format", "javid-users-v1")
            .put("exported_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .format(new java.util.Date())).put("users", users);
        return root.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static User parse(JSONObject row) throws Exception {
        User u = new User();
        u.id = row.optLong("user_id", row.optLong("telegram_id", 0));
        if (u.id <= 0) throw new IllegalArgumentException("Missing user_id");
        u.username = row.optString("username", row.optString("name", ""));
        if (u.username.length() > 120) u.username = u.username.substring(0, 120);
        String role = row.optString("role", "normal").toLowerCase(Locale.ROOT);
        if (!role.equals("normal") && !role.equals("vip") && !role.equals("admin"))
            throw new IllegalArgumentException("Unknown role for user " + u.id);
        u.role = role;
        u.active = row.optBoolean("active", true);
        u.blocked = row.optBoolean("is_blocked", row.optBoolean("blocked", false));
        if (row.has("daily_limit") && !row.isNull("daily_limit")) {
            int n = row.getInt("daily_limit");
            if (n < 0 || n > 1000000) throw new IllegalArgumentException("Invalid quota");
            u.dailyLimit = n;
        }
        int count = row.optInt("downloads_count", row.optInt("total_downloads", 0));
        if (count < 0) throw new IllegalArgumentException("Invalid history count");
        u.totalDownloads = count;
        u.todayDownloads = Math.max(0, row.optInt("today_count", 0));
        u.counterDay = row.optString("counter_day", "");
        u.vipExpiresOn = row.optString("vip_expires_on", "");
        u.digestSubscribed = row.optBoolean("digest_subscribed", false);
        if (!validExpiry(u.vipExpiresOn))
            throw new IllegalArgumentException("Invalid VIP expiry");
        u.source = "import";
        return u;
    }
    // All-or-nothing import; merge users by numeric Telegram ID without resetting others.
    public synchronized int importJson(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length > 2_000_000) throw new IllegalArgumentException("File too large");
        JSONObject root = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        if (!"javid-users-v1".equals(root.optString("format")))
            throw new IllegalArgumentException("Unsupported backup format");
        JSONArray array = root.getJSONArray("users");
        if (array.length() > 5000) throw new IllegalArgumentException("Too many users");
        java.util.LinkedHashMap<Long, User> pending = new java.util.LinkedHashMap<>();
        for (int i = 0; i < array.length(); i++) {
            User u = parse(array.getJSONObject(i));
            if (pending.containsKey(u.id)) throw new IllegalArgumentException("Duplicate user " + u.id);
            pending.put(u.id, u);
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (User u : pending.values()) put(db, u);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return pending.size();
    }
}
