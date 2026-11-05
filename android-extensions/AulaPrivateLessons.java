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
    private static final String EXTRA="aula_extra_pages";
    private static final DateTimeFormatter CLOCK=DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT);
    private final SharedPreferences p;
    AulaPrivateLessons(SharedPreferences p){this.p=p;}

    static boolean accepts(String text){
        if(text==null)return false;
        String command=text.trim().split("\\s+")[0].split("@",2)[0].toLowerCase(Locale.ROOT);
        return command.equals("/aula")||command.equals("/aula_on")||
            command.equals("/aula_off")||command.equals("/aula_now")||
            command.equals("/aula_review")||command.equals("/aula_status")||command.equals("/aula_next5")||command.equals("/aula_grammar")||command.equals("/aula_speak")||command.equals("/aula_listen")||command.equals("/aula_progress");
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
    private int lessonIndex(){return day()+p.getInt(EXTRA,0)/5;}
    private static String[] topic(int page){
        int unit=0;
        for(int i=0;i<STARTS.length;i++)if(page>=STARTS[i])unit=i;
        return UNITS[unit].split("\\|",-1);
    }
    // Each five-page study block contains five ORIGINAL teaching messages.
    // Printed-page references are a study guide, not a reproduction of the book.
    private static final String[] PATTERNS={
        "Estudio español porque quiero hablar con mis vecinos.|Aprendo español para trabajar mejor.|Estudio desde hace seis meses.|¿Para qué aprendes español?",
        "Ayer compré un libro.|La semana pasada fui al centro.|El lunes hablé con mi profesora.|¿Qué hiciste el fin de semana?",
        "Mi amiga es muy amable.|Mi hermano tiene mucha paciencia.|Nos parecemos en el carácter.|¿Cómo describirías a tu familia?",
        "Mi habitación es más grande que la tuya.|La cocina está al lado del salón.|Este piso es menos caro.|¿Cómo es tu casa?",
        "Estoy preparando la cena.|¿Podrías repetir, por favor?|Ahora estamos estudiando.|¿Qué estás haciendo?",
        "Hoy he aprendido cinco palabras.|Esta semana hemos ido al cine.|Mañana vamos a visitar el museo.|¿Qué has hecho hoy?",
        "Suelo desayunar a las ocho.|En España se cena bastante tarde.|Me gusta cocinar verduras.|¿Qué sueles comer?",
        "Me duele la garganta.|Debes descansar un poco.|Bebe agua y duerme bien.|¿Qué haces para cuidarte?",
        "Antes vivía en otra ciudad.|Cuando era niño jugaba mucho.|Ahora trabajo desde casa.|¿Cómo era tu vida antes?",
        "Cuando caminaba, empezó a llover.|Ayer conocí a un amigo.|Era tarde y hacía frío.|¿Qué pasó ayer?"
    };
    private static final String[] NOTES={
        "porque بیانگر علت است؛ para + مصدر هدف را نشان می‌دهد؛ desde hace + مدت زمان برای کاری است که از گذشته شروع شده و ادامه دارد.",
        "pretérito indefinido برای رویداد پایان‌یافته در گذشته است. برای فعل‌های -ar مانند hablar: hablé, hablaste, habló, hablamos, hablasteis, hablaron.",
        "ser برای ویژگی نسبتاً پایدار و estar برای حالت یا مکان است؛ tener برای مالکیت و بعضی ویژگی‌ها به کار می‌رود.",
        "مقایسه: más + صفت + que؛ کمتر: menos + صفت + que؛ برای مکان معمولاً estar استفاده می‌شود.",
        "estar + gerundio برای کاری است که در جریان است؛ مصدر -ar به -ando و -er/-ir به -iendo تبدیل می‌شود.",
        "pretérito perfecto: haber در زمان حال + participio؛ he, has, ha, hemos, habéis, han. ir a + مصدر برای برنامه آینده است.",
        "soler + مصدر یعنی معمولاً کاری را انجام دادن: suelo, sueles, suele, solemos, soléis, suelen.",
        "doler مانند gustar به کار می‌رود: me duele + اسم مفرد؛ me duelen + اسم جمع. امر مثبت tú در بسیاری از افعال شبیه سوم‌شخص مفرد حال است.",
        "pretérito imperfecto برای عادت یا توصیف گذشته است: hablaba, comía, vivía. آن را با indefinido اشتباه نگیر.",
        "imperfecto صحنه و شرایط را توصیف می‌کند؛ indefinido رویداد اصلی و کامل‌شده را روایت می‌کند."
    };
    private static String[] parts(int page){return PATTERNS[unitIndex(page)].split("\\|",-1);}
    private static int unitIndex(int page){int u=0;for(int i=0;i<STARTS.length;i++)if(page>=STARTS[i])u=i;return u;}
    // Page-specific ORIGINAL study guides based on the printed unit's distinct activities.
    private static final String[] PAGE_GUIDES={
        "10§آشنایی با درس و هدف‌های یادگیری§هدف این واحد شناخت عادت‌های یادگیری و توضیح انگیزه مطالعه زبان است.§Quiero aprender español para comunicarme mejor.|می‌خواهم اسپانیایی یاد بگیرم تا بهتر ارتباط برقرار کنم.§هدف شخصی خود را با Quiero + infinitivo بیان کن.",
        "11§چهار زبان در یک خانواده§صفحه درباره خانواده چندزبانه و انتخاب زبان در روابط خانوادگی است. hablar con alguien en + idioma یعنی با کسی به زبانی صحبت کردن.§En casa hablamos persa, pero en clase hablamos español.|در خانه فارسی حرف می‌زنیم، اما در کلاس اسپانیایی.§سه نفر را نام ببر و بگو با هر کدام به چه زبانی صحبت می‌کنی.",
        "12§مصاحبه ثبت‌نام در مدرسه زبان§برای پرسیدن علت از ¿Por qué...? و برای بیان هدف از para + infinitivo استفاده کن. ¿Desde cuándo...? زمان شروع را می‌پرسد.§Estudio español porque vivo en España.|اسپانیایی می‌خوانم چون در اسپانیا زندگی می‌کنم.§به سه سؤال پاسخ بده: ¿Por qué estudias? ¿Desde cuándo? ¿Qué te cuesta más?",
        "13§سبک‌های متفاوت یادگیری§صفحه سبک‌های دیداری، شنیداری، عملی، گروهی و فردی را مقایسه می‌کند. me gusta + infinitivo برای بیان علاقه به فعالیت است.§Me gusta escuchar diálogos y tomar notas.|دوست دارم مکالمه گوش کنم و یادداشت بردارم.§بگو کدام روش برای تو بهتر است و با porque دلیل بیاور.",
        "14§زندگی افراد خارجی در اسپانیا§صفحه درباره تجربه زندگی چند مهاجر در اسپانیا و مزایا و دشواری‌های زندگی آنهاست. ساختار me gusta + sustantivo را تمرین کن.§Me gusta vivir aquí, aunque a veces echo de menos a mi familia.|زندگی اینجا را دوست دارم، هرچند گاهی دلتنگ خانواده‌ام می‌شوم.§دو مزیت و دو دشواری زندگی در کشور جدید را بنویس.",
        "15§صرف زمان حال و فعل‌های روزمره§فعل‌های -ar، -er و -ir در زمان حال شناسه‌های متفاوت دارند: hablo، como، vivo. فاعل اغلب حذف می‌شود.§Trabajo por la mañana y estudio por la tarde.|صبح کار می‌کنم و عصر درس می‌خوانم.§فعل‌های hablar، comer و vivir را برای yo، tú و nosotros صرف کن.",
        "16§مدت زمان با desde و desde hace§desde + نقطه شروع و desde hace + مدت زمان است. هر دو با زمان حال برای کاری که ادامه دارد به کار می‌روند.§Vivo en Salamanca desde 2023.|از سال ۲۰۲۳ در سالامانکا زندگی می‌کنم.§دو جمله بساز: یکی با desde و یکی با desde hace.",
        "17§تجربه زندگی در خارج از کشور§برای توصیف احساسات و تجربه‌ها از me siento، me parece و creo que استفاده کن؛ اینها نظر و تجربه شخصی را جدا می‌کنند.§A veces me siento lejos de casa, pero aprendo cosas nuevas.|گاهی احساس می‌کنم از خانه دورم، اما چیزهای تازه یاد می‌گیرم.§در چهار جمله تجربه خود را توصیف کن و از pero استفاده کن.",
        "18§مشکلات یادگیری با me cuesta§me cuesta + مصدر یعنی انجام کاری برای من دشوار است. برای اسم جمع me cuestan به کار می‌رود.§Me cuesta entender conversaciones rápidas.|فهمیدن مکالمه‌های سریع برایم سخت است.§سه مشکل واقعی زبان‌آموزی را با me cuesta بنویس.",
        "19§راهکارهای یادگیری با va bien§برای توصیه می‌توان گفت va bien + infinitivo، es bueno + infinitivo و tienes que + infinitivo؛ شدت توصیه متفاوت است.§Para recordar palabras, va bien escribir ejemplos.|برای به خاطر سپردن کلمات، نوشتن مثال مفید است.§برای مشکل صفحه قبل سه راهکار متفاوت پیشنهاد بده."
    };
    private static String pageGuide(int page){
        for(String record:PAGE_GUIDES){String[] x=record.split("§",-1);if(Integer.parseInt(x[0])==page){
            String[] example=x[3].split("\\|",-1);
            return "===درس صفحه "+page+"===\n🎯 "+x[1]+"\n📘 آموزش: "+x[2]+
                "\n✍️ مثال: "+example[0]+"\n🇮🇷 "+(example.length>1?example[1]:"")+
                "\n🧩 تمرین اختصاصی: "+x[4]+"\n🗣️ مکالمه: پاسخ خود را در سه جمله اسپانیایی بگو.";
        }}
        return null;
    }
    String morning(int day){
        int from=firstPage(day);
        if(from>LAST_PAGE)return "🎓 دوره تمام شد. برای مرور /aula_review";
        int to=Math.min(LAST_PAGE,from+4);
        String[] a=topic(from);
        String[] examples=parts(from);
        StringBuilder b=new StringBuilder("🇪🇸 آموزش مفصل اسپانیایی A2 | بسته "+(day+1)+
            "\n📖 راهنمای مطالعه صفحات چاپی "+from+" تا "+to+
            "\nموضوع: "+a[0]+" — "+a[1]+
            "\nتوجه: توضیحات و تمرین‌های زیر تألیفی هستند؛ متن صفحات کتاب بازنشر نمی‌شود.");
        for(int page=from;page<=to;page++){
            String guide=pageGuide(page);
            if(guide!=null){b.append("\n\n").append(guide);continue;}
            String[] current=topic(page);
            String[] examples=parts(page);
            int slot=Math.floorMod(page-10,4);
            b.append("\n\n===درس صفحه ").append(page).append("===\n");
            b.append("📌 تمرین تکمیلی تألیفی بر اساس موضوع واحد (نه شرح دقیق متن این صفحه)\n");
            b.append("🎯 موضوع: ").append(current[1]).append("\n");
            b.append("📘 نکته: ").append(NOTES[unitIndex(page)]).append("\n");
            b.append("✍️ مثال: ").append(examples[slot]).append("\n");
            String[] tasks={
                "سه پرسش جدید درباره این موضوع بنویس و پاسخ بده.",
                "مثال را به گذشته یا آینده تغییر بده و تفاوت را توضیح بده.",
                "یک مکالمه چهارخطی در موقعیت واقعی بساز.",
                "سه واژه مرتبط پیدا کن و با هرکدام یک جمله بساز."
            };
            b.append("🧩 فعالیت ویژه: ").append(tasks[slot]).append("\n");
            b.append("⚠️ برای شرح دقیق این صفحه باید محتوای آن جداگانه بررسی شود.");
        }
        b.append("\n\n🌙 ساعت ۲۲ مرور و سؤال‌های تمرینی ارسال می‌شود.");
        return b.toString();
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
        if(cmd.equals("/aula_next5")){
            int next=firstPage(lessonIndex()+1);
            if(next>LAST_PAGE)return "🎓 دوره کامل شده است.";
            p.edit().putInt(EXTRA,p.getInt(EXTRA,0)+5).apply();
            return morning(lessonIndex());
        }
        if(cmd.equals("/aula_now"))return morning(lessonIndex());
        if(cmd.equals("/aula_grammar")){
            String[] a=topic(firstPage(lessonIndex()));
            return "📘 گرامر: "+a[2]+"\nمثال: "+a[3]+"\nمعنی: "+a[4]+
                "\nتمرین: سه جمله مشابه درباره زندگی خودت بساز.";
        }
        if(cmd.equals("/aula_speak")){
            String[] a=topic(firstPage(lessonIndex()));
            return "🗣️ تمرین مکالمه: "+a[5]+"\nپاسخ را بلند بگو و سپس به اسپانیایی بنویس.";
        }
        if(cmd.equals("/aula_listen")){
            String[] a=topic(firstPage(lessonIndex()));
            return "🎧 تمرین شنیداری: "+a[3]+
                "\nبا قابلیت خواندن متن گوشی گوش بده و سپس جمله را بازنویسی کن. فایل صوتی مستقل هنوز موجود نیست.";
        }
        if(cmd.equals("/aula_progress"))return "📊 بازه فعلی: صفحات "+
            firstPage(lessonIndex())+" تا "+Math.min(LAST_PAGE,firstPage(lessonIndex())+4);

        if(cmd.equals("/aula_review"))return evening(lessonIndex());
        return "📚 Aula A2 خصوصی\n"+
            "وضعیت: "+(p.getBoolean(ENABLED,false)?"فعال":"خاموش")+
            "\nزمان: 08:30 و 22:00 (Europe/Madrid)\nروز: "+(day()+1)+
            "\n/aula_on فعال‌سازی\n/aula_now درس امروز\n/aula_next5 پنج صفحه بعد\n/aula_grammar گرامر\n/aula_speak مکالمه\n/aula_listen شنیداری\n/aula_progress پیشرفت\n/aula_review آزمون شب\n/aula_off توقف";
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
