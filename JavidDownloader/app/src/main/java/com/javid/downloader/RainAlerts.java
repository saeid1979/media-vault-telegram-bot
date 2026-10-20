package com.javid.downloader;

import android.content.SharedPreferences;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Weather alerts only on verified rain transitions in Salamanca and Ardabil.
 * Source: Open-Meteo current observations/model estimates.
 */
final class RainAlerts {
    static final String STATUS="rain_last_status";
    static final String CHECK="rain_last_check";
    private final SharedPreferences prefs;
    RainAlerts(SharedPreferences prefs) { this.prefs=prefs; }
    private static final class Place {
        final String name,lat,lon,zone,key;
        Place(String name,String lat,String lon,String zone,String key){
            this.name=name;this.lat=lat;this.lon=lon;this.zone=zone;this.key=key;
        }
    }
    private static final Place[] PLACES={
        new Place("سالامانکا، اسپانیا","40.9701","-5.6639","Europe/Madrid","salamanca"),
        new Place("اردبیل، ایران","38.2498","48.2933","Asia/Tehran","ardabil")
    };
    static final class Report {
        List<String> notices=new ArrayList<>();
        List<String> observations=new ArrayList<>();
        int success,failed;
    }
    private static boolean rainCode(int code) {
        return (code>=51&&code<=67)||(code>=80&&code<=82)||
            code==95||code==96||code==99;
    }
    private static String localTime(String zone) {
        return ZonedDateTime.now(ZoneId.of(zone))
            .format(DateTimeFormatter.ofPattern("HH:mm",Locale.US));
    }
    Report check(boolean updateState) {
        Report r=new Report();
        for(Place place:PLACES) {
            try {
                String path="https://api.open-meteo.com/v1/forecast?latitude="+place.lat+
                    "&longitude="+place.lon+
                    "&current=temperature_2m,rain,showers,precipitation,weather_code"+
                    "&timezone="+URLEncoder.encode(place.zone,"UTF-8");
                JSONObject current=FootballLiveTracker.getJson(path,180000)
                    .getJSONObject("current");
                double precip=current.optDouble("precipitation",0);
                double rain=current.optDouble("rain",0);
                double showers=current.optDouble("showers",0);
                int code=current.optInt("weather_code",-1);
                boolean wet=(Double.isFinite(precip)&&precip>0.05)||
                    (Double.isFinite(rain)&&rain>0.05)||
                    (Double.isFinite(showers)&&showers>0.05)||
                    rainCode(code);
                String summary=place.name+": "+(wet?"بارانی 🌧️":"بدون بارندگی ثبت‌شده")+
                    " | "+String.format(Locale.US,"%.1f",current.optDouble("temperature_2m",0))+
                    "°C | "+localTime(place.zone);
                r.observations.add(summary);
                r.success++;
                String state="rain_wet_"+place.key;
                boolean wasWet=prefs.getBoolean(state,false);
                if(wet && !wasWet) {
                    r.notices.add("🌧️ هشدار بارندگی Downloaderjavid\n"+
                        "📍 "+place.name+"\n"+
                        "الان باران در حال باریدن است یا وضعیت فعلی هوا بارندگی را نشان می‌دهد.\n"+
                        "🌡 دما: "+String.format(Locale.US,"%.1f",current.optDouble("temperature_2m",0))+"°C"+
                        "\n🕒 ساعت محلی: "+localTime(place.zone)+
                        "\nمنبع هواشناسی: Open-Meteo");
                }
                if(updateState)prefs.edit().putBoolean(state,wet).apply();
            }catch(Exception ignored){
                r.failed++;
                r.observations.add(place.name+": اطلاعات فعلی در دسترس نیست.");
            }
        }
        if(updateState)prefs.edit().putString(STATUS,
            "شهرهای پاسخگو: "+r.success+" از ۲ | هشدار تازه: "+r.notices.size())
            .putLong(CHECK,System.currentTimeMillis()).apply();
        return r;
    }
}