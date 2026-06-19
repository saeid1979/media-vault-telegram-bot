from __future__ import annotations

import sqlite3
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from app.config import settings


def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(settings.database_path)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with connect() as conn:
        conn.execute("""
        CREATE TABLE IF NOT EXISTS downloads (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            username TEXT,
            url TEXT NOT NULL,
            platform TEXT,
            title TEXT,
            mode TEXT,
            file_path TEXT,
            file_size INTEGER,
            status TEXT NOT NULL,
            error TEXT,
            created_at TEXT NOT NULL
        )
        """)
        conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_downloads_user_created
        ON downloads(user_id, created_at)
        """)
        conn.execute("""
        CREATE TABLE IF NOT EXISTS bot_users (
            user_id INTEGER PRIMARY KEY,
            username TEXT,
            first_name TEXT,
            last_name TEXT,
            role TEXT NOT NULL DEFAULT 'normal',
            is_blocked INTEGER NOT NULL DEFAULT 0,
            daily_limit INTEGER,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """)


def ensure_user(user_id: int, username: str | None = None, first_name: str | None = None, last_name: str | None = None) -> None:
    now = datetime.utcnow().isoformat(timespec="seconds")
    with connect() as conn:
        row = conn.execute("SELECT user_id FROM bot_users WHERE user_id = ?", (user_id,)).fetchone()
        if row:
            conn.execute(
                """
                UPDATE bot_users
                SET username = COALESCE(?, username),
                    first_name = COALESCE(?, first_name),
                    last_name = COALESCE(?, last_name),
                    updated_at = ?
                WHERE user_id = ?
                """,
                (username, first_name, last_name, now, user_id),
            )
        else:
            conn.execute(
                """
                INSERT INTO bot_users
                (user_id, username, first_name, last_name, role, is_blocked, daily_limit, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'normal', 0, NULL, ?, ?)
                """,
                (user_id, username, first_name, last_name, now, now),
            )


def get_user_control(user_id: int) -> dict[str, Any]:
    ensure_user(user_id)
    with connect() as conn:
        row = conn.execute(
            "SELECT user_id, username, first_name, last_name, role, is_blocked, daily_limit FROM bot_users WHERE user_id = ?",
            (user_id,),
        ).fetchone()
    return dict(row) if row else {"user_id": user_id, "role": "normal", "is_blocked": 0, "daily_limit": None}


def get_effective_daily_limit(user_id: int) -> int | None:
    control = get_user_control(user_id)
    role = control.get("role") or "normal"
    if int(control.get("is_blocked") or 0) == 1 or role == "blocked":
        return 0
    if role == "admin":
        return None
    if control.get("daily_limit") is not None:
        return int(control["daily_limit"])
    if role == "vip":
        return max(int(settings.daily_limit), 30)
    return int(settings.daily_limit)


def is_user_blocked(user_id: int) -> bool:
    c = get_user_control(user_id)
    return int(c.get("is_blocked") or 0) == 1 or c.get("role") == "blocked"


def update_user_control(user_id: int, role: str, is_blocked: bool, daily_limit: int | None) -> None:
    if role not in {"normal", "vip", "admin", "blocked"}:
        raise ValueError("Invalid role")
    ensure_user(user_id)
    if role == "blocked":
        is_blocked = True
    now = datetime.utcnow().isoformat(timespec="seconds")
    with connect() as conn:
        conn.execute(
            """
            UPDATE bot_users
            SET role = ?, is_blocked = ?, daily_limit = ?, updated_at = ?
            WHERE user_id = ?
            """,
            (role, 1 if is_blocked else 0, daily_limit, now, user_id),
        )


def list_users(limit: int = 100) -> list[sqlite3.Row]:
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT
                u.user_id,
                u.username,
                u.first_name,
                u.last_name,
                u.role,
                u.is_blocked,
                u.daily_limit,
                u.created_at,
                u.updated_at,
                COUNT(d.id) AS downloads_count,
                SUM(CASE WHEN d.status = 'done' THEN 1 ELSE 0 END) AS done_count,
                SUM(CASE WHEN d.status = 'error' THEN 1 ELSE 0 END) AS error_count
            FROM bot_users u
            LEFT JOIN downloads d ON d.user_id = u.user_id
            GROUP BY u.user_id
            ORDER BY downloads_count DESC, u.updated_at DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
    return list(rows)


def count_today(user_id: int) -> int:
    today = datetime.utcnow().date().isoformat()
    with connect() as conn:
        row = conn.execute(
            """
            SELECT COUNT(*) AS total
            FROM downloads
            WHERE user_id = ?
              AND created_at >= ?
              AND status IN ('done', 'processing')
            """,
            (user_id, today),
        ).fetchone()
    return int(row["total"])


def add_download(user_id: int, username: str | None, url: str, platform: str, title: str | None, mode: str, status: str) -> int:
    now = datetime.utcnow().isoformat(timespec="seconds")
    with connect() as conn:
        cur = conn.execute(
            """
            INSERT INTO downloads
            (user_id, username, url, platform, title, mode, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (user_id, username, url, platform, title, mode, status, now),
        )
        return int(cur.lastrowid)


def mark_done(download_id: int, file_path: Path, file_size: int) -> None:
    with connect() as conn:
        conn.execute("UPDATE downloads SET status = 'done', file_path = ?, file_size = ? WHERE id = ?", (str(file_path), file_size, download_id))


def mark_error(download_id: int, error: str) -> None:
    with connect() as conn:
        conn.execute("UPDATE downloads SET status = 'error', error = ? WHERE id = ?", (error[:1000], download_id))


def user_history(user_id: int, limit: int = 10) -> list[sqlite3.Row]:
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT title, platform, mode, status, file_size, created_at, error
            FROM downloads
            WHERE user_id = ?
            ORDER BY id DESC
            LIMIT ?
            """,
            (user_id, limit),
        ).fetchall()
    return list(rows)


def cleanup_old_files(hours: int) -> int:
    cutoff = datetime.utcnow() - timedelta(hours=hours)
    deleted = 0
    with connect() as conn:
        rows = conn.execute(
            "SELECT id, file_path FROM downloads WHERE file_path IS NOT NULL AND created_at < ?",
            (cutoff.isoformat(timespec="seconds"),),
        ).fetchall()
        for row in rows:
            path = Path(row["file_path"])
            try:
                if path.exists():
                    path.unlink()
                    deleted += 1
            except OSError:
                pass
        conn.execute("UPDATE downloads SET file_path = NULL WHERE created_at < ?", (cutoff.isoformat(timespec="seconds"),))
    return deleted
