from __future__ import annotations

import asyncio
import re
import shutil
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Callable, Any

from app.config import settings
from app.utils import short_hash

ProgressCallback = Callable[[dict[str, Any]], None]

DIRECT_MEDIA_EXTENSIONS = {'.mp4', '.mp3', '.m4a', '.webm', '.wav', '.mov', '.aac', '.ogg'}

def is_direct_media_url(url: str) -> bool:
    parsed = urllib.parse.urlparse(url)
    path = parsed.path.lower()
    return any(path.endswith(ext) for ext in DIRECT_MEDIA_EXTENSIONS)

def _safe_filename_from_url(url: str, default: str = 'media_file') -> str:
    parsed = urllib.parse.urlparse(url)
    name = Path(urllib.parse.unquote(parsed.path)).name or default
    name = re.sub(r'[^A-Za-z0-9._-]+', '_', name).strip('._')
    return name or default

def _download_direct_sync(url: str, user_id: int, progress_callback: ProgressCallback | None = None) -> Path:
    job_id = short_hash(f'{user_id}:{url}:direct')
    job_dir = settings.download_dir / str(user_id) / job_id
    if job_dir.exists():
        shutil.rmtree(job_dir)
    job_dir.mkdir(parents=True, exist_ok=True)
    output_path = job_dir / _safe_filename_from_url(url)
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 MediaVaultBot/1.8', 'Accept': '*/*'})
    timeout = int(getattr(settings, 'direct_link_timeout', 120))
    max_bytes = int(getattr(settings, 'direct_link_max_mb', settings.max_file_mb)) * 1024 * 1024
    downloaded = 0
    with urllib.request.urlopen(req, timeout=timeout) as response:
        content_length = response.headers.get('Content-Length')
        total = int(content_length) if content_length and content_length.isdigit() else 0
        if total and total > max_bytes:
            raise RuntimeError(f'حجم لینک مستقیم بیشتر از حد مجاز است. حد فعلی: {round(max_bytes / 1024 / 1024)} MB')
        with output_path.open('wb') as f:
            while True:
                chunk = response.read(1024 * 256)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                if downloaded > max_bytes:
                    raise RuntimeError(f'حجم فایل از حد مجاز بیشتر شد. حد فعلی: {round(max_bytes / 1024 / 1024)} MB')
                if progress_callback:
                    percent = round(downloaded * 100 / total, 1) if total else None
                    progress_callback({'status': 'downloading', 'downloaded': downloaded, 'total': total, 'percent': percent, 'speed': None, 'eta': None})
    if not output_path.exists() or output_path.stat().st_size == 0:
        raise RuntimeError('فایل لینک مستقیم دانلود نشد یا خالی است.')
    return output_path

async def download_direct_media(url: str, user_id: int, progress_callback: ProgressCallback | None = None) -> Path:
    return await asyncio.to_thread(_download_direct_sync, url, user_id, progress_callback)
