from __future__ import annotations

import os
from contextlib import asynccontextmanager

import uvicorn
from dotenv import load_dotenv
from fastapi import Request, HTTPException
from telegram import Update
from telegram.ext import (
    Application,
    CallbackQueryHandler,
    CommandHandler,
    MessageHandler,
    filters,
)

load_dotenv()

from admin_panel.web import app as fastapi_app
from app.config import settings
from app import db
from app.upload_handler import handle_media_upload
from app.bot import (
    start,
    help_cmd,
    history_cmd,
    limits_cmd,
    handle_callback,
    handle_text,
    cleanup_job,
)


telegram_app: Application | None = None


def build_telegram_application() -> Application:
    if not settings.telegram_bot_token or settings.telegram_bot_token == 'PUT_YOUR_BOT_TOKEN_HERE':
        raise RuntimeError('TELEGRAM_BOT_TOKEN در Environment Variables یا .env تنظیم نشده است.')

    app = Application.builder().token(settings.telegram_bot_token).build()
    app.add_handler(CommandHandler('start', start))
    app.add_handler(CommandHandler('help', help_cmd))
    app.add_handler(CommandHandler('history', history_cmd))
    app.add_handler(CommandHandler('limits', limits_cmd))
    app.add_handler(CallbackQueryHandler(handle_callback))
    app.add_handler(MessageHandler((filters.VIDEO | filters.AUDIO | filters.Document.ALL), handle_media_upload))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_text))
    return app


@asynccontextmanager
async def lifespan(app):
    global telegram_app
    db.init_db()
    telegram_app = build_telegram_application()
    await telegram_app.initialize()
    await telegram_app.start()

    if telegram_app.job_queue:
        telegram_app.job_queue.run_repeating(cleanup_job, interval=3600, first=30)

    public_base_url = os.getenv('PUBLIC_BASE_URL', settings.public_base_url).rstrip('/')
    webhook_secret = os.getenv('TELEGRAM_WEBHOOK_SECRET', 'change-this-webhook-secret')
    webhook_url = f'{public_base_url}/telegram-webhook/{webhook_secret}'

    await telegram_app.bot.set_webhook(
        url=webhook_url,
        allowed_updates=Update.ALL_TYPES,
        drop_pending_updates=True,
    )

    print('MediaVault Bot V1.9 webhook is running.')
    print(f'Webhook URL: {webhook_url}')

    try:
        yield
    finally:
        if telegram_app:
            try:
                await telegram_app.bot.delete_webhook(drop_pending_updates=False)
            except Exception as exc:
                print(f'delete_webhook warning: {exc}')
            await telegram_app.stop()
            await telegram_app.shutdown()


fastapi_app.router.lifespan_context = lifespan


@fastapi_app.get('/healthz')
async def healthz():
    return {'status': 'ok', 'mode': 'webhook', 'version': '1.7'}


@fastapi_app.post('/telegram-webhook/{secret}')
async def telegram_webhook(secret: str, request: Request):
    webhook_secret = os.getenv('TELEGRAM_WEBHOOK_SECRET', 'change-this-webhook-secret')
    if secret != webhook_secret:
        raise HTTPException(status_code=403, detail='Invalid webhook secret')
    if telegram_app is None:
        raise HTTPException(status_code=503, detail='Telegram app is not ready')
    data = await request.json()
    update = Update.de_json(data, telegram_app.bot)
    await telegram_app.process_update(update)
    return {'ok': True}


if __name__ == '__main__':
    port = int(os.getenv('PORT', os.getenv('ADMIN_PORT', '8080')))
    uvicorn.run(fastapi_app, host='0.0.0.0', port=port, reload=False)
