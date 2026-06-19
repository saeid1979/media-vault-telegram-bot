from pathlib import Path

p = Path("admin_panel/web.py")
s = p.read_text(encoding="utf-8")

lines = s.splitlines()

# حذف همه from __future__ import annotations
lines = [line for line in lines if line.strip() != "from __future__ import annotations"]

# حذف خط‌های خالی اول فایل
while lines and not lines[0].strip():
    lines.pop(0)

# اضافه کردن future import در ابتدای فایل
new_text = "from __future__ import annotations\n" + "\n".join(lines) + "\n"

p.write_text(new_text, encoding="utf-8")
print("OK: fixed from __future__ import position in admin_panel/web.py")
