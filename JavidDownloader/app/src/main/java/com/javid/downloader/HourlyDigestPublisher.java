package com.javid.downloader;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Football ONLY hourly summary + live football/rain alert dispatch.
 * News headlines and ordinary weather reports have been intentionally removed.
 */
final class HourlyDigestPublisher {
    static final String ENABLED="digest_enabled";
    static final String TARGET="digest_channel";
    static final String LAST_HOUR="digest_last_hour";
    static final String LAST_SENT="digest_last_sent";
    static final String LAST_RESULT="digest_last_result";
    static final ZoneId SPAIN=ZoneId.of("Europe/Madrid");
    static final long HOUR_MS=TimeUnit.HOURS.toMillis(1);

    private final SharedPreferences prefs;
    private final BotUserStore users;
    HourlyDigestPublisher(Context context, SharedPreferences prefs, BotUserStore users) {
        this.prefs=prefs;this.users=users;
    }
    static boolean channelValid(String chat) {
        return chat!=null && (chat.matches("@[A-Za-z][A-Za-z0-9_]{4,31}") ||
            chat.matches("-100[0-9]{5,16}"));
    }
    static long millisUntilNextHour() {
        ZonedDateTime now=ZonedDateTime.now(SPAIN);
        return Math.max(1000,java.time.Duration.between(
            now,now.plusHours(1).withMinute(0).withSecond(0).withNano(0)).toMillis());
    }
    static String hourKey() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString();
    }
    static final class Report {
        String content="";
        boolean hasScores;
        int channelSent,privateSent,failed;
        String status() {
            return "کانال: "+channelSent+" | خصوصی: "+privateSent+
                " | ناموفق: "+failed;
        }
    }
    Report build() {
        Report r=new Report();
        FootballLiveTracker.Snapshot snap=new FootballLiveTracker(prefs).collect();
        r.hasScores=snap.sourcesOk>0 && !snap.games.isEmpty();
        r.content="⚽ نتایج مهم فوتبال جهان — Downloaderjavid\n"+
            "🕒 "+ZonedDateTime.now(SPAIN).format(
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.US))+
            " به وقت اسپانیا\n\n"+FootballLiveTracker.summary(snap)+
            (r.hasScores ? "\n\nبرای توقف پیام خصوصی: /digestleave" : "");
        return r;
    }
    private static String shortError(String error) {
        if(error==null)return "نامشخص";
        return error.length()>100?error.substring(0,100):error;
    }
    private static JSONObject telegram(String token,String method,JSONObject params) throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(
            "https://api.telegram.org/bot"+token+"/"+method).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(22000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type","application/json; charset=UTF-8");
        try {
            try(OutputStream out=conn.getOutputStream()) {
                out.write(params.toString().getBytes(StandardCharsets.UTF_8));
            }
            int http=conn.getResponseCode();
            InputStream input=http>=200&&http<300?conn.getInputStream():conn.getErrorStream();
            if(input==null)throw new Exception("HTTP "+http);
            try(InputStream in=input;ByteArrayOutputStream response=new ByteArrayOutputStream()){
                byte[] buf=new byte[2048];int n;
                while((n=in.read(buf))!=-1){
                    if(response.size()+n>32000)break;
                    response.write(buf,0,n);
                }
                JSONObject data=new JSONObject(response.toString("UTF-8"));
                if(!data.optBoolean("ok"))
                    throw new Exception("Telegram HTTP "+http+": "+
                        shortError(data.optString("description","ارسال ناموفق")));
                return data;
            }
        }finally{conn.disconnect();}
    }
    static void send(String token,String destination,String message) throws Exception {
        telegram(token,"sendMessage",new JSONObject()
            .put("chat_id",destination).put("text",message)
            .put("disable_web_page_preview",true));
    }
    /** Only enabled, explicitly approved/opted-in recipients and chosen channel.
     * One transient send failure never permanently disables remaining receivers.
     */
    Report sendAlert(String token,String content) {
        Report r=new Report();
        r.content=content;
        if(!prefs.getBoolean(ENABLED,false))return r;
        String channel=prefs.getString(TARGET,"");
        if(channelValid(channel)){
            try{send(token,channel,content);r.channelSent++;}
            catch(Exception ignored){r.failed++;}
        }
        List<BotUserStore.User> recipients=users.digestRecipients();
        for(BotUserStore.User u:recipients){
            if(Thread.currentThread().isInterrupted())break;
            try {
                BotUserStore.User current=users.find(u.id);
                if(current==null||!current.active||current.blocked||!current.digestSubscribed)
                    continue;
                send(token,String.valueOf(u.id),content);r.privateSent++;
            }catch(Exception ignored){r.failed++;}
            try{Thread.sleep(210);}catch(InterruptedException stopped){
                Thread.currentThread().interrupt();break;
            }
        }
        if(r.channelSent+r.privateSent>0) {
            prefs.edit().putLong(LAST_SENT,System.currentTimeMillis())
                .putString(LAST_RESULT,r.status()).apply();
        }else if(r.failed>0) {
            prefs.edit().putString(LAST_RESULT,
                "ارسال ناموفق: دسترسی بات به کانال و وضعیت کاربران را بررسی کن.").apply();
        }
        return r;
    }
    Report publish(String token,boolean manual) {
        Report r=new Report();
        if(!prefs.getBoolean(ENABLED,false)) {
            r.content="اطلاع‌رسانی خودکار خاموش است.";return r;
        }
        String hour=hourKey();
        if(!manual && hour.equals(prefs.getString(LAST_HOUR,""))) {
            r.content="گزارش این ساعت قبلاً فرستاده شد.";return r;
        }
        String channel=prefs.getString(TARGET,"");
        if(!channelValid(channel)&&users.digestRecipients().isEmpty()){
            r.content="کانال تنظیم نشده و هیچ کاربر داوطلب دریافت خصوصی نیست.";
            return r;
        }
        Report built=build();
        if(!built.hasScores) {
            // Never flood the channel with "no sources" or fabricated results.
            prefs.edit().putString(LAST_RESULT,
                "نتیجه مهم تازه‌ای نبود یا منابع در دسترس نبودند؛ پیامی منتشر نشد.").apply();
            return built;
        }
        Report sent=sendAlert(token,built.content);
        sent.hasScores=built.hasScores;
        if(sent.channelSent+sent.privateSent>0)
            prefs.edit().putString(LAST_HOUR,hour).apply();
        return sent;
    }
}