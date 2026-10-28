#!/usr/bin/env python3
"""Apply additive v3.6.9 language features after v3.6.8 source patches."""
from pathlib import Path

root = Path("app/src/main/java/com/javid/downloader")
path = root / "TelegramBotService.java"
s = path.read_text(encoding="utf-8")

def insert_once(old, new):
    global s
    if old not in s:
        raise RuntimeError("Missing source anchor: " + old[:100])
    if s.count(old) != 1:
        raise RuntimeError("Ambiguous source anchor: " + old[:100])
    s = s.replace(old, new, 1)

insert_once(
    'private static final String BUILD_LABEL = "Javid Downloader Android 3.6.8 · automation center + earthquake notices";',
    'private static final String BUILD_LABEL = "Javid Downloader Android 3.6.9 · language learning and translation";'
)
insert_once(
    '    private final ScheduledExecutorService quakeExecutor =\n        Executors.newSingleThreadScheduledExecutor();',
    '    private final ScheduledExecutorService quakeExecutor =\n        Executors.newSingleThreadScheduledExecutor();\n'
    '    private final ScheduledExecutorService languageExecutor =\n'
    '        Executors.newSingleThreadScheduledExecutor();'
)
insert_once(
    '    private QuakeAlerts quakeAlerts;',
    '    private QuakeAlerts quakeAlerts;\n'
    '    private DailyLanguageAutomations languageAutomations;'
)
insert_once(
    '        quakeAlerts = new QuakeAlerts(preferences(),weatherStore);',
    '        quakeAlerts = new QuakeAlerts(preferences(),weatherStore);\n'
    '        languageAutomations = new DailyLanguageAutomations(preferences());'
)
insert_once(
    '        // Weather commands must be routed BEFORE the downloader\'s HTTPS-only fallback.',
    '        // Opt-in language commands must bypass the HTTPS-only downloader.\n'
    '        if (DailyLanguageAutomations.supports(text)) {\n'
    '            if (!canUseMenuFeatures(user)) { sendText(chat,accessMessage(user),tok); return; }\n'
    '            try { sendText(chat,languageAutomations.handle(user,text),tok); }\n'
    '            catch(Exception problem) {\n'
    '                sendText(chat,"⚠️ دریافت یا ترجمه خبر اکنون ممکن نیست. بعداً دوباره امتحان کن.",tok);\n'
    '            }\n'
    '            return;\n'
    '        }\n'
    '        // Weather commands must be routed BEFORE the downloader\'s HTTPS-only fallback.'
)
insert_once(
    '        // Legacy rain notices linked to football digest remain unchanged.',
    '        // Independent private-chat language scheduler; per-user opt-in and dedupe.\n'
    '        languageExecutor.scheduleWithFixedDelay(() -> {\n'
    '            if(!running)return;\n'
    '            try { languageAutomations.tick(this::canUseMenuFeatures,\n'
    '                (who,body) -> { if(running)sendText(who,body,token()); }); }\n'
    '            catch(Exception ignored){ /* Failure must not interrupt football or downloads. */ }\n'
    '        }, 85, 30 * 60, TimeUnit.SECONDS);\n'
    '        // Legacy rain notices linked to football digest remain unchanged.'
)
# Both stop paths must release the additional scheduler.
if s.count('        quakeExecutor.shutdownNow();') != 2:
    raise RuntimeError("Expected exactly two executor shutdown sites")
s = s.replace(
    '        quakeExecutor.shutdownNow();',
    '        quakeExecutor.shutdownNow();\n        languageExecutor.shutdownNow();'
)
insert_once(
    '                "\\n/auto مرکز اتوماسیون | /quake_on Salamanca زلزله"+',
    '                "\\n/auto مرکز اتوماسیون | /quake_on Salamanca زلزله"+\n'
    '                "\\n/language آموزش و ترجمه خودکار | /translate_sources منابع"+\n'
    '                "\\n/lesson_on es 09:00 درس | /words_on es 18:00 واژگان"+'
)
insert_once(
    '                "بررسی دستی: /quake_now Salamanca\\n\\n"+',
    '                "بررسی دستی: /quake_now Salamanca\\n\\n"+\n'
    '                "🌐 ترجمه و یادگیری زبان: /language\\n"+\n'
    '                "📚 درس روزانه: /lesson_on es 09:00\\n"+\n'
    '                "🗣 واژه روزانه: /words_on es 18:00\\n\\n"+'
)
path.write_text(s, encoding="utf-8")
gradle = Path("app/build.gradle")
g = gradle.read_text(encoding="utf-8")
assert "versionCode 48" in g and "versionName '3.6.8'" in g
g = g.replace("versionCode 48", "versionCode 49").replace(
    "versionName '3.6.8'", "versionName '3.6.9'")
gradle.write_text(g, encoding="utf-8")

src = root / "DailyLanguageAutomations.java"
assert src.is_file() and src.stat().st_size > 10000
print("Language automation v3.6.9 overlays applied")
