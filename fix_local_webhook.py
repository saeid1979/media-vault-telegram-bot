from pathlib import Path

p = Path("render_webhook_start.py")
s = p.read_text(encoding="utf-8")

old = '''    public_base_url = os.getenv('PUBLIC_BASE_URL', settings.public_base_url).rstrip('/')
    webhook_secret = os.getenv('TELEGRAM_WEBHOOK_SECRET', 'change-this-webhook-secret')
    webhook_url = f'{public_base_url}/telegram-webhook/{webhook_secret}'

    await telegram_app.bot.set_webhook(
        url=webhook_url,
        allowed_updates=Update.ALL_TYPES,
        drop_pending_updates=True,
    )

    print('MediaVault Bot V1.9 webhook is running.')
    print(f'Webhook URL: {webhook_url}')
'''

new = '''    public_base_url = os.getenv('PUBLIC_BASE_URL', settings.public_base_url).rstrip('/')
    webhook_secret = os.getenv('TELEGRAM_WEBHOOK_SECRET', 'change-this-webhook-secret')
    webhook_url = f'{public_base_url}/telegram-webhook/{webhook_secret}'

    if public_base_url.startswith("https://"):
        await telegram_app.bot.set_webhook(
            url=webhook_url,
            allowed_updates=Update.ALL_TYPES,
            drop_pending_updates=True,
        )
        print('MediaVault Bot V1.9 webhook is running.')
        print(f'Webhook URL: {webhook_url}')
    else:
        print('MediaVault Bot V1.9 local mode is running.')
        print('Webhook was NOT set because PUBLIC_BASE_URL is not HTTPS.')
        print(f'Local panel: {public_base_url}')
'''

if old not in s:
    raise SystemExit("Target block not found. Send me render_webhook_start.py content.")

s = s.replace(old, new)
p.write_text(s, encoding="utf-8")
print("OK: render_webhook_start.py patched for local mode.")
