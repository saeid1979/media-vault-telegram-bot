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
        String id, home, away, league, source, clock = "", scorers = "";
        boolean iran;
        int h = -1, a = -1, importance = 2;
        long kickoff;
        boolean live, finished;
        String score() {
            return h >= 0 && a >= 0 ? h + "–" + a : "—";
        }
        String line() {
            return (live ? "🔴 " : finished ? "✅ " : "🕒 ") +
                league + " | " + home + " " + score() + " " + away +
                (clock.isEmpty() ? live ? " (زنده)" : finished ? " (پایان)" : "" : " (" + clock + ")") +
                (scorers.isEmpty() ? "" : "\n  ⚽ " + scorers);
        }
        String sourceLine() { return "منبع: " + source; }
    }
    static final class Snapshot {
        final List<Match> games = new ArrayList<>();
        int sourcesOk, sourcesFailed;
        boolean iranCovered;
        int iranFeedsOk, iranFeedsFailed;
        int iranMatches;
        int scorerMatches;
        int scorerUnavailable;
        String iranError = "";
        String diagnostics() {
            return "منابع پاسخگو: " + sourcesOk + "، ناموفق: " +
                sourcesFailed + "، بازی‌های مهم: " + games.size() +
                "، لیگ ایران: " + iranMatches + " بازی" +
                "، منبع گل‌زن: " + scorerMatches + " بازی موفق / " +
                scorerUnavailable + " بدون جزئیات" +
                "، منابع ایران: " + iranFeedsOk + " پاسخگو / " + iranFeedsFailed + " ناموفق" +
                (iranError.isEmpty() ? "" : "، آخرین خطا: " + iranError);
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
        return match.live || (match.kickoff > now - TimeUnit.HOURS.toMillis(
                match.iran ? 24 * 7 : 40) &&
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
        if ("iran".equals(country) || "iran".equals(region) ||
            (unique != null && unique.optInt("id", -1) == 915)) return true;
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
        boolean iran = "Iran".equalsIgnoreCase(country) ||
            (unique != null && unique.optInt("id", -1) == 915);
        return text((iran ? "ایران · " : "") + name,58);
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
            m.iran=m.league.contains("ایران");
            m.importance=m.iran?0:1;
            // Provider sometimes returns the full clock in 'description'. Do not
            // guess a minute when it instead returns '1st half', 'HT', etc.
            if(live && status!=null) {
                String clock=status.optString("description","");
                if (clock.matches(".*[0-9]{1,3}['’]?(\\\\+[0-9]+)?.*"))
                    m.clock=text(clock,18);
            }
            if(m.h>=0&&m.a>=0&&!m.home.isEmpty()&&!m.away.isEmpty()&&recent(m,now))
                found.put(m.id,m);
            if(m.iran) { snapshot.iranCovered=true; snapshot.iranMatches++; }
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
                if(m.kickoff==0) m.kickoff=e.optLong("timeTS",0);
                if(live) {
                    JSONObject time=status.optJSONObject("liveTime");
                    m.clock=time==null?"":text(time.optString("short",""),15);
                    if(m.clock.isEmpty()) {
                        JSONObject reason=status.optJSONObject("reason");
                        m.clock=reason==null?"":text(reason.optString("short",""),15);
                    }
                }
                m.importance=iran?0:1;
                m.iran=iran;
                if(m.h>=0&&m.a>=0&&!m.home.isEmpty()&&!m.away.isEmpty()&&recent(m,now))
                    found.put(m.id,m);
                if(iran) { snapshot.iranCovered=true; snapshot.iranMatches++; }
            }
        }
    }

    private static final String SOFA_IRAN = "https://api.sofascore.com/api/v1";
    private static final String FOTMOB_IRAN = "https://www.fotmob.com/api/data";
    private static int iranUniqueTournamentId = 915; // Persian Gulf Pro League

    private static String sourceError(Exception e) {
        if (e.getMessage() != null && e.getMessage().contains("HTTP "))
            return text(e.getMessage(), 90);
        return e.getClass().getSimpleName();
    }

    /** League-specific data source: avoids dropping Iran from huge global schedules. */
    private static void iranSeasonResults(Snapshot snap, Map<String,Match> matches, long now) {
        try {
            JSONObject seasons=getJson(SOFA_IRAN+
                "/unique-tournament/"+iranUniqueTournamentId+"/seasons",300_000);
            JSONArray seasonsList=seasons.optJSONArray("seasons");
            if(seasonsList==null || seasonsList.length()==0)
                throw new IllegalStateException("No active Iran season");
            int season=seasonsList.getJSONObject(0).optInt("id",0);
            if(season<=0)throw new IllegalStateException("Invalid Iran season");
            JSONObject results=getJson(SOFA_IRAN+
                "/unique-tournament/915/season/"+season+"/events/last/0",500_000);
            snap.iranFeedsOk++;
            snap.sourcesOk++;
            fromSofa(results,matches,now,snap);
        }catch(Exception failure){
            snap.iranFeedsFailed++;
            snap.iranError="Sofascore "+sourceError(failure);
        }
    }

    /** The /api/matches URL used by old versions was moved to /api/data/matches. */
    private static void iranDailyFotmob(Snapshot snap,Map<String,Match> matches,long now) {
        java.time.LocalDate today=LocalDate.now(ZoneId.of("Asia/Tehran"));
        // One live date and the 3 preceding Iranian local days.
        // Show latest Iranian results even when no game is played today.
        for(int ago=0;ago<4;ago++) {
            String date=today.minusDays(ago).format(DateTimeFormatter.BASIC_ISO_DATE);
            try {
                JSONObject result=getJson(FOTMOB_IRAN+
                    "/matches?date="+date+"&ccode3=IRN",6_000_000);
                snap.iranFeedsOk++;
                snap.sourcesOk++;
                fromFotmob(result,matches,now,snap);
            }catch(Exception failure) {
                snap.iranFeedsFailed++;
                snap.iranError="FotMob "+sourceError(failure);
            }
            // Avoid excessive calls after collecting at least one recent match
            // if additional days would no longer improve live goal alerts.
            if(ago>=1 && snap.iranCovered)break;
        }
    }

    private static String nameFrom(JSONObject row) {
        if(row==null)return "";
        JSONObject player=row.optJSONObject("player");
        String name=player==null?"":player.optString("name","");
        if(name.isEmpty() && player!=null)name=player.optString("shortName","");
        if(name.isEmpty())name=row.optString("nameStr","");
        if(name.isEmpty())name=row.optString("playerName","");
        if(name.isEmpty())name=row.optString("scorerName","");
        if(name.isEmpty())name=row.optString("name","");
        return text(name,48);
    }
    private static String compactGoals(List<String> goals) {
        if(goals.isEmpty())return "";
        StringBuilder out=new StringBuilder();
        for(String g:goals){
            if(out.length()+g.length()>400)break;
            if(out.length()>0)out.append("، ");
            out.append(g);
        }
        return out.toString();
    }
    private static String sofaGoals(String id) {
        try {
            JSONObject result=getJson(SOFA_IRAN+"/event/"+id+"/incidents",350_000);
            JSONArray incidents=result.optJSONArray("incidents");
            if(incidents==null)return "";
            List<String> goals=new ArrayList<>();
            for(int i=0;i<incidents.length();i++) {
                JSONObject item=incidents.optJSONObject(i);
                if(item==null || !"goal".equalsIgnoreCase(
                    item.optString("incidentType","")))continue;
                String scorer=nameFrom(item);
                if(scorer.isEmpty())continue;
                int minute=item.optInt("time",-1);
                int injury=item.optInt("addedTime",0);
                String stamp=minute>0 && minute<=130 ?
                    " "+minute+(injury>0?"+"+injury:"")+"′" : "";
                String side=item.optBoolean("isHome",false)?"میزبان":"میهمان";
                goals.add(scorer+stamp+" ("+side+")");
            }
            return compactGoals(goals);
        }catch(Exception unavailable){return "";}
    }
    private static String fotmobGoals(String id) {
        try {
            JSONObject data=getJson(FOTMOB_IRAN+"/matchDetails?matchId="+id,2_200_000);
            // FotMob uses several event structures, dependent on competition.
            // Prefer matchFacts events; some low-coverage leagues only return
            // header.events, or lists without the player object.
            List<JSONArray> candidates=new ArrayList<>();
            JSONObject content=data.optJSONObject("content");
            JSONObject facts=content==null?null:content.optJSONObject("matchFacts");
            if(facts!=null){
                JSONObject events=facts.optJSONObject("events");
                if(events!=null) {
                    JSONArray list=events.optJSONArray("events");
                    if(list!=null)candidates.add(list);
                }
                JSONArray list=facts.optJSONArray("events");
                if(list!=null)candidates.add(list);
            }
            JSONObject header=data.optJSONObject("header");
            if(header!=null) {
                JSONArray list=header.optJSONArray("events");
                if(list!=null)candidates.add(list);
            }
            for(JSONArray events:candidates) {
                List<String> goals=new ArrayList<>();
                for(int i=0;i<events.length();i++) {
                    JSONObject event=events.optJSONObject(i);
                    if(event==null)continue;
                    String kind=event.optString("type",event.optString("eventType",""));
                    if(!kind.toLowerCase(Locale.ROOT).contains("goal"))continue;
                    String scorer=nameFrom(event);
                    if(scorer.isEmpty())continue;
                    String stamp=text(event.optString("timeStr",""),12);
                    if(stamp.isEmpty()){
                        int minute=event.optInt("time",event.optInt("minute",0));
                        if(minute>0 && minute<=130)
                            stamp=minute+"′";
                    }
                    goals.add(scorer+(stamp.isEmpty()?"":" "+stamp)+
                        (event.optBoolean("isHome",false)?" (میزبان)":" (میهمان)"));
                }
                if(!goals.isEmpty())return compactGoals(goals);
            }
            return "";
        }catch(Exception unavailable){return "";}
    }

    private static final class CachedGoals {
        String text;
        long at;
        CachedGoals(String text,long at){this.text=text;this.at=at;}
    }
    private static final java.util.concurrent.ConcurrentHashMap<String,CachedGoals>
        goalCache=new java.util.concurrent.ConcurrentHashMap<>();
    private static String cachedGoals(Match match) {
        long now=System.currentTimeMillis();
        CachedGoals cached=goalCache.get(match.id);
        long ttl=match.live?TimeUnit.MINUTES.toMillis(2):TimeUnit.HOURS.toMillis(4);
        if(cached!=null && now-cached.at<ttl)return cached.text;
        String result="";
        if(match.id.startsWith("sofa:"))result=sofaGoals(match.id.substring(5));
        else if(match.id.startsWith("fotmob:"))result=fotmobGoals(match.id.substring(7));
        if(goalCache.size()>64)goalCache.clear();
        goalCache.put(match.id,new CachedGoals(result,now));
        return result;
    }
    private static void enrichIranGoals(Snapshot snap) {
        int inspected=0;
        for(Match match:snap.games) {
            if(!match.iran || match.h+match.a<=0 || match.h<0 || match.a<0)continue;
            if(inspected>=12)break;
            inspected++;
            match.scorers=cachedGoals(match);
            if(match.scorers.isEmpty())snap.scorerUnavailable++;
            else snap.scorerMatches++;
        }
    }

    Snapshot collect() {
        Snapshot snapshot = new Snapshot();
        Map<String, Match> matches = new HashMap<>();
        long now = System.currentTimeMillis();

        // Iran sources come first so bad global ESPN response times never
        // prevent loading the user's priority league.
        iranDailyFotmob(snapshot,matches,now);
        if(!snapshot.iranCovered || snapshot.iranMatches==0)
            iranSeasonResults(snapshot,matches,now);

        String date = LocalDate.now(ZoneId.of("UTC"))
            .format(DateTimeFormatter.BASIC_ISO_DATE);
        for(String league:ESPN) {
            try {
                JSONObject data=getJson("https://site.api.espn.com/apis/site/v2/sports/soccer/"+
                    league+"/scoreboard?dates="+date+"&limit=80",900_000);
                snapshot.sourcesOk++;
                fromEspn(snapshot,league,data,matches,now);
            }catch(Exception unavailable){snapshot.sourcesFailed++;}
        }
        // Sofa daily is only a limited fallback if the league-specific and
        // FotMob routes failed; the global feed can be several megabytes.
        if(!snapshot.iranCovered) {
            try {
                String local=LocalDate.now(ZoneId.of("Asia/Tehran")).toString();
                JSONObject daily=getJson(SOFA_IRAN+
                    "/sport/football/scheduled-events/"+local,6_000_000);
                snapshot.iranFeedsOk++;
                snapshot.sourcesOk++;
                fromSofa(daily,matches,now,snapshot);
            }catch(Exception failure) {
                snapshot.iranFeedsFailed++;
                snapshot.iranError="Sofascore daily "+sourceError(failure);
            }
        }
        snapshot.games.addAll(matches.values());
        snapshot.iranMatches=0;
        for(Match m:snapshot.games) if(m.iran)snapshot.iranMatches++;
        snapshot.iranCovered=snapshot.iranMatches>0;

        snapshot.games.sort((a,b)->{
            int importance=Boolean.compare(b.iran,a.iran);
            if(importance!=0)return importance;
            int live=Boolean.compare(b.live,a.live);
            if(live!=0)return live;
            int priority=Integer.compare(a.importance,b.importance);
            if(priority!=0)return priority;
            return Long.compare(b.kickoff,a.kickoff);
        });
        enrichIranGoals(snapshot);
        prefs.edit().putLong(LAST_CHECK,System.currentTimeMillis())
            .putString(LAST_STATUS,snapshot.diagnostics()).apply();
        return snapshot;
    }

    static String summary(Snapshot snap) {
        if(snap.sourcesOk==0)
            return "⚠️ منابع نتایج فوتبال پاسخ ندادند. نتیجه تأییدنشده ارسال نمی‌شود.";
        StringBuilder msg=new StringBuilder("⚽ نتایج فوتبال | اولویت لیگ ایران\n");
        msg.append("\n🇮🇷 لیگ برتر ایران و جام‌های داخلی:\n");
        int local=0;
        for(Match m:snap.games) {
            if(!m.iran)continue;
            if(local++>=14)break;
            msg.append(m.line()).append("\n");
        }
        if(local==0) {
            msg.append(snap.iranFeedsOk>0 ?
                "در بازه بررسی‌شده نتیجه تازه‌ای از لیگ ایران ثبت نشده است.\n":
                "⚠️ منابع اختصاصی ایران پاسخ ندادند؛ برای بررسی /footballstatus را بزن.\n");
        }
        msg.append("\n🌍 نتایج مهم اروپا و جهان:\n");
        int world=0;
        for(Match m:snap.games){
            if(m.iran)continue;
            if(world++>=12)break;
            msg.append(m.line()).append("\n");
        }
        if(world==0)msg.append("نتیجه مهم تازه‌ای در منابع فعلی ثبت نشده است.\n");
        msg.append("\nمنابع: ").append(snap.iranCovered?
            "FotMob / Sofascore برای ایران؛ ":"")
            .append("ESPN برای جهان. نام گل‌زن فقط در صورت دریافت داده نمایش داده می‌شود.");
        return msg.toString();
    }
    static String iranSummary(Snapshot snap) {
        StringBuilder msg=new StringBuilder("🇮🇷 آخرین نتایج لیگ برتر ایران\n");
        int count=0;
        for(Match m:snap.games) {
            if(!m.iran)continue;
            if(count++>=18)break;
            msg.append("\n").append(m.line()).append("\nمنبع: ").append(m.source).append("\n");
        }
        if(count==0)msg.append(snap.iranFeedsOk>0 ?
            "مسابقه زنده یا پایان‌یافته‌ای در بازه اخیر از لیگ ایران دریافت نشد.":
            "⚠️ دسترسی به داده‌های لیگ ایران برقرار نشد؛ /footballstatus را بررسی کن.");
        msg.append("\n\nوضعیت: ").append(snap.diagnostics());
        return msg.toString();
    }
    private static String key(Match m) { return m.id; }
    private static JSONObject stateEntry(Match m) throws Exception {
        return new JSONObject().put("h",m.h).put("a",m.a)
            .put("live",m.live).put("finished",m.finished)
            .put("ts",System.currentTimeMillis());
    }
    private static final long MAX_STATE_AGE=TimeUnit.DAYS.toMillis(2);
    private static String goalScorer(Match m) {
        String all = m.scorers;
        if (all==null||all.isEmpty()) {
            all=cachedGoals(m);
        }
        if(all==null||all.isEmpty())return "";
        // Only claim a known scorer when incident details exist.
        String[] goals=all.split("، ");
        return "⚽ گل‌زن گزارش‌شده: " + goals[goals.length-1];
    }
    static final class FootballAlert {
        final Match match;
        final String text;
        FootballAlert(Match match,String text) {
            this.match=match;this.text=text;
        }
    }
    static final class Update {
        Snapshot snapshot;
        final List<String> alerts=new ArrayList<>(); // compatibility with 2.2 diagnostics
        final List<FootballAlert> footballAlerts=new ArrayList<>();
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
                        String message="⚽ گل در مسابقه مهم!\n"+m.line()+
                            (scorer.isEmpty()?"":"\n"+scorer)+"\nمنبع: "+m.source;
                        out.alerts.add(message);
                        out.footballAlerts.add(new FootballAlert(m,message));
                    } else if(m.live && h>=0 && a>=0 && (m.h<h||m.a<a)) {
                        String message="📌 نتیجه اصلاح شد (احتمال بازبینی گل):\n"+
                            m.line()+"\nمنبع: "+m.source;
                        out.alerts.add(message);
                        out.footballAlerts.add(new FootballAlert(m,message));
                    } else if(m.finished && !done && wasLive) {
                        String message="🏁 پایان بازی:\n"+m.line()+
                            "\nمنبع: "+m.source;
                        out.alerts.add(message);
                        out.footballAlerts.add(new FootballAlert(m,message));
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
        while(out.footballAlerts.size()>8)
            out.footballAlerts.remove(out.footballAlerts.size()-1);
        return out;
    }
}