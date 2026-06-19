from pathlib import Path

PROJECT = Path.cwd()
CONFIG = PROJECT / 'app' / 'config.py'
BOT = PROJECT / 'app' / 'bot.py'
RWS = PROJECT / 'render_webhook_start.py'
ENV = PROJECT / '.env'

if not CONFIG.exists() or not BOT.exists():
    raise FileNotFoundError('این فایل باید داخل ریشه پروژه اجرا شود: D:\\Python_project\\downloader')

# 1) Patch config.py
config_text = CONFIG.read_text(encoding='utf-8')
if 'telegram_upload_max_mb' not in config_text:
    additions = '    telegram_upload_max_mb: int = int(os.getenv("TELEGRAM_UPLOAD_MAX_MB", os.getenv("MAX_FILE_MB", "45")))'
    anchors = [
        '    direct_link_max_mb: int = int(os.getenv("DIRECT_LINK_MAX_MB", os.getenv("MAX_FILE_MB", "45")))',
        '    temp_link_expire_hours: int = int(os.getenv("TEMP_LINK_EXPIRE_HOURS", "6"))',
        '    bot_language: str = os.getenv("BOT_LANGUAGE", "fa")',
    ]
    for anchor in anchors:
        if anchor in config_text:
            config_text = config_text.replace(anchor, anchor + '\n' + additions)
            break
    else:
        raise RuntimeError('Could not add telegram_upload_max_mb to config.py')
CONFIG.write_text(config_text, encoding='utf-8')

# 2) Patch .env
env_text = ENV.read_text(encoding='utf-8') if ENV.exists() else ''
if 'TELEGRAM_UPLOAD_MAX_MB=' not in env_text:
    if env_text and not env_text.endswith('\n'):
        env_text += '\n'
    env_text += 'TELEGRAM_UPLOAD_MAX_MB=45\n'
ENV.write_text(env_text, encoding='utf-8')

# 3) Create app/upload_handler.py
upload_code = '''
from __future__ import annotations

import shutil
from pathlib import Path

from telegram import Update
from telegram.ext import ContextTypes

from app.config import settings
from app import db
from app.temp_links import create_temp_link
from app.utils import short_hash, human_size

ALLOWED_EXTENSIONS = {'.mp4', '.mp3', '.m4a', '.webm', '.wav', '.mov', '.aac', '.ogg'}


def _safe_name(name: str | None, default: str = 'telegram_media') -> str:
    if not name:
        return default
    cleaned = ''.join(ch if ch.isalnum() or ch in '._-' else '_' for ch in name)
    return cleaned[:100] or default


def _extension_allowed(filename: str) -> bool:
    return Path(filename.lower()).suffix in ALLOWED_EXTENSIONS


async def handle_media_upload(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    user = update.effective_user
    msg = update.effective_message
    if not user or not msg:
        return

    db.ensure_user(
        user_id=user.id,
        username=user.username,
        first_name=user.first_name,
        last_name=user.last_name,
    )

    if db.is_user_blocked(user.id):
        await msg.reply_text('⛔ حساب شما برای استفاده از ربات مسدود شده است.')
        return

    tg_file = None
    filename = None
    file_size = None

    if msg.video:
        tg_file = msg.video
        filename = msg.video.file_name or f'video_{msg.video.file_unique_id}.mp4'
        file_size = msg.video.file_size
    elif msg.audio:
        tg_file = msg.audio
        filename = msg.audio.file_name or f'audio_{msg.audio.file_unique_id}.mp3'
        file_size = msg.audio.file_size
    elif msg.document:
        tg_file = msg.document
        filename = msg.document.file_name or f'document_{msg.document.file_unique_id}'
        file_size = msg.document.file_size
    else:
        return

    filename = _safe_name(filename)

    if not _extension_allowed(filename):
        await msg.reply_text(
            'این فرمت فایل در نسخه فعلی پشتیبانی نمی‌شود.\\n\\n'
            'فرمت‌های مجاز: mp4, mp3, m4a, webm, wav, mov, aac, ogg'
        )
        return

    max_bytes = int(settings.telegram_upload_max_mb) * 1024 * 1024
    if file_size and file_size > max_bytes:
        await msg.reply_text(
            f'حجم فایل بیشتر از حد مجاز است.\\n'
            f'حجم فایل: {human_size(file_size)}\\n'
            f'حد فعلی: {settings.telegram_upload_max_mb} MB'
        )
        return

    status = await msg.reply_text('⏳ فایل دریافت شد. در حال ذخیره‌سازی...')

    job_id = short_hash(f'{user.id}:{filename}:{file_size}')
    job_dir = settings.download_dir / str(user.id) / 'telegram_uploads' / job_id
    if job_dir.exists():
        shutil.rmtree(job_dir)
    job_dir.mkdir(parents=True, exist_ok=True)
    output_path = job_dir / filename

    try:
        telegram_file = await tg_file.get_file()
        await telegram_file.download_to_drive(custom_path=str(output_path))

        if not output_path.exists() or output_path.stat().st_size == 0:
            await status.edit_text('❌ فایل ذخیره نشد یا فایل خالی است.')
            return

        size = output_path.stat().st_size
        download_id = db.add_download(
            user_id=user.id,
            username=user.username,
            url='telegram_upload',
            platform='telegram',
            title=filename,
            mode='upload',
            status='processing',
        )
        db.mark_done(download_id, output_path, size)

        temp_url = create_temp_link(output_path)
        await status.edit_text(
            '✅ فایل ذخیره شد.\\n\\n'
            f'نام فایل: {filename}\\n'
            f'حجم: {human_size(size)}\\n\\n'
            'لینک موقت دانلود:\\n'
            f'{temp_url}\\n\\n'
            f'اعتبار لینک: {settings.temp_link_expire_hours} ساعت'
        )
    except Exception as exc:
        await status.edit_text(f'❌ خطا در ذخیره فایل:\\n{exc}')
'''
(PROJECT / 'app' / 'upload_handler.py').write_text(upload_code.lstrip(), encoding='utf-8')

# 4) Patch app/bot.py
bot_text = BOT.read_text(encoding='utf-8')
if 'from app.upload_handler import handle_media_upload' not in bot_text:
    marker = 'from app.utils import extract_first_url, short_hash, human_duration, human_size'
    if marker in bot_text:
        bot_text = bot_text.replace(marker, marker + '\nfrom app.upload_handler import handle_media_upload')
    else:
        bot_text = bot_text.replace('from app import db', 'from app import db\nfrom app.upload_handler import handle_media_upload')

media_handler_line = '    app.add_handler(MessageHandler((filters.VIDEO | filters.AUDIO | filters.Document.ALL), handle_media_upload))'
if 'handle_media_upload' in bot_text and media_handler_line not in bot_text:
    text_handler = '    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_text))'
    if text_handler in bot_text:
        bot_text = bot_text.replace(text_handler, media_handler_line + '\n' + text_handler)

for old_version in [
    'MediaVault Telegram Bot V1.8 is running...',
    'MediaVault Telegram Bot V1.7 webhook is running.',
    'MediaVault Telegram Bot V1.6 is running...',
    'MediaVault Telegram Bot V1.5 is running...',
    'MediaVault Telegram Bot V1.4 is running...',
    'MediaVault Telegram Bot V1.3 is running...',
    'MediaVault Telegram Bot V1.2 is running...',
    'MediaVault Telegram Bot V1.1 is running...',
    'MediaVault Telegram Bot is running...',
]:
    bot_text = bot_text.replace(old_version, 'MediaVault Telegram Bot V1.9 is running...')
BOT.write_text(bot_text, encoding='utf-8')

# 5) Patch render_webhook_start.py
if RWS.exists():
    rws = RWS.read_text(encoding='utf-8')
    if 'from app.upload_handler import handle_media_upload' not in rws:
        rws = rws.replace(
            'from app.bot import (',
            'from app.upload_handler import handle_media_upload\nfrom app.bot import ('
        )
    media_line = '    app.add_handler(MessageHandler((filters.VIDEO | filters.AUDIO | filters.Document.ALL), handle_media_upload))'
    if media_line not in rws:
        text_line = '    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_text))'
        if text_line in rws:
            rws = rws.replace(text_line, media_line + '\n' + text_line)
    rws = rws.replace('MediaVault Bot V1.8 webhook is running.', 'MediaVault Bot V1.9 webhook is running.')
    rws = rws.replace('MediaVault Bot V1.7 webhook is running.', 'MediaVault Bot V1.9 webhook is running.')
    RWS.write_text(rws, encoding='utf-8')

print('OK: MediaVault Bot upgraded to V1.9 Telegram Uploads.')
print('Added app/upload_handler.py')
print('Added/checked .env: TELEGRAM_UPLOAD_MAX_MB=45')
print('Next: git add . && git commit -m "V1.9 Telegram uploads" && git push')
