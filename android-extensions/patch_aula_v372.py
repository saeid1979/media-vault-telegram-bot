#!/usr/bin/env python3
from pathlib import Path
root=Path("app/src/main/java/com/javid/downloader")
bot=root/"TelegramBotService.java"
s=bot.read_text(encoding="utf-8")
def change(old,new):
    global s
    assert s.count(old)==1, f"Missing or ambiguous anchor: {old[:100]} (found {s.count(old)})"
    s=s.replace(old,new,1)
# Owner authorization must use the existing bot administrator check. Fail closed if unknown.
import re
admin_candidates=["isAdmin(user)","isOwner(user)","isAdminUser(user)","isBotAdmin(user)"]
admin=next((v for v in admin_candidates if v.split("(")[0]+"(" in s),None)
if admin is None:
    raise RuntimeError("No verified administrator predicate found; refusing insecure private-course build. Admin-related lines: "+repr([line.strip()[:140] for line in s.splitlines() if 'admin' in line.lower()][:25]))
change('    private DailyLanguageAutomations languageAutomations;',
       '    private DailyLanguageAutomations languageAutomations;\n    private AulaPrivateLessons aulaPrivateLessons;')
change('        languageAutomations = new DailyLanguageAutomations(preferences());',
       '        languageAutomations = new DailyLanguageAutomations(preferences());\n        aulaPrivateLessons = new AulaPrivateLessons(preferences());')
change('        // Progressive CEFR courses: route before legacy A2-only lessons.',
       '        // Owner-only Aula lessons: never publish to groups or other users.\n'
       '        if (AulaPrivateLessons.accepts(text)) {\n'
       '            if (chat != user || !'+admin+') {\n'
       '                sendText(chat,"⛔ این بخش فقط در چت خصوصی مدیر اصلی فعال است.",tok);return;\n'
       '            }\n'
       '            sendText(chat,aulaPrivateLessons.handle(user,text),tok);return;\n'
       '        }\n'
       '        // Progressive CEFR courses: route before legacy A2-only lessons.')
change('        // Independent private-chat language scheduler; per-user opt-in and dedupe.',
       '        // Private Aula delivery; owner ID stored only after administrator verification.\n'
       '        languageExecutor.scheduleWithFixedDelay(() -> {\n'
       '            if(!running)return;\n'
       '            aulaPrivateLessons.tick((who,body)-> { if(running)sendText(who,body,token()); });\n'
       '        }, 90, 5, TimeUnit.MINUTES);\n'
       '        // Independent private-chat language scheduler; per-user opt-in and dedupe.')
bot.write_text(s,encoding="utf-8")
gradle=Path("app/build.gradle")
g=gradle.read_text(encoding="utf-8")
assert "versionCode 51" in g and "versionName '3.7.1'" in g
g=g.replace("versionCode 51","versionCode 52").replace("versionName '3.7.1'","versionName '3.7.2'")
gradle.write_text(g,encoding="utf-8")
assert (root/"AulaPrivateLessons.java").exists()
print("Aula 3.7.2 private administrator course integrated via",admin)
