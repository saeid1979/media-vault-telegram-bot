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
    private static final String RESTART_MARKER="aula_curriculum_version";
    private static final int CURRICULUM_VERSION=378;
    private static final DateTimeFormatter CLOCK=DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT);
    private final SharedPreferences p;
    AulaPrivateLessons(SharedPreferences p){this.p=p;resetOnCurriculumUpgrade();}
    private void resetOnCurriculumUpgrade(){
        if(p.getInt(RESTART_MARKER,0)>=CURRICULUM_VERSION)return;
        SharedPreferences.Editor e=p.edit().putInt(RESTART_MARKER,CURRICULUM_VERSION)
            .putString(START,LocalDate.now(ZoneId.of("Europe/Madrid")).toString())
            .putInt(EXTRA,0).remove(AM_SENT).remove(PM_SENT);
        e.apply();
    }

    static boolean accepts(String text){
        if(text==null)return false;
        String command=text.trim().split("\\s+")[0].split("@",2)[0].toLowerCase(Locale.ROOT);
        return command.equals("/aula")||command.equals("/aula_on")||
            command.equals("/aula_off")||command.equals("/aula_now")||
            command.equals("/aula_review")||command.equals("/aula_status")||command.equals("/aula_next5")||command.equals("/aula_grammar")||command.equals("/aula_speak")||command.equals("/aula_listen")||command.equals("/aula_progress")||command.equals("/aula_page");
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
            long elapsed=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(started),LocalDate.now(ZoneId.of("Europe/Madrid")));
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
    private static final String[][] EXPANDED_GUIDES={
        {"10","EL ESPAÑOL Y TÚ: هدف‌گذاری و عادت‌های یادگیری","🎯 هدف: درباره روش مطالعه و دلیل یادگیری زبان صحبت کنی.\n📘 گرامر ۱: quiero + مصدر؛ بعد از quiero فعل دوم صرف نمی‌شود: Quiero hablar, نه Quiero hablo.\n📘 گرامر ۲: porque + جمله برای دلیل؛ para + مصدر برای هدف. Estudio porque trabajo aquí. Estudio para hablar mejor.\n📘 گرامر ۳: me gusta + مصدر برای علاقه به فعالیت؛ Me gusta leer.\n📚 واژگان: aprender=یادگرفتن؛ practicar=تمرین‌کردن؛ mejorar=بهترکردن؛ objetivo=هدف؛ hábito=عادت؛ consejo=توصیه؛ dificultad=دشواری.\n🗣️ مکالمه تألیفی:\nA: ¿Por qué aprendes español?\nB: Porque vivo en España.\nA: ¿Para qué lo necesitas?\nB: Para hablar con mis vecinos.\n🇮🇷 چرا اسپانیایی یاد می‌گیری؟ چون در اسپانیا زندگی می‌کنم. برای چه نیاز داری؟ برای صحبت با همسایه‌ها.\n🧩 تمرین ۱: جای خالی را پر کن: Estudio español ___ comunicarme. / Estudio ___ vivo aquí.\n🧩 تمرین ۲: سه هدف شخصی را با Quiero + infinitivo بنویس.\n🧩 تمرین ۳: فرق علت و هدف را با دو مثال توضیح بده.\n🔑 پاسخ ۱: para / porque.\n🎤 بلند بخوان: Quiero aprender español para trabajar mejor. سپس بدون نگاه تکرار کن."},
        {"11","UNA FAMILIA CON CUATRO LENGUAS: خانواده چندزبانه","🎯 هدف: درباره زبان‌های خانواده، ارتباط و عادت‌ها حرف بزنی.\n📘 ساختار: hablar + idioma؛ Hablo persa y español. برای مخاطب: hablar con + persona؛ برای زبان مکالمه: hablar en + idioma.\n📘 زمان حال hablar: yo hablo، tú hablas، él habla، nosotros hablamos، vosotros habláis، ellos hablan. در اسپانیا vosotros برای جمع دوستانه رایج است.\n📘 تضاد: pero=اما؛ también=همچنین؛ en cambio=در مقابل. En casa hablamos persa, pero en el trabajo hablamos español.\n📚 واژگان: lengua materna=زبان مادری؛ bilingüe=دوزبانه؛ familia=خانواده؛ pareja=همسر/زوج؛ hijo=فرزند؛ comunicarse=ارتباط برقرار کردن؛ entender=فهمیدن.\n🗣️ مکالمه تألیفی:\nA: ¿Qué idiomas habláis en casa?\nB: Hablamos persa y a veces español.\nA: ¿Tus hijos entienden español?\nB: Sí, entienden bastante, pero hablan poco.\n🇮🇷 در خانه چه زبان‌هایی حرف می‌زنید؟ فارسی و گاهی اسپانیایی. فرزندانت اسپانیایی می‌فهمند؟ بله، نسبتاً خوب، اما کم صحبت می‌کنند.\n🧩 تمرین ۱: nosotros ___ (hablar) español.\n🧩 تمرین ۲: vosotros ___ (hablar) con la profesora.\n🧩 تمرین ۳: دو جمله درباره زبان‌های خانواده خود بنویس و با pero وصل کن.\n🔑 پاسخ: hablamos / habláis.\n🎤 چهار نوبت مکالمه را با نقش‌های متفاوت تمرین کن."},
        {"12","ENTREVISTA EN LA ESCUELA DE IDIOMAS: مصاحبه مدرسه زبان","🎯 هدف: به پرسش‌های ثبت‌نام و یادگیری پاسخ بدهی.\n📘 تفاوت ¿Por qué? (چرا، علت) و ¿Para qué? (برای چه هدفی): ¿Por qué estudias? Porque vivo aquí. ¿Para qué estudias? Para trabajar.\n📘 ¿Desde cuándo? نقطه شروع را می‌پرسد: Desde 2023. ¿Cuánto tiempo llevas...? مدت را می‌پرسد: Llevo dos años estudiando.\n📘 desde + زمان آغاز: desde enero؛ desde hace + مدت: desde hace seis meses. از زمان حال برای وضعیت ادامه‌دار استفاده کن: Estudio desde hace seis meses.\n📚 واژگان: matricularse=ثبت‌نام‌کردن؛ nivel=سطح؛ horario=برنامه زمانی؛ conversación=مکالمه؛ comprensión=درک؛ mejorar=بهبوددادن؛ dificultad=مشکل.\n🗣️ مکالمه تألیفی:\nA: ¿Desde cuándo estudias español?\nB: Desde hace ocho meses.\nA: ¿Qué te cuesta más?\nB: Entender cuando la gente habla rápido.\nA: ¿Para qué quieres mejorar?\nB: Para hablar con mis compañeros.\n🇮🇷 از چه مدت اسپانیایی می‌خوانی؟ هشت ماه است. چه چیزی سخت‌تر است؟ فهمیدن صحبت سریع مردم.\n🧩 تمرین ۱: ___ estudias español? Porque vivo en Salamanca.\n🧩 تمرین ۲: Estudio ___ hace un año.\n🧩 تمرین ۳: به سه سؤال مکالمه با اطلاعات واقعی خود پاسخ بده.\n🔑 پاسخ: Por qué / desde.\n🎤 نقش مصاحبه‌کننده و زبان‌آموز را عوض کن."},
        {"13","¿CÓMO APRENDES?: سبک‌های یادگیری","🎯 هدف: سبک‌های یادگیری را مقایسه کنی و روش مناسب خودت را توضیح بدهی.\n📘 متن این بخش سبک‌های مستقل/وابسته به زمینه، دیداری، شنیداری، حرکتی، گروهی و فردی را معرفی می‌کند؛ هیچ‌کدام برای همه افراد بهترین نیست.\n📘 me gusta + مصدر: Me gusta escuchar. me cuesta + مصدر: Me cuesta recordar palabras. me va bien + مصدر: Me va bien practicar en voz alta.\n📘 برای مقایسه: en cambio=در مقابل؛ mientras que=درحالی‌که؛ más que=بیشتر از. Prefiero escuchar más que memorizar listas.\n📚 واژگان: visual=دیداری؛ auditivo=شنیداری؛ cinestésico=حرکتی؛ tomar notas=یادداشت برداشتن؛ recordar=به خاطر سپردن؛ fluidez=روانی گفتار؛ corrección=درستی.\n🗣️ مکالمه تألیفی:\nA: ¿Cómo aprendes mejor?\nB: Escuchando diálogos y hablando.\nA: ¿Te gusta estudiar solo?\nB: A veces, pero prefiero practicar con alguien.\n🇮🇷 چطور بهتر یاد می‌گیری؟ با گوش‌دادن به مکالمه و صحبت‌کردن. دوست داری تنها مطالعه کنی؟ گاهی، اما تمرین با یک نفر را ترجیح می‌دهم.\n🧩 تمرین ۱: Me ___ escuchar pódcasts. (gustar)\n🧩 تمرین ۲: Me ___ memorizar verbos. (costar)\n🧩 تمرین ۳: دو روش یادگیری را با en cambio مقایسه کن.\n🔑 پاسخ: gusta / cuesta.\n🎤 دو دقیقه درباره سبک یادگیری خود صحبت کن."},
        {"14","LOS NUEVOS ESPAÑOLES: زندگی در اسپانیا","🎯 هدف: زندگی، کار، برنامه روزانه و احساسات افراد ساکن اسپانیا را مقایسه کنی.\n📘 این بخش تجربه چند نفر با سبک‌های زندگی متفاوت را بررسی می‌کند؛ هنگام مقایسه به کار، محل زندگی، ساعت کار و فعالیت‌هایشان توجه کن.\n📘 me gusta + اسم مفرد: Me gusta la ciudad. me gustan + اسم جمع: Me gustan los parques. me gusta + مصدر: Me gusta caminar.\n📘 preferir: prefiero، prefieres، prefiere، preferimos، preferís، prefieren. Prefiero vivir cerca del centro.\n📘 aunque=اگرچه؛ pero=اما؛ porque=چون. Me gusta mi trabajo, aunque el horario es difícil.\n📚 واژگان: extranjero=خارجی؛ horario=برنامه زمانی؛ jornada=روز کاری؛ barrio=محله؛ echar de menos=دلتنگ شدن؛ acostumbrarse=عادت کردن؛ elegir=انتخاب کردن.\n🗣️ مکالمه تألیفی:\nA: ¿Prefieres vivir en una ciudad grande?\nB: No, prefiero una ciudad tranquila.\nA: ¿Por qué?\nB: Porque puedo ir andando a muchos sitios.\n🇮🇷 ترجیح می‌دهی در شهر بزرگ زندگی کنی؟ نه، شهر آرام را ترجیح می‌دهم. چرا؟ چون می‌توانم پیاده به جاهای زیادی بروم.\n🧩 تمرین ۱: Me ___ los barrios tranquilos. (gustar)\n🧩 تمرین ۲: Yo ___ vivir cerca de mi trabajo. (preferir)\n🧩 تمرین ۳: دو سبک زندگی را با pero و aunque مقایسه کن.\n🔑 پاسخ: gustan / prefiero.\n🎤 در چهار جمله درباره شهر و زندگی دلخواهت حرف بزن."}
    };
    // Original, page-aligned practice: textbook is not reproduced.
    private static final String[][] RICH_PAGES={
        {"10","هدف‌ها و عادت‌های یادگیری","Quiero + infinitivo = می‌خواهم کاری کنم\nNecesito + infinitivo = لازم است کاری کنم\nEstudio ... para + infinitivo = برای هدفی درس می‌خوانم\nEstudio ... porque + oración = چون دلیلی دارم\nMe gusta + infinitivo = از انجام کاری خوشم می‌آید\nTengo que + infinitivo = باید کاری کنم","1. Quiero hablar español con soltura. = می‌خواهم اسپانیایی را روان صحبت کنم.\n2. Necesito practicar todos los días. = لازم است هر روز تمرین کنم.\n3. Estudio para entender a mis compañeros. = درس می‌خوانم تا همکارانم را بفهمم.\n4. Aprendo español porque vivo en España. = اسپانیایی یاد می‌گیرم چون در اسپانیا زندگی می‌کنم.\n5. Me gusta aprender palabras nuevas. = دوست دارم واژه‌های جدید یاد بگیرم.\n6. Tengo que escuchar más conversaciones. = باید مکالمه‌های بیشتری گوش کنم.\n7. Quiero mejorar mi pronunciación. = می‌خواهم تلفظم را بهتر کنم.\n8. Necesito leer textos sencillos. = لازم است متن‌های ساده بخوانم.\n9. Practico para perder el miedo a hablar. = تمرین می‌کنم تا ترس از صحبت کردن را از دست بدهم.\n10. Me gusta estudiar con ejemplos. = دوست دارم با مثال مطالعه کنم.","فارسی: «می‌خواهم بهتر صحبت کنم.» → Quiero hablar mejor. نکته: hablar بعد از quiero مصدر است.\nفارسی: «چون اینجا کار می‌کنم، اسپانیایی می‌خوانم.» → Estudio español porque trabajo aquí. نکته: porque دلیل را معرفی می‌کند.\nفارسی: «برای پیدا کردن کار، درس می‌خوانم.» → Estudio para encontrar trabajo. نکته: para + مصدر هدف را نشان می‌دهد."},
        {"11","خانواده چندزبانه و زبان‌های روزمره","Hablo + idioma = زبانی صحبت می‌کنم\nHablo con + persona = با کسی حرف می‌زنم\nHablamos en + idioma = به زبانی حرف می‌زنیم\nEn casa + verbo = در خانه...\nCon mis amigos + verbo = با دوستانم...\n..., pero ... = تضاد دو جمله","1. Hablo persa y español. = فارسی و اسپانیایی صحبت می‌کنم.\n2. Hablo con mi hermana por teléfono. = با خواهرم تلفنی صحبت می‌کنم.\n3. En casa hablamos persa. = در خانه فارسی حرف می‌زنیم.\n4. En clase hablamos en español. = در کلاس به اسپانیایی حرف می‌زنیم.\n5. Mis amigos entienden inglés. = دوستانم انگلیسی می‌فهمند.\n6. Mi madre habla conmigo en persa. = مادرم با من به فارسی صحبت می‌کند.\n7. Mi compañero habla muy rápido. = همکارم خیلی سریع حرف می‌زند.\n8. Entiendo bastante, pero hablo poco. = نسبتاً خوب می‌فهمم، اما کم حرف می‌زنم.\n9. Vosotros habláis español muy bien. = شماها اسپانیایی را خیلی خوب حرف می‌زنید.\n10. Nosotros practicamos juntos. = ما با هم تمرین می‌کنیم.","فاعل «ما» و hablar → Nosotros hablamos. شناسه -amos برای nosotros در افعال -ar است.\nفاعل «شماها» در اسپانیا و hablar → Vosotros habláis. شکل vosotros را با ustedes اشتباه نگیر.\nفارسی: «من می‌فهمم اما کم حرف می‌زنم.» → Entiendo, pero hablo poco. pero تضاد را نشان می‌دهد."},
        {"12","مصاحبه مدرسه زبان و پرسیدن دلیل","¿Por qué + verbo? = چرا؟\nPorque + oración = چون...\n¿Para qué + verbo? = برای چه هدفی؟\nPara + infinitivo = برای انجام کاری\n¿Desde cuándo + verbo? = از چه زمانی؟\nDesde hace + duración = به مدت...","1. ¿Por qué estudias español? = چرا اسپانیایی می‌خوانی؟\n2. Porque quiero comunicarme mejor. = چون می‌خواهم بهتر ارتباط برقرار کنم.\n3. ¿Para qué necesitas español? = برای چه هدفی به اسپانیایی نیاز داری؟\n4. Para hablar con mis clientes. = برای صحبت با مشتریانم.\n5. ¿Desde cuándo vives aquí? = از چه زمانی اینجا زندگی می‌کنی؟\n6. Vivo aquí desde enero. = از ژانویه اینجا زندگی می‌کنم.\n7. Estudio desde hace seis meses. = شش ماه است درس می‌خوانم.\n8. Me cuesta entender la radio. = فهمیدن رادیو برایم دشوار است.\n9. Me gusta trabajar en grupo. = کار گروهی را دوست دارم.\n10. Quiero matricularme en el curso. = می‌خواهم در دوره ثبت‌نام کنم.","«از سال ۲۰۲۴» → desde 2024. بعد از desde یک نقطه شروع می‌آید.\n«سه ماه است» → desde hace tres meses. بعد از desde hace مدت زمان می‌آید.\n«برای کار کردن» → para trabajar؛ ولی «چون کار می‌کنم» → porque trabajo. مصدر در برابر جمله صرف‌شده."},
        {"13","روش‌های یادگیری و ترجیح‌ها","Me gusta + infinitivo = دوست دارم...\nMe cuesta + infinitivo = برایم سخت است...\nPrefiero + infinitivo = ترجیح می‌دهم...\nMe va bien + infinitivo = برایم مفید است...\nAprendo mejor + gerundio = با انجام کاری بهتر یاد می‌گیرم\n..., en cambio, ... = در مقابل","1. Me gusta escuchar diálogos. = دوست دارم مکالمه گوش کنم.\n2. Me cuesta recordar los verbos. = به خاطر سپردن فعل‌ها برایم سخت است.\n3. Prefiero estudiar por la mañana. = ترجیح می‌دهم صبح مطالعه کنم.\n4. Me va bien repetir en voz alta. = بلند تکرار کردن برایم مفید است.\n5. Aprendo mejor escribiendo ejemplos. = با نوشتن مثال بهتر یاد می‌گیرم.\n6. Me gusta estudiar en grupo. = مطالعه گروهی را دوست دارم.\n7. Prefiero hablar antes que memorizar listas. = ترجیح می‌دهم صحبت کنم تا فهرست‌ها را حفظ کنم.\n8. Necesito corregir mis errores. = لازم است اشتباهاتم را اصلاح کنم.\n9. Entiendo las reglas, pero me cuesta hablar. = قواعد را می‌فهمم اما صحبت کردن برایم سخت است.\n10. Mi amigo aprende escuchando; yo, en cambio, leyendo. = دوستم با گوش دادن یاد می‌گیرد؛ من در مقابل با خواندن.","«حفظ کلمات برایم سخت است» → Me cuesta memorizar palabras. بعد از cuesta مصدر می‌آید.\n«با گوش دادن بهتر یاد می‌گیرم» → Aprendo mejor escuchando. شکل -ando روش انجام کار را بیان می‌کند.\n«ترجیح می‌دهم با دوستان تمرین کنم» → Prefiero practicar con amigos. بعد از prefiero مصدر می‌آید."},
        {"14","تجربه زندگی در اسپانیا","Me gusta + singular = یک چیز را دوست دارم\nMe gustan + plural = چند چیز را دوست دارم\nMe gusta + infinitivo = کاری را دوست دارم\nPrefiero + infinitivo = ترجیح می‌دهم...\nAunque + oración = اگرچه...\nEcho de menos + persona/cosa = دلتنگ ... می‌شوم","1. Me gusta la vida en España. = زندگی در اسپانیا را دوست دارم.\n2. Me gustan los parques de mi barrio. = پارک‌های محله‌ام را دوست دارم.\n3. Me gusta caminar por el centro. = قدم زدن در مرکز شهر را دوست دارم.\n4. Prefiero vivir en una ciudad tranquila. = ترجیح می‌دهم در شهری آرام زندگی کنم.\n5. Aunque trabajo mucho, estudio español. = اگرچه زیاد کار می‌کنم، اسپانیایی می‌خوانم.\n6. Echo de menos a mi familia. = دلتنگ خانواده‌ام می‌شوم.\n7. Mi horario es diferente. = برنامه زمانی من متفاوت است.\n8. Me cuesta entender cuando hablan rápido. = وقتی سریع حرف می‌زنند فهمیدن برایم سخت است.\n9. Vivo cerca del trabajo. = نزدیک محل کار زندگی می‌کنم.\n10. Me gusta mi barrio, pero es ruidoso. = محله‌ام را دوست دارم اما پر سروصداست.","«پارک‌ها را دوست دارم» → Me gustan los parques. چون اسم جمع است gustan می‌آید.\n«پیاده‌روی را دوست دارم» → Me gusta caminar. مصدر با gusta می‌آید.\n«اگرچه شلوغ است، شهر را دوست دارم» → Aunque es ruidosa, me gusta la ciudad. aunque تضاد را معرفی می‌کند."}
    };
    private static String richPage(int page){
        for(String[] d:RICH_PAGES){if(Integer.parseInt(d[0])==page){
            return "===درس صفحه "+page+"===\n📖 "+d[1]+"\n\n🧱 شش الگوی جمله‌سازی:\n"+d[2]+
                "\n\n✍️ ده مثال تازه با ترجمه:\n"+d[3]+
                "\n\n🔍 سه مثال حل‌شده با توضیح:\n"+d[4]+
                "\n\n🧩 تمرین: سه جمله با الگوهای بالا درباره خودت بساز؛ دو جمله را منفی کن؛ سپس با صدای بلند بخوان.\n"+
                "🔑 پاسخ نمونه: از مثال‌های حل‌شده برای مقایسه استفاده کن.";
        }}return null;
    }
    // Original unit-aligned supplementary lessons; NOT a reproduction or page-by-page transcription.
    private static final String[][] UNIT_EXAMPLES={
{"Estudio español todos los días.","Aprendo palabras nuevas.","Quiero hablar con mis vecinos.","Necesito practicar la pronunciación.","Me gusta escuchar diálogos.","Me cuesta recordar algunos verbos.","Practico para hablar mejor.","Estudio porque vivo en España.","Desde hace meses leo en español.","En clase trabajo con compañeros."},
{"Ayer visité el centro.","El lunes hablé con mi profesora.","Anoche cené con amigos.","El año pasado viajé a Madrid.","Compré un libro de español.","Empecé el curso en septiembre.","Llegué a casa a las ocho.","Después llamé a mi hermano.","El sábado fui al mercado.","Conocí a una persona interesante."},
{"Mi hermana es simpática.","Mi amigo tiene mucha paciencia.","Soy una persona tranquila.","Mi compañero parece serio.","Mi padre lleva gafas.","Mi vecina es muy amable.","Tengo el pelo corto.","Mi amigo y yo nos parecemos.","Mi prima es más alta que yo.","A veces estoy nervioso."},
{"Mi piso tiene dos habitaciones.","La cocina está al lado del salón.","La mesa está cerca de la ventana.","Mi habitación es luminosa.","El sofá es más cómodo que la silla.","Prefiero una casa tranquila.","El baño está a la derecha.","La cama está enfrente del armario.","Esta vivienda es menos cara.","Me gusta el balcón."},
{"Estoy buscando una dirección.","¿Podrías ayudarme, por favor?","¿Te importa abrir la ventana?","Ahora estamos preparando la cena.","Perdona, ¿puedes repetirlo?","Lo siento, hoy no puedo.","¿Me dejas tu bolígrafo?","Muchas gracias por tu ayuda.","De nada, hasta luego.","¿Sería posible cambiar la hora?"},
{"Esta semana he visitado un museo.","Todavía no he visto esa película.","Ya he comprado las entradas.","Hoy hemos paseado por el parque.","Mañana voy a ir al cine.","Quiero conocer la ciudad.","Pienso visitar una exposición.","Me ha gustado mucho la visita.","Nunca he probado esa actividad.","¿Has estado alguna vez allí?"},
{"Suelo desayunar a las ocho.","Normalmente como en casa.","Me gusta preparar verduras.","No como carne todos los días.","A veces pido comida para llevar.","En España se cena bastante tarde.","Prefiero beber agua.","Los domingos cocinamos juntos.","¿Qué sueles comer al mediodía?","Hoy voy a preparar una ensalada."},
{"Me duele la cabeza.","Me duelen los pies.","Hoy me encuentro mejor.","Tienes que descansar.","Bebe suficiente agua.","Duerme ocho horas si puedes.","Me siento cansado.","¿Qué te pasa?","Deberías hablar con un médico.","Necesito hacer una pausa."},
{"Antes vivía en otra ciudad.","Cuando era niño jugaba en el parque.","Mi casa era más pequeña.","Todos los veranos viajábamos.","Antes trabajaba por la tarde.","De pequeño leía mucho.","Mi barrio tenía pocas tiendas.","Ahora vivo cerca del centro.","Antes no usaba el móvil.","En aquella época tenía más tiempo."},
{"Ayer ocurrió algo especial.","Era tarde y hacía frío.","Cuando caminaba, empezó a llover.","De repente sonó el teléfono.","Primero salimos de casa.","Después cogimos el autobús.","Mientras esperaba, leí un libro.","Al final llegamos a tiempo.","Me puse muy contento.","Nunca olvidaré aquel día."}
    };
    private static final String[][] UNIT_TRANSLATIONS={
{"هر روز اسپانیایی می‌خوانم.","واژه‌های جدید یاد می‌گیرم.","می‌خواهم با همسایه‌هایم صحبت کنم.","باید تلفظ را تمرین کنم.","دوست دارم مکالمه گوش کنم.","به خاطر سپردن بعضی فعل‌ها برایم سخت است.","تمرین می‌کنم تا بهتر صحبت کنم.","چون در اسپانیا زندگی می‌کنم درس می‌خوانم.","چند ماه است به اسپانیایی می‌خوانم.","در کلاس با همکلاسی‌ها کار می‌کنم."},
{"دیروز از مرکز شهر دیدن کردم.","دوشنبه با معلمم صحبت کردم.","دیشب با دوستان شام خوردم.","سال گذشته به مادرید سفر کردم.","یک کتاب اسپانیایی خریدم.","دوره را در سپتامبر شروع کردم.","ساعت هشت به خانه رسیدم.","بعد به برادرم زنگ زدم.","شنبه به بازار رفتم.","با فرد جالبی آشنا شدم."},
{"خواهرم خوش‌برخورد است.","دوستم خیلی صبور است.","آدم آرامی هستم.","همکارم جدی به نظر می‌رسد.","پدرم عینک می‌زند.","همسایه‌ام خیلی مهربان است.","موهای کوتاه دارم.","من و دوستم شبیه هم هستیم.","دخترخاله‌ام از من بلندتر است.","گاهی مضطربم."},
{"خانه‌ام دو اتاق دارد.","آشپزخانه کنار پذیرایی است.","میز نزدیک پنجره است.","اتاقم نورگیر است.","مبل از صندلی راحت‌تر است.","خانه‌ای آرام را ترجیح می‌دهم.","حمام سمت راست است.","تخت روبه‌روی کمد است.","این خانه ارزان‌تر است.","بالکن را دوست دارم."},
{"دنبال یک آدرس می‌گردم.","ممکن است لطفاً کمکم کنی؟","اشکالی دارد پنجره را باز کنی؟","الان داریم شام آماده می‌کنیم.","ببخشید، می‌توانی تکرار کنی؟","متأسفم، امروز نمی‌توانم.","خودکارت را به من قرض می‌دهی؟","از کمکت بسیار ممنونم.","خواهش می‌کنم، خداحافظ.","امکان تغییر ساعت هست؟"},
{"این هفته از یک موزه بازدید کرده‌ام.","هنوز آن فیلم را ندیده‌ام.","بلیط‌ها را خریده‌ام.","امروز در پارک قدم زده‌ایم.","فردا می‌خواهم به سینما بروم.","می‌خواهم شهر را بشناسم.","قصد دارم از یک نمایشگاه بازدید کنم.","از بازدید خیلی خوشم آمده است.","هرگز آن فعالیت را امتحان نکرده‌ام.","تا حالا آنجا بوده‌ای؟"},
{"معمولاً ساعت هشت صبحانه می‌خورم.","معمولاً در خانه ناهار می‌خورم.","دوست دارم سبزیجات آماده کنم.","هر روز گوشت نمی‌خورم.","گاهی غذای بیرون‌بر سفارش می‌دهم.","در اسپانیا نسبتاً دیر شام می‌خورند.","نوشیدن آب را ترجیح می‌دهم.","یکشنبه‌ها با هم آشپزی می‌کنیم.","معمولاً ظهر چه می‌خوری؟","امروز می‌خواهم سالاد درست کنم."},
{"سرم درد می‌کند.","پاهایم درد می‌کنند.","امروز حالم بهتر است.","باید استراحت کنی.","آب کافی بنوش.","اگر می‌توانی هشت ساعت بخواب.","احساس خستگی می‌کنم.","چه شده است؟","بهتر است با پزشک صحبت کنی.","نیاز دارم کمی استراحت کنم."},
{"قبلاً در شهر دیگری زندگی می‌کردم.","وقتی بچه بودم در پارک بازی می‌کردم.","خانه‌ام کوچک‌تر بود.","هر تابستان سفر می‌کردیم.","قبلاً عصرها کار می‌کردم.","در کودکی زیاد کتاب می‌خواندم.","محله‌ام مغازه‌های کمی داشت.","حالا نزدیک مرکز شهر زندگی می‌کنم.","قبلاً از موبایل استفاده نمی‌کردم.","آن زمان وقت بیشتری داشتم."},
{"دیروز اتفاق خاصی افتاد.","دیر وقت بود و هوا سرد بود.","وقتی راه می‌رفتم باران شروع شد.","ناگهان تلفن زنگ زد.","اول از خانه بیرون رفتیم.","بعد سوار اتوبوس شدیم.","در حالی که منتظر بودم کتاب خواندم.","در نهایت به موقع رسیدیم.","خیلی خوشحال شدم.","آن روز را هرگز فراموش نمی‌کنم."}
    };
    private static final String[] PAGE_FOCUS={"هدف و انگیزه","زمان و مدت","گفت‌وگو و پرسش","توصیف و واژگان","کاربرد روزمره","ساخت جمله مثبت","ساخت جمله منفی","پرسش‌سازی","مکالمه کاربردی","تمرین زمان فعل","مقایسه دو جمله","اشتباه رایج","نوشتن و خواندن","مرور و جمع‌بندی"};
    private static String expandedUnitPage(int page){
        int u=unitIndex(page), offset=page-STARTS[u];
        String[] topic=topic(page), samples=UNIT_EXAMPLES[u], fa=UNIT_TRANSLATIONS[u];
        StringBuilder b=new StringBuilder("📖 صفحه "+page+" | "+topic[0]+"\n🎯 تمرکز تمرین تکمیلی: "+PAGE_FOCUS[Math.floorMod(offset,14)]);
        b.append("\n📘 توضیح گرامری: ").append(NOTES[u]);
        b.append("\n🧱 شش الگوی جمله‌سازی:\n");
        String[] models=PATTERNS[u].split("\\|");
        for(int k=0;k<6;k++){String ex=models[k%models.length];b.append(k+1).append(". ").append(ex).append("\n");}
        b.append("\n✍️ ده مثال همراه ترجمه فارسی:\n");
        for(int k=0;k<10;k++){int n=Math.floorMod(k+offset,10);b.append(k+1).append(". ").append(samples[n]).append("\n   🇮🇷 ").append(fa[n]).append("\n");}
        b.append("\n🔍 سه مثال حل‌شده و تحلیل:\n");
        for(int k=0;k<3;k++){int n=Math.floorMod(offset+k,10);b.append(k+1).append(") ").append(samples[n]).append(" = ").append(fa[n]).append("\n   نکته: ").append(NOTES[u]).append("\n");}
        b.append("\n🗣️ گفت‌وگوی تألیفی:\nA: ").append(topic[5]).append("\nB: ").append(topic[3]).append("\n🇮🇷 ").append(topic[4]);
        b.append("\n🧩 تمرین: ۱) دو جمله را منفی کن. ۲) با سه فعل جدید جمله بساز. ۳) یک پرسش و پاسخ بساز. ۴) پنج مثال را با صدای بلند بخوان.");
        b.append("\n🔑 پاسخ نمونه: ").append(samples[Math.floorMod(offset,10)]).append(" | ").append(fa[Math.floorMod(offset,10)]);
        b.append("\nℹ️ این درس تمرین تألیفی بر اساس موضوع واحد است؛ تطبیق دقیق با متن همین صفحه ادعا نمی‌شود.");
        return b.toString();
    }
    private static String pageGuide(int page){
        String rich=richPage(page);if(rich!=null)return rich;
        for(String[] entry:EXPANDED_GUIDES){if(Integer.parseInt(entry[0])==page)return "===درس صفحه "+page+"===\n📖 "+entry[1]+"\n"+entry[2];}
        for(String record:PAGE_GUIDES){String[] x=record.split("§",-1);if(Integer.parseInt(x[0])==page){
            String[] example=x[3].split("\\|",-1);
            return "===درس صفحه "+page+"===\n🎯 "+x[1]+"\n📘 آموزش: "+x[2]+
                "\n✍️ مثال: "+example[0]+"\n🇮🇷 "+(example.length>1?example[1]:"")+
                "\n🧩 تمرین اختصاصی: "+x[4]+"\n🗣️ مکالمه: پاسخ خود را در سه جمله اسپانیایی بگو.";
        }}
        return expandedUnitPage(page);
    }
    String morning(int day){
        int from=firstPage(day);
        if(from>LAST_PAGE)return "🎓 دوره تمام شد. برای مرور /aula_review";
        int to=Math.min(LAST_PAGE,from+4);
        String[] a=topic(from);
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
        if(cmd.equals("/aula_page")){
            String[] bits=text.trim().split("\\s+");
            if(bits.length<2)return "شماره صفحه را وارد کن: /aula_page 15";
            try {int page=Integer.parseInt(bits[1]);return page>=10&&page<=149?pageGuide(page):"صفحه باید بین ۱۰ تا ۱۴۹ باشد.";}catch(Exception ex){return "شماره صفحه معتبر نیست.";}
        }
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
            try {
                int from=firstPage(day());
                if(from<=LAST_PAGE){for(int page=from;page<=Math.min(from+4,LAST_PAGE);page++)sender.send(owner,pageGuide(page));}
                p.edit().putString(AM_SENT,date).apply();
            }catch(Exception ignored){return;}
        }
        if(!now.isBefore(LocalTime.parse("22:00",CLOCK)) && !date.equals(p.getString(PM_SENT,""))){
            try {sender.send(owner,evening(day()));p.edit().putString(PM_SENT,date).apply();}
            catch(Exception ignored){/* retry on next tick */}
        }
    }
}
