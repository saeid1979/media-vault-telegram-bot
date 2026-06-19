from __future__ import annotations

import asyncio
import shutil
import subprocess
from pathlib import Path
from typing import Any, Callable

import yt_dlp

from app.config import settings
from app.utils import short_hash, find_largest_media_file


class DownloadError(Exception):
    pass


def make_user_friendly_download_error(error_text: str) -> str:
    lower = error_text.lower()
    if 'sign in to confirm' in lower or 'not a bot' in lower:
        return (
            'YouTube این درخواست را به‌عنوان ربات تشخیص داده و اجازه دانلود نمی‌دهد.\n\n'
            'این مشکل معمولاً روی سرورهای ابری مثل Render اتفاق می‌افتد.\n'
            'این ربات برای امنیت و رعایت قوانین، از ورود با حساب کاربری یا cookie استفاده نمی‌کند.\n\n'
            'راه‌حل: یک لینک عمومی دیگر یا لینک مستقیم فایل ویدئو/صدا مثل mp4 یا mp3 ارسال کن.'
        )
    if 'unsupported url' in lower:
        return (
            'این لینک در نسخه فعلی پشتیبانی نمی‌شود.\n\n'
            'لینک‌های پست یا صفحات نیازمند ورود معمولاً قابل دانلود نیستند.\n'
            'لطفاً لینک مستقیم فایل عمومی مثل mp4/mp3/m4a/webm ارسال کن.'
        )
    if 'linkedin' in lower:
        return (
            'LinkedIn در این نسخه فقط برای لینک مستقیم فایل ویدئو/صدا پشتیبانی می‌شود.\n\n'
            'بیشتر ویدئوهای LinkedIn پشت login/session هستند و بدون ورود قابل دریافت نیستند.'
        )
    if 'private video' in lower or 'login' in lower or 'requires authentication' in lower:
        return (
            'این محتوا عمومی نیست یا نیاز به ورود دارد.\n\n'
            'ربات فقط محتوای عمومی یا لینک مستقیم فایل‌هایی را می‌پذیرد که اجازه دانلودشان را داری.'
        )
    return error_text


ProgressCallback = Callable[[dict[str, Any]], None]


def _base_ydl_options() -> dict[str, Any]:
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "ignoreerrors": False,
        "geo_bypass": False,
        "nocheckcertificate": False,
        "restrictfilenames": True,
        "windowsfilenames": True,
        "overwrites": True,
        "max_filesize": settings.max_file_bytes * 3,
    }

    if getattr(settings, "ffmpeg_location", ""):
        opts["ffmpeg_location"] = settings.ffmpeg_location

    return opts


def _ffmpeg_exe() -> str:
    ffmpeg_location = getattr(settings, "ffmpeg_location", "")
    if ffmpeg_location:
        candidate = Path(ffmpeg_location) / "ffmpeg.exe"
        if candidate.exists():
            return str(candidate)
        candidate2 = Path(ffmpeg_location) / "ffmpeg"
        if candidate2.exists():
            return str(candidate2)
    return "ffmpeg"


def _extract_info_sync(url: str) -> dict[str, Any]:
    opts = _base_ydl_options()
    opts.update({"skip_download": True})

    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)

    if not info:
        raise DownloadError("اطلاعات فایل دریافت نشد.")

    return {
        "title": info.get("title") or "Untitled",
        "duration": info.get("duration"),
        "uploader": info.get("uploader") or info.get("channel"),
        "webpage_url": info.get("webpage_url") or url,
        "thumbnail": info.get("thumbnail"),
        "filesize_approx": info.get("filesize_approx") or info.get("filesize"),
    }


async def extract_info(url: str) -> dict[str, Any]:
    return await asyncio.to_thread(_extract_info_sync, url)


def _format_selector(mode: str) -> str:
    if mode == "video_low":
        return "worst[ext=mp4]/worst"
    if mode == "video_medium":
        return "best[height<=720][ext=mp4]/best[height<=720]/best[ext=mp4]/best"
    if mode == "audio":
        return "bestaudio/best"
    raise DownloadError("فرمت انتخاب‌شده معتبر نیست.")


def _progress_hook_factory(progress_callback: ProgressCallback | None) -> ProgressCallback | None:
    if progress_callback is None:
        return None

    def hook(data: dict[str, Any]) -> None:
        status = data.get("status")

        if status == "downloading":
            downloaded = data.get("downloaded_bytes") or 0
            total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
            percent = round(downloaded * 100 / total, 1) if total else None

            progress_callback({
                "status": "downloading",
                "downloaded": downloaded,
                "total": total,
                "percent": percent,
                "speed": data.get("speed"),
                "eta": data.get("eta"),
            })

        elif status == "finished":
            progress_callback({
                "status": "processing",
                "message": "دانلود تمام شد. در حال تبدیل/آماده‌سازی فایل…",
            })

        elif status == "error":
            progress_callback({
                "status": "error",
                "message": "خطا در دانلود فایل.",
            })

    return hook


def _notify(progress_callback: ProgressCallback | None, data: dict[str, Any]) -> None:
    if progress_callback:
        progress_callback(data)


def _compress_video(input_path: Path, progress_callback: ProgressCallback | None = None) -> Path:
    output_path = input_path.with_name(input_path.stem + "_compressed.mp4")

    _notify(progress_callback, {
        "status": "processing",
        "message": "فایل بزرگ است. در حال فشرده‌سازی ویدئو…",
    })

    ffmpeg = _ffmpeg_exe()
    height = int(getattr(settings, "video_max_height", 720))
    crf = int(getattr(settings, "video_crf", 28))
    audio_bitrate = getattr(settings, "audio_bitrate", "128k")

    cmd = [
        ffmpeg,
        "-y",
        "-i", str(input_path),
        "-vf", f"scale=-2:min({height}\\,ih)",
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-crf", str(crf),
        "-c:a", "aac",
        "-b:a", audio_bitrate,
        "-movflags", "+faststart",
        str(output_path),
    ]

    result = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="ignore",
    )

    if result.returncode != 0 or not output_path.exists():
        raise DownloadError("فشرده‌سازی ویدئو با FFmpeg انجام نشد.")

    if output_path.stat().st_size < input_path.stat().st_size:
        try:
            input_path.unlink()
        except OSError:
            pass
        return output_path

    try:
        output_path.unlink()
    except OSError:
        pass

    return input_path


def _compress_audio(input_path: Path, progress_callback: ProgressCallback | None = None) -> Path:
    output_path = input_path.with_name(input_path.stem + "_compressed.mp3")

    _notify(progress_callback, {
        "status": "processing",
        "message": "فایل بزرگ است. در حال فشرده‌سازی صدا…",
    })

    ffmpeg = _ffmpeg_exe()
    audio_bitrate = getattr(settings, "audio_bitrate", "128k")

    cmd = [
        ffmpeg,
        "-y",
        "-i", str(input_path),
        "-vn",
        "-c:a", "libmp3lame",
        "-b:a", audio_bitrate,
        str(output_path),
    ]

    result = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="ignore",
    )

    if result.returncode != 0 or not output_path.exists():
        raise DownloadError("فشرده‌سازی صدا با FFmpeg انجام نشد.")

    if output_path.stat().st_size < input_path.stat().st_size:
        try:
            input_path.unlink()
        except OSError:
            pass
        return output_path

    try:
        output_path.unlink()
    except OSError:
        pass

    return input_path


def _maybe_compress(file_path: Path, mode: str, progress_callback: ProgressCallback | None = None) -> Path:
    auto_compress = bool(getattr(settings, "auto_compress", True))
    if not auto_compress:
        return file_path

    if file_path.stat().st_size <= settings.max_file_bytes:
        return file_path

    if mode == "audio":
        return _compress_audio(file_path, progress_callback)

    return _compress_video(file_path, progress_callback)


def _download_sync(
    url: str,
    mode: str,
    user_id: int,
    progress_callback: ProgressCallback | None = None,
) -> Path:
    job_id = short_hash(f"{user_id}:{url}:{mode}")
    job_dir = settings.download_dir / str(user_id) / job_id

    if job_dir.exists():
        shutil.rmtree(job_dir)
    job_dir.mkdir(parents=True, exist_ok=True)

    opts = _base_ydl_options()
    opts.update({
        "format": _format_selector(mode),
        "outtmpl": str(job_dir / "%(title).80s-%(id)s.%(ext)s"),
    })

    hook = _progress_hook_factory(progress_callback)
    if hook:
        opts["progress_hooks"] = [hook]

    if mode == "audio":
        opts.update({
            "postprocessors": [
                {
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": "mp3",
                    "preferredquality": "128",
                }
            ],
        })
    else:
        opts.update({"merge_output_format": "mp4"})

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.download([url])
    except yt_dlp.utils.DownloadError as exc:
        raise DownloadError(make_user_friendly_download_error(str(exc))) from exc

    file_path = find_largest_media_file(job_dir)
    if not file_path:
        raise DownloadError("فایل خروجی ساخته نشد.")

    file_path = _maybe_compress(file_path, mode, progress_callback)

    size = file_path.stat().st_size
    if size > settings.max_file_bytes:
        raise DownloadError(
            f"حجم فایل بعد از فشرده‌سازی هنوز بیشتر از حد مجاز است. "
            f"حجم فعلی: {round(size / 1024 / 1024, 1)} MB | حد فعلی: {settings.max_file_mb} MB"
        )

    return file_path


async def download_media(
    url: str,
    mode: str,
    user_id: int,
    progress_callback: ProgressCallback | None = None,
) -> Path:
    return await asyncio.to_thread(_download_sync, url, mode, user_id, progress_callback)
