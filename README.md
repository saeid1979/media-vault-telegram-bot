# MediaVault Telegram Bot V1

ربات تلگرام برای دریافت لینک محتوای عمومی و مجاز، انتخاب کیفیت، دانلود، ارسال فایل و ذخیره تاریخچه.

## نکته حقوقی مهم

این پروژه فقط برای محتوایی طراحی شده که کاربر مالک آن است، اجازه دانلود دارد، یا محتوا عمومی/آزاد/آموزشی و مجاز است.

این نسخه عمداً از موارد زیر پشتیبانی نمی‌کند:

- کوکی و لاگین
- محتوای خصوصی
- دور زدن محدودیت پلتفرم‌ها
- DRM
- دانلود پلی‌لیست
- فایل‌های خیلی بزرگ

## امکانات نسخه اول

- ربات تلگرام با python-telegram-bot
- دریافت لینک YouTube, Instagram, TikTok, Facebook و لینک مستقیم رسانه
- تشخیص پلتفرم
- پیام تأیید حق دانلود
- خواندن اطلاعات اولیه ویدئو
- انتخاب کیفیت: ویدئوی کم‌حجم، ویدئوی متوسط، فقط صدا
- دانلود با yt-dlp
- ارسال فایل به تلگرام
- ذخیره تاریخچه در SQLite
- محدودیت حجم
- پاک‌سازی خودکار فایل‌های قدیمی

## پیش‌نیازها

- Python 3.10+
- FFmpeg نصب‌شده و موجود در PATH
- توکن ربات تلگرام از BotFather

## نصب روی ویندوز

```powershell
cd media_vault_telegram_bot_v1

python -m venv .venv
.\.venv\Scripts\Activate.ps1

pip install -r requirements.txt
copy .env.example .env
```

فایل `.env` را باز کن و توکن ربات را قرار بده:

```env
TELEGRAM_BOT_TOKEN=123456:ABC...
```

تست FFmpeg:

```powershell
ffmpeg -version
```

اجرای ربات:

```powershell
python run.py
```

## ساخت ربات در BotFather

1. در تلگرام برو به BotFather
2. دستور `/newbot`
3. نام ربات را بده
4. username بده که با `bot` تمام شود
5. توکن را کپی کن
6. داخل `.env` قرار بده

## دستورهای ربات

```text
/start
/help
/history
/limits
```

## محدودیت‌ها

در فایل `.env` می‌توانی این‌ها را تغییر دهی:

```env
MAX_FILE_MB=45
DAILY_LIMIT=10
AUTO_DELETE_HOURS=6
```
