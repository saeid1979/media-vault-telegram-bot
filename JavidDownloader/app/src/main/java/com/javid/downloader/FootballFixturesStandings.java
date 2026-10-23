package com.javid.downloader;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** On-demand, read-only fixtures and standings for the Javid Telegram bot.
 * No match outcome is fabricated; upstream error or incomplete standings are
 * clearly labeled as unavailable. Cache reduces requests from multiple users.
 */
final class FootballFixturesStandings {
    private static final String FOTMOB="https://www.fotmob.com/api/data/matches?date=";
    private static final String ESPN="https://site.api.espn.com";
    private static final String SOFA="https://api.sofascore.com/api/v1";
    static final ZoneId SPAIN=ZoneId.of("Europe/Madrid");
    static final ZoneId IRAN=ZoneId.of("Asia/Tehran");
    private static final DateTimeFormatter FORMAT=DateTimeFormatter.ofPattern("dd/MM HH:mm",Locale.US);
    static final String[] CODES={"IRN","ENG","ESP","GER","ITA","FRA"};
    static final String[] LABELS={"🇮🇷 لیگ برتر ایران","🏴 لیگ برتر انگلیس","🇪🇸 لالیگا",
        "🇩🇪 بوندس‌لیگا","🇮🇹 سری آ","🇫🇷 لیگ یک فرانسه"};
    private static final String[] ESPN_IDS={"","eng.1","esp.1","ger.1","ita.1","fra.1"};
    private static final ConcurrentHashMap<String,Cache> cache=new ConcurrentHashMap<>();
    private static final class Cache {
        final String value; final long until;
        Cache(String value,long until){this.value=value;this.until=until;}
    }
    private static String cached(String key){
        Cache c=cache.get(key);
        return c!=null && System.currentTimeMillis()<c.until?c.value:null;
    }
    private static String store(String key,String value,long durationMinutes) {
        if(cache.size()>80)cache.clear();
        cache.put(key,new Cache(value,System.currentTimeMillis()+TimeUnit.MINUTES.toMillis(durationMinutes)));
        return value;
    }
    private static String clean(String name){
        if(name==null)return "";
        String value=name.replace('\n',' ').replace('\r',' ').trim();
        return value.length()>42?value.substring(0,42):value;
    }
    private static String leagueCode(String id, String cc, String name) {
        String code=cc==null?"":cc.toUpperCase(Locale.ROOT);
        String title=name==null?"":name.toLowerCase(Locale.ROOT);
        if(code.equals("IRN") &&
            (title.contains("persian")||title.contains("pro league")||
             title.contains("premier league")||title.contains("iran")))return "IRN";
        if(title.contains("champions league")&&
           (code.equals("INT")||code.equals("EUR")||code.equals("UEFA")))return "UCL";
        if(code.equals("ENG") && title.equals("premier league"))return "ENG";
        if(code.equals("ESP") && (title.contains("laliga")||title.contains("la liga")))return "ESP";
        if(code.equals("GER") && title.contains("bundesliga") && !title.contains("2."))return "GER";
        if(code.equals("ITA") && title.equals("serie a"))return "ITA";
        if(code.equals("FRA") && (title.equals("ligue 1")||title.startsWith("ligue 1 ")))return "FRA";
        return "";
    }
    private static String label(String code) {
        if("UCL".equals(code))return "🏆 لیگ قهرمانان اروپا";
        for(int i=0;i<CODES.length;i++)if(CODES[i].equals(code))return LABELS[i];
        return code;
    }
    private static long instant(String date){
        try{return Instant.parse(date).toEpochMilli();}
        catch(Exception invalid) {
            try{return java.time.OffsetDateTime.parse(date).toInstant().toEpochMilli();}
            catch(Exception ignored){return 0;}
        }
    }
    private static final class Fixture {
        String code,home,away,source;
        boolean live,done;
        int h=-1,a=-1;
        long at;
        String status(){
            if(done && h>=0 && a>=0)return " ✅ "+h+"–"+a+" (پایان)";
            if(live && h>=0 && a>=0)return " 🔴 "+h+"–"+a+" (زنده)";
            return " ⏳";
        }
        String line() {
            String time=at<=0?"زمان نامشخص":Instant.ofEpochMilli(at).atZone(
                "IRN".equals(code)?IRAN:SPAIN).format(FORMAT);
            return time+" | "+home+" — "+away+status()+
                ("IRN".equals(code)?" (ایران)":" (اسپانیا)");
        }
    }
    private static int score(JSONObject team) {
        if(team==null || team.isNull("score") || !team.has("score"))return -1;
        return team.optInt("score",-1);
    }
    private static List<Fixture> parseFotmob(JSONObject data) {
        List<Fixture> games=new ArrayList<>();
        JSONArray leagues=data.optJSONArray("leagues");
        if(leagues==null)return games;
        for(int i=0;i<leagues.length();i++){
            JSONObject league=leagues.optJSONObject(i);
            if(league==null)continue;
            String code=leagueCode(league.optString("id",""),
                league.optString("ccode",""),league.optString("name",""));
            if(code.isEmpty())continue;
            JSONArray matches=league.optJSONArray("matches");
            if(matches==null)continue;
            for(int j=0;j<matches.length();j++){
                JSONObject match=matches.optJSONObject(j);
                if(match==null)continue;
                JSONObject home=match.optJSONObject("home");
                JSONObject away=match.optJSONObject("away");
                JSONObject status=match.optJSONObject("status");
                if(home==null||away==null||status==null||
                   status.optBoolean("cancelled",false))continue;
                Fixture game=new Fixture();
                game.code=code;game.source="FotMob";
                game.home=clean(home.optString("name",""));
                game.away=clean(away.optString("name",""));
                if(game.home.isEmpty()||game.away.isEmpty())continue;
                game.at=instant(status.optString("utcTime",""));
                if(game.at==0)game.at=match.optLong("timeTS",0);
                if(game.at==0)continue;
                game.done=status.optBoolean("finished",false);
                game.live=status.optBoolean("started",false)&&!game.done;
                game.h=score(home);game.a=score(away);
                games.add(game);
            }
        }
        return games;
    }
    private static List<Fixture> parseEspn(String league,JSONObject response) {
        List<Fixture> games=new ArrayList<>();
        JSONArray events=response.optJSONArray("events");
        if(events==null)return games;
        String code="";
        for(int i=1;i<ESPN_IDS.length;i++)
            if(ESPN_IDS[i].equals(league))code=CODES[i];
        if(league.equals("uefa.champions"))code="UCL";
        if(code.isEmpty())return games;
        for(int i=0;i<events.length();i++){
            JSONObject event=events.optJSONObject(i);
            if(event==null)continue;
            JSONArray competitions=event.optJSONArray("competitions");
            JSONObject contest=competitions==null?null:competitions.optJSONObject(0);
            JSONArray competitors=contest==null?null:contest.optJSONArray("competitors");
            if(competitors==null)continue;
            Fixture game=new Fixture();
            game.code=code;game.source="ESPN";
            game.at=instant(event.optString("date",""));
            JSONObject status=event.optJSONObject("status");
            JSONObject type=status==null?null:status.optJSONObject("type");
            String state=type==null?"":type.optString("state","");
            game.live="in".equals(state);game.done="post".equals(state);
            if(game.at==0)continue;
            for(int n=0;n<competitors.length();n++){
                JSONObject team=competitors.optJSONObject(n);
                if(team==null)continue;
                JSONObject identity=team.optJSONObject("team");
                String teamName=clean(identity==null?"":identity.optString("displayName",""));
                if("home".equals(team.optString("homeAway"))) {
                    game.home=teamName;game.h=readScore(team.opt("score"));
                }else if("away".equals(team.optString("homeAway"))) {
                    game.away=teamName;game.a=readScore(team.opt("score"));
                }
            }
            if(game.home!=null&&!game.home.isEmpty()&&game.away!=null&&!game.away.isEmpty())
                games.add(game);
        }
        return games;
    }
    private static int readScore(Object item){
        if(item instanceof JSONObject)item=((JSONObject)item).opt("displayValue");
        try{return Integer.parseInt(String.valueOf(item));}catch(Exception unknown){return -1;}
    }
    private static List<Fixture> fixturesFor(LocalDate date){
        String key="fixtures:"+date;
        // Cache encoded output per day in memory, not the JSON; matches are
        // reparsed only when needed to preserve bounded memory.
        List<Fixture> matches=new ArrayList<>();
        try {
            JSONObject data=FootballLiveTracker.getJson(FOTMOB+
                date.format(DateTimeFormatter.BASIC_ISO_DATE),6_000_000);
            matches.addAll(parseFotmob(data));
        }catch(Exception noFotmob){ }
        if(matches.isEmpty()){
            // Fallback is intentionally small and bounded, unlike trying every
            // world tournament on every user's request.
            String day=date.format(DateTimeFormatter.BASIC_ISO_DATE);
            for(String league:new String[]{"eng.1","esp.1","ger.1","ita.1","fra.1","uefa.champions"}) {
                try {
                    JSONObject data=FootballLiveTracker.getJson(
                        ESPN+"/apis/site/v2/sports/soccer/"+league+
                        "/scoreboard?dates="+day+"&limit=100",900_000);
                    matches.addAll(parseEspn(league,data));
                }catch(Exception ignored){ }
            }
        }
        return matches;
    }
    static String fixtures(int dayCount, boolean tomorrow, String preferredLeague) {
        if(dayCount!=1&&dayCount!=7)dayCount=1;
        String league=preferredLeague==null?"all":preferredLeague;
        LocalDate start=LocalDate.now(SPAIN).plusDays(tomorrow?1:0);
        String key="program:"+start+":"+dayCount+":"+league;
        String previous=cached(key);
        if(previous!=null)return previous;
        StringBuilder output=new StringBuilder("📅 برنامه مسابقات "+
            (dayCount==7?"۷ روز آینده":tomorrow?"فردا":"امروز")+
            "\n🕓 ساعت مسابقات ایران به وقت تهران و اروپا به وقت اسپانیا\n");
        List<Fixture> games=new ArrayList<>();
        Set<String> known=new HashSet<>();
        for(int offset=0;offset<dayCount;offset++){
            LocalDate day=start.plusDays(offset);
            for(Fixture match:fixturesFor(day)){
                if(!"all".equals(league)&&!match.code.equals(league))continue;
                String id=match.code+":"+match.home+":"+match.away+":"+match.at;
                if(known.add(id))games.add(match);
            }
        }
        games.sort((a,b)->Long.compare(a.at,b.at));
        String lastGroup="";
        int displayed=0;
        for(Fixture m:games) {
            if(displayed>=34)break;
            if(!lastGroup.equals(m.code)) {
                // Show league for each match rather than grouping by date; users
                // can recognize which game belongs to which competition.
                lastGroup=m.code;
            }
            String line="\n"+label(m.code)+"\n"+m.line()+"\n";
            if(output.length()+line.length()>3550)break;
            output.append(line);
            displayed++;
        }
        if(displayed==0)output.append("\n⚠️ بازی قابل تأییدی در منابع فعلی یافت نشد."+
            " ممکن است برنامه مسابقات هنوز اعلام نشده باشد یا منبع در دسترس نباشد.");
        if(games.size()>displayed)output.append("\n... و "+(games.size()-displayed)+
            " مسابقه دیگر. برای دیدن یک لیگ از منوی انتخاب لیگ استفاده کن.");
        output.append("\n🔎 منابع: FotMob، در صورت نیاز ESPN. نمایش فقط برای بازی‌های دریافتی.");
        // Show no-scores result for only two minutes, fresh positive fixture 15min.
        return store(key,output.toString(),displayed>0?15:2);
    }
    static boolean validLeague(String code) {
        if(code==null)return false;
        for(String known:CODES)if(known.equals(code))return true;
        return false;
    }
    static String standings(String league) {
        if(!validLeague(league))return "لیگ انتخاب‌شده معتبر نیست.";
        String key="standing:"+league;
        String previous=cached(key);
        if(previous!=null)return previous;
        String result="IRN".equals(league)?iranStandings():espnStandings(league);
        return store(key,result,result.contains("در دسترس نیست")?2:20);
    }
    private static String iranStandings() {
        try{
            JSONObject season=FootballLiveTracker.getJson(SOFA+
                "/unique-tournament/915/seasons",250_000);
            JSONArray seasons=season.optJSONArray("seasons");
            if(seasons==null||seasons.length()==0)
                throw new IllegalStateException("Iran league season not returned");
            int seasonId=seasons.getJSONObject(0).optInt("id",-1);
            if(seasonId<=0)throw new IllegalStateException("Iran season ID missing");
            JSONObject data=FootballLiveTracker.getJson(SOFA+
                "/unique-tournament/915/season/"+seasonId+
                "/standings/total",700_000);
            JSONArray groups=data.optJSONArray("standings");
            if(groups==null||groups.length()==0)
                throw new IllegalStateException("Iran standings missing");
            StringBuilder response=new StringBuilder("🏆 جدول لیگ برتر ایران\n"+
                "فصل: "+seasons.getJSONObject(0).optString("name","")+
                "\nرتبه | تیم — بازی | برد-مساوی-باخت | تفاضل | امتیاز\n");
            int added=0;
            for(int index=0;index<groups.length();index++){
                JSONObject group=groups.optJSONObject(index);
                JSONArray rows=group==null?null:group.optJSONArray("rows");
                if(rows==null)continue;
                for(int i=0;i<rows.length();i++){
                    JSONObject row=rows.optJSONObject(i);
                    if(row==null)continue;
                    JSONObject team=row.optJSONObject("team");
                    String name=clean(team==null?"":team.optString("name",""));
                    if(name.isEmpty())continue;
                    String line=row.optInt("position",i+1)+". "+name+
                        " — "+value(row,"matches")+
                        " | "+value(row,"wins")+"-"+value(row,"draws")+"-"+value(row,"losses")+
                        " | "+signed(row,"scoresFor","scoresAgainst")+
                        " | "+value(row,"points")+"\n";
                    if(response.length()+line.length()>3560)break;
                    response.append(line);
                    added++;
                }
                if(added>0)break;
            }
            if(added==0)throw new IllegalStateException("Iran table empty");
            response.append("🔎 منبع: Sofascore | تازه‌ترین جدول منتشرشده");
            return response.toString();
        }catch(Exception inaccessible) {
            return "🏆 جدول لیگ برتر ایران\n⚠️ اطلاعات معتبر جدول فعلاً در دسترس نیست."+
                "\nبرای اطمینان، بعداً دوباره امتحان کن. نتیجه‌ای حدس زده نمی‌شود.";
        }
    }
    private static String value(JSONObject row,String name) {
        if(row==null||!row.has(name)||row.isNull(name))return "—";
        return row.optString(name,"—");
    }
    private static String signed(JSONObject row,String scored,String conceded) {
        if(row==null||!row.has(scored)||!row.has(conceded))return "—";
        int left=row.optInt(scored,Integer.MIN_VALUE);
        int right=row.optInt(conceded,Integer.MIN_VALUE);
        if(left==Integer.MIN_VALUE||right==Integer.MIN_VALUE)return "—";
        int gd=left-right;
        return gd>0?"+"+gd:String.valueOf(gd);
    }
    private static String espnStat(JSONObject entry,String... names) {
        JSONArray stats=entry.optJSONArray("stats");
        if(stats==null)return "—";
        for(String wanted:names){
            for(int i=0;i<stats.length();i++){
                JSONObject item=stats.optJSONObject(i);
                if(item==null)continue;
                if(wanted.equals(item.optString("name",""))){
                    String value=item.optString("displayValue","");
                    return value.isEmpty()?item.optString("value","—"):value;
                }
            }
        }
        return "—";
    }
    private static String espnStandings(String league) {
        int ix=-1;
        for(int i=1;i<CODES.length;i++)if(CODES[i].equals(league))ix=i;
        if(ix<0)return "لیگ نامعتبر.";
        try{
            JSONObject data=FootballLiveTracker.getJson(ESPN+
                "/apis/v2/sports/soccer/"+ESPN_IDS[ix]+"/standings",1_000_000);
            JSONArray groups=data.optJSONArray("children");
            if(groups==null||groups.length()==0)
                throw new IllegalStateException("No ESPN standings");
            JSONObject group=groups.optJSONObject(0);
            JSONObject standings=group==null?null:group.optJSONObject("standings");
            JSONArray entries=standings==null?null:standings.optJSONArray("entries");
            if(entries==null)throw new IllegalStateException("No standings rows");
            StringBuilder output=new StringBuilder("🏆 "+LABELS[ix]+
                "\nرتبه | تیم — بازی | برد-مساوی-باخت | تفاضل | امتیاز\n");
            int rows=0;
            for(int i=0;i<entries.length();i++) {
                JSONObject entry=entries.optJSONObject(i);
                if(entry==null)continue;
                JSONObject team=entry.optJSONObject("team");
                String name=clean(team==null?"":team.optString("displayName",""));
                if(name.isEmpty())continue;
                String rank=espnStat(entry,"rank");
                if("—".equals(rank))rank=String.valueOf(i+1);
                String line=rank+". "+name+
                    " — "+espnStat(entry,"gamesPlayed")+
                    " | "+espnStat(entry,"wins")+"-"+espnStat(entry,"ties","draws")+
                    "-"+espnStat(entry,"losses")+
                    " | "+espnStat(entry,"pointDifferential")+
                    " | "+espnStat(entry,"points")+"\n";
                if(output.length()+line.length()>3500)break;
                output.append(line);rows++;
            }
            if(rows==0)throw new IllegalStateException("Empty ESPN table");
            output.append("🔎 منبع: ESPN | جدول رسمی منتشرشده توسط منبع");
            return output.toString();
        }catch(Exception inaccessible) {
            return "🏆 "+LABELS[ix]+"\n⚠️ جدول معتبر فعلاً از ESPN دریافت نشد."+
                "\nلطفاً بعداً دوباره امتحان کن.";
        }
    }
}
