package com.javid.downloader;

import android.content.SharedPreferences;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Private-chat CEFR learning; six progressive modules per level, each language separately. */
final class ProgressiveLearning {
    interface Allowed { boolean test(long id); }
    interface Deliver { void send(long id, String language, String text) throws Exception; }
    static final String[] LEVELS={"A1","A2","B1","B2","C1","C2"};
    static final int LESSONS_PER_LEVEL=5;
    static final int COURSE_LENGTH=30;
    private static final String USERS="cefr_users";
    private final SharedPreferences prefs;
    private final String[][] es={
        {"Saludos", "Hola, ¿cómo te llamas?", "سلام، اسمت چیست؟", "¿Cómo te ___?", "llamas", "llama", "llamó", "برای «تو» از llamas استفاده می‌کنیم."},
        {"Presentarse", "Me llamo Sara y vivo en España.", "اسم من سارا است و در اسپانیا زندگی می‌کنم.", "Yo ___ en España.", "vivo", "vive", "vivir", "فاعل yo با vivo می‌آید."},
        {"Números y edad", "Tengo veinticinco años.", "بیست‌وپنج سال دارم.", "Yo ___ veinticinco años.", "tengo", "soy", "estoy", "برای سن از tener استفاده می‌شود."},
        {"La familia", "Mi hermana trabaja en Madrid.", "خواهرم در مادرید کار می‌کند.", "Mi ___ trabaja.", "hermana", "hermano", "padre", "hermana یعنی خواهر."},
        {"La hora", "La clase empieza a las nueve.", "کلاس ساعت نه شروع می‌شود.", "La clase empieza ___ las nueve.", "a", "en", "de", "ساعت با a las بیان می‌شود."},
        {"Rutinas", "Normalmente, me levanto a las ocho.", "معمولاً ساعت هشت از خواب بلند می‌شوم.", "Yo me ___ a las ocho.", "levanto", "levanta", "levanté", "در زمان حال برای yo از levanto استفاده می‌کنیم."},
        {"Pasado reciente", "Ayer fui al supermercado.", "دیروز به سوپرمارکت رفتم.", "Ayer ___ al supermercado.", "fui", "voy", "iré", "fui گذشته ساده فعل ir است."},
        {"Planes", "Mañana voy a estudiar español.", "فردا می‌خواهم اسپانیایی بخوانم.", "Mañana ___ a estudiar.", "voy", "fui", "estaba", "ir a + infinitivo برای برنامه نزدیک است."},
        {"Comparaciones", "Este libro es más fácil que el otro.", "این کتاب از آن یکی آسان‌تر است.", "Es más fácil ___ el otro.", "que", "de", "con", "برای مقایسه از más ... que استفاده می‌شود."},
        {"En el médico", "Me duele la cabeza desde ayer.", "از دیروز سرم درد می‌کند.", "Me ___ la cabeza.", "duele", "duelen", "dolía", "برای اسم مفرد در زمان حال duele به کار می‌رود."},
        {"Opiniones", "Creo que deberíamos salir antes.", "فکر می‌کنم بهتر است زودتر بیرون برویم.", "Creo ___ debemos salir.", "que", "si", "por", "بعد از creo برای بیان نظر از que استفاده می‌شود."},
        {"Experiencias", "He visitado Salamanca varias veces.", "چند بار از سالامانکا بازدید کرده‌ام.", "___ visitado Salamanca.", "He", "Soy", "Estoy", "گذشته نقلی: haber + participio."},
        {"Condicional", "Si tuviera tiempo, estudiaría más.", "اگر وقت داشتم، بیشتر درس می‌خواندم.", "Si tuviera tiempo, ___.", "estudiaría", "estudié", "estudio", "در شرط فرضی از condicional استفاده می‌شود."},
        {"Conectores", "Aunque estaba cansado, terminé el trabajo.", "با اینکه خسته بودم، کار را تمام کردم.", "___ estaba cansado, terminé.", "Aunque", "Porque", "Para", "aunque تضاد را نشان می‌دهد."},
        {"Subjuntivo", "Espero que tengas un buen día.", "امیدوارم روز خوبی داشته باشی.", "Espero que ___ suerte.", "tengas", "tienes", "tenías", "بعد از espero que معمولاً subjuntivo می‌آید."},
        {"Explicar problemas", "El proyecto se retrasó por falta de recursos.", "پروژه به‌دلیل کمبود منابع به تأخیر افتاد.", "Se retrasó ___ falta de recursos.", "por", "para", "aunque", "por علت را بیان می‌کند."},
        {"Hipótesis", "Es posible que lleguen mañana.", "ممکن است فردا برسند.", "Es posible que ___ mañana.", "lleguen", "llegan", "llegaron", "با es posible que فعل در subjuntivo می‌آید."},
        {"Estilo indirecto", "Dijo que no podía asistir a la reunión.", "گفت که نمی‌تواند در جلسه شرکت کند.", "Dijo que no ___ asistir.", "podía", "puede", "podrá", "در نقل قول غیرمستقیم زمان فعل تغییر می‌کند."},
        {"Argumentación", "No obstante, conviene analizar los riesgos.", "بااین‌حال، بهتر است خطرها بررسی شوند.", "___, conviene analizar los riesgos.", "No obstante", "Por ejemplo", "Además", "no obstante برای بیان تقابل است."},
        {"Registro formal", "Le agradecería que confirmara la recepción.", "سپاسگزار می‌شوم دریافت را تأیید کنید.", "Le agradecería que ___.", "confirmara", "confirma", "confirmó", "در درخواست رسمی از ساختار مؤدبانه با subjuntivo استفاده می‌شود."},
        {"Matices", "La medida resulta cuestionable desde varios puntos de vista.", "این اقدام از چند دیدگاه قابل‌بحث است.", "La medida resulta ___.", "cuestionable", "indudable", "obligatoria", "cuestionable یعنی قابل نقد یا بحث."},
        {"Inferencias", "Es probable que hayan cambiado los requisitos.", "احتمال دارد شرایط را تغییر داده باشند.", "Es probable que ___ cambiado.", "hayan", "han", "habían", "بعد از احتمال، subjuntivo compuesto می‌آید."},
        {"Cohesión", "Por consiguiente, debemos reconsiderar la propuesta.", "بنابراین باید پیشنهاد را دوباره بررسی کنیم.", "___, reconsideraremos la propuesta.", "Por consiguiente", "Sin embargo", "A pesar de", "por consiguiente نتیجه‌گیری را نشان می‌دهد."},
        {"Persuasión", "Sería conveniente abordar el asunto con cautela.", "بهتر است موضوع را با احتیاط بررسی کنیم.", "Sería ___ abordarlo.", "conveniente", "imposible", "prohibido", "sería conveniente ساختار پیشنهادی رسمی است."},
        {"Síntesis", "En definitiva, la evidencia sigue siendo insuficiente.", "در نهایت، شواهد هنوز کافی نیستند.", "En definitiva, la evidencia es ___.", "insuficiente", "concluyente", "irrelevante", "insuficiente یعنی ناکافی."},
        {"Discurso académico", "Cabe destacar que los resultados son provisionales.", "باید تأکید کرد که نتایج موقتی هستند.", "___ destacar que son provisionales.", "Cabe", "Puede", "Debe de", "cabe destacar que عبارتی رسمی برای تأکید است."},
        {"Sutilezas", "La conclusión dista mucho de ser inequívoca.", "نتیجه به هیچ وجه بی‌ابهام نیست.", "La conclusión dista ___ ser clara.", "de", "a", "por", "dista de یعنی فاصله زیادی دارد."},
        {"Concesión avanzada", "Por más que insista, no cambiará la decisión.", "هرقدر هم اصرار کند، تصمیم تغییر نمی‌کند.", "___ más que insista...", "Por", "Para", "Con", "por más que ساختار امتیازی پیشرفته است."},
        {"Precisión léxica", "Conviene deslindar los hechos de las interpretaciones.", "باید واقعیت‌ها را از تفسیرها جدا کرد.", "Conviene ___ los hechos.", "deslindar", "confundir", "ocultar", "deslindar به معنی تفکیک دقیق است."},
        {"Metadiscurso", "A efectos de claridad, delimitaré el alcance del análisis.", "برای وضوح، دامنه تحلیل را مشخص می‌کنم.", "A efectos de ___.", "claridad", "casualidad", "descuido", "a efectos de در نوشتار رسمی برای هدف به کار می‌رود."}
    };
    private final String[][] en={
        {"Greetings", "Hello, what is your name?", "سلام، نام شما چیست؟", "What ___ your name?", "is", "are", "am", "what is your name ساختار سؤال معرفی است."},
        {"Introductions", "My name is Sara and I live in Spain.", "اسم من سارا است و در اسپانیا زندگی می‌کنم.", "I ___ in Spain.", "live", "lives", "living", "برای I از live استفاده می‌کنیم."},
        {"Numbers and age", "I am twenty-five years old.", "بیست‌وپنج سال دارم.", "I ___ twenty-five.", "am", "have", "are", "در انگلیسی برای سن از be استفاده می‌شود."},
        {"Family", "My sister works in Madrid.", "خواهرم در مادرید کار می‌کند.", "My sister ___ in Madrid.", "works", "work", "working", "سوم شخص مفرد در حال ساده s می‌گیرد."},
        {"Telling time", "The lesson starts at nine.", "درس ساعت نه شروع می‌شود.", "It starts ___ nine.", "at", "on", "in", "برای ساعت مشخص از at استفاده می‌شود."},
        {"Daily routines", "I usually wake up at eight.", "معمولاً ساعت هشت بیدار می‌شوم.", "I usually ___ up at eight.", "wake", "wakes", "waking", "برای I فعل حال ساده بدون s است."},
        {"Past simple", "Yesterday I went to the store.", "دیروز به فروشگاه رفتم.", "Yesterday I ___ to the store.", "went", "go", "gone", "گذشته go برابر went است."},
        {"Future plans", "I am going to study tomorrow.", "فردا می‌خواهم درس بخوانم.", "I am ___ to study.", "going", "went", "goes", "be going to برای برنامه آینده است."},
        {"Comparatives", "This book is easier than that one.", "این کتاب از آن یکی آسان‌تر است.", "This book is easier ___ that one.", "than", "then", "as", "برای مقایسه از than استفاده می‌کنیم."},
        {"Health", "I have had a headache since yesterday.", "از دیروز سردرد دارم.", "I have had a headache ___ yesterday.", "since", "for", "during", "since نقطه شروع زمان است."},
        {"Opinions", "I think we should leave earlier.", "فکر می‌کنم باید زودتر برویم.", "I think we ___ leave earlier.", "should", "would", "might not", "should برای توصیه است."},
        {"Experiences", "I have visited Salamanca several times.", "چند بار از سالامانکا بازدید کرده‌ام.", "I ___ visited Salamanca.", "have", "am", "was", "حال کامل با have + past participle ساخته می‌شود."},
        {"Conditionals", "If I had time, I would study more.", "اگر وقت داشتم، بیشتر درس می‌خواندم.", "If I had time, I ___ study more.", "would", "will", "did", "شرط فرضی با would بیان می‌شود."},
        {"Linking ideas", "Although I was tired, I finished the task.", "با اینکه خسته بودم، کار را تمام کردم.", "___ I was tired, I finished.", "Although", "Because", "Unless", "although برای تضاد است."},
        {"Suggestions", "I suggest that he arrive earlier.", "پیشنهاد می‌کنم زودتر برسد.", "I suggest that he ___ earlier.", "arrive", "arrives", "arrived", "بعد از suggest that حالت پایه فعل رسمی است."},
        {"Problem solving", "The project was delayed due to limited resources.", "پروژه به علت منابع محدود به تأخیر افتاد.", "The project was delayed ___ limited resources.", "due to", "unless", "instead", "due to علت را بیان می‌کند."},
        {"Possibility", "It is likely that they will arrive tomorrow.", "احتمالاً فردا می‌رسند.", "It is ___ they will arrive.", "likely", "certainly", "impossible that", "likely به معنی محتمل است."},
        {"Reported speech", "She said she could not attend the meeting.", "گفت نمی‌تواند در جلسه شرکت کند.", "She said she ___ not attend.", "could", "can", "will", "در reported speech معمولاً can به could تبدیل می‌شود."},
        {"Argumentation", "Nevertheless, we should assess the risks.", "بااین‌حال باید خطرها را بررسی کنیم.", "___, we should assess the risks.", "Nevertheless", "Therefore", "Similarly", "nevertheless تضاد را نشان می‌دهد."},
        {"Formal requests", "I would appreciate it if you confirmed receipt.", "سپاسگزار می‌شوم دریافت را تأیید کنید.", "I would appreciate it if you ___ receipt.", "confirmed", "confirm", "confirming", "در درخواست مؤدبانه از past form استفاده می‌شود."},
        {"Nuance", "The policy remains questionable in several respects.", "این سیاست از چند جهت قابل بحث است.", "The policy remains ___.", "questionable", "unquestioned", "mandatory", "questionable یعنی قابل نقد."},
        {"Inference", "They may have changed the requirements.", "ممکن است شرایط را تغییر داده باشند.", "They ___ have changed the requirements.", "may", "must to", "will to", "may have + participle برای حدس درباره گذشته است."},
        {"Cohesion", "Consequently, we must reconsider the proposal.", "در نتیجه باید پیشنهاد را بازنگری کنیم.", "___, we must reconsider it.", "Consequently", "However", "Despite", "consequently نتیجه را نشان می‌دهد."},
        {"Persuasion", "It would be advisable to approach this carefully.", "بهتر است با احتیاط به این موضوع بپردازیم.", "It would be ___ to proceed carefully.", "advisable", "randomly", "irrelevant", "advisable در پیشنهاد رسمی است."},
        {"Synthesis", "Ultimately, the evidence remains insufficient.", "در نهایت شواهد ناکافی است.", "The evidence remains ___.", "insufficient", "definitive", "convincing", "insufficient یعنی ناکافی."},
        {"Academic discourse", "It is worth emphasizing that the findings are provisional.", "شایسته تأکید است که یافته‌ها موقتی هستند.", "The findings are ___.", "provisional", "permanent", "conclusive", "provisional یعنی موقت."},
        {"Subtle distinctions", "The conclusion is far from unequivocal.", "این نتیجه به هیچ وجه بی‌ابهام نیست.", "The conclusion is far from ___.", "unequivocal", "unclear", "disputed", "unequivocal یعنی بدون ابهام."},
        {"Concession", "However compelling the case, the decision stands.", "هرقدر هم استدلال قوی باشد، تصمیم پابرجاست.", "___ compelling the case...", "However", "Therefore", "Because", "however + adjective برای امتیاز پیشرفته است."},
        {"Lexical precision", "We must distinguish facts from interpretations.", "باید واقعیت‌ها را از تفسیرها تفکیک کنیم.", "We must ___ facts from interpretations.", "distinguish", "merge", "ignore", "distinguish ... from یعنی تفکیک کردن."},
        {"Metadiscourse", "For the sake of clarity, I shall delimit the scope.", "برای وضوح، محدوده را مشخص می‌کنم.", "For the sake of ___.", "clarity", "confusion", "ambiguity", "for the sake of برای بیان هدف رسمی است."}
    };
    ProgressiveLearning(SharedPreferences prefs){this.prefs=prefs;}
    private static String lang(String word){
        if(word==null)return null;
        word=word.trim().toLowerCase(Locale.ROOT);
        if(Arrays.asList("es","español","spanish","اسپانیایی").contains(word))return "es";
        if(Arrays.asList("en","english","انگلیسی").contains(word))return "en";
        return null;
    }
    private static String str(long user,String language,String type){return "cefr_"+type+"_"+user+"_"+language;}
    private int index(long user,String language){return Math.max(0,Math.min(COURSE_LENGTH,prefs.getInt(str(user,language,"index"),0)));}
    private String[][] deck(String language){return "en".equals(language)?en:es;}
    private static String level(int i){return LEVELS[Math.min(LEVELS.length-1,i/LESSONS_PER_LEVEL)];}
    private static String clock(SharedPreferences p,String k,String d){return p.getString(k,d);}
    private static boolean validClock(String s){return s!=null && s.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]");}
    private static String date(){return LocalDate.now(ZoneId.systemDefault()).toString();}
    private void register(long user){
        synchronized(this){
            Set<String> ids=new HashSet<>(prefs.getStringSet(USERS,new HashSet<String>()));
            ids.add(Long.toString(user));
            prefs.edit().putStringSet(USERS,ids).apply();
        }
    }
    private static String url(String language,String cefr,String topic){
        String query=("es".equals(language)?"VideoELE español España ": "VOA Learning English American English ")+cefr+" "+topic+" video lesson";
        try {return "https://www.youtube.com/results?search_query="+URLEncoder.encode(query,"UTF-8");}
        catch(Exception e){return "https://www.youtube.com";}
    }
    static boolean accepts(String text){
        if(text==null||!text.startsWith("/"))return false;
        String cmd=text.trim().split("\\s+")[0].split("@",2)[0].toLowerCase(Locale.ROOT);
        return Arrays.asList("/course","/course_on","/course_off","/course_status",
          "/course_now","/course_audio","/course_video","/course_level",
          "/course_restart","/course_next","/answer","/solution",
          "/lesson_on","/lesson_off","/lesson_status","/lesson_now").contains(cmd);
    }
    private static String[] arguments(String text){return text.trim().split("\\s+");}
    private String currentLang(long id){
        return prefs.getString("cefr_last_lang_"+id,"es");
    }
    private void setCurrentLang(long id,String language){
        prefs.edit().putString("cefr_last_lang_"+id,language).apply();
    }
    private String help(long id){
        return "🎓 آموزش مرحله‌ای اسپانیایی 🇪🇸 و انگلیسی 🇺🇸\n"+
            "📚 سطح‌بندی: A1 → A2 → B1 → B2 → C1 → C2\n"+
            "هر سطح ۵ درس نمونه دارد. بعد از پاسخ صحیح یا مشاهده جواب، درس بعدی باز می‌شود.\n\n"+
            "📗 شروع: /course_on es 09:00\n"+
            "📘 انگلیسی: /course_on en 10:00\n"+
            "📖 درس فعلی: /course_now es\n"+
            "🎧 فایل صوتی: /course_audio es\n"+
            "🎬 جستجوی ویدئوهای مرتبط: /course_video es\n"+
            "✍️ جواب تمرین: /answer es A\n"+
            "✅ پاسخ تشریحی: /solution es\n"+
            "➡️ درس بعدی: /course_next es\n"+
            "🎯 انتخاب سطح: /course_level es B1\n"+
            "📊 پیشرفت: /course_status\n"+
            "🔕 غیرفعال‌سازی: /course_off es\n"+
            "⚙️ زمان‌ها به وقت گوشی میزبان بات هستند.\n"+
            "دو زبان پیشرفت جداگانه دارند و در صورت درست پاسخ ندادن، درس خودکار تکرار می‌شود.\n\n"+
            status(id);
    }
    private String status(long id){
        StringBuilder s=new StringBuilder("📊 وضعیت دوره‌های آموزشی\n");
        for(String language:new String[]{"es","en"}){
            int i=index(id,language);
            boolean on=prefs.getBoolean(str(id,language,"enabled"),false);
            s.append("• ").append(language.equals("es")?"اسپانیایی":"انگلیسی")
             .append(": ").append(on?"روشن":"خاموش")
             .append(" | ").append(i>=COURSE_LENGTH?"دوره نمونه تکمیل شد":level(i)+" درس "+(i%LESSONS_PER_LEVEL+1))
             .append(" | ").append(clock(prefs,str(id,language,"at"),"09:00"))
             .append(" | ").append(i).append("/").append(COURSE_LENGTH).append("\n");
        }
        return s.toString();
    }
    String lesson(long user,String language){
        int i=index(user,language);
        if(i>=COURSE_LENGTH)return "🏆 همه ۳۰ درس نمونه "+language.toUpperCase(Locale.ROOT)+
            " کامل شد! برای مرور: /course_restart "+language;
        String[] r=deck(language)[i];
        int ans=i%3;
        String[] opt={r[4],r[5],r[6]};
        String[] choices=new String[3];
        choices[ans]=opt[0];choices[(ans+1)%3]=opt[1];choices[(ans+2)%3]=opt[2];
        return "📚 درس "+(i+1)+"/"+COURSE_LENGTH+" | سطح "+level(i)+" | "+
            ("es".equals(language)?"اسپانیایی اسپانیا 🇪🇸":"انگلیسی آمریکایی 🇺🇸")+"\n"+
            "📌 "+r[0]+"\n\n"+
            "🗣 "+r[1]+"\n🇮🇷 "+r[2]+"\n\n"+
            "✍️ تمرین: "+r[3]+"\n"+
            "A) "+choices[0]+"\nB) "+choices[1]+"\nC) "+choices[2]+"\n\n"+
            "✅ جواب: /answer "+language+" A\n"+
            "📝 پاسخ تشریحی: /solution "+language+"\n"+
            "🎧 صوت: /course_audio "+language+"\n"+
            "🎬 ویدئو: /course_video "+language+"\n"+
            "🕒 درس بعد از پاسخ صحیح یا مشاهده راه‌حل باز می‌شود.";
    }
    String audioSentence(long id,String language){
        int i=index(id,language);return i>=COURSE_LENGTH?null:deck(language)[i][1];
    }
    String run(long id,String body){
        String[] p=arguments(body);
        String cmd=p[0].split("@",2)[0].toLowerCase(Locale.ROOT);
        String language=(p.length>1?lang(p[1]):null);
        if(cmd.equals("/course")||cmd.equals("/course_status")||cmd.equals("/lesson_status")){
            return help(id);
        }
        if(cmd.equals("/course_on")||cmd.equals("/lesson_on")){
            if(language==null && p.length>1)return "زبان را es یا en انتخاب کن. مثال: /course_on es 09:00";
            if(language==null)language="es";
            String time=p.length>2?p[2]:"es".equals(language)?"09:00":"10:00";
            if(!validClock(time))return "فرمت ساعت صحیح نیست. نمونه: /course_on es 09:00";
            register(id);
            setCurrentLang(id,language);
            prefs.edit().putString(str(id,language,"sent"),date())
                .putBoolean(str(id,language,"enabled"),true)
                .putString(str(id,language,"at"),time)
                .putBoolean("lang_lesson_on_"+id,false).apply(); // avoid legacy A2 duplicate
            return "✅ دوره "+language.toUpperCase(Locale.ROOT)+" از سطح فعلی فعال شد.\n"+
                "📆 ارسال هر روز ساعت "+time+" (زمان گوشی میزبان)\n\n"+lesson(id,language);
        }
        if(cmd.equals("/course_off")||cmd.equals("/lesson_off")){
            if(language==null)language=currentLang(id);
            prefs.edit().putBoolean(str(id,language,"enabled"),false)
                .putBoolean("lang_lesson_on_"+id,false).apply();
            return "🔕 ارسال روزانه "+language.toUpperCase(Locale.ROOT)+" غیرفعال شد؛ پیشرفت ذخیره ماند.";
        }
        if(language==null){
            if(p.length>1)return "زبان را es یا en بنویس.";
            language=currentLang(id);
        }
        setCurrentLang(id,language);
        if(cmd.equals("/course_now")||cmd.equals("/lesson_now"))return lesson(id,language);
        if(cmd.equals("/course_audio")){
            return "🎧 فایل صوتی تمرین "+language.toUpperCase(Locale.ROOT)+
                " در حال آماده‌سازی است؛ اگر بسته صوتی گوشی نصب نباشد، پیام خطا دریافت می‌کنی.";
        }
        if(cmd.equals("/course_video")){
            int i=index(id,language);
            if(i>=COURSE_LENGTH)return "دوره نمونه تکمیل شده است.";
            String[] r=deck(language)[i];
            return "🎬 ویدئوهای آموزشی مرتبط با «"+r[0]+"» (سطح "+level(i)+")\n"+
                url(language,level(i),r[0])+"\n"+
                "این لینک جست‌وجوی ویدئوهای مرتبط است؛ ویدئوی خاصی به‌عنوان محتوای تأییدشده انتخاب نشده است.";
        }
        if(cmd.equals("/course_level")){
            if(p.length<3)return "نمونه: /course_level es A2";
            int target=Arrays.asList(LEVELS).indexOf(p[2].toUpperCase(Locale.ROOT));
            if(target<0)return "سطح معتبر: A1, A2, B1, B2, C1, C2";
            prefs.edit().putInt(str(id,language,"index"),target*LESSONS_PER_LEVEL)
                .putBoolean(str(id,language,"unlocked"),false).apply();
            register(id);
            return "✅ انتقال به سطح "+LEVELS[target]+" انجام شد.\n\n"+lesson(id,language);
        }
        if(cmd.equals("/course_restart")){
            prefs.edit().putInt(str(id,language,"index"),0)
                .putBoolean(str(id,language,"unlocked"),false).apply();
            register(id);
            return "🔄 دوره از A1 شروع شد.\n\n"+lesson(id,language);
        }
        if(cmd.equals("/solution")){
            int i=index(id,language);
            if(i>=COURSE_LENGTH)return "دوره تکمیل شد.";
            String[] r=deck(language)[i];
            char answer=(char)('A'+i%3);
            prefs.edit().putBoolean(str(id,language,"unlocked"),true).apply();
            return "✅ پاسخ صحیح "+answer+": "+r[4]+"\n🧠 توضیح: "+r[7]+
                "\n➡️ برای ادامه: /course_next "+language;
        }
        if(cmd.equals("/answer")){
            if(p.length<3)return "جواب را با یک حرف وارد کن. مثال: /answer es A";
            int i=index(id,language);
            if(i>=COURSE_LENGTH)return "تبریک! دوره نمونه به پایان رسیده است.";
            String letter=p[2].toUpperCase(Locale.ROOT);
            if(!Arrays.asList("A","B","C").contains(letter))return "پاسخ باید A، B یا C باشد.";
            String[] r=deck(language)[i];
            String answer=String.valueOf((char)('A'+i%3));
            if(!letter.equals(answer)){
                return "❌ این گزینه درست نیست. دوباره امتحان کن.\n"+
                    "💡 راهنمایی: "+r[7]+"\n"+
                    "📝 مشاهده جواب: /solution "+language;
            }
            prefs.edit().putBoolean(str(id,language,"unlocked"),true).apply();
            return "✅ آفرین! جواب صحیح «"+answer+"» است.\n🧠 "+r[7]+
                "\n➡️ درس بعدی: /course_next "+language;
        }
        if(cmd.equals("/course_next")){
            int i=index(id,language);
            if(i>=COURSE_LENGTH)return "دوره نمونه تکمیل شد.";
            if(!prefs.getBoolean(str(id,language,"unlocked"),false))
                return "ابتدا به تمرین پاسخ بده یا /solution "+language+" را ببین.";
            prefs.edit().putInt(str(id,language,"index"),i+1)
                .putString(str(id,language,"sent"),date())
                .putBoolean(str(id,language,"unlocked"),false).apply();
            return lesson(id,language);
        }
        return help(id);
    }
    void tick(Allowed eligible,Deliver delivery){
        LocalDate today=LocalDate.now(ZoneId.systemDefault());
        LocalTime now=LocalTime.now(ZoneId.systemDefault());
        Set<String> users=new HashSet<>(prefs.getStringSet(USERS,new HashSet<String>()));
        for(String item:users){
            long id;
            try{id=Long.parseLong(item);}catch(Exception ignored){continue;}
            if(!eligible.test(id))continue;
            for(String language:new String[]{"es","en"}){
                if(!prefs.getBoolean(str(id,language,"enabled"),false))continue;
                if(index(id,language)>=COURSE_LENGTH)continue;
                String last=prefs.getString(str(id,language,"sent"),"");
                if(today.toString().equals(last))continue;
                String at=prefs.getString(str(id,language,"at"),"09:00");
                if(!validClock(at)||now.isBefore(LocalTime.parse(at)))continue;
                try{
                    delivery.send(id,language,lesson(id,language));
                    prefs.edit().putString(str(id,language,"sent"),today.toString()).apply();
                }catch(Exception ignored){/* retry later; don't advance automatically */}
            }
        }
    }
}
