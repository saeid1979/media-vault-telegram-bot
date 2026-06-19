from __future__ import annotations

import os
import sqlite3
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from fastapi import FastAPI, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

load_dotenv()
PROJECT_ROOT = Path(__file__).resolve().parents[1]
DATABASE_PATH = Path(os.getenv("DATABASE_PATH", "storage/media_vault.sqlite3"))
if not DATABASE_PATH.is_absolute():
    DATABASE_PATH = PROJECT_ROOT / DATABASE_PATH
DOWNLOAD_DIR = Path(os.getenv("DOWNLOAD_DIR", "storage/downloads"))
if not DOWNLOAD_DIR.is_absolute():
    DOWNLOAD_DIR = PROJECT_ROOT / DOWNLOAD_DIR
ADMIN_TOKEN = os.getenv("ADMIN_TOKEN", "change-this-long-secret")
AUTO_DELETE_HOURS = int(os.getenv("AUTO_DELETE_HOURS", "6"))
BASE_DIR = Path(__file__).resolve().parent
templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))
app = FastAPI(title="MediaVault Admin Panel", version="1.3.0")
app.mount("/static", StaticFiles(directory=str(BASE_DIR / "static")), name="static")

def require_admin(token: str | None) -> None:
    if not token or token != ADMIN_TOKEN:
        raise HTTPException(status_code=403, detail="Forbidden")

def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DATABASE_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def scalar(query: str) -> int:
    with connect() as conn:
        row = conn.execute(query).fetchone()
    return int(row[0] or 0)

def human_size(num_bytes: int | None) -> str:
    if not num_bytes:
        return "0 B"
    size = float(num_bytes)
    for unit in ["B", "KB", "MB", "GB"]:
        if size < 1024:
            return f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} TB"

def storage_stats() -> dict[str, Any]:
    files = [p for p in DOWNLOAD_DIR.rglob("*") if p.is_file()] if DOWNLOAD_DIR.exists() else []
    total_size = sum(p.stat().st_size for p in files)
    return {"file_count": len(files), "total_size_label": human_size(total_size)}

@app.get("/", response_class=HTMLResponse)
async def dashboard(request: Request, token: str | None = None) -> HTMLResponse:
    require_admin(token)
    from app import db
    db.init_db()
    with connect() as conn:
        recent = [dict(r) for r in conn.execute("""
            SELECT id, user_id, username, platform, title, mode, status, file_size, created_at
            FROM downloads ORDER BY id DESC LIMIT 25
        """).fetchall()]
    for item in recent:
        item["file_size_label"] = human_size(item.get("file_size"))
    stats = storage_stats()
    return templates.TemplateResponse("dashboard.html", {
        "request": request,
        "token": token,
        "cards": {
            "total": scalar("SELECT COUNT(*) FROM downloads"),
            "done": scalar("SELECT COUNT(*) FROM downloads WHERE status = 'done'"),
            "errors": scalar("SELECT COUNT(*) FROM downloads WHERE status = 'error'"),
            "users": scalar("SELECT COUNT(*) FROM bot_users"),
            "vip_users": scalar("SELECT COUNT(*) FROM bot_users WHERE role = 'vip'"),
            "blocked_users": scalar("SELECT COUNT(*) FROM bot_users WHERE is_blocked = 1 OR role = 'blocked'"),
            "today": scalar("SELECT COUNT(*) FROM downloads WHERE DATE(created_at) = DATE('now')"),
            "stored_size": stats["total_size_label"],
        },
        "recent": recent,
        "users_list": [dict(row) for row in db.list_users(limit=100)],
    })

@app.post("/users/update")
async def update_user(token: str | None = None, user_id: int = Form(...), role: str = Form(...), daily_limit: str = Form(""), is_blocked: str | None = Form(None)) -> RedirectResponse:
    require_admin(token)
    from app import db
    limit_value = int(daily_limit) if daily_limit.strip() else None
    db.update_user_control(user_id, role, is_blocked == "on", limit_value)
    return RedirectResponse(url=f"/?token={token}#users", status_code=303)

@app.post("/cleanup")
async def cleanup(token: str | None = None) -> RedirectResponse:
    require_admin(token)
    from app import db
    db.cleanup_old_files(AUTO_DELETE_HOURS)
    return RedirectResponse(url=f"/?token={token}", status_code=303)

@app.get('/download-temp')
async def download_temp(path: str, expires: int, sig: str):
    try:
        from app.temp_links import verify_temp_link
        file_path = verify_temp_link(path, expires, sig)
    except Exception as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    return FileResponse(path=str(file_path), filename=file_path.name, media_type='application/octet-stream')
