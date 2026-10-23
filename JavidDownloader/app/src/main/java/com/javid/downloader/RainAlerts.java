package com.javid.downloader;

import android.content.SharedPreferences;
import org.json.JSONObject;
import org.json.JSONArray;
import java.net.URLEncoder;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Optional city-specific current rain and imminent-rain notices.
 * Open-Meteo reports model estimates, not local physical rain sensors.
 * Previous Salamanca/Ardabil defaults are retained for legacy subscribers.
 */
final class RainAlerts {
    static final String STATUS="rain_last_status";
    static final String CHECK="rain_last_check";
    private final SharedPreferences prefs;
    RainAlerts(SharedPreferences prefs){this.prefs=prefs;}
    static final class RainEvent {
        final WeatherCityStore.City city;
        final String text;
        final boolean imminent;
        RainEvent(WeatherCityStore.City city,String text,boolean imminent){
            this.city=city;this.text=text;this.imminent=imminent;
        }
    }
    static final class Report {
        final List<String> notices=new ArrayList<>();
        final List<RainEvent> events=new ArrayList<>();
        final List<String> observations=new ArrayList<>();
        int success,failed;
    }
    private static boolean rainyCode(int code){
        return (code>=51&&code<=67)||(code>=80&&code<=82)||
            code==95||code==96||code==99;
    }
    private static String time(String zone){
        try{return ZonedDateTime.now(ZoneId.of(zone))
            .format(DateTimeFormatter.ofPattern("HH:mm",Locale.US));}
        catch(Exception invalid){return "--:--";}
    }
    Report check(boolean update){
        return check(update,Arrays.asList(WeatherCityStore.PRESETS[0],
            WeatherCityStore.PRESETS[1]));
    }
    Report check(boolean update,Collection<WeatherCityStore.City> monitored){
        Report report=new Report();
        int total=0;
        for(WeatherCityStore.City city:monitored){
            if(total++>=WeatherCityStore.MAX_CHECK_CITIES)break;
            if(city==null||!city.valid())continue;
            try {
                String endpoint="https://api.open-meteo.com/v1/forecast?latitude="+city.lat+
                    "&longitude="+city.lon+
                    "&current=temperature_2m,rain,showers,precipitation,weather_code"+
                    "&hourly=precipitation_probability,precipitation"+
                    "&forecast_hours=3&timezone="+URLEncoder.encode(city.zone,"UTF-8");
                JSONObject weather=FootballLiveTracker.getJson(endpoint,200_000);
                JSONObject current=weather.getJSONObject("current");
                double rain=current.optDouble("rain",0);
                double showers=current.optDouble("showers",0);
                double precip=current.optDouble("precipitation",0);
                double temperature=current.optDouble("temperature_2m",Double.NaN);
                int code=current.optInt("weather_code",-1);
                boolean wet=(Double.isFinite(precip)&&precip>=0.1)||
                    (Double.isFinite(rain)&&rain>=0.1)||
                    (Double.isFinite(showers)&&showers>=0.1)||rainyCode(code);
                JSONObject hourly=weather.optJSONObject("hourly");
                JSONArray probability=hourly==null?null:
                    hourly.optJSONArray("precipitation_probability");
                JSONArray amount=hourly==null?null:hourly.optJSONArray("precipitation");
                boolean imminent=false;
                if(!wet && probability!=null && amount!=null) {
                    // Rain forecast within the next two hours.
                    int limit=Math.min(3,Math.min(probability.length(),amount.length()));
                    for(int i=0;i<limit;i++){
                        double chance=probability.optDouble(i,0);
                        double mm=amount.optDouble(i,0);
                        if(chance>=75 && mm>=0.3) {imminent=true;break;}
                    }
                }
                report.success++;
                String heat=Double.isFinite(temperature)?
                    String.format(Locale.US,"%.1f",temperature)+"°C":"نامشخص";
                report.observations.add(city.name+": "+
                    (wet?"بارانی 🌧":imminent?"احتمال بارندگی در ساعت‌های آینده":"بدون هشدار")+
                    " | "+heat+" | "+time(city.zone));
                String oldWetKey="rain_wet_"+city.id;
                String oldSoonKey="rain_soon_"+city.id;
                boolean priorWet=prefs.getBoolean(oldWetKey,false);
                boolean priorSoon=prefs.getBoolean(oldSoonKey,false);
                if(wet&&!priorWet) {
                    String msg="🌧️ هشدار آغاز بارندگی Downloaderjavid\n"+
                        "📍 "+city.name+"\n"+
                        "اطلاعات فعلی Open-Meteo بارندگی را نشان می‌دهد.\n"+
                        "🌡 دما: "+heat+
                        "\n🕒 ساعت محلی: "+time(city.zone)+
                        "\n🔎 منبع: Open-Meteo";
                    report.notices.add(msg);
                    report.events.add(new RainEvent(city,msg,false));
                }else if(imminent&&!priorSoon&&!priorWet){
                    String msg="☔ هشدار احتمال بارندگی در دو ساعت آینده\n"+
                        "📍 "+city.name+"\n"+
                        "احتمال بارش در پیش‌بینی Open-Meteo بالا گزارش شده است.\n"+
                        "🌡 دما: "+heat+
                        "\n🕒 ساعت محلی: "+time(city.zone)+
                        "\n🔎 پیش‌بینی است، نه مشاهده قطعی بارش.";
                    report.notices.add(msg);
                    report.events.add(new RainEvent(city,msg,true));
                }
                if(update){
                    prefs.edit().putBoolean(oldWetKey,wet)
                        .putBoolean(oldSoonKey,imminent).apply();
                }
            }catch(Exception unavailable){
                report.failed++;
                report.observations.add(city.name+": اطلاعات معتبر فعلاً در دسترس نیست.");
            }
        }
        if(update) prefs.edit().putString(STATUS,
            "شهرهای پاسخگو: "+report.success+" از "+(report.success+report.failed)+
                " | هشدار تازه: "+report.events.size())
            .putLong(CHECK,System.currentTimeMillis()).apply();
        return report;
    }
}
