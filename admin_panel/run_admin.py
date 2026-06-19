from __future__ import annotations

import os
import sys
from pathlib import Path

import uvicorn
from dotenv import load_dotenv

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from admin_panel.web import app

load_dotenv(PROJECT_ROOT / ".env")

HOST = os.getenv("ADMIN_HOST", "127.0.0.1")
PORT = int(os.getenv("ADMIN_PORT", "8080"))

if __name__ == "__main__":
    uvicorn.run(
        app,
        host=HOST,
        port=PORT,
        reload=False,
    )
