from pathlib import Path

p = Path("app/db.py")
s = p.read_text(encoding="utf-8")

# اگر get_connection وجود ندارد، آن را به تابع اتصال موجود وصل می‌کنیم
if "def get_connection(" not in s:
    if "def get_db(" in s:
        s += """

# V2.2 compatibility alias
def get_connection():
    return get_db()
"""
    elif "def get_db_connection(" in s:
        s += """

# V2.2 compatibility alias
def get_connection():
    return get_db_connection()
"""
    elif "def connect_db(" in s:
        s += """

# V2.2 compatibility alias
def get_connection():
    return connect_db()
"""
    else:
        raise SystemExit("تابع اتصال دیتابیس پیدا نشد. خروجی Select-String را بفرست.")

p.write_text(s, encoding="utf-8")
print("OK: get_connection compatibility alias added.")
