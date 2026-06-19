from pathlib import Path

PROJECT = Path.cwd()
ENV = PROJECT / '.env'
REQ = PROJECT / 'requirements.txt'

if not (PROJECT / 'app' / 'bot.py').exists():
    raise FileNotFoundError('این فایل باید داخل ریشه پروژه اجرا شود: D:\\Python_project\\downloader')

required = [
    'python-telegram-bot[job-queue]==22.4',
    'yt-dlp>=2025.01.01',
    'python-dotenv==1.0.1',
    'fastapi==0.115.12',
    'uvicorn[standard]==0.34.3',
    'jinja2==3.1.6',
    'python-multipart==0.0.20',
]
existing = REQ.read_text(encoding='utf-8').splitlines() if REQ.exists() else []
clean_existing = [line.strip() for line in existing if line.strip() and not line.strip().startswith('#')]
for item in required:
    package_name = item.split('==')[0].split('>=')[0].lower()
    if not any(line.lower().startswith(package_name) for line in clean_existing):
        clean_existing.append(item)
REQ.write_text('\n'.join(clean_existing) + '\n', encoding='utf-8')

env_text = ENV.read_text(encoding='utf-8') if ENV.exists() else ''
def ensure_line(text: str, key: str, value: str) -> str:
    if any(line.startswith(key + '=') for line in text.splitlines()):
        return text
    if text and not text.endswith('\n'):
        text += '\n'
    return text + f'{key}={value}\n'
env_text = ensure_line(env_text, 'PUBLIC_BASE_URL', 'http://127.0.0.1:8080')
env_text = ensure_line(env_text, 'TELEGRAM_WEBHOOK_SECRET', 'change-this-webhook-secret')
env_text = ensure_line(env_text, 'ADMIN_TOKEN', 'change-this-long-secret')
env_text = ensure_line(env_text, 'TEMP_LINK_SECRET', 'change-this-temp-link-secret')
env_text = ensure_line(env_text, 'TEMP_LINK_EXPIRE_HOURS', '6')
env_text = ensure_line(env_text, 'MAX_CONCURRENT_DOWNLOADS', '1')
ENV.write_text(env_text, encoding='utf-8')

(PROJECT / 'render_webhook_start.py').write_text("from __future__ import annotations\n\nimport os\nfrom contextlib import asynccontextmanager\n\nimport uvicorn\nfrom dotenv import load_dotenv\nfrom fastapi import Request, HTTPException\nfrom telegram import Update\nfrom telegram.ext import (\n    Application,\n    CallbackQueryHandler,\n    CommandHandler,\n    MessageHandler,\n    filters,\n)\n\nload_dotenv()\n\nfrom admin_panel.web import app as fastapi_app\nfrom app.config import settings\nfrom app import db\nfrom app.bot import (\n    start,\n    help_cmd,\n    history_cmd,\n    limits_cmd,\n    handle_callback,\n    handle_text,\n    cleanup_job,\n)\n\n\ntelegram_app: Application | None = None\n\n\ndef build_telegram_application() -> Application:\n    if not settings.telegram_bot_token or settings.telegram_bot_token == 'PUT_YOUR_BOT_TOKEN_HERE':\n        raise RuntimeError('TELEGRAM_BOT_TOKEN در Environment Variables یا .env تنظیم نشده است.')\n\n    app = Application.builder().token(settings.telegram_bot_token).build()\n    app.add_handler(CommandHandler('start', start))\n    app.add_handler(CommandHandler('help', help_cmd))\n    app.add_handler(CommandHandler('history', history_cmd))\n    app.add_handler(CommandHandler('limits', limits_cmd))\n    app.add_handler(CallbackQueryHandler(handle_callback))\n    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_text))\n    return app\n\n\n@asynccontextmanager\nasync def lifespan(app):\n    global telegram_app\n    db.init_db()\n    telegram_app = build_telegram_application()\n    await telegram_app.initialize()\n    await telegram_app.start()\n\n    if telegram_app.job_queue:\n        telegram_app.job_queue.run_repeating(cleanup_job, interval=3600, first=30)\n\n    public_base_url = os.getenv('PUBLIC_BASE_URL', settings.public_base_url).rstrip('/')\n    webhook_secret = os.getenv('TELEGRAM_WEBHOOK_SECRET', 'change-this-webhook-secret')\n    webhook_url = f'{public_base_url}/telegram-webhook/{webhook_secret}'\n\n    await telegram_app.bot.set_webhook(\n        url=webhook_url,\n        allowed_updates=Update.ALL_TYPES,\n        drop_pending_updates=True,\n    )\n\n    print('MediaVault Bot V1.7 webhook is running.')\n    print(f'Webhook URL: {webhook_url}')\n\n    try:\n        yield\n    finally:\n        if telegram_app:\n            try:\n                await telegram_app.bot.delete_webhook(drop_pending_updates=False)\n            except Exception as exc:\n                print(f'delete_webhook warning: {exc}')\n            await telegram_app.stop()\n            await telegram_app.shutdown()\n\n\nfastapi_app.router.lifespan_context = lifespan\n\n\n@fastapi_app.get('/healthz')\nasync def healthz():\n    return {'status': 'ok', 'mode': 'webhook', 'version': '1.7'}\n\n\n@fastapi_app.post('/telegram-webhook/{secret}')\nasync def telegram_webhook(secret: str, request: Request):\n    webhook_secret = os.getenv('TELEGRAM_WEBHOOK_SECRET', 'change-this-webhook-secret')\n    if secret != webhook_secret:\n        raise HTTPException(status_code=403, detail='Invalid webhook secret')\n    if telegram_app is None:\n        raise HTTPException(status_code=503, detail='Telegram app is not ready')\n    data = await request.json()\n    update = Update.de_json(data, telegram_app.bot)\n    await telegram_app.process_update(update)\n    return {'ok': True}\n\n\nif __name__ == '__main__':\n    port = int(os.getenv('PORT', os.getenv('ADMIN_PORT', '8080')))\n    uvicorn.run(fastapi_app, host='0.0.0.0', port=port, reload=False)\n", encoding='utf-8')
(PROJECT / 'render.yaml.example').write_text('services:\n  - type: web\n    name: media-vault-webhook\n    runtime: python\n    buildCommand: pip install -r requirements.txt\n    startCommand: python render_webhook_start.py\n    envVars:\n      - key: PYTHON_VERSION\n        value: 3.13.5\n      - key: TELEGRAM_BOT_TOKEN\n        sync: false\n      - key: TELEGRAM_WEBHOOK_SECRET\n        sync: false\n      - key: PUBLIC_BASE_URL\n        value: https://YOUR-SERVICE-NAME.onrender.com\n      - key: ADMIN_TOKEN\n        sync: false\n      - key: TEMP_LINK_SECRET\n        sync: false\n      - key: TEMP_LINK_EXPIRE_HOURS\n        value: "6"\n      - key: MAX_FILE_MB\n        value: "45"\n      - key: DAILY_LIMIT\n        value: "10"\n      - key: AUTO_DELETE_HOURS\n        value: "6"\n      - key: AUTO_COMPRESS\n        value: "True"\n      - key: VIDEO_MAX_HEIGHT\n        value: "720"\n      - key: VIDEO_CRF\n        value: "28"\n      - key: AUDIO_BITRATE\n        value: "128k"\n      - key: MAX_CONCURRENT_DOWNLOADS\n        value: "1"\n      - key: DOWNLOAD_DIR\n        value: storage/downloads\n      - key: DATABASE_PATH\n        value: storage/media_vault.sqlite3\n', encoding='utf-8')

print('OK: MediaVault Bot upgraded to V1.7 Render Webhook.')
print('Created: render_webhook_start.py')
print('Created: render.yaml.example')
print('Updated: requirements.txt')
print('Added/checked .env: PUBLIC_BASE_URL, TELEGRAM_WEBHOOK_SECRET')
print('Render Start Command: python render_webhook_start.py')
