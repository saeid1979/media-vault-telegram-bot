#!/usr/bin/env python3
"""Bump v3.7.0 and verify the Persian Spain/Iran news scheduler integration."""
from pathlib import Path
gradle=Path("app/build.gradle")
g=gradle.read_text(encoding="utf-8")
assert "versionCode 50" in g and "versionName '3.7.0'" in g
g=g.replace("versionCode 50","versionCode 51").replace("versionName '3.7.0'","versionName '3.7.1'")
gradle.write_text(g,encoding="utf-8")
src=Path("app/src/main/java/com/javid/downloader/DailyLanguageAutomations.java")
s=src.read_text(encoding="utf-8")
for marker in ("/news_on","/news_off","/news_now","/news_status","feeds.bbci.co.uk/persian/rss.xml","rtve.es/api/noticias.rss"):
    assert marker in s, "missing news feature: "+marker
print("v3.7.1 Persian Spain/Iran configurable news scheduler verified")
