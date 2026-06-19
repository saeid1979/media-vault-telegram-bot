from __future__ import annotations

import asyncio
import time

from telegram import Update
from telegram.constants import ChatAction
from telegram.ext import (
    Application,
    CallbackQueryHandler,
    CommandHandler,
    ContextTypes,
    MessageHandler,
    filters,
)

from app.config import settings
from app import db
from app.downloader import extract_info, download_media, DownloadError
from app.direct_downloader import is_direct_media_url, download_direct_media
from app.keyboards import rights_keyboard, format_keyboard
from app.policy import detect_platform, policy_message
from app.utils import extract_first_url, short_hash, human_duration, human_size
from app.temp_links import create_temp_link

PENDING: dict[str, dict] = {}
DOWNLOAD_SEMAPHORE: asyncio.Semaphore | None = None

def register_user(update: Update) -> None:
    user = update.effective_user
    if not user:
        return
    db.ensure_user(
        user_id=user.id,
        username=user.username,
        first_name=user.first_name,
        last_name=user.last_name,
    )


def user_can_download(user_id: int) -> tuple[bool, str]:
    if db.is_user_blocked(user_id):
        return False, "⛔ حساب شما برای استفاده از ربات مسدود شده است."

    limit = db.get_effective_daily_limit(user_id)
    used = db.count_today(user_id)

    if limit is not None and used >= limit:
        return False, f"محدودیت روزانه شما تمام شده است: {used}/{limit}"

    return True, ""



def progress_bar(percent: float | None) -> str:
    if percent is None:
        return "▰▱▱▱▱▱▱▱▱▱"

    blocks = int(percent // 10)
    blocks = max(0, min(10, blocks))
    return "▰" * blocks + "▱" * (10 - blocks)


def human_speed(speed: float | None) -> str:
    if not speed:
        return "نامشخص"
    return human_size(int(speed)) + "/s"


def human_eta(eta: int | None) -> str:
    if eta is None:
        return "نامشخص"
    if eta < 60:
        return f"{eta}s"
    return f"{eta // 60}m {eta % 60}s"



async def get_download_semaphore() -> asyncio.Semaphore:
    global DOWNLOAD_SEMAPHORE
    if DOWNLOAD_SEMAPHORE is None:
        limit = max(1, int(getattr(settings, "max_concurrent_downloads", 2)))
        DOWNLOAD_SEMAPHORE = asyncio.Semaphore(limit)
    return DOWNLOAD_SEMAPHORE


async def run_download_in_queue(status_msg, user_id: int, url: str, mode: str, progress_callback):
    semaphore = await get_download_semaphore()
    free_slots = getattr(semaphore, "_value", 0)

    if free_slots <= 0:
        try:
            await status_msg.edit_text(
                "⏳ درخواست شما در صف دانلود قرار گرفت.\n"
                "به محض آزاد شدن ظرفیت، دانلود شروع می‌شود."
            )
        except Exception:
            pass

    async with semaphore:
        try:
            await status_msg.edit_text(
                "🚀 نوبت شما رسید. دانلود شروع شد.\n"
                "تا چند لحظه دیگر درصد پیشرفت نمایش داده می‌شود."
            )
        except Exception:
            pass

        return await download_media(
            url,
            mode,
            user_id,
            progress_callback=progress_callback,
        )

async def progress_reporter(message, progress_state: dict, stop_event: asyncio.Event) -> None:
    last_text = ""
    last_edit = 0.0

    while not stop_event.is_set():
        await asyncio.sleep(2.0)

        state = progress_state.copy()
        if not state:
            continue

        status = state.get("status")
        if status == "downloading":
            percent = state.get("percent")
            bar = progress_bar(percent)
            text = (
                "⏳ در حال دانلود…\n\n"
                f"{bar}\n"
                f"درصد: {percent if percent is not None else 'نامشخص'}%\n"
                f"دریافت‌شده: {human_size(state.get('downloaded'))}\n"
                f"حجم کل: {human_size(state.get('total'))}\n"
                f"سرعت: {human_speed(state.get('speed'))}\n"
                f"زمان باقی‌مانده: {human_eta(state.get('eta'))}"
            )
        elif status == "processing":
            text = "⚙️ دانلود تمام شد. در حال تبدیل/آماده‌سازی فایل…"
        elif status == "error":
            text = "❌ خطا در دانلود فایل."
        else:
            continue

        now = time.time()
        if text != last_text and now - last_edit >= 1.5:
            try:
                await message.edit_text(text)
                last_text = text
                last_edit = now
            except Exception:
                pass


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    register_user(update)
    text = (
        "سلام 👋\n\n"
        "من MediaVault Bot هستم.\n"
        "لینک محتوایی را بفرست که مالک آن هستی یا اجازه دانلودش را داری.\n\n"
        "پشتیبانی نسخه اول: YouTube, Instagram, TikTok, Facebook و لینک مستقیم رسانه.\n\n"
        "دستورها:\n/help راهنما\n/history تاریخچه\n/limits محدودیت‌ها"
    )
    await update.message.reply_text(text)


async def help_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    register_user(update)
    text = (
        "راهنما:\n\n"
        "1. لینک را ارسال کن.\n"
        "2. تأیید کن که حق دانلود داری.\n"
        "3. کیفیت را انتخاب کن.\n"
        "4. منتظر آماده‌سازی فایل بمان.\n\n"
        "این ربات از کوکی، لاگین، محتوای خصوصی، DRM و دور زدن محدودیت پلتفرم‌ها پشتیبانی نمی‌کند."
    )
    await update.message.reply_text(text)


async def limits_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    register_user(update)
    user_id = update.effective_user.id
    used = db.count_today(user_id)
    text = (
        "محدودیت‌های فعلی:\n\n"
        f"حداکثر حجم فایل: {settings.max_file_mb} MB\n"
        f"حداکثر دانلود روزانه: {settings.daily_limit}\n"
        f"مصرف امروز شما: {used}/{settings.daily_limit}\n"
        f"حذف خودکار فایل‌ها بعد از: {settings.auto_delete_hours} ساعت"
    )
    await update.message.reply_text(text)


async def history_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    register_user(update)
    rows = db.user_history(update.effective_user.id, limit=10)

    if not rows:
        await update.message.reply_text("هنوز تاریخچه‌ای ثبت نشده است.")
        return

    lines = ["تاریخچه ۱۰ درخواست آخر:\n"]
    for row in rows:
        title = row["title"] or "بدون عنوان"
        platform = row["platform"] or "-"
        mode = row["mode"] or "-"
        status = row["status"]
        size = human_size(row["file_size"])
        created = row["created_at"]
        lines.append(
            f"• {title[:60]}\n"
            f"  {platform} | {mode} | {status} | {size}\n"
            f"  {created}"
        )

    await update.message.reply_text("\n\n".join(lines))


async def handle_text(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    register_user(update)
    text = update.message.text or ""
    url = extract_first_url(text)

    if not url:
        await update.message.reply_text("لطفاً یک لینک معتبر ارسال کن.")
        return

    ok, platform, error = detect_platform(url)
    if not ok:
        await update.message.reply_text(f"لینک پشتیبانی نمی‌شود.\n\n{error}")
        return

    user_id = update.effective_user.id
    allowed, reason = user_can_download(user_id)
    if not allowed:
        await update.message.reply_text(reason)
        return

    url_id = short_hash(f"{user_id}:{url}")
    PENDING[url_id] = {"url": url, "platform": platform, "user_id": user_id, "title": None}

    await update.message.reply_text(
        policy_message(platform),
        reply_markup=rights_keyboard(url_id),
        disable_web_page_preview=True,
    )


async def handle_callback(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    query = update.callback_query
    await query.answer()

    data = query.data or ""

    if data == "cancel":
        await query.edit_message_text("درخواست لغو شد.")
        return

    if data.startswith("rights:"):
        url_id = data.split(":", 1)[1]
        item = PENDING.get(url_id)

        if not item:
            await query.edit_message_text("این درخواست منقضی شده است. لینک را دوباره ارسال کن.")
            return

        await query.edit_message_text("⏳ در حال خواندن اطلاعات لینک...")
        try:
            if is_direct_media_url(item["url"]):
                item["title"] = "Direct media file"
                item["info"] = {"title": "Direct media file"}
                text = (
                    "✅ لینک مستقیم فایل شناسایی شد.\n\n"
                    "این نوع لینک بدون نیاز به استخراج از پلتفرم دانلود می‌شود.\n\n"
                    "کیفیت خروجی را انتخاب کن:"
                )
                await query.edit_message_text(text, reply_markup=format_keyboard(url_id), disable_web_page_preview=True)
                return
            if "linkedin.com" in item["url"].lower():
                await query.edit_message_text(
                    "LinkedIn در این نسخه فقط برای لینک مستقیم فایل ویدئو/صدا پشتیبانی می‌شود.\n\n"
                    "لینک‌های پست LinkedIn معمولاً نیاز به ورود دارند و پشتیبانی نمی‌شوند.\n"
                    "لطفاً لینک مستقیم فایل عمومی مثل mp4/mp3 ارسال کن."
                )
                return
            info = await extract_info(item["url"])
            item["title"] = info["title"]
            item["info"] = info
            text = (
                "🎬 محتوا پیدا شد\n\n"
                f"عنوان: {info['title'][:120]}\n"
                f"مدت: {human_duration(info.get('duration'))}\n"
                f"سازنده/کانال: {info.get('uploader') or 'نامشخص'}\n"
                f"حجم تقریبی: {human_size(info.get('filesize_approx'))}\n\n"
                "کیفیت خروجی را انتخاب کن:"
            )
            await query.edit_message_text(text, reply_markup=format_keyboard(url_id), disable_web_page_preview=True)
        except Exception as exc:
            await query.edit_message_text(f"خطا در خواندن اطلاعات لینک:\n{exc}")
        return

    if data.startswith("fmt:"):
        _, url_id, mode = data.split(":")
        item = PENDING.get(url_id)

        if not item:
            await query.edit_message_text("این درخواست منقضی شده است. لینک را دوباره ارسال کن.")
            return

        user = update.effective_user
        allowed, reason = user_can_download(user.id)
        if not allowed:
            await query.edit_message_text(reason)
            return

        title = item.get("title") or "Untitled"
        download_id = db.add_download(
            user_id=user.id,
            username=user.username,
            url=item["url"],
            platform=item["platform"],
            title=title,
            mode=mode,
            status="processing",
        )

        status_msg = await query.edit_message_text(
            "⏳ دانلود شروع شد.\n"
            "تا چند لحظه دیگر درصد پیشرفت نمایش داده می‌شود."
        )

        progress_state: dict = {}
        stop_event = asyncio.Event()
        loop = asyncio.get_running_loop()

        def progress_callback(data: dict) -> None:
            loop.call_soon_threadsafe(progress_state.update, data)

        reporter_task = asyncio.create_task(
            progress_reporter(status_msg, progress_state, stop_event)
        )

        try:
            await context.bot.send_chat_action(chat_id=user.id, action=ChatAction.UPLOAD_DOCUMENT)

            if is_direct_media_url(item["url"]):
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

            stop_event.set()
            await reporter_task

            size = file_path.stat().st_size
            db.mark_done(download_id, file_path, size)

            await status_msg.edit_text("✅ فایل آماده شد. در حال ارسال به تلگرام…")

            caption = f"✅ آماده شد\nعنوان: {title[:80]}\nحجم: {human_size(size)}\nحالت: {mode}"

            try:
                if mode == "audio" and file_path.suffix.lower() == ".mp3":
                    with file_path.open("rb") as f:
                        await context.bot.send_audio(
                            chat_id=user.id,
                            audio=f,
                            caption=caption,
                            read_timeout=120,
                            write_timeout=120,
                            connect_timeout=60,
                            pool_timeout=60,
                        )
                else:
                    with file_path.open("rb") as f:
                        await context.bot.send_document(
                            chat_id=user.id,
                            document=f,
                            caption=caption,
                            read_timeout=120,
                            write_timeout=120,
                            connect_timeout=60,
                            pool_timeout=60,
                        )

                await status_msg.edit_text("✅ ارسال فایل کامل شد.")

            except Exception:
                temp_url = create_temp_link(file_path)
                await status_msg.edit_text(
                    "⚠️ فایل آماده شد، اما ارسال مستقیم در تلگرام انجام نشد.\n\n"
                    "می‌توانید از لینک موقت زیر دانلود کنید:\n"
                    f"{temp_url}\n\n"
                    f"اعتبار لینک: {settings.temp_link_expire_hours} ساعت"
                )

        except DownloadError as exc:
            stop_event.set()
            try:
                await reporter_task
            except Exception:
                pass
            db.mark_error(download_id, str(exc))
            await context.bot.send_message(chat_id=user.id, text=f"❌ خطا در دانلود:\n{exc}")

        except Exception as exc:
            stop_event.set()
            try:
                await reporter_task
            except Exception:
                pass
            db.mark_error(download_id, str(exc))
            await context.bot.send_message(chat_id=user.id, text=f"❌ خطای غیرمنتظره:\n{exc}")

        finally:
            stop_event.set()
            PENDING.pop(url_id, None)
        return


async def cleanup_job(context: ContextTypes.DEFAULT_TYPE) -> None:
    deleted = db.cleanup_old_files(settings.auto_delete_hours)
    if deleted:
        print(f"cleanup: deleted {deleted} old files")


async def post_init(application: Application) -> None:
    db.init_db()
    application.job_queue.run_repeating(cleanup_job, interval=3600, first=30)


def main() -> None:
    if not settings.telegram_bot_token or settings.telegram_bot_token == "PUT_YOUR_BOT_TOKEN_HERE":
        raise RuntimeError("TELEGRAM_BOT_TOKEN در فایل .env تنظیم نشده است.")

    db.init_db()

    app = (
        Application.builder()
        .token(settings.telegram_bot_token)
        .post_init(post_init)
        .build()
    )

    app.add_handler(CommandHandler("start", start))
    app.add_handler(CommandHandler("help", help_cmd))
    app.add_handler(CommandHandler("history", history_cmd))
    app.add_handler(CommandHandler("limits", limits_cmd))
    app.add_handler(CallbackQueryHandler(handle_callback))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_text))

    print("MediaVault Telegram Bot V1.8 is running...")
    app.run_polling(allowed_updates=Update.ALL_TYPES)
