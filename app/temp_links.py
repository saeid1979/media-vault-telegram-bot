from __future__ import annotations

import hashlib
import hmac
import time
from pathlib import Path
from urllib.parse import quote

from app.config import settings


def _sign(payload: str) -> str:
    secret = settings.temp_link_secret.encode('utf-8')
    return hmac.new(secret, payload.encode('utf-8'), hashlib.sha256).hexdigest()


def create_temp_link(file_path: Path) -> str:
    expire_at = int(time.time() + int(settings.temp_link_expire_hours) * 3600)
    path_text = str(file_path.resolve())
    payload = f'{expire_at}:{path_text}'
    sig = _sign(payload)
    base = settings.public_base_url.rstrip('/')
    return f'{base}/download-temp?expires={expire_at}&path={quote(path_text)}&sig={sig}'


def verify_temp_link(path_text: str, expires: int, sig: str) -> Path:
    if int(expires) < int(time.time()):
        raise ValueError('لینک منقضی شده است.')
    payload = f'{int(expires)}:{path_text}'
    expected = _sign(payload)
    if not hmac.compare_digest(expected, sig):
        raise ValueError('امضای لینک معتبر نیست.')
    file_path = Path(path_text).resolve()
    download_root = settings.download_dir.resolve()
    if download_root not in file_path.parents and file_path != download_root:
        raise ValueError('مسیر فایل مجاز نیست.')
    if not file_path.exists() or not file_path.is_file():
        raise FileNotFoundError('فایل وجود ندارد.')
    return file_path
