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
    private final TeamFavoritesStore favorites;
    private final WeatherCityStore weatherCities;
    HourlyDigestPublisher(Context context, SharedPreferences prefs, BotUserStore users,
                          TeamFavoritesStore favorites, WeatherCityStore weatherCities) {
        this.prefs=prefs;this.users=users;this.favorites=favorites;
        this.weatherCities=weatherCities;
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
        FootballLiveTracker.Snapshot snapshot;
        int channelSent,privateSent,failed;
        String status() {
            return "کانال: "+channelSent+" | خصوصی: "+privateSent+
                " | ناموفق: "+failed;
        }
    }
    Report build() {
        Report r=new Report();
        FootballLiveTracker.Snapshot snap=new FootballLiveTracker(prefs).collect();
        r.snapshot=snap;
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
        Report result=new Report();
        if(!prefs.getBoolean(ENABLED,false)) {
            result.content="اطلاع‌رسانی خودکار خاموش است.";
            return result;
        }
        String hour=hourKey();
        if(!manual && hour.equals(prefs.getString(LAST_HOUR,""))) {
            result.content="گزارش این ساعت قبلاً فرستاده شد.";
            return result;
        }
        String channel=prefs.getString(TARGET,"");
        List<BotUserStore.User> recipients=users.digestRecipients();
        if(!channelValid(channel)&&recipients.isEmpty()){
            result.content="کانال تنظیم نشده و هیچ کاربر داوطلب دریافت خصوصی نیست.";
            return result;
        }
        Report built=build();
        if(!built.hasScores) {
            prefs.edit().putString(LAST_RESULT,
                "نتیجه مهم تازه‌ای نبود یا منابع در دسترس نبودند؛ پیامی منتشر نشد.").apply();
            return built;
        }
        result.content=built.content;
        result.snapshot=built.snapshot;
        result.hasScores=true;
        if(channelValid(channel)) {
            try {send(token,channel,built.content);result.channelSent++;}
            catch(Exception failure){result.failed++;}
        }
        for(BotUserStore.User member:recipients){
            if(Thread.currentThread().isInterrupted())break;
            try {
                BotUserStore.User current=users.find(member.id);
                if(current==null||!current.active||current.blocked||
                    !current.digestSubscribed)continue;
                String msg=built.content;
                if(favorites.favoritesOnly(current.id)) {
                    msg=personalizedFootball(built.snapshot,favorites.ids(current.id));
                    if(msg.isEmpty())continue;
                }
                send(token,String.valueOf(current.id),msg);
                result.privateSent++;
            }catch(Exception failure){result.failed++;}
            try{Thread.sleep(210);}catch(InterruptedException stopped){
                Thread.currentThread().interrupt();
                break;
            }
        }
        if(result.channelSent+result.privateSent>0) {
            prefs.edit().putString(LAST_HOUR,hour)
                .putLong(LAST_SENT,System.currentTimeMillis())
                .putString(LAST_RESULT,result.status()).apply();
        }else {
            // No favorite team match this hour is expected, not a transmission error.
            prefs.edit().putString(LAST_HOUR,hour)
                .putString(LAST_RESULT,result.failed>0 ?
                    "خطای ارسال خبرنامه" : "تیم محبوبی در نتایج این ساعت نبود").apply();
        }
        return result;
    }

    /** City-specific alerts are private opt-in messages.
     * The existing public channel still receives Salamanca and Ardabil only.
     * A user with no customized cities retains those original two defaults.
     */
    Report sendRainEvent(String token,RainAlerts.RainEvent event) {
        Report report=new Report();
        report.content=event.text;
        if(!prefs.getBoolean(ENABLED,false))return report;
        String channel=prefs.getString(TARGET,"");
        if(("salamanca".equals(event.city.id)||"ardabil".equals(event.city.id)) &&
            channelValid(channel)) {
            try{send(token,channel,event.text);report.channelSent++;}
            catch(Exception ignored){report.failed++;}
        }
        for(BotUserStore.User candidate:users.digestRecipients()) {
            if(Thread.currentThread().isInterrupted())break;
            try {
                BotUserStore.User current=users.find(candidate.id);
                if(current==null || !current.active || current.blocked ||
                    !current.digestSubscribed)continue;
                if(!weatherCities.wants(current.id,event.city.id))continue;
                send(token,String.valueOf(current.id),event.text);
                report.privateSent++;
            }catch(Exception ignored){report.failed++;}
            try{Thread.sleep(210);}catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();break;
            }
        }
        if(report.channelSent+report.privateSent>0)
            prefs.edit().putLong(LAST_SENT,System.currentTimeMillis())
                .putString(LAST_RESULT,report.status()).apply();
        else if(report.failed>0)
            prefs.edit().putString(LAST_RESULT,"ارسال هشدار آب‌وهوا ناموفق بود.").apply();
        return report;
    }

    /** Live goal/final events are dispatched to the selected team subscribers
     * only. A configured public channel still gets ALL important results.
     * Rain notifications use sendAlert(), which is intentionally unfiltered.
     */
    Report sendFootballAlert(String token, FootballLiveTracker.FootballAlert event) {
        Report r=new Report();
        r.content=event.text;
        if(!prefs.getBoolean(ENABLED,false))return r;
        String channel=prefs.getString(TARGET,"");
        if(channelValid(channel)){
            try {send(token,channel,event.text);r.channelSent++;}
            catch(Exception failure){r.failed++;}
        }
        for(BotUserStore.User user:users.digestRecipients()) {
            if(Thread.currentThread().isInterrupted())break;
            try {
                BotUserStore.User current=users.find(user.id);
                if(current==null||!current.active||current.blocked||
                    !current.digestSubscribed)continue;
                if(!favorites.relevant(user.id,event.match))continue;
                send(token,String.valueOf(user.id),event.text);
                r.privateSent++;
            }catch(Exception failure){r.failed++;}
            try{Thread.sleep(210);}catch(InterruptedException stopped){
                Thread.currentThread().interrupt();
                break;
            }
        }
        if(r.channelSent+r.privateSent>0)
            prefs.edit().putLong(LAST_SENT,System.currentTimeMillis())
                .putString(LAST_RESULT,r.status()).apply();
        else if(r.failed>0)
            prefs.edit().putString(LAST_RESULT,"ارسال اعلان فوتبال ناموفق بود.").apply();
        return r;
    }

    private static String personalizedFootball(FootballLiveTracker.Snapshot snap,
                                               java.util.Set<String> selected) {
        StringBuilder text=new StringBuilder("⭐ نتایج تیم‌های محبوب شما\n");
        int matches=0;
        for(FootballLiveTracker.Match match:snap.games){
            if(!TeamFavoritesStore.includes(selected,match))continue;
            if(matches++>=12)break;
            text.append("\n").append(match.line()).append("\n");
        }
        if(matches==0)return "";
        text.append("\n🔕 لغو اشتراک: /digestleave");
        return text.toString();
    }

}