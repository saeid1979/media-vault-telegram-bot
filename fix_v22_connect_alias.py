from pathlib import Path

p = Path("app/db.py")
s = p.read_text(encoding="utf-8")

if "def get_connection(" not in s:
    s += """

# V2.2 compatibility alias
def get_connection():
    return connect()
"""
    p.write_text(s, encoding="utf-8")
    print("OK: get_connection alias added to app/db.py")
else:
    print("get_connection already exists.")
