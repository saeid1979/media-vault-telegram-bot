from telegram import InlineKeyboardButton, InlineKeyboardMarkup


def rights_keyboard(url_id: str) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup([
        [InlineKeyboardButton("✅ تأیید می‌کنم حق دانلود دارم", callback_data=f"rights:{url_id}")],
        [InlineKeyboardButton("❌ لغو", callback_data="cancel")],
    ])


def format_keyboard(url_id: str) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup([
        [InlineKeyboardButton("📱 ویدئو کم‌حجم", callback_data=f"fmt:{url_id}:video_low")],
        [InlineKeyboardButton("🎬 ویدئو متوسط", callback_data=f"fmt:{url_id}:video_medium")],
        [InlineKeyboardButton("🎧 فقط صدا", callback_data=f"fmt:{url_id}:audio")],
        [InlineKeyboardButton("❌ لغو", callback_data="cancel")],
    ])
