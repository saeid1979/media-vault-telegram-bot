package com.javid.downloader;

import android.content.SharedPreferences;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Private, owner-only, offline lesson summaries inspired by Aula Internacional Plus 2.
 * No textbook pages or copyrighted passages are redistributed. */
final class AulaPrivateLessons {
    interface Sender { void send(long chatId, String message) throws Exception; }
    private static final String ENABLED="aula_private_enabled";
    private static final String OWNER="aula_private_owner";
    private static final String START="aula_private_start";
    private static final String AM_SENT="aula_private_am_sent";
    private static final String PM_SENT="aula_private_pm_sent";
    private static final DateTimeFormatter CLOCK=DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT);
    private final SharedPreferences p;
    AulaPrivateLessons(SharedPreferences p){this.p=p;}

    static boolean accepts(String text){
        if(text==null)return false;
        String command=text.trim().split("\\s+")[0].split("@",2)[0].toLowerCase(Locale.ROOT);
        return command.equals("/aula")||command.equals("/aula_on")||
            command.equals("/aula_off")||command.equals("/aula_now")||
            command.equals("/aula_review")||command.equals("/aula_status");
    }
    private static final String[] UNITS={
        "El español y tú|عادت‌های یادگیری زبان|desde hace / porque / para|Estudio español desde hace dos años.|دو سال است اسپانیایی می‌خوانم.|¿Por qué estudias español?",
        "Una vida de película|زندگی‌نامه و گذشته|pretérito indefinido|Ayer empecé un curso nuevo.|دیروز یک دوره جدید را شروع کردم.|¿Qué hiciste ayer?",
        "Yo soy así|توصیف شخصیت و روابط|ser / tener / parecerse|Mi hermano es simpático y tiene el pelo corto.|برادرم خوش‌برخورد است و موهای کوتاه دارد.|¿Cómo es tu mejor amigo?",
        "Hogar, dulce hogar|خانه و مقایسه|más ... que / estar|Mi piso es más luminoso que el anterior.|خانه من از قبلی روشن‌تر است.|¿Dónde está la mesa?",
        "¿Cómo va todo?|درخواست مؤدبانه|estar + gerundio / podría|¿Podrías ayudarme? Estoy buscando una dirección.|می‌توانی کمکم کنی؟ دنبال یک آدرس می‌گردم.|¿Qué estás haciendo ahora?",
        "Guía del ocio|تفریح و تجربه|pretérito perfecto / ir a|Este mes he visitado un museo.|این ماه از یک موزه بازدید کرده‌ام.|¿Qué has hecho esta semana?",
        "¿Comes de todo?|غذا و عادت غذایی|soler + infinitivo / se|Suelo cocinar en casa.|معمولاً در خانه آشپزی می‌کنم.|¿Qué sueles desayunar?",
        "Estamos muy bien|سلامتی و توصیه|doler / imperativo|Me duele la cabeza. Descansa un poco.|سرم درد می‌کند. کمی استراحت کن.|¿Qué haces cuando te encuentras mal?",
        "Antes y ahora|عادت‌های گذشته|pretérito imperfecto|Antes vivía lejos, ahora vivo cerca.|قبلاً دور زندگی می‌کردم، حالا نزدیک زندگی می‌کنم.|¿Cómo era tu vida antes?",
        "Momentos especiales|خاطرات و روایت|indefinido frente a imperfecto|Cuando era pequeño, viajé a Madrid.|وقتی کوچک بودم به مادرید سفر کردم.|¿Qué pasó aquel día?"
    };
    private static final int[] STARTS={10,24,38,52,66,80,94,108,122,136};
    private static final int LAST_PAGE=149;
    private int day(){
        String started=p.getString(START,"");
        if(started.isEmpty())return 0;
        try {
            long elapsed=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(started),LocalDate.now(ZoneId.systemDefault()));
            return (int)Math.max(0,Math.min(10000,elapsed));
        }catch(Exception ex){return 0;}
    }
    private static int firstPage(int day){return 10+5*day;}
    private static String[] topic(int page){
        int unit=0;
        for(int i=0;i<STARTS.length;i++)if(page>=STARTS[i])unit=i;
        return UNITS[unit].split("\\|",-1);
    }
    String morning(int day){
        int from=firstPage(day);
        if(from>LAST_PAGE)return "🎓 دوره ۲۸ روزه Aula Internacional Plus 2 به پایان رسید. برای مرور /aula_review";
        int to=Math.min(LAST_PAGE,from+4);
        String[] a=topic(from);
        StringBuilder out=new StringBuilder("🇪🇸 آموزش خصوصی اسپانیایی A2 | روز "+(day+1)+"\n");
        out.append("📖 بازه مطالعه: صفحات چاپی ").append(from).append(" تا ").append(to).append("\n");
        out.append("📚 موضوع: ").append(a[0]).append(" — ").append(a[1]).append("\n\n");
        out.append("🧠 نکته گرامری: ").append(a[2]).append("\n");
        out.append("✍️ مثال: ").append(a[3]).append("\n🇮🇷 معنی: ").append(a[4]).append("\n\n");
        out.append("🔍 تحلیل: فعل جمله را پیدا کن، زمان آن را مشخص کن و جمله را برای زندگی خودت تغییر بده.\n");
        out.append("🗣️ تمرین گفتاری: ").append(a[5]).append("\n");
        out.append("📝 تمرین نوشتاری: با الگوی بالا سه جمله جدید بساز.\n");
        out.append("📌 این درس توضیح و مثال تألیفی بر اساس موضوعات کتاب است، نه بازنشر پنج صفحه کتاب.");
        return out.toString();
    }
    String evening(int day){
        int from=firstPage(day);
        if(from>LAST_PAGE)return "🎓 دوره کامل شد. برای مرور /aula_review";
        String[] a=topic(from);
        return "🌙 مرور شبانه اسپانیایی A2 | روز "+(day+1)+"\n"+
            "📖 صفحات "+from+" تا "+Math.min(LAST_PAGE,from+4)+"\n\n"+
            "❓ سؤال ۱: "+a[5]+"\n"+
            "❓ سؤال ۲: در جمله «"+a[3]+"» فعل چیست و چه زمانی دارد؟\n"+
            "❓ سؤال ۳: جمله را منفی کن.\n"+
            "❓ سؤال ۴: معنی عبارت «"+a[2]+"» را توضیح بده.\n\n"+
            "✅ پاسخ نمونه: "+a[3]+"\n🇮🇷 "+a[4]+"\n"+
            "🎯 تکلیف: پاسخ‌هایت را در یک پیام خصوصی برای بات بنویس.";
    }
    String handle(long id,String text){
        String cmd=text.trim().split("\\s+")[0].split("@",2)[0].toLowerCase(Locale.ROOT);
        long owner=p.getLong(OWNER,0);
        if(owner!=0 && owner!=id)return "⛔ این دوره فقط برای مدیر اصلی فعال است.";
        if(cmd.equals("/aula_on")){
            SharedPreferences.Editor e=p.edit().putLong(OWNER,id).putBoolean(ENABLED,true);
            if(p.getString(START,"").isEmpty())e.putString(START,LocalDate.now(ZoneId.systemDefault()).toString());
            e.apply();
            return "✅ آموزش خصوصی فعال شد. هر روز 08:30 درس و 22:00 مرور، به وقت Europe/Madrid.\n"+morning(day());
        }
        if(cmd.equals("/aula_off")){p.edit().putBoolean(ENABLED,false).apply();return "🔕 آموزش خصوصی متوقف شد.";}
        if(cmd.equals("/aula_now"))return morning(day());
        if(cmd.equals("/aula_review"))return evening(day());
        return "📚 Aula A2 خصوصی\n"+
            "وضعیت: "+(p.getBoolean(ENABLED,false)?"فعال":"خاموش")+
            "\nزمان: 08:30 و 22:00 (Europe/Madrid)\nروز: "+(day()+1)+
            "\n/aula_on فعال‌سازی\n/aula_now درس امروز\n/aula_review آزمون شب\n/aula_off توقف";
    }
    void tick(Sender sender){
        if(!p.getBoolean(ENABLED,false))return;
        long owner=p.getLong(OWNER,0);
        if(owner<=0)return;
        ZoneId zone=ZoneId.of("Europe/Madrid");
        LocalDate today=LocalDate.now(zone);
        LocalTime now=LocalTime.now(zone);
        String date=today.toString();
        if(!now.isBefore(LocalTime.parse("08:30",CLOCK)) && !date.equals(p.getString(AM_SENT,""))){
            try {sender.send(owner,morning(day()));p.edit().putString(AM_SENT,date).apply();}
            catch(Exception ignored){return;}
        }
        if(!now.isBefore(LocalTime.parse("22:00",CLOCK)) && !date.equals(p.getString(PM_SENT,""))){
            try {sender.send(owner,evening(day()));p.edit().putString(PM_SENT,date).apply();}
            catch(Exception ignored){/* retry on next tick */}
        }
    }
}
