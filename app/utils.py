from __future__ import annotations

import hashlib
import re
from datetime import timedelta
from pathlib import Path

URL_RE = re.compile(r"https?://[^\s]+", re.IGNORECASE)


def extract_first_url(text: str) -> str | None:
    match = URL_RE.search(text or "")
    if not match:
        return None
    return match.group(0).strip()


def short_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def human_size(num_bytes: int | None) -> str:
    if not num_bytes:
        return "نامشخص"

    size = float(num_bytes)
    for unit in ["B", "KB", "MB", "GB"]:
        if size < 1024:
            return f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} TB"


def human_duration(seconds: int | None) -> str:
    if not seconds:
        return "نامشخص"

    td = timedelta(seconds=int(seconds))
    text = str(td)
    if text.startswith("0:"):
        text = text[2:]
    return text


def find_largest_media_file(folder: Path) -> Path | None:
    files = [p for p in folder.rglob("*") if p.is_file()]
    if not files:
        return None
    return max(files, key=lambda p: p.stat().st_size)
