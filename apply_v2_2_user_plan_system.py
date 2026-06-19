from pathlib import Path
PROJECT = Path.cwd()
DB = PROJECT / 'app' / 'db.py'
BOT = PROJECT / 'app' / 'bot.py'
WEB = PROJECT / 'admin_panel' / 'web.py'
TEMPLATES = PROJECT / 'admin_panel' / 'templates'
CONFIG = PROJECT / 'app' / 'config.py'
ENV = PROJECT / '.env'
RWS = PROJECT / 'render_webhook_start.py'
if not DB.exists() or not BOT.exists() or not WEB.exists():
    raise FileNotFoundError('این فایل باید داخل ریشه پروژه اجرا شود: D:\\Python_project\\downloader')
TEMPLATES.mkdir(parents=True, exist_ok=True)

config = CONFIG.read_text(encoding='utf-8')
if 'normal_daily_limit' not in config:
    additions = (
        '    normal_daily_limit: int = int(os.getenv("NORMAL_DAILY_LIMIT", os.getenv("DAILY_LIMIT", "10")))\n'
        '    vip_daily_limit: int = int(os.getenv("VIP_DAILY_LIMIT", "50"))\n'
        '    admin_daily_limit: int = int(os.getenv("ADMIN_DAILY_LIMIT", "9999"))'
    )
    anchors = [
        '    max_storage_mb: int = int(os.getenv("MAX_STORAGE_MB", "500"))',
        '    auto_cleanup_older_than_hours: int = int(os.getenv("AUTO_CLEANUP_OLDER_THAN_HOURS", "6"))',
        '    daily_limit: int = int(os.getenv("DAILY_LIMIT", "10"))',
        '    bot_language: str = os.getenv("BOT_LANGUAGE", "fa")',
    ]
    for anchor in anchors:
        if anchor in config:
            config = config.replace(anchor, anchor + '\n' + additions)
            break
    else:
        raise RuntimeError('جای مناسب برای اضافه کردن تنظیمات V2.2 در config.py پیدا نشد.')
CONFIG.write_text(config, encoding='utf-8')

env = ENV.read_text(encoding='utf-8') if ENV.exists() else ''
def ensure_env(text: str, key: str, value: str) -> str:
    if any(line.startswith(key + '=') for line in text.splitlines()):
        return text
    if text and not text.endswith('\n'):
        text += '\n'
    return text + f'{key}={value}\n'
env = ensure_env(env, 'NORMAL_DAILY_LIMIT', '10')
env = ensure_env(env, 'VIP_DAILY_LIMIT', '50')
env = ensure_env(env, 'ADMIN_DAILY_LIMIT', '9999')
ENV.write_text(env, encoding='utf-8')
db = DB.read_text(encoding='utf-8')
if 'def ensure_user_plan_schema' not in db:
    db += '\ndef ensure_user_plan_schema() -> None:\n    """Ensure bot_users table and V2.2 columns exist."""\n    with get_connection() as conn:\n        conn.execute("""\n            CREATE TABLE IF NOT EXISTS bot_users (\n                user_id INTEGER PRIMARY KEY,\n                username TEXT,\n                first_name TEXT,\n                last_name TEXT,\n                role TEXT DEFAULT \'normal\',\n                is_blocked INTEGER DEFAULT 0,\n                daily_limit INTEGER,\n                created_at TEXT DEFAULT CURRENT_TIMESTAMP,\n                updated_at TEXT DEFAULT CURRENT_TIMESTAMP\n            )\n        """)\n\n        existing = {row[1] for row in conn.execute("PRAGMA table_info(bot_users)").fetchall()}\n        migrations = {\n            "username": "ALTER TABLE bot_users ADD COLUMN username TEXT",\n            "first_name": "ALTER TABLE bot_users ADD COLUMN first_name TEXT",\n            "last_name": "ALTER TABLE bot_users ADD COLUMN last_name TEXT",\n            "role": "ALTER TABLE bot_users ADD COLUMN role TEXT DEFAULT \'normal\'",\n            "is_blocked": "ALTER TABLE bot_users ADD COLUMN is_blocked INTEGER DEFAULT 0",\n            "daily_limit": "ALTER TABLE bot_users ADD COLUMN daily_limit INTEGER",\n            "created_at": "ALTER TABLE bot_users ADD COLUMN created_at TEXT DEFAULT CURRENT_TIMESTAMP",\n            "updated_at": "ALTER TABLE bot_users ADD COLUMN updated_at TEXT DEFAULT CURRENT_TIMESTAMP",\n        }\n        for col, sql in migrations.items():\n            if col not in existing:\n                conn.execute(sql)\n        conn.commit()\n\n\ndef ensure_user(user_id: int, username: str | None = None, first_name: str | None = None, last_name: str | None = None) -> dict:\n    ensure_user_plan_schema()\n    with get_connection() as conn:\n        row = conn.execute("SELECT user_id FROM bot_users WHERE user_id = ?", (user_id,)).fetchone()\n        if row is None:\n            conn.execute(\n                """\n                INSERT INTO bot_users (user_id, username, first_name, last_name, role, is_blocked, daily_limit)\n                VALUES (?, ?, ?, ?, \'normal\', 0, NULL)\n                """,\n                (user_id, username, first_name, last_name),\n            )\n        else:\n            conn.execute(\n                """\n                UPDATE bot_users\n                SET username = COALESCE(?, username),\n                    first_name = COALESCE(?, first_name),\n                    last_name = COALESCE(?, last_name),\n                    updated_at = CURRENT_TIMESTAMP\n                WHERE user_id = ?\n                """,\n                (username, first_name, last_name, user_id),\n            )\n        conn.commit()\n    return get_user_control(user_id)\n\n\ndef get_user_control(user_id: int) -> dict:\n    ensure_user_plan_schema()\n    with get_connection() as conn:\n        cur = conn.execute(\n            "SELECT user_id, username, first_name, last_name, role, is_blocked, daily_limit, created_at, updated_at FROM bot_users WHERE user_id = ?",\n            (user_id,),\n        )\n        row = cur.fetchone()\n        if row is None:\n            return {"user_id": user_id, "username": None, "first_name": None, "last_name": None, "role": "normal", "is_blocked": 0, "daily_limit": None}\n        columns = [d[0] for d in cur.description]\n        return dict(zip(columns, row))\n\n\ndef get_role_daily_limit(role: str) -> int:\n    from app.config import settings\n    role = (role or "normal").lower()\n    if role == "admin":\n        return int(getattr(settings, "admin_daily_limit", 9999))\n    if role == "vip":\n        return int(getattr(settings, "vip_daily_limit", 50))\n    return int(getattr(settings, "normal_daily_limit", getattr(settings, "daily_limit", 10)))\n\n\ndef get_effective_daily_limit(user_id: int) -> int:\n    user = get_user_control(user_id)\n    custom = user.get("daily_limit")\n    if custom is not None:\n        try:\n            return int(custom)\n        except Exception:\n            pass\n    return get_role_daily_limit(user.get("role", "normal"))\n\n\ndef is_user_blocked(user_id: int) -> bool:\n    user = get_user_control(user_id)\n    return bool(int(user.get("is_blocked") or 0))\n\n\ndef update_user_control(user_id: int, role: str | None = None, is_blocked: int | bool | None = None, daily_limit: int | None = None) -> None:\n    ensure_user_plan_schema()\n    role = (role or "normal").lower()\n    if role not in {"normal", "vip", "admin"}:\n        role = "normal"\n    blocked_value = 1 if bool(is_blocked) else 0\n    with get_connection() as conn:\n        conn.execute(\n            """\n            INSERT INTO bot_users (user_id, role, is_blocked, daily_limit)\n            VALUES (?, ?, ?, ?)\n            ON CONFLICT(user_id) DO UPDATE SET\n                role = excluded.role,\n                is_blocked = excluded.is_blocked,\n                daily_limit = excluded.daily_limit,\n                updated_at = CURRENT_TIMESTAMP\n            """,\n            (user_id, role, blocked_value, daily_limit),\n        )\n        conn.commit()\n\n\ndef list_users(limit: int = 500) -> list[dict]:\n    ensure_user_plan_schema()\n    with get_connection() as conn:\n        cur = conn.execute(\n            """\n            SELECT user_id, username, first_name, last_name, role, is_blocked, daily_limit, created_at, updated_at\n            FROM bot_users\n            ORDER BY updated_at DESC\n            LIMIT ?\n            """,\n            (limit,),\n        )\n        columns = [d[0] for d in cur.description]\n        return [dict(zip(columns, row)) for row in cur.fetchall()]\n\n\ndef count_today_downloads(user_id: int) -> int:\n    with get_connection() as conn:\n        try:\n            row = conn.execute(\n                """\n                SELECT COUNT(*)\n                FROM downloads\n                WHERE user_id = ?\n                  AND date(created_at) = date(\'now\', \'localtime\')\n                  AND status IN (\'done\', \'processing\')\n                """,\n                (user_id,),\n            ).fetchone()\n            return int(row[0] if row else 0)\n        except Exception:\n            return 0\n\n\ndef get_user_usage_status(user_id: int) -> dict:\n    user = get_user_control(user_id)\n    limit = get_effective_daily_limit(user_id)\n    used = count_today_downloads(user_id)\n    remaining = max(0, limit - used)\n    return {"user": user, "limit": limit, "used": used, "remaining": remaining, "is_blocked": is_user_blocked(user_id)}\n'
if '_v22_original_init_db' not in db:
    db += '\n# V2.2 init_db wrapper\ntry:\n    _v22_original_init_db = init_db\n\n    def init_db() -> None:\n        _v22_original_init_db()\n        ensure_user_plan_schema()\nexcept NameError:\n    pass\n'
DB.write_text(db, encoding='utf-8')
(TEMPLATES / 'users.html').write_text('<!doctype html>\n<html lang="fa" dir="rtl">\n<head>\n  <meta charset="utf-8">\n  <title>MediaVault Users</title>\n  <meta name="viewport" content="width=device-width, initial-scale=1">\n  <style>\n    body{background:#0b1220;color:#f9fafb;font-family:Tahoma,Arial,sans-serif}\n    .wrap{max-width:1200px;margin:24px auto;padding:0 16px}\n    .card{background:#111827;border-radius:18px;padding:18px;margin-bottom:18px;box-shadow:0 10px 30px rgba(0,0,0,.2)}\n    .top{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;align-items:center}\n    h1{font-size:24px;margin:0 0 8px}.sub{color:#cbd5e1}\n    table{width:100%;border-collapse:collapse}th,td{padding:10px;border-bottom:1px solid #374151;text-align:right}\n    th{background:#1f2937}.ltr{direction:ltr;text-align:left}\n    .btn{border:0;border-radius:10px;padding:8px 12px;text-decoration:none;color:white;font-weight:700;cursor:pointer;display:inline-block}\n    .blue{background:#2563eb}.green{background:#16a34a}.red{background:#dc2626}\n    .actions{display:flex;gap:8px;flex-wrap:wrap;align-items:center}\n    .input,.select{background:#0b1220;color:white;border:1px solid #374151;border-radius:10px;padding:8px;max-width:120px}\n    .badge{border-radius:999px;padding:4px 9px;font-weight:700;display:inline-block}\n    .normal{background:#374151}.vip{background:#7c3aed}.admin{background:#16a34a}.blocked{background:#dc2626}\n    .msg{background:#064e3b}\n    @media(max-width:900px){th:nth-child(4),td:nth-child(4),th:nth-child(8),td:nth-child(8){display:none}}\n  </style>\n</head>\n<body>\n<div class="wrap">\n  <div class="card">\n    <div class="top">\n      <div>\n        <h1>مدیریت کاربران - MediaVault V2.2</h1>\n        <div class="sub">نقش کاربران، محدودیت روزانه، VIP، Admin و Block</div>\n      </div>\n      <div class="actions">\n        <a class="btn blue" href="/?token={{ token }}">داشبورد</a>\n        <a class="btn blue" href="/files?token={{ token }}">فایل\u200cها</a>\n        <a class="btn blue" href="/users?token={{ token }}">تازه\u200cسازی</a>\n      </div>\n    </div>\n    {% if message %}<div class="card msg">{{ message }}</div>{% endif %}\n  </div>\n  <div class="card">\n    <table>\n      <thead>\n        <tr>\n          <th>User ID</th><th>نام</th><th>Username</th><th>نقش</th><th>Blocked</th><th>Limit</th><th>مصرف امروز</th><th>آخرین بروزرسانی</th><th>عملیات</th>\n        </tr>\n      </thead>\n      <tbody>\n      {% for u in users %}\n        <tr>\n          <td class="ltr">{{ u.user_id }}</td>\n          <td>{{ u.first_name or "" }} {{ u.last_name or "" }}</td>\n          <td class="ltr">{{ "@" + u.username if u.username else "" }}</td>\n          <td><span class="badge {{ u.role }}">{{ u.role }}</span></td>\n          <td>{% if u.is_blocked %}<span class="badge blocked">Blocked</span>{% else %}No{% endif %}</td>\n          <td>{{ u.effective_limit }}</td>\n          <td>{{ u.used_today }}</td>\n          <td class="ltr">{{ u.updated_at or "" }}</td>\n          <td>\n            <form method="post" action="/users/update" class="actions">\n              <input type="hidden" name="token" value="{{ token }}">\n              <input type="hidden" name="user_id" value="{{ u.user_id }}">\n              <select name="role" class="select">\n                <option value="normal" {% if u.role == "normal" %}selected{% endif %}>normal</option>\n                <option value="vip" {% if u.role == "vip" %}selected{% endif %}>vip</option>\n                <option value="admin" {% if u.role == "admin" %}selected{% endif %}>admin</option>\n              </select>\n              <select name="is_blocked" class="select">\n                <option value="0" {% if not u.is_blocked %}selected{% endif %}>active</option>\n                <option value="1" {% if u.is_blocked %}selected{% endif %}>blocked</option>\n              </select>\n              <input class="input" type="number" name="daily_limit" value="{{ u.daily_limit or \'\' }}" placeholder="limit">\n              <button class="btn green" type="submit">ذخیره</button>\n            </form>\n          </td>\n        </tr>\n      {% endfor %}\n      </tbody>\n    </table>\n  </div>\n</div>\n</body>\n</html>\n', encoding='utf-8')

web = WEB.read_text(encoding='utf-8')
if 'from app import db as app_db' not in web:
    web += '\nfrom app import db as app_db\n'
if 'Form' not in web:
    if 'from fastapi import' in web:
        web = web.replace('from fastapi import', 'from fastapi import Form,')
    else:
        web = 'from fastapi import Form\n' + web
if 'urllib.parse' not in web:
    web = 'import urllib.parse\n' + web
if '@app.get("/users")' not in web:
    web += '\n@app.get("/users")\nasync def users_page(request: Request, token: str = "", message: str = ""):\n    if not _v2_admin_token_ok(token):\n        _v2_forbidden()\n\n    users = []\n    for u in app_db.list_users():\n        usage = app_db.get_user_usage_status(int(u["user_id"]))\n        u["effective_limit"] = usage["limit"]\n        u["used_today"] = usage["used"]\n        u["is_blocked"] = bool(int(u.get("is_blocked") or 0))\n        u["role"] = (u.get("role") or "normal").lower()\n        users.append(u)\n\n    return templates.TemplateResponse(\n        "users.html",\n        {"request": request, "token": token, "users": users, "message": message},\n    )\n\n\n@app.post("/users/update")\nasync def users_update(token: str = Form(""), user_id: int = Form(...), role: str = Form("normal"), is_blocked: int = Form(0), daily_limit: str = Form("")):\n    if not _v2_admin_token_ok(token):\n        _v2_forbidden()\n\n    custom_limit = None\n    if daily_limit is not None and str(daily_limit).strip() != "":\n        try:\n            custom_limit = max(0, int(daily_limit))\n        except Exception:\n            custom_limit = None\n\n    app_db.update_user_control(user_id=int(user_id), role=role, is_blocked=int(is_blocked), daily_limit=custom_limit)\n\n    msg = urllib.parse.quote(f"کاربر {user_id} بروزرسانی شد.")\n    return RedirectResponse(url=f"/users?token={urllib.parse.quote(token)}&message={msg}", status_code=303)\n'
WEB.write_text(web, encoding='utf-8')
bot = BOT.read_text(encoding='utf-8')
new_limits_cmd = '\nasync def limits_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:\n    user = update.effective_user\n    if not user:\n        return\n\n    db.ensure_user(user_id=user.id, username=user.username, first_name=user.first_name, last_name=user.last_name)\n\n    status = db.get_user_usage_status(user.id)\n    info = status["user"]\n    role = (info.get("role") or "normal").lower()\n\n    if status["is_blocked"]:\n        await update.effective_message.reply_text(\n            "⛔ حساب شما در حال حاضر مسدود است.\\\\n"\n            "برای فعال\u200cسازی دوباره با مدیر ربات تماس بگیرید."\n        )\n        return\n\n    await update.effective_message.reply_text(\n        "📊 وضعیت حساب شما\\\\n\\\\n"\n        f"نقش حساب: {role}\\\\n"\n        f"مصرف امروز: {status[\'used\']}\\\\n"\n        f"محدودیت روزانه: {status[\'limit\']}\\\\n"\n        f"باقی\u200cمانده امروز: {status[\'remaining\']}\\\\n\\\\n"\n        "Normal: محدودیت عادی\\\\n"\n        "VIP: محدودیت بیشتر\\\\n"\n        "Admin: دسترسی مدیریتی"\n    )\n'
helper = '\nasync def _v22_check_user_access(update: Update) -> bool:\n    user = update.effective_user\n    msg = update.effective_message\n    if not user or not msg:\n        return False\n\n    db.ensure_user(user_id=user.id, username=user.username, first_name=user.first_name, last_name=user.last_name)\n\n    if db.is_user_blocked(user.id):\n        await msg.reply_text("⛔ حساب شما برای استفاده از ربات مسدود شده است.")\n        return False\n\n    usage = db.get_user_usage_status(user.id)\n    if usage["remaining"] <= 0:\n        await msg.reply_text(\n            "⏳ محدودیت روزانه شما تمام شده است.\\\\n\\\\n"\n            f"مصرف امروز: {usage[\'used\']}\\\\n"\n            f"محدودیت روزانه: {usage[\'limit\']}\\\\n\\\\n"\n            "برای افزایش محدودیت، حساب VIP لازم است."\n        )\n        return False\n\n    return True\n'

if 'async def limits_cmd' in bot:
    start = bot.find('async def limits_cmd')
    next_def = bot.find('\nasync def ', start + 1)
    if next_def != -1:
        bot = bot[:start] + new_limits_cmd.lstrip() + '\n' + bot[next_def+1:]
    else:
        bot = bot[:start] + new_limits_cmd.lstrip() + '\n'
else:
    bot += '\n' + new_limits_cmd.lstrip() + '\n'

if 'async def _v22_check_user_access' not in bot:
    if 'async def handle_text' in bot:
        bot = bot.replace('async def handle_text', helper.lstrip() + '\nasync def handle_text', 1)
    else:
        bot += '\n' + helper.lstrip()

if 'async def handle_text' in bot and '_v22_check_user_access(update)' not in bot:
    marker = 'async def handle_text(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:\n'
    if marker in bot:
        bot = bot.replace(marker, marker + '    if not await _v22_check_user_access(update):\n        return\n\n', 1)

for old in ['MediaVault Telegram Bot V2.1', 'MediaVault Telegram Bot V2.0', 'MediaVault Telegram Bot V1.9']:
    bot = bot.replace(old, 'MediaVault Telegram Bot V2.2')
BOT.write_text(bot, encoding='utf-8')

if RWS.exists():
    rws = RWS.read_text(encoding='utf-8')
    rws = rws.replace('"version": "2.1"', '"version": "2.2"')
    rws = rws.replace('"version": "2.0"', '"version": "2.2"')
    rws = rws.replace("'version': '2.1'", "'version': '2.2'")
    rws = rws.replace("'version': '2.0'", "'version': '2.2'")
    rws = rws.replace('MediaVault Bot V2.1', 'MediaVault Bot V2.2')
    rws = rws.replace('MediaVault Bot V2.0', 'MediaVault Bot V2.2')
    RWS.write_text(rws, encoding='utf-8')

print('OK: MediaVault Bot upgraded to V2.2 User Plan System.')
print('Open users admin:')
print('http://127.0.0.1:8080/users?token=YOUR_ADMIN_TOKEN')
print('Next: test locally, then git add/commit/push and deploy on Render.')
