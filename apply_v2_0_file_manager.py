from pathlib import Path

PROJECT = Path.cwd()
ADMIN_WEB = PROJECT / "admin_panel" / "web.py"
TEMPLATES = PROJECT / "admin_panel" / "templates"

if not ADMIN_WEB.exists():
    raise FileNotFoundError("admin_panel/web.py پیدا نشد. این فایل را در ریشه پروژه اجرا کن.")

TEMPLATES.mkdir(parents=True, exist_ok=True)

file_manager_code = '''
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
'''
(PROJECT / "admin_panel" / "file_manager.py").write_text(file_manager_code.lstrip(), encoding="utf-8")

files_html = '''
<!doctype html>
<html lang="fa" dir="rtl"><head><meta charset="utf-8"><title>MediaVault File Manager</title><meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body{background:#0b1220;color:#f9fafb;font-family:Tahoma,Arial,sans-serif}.wrap{max-width:1200px;margin:24px auto;padding:0 16px}.card{background:#111827;border-radius:18px;padding:18px;margin-bottom:18px;box-shadow:0 10px 30px rgba(0,0,0,.2)}.top{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;align-items:center}h1{font-size:24px;margin:0 0 8px}.sub{color:#cbd5e1}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-top:14px}.stat{background:#1f2937;border-radius:14px;padding:14px}.stat b{display:block;font-size:22px;margin-top:6px}table{width:100%;border-collapse:collapse}th,td{padding:10px;border-bottom:1px solid #374151;text-align:right}th{background:#1f2937}.path{direction:ltr;text-align:left;color:#cbd5e1;font-size:12px;word-break:break-all}.btn{border:0;border-radius:10px;padding:8px 12px;text-decoration:none;color:white;font-weight:700;cursor:pointer;display:inline-block}.blue{background:#2563eb}.green{background:#16a34a}.red{background:#dc2626}.yellow{background:#f59e0b;color:#111827}.actions{display:flex;gap:8px;flex-wrap:wrap}.input{background:#0b1220;color:white;border:1px solid #374151;border-radius:10px;padding:8px;width:90px}.msg{background:#064e3b}.empty{text-align:center;padding:28px;color:#cbd5e1}@media(max-width:800px){.grid{grid-template-columns:1fr}th:nth-child(3),td:nth-child(3),th:nth-child(5),td:nth-child(5){display:none}}
</style></head><body><div class="wrap">
<div class="card"><div class="top"><div><h1>مدیریت فایل‌ها - MediaVault V2.0</h1><div class="sub">نمایش، دانلود، حذف و پاک‌سازی فایل‌های ذخیره‌شده روی سرور</div></div><div class="actions"><a class="btn blue" href="/?token={{ token }}">داشبورد</a><a class="btn blue" href="/files?token={{ token }}">تازه‌سازی</a></div></div>{% if message %}<div class="card msg">{{ message }}</div>{% endif %}<div class="grid"><div class="stat">تعداد فایل‌ها <b>{{ summary.count }}</b></div><div class="stat">حجم کل <b>{{ summary.total_size_human }}</b></div><div class="stat">مسیر ذخیره <b style="font-size:13px;direction:ltr;text-align:left">{{ download_dir }}</b></div></div></div>
<div class="card"><h2>پاک‌سازی فایل‌های قدیمی</h2><form method="post" action="/files/cleanup" class="actions"><input type="hidden" name="token" value="{{ token }}"><label>حذف فایل‌های قدیمی‌تر از</label><input class="input" type="number" name="days" value="1" min="0" max="365"><label>روز</label><button class="btn yellow" type="submit" onclick="return confirm('فایل‌های قدیمی حذف شوند؟')">پاک‌سازی</button></form></div>
<div class="card"><h2>لیست فایل‌ها</h2>{% if files %}<table><thead><tr><th>نام فایل</th><th>حجم</th><th>تاریخ</th><th>نوع</th><th>مسیر</th><th>عملیات</th></tr></thead><tbody>{% for f in files %}<tr><td>{{ f.name }}</td><td>{{ f.size_human }}</td><td>{{ f.modified_at_text }}</td><td>{{ f.extension }}</td><td class="path">{{ f.rel_path }}</td><td><div class="actions"><a class="btn green" href="/files/download?token={{ token }}&path={{ f.rel_path_url }}">دانلود</a><form method="post" action="/files/delete"><input type="hidden" name="token" value="{{ token }}"><input type="hidden" name="path" value="{{ f.rel_path }}"><button class="btn red" type="submit" onclick="return confirm('این فایل حذف شود؟')">حذف</button></form></div></td></tr>{% endfor %}</tbody></table>{% else %}<div class="empty">هنوز فایلی ذخیره نشده است.</div>{% endif %}</div>
</div></body></html>
'''
(TEMPLATES / "files.html").write_text(files_html.lstrip(), encoding="utf-8")

web = ADMIN_WEB.read_text(encoding="utf-8")
if "FileResponse" not in web:
    if "from fastapi.responses import HTMLResponse" in web:
        web = web.replace("from fastapi.responses import HTMLResponse", "from fastapi.responses import HTMLResponse, FileResponse, RedirectResponse")
    else:
        web = "from fastapi.responses import FileResponse, RedirectResponse\n" + web
if "Form" not in web:
    if "from fastapi import" in web:
        web = web.replace("from fastapi import", "from fastapi import Form,")
    else:
        web = "from fastapi import Form\n" + web
if "import urllib.parse" not in web:
    web = "import urllib.parse\n" + web
if "from admin_panel.file_manager import" not in web:
    web += """
from admin_panel.file_manager import (
    list_managed_files, storage_summary, human_size as fm_human_size,
    resolve_file, delete_file, cleanup_old_files, safe_base_dir
)
"""
if "def _v2_admin_token_ok" not in web:
    web += """
def _v2_admin_token_ok(token: str | None) -> bool:
    import os
    expected = os.getenv("ADMIN_TOKEN", "change-this-long-secret")
    return bool(token) and token == expected

def _v2_forbidden():
    from fastapi import HTTPException
    raise HTTPException(status_code=403, detail="Forbidden")
"""
if '@app.get("/files")' not in web:
    web += """
@app.get("/files")
async def files_page(request: Request, token: str = "", message: str = ""):
    if not _v2_admin_token_ok(token):
        _v2_forbidden()
    items = []
    for f in list_managed_files():
        items.append({"name": f.name, "rel_path": f.rel_path, "rel_path_url": urllib.parse.quote(f.rel_path), "size_human": fm_human_size(f.size), "modified_at_text": f.modified_at.strftime("%Y-%m-%d %H:%M"), "extension": f.extension})
    return templates.TemplateResponse("files.html", {"request": request, "token": token, "files": items, "summary": storage_summary(), "download_dir": str(safe_base_dir()), "message": message})

@app.get("/files/download")
async def files_download(token: str = "", path: str = ""):
    if not _v2_admin_token_ok(token):
        _v2_forbidden()
    target = resolve_file(path)
    return FileResponse(str(target), filename=target.name, media_type="application/octet-stream")

@app.post("/files/delete")
async def files_delete(token: str = Form(""), path: str = Form("")):
    if not _v2_admin_token_ok(token):
        _v2_forbidden()
    deleted = delete_file(path)
    msg = urllib.parse.quote(f"فایل حذف شد: {deleted.name}")
    return RedirectResponse(url=f"/files?token={urllib.parse.quote(token)}&message={msg}", status_code=303)

@app.post("/files/cleanup")
async def files_cleanup(token: str = Form(""), days: int = Form(1)):
    if not _v2_admin_token_ok(token):
        _v2_forbidden()
    result = cleanup_old_files(days=days)
    msg = urllib.parse.quote(f"پاک‌سازی انجام شد: {result['deleted']} فایل، {result['deleted_size_human']}")
    return RedirectResponse(url=f"/files?token={urllib.parse.quote(token)}&message={msg}", status_code=303)
"""
ADMIN_WEB.write_text(web, encoding="utf-8")

rws = PROJECT / "render_webhook_start.py"
if rws.exists():
    s = rws.read_text(encoding="utf-8")
    s = s.replace('"version": "1.9"', '"version": "2.0"')
    s = s.replace("'version': '1.9'", "'version': '2.0'")
    s = s.replace("MediaVault Bot V1.9", "MediaVault Bot V2.0")
    rws.write_text(s, encoding="utf-8")

print("OK: MediaVault Bot upgraded to V2.0 File Manager Admin Panel.")
print("Open: http://127.0.0.1:8080/files?token=YOUR_ADMIN_TOKEN")
