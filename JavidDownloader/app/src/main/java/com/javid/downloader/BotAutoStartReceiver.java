package com.javid.downloader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Resumes a previously user-enabled, visible Telegram foreground bot after
 * a completed device boot or application upgrade. Never silently enables a
 * bot that its owner has manually switched off.
 */
public final class BotAutoStartReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
            !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) &&
            !Intent.ACTION_USER_UNLOCKED.equals(action)) return;
        SharedPreferences prefs = context.getSharedPreferences(
            TelegramBotService.PREFS, Context.MODE_PRIVATE);
        // One-time migration: a running pre-2.1 bot had only 'enabled'.
        // Preserve that owner's existing choice when the APK is upgraded.
        if (!prefs.contains(TelegramBotService.PREF_DESIRED) &&
            prefs.getBoolean("enabled", false) && BotSecrets.hasToken(context))
            prefs.edit().putBoolean(TelegramBotService.PREF_DESIRED, true).apply();
        if (!prefs.getBoolean(TelegramBotService.PREF_AUTO_RESUME, true) ||
            !prefs.getBoolean(TelegramBotService.PREF_DESIRED, false)) return;
        if (!BotSecrets.hasToken(context)) return;
        try {
            Intent service = new Intent(context, TelegramBotService.class);
            service.setAction(TelegramBotService.ACTION_RESUME);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        } catch (Exception e) {
            // Android/OEM autostart restrictions can still forbid background
            // FGS starts. Remember the error for display when owner opens app.
            prefs.edit().putString("auto_resume_issue",
                "تلاش خودکار برای راه‌اندازی بات توسط اندروید محدود شد (" +
                    e.getClass().getSimpleName() + ").").apply();
        }
    }
}
