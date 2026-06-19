from pathlib import Path

PROJECT = Path.cwd()
BOT = PROJECT / "app" / "bot.py"
DB = PROJECT / "app" / "db.py"

if not BOT.exists() or not DB.exists():
    raise FileNotFoundError("Run from project root: D:\\Python_project\\downloader")

text = BOT.read_text(encoding="utf-8")

helper = '''

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
'''

if "def register_user(update: Update)" not in text:
    text = text.replace("PENDING: dict[str, dict] = {}", "PENDING: dict[str, dict] = {}" + helper)

handlers = ["start", "help_cmd", "limits_cmd", "history_cmd", "handle_text"]
for name in handlers:
    sig = f"async def {name}(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:\n"
    if sig in text and sig + "    register_user(update)\n" not in text:
        text = text.replace(sig, sig + "    register_user(update)\n", 1)

old = '''    user_id = update.effective_user.id
    if db.count_today(user_id) >= settings.daily_limit:
        await update.message.reply_text(f"محدودیت روزانه شما تمام شده است: {settings.daily_limit} دانلود در روز.")
        return
'''
new = '''    user_id = update.effective_user.id
    allowed, reason = user_can_download(user_id)
    if not allowed:
        await update.message.reply_text(reason)
        return
'''
text = text.replace(old, new)

old2 = '''        user = update.effective_user
        if db.count_today(user.id) >= settings.daily_limit:
            await query.edit_message_text(f"محدودیت روزانه شما تمام شده است: {settings.daily_limit} دانلود در روز.")
            return
'''
new2 = '''        user = update.effective_user
        allowed, reason = user_can_download(user.id)
        if not allowed:
            await query.edit_message_text(reason)
            return
'''
text = text.replace(old2, new2)

BOT.write_text(text, encoding="utf-8")
print("OK: bot.py patched for V1.3 user control")
