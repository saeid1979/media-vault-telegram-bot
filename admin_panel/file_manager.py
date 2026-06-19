from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from app.config import settings

MEDIA_EXTENSIONS = {".mp4", ".mp3", ".m4a", ".webm", ".wav", ".mov", ".aac", ".ogg", ".mkv", ".avi", ".flv", ".mpeg", ".mpg", ".3gp"}

@dataclass
class ManagedFile:
    name: str
    rel_path: str
    abs_path: Path
    size: int
    modified_at: datetime
    extension: str

def safe_base_dir() -> Path:
    base = Path(settings.download_dir).resolve()
    base.mkdir(parents=True, exist_ok=True)
    return base

def human_size(size: int | None) -> str:
    if not size:
        return "0 B"
    units = ["B", "KB", "MB", "GB", "TB"]
    value = float(size)
    for unit in units:
        if value < 1024 or unit == units[-1]:
            return f"{int(value)} {unit}" if unit == "B" else f"{value:.2f} {unit}"
        value /= 1024
    return f"{size} B"

def list_managed_files(limit: int = 500) -> list[ManagedFile]:
    base = safe_base_dir()
    files: list[ManagedFile] = []
    for p in base.rglob("*"):
        if not p.is_file() or p.name.startswith("."):
            continue
        ext = p.suffix.lower()
        if ext and ext not in MEDIA_EXTENSIONS:
            continue
        try:
            st = p.stat()
            files.append(ManagedFile(
                name=p.name,
                rel_path=p.relative_to(base).as_posix(),
                abs_path=p,
                size=st.st_size,
                modified_at=datetime.fromtimestamp(st.st_mtime),
                extension=ext.replace(".", "").upper() if ext else "FILE",
            ))
        except Exception:
            continue
    files.sort(key=lambda x: x.modified_at, reverse=True)
    return files[:limit]

def storage_summary() -> dict:
    files = list_managed_files(limit=100000)
    total = sum(f.size for f in files)
    return {"count": len(files), "total_size": total, "total_size_human": human_size(total)}

def resolve_file(rel_path: str) -> Path:
    base = safe_base_dir()
    target = (base / rel_path).resolve()
    if not str(target).startswith(str(base)):
        raise ValueError("Invalid file path")
    if not target.exists() or not target.is_file():
        raise FileNotFoundError("File not found")
    return target

def delete_file(rel_path: str) -> Path:
    target = resolve_file(rel_path)
    target.unlink(missing_ok=True)
    base = safe_base_dir()
    parent = target.parent
    while parent != base and str(parent).startswith(str(base)):
        try:
            parent.rmdir()
        except OSError:
            break
        parent = parent.parent
    return target

def cleanup_old_files(days: int = 1) -> dict:
    base = safe_base_dir()
    cutoff = datetime.now() - timedelta(days=max(0, int(days)))
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
    return {"deleted": deleted, "deleted_size": deleted_size, "deleted_size_human": human_size(deleted_size), "days": days}
