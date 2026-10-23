package com.javid.downloader;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A separate non-destructive database for user-selected football teams.
 * Favorite mode never turns on private bot notifications without /digestjoin.
 */
final class TeamFavoritesStore extends SQLiteOpenHelper {
    private static final String DB="javid_team_favorites_v1.db";
    private static final int VERSION=1;
    private static final int MAX_TEAMS=8;
    static final class Team {
        final String id,label,group;
        final String[] aliases;
        Team(String id,String label,String group,String... aliases) {
            this.id=id;this.label=label;this.group=group;this.aliases=aliases;
        }
    }
    static final Team[] CATALOG = {
        new Team("persepolis","🔴 پرسپولیس","iran","Persepolis","Perspolis","Piroozi","Pirouzi","پرسپولیس"),
        new Team("esteghlal","🔵 استقلال","iran","Esteghlal","استقلال","Esteghlal FC"),
        new Team("tractor","🔴 تراکتور","iran","Tractor SC","Tractor Sazi","Tractor","Traktorsazi","تراکتور"),
        new Team("sepahan","🟡 سپاهان","iran","Sepahan","سپاهان","Sepahan SC"),
        new Team("foolad","🔴 فولاد","iran","Foolad","Foolad Khuzestan","فولاد"),
        new Team("golgohar","🔵 گل‌گهر","iran","Gol Gohar","Gol-Gohar","Gol Gohar Sirjan","گل گهر","گل‌گهر"),
        new Team("malavan","⚪ ملوان","iran","Malavan","Malavan Bandar Anzali","ملوان"),
        new Team("zobahan","🟢 ذوب‌آهن","iran","Zob Ahan","Zobahan","Zob Ahan Esfahan","ذوب آهن","ذوب‌آهن"),
        new Team("chadormalou","🟠 چادرملو","iran","Chadormalou","Chadormalu","چادرملو"),
        new Team("real","⚪ رئال مادرید","europe","Real Madrid","Real Madrid CF","رئال مادرید"),
        new Team("barca","🔵 بارسلونا","europe","FC Barcelona","Barcelona","بارسلونا"),
        new Team("atletico","🔴 اتلتیکو مادرید","europe","Atlético Madrid","Atletico Madrid","Atletico de Madrid","اتلتیکو مادرید"),
        new Team("mancity","🔵 منچسترسیتی","europe","Manchester City","Man City","Man. City","منچسترسیتی"),
        new Team("manutd","🔴 منچستریونایتد","europe","Manchester United","Man United","Man Utd","منچستریونایتد"),
        new Team("arsenal","🔴 آرسنال","europe","Arsenal","آرسنال"),
        new Team("liverpool","🔴 لیورپول","europe","Liverpool","لیورپول"),
        new Team("chelsea","🔵 چلسی","europe","Chelsea","چلسی"),
        new Team("tottenham","⚪ تاتنهام","europe","Tottenham","Tottenham Hotspur","Spurs","تاتنهام"),
        new Team("bayern","🔴 بایرن مونیخ","europe","Bayern Munich","Bayern München","FC Bayern","Bayern","بایرن مونیخ"),
        new Team("dortmund","🟡 دورتموند","europe","Borussia Dortmund","Dortmund","BVB","دورتموند"),
        new Team("inter","🔵 اینتر میلان","europe","Inter Milan","Internazionale","FC Internazionale","اینتر میلان"),
        new Team("milan","🔴 آث میلان","europe","AC Milan","A.C. Milan","آث میلان"),
        new Team("juve","⚪ یوونتوس","europe","Juventus","یوونتوس"),
        new Team("napoli","🔵 ناپولی","europe","Napoli","SSC Napoli","ناپولی"),
        new Team("psg","🔵 پاری سن ژرمن","europe","Paris Saint-Germain","Paris Saint Germain","Paris SG","PSG","پاری سن ژرمن")
    };
    private static final Map<String,Team> TEAM_BY_ID=new LinkedHashMap<>();
    static {
        for(Team item:CATALOG)TEAM_BY_ID.put(item.id,item);
    }
    TeamFavoritesStore(Context context) { super(context,DB,null,VERSION); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS team_favorites ("+
            "user_id INTEGER NOT NULL,team_id TEXT NOT NULL,"+
            "PRIMARY KEY(user_id,team_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS team_favorite_modes ("+
            "user_id INTEGER PRIMARY KEY, favorites_only INTEGER NOT NULL DEFAULT 0)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int previous,int newer) {}
    static Team get(String id) { return TEAM_BY_ID.get(id); }
    static List<Team> teams(String group) {
        List<Team> list=new ArrayList<>();
        for(Team t:CATALOG)if(t.group.equals(group))list.add(t);
        return list;
    }
    synchronized Set<String> ids(long user) {
        Set<String> results=new HashSet<>();
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT team_id FROM team_favorites WHERE user_id=? ORDER BY team_id",
            new String[]{String.valueOf(user)})) {
            while(c.moveToNext())results.add(c.getString(0));
        }
        return results;
    }
    synchronized boolean favoritesOnly(long user) {
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT favorites_only FROM team_favorite_modes WHERE user_id=?",
            new String[]{String.valueOf(user)})) {
            return c.moveToFirst() && c.getInt(0)==1;
        }
    }
    synchronized String toggle(long user,String id) {
        Team team=get(id);
        if(team==null)return "این تیم در فهرست فعلی وجود ندارد.";
        SQLiteDatabase db=getWritableDatabase();
        Set<String> existing=ids(user);
        if(existing.contains(id)){
            db.delete("team_favorites","user_id=? AND team_id=?",
                new String[]{String.valueOf(user),id});
            if(existing.size()==1) {
                setFavoritesOnly(user,false);
                return "➖ "+team.label+" حذف شد. چون تیمی نمانده، اعلان همه مسابقات فعال است.";
            }
            return "➖ "+team.label+" از تیم‌های محبوب حذف شد.";
        }
        if(existing.size()>=MAX_TEAMS)
            return "حداکثر "+MAX_TEAMS+" تیم قابل انتخاب است؛ ابتدا یک تیم را حذف کن.";
        android.content.ContentValues values=new android.content.ContentValues();
        values.put("user_id",user);values.put("team_id",id);
        db.insertWithOnConflict("team_favorites",null,values,SQLiteDatabase.CONFLICT_IGNORE);
        return "✅ "+team.label+" به تیم‌های محبوب اضافه شد.";
    }
    synchronized String setFavoritesOnly(long user,boolean selected) {
        if(selected && ids(user).isEmpty())
            return "ابتدا حداقل یک تیم انتخاب کن؛ اعلان همه مسابقات هنوز فعال است.";
        android.content.ContentValues values=new android.content.ContentValues();
        values.put("user_id",user);values.put("favorites_only",selected?1:0);
        getWritableDatabase().insertWithOnConflict("team_favorite_modes",null,
            values,SQLiteDatabase.CONFLICT_REPLACE);
        return selected ?
            "🔔 از این پس فقط اعلان‌های فوتبال تیم‌های منتخب را دریافت می‌کنی.":
            "🌍 اعلان فوتبال همه مسابقات مهم دوباره فعال شد.";
    }
    private static String canonical(String value) {
        if(value==null)return "";
        String normal=Normalizer.normalize(value,Normalizer.Form.NFD)
            .replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT)
            .replace("ي","ی").replace("ك","ک").replace("‌"," ");
        return normal.replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll(" +"," ");
    }
    static boolean matchesTeam(String id,FootballLiveTracker.Match match) {
        Team team=get(id);
        if(team==null || match==null)return false;
        String home=canonical(match.home),away=canonical(match.away);
        if(home.isEmpty()||away.isEmpty())return false;
        for(String alias:team.aliases) {
            String needle=canonical(alias);
            if(needle.isEmpty())continue;
            if(home.equals(needle)||away.equals(needle)||
               home.startsWith(needle+" ")||away.startsWith(needle+" ")||
               home.endsWith(" "+needle)||away.endsWith(" "+needle))return true;
        }
        return false;
    }
    synchronized boolean relevant(long user,FootballLiveTracker.Match match) {
        if(!favoritesOnly(user))return true;
        for(String id:ids(user)) if(matchesTeam(id,match))return true;
        return false;
    }
    static boolean includes(Set<String> ids,FootballLiveTracker.Match m) {
        if(ids==null||ids.isEmpty())return true;
        for(String id:ids)if(matchesTeam(id,m))return true;
        return false;
    }
}