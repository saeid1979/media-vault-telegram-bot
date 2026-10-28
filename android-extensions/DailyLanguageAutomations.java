package com.javid.downloader;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Opt-in private-chat language features.
 * - Public RSS headline translation from individually selected feeds (MyMemory);
 * - Scheduled local Spanish/English lessons;
 * - Scheduled words with yesterday/last-week revision.
 *
 * Only PUBLIC source headlines are sent to the third-party translation API.
 * There is no transmission of private chats, user details or stored words.
 */
final class DailyLanguageAutomations {
    interface Delivery {
        void send(long telegramUserId, String text) throws Exception;
    }
    interface Eligibility { boolean allowed(long id); }
    private static final String USERS="lang_auto_users";
    private static final String TRANS="lang_translate_";
    private static final String TRANS_SENT="lang_trans_sent_";
    private static final String LESSON_ON="lang_lesson_on_";
    private static final String LESSON_LANG="lang_lesson_lang_";
    private static final String LESSON_AT="lang_lesson_at_";
    private static final String LESSON_SENT="lang_lesson_sent_";
    private static final String WORDS_ON="lang_words_on_";
    private static final String WORDS_LANG="lang_words_lang_";
    private static final String WORDS_AT="lang_words_at_";
    private static final String WORDS_SENT="lang_words_sent_";
    private static final String TRANSLATION_API=
        "https://api.mymemory.translated.net/get";
    private static final DateTimeFormatter CLOCK=DateTimeFormatter.ofPattern("HH:mm",Locale.US);
    private static final String[] FEED_IDS={"es_news","en_world","en_tech"};
    private static final String[] FEED_URLS={
        "https://feeds.bbci.co.uk/mundo/rss.xml",
        "https://feeds.bbci.co.uk/news/world/rss.xml",
        "https://feeds.bbci.co.uk/news/technology/rss.xml"
    };
    private static final String[] FEED_LABELS={
        "BBC Mundo (اسپانیایی)",
        "BBC World (انگلیسی)",
        "BBC Technology (انگلیسی)"
    };
    private static final String[] FEED_LANGS={"es","en","en"};

    private static final String[][] ES_WORDS={
        {"aprovechar","استفاده بهینه کردن","Quiero aprovechar el tiempo."},
        {"sin embargo","با این حال","Estudio mucho; sin embargo, necesito practicar."},
        {"conseguir","به دست آوردن","Quiero conseguir un trabajo."},
        {"acostumbrarse","عادت کردن","Me acostumbro a hablar español."},
        {"el trámite","کار اداری","Tengo que hacer un trámite mañana."},
        {"mejorar","بهتر کردن","Quiero mejorar mi pronunciación."},
        {"el plazo","مهلت","El plazo termina el viernes."},
        {"aunque","اگرچه","Salgo aunque llueva."},
        {"solicitar","درخواست کردن","Voy a solicitar información."},
        {"la cita","وقت ملاقات","Tengo una cita a las diez."},
        {"darse cuenta","متوجه شدن","Me di cuenta de mi error."},
        {"resolver","حل کردن","Podemos resolver este problema."},
        {"la factura","صورت‌حساب","Necesito guardar la factura."},
        {"disponible","در دسترس","¿Está disponible esta habitación?"}
    };
    private static final String[][] EN_WORDS={
        {"improve","بهبود دادن","I want to improve my English."},
        {"although","اگرچه","Although it is raining, I will go."},
        {"deadline","مهلت","The deadline is on Friday."},
        {"apply","درخواست دادن","I want to apply for this job."},
        {"appointment","وقت ملاقات","I have an appointment tomorrow."},
        {"available","در دسترس","Is this room available?"},
        {"instead","در عوض","Let's walk instead of driving."},
        {"manage","مدیریت کردن","I manage my time carefully."},
        {"probably","احتمالاً","It will probably rain later."},
        {"necessary","ضروری","Practice is necessary."},
        {"achieve","به دست آوردن","You can achieve your goal."},
        {"notice","متوجه شدن","I noticed a mistake."},
        {"reliable","قابل اعتماد","We need a reliable source."},
        {"schedule","برنامه زمانی","My schedule starts at nine."}
    };
    private static final String[][] ES_LESSONS={
        {"Hablar de rutinas", "Normalmente me levanto a las ocho y desayuno.",
         "معمولاً ساعت هشت بلند می‌شوم و صبحانه می‌خورم.",
         "¿A qué hora te levantas normalmente?"},
        {"Pedir información", "¿Podría decirme dónde está la estación?",
         "ممکن است به من بگویید ایستگاه کجاست؟",
         "¿Podría ayudarme, por favor?"},
        {"Planes", "Mañana voy a estudiar español.",
         "فردا می‌خواهم اسپانیایی بخوانم.",
         "¿Qué vas a hacer mañana?"},
        {"Pasado: ayer", "Ayer trabajé hasta las seis.",
         "دیروز تا ساعت شش کار کردم.",
         "¿Qué hiciste ayer?"},
        {"Opiniones", "Creo que aprender idiomas es útil.",
         "فکر می‌کنم یادگیری زبان‌ها مفید است.",
         "¿Qué opinas tú?"},
        {"Comparaciones", "Este camino es más corto que el otro.",
         "این مسیر از آن یکی کوتاه‌تر است.",
         "Compara dos ciudades."},
        {"En el trabajo", "Empecé a trabajar a las once.",
         "ساعت یازده شروع به کار کردم.",
         "¿A qué hora empiezas a trabajar?"}
    };
    private static final String[][] EN_LESSONS={
        {"Daily routines", "I usually start work at eleven.",
         "من معمولاً ساعت یازده کار را شروع می‌کنم.",
         "What time do you usually start work?"},
        {"Polite requests", "Could you tell me where the station is?",
         "ممکن است بگویید ایستگاه کجاست؟",
         "Could you help me, please?"},
        {"Plans", "I'm going to study English tomorrow.",
         "فردا می‌خواهم انگلیسی بخوانم.",
         "What are you going to do tomorrow?"},
        {"Past simple", "I worked until six yesterday.",
         "دیروز تا ساعت شش کار کردم.",
         "What did you do yesterday?"},
        {"Opinions", "I think learning languages is useful.",
         "فکر می‌کنم یادگیری زبان‌ها مفید است.",
         "What do you think?"},
        {"Comparisons", "This route is shorter than the other one.",
         "این مسیر از مسیر دیگر کوتاه‌تر است.",
         "Compare two places."},
        {"At work", "I started working at eleven.",
         "ساعت یازده شروع به کار کردم.",
         "When do you start work?"}
    };

    private final SharedPreferences prefs;
    DailyLanguageAutomations(SharedPreferences prefs) { this.prefs=prefs; }

    static String normalizeLanguage(String value) {
        if(value==null)return null;
        String lang=value.trim().toLowerCase(Locale.ROOT);
        if(lang.equals("es")||lang.equals("spanish")||lang.equals("español")||
           lang.equals("اسپانیایی"))return "es";
        if(lang.equals("en")||lang.equals("english")||lang.equals("انگلیسی"))return "en";
        return null;
    }

    private static int feedIndex(String name) {
        for(int i=0;i<FEED_IDS.length;i++)if(FEED_IDS[i].equals(name))return i;
        return -1;
    }
    private static String[] args(String input) {
        return input.trim().split("\\s+");
    }
    private static String normalizeCommand(String value) {
        return value.toLowerCase(Locale.ROOT).split("@",2)[0];
    }
    static boolean supports(String text) {
        if(text==null||!text.startsWith("/"))return false;
        String cmd=normalizeCommand(args(text)[0]);
        return Arrays.asList("/language","/lang_status","/translate_sources",
          "/translate_on","/translate_off","/translate_status","/translate_now",
          "/lesson_on","/lesson_off","/lesson_status","/lesson_now",
          "/words_on","/words_off","/words_status","/words_now","/words_review").contains(cmd);
    }

    private synchronized void register(long id) {
        Set<String> users=new HashSet<>(prefs.getStringSet(USERS,new HashSet<String>()));
        users.add(Long.toString(id));
        prefs.edit().putStringSet(USERS,users).apply();
    }
    private synchronized Set<String> feeds(long id) {
        String raw=prefs.getString(TRANS+id,"");
        Set<String> ids=new LinkedHashSet<>();
        if(!raw.isEmpty())for(String part:raw.split(","))if(feedIndex(part)>=0)ids.add(part);
        return ids;
    }
    private synchronized void setFeeds(long id,Set<String> chosen) {
        prefs.edit().putString(TRANS+id,String.join(",",chosen)).apply();
        register(id);
    }
    private String clockKey(long id,boolean lesson) {
        return (lesson?LESSON_AT:WORDS_AT)+id;
    }
    private static String languageName(String lang) {
        return "es".equals(lang)?"اسپانیایی 🇪🇸":"انگلیسی 🇺🇸";
    }
    private String getClock(long id,boolean lesson){
        return prefs.getString(clockKey(id,lesson),lesson?"09:00":"18:00");
    }
    private static boolean validTime(String value){
        try {LocalTime.parse(value,CLOCK);return value.matches("([01][0-9]|2[0-3]):[0-5][0-9]");}
        catch(Exception e){return false;}
    }
    private String status(long id) {
        Set<String> selected=feeds(id);
        StringBuilder sb=new StringBuilder("🤖 مرکز آموزش و ترجمه خودکار\n\n");
        sb.append("🌐 ترجمه خبرهای عمومی: ");
        if(selected.isEmpty())sb.append("خاموش\n");
        else sb.append(String.join("، ",selected)).append("\n");
        boolean lesson=prefs.getBoolean(LESSON_ON+id,false);
        boolean words=prefs.getBoolean(WORDS_ON+id,false);
        sb.append("📚 درس روزانه: ").append(lesson?
            languageName(prefs.getString(LESSON_LANG+id,"es"))+"، ساعت "+getClock(id,true):"خاموش").append("\n");
        sb.append("🗣️ واژگان و مرور: ").append(words?
            languageName(prefs.getString(WORDS_LANG+id,"es"))+"، ساعت "+getClock(id,false):"خاموش").append("\n");
        sb.append("🕒 ساعت‌ها طبق منطقه زمانی گوشی میزبان هستند.\n");
        sb.append("\nمنابع: /translate_sources\n");
        sb.append("فعال‌سازی: /translate_on es_news\n");
        sb.append("درس: /lesson_on es 09:00\n");
        sb.append("واژگان: /words_on es 18:00\n");
        sb.append("پیش‌نمایش: /translate_now es_news ، /lesson_now ، /words_now");
        return sb.toString();
    }

    String handle(long id,String text) throws Exception {
        String[] tokens=args(text);
        String cmd=normalizeCommand(tokens[0]);
        if(cmd.equals("/language")||cmd.equals("/lang_status"))return status(id);
        if(cmd.equals("/translate_sources")){
            return "🌐 منابع عمومی قابل انتخاب (تیترهای کوتاه):\n"+
                "es_news: BBC Mundo، اسپانیایی\n"+
                "en_world: BBC World، انگلیسی\n"+
                "en_tech: BBC Technology، انگلیسی\n\n"+
                "نمونه: /translate_on es_news\n"+
                "یا: /translate_on en_world\n"+
                "برای خاموشی: /translate_off es_news\n"+
                "سرویس MyMemory فقط متن تیتر عمومی را برای ترجمه دریافت می‌کند؛ نه پیام خصوصی کاربران.";
        }
        if(cmd.equals("/translate_status"))return "🌐 منابع فعال: "+
            (feeds(id).isEmpty()?"هیچ‌کدام":String.join("، ",feeds(id)));
        if(cmd.equals("/translate_on")){
            if(tokens.length<2)return "منبع را انتخاب کن: /translate_sources";
            Set<String> active=feeds(id);
            String choice=tokens[1].toLowerCase(Locale.ROOT);
            if(choice.equals("es"))active.add("es_news");
            else if(choice.equals("en")){active.add("en_world");active.add("en_tech");}
            else if(choice.equals("all"))active.addAll(Arrays.asList(FEED_IDS));
            else if(feedIndex(choice)>=0)active.add(choice);
            else return "منبع نامعتبر است. فهرست: /translate_sources";
            setFeeds(id,active);
            return "✅ ترجمه خودکار فعال شد: "+String.join("، ",active)+
                "\n📩 حداکثر یک تیتر تازه برای هر منبع در روز و جلوگیری از تکرار.\n"+
                "🔕 خاموش‌کردن: /translate_off";
        }
        if(cmd.equals("/translate_off")){
            Set<String> active=feeds(id);
            if(tokens.length<2||tokens[1].equalsIgnoreCase("all"))active.clear();
            else {
                if(feedIndex(tokens[1])<0)return "منبع نامعتبر است. /translate_sources";
                active.remove(tokens[1]);
            }
            setFeeds(id,active);
            return "🔕 منابع فعال باقی‌مانده: "+(active.isEmpty()?"هیچ‌کدام":String.join("، ",active));
        }
        if(cmd.equals("/translate_now")){
            String source=tokens.length>=2?tokens[1].toLowerCase(Locale.ROOT):null;
            if(source==null){
                Set<String> active=feeds(id);
                if(active.isEmpty())return "ابتدا یک منبع را فعال کن: /translate_on es_news";
                source=active.iterator().next();
            }
            int index=feedIndex(source);
            if(index<0)return "منبع نامعتبر است. /translate_sources";
            NewsItem item=fetchLatest(FEED_URLS[index]);
            String farsi=translateTitle(item.title,FEED_LANGS[index]);
            return formatNews(index,item,farsi)+"\n\n🔎 این یک پیش‌نمایش دستی است.";
        }
        if(cmd.equals("/lesson_on")||cmd.equals("/words_on")){
            boolean lesson=cmd.equals("/lesson_on");
            String lang=tokens.length>1?normalizeLanguage(tokens[1]):"es";
            if(lang==null)return "زبان را es یا en وارد کن. نمونه: /lesson_on es 09:00";
            String time=tokens.length>2?tokens[2]:(lesson?"09:00":"18:00");
            if(!validTime(time))return "زمان نامعتبر است. از قالب HH:mm استفاده کن؛ مثال: 09:00";
            prefs.edit().putBoolean((lesson?LESSON_ON:WORDS_ON)+id,true)
                .putString((lesson?LESSON_LANG:WORDS_LANG)+id,lang)
                .putString(clockKey(id,lesson),time).apply();
            register(id);
            return "✅ "+(lesson?"درس کوتاه روزانه":"واژگان همراه مرور")+
                " به زبان "+languageName(lang)+" هر روز ساعت "+time+
                " (وقت گوشی میزبان) فعال شد.\nخاموش: "+(lesson?"/lesson_off":"/words_off");
        }
        if(cmd.equals("/lesson_off")||cmd.equals("/words_off")){
            boolean lesson=cmd.equals("/lesson_off");
            prefs.edit().putBoolean((lesson?LESSON_ON:WORDS_ON)+id,false).apply();
            return "🔕 "+(lesson?"درس روزانه":"واژگان روزانه")+" خاموش شد.";
        }
        if(cmd.equals("/lesson_status")||cmd.equals("/words_status"))return status(id);
        if(cmd.equals("/lesson_now")){
            String lang=tokens.length>1?normalizeLanguage(tokens[1]):
                prefs.getString(LESSON_LANG+id,"es");
            if(lang==null)return "زبان es یا en را انتخاب کن.";
            return lessonText(lang,LocalDate.now(ZoneId.systemDefault()));
        }
        if(cmd.equals("/words_now")){
            String lang=tokens.length>1?normalizeLanguage(tokens[1]):
                prefs.getString(WORDS_LANG+id,"es");
            if(lang==null)return "زبان es یا en را انتخاب کن.";
            return wordsText(lang,LocalDate.now(ZoneId.systemDefault()),false);
        }
        if(cmd.equals("/words_review")){
            String lang=tokens.length>1?normalizeLanguage(tokens[1]):
                prefs.getString(WORDS_LANG+id,"es");
            if(lang==null)return "زبان es یا en را انتخاب کن.";
            return wordsText(lang,LocalDate.now(ZoneId.systemDefault()),true);
        }
        return "برای مشاهده امکانات: /language";
    }

    static String lessonText(String lang, LocalDate today){
        String[][] lessons="en".equals(lang)?EN_LESSONS:ES_LESSONS;
        int index=(int)Math.floorMod(today.toEpochDay(),lessons.length);
        String[] row=lessons[index];
        return "📚 درس روزانه "+languageName(lang)+" (سطح A2)\n"+
            "📌 موضوع: "+row[0]+"\n\n"+
            "🔹 "+row[1]+"\n"+
            "🇮🇷 "+row[2]+"\n\n"+
            "✍️ تمرین: "+row[3]+"\n"+
            "🔁 برای دریافت درس جدید در روز بعد /lesson_now";
    }
    static String wordsText(String lang,LocalDate today,boolean reviewOnly){
        String[][] deck="en".equals(lang)?EN_WORDS:ES_WORDS;
        int i=(int)Math.floorMod(today.toEpochDay(),deck.length);
        String[] todayRow=deck[i];
        String[] yesterday=deck[(i-1+deck.length)%deck.length];
        String[] weekAgo=deck[(i-7+deck.length)%deck.length];
        StringBuilder b=new StringBuilder();
        b.append("🗣️ ").append(reviewOnly?"مرور واژگان":"واژه روز").append(" ")
            .append(languageName(lang)).append(" (A2)\n\n");
        if(!reviewOnly)b.append("🆕 ").append(todayRow[0]).append(" = ")
            .append(todayRow[1]).append("\nمثال: ").append(todayRow[2]).append("\n\n");
        b.append("🔁 مرور دیروز: ").append(yesterday[0]).append(" = ")
            .append(yesterday[1]).append("\n");
        b.append("🔁 مرور هفت روز قبل: ").append(weekAgo[0]).append(" = ")
            .append(weekAgo[1]).append("\n");
        b.append("🧩 تمرین: جمله‌ای با یکی از این کلمات بساز.");
        return b.toString();
    }

    private static final class NewsItem {
        final String title,link;
        NewsItem(String title,String link){this.title=title;this.link=link;}
    }
    private static InputStream open(String address) throws Exception {
        URL u=new URL(address);
        if(!"https".equals(u.getProtocol()))throw new Exception("HTTPS is required");
        HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(9000);c.setReadTimeout(9000);
        c.setRequestProperty("User-Agent","JavidDownloader-Language/3.6.9");
        c.setRequestProperty("Accept","application/rss+xml, application/xml, application/json");
        if(c.getResponseCode()!=200) {c.disconnect();throw new Exception("Source returned "+c.getResponseCode());}
        return new java.io.FilterInputStream(c.getInputStream()){
            int total=0;
            @Override public int read() throws java.io.IOException{
                int a=super.read();
                if(a>=0 && ++total>262144)throw new java.io.IOException("Response too large");
                return a;
            }
            @Override public int read(byte[] b,int off,int len) throws java.io.IOException{
                int a=super.read(b,off,Math.min(len,262144-total+1));
                if(a>0 && (total+=a)>262144)throw new java.io.IOException("Response too large");
                return a;
            }
            @Override public void close() throws java.io.IOException{super.close();c.disconnect();}
        };
    }
    private static NewsItem fetchLatest(String url) throws Exception {
        try(InputStream in=open(url)){
            XmlPullParserFactory factory=XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser xml=factory.newPullParser();
            xml.setInput(in,"UTF-8");
            boolean item=false;
            String title="",link="";
            int tag=xml.getEventType();
            while(tag!=XmlPullParser.END_DOCUMENT){
                if(tag==XmlPullParser.START_TAG){
                    String name=xml.getName();
                    if(name.equalsIgnoreCase("item")||name.equalsIgnoreCase("entry")){
                        item=true;title="";link="";
                    } else if(item && name.equalsIgnoreCase("title"))title=xml.nextText().trim();
                    else if(item && name.equalsIgnoreCase("link")){
                        String value=xml.getAttributeValue(null,"href");
                        if(value==null)value=xml.nextText();
                        link=value.trim();
                    }
                }else if(tag==XmlPullParser.END_TAG && item &&
                    (xml.getName().equalsIgnoreCase("item")||xml.getName().equalsIgnoreCase("entry"))){
                    if(!title.isEmpty()){
                        if(title.length()>210)title=title.substring(0,210);
                        if(!link.startsWith("https://"))link="";
                        return new NewsItem(title,link);
                    }
                    item=false;
                }
                tag=xml.next();
            }
        }
        throw new Exception("No article in feed");
    }
    private static String readText(InputStream in,int maxBytes) throws Exception{
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        byte[] chunk=new byte[2048];int total=0,n;
        while((n=in.read(chunk))>=0){
            total+=n;
            if(total>maxBytes)throw new Exception("Oversized translation response");
            b.write(chunk,0,n);
        }
        return b.toString("UTF-8");
    }
    private static String translateTitle(String title,String language)throws Exception{
        String q=title;
        byte[] raw=q.getBytes(StandardCharsets.UTF_8);
        if(raw.length>460){
            q=q.substring(0,Math.min(130,q.length()));
            while(q.getBytes(StandardCharsets.UTF_8).length>450)q=q.substring(0,q.length()-1);
        }
        String url=TRANSLATION_API+"?q="+URLEncoder.encode(q,"UTF-8")+
            "&langpair="+URLEncoder.encode(language+"|fa","UTF-8");
        try(InputStream in=open(url)){
            JSONObject result=new JSONObject(readText(in,100000));
            if(result.optInt("responseStatus",200)!=200)throw new Exception("Translation unavailable");
            JSONObject data=result.optJSONObject("responseData");
            if(data==null)throw new Exception("Translation missing");
            String translated=data.optString("translatedText","").trim();
            if(translated.isEmpty()||translated.equalsIgnoreCase(title))
                throw new Exception("No Persian translation");
            return translated.replace("&quot;","\"").replace("&#39;","'")
                .replace("&amp;","&").replace("&lt;","<").replace("&gt;",">");
        }
    }
    private static String formatNews(int i,NewsItem item,String persian){
        return "🌐 ترجمه خودکار خبر ("+FEED_LABELS[i]+")\n"+
            "🇮🇷 "+persian+"\n"+
            "📰 متن اصلی: "+item.title+"\n"+
            (item.link.isEmpty()?"":"🔗 "+item.link+"\n")+
            "🔍 ترجمه ماشینی تیتر عمومی، نه متن کامل مقاله.\n"+
            "منبع: "+FEED_LABELS[i]+" | مترجم: MyMemory";
    }
    private static String dayNow(){return LocalDate.now(ZoneId.systemDefault()).toString();}
    private static boolean passed(LocalTime now,String time){
        return !now.isBefore(LocalTime.parse(time,CLOCK));
    }
    private boolean due(long id,boolean lesson,String day,LocalTime time){
        String enable=(lesson?LESSON_ON:WORDS_ON)+id;
        String sent=(lesson?LESSON_SENT:WORDS_SENT)+id;
        return prefs.getBoolean(enable,false)&&!day.equals(prefs.getString(sent,""))&&
            passed(time,getClock(id,lesson));
    }

    /** Background periodic check. Persistent per-user/per-day keys prevent duplicates. */
    void tick(Eligibility eligible,Delivery delivery){
        String day=dayNow();
        LocalTime time=LocalTime.now(ZoneId.systemDefault());
        Set<String> userIds=new HashSet<>(prefs.getStringSet(USERS,new HashSet<String>()));
        Map<String,List<Long>> waitingNews=new HashMap<>();
        for(String value:userIds){
            long id;
            try {id=Long.parseLong(value);}catch(Exception e){continue;}
            if(!eligible.allowed(id))continue;
            if(due(id,true,day,time)) {
                String lang=prefs.getString(LESSON_LANG+id,"es");
                try {
                    delivery.send(id,lessonText(lang,LocalDate.now(ZoneId.systemDefault())));
                    prefs.edit().putString(LESSON_SENT+id,day).apply();
                }catch(Exception ignored){ }
            }
            if(due(id,false,day,time)) {
                String lang=prefs.getString(WORDS_LANG+id,"es");
                try {
                    delivery.send(id,wordsText(lang,LocalDate.now(ZoneId.systemDefault()),false));
                    prefs.edit().putString(WORDS_SENT+id,day).apply();
                }catch(Exception ignored){ }
            }
            for(String feed:feeds(id)){
                if(day.equals(prefs.getString(TRANS_SENT+id+"_"+feed,"")))continue;
                waitingNews.computeIfAbsent(feed,key->new ArrayList<>()).add(id);
            }
        }
        for(Map.Entry<String,List<Long>> source:waitingNews.entrySet()){
            int i=feedIndex(source.getKey());
            if(i<0)continue;
            try {
                NewsItem item=fetchLatest(FEED_URLS[i]);
                String translated=translateTitle(item.title,FEED_LANGS[i]);
                String message=formatNews(i,item,translated);
                for(Long user:source.getValue()){
                    // Recheck opt-in to avoid sending after opt-out while network request was running.
                    if(!eligible.allowed(user)||!feeds(user).contains(source.getKey()))continue;
                    try {
                        delivery.send(user,message);
                        prefs.edit().putString(TRANS_SENT+user+"_"+source.getKey(),day).apply();
                    }catch(Exception ignored){ }
                }
            }catch(Exception ignored){ /* API problems never send made-up translations */ }
        }
    }
}
