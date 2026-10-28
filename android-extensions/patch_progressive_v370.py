#!/usr/bin/env python3
"""Apply CEFR lessons, audio and topic-video discovery after v3.6.9 integration."""
from pathlib import Path

root=Path("app/src/main/java/com/javid/downloader")
bot=root/"TelegramBotService.java"
s=bot.read_text(encoding="utf-8")

def replace_once(old,new):
    global s
    count=s.count(old)
    if count != 1:
        raise ValueError(f"Expected unique source anchor ({count} matches): {old[:100]}")
    s=s.replace(old,new,1)

replace_once(
 'private static final String BUILD_LABEL = "Javid Downloader Android 3.6.9 · language learning and translation";',
 'private static final String BUILD_LABEL = "Javid Downloader Android 3.7.0 · CEFR learning + voice + exercises + videos";')
replace_once(
 '    private final ScheduledExecutorService languageExecutor =\n        Executors.newSingleThreadScheduledExecutor();',
 '    private final ScheduledExecutorService languageExecutor =\n        Executors.newSingleThreadScheduledExecutor();\n'
 '    private final ScheduledExecutorService courseExecutor =\n        Executors.newSingleThreadScheduledExecutor();'
)
replace_once(
 '    private DailyLanguageAutomations languageAutomations;',
 '    private DailyLanguageAutomations languageAutomations;\n'
 '    private ProgressiveLearning progressiveLearning;'
)
replace_once(
 '        languageAutomations = new DailyLanguageAutomations(preferences());',
 '        languageAutomations = new DailyLanguageAutomations(preferences());\n'
 '        progressiveLearning = new ProgressiveLearning(preferences());'
)
replace_once(
 '        // Opt-in language commands must bypass the HTTPS-only downloader.',
 '        // Progressive CEFR courses: route before legacy A2-only lessons.\n'
 '        if (ProgressiveLearning.accepts(text)) {\n'
 '            if (!canUseMenuFeatures(user)) {sendText(chat,accessMessage(user),tok);return;}\n'
 '            String command=text.split("\\\\s+")[0].split("@",2)[0].toLowerCase(java.util.Locale.ROOT);\n'
 '            String[] pieces=text.split("\\\\s+");\n'
 '            String spokenLanguage=pieces.length>1 &&\n'
 '                 (pieces[1].equalsIgnoreCase("es")||pieces[1].equalsIgnoreCase("en"))?\n'
 '                 pieces[1].toLowerCase(java.util.Locale.ROOT):\n'
 '                 preferences().getString("cefr_last_lang_"+user,"es");\n'
 '            try {\n'
 '                String response=progressiveLearning.run(user,text);\n'
 '                sendText(chat,response,tok);\n'
 '                boolean withAudio=java.util.Arrays.asList("/course_audio","/course_on",\n'
 '                    "/lesson_on","/course_now","/lesson_now","/course_next",\n'
 '                    "/course_level").contains(command);\n'
 '                if(withAudio && !response.startsWith("فرمت") && !response.startsWith("زبان را")) {\n'
 '                    String sentence=progressiveLearning.audioSentence(user,spokenLanguage);\n'
 '                    if(sentence!=null){\n'
 '                        try{CourseAudioDelivery.send(this,chat,tok,spokenLanguage,sentence);}\n'
 '                        catch(Exception audioProblem){\n'
 '                            sendText(chat,"⚠️ فایل صوتی تولید یا ارسال نشد. "+\n'
 '                                "صدای زبان را در تنظیمات Text-to-Speech گوشی نصب کن و دوباره /course_audio "+\n'
 '                                spokenLanguage+" را بفرست.",tok);\n'
 '                        }\n'
 '                    }\n'
 '                }\n'
 '            }catch(Exception problem){\n'
 '                sendText(chat,"⚠️ اجرای دوره آموزشی ناموفق بود. لطفاً دوباره امتحان کن.",tok);\n'
 '            }\n'
 '            return;\n'
 '        }\n'
 '        // Opt-in language commands must bypass the HTTPS-only downloader.'
)
replace_once(
 '        // Independent private-chat language scheduler; per-user opt-in and dedupe.',
 '        // Scheduled progression: students advance only after answering correctly or\n'
 '        // viewing the solution, never through blind daily lesson rotation.\n'
 '        courseExecutor.scheduleWithFixedDelay(() -> {\n'
 '            if(!running)return;\n'
 '            try {progressiveLearning.tick(this::canUseMenuFeatures,\n'
 '                (who,lessonLanguage,body)->{\n'
 '                    if(!running)return;\n'
 '                    sendText(who,body,token());\n'
 '                    String sample=progressiveLearning.audioSentence(who,lessonLanguage);\n'
 '                    if(sample!=null){\n'
 '                        try{CourseAudioDelivery.send(this,who,token(),lessonLanguage,sample);}\n'
 '                        catch(Exception ignored){ /* text delivery is independent of TTS */ }\n'
 '                    }\n'
 '                });}\n'
 '            catch(Exception ignored){ /* Keep Telegram polling and existing services alive */ }\n'
 '        }, 65, 20*60, TimeUnit.SECONDS);\n'
 '        // Independent private-chat language scheduler; per-user opt-in and dedupe.'
)
if s.count("        languageExecutor.shutdownNow();")!=2:
    raise ValueError("Missing both language shutdown hooks")
s=s.replace(
 '        languageExecutor.shutdownNow();',
 '        languageExecutor.shutdownNow();\n        courseExecutor.shutdownNow();')
replace_once(
 '                "\\n/lesson_on es 09:00 درس | /words_on es 18:00 واژگان"+',
 '                "\\n/lesson_on es 09:00 درس | /words_on es 18:00 واژگان"+\n'
 '                "\\n/course دوره A1 تا C2 | /course_audio صوت درس"+'
)
bot.write_text(s,encoding="utf-8")

gradle=Path("app/build.gradle")
g=gradle.read_text(encoding="utf-8")
assert "versionCode 49" in g and "versionName '3.6.9'" in g
g=g.replace("versionCode 49","versionCode 50").replace(
    "versionName '3.6.9'","versionName '3.7.0'")
gradle.write_text(g,encoding="utf-8")

manifest=Path("app/src/main/AndroidManifest.xml")
m=manifest.read_text(encoding="utf-8")
if '<queries>' not in m:
    m=m.replace('    <application',
       '    <queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>\n'
       '    <application',1)
manifest.write_text(m,encoding="utf-8")

for name in ("ProgressiveLearning.java","CourseAudioDelivery.java"):
    assert (root/name).is_file()
print("3.7.0 progressive course + localized TTS + quiz + topical video sources integrated")
