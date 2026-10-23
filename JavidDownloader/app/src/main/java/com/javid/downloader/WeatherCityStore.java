package com.javid.downloader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit city-level opt-in preferences; never saves an exact device location.
 * Users without custom settings continue to receive the old two-city notices.
 */
final class WeatherCityStore extends SQLiteOpenHelper {
    private static final String NAME="javid_bot_weather_cities_v1.db";
    private static final int VERSION=1;
    static final int MAX_PER_USER=3;
    static final int MAX_CHECK_CITIES=16;
    static final class City {
        final String id,name,lat,lon,zone;
        City(String id,String name,String lat,String lon,String zone){
            this.id=id;this.name=name;this.lat=lat;this.lon=lon;this.zone=zone;
        }
        boolean valid(){
            try{
                double latitude=Double.parseDouble(lat),longitude=Double.parseDouble(lon);
                ZoneId.of(zone);
                return id!=null && id.length()>0 && name!=null && !name.isEmpty() &&
                    Double.isFinite(latitude)&&Double.isFinite(longitude) &&
                    latitude>=-90 && latitude<=90 && longitude>=-180 && longitude<=180;
            }catch(Exception invalid){return false;}
        }
    }
    static final City[] PRESETS={
        new City("salamanca","سالامانکا، اسپانیا","40.9701","-5.6639","Europe/Madrid"),
        new City("ardabil","اردبیل، ایران","38.2498","48.2933","Asia/Tehran"),
        new City("madrid","مادرید، اسپانیا","40.4168","-3.7038","Europe/Madrid"),
        new City("barcelona","بارسلونا، اسپانیا","41.3874","2.1686","Europe/Madrid"),
        new City("valencia","والنسیا، اسپانیا","39.4699","-0.3763","Europe/Madrid"),
        new City("zamora","زامورا، اسپانیا","41.5033","-5.7446","Europe/Madrid"),
        new City("valladolid","وایادولید، اسپانیا","41.6523","-4.7245","Europe/Madrid"),
        new City("tehran","تهران، ایران","35.6892","51.3890","Asia/Tehran"),
        new City("tabriz","تبریز، ایران","38.0962","46.2738","Asia/Tehran"),
        new City("rasht","رشت، ایران","37.2808","49.5832","Asia/Tehran"),
        new City("isfahan","اصفهان، ایران","32.6546","51.6680","Asia/Tehran"),
        new City("mashhad","مشهد، ایران","36.2605","59.6168","Asia/Tehran"),
        new City("shiraz","شیراز، ایران","29.5918","52.5837","Asia/Tehran")
    };
    WeatherCityStore(Context context){super(context,NAME,null,VERSION);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE weather_cities ("+
            "id TEXT PRIMARY KEY, name TEXT NOT NULL, lat TEXT NOT NULL,"+
            "lon TEXT NOT NULL, zone TEXT NOT NULL)");
        db.execSQL("CREATE TABLE weather_subscriptions ("+
            "user_id INTEGER NOT NULL, city_id TEXT NOT NULL,"+
            "PRIMARY KEY(user_id,city_id))");
        db.execSQL("CREATE TABLE weather_customized ("+
            "user_id INTEGER PRIMARY KEY)");
        for(City c:PRESETS)saveCity(db,c);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to){}
    private static void saveCity(SQLiteDatabase db,City c){
        ContentValues v=new ContentValues();
        v.put("id",c.id);v.put("name",c.name);v.put("lat",c.lat);
        v.put("lon",c.lon);v.put("zone",c.zone);
        db.insertWithOnConflict("weather_cities",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    synchronized City get(String id){
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT id,name,lat,lon,zone FROM weather_cities WHERE id=?",
            new String[]{id})){
            return c.moveToFirst()?new City(c.getString(0),c.getString(1),
                c.getString(2),c.getString(3),c.getString(4)):null;
        }
    }
    synchronized boolean customized(long user){
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT user_id FROM weather_customized WHERE user_id=?",
            new String[]{String.valueOf(user)})){
            return c.moveToFirst();
        }
    }
    synchronized Set<String> selected(long user){
        Set<String> out=new LinkedHashSet<>();
        if(!customized(user)){
            out.add("salamanca");out.add("ardabil");
            return out;
        }
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT city_id FROM weather_subscriptions WHERE user_id=? ORDER BY city_id",
            new String[]{String.valueOf(user)})){
            while(c.moveToNext())out.add(c.getString(0));
        }
        return out;
    }
    synchronized String toggle(long user,String id){
        City city=get(id);
        if(city==null)return "⚠️ این شهر یافت نشد.";
        Set<String> old=selected(user);
        boolean removing=old.contains(id);
        if(!removing && old.size()>=MAX_PER_USER)
            return "⚠️ تا ۳ شهر می‌توانی انتخاب کنی. اول یکی را حذف کن.";
        if(removing)old.remove(id);
        else old.add(id);
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues seen=new ContentValues();
            seen.put("user_id",user);
            db.insertWithOnConflict("weather_customized",null,seen,SQLiteDatabase.CONFLICT_IGNORE);
            db.delete("weather_subscriptions","user_id=?",new String[]{String.valueOf(user)});
            for(String key:old){
                ContentValues row=new ContentValues();
                row.put("user_id",user);row.put("city_id",key);
                db.insertWithOnConflict("weather_subscriptions",null,row,
                    SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return (removing?"➖ حذف شد: ":"✅ اضافه شد: ")+city.name+
            (old.isEmpty()?"\n🔕 هشدار آب‌وهوای خصوصی برای شما شهری ندارد.":"");
    }
    synchronized String addFound(long user,City city){
        if(city==null||!city.valid()||!city.id.startsWith("city_"))
            return "⚠️ مشخصات این شهر معتبر نیست.";
        SQLiteDatabase db=getWritableDatabase();
        saveCity(db,city);
        return selected(user).contains(city.id)?"✅ این شهر قبلاً انتخاب شده است.":
            toggle(user,city.id);
    }
    synchronized List<City> forMonitoring(List<BotUserStore.User> recipients){
        Map<String,City> cities=new LinkedHashMap<>();
        City salamanca=get("salamanca"),ardabil=get("ardabil");
        if(salamanca!=null)cities.put(salamanca.id,salamanca);
        if(ardabil!=null)cities.put(ardabil.id,ardabil);
        for(BotUserStore.User recipient:recipients){
            if(!recipient.active||recipient.blocked||!recipient.digestSubscribed)continue;
            for(String id:selected(recipient.id)){
                if(cities.size()>=MAX_CHECK_CITIES)break;
                City city=get(id);
                if(city!=null)cities.put(id,city);
            }
            if(cities.size()>=MAX_CHECK_CITIES)break;
        }
        return new ArrayList<>(cities.values());
    }
    synchronized boolean wants(long user,String id){
        return selected(user).contains(id);
    }
    static List<City> search(String query) throws Exception {
        if(query==null || !query.matches("[\\p{L}\\p{M}\\p{N} .'-]{2,48}"))
            throw new IllegalArgumentException("نام شهر باید ۲ تا ۴۸ حرف باشد.");
        String url="https://geocoding-api.open-meteo.com/v1/search?name="+
            URLEncoder.encode(query.trim(),"UTF-8")+
            "&count=5&language=en&format=json";
        JSONObject geo=FootballLiveTracker.getJson(url,150_000);
        JSONArray results=geo.optJSONArray("results");
        List<City> found=new ArrayList<>();
        if(results==null)return found;
        for(int i=0;i<results.length()&&found.size()<5;i++){
            JSONObject row=results.optJSONObject(i);
            if(row==null)continue;
            String id="city_"+row.optLong("id",0);
            String cityName=row.optString("name","");
            String country=row.optString("country","");
            String zone=row.optString("timezone","");
            String lat=String.valueOf(row.optDouble("latitude",Double.NaN));
            String lon=String.valueOf(row.optDouble("longitude",Double.NaN));
            City c=new City(id,cityName+"، "+country,lat,lon,zone);
            if(c.valid() && !cityName.isEmpty() && !id.equals("city_0"))found.add(c);
        }
        return found;
    }
}
