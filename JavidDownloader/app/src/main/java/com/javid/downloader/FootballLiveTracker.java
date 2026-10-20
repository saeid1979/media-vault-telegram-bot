package com.javid.downloader;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Live football results using two independent unauthenticated public data feeds:
 * ESPN scoreboards for major world competitions and Sofascore (and FotMob fallback)
 * for Iran and major leagues. Unofficial feeds can fail or change: no invented
 * score, minute or scorer is ever shown. Only the change from a verified
 * previous result can trigger a goal notification.
 */
final class FootballLiveTracker {
    static final String STATE = "football_score_state_v2";
    static final String LAST_STATUS = "football_last_status";
    static final String LAST_CHECK = "football_last_check";
    private final SharedPreferences prefs;
    FootballLiveTracker(SharedPreferences prefs) { this.prefs = prefs; }

    static final class Match {
        String id, home, away, league, source, clock = "";
        int h = -1, a = -1, importance = 2;
        long kickoff;
        boolean live, finished;
        String score() {
            return h >= 0 && a >= 0 ? h + "–" + a : "—";
        }
        String line() {
            return (live ? "🔴 " : finished ? "✅ " : "🕒 ") +
                league + " | " + home + " " + score() + " " + away +
                (clock.isEmpty() ? live ? " (زنده)" : finished ? " (پایان)" : "" : " (" + clock + ")");
        }
        String sourceLine() { return "منبع: " + source; }
    }
    static final class Snapshot {
        final List<Match> games = new ArrayList<>();
        int sourcesOk, sourcesFailed;
        boolean iranCovered;
        String diagnostics() {
            return "منابع پاسخگو: " + sourcesOk + "، ناموفق: " +
                sourcesFailed + "، بازی‌های مهم: " + games.size() +
                "، پوشش ایران: " + (iranCovered ? "دریافت شد" : "نامشخص");
        }
    }

    static JSONObject getJson(String endpoint, int maxBytes) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(endpoint).openConnection();
        conn.setConnectTimeout(6500);
        conn.setReadTimeout(7000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "JavidDownloaderAndroid/2.0 (football score reader)");
        conn.setRequestProperty("Accept", "application/json");
        try {
            if (conn.getResponseCode() != 200)
                throw new IllegalStateException("Football source unavailable");
            try (InputStream stream = conn.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > maxBytes) throw new IllegalStateException("Payload too large");
                    bytes.write(buffer, 0, count);
                }
                return new JSONObject(bytes.toString("UTF-8"));
            }
        } finally { conn.disconnect(); }
    }
    private static String text(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() <= max ? s : s.substring(0,max);
    }
    private static String valScore(Object value) {
        if (value instanceof JSONObject) {
            JSONObject score = (JSONObject) value;
            return score.optString("displayValue", score.optString("current", ""));
        }
        return value == null ? "" : String.valueOf(value);
    }
    private static int parseScore(String v) {
        try { return Integer.parseInt(v.trim()); }
        catch (Exception ignored) { return -1; }
    }
    private static long parseInstant(String str) {
        try { return Instant.parse(str).toEpochMilli(); }
        catch (Exception ignored) { return 0; }
    }
    private static boolean recent(Match match, long now) {
        // Avoid stale historic season events; only a 40-hour window, or ongoing live games.
        return match.live || (match.kickoff > now - TimeUnit.HOURS.toMillis(40) &&
            match.kickoff < now + TimeUnit.HOURS.toMillis(8));
    }
    private static String[] ESPN = {
        "uefa.champions", "uefa.europa", "eng.1", "esp.1", "ger.1",
        "ita.1", "fra.1", "fifa.world", "usa.1"
    };
    private static String label(String id) {
        switch (id) {
            case "uefa.champions": return "لیگ قهرمانان اروپا";
            case "uefa.europa": return "لیگ اروپا";
            case "eng.1": return "لیگ برتر انگلیس";
            case "esp.1": return "لالیگا";
            case "ger.1": return "بوندس‌لیگا";
            case "ita.1": return "سری‌آ";
            case "fra.1": return "لیگ فرانسه";
            case "fifa.world": return "جام جهانی";
            case "usa.1": return "MLS";
            default: return id;
        }
    }

    private static void fromEspn(Snapshot snapshot, String league, JSONObject result,
                                 Map<String,Match> found, long now) {
        JSONArray events = result.optJSONArray("events");
        if (events == null) return;
        for (int i=0; i<events.length(); i++) {
            JSONObject game = events.optJSONObject(i);
            if (game == null) continue;
            JSONObject status = game.optJSONObject("status");
            JSONObject type = status == null ? null : status.optJSONObject("type");
            String state = type == null ? "" : type.optString("state", "");
            if (!"in".equals(state) && !"post".equals(state)) continue;
            JSONArray comps = game.optJSONArray("competitions");
            JSONObject comp = comps == null ? null : comps.optJSONObject(0);
            JSONArray teams = comp == null ? null : comp.optJSONArray("competitors");
            if (teams == null) continue;
            Match m = new Match();
            m.id = "espn:" + league + ":" + game.optString("id","");
            if (m.id.endsWith(":")) continue;
            m.league = label(league);
            m.source = "ESPN";
            m.live = "in".equals(state);
            m.finished = "post".equals(state) && type.optBoolean("completed", true);
            m.kickoff = parseInstant(game.optString("date", ""));
            m.importance = league.equals("fifa.world") || league.startsWith("uefa.") ? 0 : 1;
            if (status != null && m.live) {
                String clock = status.optString("displayClock", "");
                if (clock.isEmpty()) clock = type.optString("shortDetail","");
                m.clock = text(clock,16);
            }
            for (int t=0;t<teams.length();t++) {
                JSONObject p=teams.optJSONObject(t);
                if (p==null) continue;
                JSONObject team=p.optJSONObject("team");
                String name=text(team==null?"":team.optString("displayName", ""),48);
                String score=valScore(p.opt("score"));
                if ("home".equals(p.optString("homeAway"))) {
                    m.home=name; m.h=parseScore(score);
                } else if ("away".equals(p.optString("homeAway"))) {
                    m.away=name; m.a=parseScore(score);
                }
            }
            if (m.home!=null && !m.home.isEmpty() && m.away!=null &&
                !m.away.isEmpty() && m.h>=0 && m.a>=0 && recent(m,now))
                found.put(m.id,m);
        }
    }

    private static boolean selectedTournament(JSONObject tournament) {
        if (tournament == null) return false;
        JSONObject category = tournament.optJSONObject("category");
        JSONObject unique = tournament.optJSONObject("uniqueTournament");
        String country = category==null?"":category.optString("name","").toLowerCase(Locale.ROOT);
        String region = category==null?"":category.optString("slug","").toLowerCase(Locale.ROOT);
        String name = (unique!=null?unique.optString("name",""):tournament.optString("name",""))
            .toLowerCase(Locale.ROOT);
        if ("iran".equals(country) || "iran".equals(region)) return true;
        if (name.contains("champions league") || name.contains("europa league") ||
            name.contains("world cup") || name.contains("afc champions") ||
            name.contains("copa america") || name.contains("libertadores")) return true;
        boolean importantCountry = country.equals("england") || country.equals("spain") ||
            country.equals("germany") || country.equals("italy") || country.equals("france") ||
            country.equals("iran");
        if (!importantCountry) return false;
        return name.contains("premier league") || name.equals("laliga") ||
            name.contains("la liga") || name.equals("serie a") ||
            name.contains("bundesliga") || name.contains("ligue 1") ||
            name.contains("persian gulf") || name.contains("hazfi");
    }

    private static String sofaLeague(JSONObject tournament) {
        JSONObject unique = tournament.optJSONObject("uniqueTournament");
        JSONObject category=tournament.optJSONObject("category");
        String country=category==null?"":category.optString("name","");
        String name = unique==null?tournament.optString("name",""):
            unique.optString("name", tournament.optString("name",""));
        return text(("Iran".equalsIgnoreCase(country) ? "ایران · " : "") + name,58);
    }
    private static int sofaScore(JSONObject teamScore) {
        if (teamScore == null) return -1;
        return teamScore.has("current") ? teamScore.optInt("current",-1):
            teamScore.optInt("display",-1);
    }
    private static void fromSofa(JSONObject response, Map<String,Match> found, long now,
                                 Snapshot snapshot) {
        JSONArray events = response.optJSONArray("events");
        if (events==null) return;
        for (int i=0; i<events.length();i++) {
            JSONObject event=events.optJSONObject(i);
            if(event==null)continue;
            JSONObject tournament=event.optJSONObject("tournament");
            if(!selectedTournament(tournament))continue;
            JSONObject status=event.optJSONObject("status");
            String type = status==null?"":status.optString("type","").toLowerCase(Locale.ROOT);
            boolean live=type.contains("inprogress") || type.equals("in");
            boolean ended=type.contains("finished") || type.equals("post");
            if (!live && !ended) continue;
            JSONObject home=event.optJSONObject("homeTeam");
            JSONObject away=event.optJSONObject("awayTeam");
            Match m=new Match();
            m.id="sofa:"+event.optString("id","");
            if(m.id.endsWith(":"))continue;
            m.home=text(home==null?"":home.optString("name",""),48);
            m.away=text(away==null?"":away.optString("name",""),48);
            m.h=sofaScore(event.optJSONObject("homeScore"));
            m.a=sofaScore(event.optJSONObject("awayScore"));
            m.league=sofaLeague(tournament);
            m.source="Sofascore";
            m.live=live;m.finished=ended;
            m.kickoff=1000L*event.optLong("startTimestamp",0);
            m.importance=m.league.contains("ایران")?0:1;
            // Provider sometimes returns the full clock in 'description'. Do not
            // guess a minute when it instead returns '1st half', 'HT', etc.
            if(live && status!=null) {
                String clock=status.optString("description","");
                if (clock.matches(".*[0-9]{1,3}['’]?(\\\\+[0-9]+)?.*"))
                    m.clock=text(clock,18);
            }
            if(m.h>=0&&m.a>=0&&!m.home.isEmpty()&&!m.away.isEmpty()&&recent(m,now))
                found.put(m.id,m);
            if(m.league.contains("ایران"))snapshot.iranCovered=true;
        }
    }
    private static void fromFotmob(JSONObject response, Map<String,Match> found,long now,
                                   Snapshot snapshot) {
        JSONArray leagues=response.optJSONArray("leagues");
        if(leagues==null)return;
        for(int i=0;i<leagues.length();i++){
            JSONObject league=leagues.optJSONObject(i);
            if(league==null)continue;
            String title=league.optString("name","");
            String ccode=league.optString("ccode","");
            String slug=(title+" "+ccode).toLowerCase(Locale.ROOT);
            boolean iran=league.optInt("primaryId",-1)==523||ccode.equalsIgnoreCase("IRN") ||
                slug.contains("persian gulf") || slug.contains("iran");
            boolean important=iran || slug.contains("champions league") ||
                slug.contains("premier league")||slug.contains("bundesliga")||
                slug.contains("laliga")||slug.contains("serie a")||
                slug.contains("ligue 1")||slug.contains("world cup")||
                slug.contains("europa league");
            if(!important)continue;
            JSONArray matches=league.optJSONArray("matches");
            if(matches==null)continue;
            for(int j=0;j<matches.length();j++){
                JSONObject e=matches.optJSONObject(j);
                if(e==null)continue;
                JSONObject status=e.optJSONObject("status");
                if(status==null)continue;
                boolean live=status.optBoolean("started",false)&&
                    !status.optBoolean("finished",false) && !status.optBoolean("cancelled",false);
                boolean ended=status.optBoolean("finished",false);
                if(!live&&!ended)continue;
                JSONObject home=e.optJSONObject("home"),away=e.optJSONObject("away");
                if(home==null||away==null)continue;
                Match m=new Match();
                m.id="fotmob:"+e.optString("id","");
                if(m.id.endsWith(":"))continue;
                m.home=text(home.optString("name",""),48);
                m.away=text(away.optString("name",""),48);
                m.h=home.has("score")&&!home.isNull("score")?home.optInt("score",-1):-1;
                m.a=away.has("score")&&!away.isNull("score")?away.optInt("score",-1):-1;
                m.league=(iran?"ایران · ":"")+text(title,50);
                m.source="FotMob";
                m.live=live;m.finished=ended;
                m.kickoff=parseInstant(status.optString("utcTime",""));
                m.clock=live?text(status.optString("reason",""),14):"";
                m.importance=iran?0:1;
                if(m.h>=0&&m.a>=0&&!m.home.isEmpty()&&!m.away.isEmpty()&&recent(m,now))
                    found.put(m.id,m);
                if(iran)snapshot.iranCovered=true;
            }
        }
    }

    Snapshot collect() {
        Snapshot snapshot=new Snapshot();
        Map<String,Match> matches=new HashMap<>();
        long now=System.currentTimeMillis();
        // Query the previous date too after midnight, preserving late matches.
        String[] dates={
            LocalDate.now(ZoneId.of("UTC")).format(DateTimeFormatter.BASIC_ISO_DATE),
            LocalDate.now(ZoneId.of("UTC")).minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)
        };
        for(String league:ESPN){
            try {
                JSONObject response=getJson("https://site.api.espn.com/apis/site/v2/sports/soccer/"+
                    league+"/scoreboard?dates="+dates[0]+"&limit=80",900_000);
                snapshot.sourcesOk++;
                fromEspn(snapshot,league,response,matches,now);
            } catch(Exception ignored){snapshot.sourcesFailed++;}
        }
        boolean sofaOk=false;
        try {
            String day=LocalDate.now(ZoneId.of("Asia/Tehran")).toString();
            JSONObject response=getJson("https://www.sofascore.com/api/v1/sport/football/scheduled-events/"+
                day,2_800_000);
            sofaOk=true;snapshot.sourcesOk++;
            fromSofa(response,matches,now,snapshot);
        } catch(Exception ignored) {
            try {
                String day=LocalDate.now(ZoneId.of("Asia/Tehran")).toString();
                JSONObject response=getJson("https://api.sofascore.com/api/v1/sport/football/scheduled-events/"+
                    day,2_800_000);
                sofaOk=true;snapshot.sourcesOk++;
                fromSofa(response,matches,now,snapshot);
            }catch(Exception ignored2){snapshot.sourcesFailed++;}
        }
        if(!snapshot.iranCovered || snapshot.sourcesOk==0) {
            try {
                String day=LocalDate.now(ZoneId.of("Asia/Tehran"))
                    .format(DateTimeFormatter.BASIC_ISO_DATE);
                JSONObject response=getJson("https://www.fotmob.com/api/matches?date="+
                    day+"&ccode3=IRN",1_500_000);
                snapshot.sourcesOk++;
                fromFotmob(response,matches,now,snapshot);
            }catch(Exception ignored){snapshot.sourcesFailed++;}
        }
        // Supplement late-night matches from the prior UTC day if primary result
        // is empty. One source only to keep mobile polling reasonably cheap.
        if(matches.isEmpty() && snapshot.sourcesOk>0) {
            try {
                JSONObject response=getJson("https://site.api.espn.com/apis/site/v2/sports/soccer/all/scoreboard?dates="+
                    dates[1]+"&limit=120",1_400_000);
                snapshot.sourcesOk++;
                fromEspn(snapshot,"جهان",response,matches,now);
            }catch(Exception ignored){snapshot.sourcesFailed++;}
        }
        snapshot.games.addAll(matches.values());
        snapshot.games.sort((a,b)->{
            int v=Boolean.compare(b.live,a.live);
            if(v!=0)return v;
            v=Integer.compare(a.importance,b.importance);
            if(v!=0)return v;
            return Long.compare(b.kickoff,a.kickoff);
        });
        prefs.edit().putLong(LAST_CHECK,System.currentTimeMillis())
            .putString(LAST_STATUS,snapshot.diagnostics()).apply();
        return snapshot;
    }
    static String summary(Snapshot snap) {
        if(snap.sourcesOk==0)
            return "⚠️ منابع نتایج فوتبال در حال حاضر پاسخ ندادند. آخرین نتایج تأییدنشده ارسال نمی‌شود.";
        if(snap.games.isEmpty())
            return "⚽ در حال حاضر نتیجه زنده یا بازی مهم پایان‌یافته در بازه بررسی‌شده یافت نشد.";
        StringBuilder sb=new StringBuilder("⚽ نتایج معتبر فوتبال اروپا، ایران و جهان\n");
        int count=0;
        for(Match m:snap.games) {
            if(count++>=18)break;
            sb.append(m.line()).append("\n");
        }
        sb.append("🔎 منابع: ESPN / Sofascore / FotMob (مطابق داده دریافت‌شده).");
        return sb.toString();
    }
    private static String key(Match m) { return m.id; }
    private static JSONObject stateEntry(Match m) throws Exception {
        return new JSONObject().put("h",m.h).put("a",m.a)
            .put("live",m.live).put("finished",m.finished)
            .put("ts",System.currentTimeMillis());
    }
    private static final long MAX_STATE_AGE=TimeUnit.DAYS.toMillis(2);
    private static String goalScorer(Match m) {
        if(!m.id.startsWith("sofa:"))return "";
        try{
            String event=m.id.substring(5);
            JSONObject data=getJson("https://www.sofascore.com/api/v1/event/"+
                event+"/incidents",250_000);
            JSONArray arr=data.optJSONArray("incidents");
            if(arr==null)return "";
            for(int i=0;i<arr.length();i++){
                JSONObject x=arr.optJSONObject(i);
                if(x==null)continue;
                if(!"goal".equals(x.optString("incidentType","")))continue;
                if(x.optInt("homeScore",-99)!=m.h||x.optInt("awayScore",-99)!=m.a)continue;
                JSONObject player=x.optJSONObject("player");
                if(player==null)continue;
                String scorer=text(player.optString("name",""),60);
                int minute=x.optInt("time",0);
                return scorer.isEmpty()?"":("⚽ زننده گل: "+scorer+
                    (minute>0 && minute<=130?" ("+minute+"′)":""));
            }
        }catch(Exception ignored){ }
        return "";
    }
    static final class Update {
        Snapshot snapshot;
        final List<String> alerts=new ArrayList<>();
    }
    Update check() {
        Update out=new Update();
        out.snapshot=collect();
        if(out.snapshot.sourcesOk==0)return out;
        JSONObject prior;
        try{prior=new JSONObject(prefs.getString(STATE,"{}"));}
        catch(Exception ignored){prior=new JSONObject();}
        JSONObject next=new JSONObject();
        long now=System.currentTimeMillis();
        for(Match m:out.snapshot.games) {
            try {
                JSONObject old=prior.optJSONObject(key(m));
                if(old!=null) {
                    int h=old.optInt("h",-1),a=old.optInt("a",-1);
                    boolean wasLive=old.optBoolean("live",false);
                    boolean done=old.optBoolean("finished",false);
                    if(m.live && ((h>=0&&m.h>h)||(a>=0&&m.a>a))) {
                        String scorer=goalScorer(m);
                        out.alerts.add("⚽ گل در مسابقه مهم!\n"+m.line()+
                            (scorer.isEmpty()?"":"\n"+scorer)+"\nمنبع: "+m.source);
                    } else if(m.live && h>=0 && a>=0 && (m.h<h||m.a<a)) {
                        out.alerts.add("📌 نتیجه اصلاح شد (احتمال بازبینی گل):\n"+m.line()+
                            "\nمنبع: "+m.source);
                    } else if(m.finished && !done && wasLive) {
                        out.alerts.add("🏁 پایان بازی:\n"+m.line()+"\nمنبع: "+m.source);
                    }
                }
                next.put(key(m),stateEntry(m));
            } catch(Exception ignored){ }
        }
        // Retain last-seen matches briefly. When an upstream temporarily omits a
        // match it should not be mistaken for a new match on the next poll.
        JSONArray keys=prior.names();
        if(keys!=null)for(int i=0;i<keys.length();i++){
            String id=keys.optString(i,"");
            if(next.has(id))continue;
            JSONObject entry=prior.optJSONObject(id);
            if(entry!=null && now-entry.optLong("ts",0)<MAX_STATE_AGE)
                try{next.put(id,entry);}catch(Exception ignored){}
        }
        prefs.edit().putString(STATE,next.toString()).apply();
        // Avoid a huge storm after reconnection.
        while(out.alerts.size()>8)out.alerts.remove(out.alerts.size()-1);
        return out;
    }
}