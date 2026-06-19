from pathlib import Path

p = Path("app/db.py")
s = p.read_text(encoding="utf-8")

# اصلاح INSERT در ensure_user
old1 = """INSERT INTO bot_users (user_id, username, first_name, last_name, role, is_blocked, daily_limit)
                VALUES (?, ?, ?, ?, 'normal', 0, NULL)"""

new1 = """INSERT INTO bot_users (user_id, username, first_name, last_name, role, is_blocked, daily_limit, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'normal', 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"""

if old1 in s:
    s = s.replace(old1, new1)

# اصلاح INSERT در update_user_control
old2 = """INSERT INTO bot_users (user_id, role, is_blocked, daily_limit)
            VALUES (?, ?, ?, ?)"""

new2 = """INSERT INTO bot_users (user_id, role, is_blocked, daily_limit, created_at, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"""

if old2 in s:
    s = s.replace(old2, new2)

p.write_text(s, encoding="utf-8")
print("OK: fixed bot_users created_at/updated_at insert problem.")
