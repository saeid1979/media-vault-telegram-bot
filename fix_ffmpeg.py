from pathlib import Path

config_path = Path(r"D:\Python_project\downloader\app\config.py")
downloader_path = Path(r"D:\Python_project\downloader\app\downloader.py")

# Fix config.py
config_text = config_path.read_text(encoding="utf-8")

if "ffmpeg_location" not in config_text:
    config_text = config_text.replace(
        '    bot_language: str = os.getenv("BOT_LANGUAGE", "fa")',
        '    bot_language: str = os.getenv("BOT_LANGUAGE", "fa")\n'
        '    ffmpeg_location: str = os.getenv("FFMPEG_LOCATION", "")'
    )

config_path.write_text(config_text, encoding="utf-8")


# Fix downloader.py
downloader_text = downloader_path.read_text(encoding="utf-8")

old_function_start = downloader_text.find("def _base_ydl_options()")
next_function_start = downloader_text.find("\ndef _extract_info_sync", old_function_start)

if old_function_start == -1 or next_function_start == -1:
    raise RuntimeError("Could not find _base_ydl_options function in downloader.py")

new_function = '''def _base_ydl_options() -> dict[str, Any]:
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "ignoreerrors": False,
        "geo_bypass": False,
        "nocheckcertificate": False,
        "restrictfilenames": True,
        "windowsfilenames": True,
        "overwrites": True,
        "max_filesize": settings.max_file_bytes,
    }

    if settings.ffmpeg_location:
        opts["ffmpeg_location"] = settings.ffmpeg_location

    return opts
'''

downloader_text = (
    downloader_text[:old_function_start]
    + new_function
    + downloader_text[next_function_start:]
)

downloader_path.write_text(downloader_text, encoding="utf-8")

print("OK: config.py and downloader.py updated successfully.")
