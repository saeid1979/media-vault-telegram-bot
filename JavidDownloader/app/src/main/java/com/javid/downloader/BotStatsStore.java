package com.javid.downloader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Local, aggregate-only analytics. Stores counts by day, never links, chat
 * texts, usernames, Telegram IDs, media content or bot tokens.
 */
public final class BotStatsStore extends SQLiteOpenHelper {
    private static final String DATABASE = "javid_bot_stats_v1.db";
    private static final int DB_VERSION = 1;

    public static final class Day {
        public String date;
        public int downloadOk;
        public int downloadFailed;
        public int messageOk;
        public int messageFailed;
        public int announcementBatches;
        public int totalDownloads() { return downloadOk + downloadFailed; }
        public int totalMessages() { return messageOk + messageFailed; }
    }

    public BotStatsStore(Context ctx) { super(ctx, DATABASE, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE daily_stats (" +
            "day TEXT PRIMARY KEY NOT NULL, " +
            "download_ok INTEGER NOT NULL DEFAULT 0, " +
            "download_failed INTEGER NOT NULL DEFAULT 0, " +
            "message_ok INTEGER NOT NULL DEFAULT 0, " +
            "message_failed INTEGER NOT NULL DEFAULT 0, " +
            "announcement_batches INTEGER NOT NULL DEFAULT 0)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // No upgrades needed in this first version.
    }

    private void increment(String column) {
        // Column name is chosen exclusively from constant strings in this class.
        String today = LocalDate.now().toString();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues day = new ContentValues();
            day.put("day", today);
            db.insertWithOnConflict("daily_stats", null, day,
                SQLiteDatabase.CONFLICT_IGNORE);
            db.execSQL("UPDATE daily_stats SET " + column + " = " + column +
                " + 1 WHERE day = ?", new Object[]{today});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void downloadDelivered() { increment("download_ok"); }
    public synchronized void downloadFailed() { increment("download_failed"); }
    public synchronized void messageDelivered() { increment("message_ok"); }
    public synchronized void messageFailed() { increment("message_failed"); }
    public synchronized void announcementStarted() { increment("announcement_batches"); }

    private static final String SELECT =
        "SELECT day,download_ok,download_failed,message_ok,message_failed," +
        "announcement_batches FROM daily_stats";
    private static Day from(Cursor c) {
        Day d = new Day();
        d.date = c.getString(0);
        d.downloadOk = c.getInt(1);
        d.downloadFailed = c.getInt(2);
        d.messageOk = c.getInt(3);
        d.messageFailed = c.getInt(4);
        d.announcementBatches = c.getInt(5);
        return d;
    }

    public synchronized List<Day> lastDays(int count) {
        int n = Math.max(1, Math.min(31, count));
        java.util.HashMap<String, Day> available = new java.util.HashMap<>();
        LocalDate first = LocalDate.now().minusDays(n - 1);
        try (Cursor c = getReadableDatabase().rawQuery(
            SELECT + " WHERE day >= ? ORDER BY day",
            new String[]{first.toString()})) {
            while (c.moveToNext()) {
                Day entry = from(c);
                available.put(entry.date, entry);
            }
        }
        List<Day> result = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            String date = LocalDate.now().minusDays(i).toString();
            Day d = available.get(date);
            if (d == null) {
                d = new Day(); d.date = date;
            }
            result.add(d);
        }
        return result;
    }

    public synchronized Day totals() {
        Day sum = new Day();
        sum.date = "از آغاز ثبت آمار نسخه 1.7.0";
        try (Cursor c = getReadableDatabase().rawQuery(
            "SELECT COALESCE(SUM(download_ok),0), " +
            "COALESCE(SUM(download_failed),0), COALESCE(SUM(message_ok),0), " +
            "COALESCE(SUM(message_failed),0), COALESCE(SUM(announcement_batches),0) " +
            "FROM daily_stats", null)) {
            if (c.moveToFirst()) {
                sum.downloadOk = c.getInt(0);
                sum.downloadFailed = c.getInt(1);
                sum.messageOk = c.getInt(2);
                sum.messageFailed = c.getInt(3);
                sum.announcementBatches = c.getInt(4);
            }
        }
        return sum;
    }

    public synchronized void purgeBefore(int days) {
        String cutoff = LocalDate.now().minusDays(Math.max(31, days)).toString();
        getWritableDatabase().delete("daily_stats", "day < ?", new String[]{cutoff});
    }
}
