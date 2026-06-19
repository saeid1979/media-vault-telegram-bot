
from pathlib import Path

PROJECT = Path.cwd()
CONFIG = PROJECT / "app" / "config.py"
RWS = PROJECT / "render_webhook_start.py"
ENV = PROJECT / ".env"
FM = PROJECT / "admin_panel" / "file_manager.py"
WEB = PROJECT / "admin_panel" / "web.py"

if not CONFIG.exists() or not RWS.exists() or not FM.exists() or not WEB.exists():
    raise FileNotFoundError("فایل‌های لازم پیدا نشدند. اول V2.0 را نصب کن و این فایل را در ریشه پروژه اجرا کن.")

# 1) config.py
config = CONFIG.read_text(encoding="utf-8")
if "auto_cleanup_on_start" not in config:
    additions = (
        '    auto_cleanup_on_start: bool = os.getenv("AUTO_CLEANUP_ON_START", "True").lower() in {"1", "true", "yes", "on"}\n'
        '    auto_cleanup_older_than_hours: int = int(os.getenv("AUTO_CLEANUP_OLDER_THAN_HOURS", "6"))\n'
        '    max_storage_mb: int = int(os.getenv("MAX_STORAGE_MB", "500"))'
    )
    anchors = [
        '    telegram_upload_max_mb: int = int(os.getenv("TELEGRAM_UPLOAD_MAX_MB", os.getenv("MAX_FILE_MB", "45")))',
        '    direct_link_max_mb: int = int(os.getenv("DIRECT_LINK_MAX_MB", os.getenv("MAX_FILE_MB", "45")))',
        '    temp_link_expire_hours: int = int(os.getenv("TEMP_LINK_EXPIRE_HOURS", "6"))',
        '    bot_language: str = os.getenv("BOT_LANGUAGE", "fa")',
    ]
    for anchor in anchors:
        if anchor in config:
            config = config.replace(anchor, anchor + "\n" + additions)
            break
    else:
        raise RuntimeError("جای مناسب برای اضافه کردن تنظیمات V2.1 در config.py پیدا نشد.")
CONFIG.write_text(config, encoding="utf-8")

# 2) .env
env = ENV.read_text(encoding="utf-8") if ENV.exists() else ""
def ensure_env(text: str, key: str, value: str) -> str:
    if any(line.startswith(key + "=") for line in text.splitlines()):
        return text
    if text and not text.endswith("\n"):
        text += "\n"
    return text + f"{key}={value}\n"

env = ensure_env(env, "AUTO_CLEANUP_ON_START", "True")
env = ensure_env(env, "AUTO_CLEANUP_OLDER_THAN_HOURS", "6")
env = ensure_env(env, "MAX_STORAGE_MB", "500")
ENV.write_text(env, encoding="utf-8")

# 3) file_manager.py functions
fm = FM.read_text(encoding="utf-8")
if "def cleanup_old_files_by_hours" not in fm:
    fm += """
def cleanup_old_files_by_hours(hours: int = 6) -> dict:
    from datetime import datetime, timedelta

    base = safe_base_dir()
    cutoff = datetime.now() - timedelta(hours=max(0, int(hours)))
    deleted = 0
    deleted_size = 0

    for item in list_managed_files(limit=100000):
        if item.modified_at < cutoff:
            try:
                size = item.size
                item.abs_path.unlink(missing_ok=True)
                deleted += 1
                deleted_size += size
            except Exception:
                pass

    for p in sorted(base.rglob("*"), reverse=True):
        if p.is_dir():
            try:
                p.rmdir()
            except OSError:
                pass

    return {
        "deleted": deleted,
        "deleted_size": deleted_size,
        "deleted_size_human": human_size(deleted_size),
        "hours": hours,
    }


def enforce_storage_limit(max_storage_mb: int = 500) -> dict:
    max_bytes = max(1, int(max_storage_mb)) * 1024 * 1024
    files = list_managed_files(limit=100000)
    total = sum(f.size for f in files)
    before = total

    deleted = 0
    deleted_size = 0

    if total <= max_bytes:
        return {
            "deleted": 0,
            "deleted_size": 0,
            "deleted_size_human": human_size(0),
            "before_size": before,
            "before_size_human": human_size(before),
            "after_size": total,
            "after_size_human": human_size(total),
            "max_storage_mb": max_storage_mb,
        }

    files.sort(key=lambda f: f.modified_at)

    for item in files:
        if total <= max_bytes:
            break
        try:
            size = item.size
            item.abs_path.unlink(missing_ok=True)
            total -= size
            deleted += 1
            deleted_size += size
        except Exception:
            pass

    base = safe_base_dir()
    for p in sorted(base.rglob("*"), reverse=True):
        if p.is_dir():
            try:
                p.rmdir()
            except OSError:
                pass

    return {
        "deleted": deleted,
        "deleted_size": deleted_size,
        "deleted_size_human": human_size(deleted_size),
        "before_size": before,
        "before_size_human": human_size(before),
        "after_size": total,
        "after_size_human": human_size(total),
        "max_storage_mb": max_storage_mb,
    }


def run_startup_cleanup() -> dict:
    from app.config import settings

    result = {
        "enabled": bool(getattr(settings, "auto_cleanup_on_start", True)),
        "old_cleanup": None,
        "storage_guard": None,
    }

    if not result["enabled"]:
        return result

    hours = int(getattr(settings, "auto_cleanup_older_than_hours", 6))
    max_mb = int(getattr(settings, "max_storage_mb", 500))

    result["old_cleanup"] = cleanup_old_files_by_hours(hours)
    result["storage_guard"] = enforce_storage_limit(max_mb)

    return result
"""
FM.write_text(fm, encoding="utf-8")

# 4) render_webhook_start.py
rws = RWS.read_text(encoding="utf-8")

if "from admin_panel.file_manager import run_startup_cleanup" not in rws:
    marker = "from admin_panel.web import app as fastapi_app"
    if marker in rws:
        rws = rws.replace(marker, marker + "\nfrom admin_panel.file_manager import run_startup_cleanup")
    else:
        rws = "from admin_panel.file_manager import run_startup_cleanup\n" + rws

if "V2.1 startup cleanup" not in rws:
    old = "    db.init_db()\n"
    new = """    db.init_db()

    try:
        cleanup_result = run_startup_cleanup()
        print(f"V2.1 startup cleanup: {cleanup_result}")
    except Exception as exc:
        print(f"V2.1 startup cleanup warning: {exc}")

"""
    if old in rws:
        rws = rws.replace(old, new, 1)
    else:
        raise RuntimeError("خط db.init_db() برای اضافه کردن cleanup پیدا نشد.")

rws = rws.replace('"version": "2.0"', '"version": "2.1"')
rws = rws.replace('"version": "1.9"', '"version": "2.1"')
rws = rws.replace("'version': '2.0'", "'version': '2.1'")
rws = rws.replace("'version': '1.9'", "'version': '2.1'")
rws = rws.replace("MediaVault Bot V2.0", "MediaVault Bot V2.1")
rws = rws.replace("MediaVault Bot V1.9", "MediaVault Bot V2.1")
RWS.write_text(rws, encoding="utf-8")

# 5) admin_panel/web.py endpoint
web = WEB.read_text(encoding="utf-8")

if "enforce_storage_limit" not in web:
    web = web.replace(
        "cleanup_old_files, safe_base_dir",
        "cleanup_old_files, cleanup_old_files_by_hours, enforce_storage_limit, safe_base_dir"
    )
    web = web.replace(
        "cleanup_old_files,",
        "cleanup_old_files, cleanup_old_files_by_hours, enforce_storage_limit,"
    )

if '@app.post("/files/enforce-storage")' not in web:
    web += """
@app.post("/files/enforce-storage")
async def files_enforce_storage(token: str = Form(""), max_mb: int = Form(500)):
    if not _v2_admin_token_ok(token):
        _v2_forbidden()
    result = enforce_storage_limit(max_mb)
    msg = urllib.parse.quote(
        f"Storage Guard انجام شد: {result['deleted']} فایل حذف شد، {result['deleted_size_human']} آزاد شد"
    )
    return RedirectResponse(
        url=f"/files?token={urllib.parse.quote(token)}&message={msg}",
        status_code=303,
    )
"""
WEB.write_text(web, encoding="utf-8")

print("OK: MediaVault Bot upgraded to V2.1 Auto Cleanup + Storage Guard.")
print("New env vars: AUTO_CLEANUP_ON_START=True, AUTO_CLEANUP_OLDER_THAN_HOURS=6, MAX_STORAGE_MB=500")
print("Next: test locally, then git add/commit/push and deploy on Render.")
