"""Opt-in Telegram precipitation alerts using Open-Meteo hourly forecasts."""
from __future__ import annotations

import asyncio
import json
import logging
import os
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

from telegram import Update
from telegram.error import Forbidden
from telegram.ext import ContextTypes

from app import db

logger = logging.getLogger(__name__)
THRESHOLD = max(0, min(100, int(os.getenv("RAIN_THRESHOLD", "50"))))
LOOKAHEAD_HOURS = max(1, min(72, int(os.getenv("RAIN_LOOKAHEAD_HOURS", "24"))))
CHECK_MINUTES = max(15, int(os.getenv("RAIN_CHECK_MINUTES", "60")))
MIN_GAP_HOURS = max(0, int(os.getenv("RAIN_MIN_ALERT_GAP_HOURS", "6")))

# Coordinates are city centers; forecast values represent model grid cells, not street observations.
CITIES = {
    "salamanca": ("سالامانکا", 40.9701, -5.6635, "Europe/Madrid"),
    "madrid": ("مادرید", 40.4168, -3.7038, "Europe/Madrid"),
    "tehran": ("تهران", 35.6892, 51.3890, "Asia/Tehran"),
    "ardabil": ("اردبیل", 38.2498, 48.2933, "Asia/Tehran"),
}
ALIASES = {
    "سالامانکا": "salamanca", "salamanca": "salamanca",
    "مادرید": "madrid", "madrid": "madrid",
    "تهران": "tehran", "tehran": "tehran",
    "اردبیل": "ardabil", "ardabil": "ardabil",
}


def init_db() -> None:
    with db.connect() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS rain_subscriptions (
                chat_id INTEGER NOT NULL,
                city TEXT NOT NULL,
                PRIMARY KEY (chat_id, city)
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS rain_alert_events (
                chat_id INTEGER NOT NULL,
                city TEXT NOT NULL,
                event_start_utc TEXT NOT NULL,
                sent_at_utc TEXT NOT NULL,
                PRIMARY KEY (chat_id, city, event_start_utc)
            )
        """)


def subscribe(chat_id: int, city: str) -> None:
    with db.connect() as conn:
        conn.execute(
            "INSERT OR IGNORE INTO rain_subscriptions(chat_id, city) VALUES (?, ?)",
            (chat_id, city),
        )


def unsubscribe(chat_id: int, city: str | None = None) -> None:
    with db.connect() as conn:
        if city:
            conn.execute(
                "DELETE FROM rain_subscriptions WHERE chat_id = ? AND city = ?",
                (chat_id, city),
            )
        else:
            conn.execute("DELETE FROM rain_subscriptions WHERE chat_id = ?", (chat_id,))


def subscribed_cities(chat_id: int) -> list[str]:
    with db.connect() as conn:
        rows = conn.execute(
            "SELECT city FROM rain_subscriptions WHERE chat_id = ? ORDER BY city",
            (chat_id,),
        ).fetchall()
    return [row["city"] for row in rows if row["city"] in CITIES]


def subscribers(city: str) -> list[int]:
    with db.connect() as conn:
        rows = conn.execute(
            "SELECT chat_id FROM rain_subscriptions WHERE city = ?", (city,)
        ).fetchall()
    return [row["chat_id"] for row in rows]


def already_alerted(chat_id: int, city: str, event_start: str) -> bool:
    with db.connect() as conn:
        row = conn.execute(
            """SELECT 1 FROM rain_alert_events
               WHERE chat_id = ? AND city = ? AND event_start_utc = ?""",
            (chat_id, city, event_start),
        ).fetchone()
        if row:
            return True
        last = conn.execute(
            "SELECT MAX(sent_at_utc) AS sent FROM rain_alert_events WHERE chat_id = ? AND city = ?",
            (chat_id, city),
        ).fetchone()
    if last and last["sent"]:
        previous = datetime.fromisoformat(last["sent"])
        if datetime.now(timezone.utc) - previous < timedelta(hours=MIN_GAP_HOURS):
            return True
    return False


def mark_alerted(chat_id: int, city: str, event_start: str) -> None:
    with db.connect() as conn:
        conn.execute(
            """INSERT OR IGNORE INTO rain_alert_events
               (chat_id, city, event_start_utc, sent_at_utc)
               VALUES (?, ?, ?, ?)""",
            (chat_id, city, event_start, datetime.now(timezone.utc).isoformat()),
        )
        conn.execute(
            "DELETE FROM rain_alert_events WHERE sent_at_utc < ?",
            ((datetime.now(timezone.utc) - timedelta(days=14)).isoformat(),),
        )


def _read_forecast(city: str) -> dict:
    _, latitude, longitude, _ = CITIES[city]
    params = urlencode({
        "latitude": latitude,
        "longitude": longitude,
        "hourly": "precipitation_probability",
        "timeformat": "unixtime",
        "timezone": "UTC",
        "forecast_hours": LOOKAHEAD_HOURS + 2,
    })
    req = Request(
        "https://api.open-meteo.com/v1/forecast?" + params,
        headers={"User-Agent": "MediaVault-WeatherAlerts/1.0"},
    )
    with urlopen(req, timeout=15) as response:
        return json.load(response)


def rain_periods(forecast: dict, now: datetime | None = None) -> list[tuple[int, int, int]]:
    """Return (start_epoch, end_epoch_exclusive, max_probability) for rain periods."""
    now = now or datetime.now(timezone.utc)
    now_ts = int(now.timestamp())
    stop = now_ts + LOOKAHEAD_HOURS * 3600
    hourly = forecast.get("hourly") or {}
    hours = hourly.get("time") or []
    chances = hourly.get("precipitation_probability") or []
    periods: list[tuple[int, int, int]] = []
    start = end = peak = None
    for ts, probability in sorted(zip(hours, chances)):
        if not isinstance(probability, (int, float)):
            continue
        ts = int(ts)
        if ts + 3600 < now_ts or ts >= stop:
            continue
        if probability > THRESHOLD:
            if start is not None and ts == end:
                end += 3600
                peak = max(peak, round(probability))
            else:
                if start is not None:
                    periods.append((start, end, peak))
                start, end, peak = ts, ts + 3600, round(probability)
        elif start is not None:
            periods.append((start, end, peak))
            start = end = peak = None
    if start is not None:
        periods.append((start, end, peak))
    return periods


def format_alert(city: str, period: tuple[int, int, int]) -> str:
    name, _, _, tz_name = CITIES[city]
    tz = ZoneInfo(tz_name)
    start_ts, end_ts, peak = period
    start = datetime.fromtimestamp(start_ts, tz=tz)
    end = datetime.fromtimestamp(end_ts, tz=tz)
    time_range = (
        f"{start:%Y-%m-%d} از {start:%H:%M} تا "
        f"{end:%H:%M}" if start.date() == end.date() else
        f"{start:%Y-%m-%d %H:%M} تا {end:%Y-%m-%d %H:%M}"
    )
    return (
        f"🌧️ هشدار احتمال بارش در {name}\n\n"
        f"📊 بیشترین احتمال: {peak}٪ (بیشتر از {THRESHOLD}٪)\n"
        f"🕒 زمان پیش‌بینی‌شده: {time_range}\n"
        "⚠️ این یک پیش‌بینی احتمالی است، نه هشدار رسمی هواشناسی.\n"
        "منبع: Open-Meteo"
    )


def _parse_cities(arguments: list[str], default_all: bool = True) -> list[str] | None:
    name = " ".join(arguments).strip().lower()
    if not name or name in {"all", "همه"}:
        return list(CITIES) if default_all else []
    city = ALIASES.get(name)
    return [city] if city else None


async def _require_private(update: Update) -> bool:
    if not update.effective_chat or update.effective_chat.type != "private":
        if update.effective_message:
            await update.effective_message.reply_text("برای هشدارهای شخصی بارندگی، این دستور را در چت خصوصی بات اجرا کن.")
        return False
    return True


async def rain_on(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    if not await _require_private(update):
        return
    cities = _parse_cities(context.args)
    if cities is None:
        await update.effective_message.reply_text(
            "شهر شناخته نشد. نام‌های مجاز: salamanca, madrid, tehran, ardabil یا all"
        )
        return
    init_db()
    for city in cities:
        subscribe(update.effective_chat.id, city)
    names = "، ".join(CITIES[c][0] for c in cities)
    await update.effective_message.reply_text(
        f"✅ هشدار احتمال بارندگی بالاتر از {THRESHOLD}٪ فعال شد.\n"
        f"📍 شهرها: {names}\n"
        f"⏰ بررسی هر {CHECK_MINUTES} دقیقه؛ اعلان تکراری محدود می‌شود.\n"
        "برای خاموش‌کردن: /rain_off"
    )


async def rain_off(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    if not await _require_private(update):
        return
    cities = _parse_cities(context.args)
    if cities is None:
        await update.effective_message.reply_text("شهر نامعتبر است. برای قطع همه: /rain_off")
        return
    init_db()
    if not context.args or " ".join(context.args).strip().lower() in {"all", "همه"}:
        unsubscribe(update.effective_chat.id)
    else:
        for city in cities:
            unsubscribe(update.effective_chat.id, city)
    await update.effective_message.reply_text("🔕 هشدارهای انتخاب‌شده بارندگی غیرفعال شدند.")


async def rain_status(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    if not await _require_private(update):
        return
    init_db()
    cities = subscribed_cities(update.effective_chat.id)
    if not cities:
        await update.effective_message.reply_text("هشدار بارندگی فعال نیست. برای فعال‌سازی: /rain_on")
        return
    await update.effective_message.reply_text(
        f"✅ هشدار بالاتر از {THRESHOLD}٪ فعال است:\n"
        + "، ".join(CITIES[c][0] for c in cities)
        + f"\n⏰ بررسی هر {CHECK_MINUTES} دقیقه"
    )


async def rain_now(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    if not await _require_private(update):
        return
    init_db()
    if context.args:
        cities = _parse_cities(context.args)
    else:
        cities = subscribed_cities(update.effective_chat.id) or list(CITIES)
    if cities is None:
        await update.effective_message.reply_text("شهر نامعتبر است. مثال: /rain_now salamanca")
        return
    for city in cities:
        try:
            forecast = await asyncio.to_thread(_read_forecast, city)
            periods = rain_periods(forecast)
            if periods:
                await update.effective_message.reply_text(format_alert(city, periods[0]))
            else:
                await update.effective_message.reply_text(
                    f"☀️ در {CITIES[city][0]} طی {LOOKAHEAD_HOURS} ساعت آینده "
                    f"احتمال بارش بالاتر از {THRESHOLD}٪ پیش‌بینی نشده است."
                )
        except Exception:
            logger.exception("Rain forecast preview failed for %s", city)
            await update.effective_message.reply_text(
                f"⚠️ دریافت پیش‌بینی برای {CITIES[city][0]} ممکن نشد."
            )


async def rain_check_job(context: ContextTypes.DEFAULT_TYPE) -> None:
    """Check subscribed cities once per interval; never announce below/equal threshold."""
    for city in CITIES:
        chat_ids = subscribers(city)
        if not chat_ids:
            continue
        try:
            forecast = await asyncio.to_thread(_read_forecast, city)
            periods = rain_periods(forecast)
        except Exception:
            logger.exception("Rain forecast fetch failed for %s", city)
            continue
        for chat_id in chat_ids:
            for period in periods:
                event_start = datetime.fromtimestamp(period[0], timezone.utc).isoformat()
                if already_alerted(chat_id, city, event_start):
                    continue
                try:
                    await context.bot.send_message(chat_id=chat_id, text=format_alert(city, period))
                except Forbidden:
                    unsubscribe(chat_id, city)
                    break
                except Exception:
                    logger.exception("Could not send rain warning to chat %s", chat_id)
                    break
                mark_alerted(chat_id, city, event_start)
                # One message per city per scheduled check avoids bursts.
                break
