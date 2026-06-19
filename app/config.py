from dataclasses import dataclass
from pathlib import Path
import os

from dotenv import load_dotenv

load_dotenv()


@dataclass(frozen=True)
class Settings:
    telegram_bot_token: str = os.getenv("TELEGRAM_BOT_TOKEN", "")
    max_file_mb: int = int(os.getenv("MAX_FILE_MB", "45"))
    daily_limit: int = int(os.getenv("DAILY_LIMIT", "10"))
    auto_delete_hours: int = int(os.getenv("AUTO_DELETE_HOURS", "6"))
    download_dir: Path = Path(os.getenv("DOWNLOAD_DIR", "storage/downloads"))
    database_path: Path = Path(os.getenv("DATABASE_PATH", "storage/media_vault.sqlite3"))
    bot_language: str = os.getenv("BOT_LANGUAGE", "fa")
    ffmpeg_location: str = os.getenv("FFMPEG_LOCATION", "")
    auto_compress: bool = os.getenv("AUTO_COMPRESS", "True").lower() in {"1", "true", "yes", "on"}
    video_max_height: int = int(os.getenv("VIDEO_MAX_HEIGHT", "720"))
    video_crf: int = int(os.getenv("VIDEO_CRF", "28"))
    audio_bitrate: str = os.getenv("AUDIO_BITRATE", "128k")
    max_concurrent_downloads: int = int(os.getenv("MAX_CONCURRENT_DOWNLOADS", "2"))
    public_base_url: str = os.getenv("PUBLIC_BASE_URL", "http://127.0.0.1:8080")
    temp_link_secret: str = os.getenv("TEMP_LINK_SECRET", "change-this-temp-link-secret")
    temp_link_expire_hours: int = int(os.getenv("TEMP_LINK_EXPIRE_HOURS", "6"))

    @property
    def max_file_bytes(self) -> int:
        return self.max_file_mb * 1024 * 1024


settings = Settings()
settings.download_dir.mkdir(parents=True, exist_ok=True)
settings.database_path.parent.mkdir(parents=True, exist_ok=True)

