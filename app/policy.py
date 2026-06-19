from urllib.parse import urlparse

SUPPORTED_DOMAINS = {
    "youtube.com": "YouTube",
    "www.youtube.com": "YouTube",
    "m.youtube.com": "YouTube",
    "youtu.be": "YouTube",
    "instagram.com": "Instagram",
    "www.instagram.com": "Instagram",
    "tiktok.com": "TikTok",
    "www.tiktok.com": "TikTok",
    "vm.tiktok.com": "TikTok",
    "facebook.com": "Facebook",
    "www.facebook.com": "Facebook",
    "fb.watch": "Facebook",
}


def detect_platform(url: str) -> tuple[bool, str, str]:
    parsed = urlparse(url.strip())
    if parsed.scheme not in {"http", "https"}:
        return False, "", "لینک باید با http یا https شروع شود."

    host = (parsed.hostname or "").lower().strip()
    if host in SUPPORTED_DOMAINS:
        return True, SUPPORTED_DOMAINS[host], ""

    path = parsed.path.lower()
    if path.endswith((".mp4", ".webm", ".mov", ".m4a", ".mp3", ".wav")):
        return True, "Direct media", ""

    return False, "", "این دامنه در نسخه اول پشتیبانی نمی‌شود."


def policy_message(platform: str) -> str:
    return (
        f"پلتفرم تشخیص داده شد: {platform}\n\n"
        "قبل از ادامه تأیید کن که حق دانلود و استفاده از این محتوا را داری.\n\n"
        "این ربات برای محتوای شخصی، آموزشی، آزاد، عمومی و مجاز است.\n"
        "محتوای خصوصی، دارای DRM، محتوای بدون اجازه، یا دور زدن محدودیت پلتفرم‌ها پشتیبانی نمی‌شود."
    )
