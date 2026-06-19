from pathlib import Path

PROJECT = Path.cwd()
CONFIG = PROJECT / 'app' / 'config.py'
DOWNLOADER = PROJECT / 'app' / 'downloader.py'
POLICY = PROJECT / 'app' / 'policy.py'
BOT = PROJECT / 'app' / 'bot.py'
ENV = PROJECT / '.env'

if not CONFIG.exists() or not DOWNLOADER.exists() or not POLICY.exists() or not BOT.exists():
    raise FileNotFoundError('این فایل باید داخل ریشه پروژه اجرا شود: D:\\Python_project\\downloader')

# 1) Patch config.py
config_text = CONFIG.read_text(encoding='utf-8')
if 'direct_link_timeout' not in config_text:
    additions = (
        '    direct_link_timeout: int = int(os.getenv("DIRECT_LINK_TIMEOUT", "120"))\n'
        '    direct_link_max_mb: int = int(os.getenv("DIRECT_LINK_MAX_MB", os.getenv("MAX_FILE_MB", "45")))'
    )
    anchors = [
        '    temp_link_expire_hours: int = int(os.getenv("TEMP_LINK_EXPIRE_HOURS", "6"))',
        '    max_concurrent_downloads: int = int(os.getenv("MAX_CONCURRENT_DOWNLOADS", "2"))',
        '    bot_language: str = os.getenv("BOT_LANGUAGE", "fa")',
    ]
    for anchor in anchors:
        if anchor in config_text:
            config_text = config_text.replace(anchor, anchor + '\n' + additions)
            break
    else:
        raise RuntimeError('Could not find a place to add direct link settings in config.py')
CONFIG.write_text(config_text, encoding='utf-8')

# 2) Patch .env
env_text = ENV.read_text(encoding='utf-8') if ENV.exists() else ''
def ensure_line(text: str, key: str, value: str) -> str:
    if any(line.startswith(key + '=') for line in text.splitlines()):
        return text
    if text and not text.endswith('\n'):
        text += '\n'
    return text + f'{key}={value}\n'
env_text = ensure_line(env_text, 'DIRECT_LINK_TIMEOUT', '120')
env_text = ensure_line(env_text, 'DIRECT_LINK_MAX_MB', '45')
ENV.write_text(env_text, encoding='utf-8')

# 3) Create app/direct_downloader.py
direct_code = '''
from __future__ import annotations

import asyncio
import re
import shutil
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Callable, Any

from app.config import settings
from app.utils import short_hash

ProgressCallback = Callable[[dict[str, Any]], None]

DIRECT_MEDIA_EXTENSIONS = {'.mp4', '.mp3', '.m4a', '.webm', '.wav', '.mov', '.aac', '.ogg'}

def is_direct_media_url(url: str) -> bool:
    parsed = urllib.parse.urlparse(url)
    path = parsed.path.lower()
    return any(path.endswith(ext) for ext in DIRECT_MEDIA_EXTENSIONS)

def _safe_filename_from_url(url: str, default: str = 'media_file') -> str:
    parsed = urllib.parse.urlparse(url)
    name = Path(urllib.parse.unquote(parsed.path)).name or default
    name = re.sub(r'[^A-Za-z0-9._-]+', '_', name).strip('._')
    return name or default

def _download_direct_sync(url: str, user_id: int, progress_callback: ProgressCallback | None = None) -> Path:
    job_id = short_hash(f'{user_id}:{url}:direct')
    job_dir = settings.download_dir / str(user_id) / job_id
    if job_dir.exists():
        shutil.rmtree(job_dir)
    job_dir.mkdir(parents=True, exist_ok=True)
    output_path = job_dir / _safe_filename_from_url(url)
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 MediaVaultBot/1.8', 'Accept': '*/*'})
    timeout = int(getattr(settings, 'direct_link_timeout', 120))
    max_bytes = int(getattr(settings, 'direct_link_max_mb', settings.max_file_mb)) * 1024 * 1024
    downloaded = 0
    with urllib.request.urlopen(req, timeout=timeout) as response:
        content_length = response.headers.get('Content-Length')
        total = int(content_length) if content_length and content_length.isdigit() else 0
        if total and total > max_bytes:
            raise RuntimeError(f'حجم لینک مستقیم بیشتر از حد مجاز است. حد فعلی: {round(max_bytes / 1024 / 1024)} MB')
        with output_path.open('wb') as f:
            while True:
                chunk = response.read(1024 * 256)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                if downloaded > max_bytes:
                    raise RuntimeError(f'حجم فایل از حد مجاز بیشتر شد. حد فعلی: {round(max_bytes / 1024 / 1024)} MB')
                if progress_callback:
                    percent = round(downloaded * 100 / total, 1) if total else None
                    progress_callback({'status': 'downloading', 'downloaded': downloaded, 'total': total, 'percent': percent, 'speed': None, 'eta': None})
    if not output_path.exists() or output_path.stat().st_size == 0:
        raise RuntimeError('فایل لینک مستقیم دانلود نشد یا خالی است.')
    return output_path

async def download_direct_media(url: str, user_id: int, progress_callback: ProgressCallback | None = None) -> Path:
    return await asyncio.to_thread(_download_direct_sync, url, user_id, progress_callback)
'''
(PROJECT / 'app' / 'direct_downloader.py').write_text(direct_code.lstrip(), encoding='utf-8')

# 4) Patch downloader.py better errors
downloader_text = DOWNLOADER.read_text(encoding='utf-8')
if 'def make_user_friendly_download_error' not in downloader_text:
    helper = '''

def make_user_friendly_download_error(error_text: str) -> str:
    lower = error_text.lower()
    if 'sign in to confirm' in lower or 'not a bot' in lower:
        return (
            'YouTube این درخواست را به‌عنوان ربات تشخیص داده و اجازه دانلود نمی‌دهد.\\n\\n'
            'این مشکل معمولاً روی سرورهای ابری مثل Render اتفاق می‌افتد.\\n'
            'این ربات برای امنیت و رعایت قوانین، از ورود با حساب کاربری یا cookie استفاده نمی‌کند.\\n\\n'
            'راه‌حل: یک لینک عمومی دیگر یا لینک مستقیم فایل ویدئو/صدا مثل mp4 یا mp3 ارسال کن.'
        )
    if 'unsupported url' in lower:
        return (
            'این لینک در نسخه فعلی پشتیبانی نمی‌شود.\\n\\n'
            'لینک‌های پست یا صفحات نیازمند ورود معمولاً قابل دانلود نیستند.\\n'
            'لطفاً لینک مستقیم فایل عمومی مثل mp4/mp3/m4a/webm ارسال کن.'
        )
    if 'linkedin' in lower:
        return (
            'LinkedIn در این نسخه فقط برای لینک مستقیم فایل ویدئو/صدا پشتیبانی می‌شود.\\n\\n'
            'بیشتر ویدئوهای LinkedIn پشت login/session هستند و بدون ورود قابل دریافت نیستند.'
        )
    if 'private video' in lower or 'login' in lower or 'requires authentication' in lower:
        return (
            'این محتوا عمومی نیست یا نیاز به ورود دارد.\\n\\n'
            'ربات فقط محتوای عمومی یا لینک مستقیم فایل‌هایی را می‌پذیرد که اجازه دانلودشان را داری.'
        )
    return error_text
'''
    insert_after = 'class DownloadError(Exception):\n    pass\n'
    if insert_after in downloader_text:
        downloader_text = downloader_text.replace(insert_after, insert_after + helper)
    else:
        raise RuntimeError('Could not insert error helper in downloader.py')
old = 'except yt_dlp.utils.DownloadError as exc:\n        raise DownloadError(str(exc)) from exc'
new = 'except yt_dlp.utils.DownloadError as exc:\n        raise DownloadError(make_user_friendly_download_error(str(exc))) from exc'
if old in downloader_text:
    downloader_text = downloader_text.replace(old, new)
DOWNLOADER.write_text(downloader_text, encoding='utf-8')

# 5) Patch bot.py
bot_text = BOT.read_text(encoding='utf-8')
if 'from app.direct_downloader import' not in bot_text:
    bot_text = bot_text.replace(
        'from app.downloader import extract_info, download_media, DownloadError',
        'from app.downloader import extract_info, download_media, DownloadError\nfrom app.direct_downloader import is_direct_media_url, download_direct_media'
    )
old_rights = '''        await query.edit_message_text("⏳ در حال خواندن اطلاعات لینک...")
        try:
            info = await extract_info(item["url"])
            item["title"] = info["title"]
            item["info"] = info

            text = (
                "🎬 محتوا پیدا شد\\n\\n"
                f"عنوان: {info['title'][:120]}\\n"
                f"مدت: {human_duration(info.get('duration'))}\\n"
                f"سازنده/کانال: {info.get('uploader') or 'نامشخص'}\\n"
                f"حجم تقریبی: {human_size(info.get('filesize_approx'))}\\n\\n"
                "کیفیت خروجی را انتخاب کن:"
            )
            await query.edit_message_text(text, reply_markup=format_keyboard(url_id), disable_web_page_preview=True)
        except Exception as exc:
            await query.edit_message_text(f"خطا در خواندن اطلاعات لینک:\\n{exc}")
        return
'''
new_rights = '''        await query.edit_message_text("⏳ در حال خواندن اطلاعات لینک...")
        try:
            if is_direct_media_url(item["url"]):
                item["title"] = "Direct media file"
                item["info"] = {"title": "Direct media file"}
                text = (
                    "✅ لینک مستقیم فایل شناسایی شد.\\n\\n"
                    "این نوع لینک بدون نیاز به استخراج از پلتفرم دانلود می‌شود.\\n\\n"
                    "کیفیت خروجی را انتخاب کن:"
                )
                await query.edit_message_text(text, reply_markup=format_keyboard(url_id), disable_web_page_preview=True)
                return
            if "linkedin.com" in item["url"].lower():
                await query.edit_message_text(
                    "LinkedIn در این نسخه فقط برای لینک مستقیم فایل ویدئو/صدا پشتیبانی می‌شود.\\n\\n"
                    "لینک‌های پست LinkedIn معمولاً نیاز به ورود دارند و پشتیبانی نمی‌شوند.\\n"
                    "لطفاً لینک مستقیم فایل عمومی مثل mp4/mp3 ارسال کن."
                )
                return
            info = await extract_info(item["url"])
            item["title"] = info["title"]
            item["info"] = info
            text = (
                "🎬 محتوا پیدا شد\\n\\n"
                f"عنوان: {info['title'][:120]}\\n"
                f"مدت: {human_duration(info.get('duration'))}\\n"
                f"سازنده/کانال: {info.get('uploader') or 'نامشخص'}\\n"
                f"حجم تقریبی: {human_size(info.get('filesize_approx'))}\\n\\n"
                "کیفیت خروجی را انتخاب کن:"
            )
            await query.edit_message_text(text, reply_markup=format_keyboard(url_id), disable_web_page_preview=True)
        except Exception as exc:
            await query.edit_message_text(f"خطا در خواندن اطلاعات لینک:\\n{exc}")
        return
'''
if old_rights in bot_text and 'لینک مستقیم فایل شناسایی شد' not in bot_text:
    bot_text = bot_text.replace(old_rights, new_rights)
else:
    print('WARNING: Could not patch rights/extract_info block automatically or already patched.')

old_download_queue = '''            file_path = await run_download_in_queue(
                status_msg,
                user.id,
                item["url"],
                mode,
                progress_callback,
            )
'''
new_download_queue = '''            if is_direct_media_url(item["url"]):
                file_path = await download_direct_media(
                    item["url"],
                    user.id,
                    progress_callback=progress_callback,
                )
            else:
                file_path = await run_download_in_queue(
                    status_msg,
                    user.id,
                    item["url"],
                    mode,
                    progress_callback,
                )
'''
old_download_direct = '''            file_path = await download_media(
                item["url"],
                mode,
                user.id,
                progress_callback=progress_callback,
            )
'''
new_download_direct = '''            if is_direct_media_url(item["url"]):
                file_path = await download_direct_media(
                    item["url"],
                    user.id,
                    progress_callback=progress_callback,
                )
            else:
                file_path = await download_media(
                    item["url"],
                    mode,
                    user.id,
                    progress_callback=progress_callback,
                )
'''
if old_download_queue in bot_text and 'download_direct_media(' not in bot_text.split('try:', 1)[-1]:
    bot_text = bot_text.replace(old_download_queue, new_download_queue)
elif old_download_direct in bot_text and 'download_direct_media(' not in bot_text.split('try:', 1)[-1]:
    bot_text = bot_text.replace(old_download_direct, new_download_direct)
else:
    print('WARNING: Could not patch download block automatically or already patched.')
for old_version in [
    'MediaVault Telegram Bot V1.7 webhook is running.',
    'MediaVault Telegram Bot V1.6 is running...',
    'MediaVault Telegram Bot V1.5 is running...',
    'MediaVault Telegram Bot V1.4 is running...',
    'MediaVault Telegram Bot V1.3 is running...',
    'MediaVault Telegram Bot V1.2 is running...',
    'MediaVault Telegram Bot V1.1 is running...',
    'MediaVault Telegram Bot is running...',
]:
    bot_text = bot_text.replace(old_version, 'MediaVault Telegram Bot V1.8 is running...')
BOT.write_text(bot_text, encoding='utf-8')

# 6) Patch render_webhook_start.py print
RWS = PROJECT / 'render_webhook_start.py'
if RWS.exists():
    rws_text = RWS.read_text(encoding='utf-8')
    rws_text = rws_text.replace('MediaVault Bot V1.7 webhook is running.', 'MediaVault Bot V1.8 webhook is running.')
    RWS.write_text(rws_text, encoding='utf-8')

print('OK: MediaVault Bot upgraded to V1.8 Direct Links + Better Errors.')
print('Added app/direct_downloader.py')
print('Added/checked .env: DIRECT_LINK_TIMEOUT, DIRECT_LINK_MAX_MB')
print('Next: git add . && git commit -m "V1.8 direct links and better errors" && git push')
