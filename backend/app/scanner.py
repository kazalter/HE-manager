import os
import json
import threading
import zipfile
import traceback
import hashlib
import cv2
import numpy as np
from PIL import Image
from sqlalchemy.orm import Session
from datetime import datetime
from . import models, database
from .dedup import normalize as dedup_normalize
from .dedup import worker as dedup_worker

# Supported extensions
VIDEO_EXTENSIONS = {'.mp4', '.mkv', '.avi', '.mov', '.wmv', '.webm', '.flv', '.ts', '.m4v'}
MANGA_EXTENSIONS = {'.zip', '.cbz'}
IMAGE_EXTENSIONS = {'.png', '.jpg', '.jpeg', '.webp', '.bmp', '.gif', '.avif'}
# Single-file audio (one Media row per file). ASMR works downloaded via
# /external/asmr/downloads use a folder-level Media row instead and live in
# Folder.scan_mode='asmr_work' so the scanner skips them — see
# ensure_external_audio_library() in main.py.
AUDIO_EXTENSIONS = {'.mp3', '.wav', '.flac', '.m4a', '.aac', '.ogg', '.opus'}

# Folders to skip (case-insensitive)
SKIP_FOLDERS = {'mask', 'result', 'inpainted', '.thumbnails', 'node_modules', '.git', '.vite'}

# Global lock for video thumbnail generation to limit concurrency to 1
THUMBNAIL_LOCK = threading.Lock()

def is_valid_frame(frame, max_width=512):
    """
    Checks if a frame is 'valid' for a thumbnail (not black/white, not pure color, not too blurry).
    """
    try:
        # Resize for efficiency
        h, w = frame.shape[:2]
        if w > max_width:
            scale = max_width / w
            frame = cv2.resize(frame, (max_width, int(h * scale)))
        
        # Convert to grayscale for brightness and variance
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        
        # 1. Brightness check (avoid black or white screens)
        avg_brightness = np.mean(gray)
        if avg_brightness < 20 or avg_brightness > 235:
            return False, avg_brightness, 0, 0
        
        # 2. Variance check (avoid pure colors or very low detail)
        variance = np.var(gray)
        if variance < 150:
            return False, avg_brightness, variance, 0
            
        # 3. Sharpness check (Laplacian variance)
        sharpness = cv2.Laplacian(gray, cv2.CV_64F).var()
        if sharpness < 15:
             return False, avg_brightness, variance, sharpness
             
        return True, avg_brightness, variance, sharpness
    except Exception:
        return False, 0, 0, 0

def get_video_thumbnail(video_path, thumb_path):
    """
    Generates a thumbnail for a video by finding the first 'valid' frame.
    Returns (success, time_ms, source).
    """
    sampling_ms = [0, 500, 1000, 1500, 2000, 3000, 5000, 8000]
    best_fallback_frame = None
    best_fallback_time = 0
    
    with THUMBNAIL_LOCK:
        try:
            cap = cv2.VideoCapture(video_path)
            if not cap.isOpened():
                return False, 0, "error"
                
            total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
            fps = cap.get(cv2.CAP_PROP_FPS)
            duration_ms = (total_frames / fps) * 1000 if fps > 0 else 0
            
            # Try sampling points
            for t_ms in sampling_ms:
                if duration_ms > 0 and t_ms > duration_ms:
                    break
                    
                cap.set(cv2.CAP_PROP_POS_MSEC, t_ms)
                success, frame = cap.read()
                if not success or frame is None:
                    continue
                    
                valid, brightness, variance, sharpness = is_valid_frame(frame)
                
                # Keep the first frame we read as initial fallback
                if best_fallback_frame is None:
                    best_fallback_frame = frame.copy()
                    best_fallback_time = t_ms
                
                if valid:
                    cv2.imwrite(thumb_path, frame)
                    cap.release()
                    return True, int(t_ms), "first_valid_frame"
            
            # If no valid frame found in 8s, try fallback at 10% duration
            if duration_ms > 0:
                fallback_ms = duration_ms * 0.1
                cap.set(cv2.CAP_PROP_POS_MSEC, fallback_ms)
                success, frame = cap.read()
                if success and frame is not None:
                    cv2.imwrite(thumb_path, frame)
                    cap.release()
                    return True, int(fallback_ms), "fallback_10_percent"
            
            # Ultimate fallback: use the first frame we encountered
            if best_fallback_frame is not None:
                cv2.imwrite(thumb_path, best_fallback_frame)
                cap.release()
                return True, int(best_fallback_time), "fallback_initial"
                
            cap.release()
        except Exception as e:
            print(f"Error generating video thumbnail: {e}")
    
    return False, 0, "failed"

def get_video_metadata(video_path):
    metadata = {"duration": None, "width": None, "height": None}
    try:
        cap = cv2.VideoCapture(video_path)
        fps = cap.get(cv2.CAP_PROP_FPS)
        total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        if fps and fps > 0 and total_frames > 0:
            metadata["duration"] = int(total_frames / fps)
        if width > 0:
            metadata["width"] = width
        if height > 0:
            metadata["height"] = height
        cap.release()
    except Exception as e:
        print(f"Error reading video metadata: {e}")
    return metadata

def get_image_metadata(image_path):
    try:
        with Image.open(image_path) as img:
            return {"width": img.width, "height": img.height}
    except Exception as e:
        print(f"Error reading image metadata: {e}")
    return {"width": None, "height": None}

def count_manga_pages(manga_path, extension):
    image_exts = {'.jpg', '.jpeg', '.png', '.webp', '.bmp'}
    try:
        if extension == ".dir":
            count = 0
            for _, _, files in os.walk(manga_path):
                count += sum(1 for file in files if any(file.lower().endswith(ext) for ext in image_exts))
            return count
        with zipfile.ZipFile(manga_path, 'r') as z:
            return sum(1 for f in z.namelist() if any(f.lower().endswith(ext) for ext in image_exts))
    except Exception as e:
        print(f"Error counting manga pages: {e}")
    return None

def generate_sprite_vtt(video_path, base_name, thumbnail_dir, interval=2):
    try:
        cap = cv2.VideoCapture(video_path)
        fps = cap.get(cv2.CAP_PROP_FPS)
        total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        if fps <= 0 or total_frames <= 0:
            cap.release()
            return False
            
        duration = total_frames / fps
        if duration < interval:
            cap.release()
            return False
            
        width = 160
        orig_w = cap.get(cv2.CAP_PROP_FRAME_WIDTH)
        orig_h = cap.get(cv2.CAP_PROP_FRAME_HEIGHT)
        if orig_w == 0 or orig_h == 0:
            cap.release()
            return False

        height = int(orig_h * (width / orig_w))
        cols = 10
        rows_per_sheet = 10
        sprites_per_sheet = cols * rows_per_sheet
        
        total_thumbnails = int(duration / interval)
        if total_thumbnails == 0:
            cap.release()
            return False
            
        vtt_content = ["WEBVTT\n"]
        sheet_index = 0
        current_sheet_img = Image.new('RGB', (cols * width, rows_per_sheet * height))
        
        def format_time(seconds):
            h = int(seconds // 3600)
            m = int((seconds % 3600) // 60)
            s = int(seconds % 60)
            ms = int((seconds - int(seconds)) * 1000)
            return f"{h:02d}:{m:02d}:{s:02d}.{ms:03d}"
            
        sheet_dirty = False
        
        for i in range(total_thumbnails):
            current_time = i * interval
            cap.set(cv2.CAP_PROP_POS_MSEC, current_time * 1000)
            success, frame = cap.read()
            if success:
                frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                img = Image.fromarray(frame)
                img = img.resize((width, height))
                
                local_i = i % sprites_per_sheet
                col = local_i % cols
                row = local_i // cols
                x = col * width
                y = row * height
                
                current_sheet_img.paste(img, (x, y))
                sheet_dirty = True
                
                start_time = format_time(current_time)
                end_time = format_time(min(current_time + interval, duration))
                
                vtt_content.append(f"\n{start_time} --> {end_time}")
                # 使用相对路径，使其自动相对于 VTT 文件的 URL 解析
                vtt_content.append(f"{base_name}_{sheet_index}.jpg#xywh={x},{y},{width},{height}")
                
                if local_i == sprites_per_sheet - 1:
                    sheet_name = f"{base_name}_{sheet_index}.jpg"
                    current_sheet_img.save(os.path.join(thumbnail_dir, sheet_name), "JPEG", quality=80)
                    sheet_index += 1
                    current_sheet_img = Image.new('RGB', (cols * width, rows_per_sheet * height))
                    sheet_dirty = False

        if sheet_dirty:
            sheet_name = f"{base_name}_{sheet_index}.jpg"
            current_sheet_img.save(os.path.join(thumbnail_dir, sheet_name), "JPEG", quality=80)
            
        vtt_name = f"{base_name}.vtt"
        with open(os.path.join(thumbnail_dir, vtt_name), "w", encoding="utf-8") as f:
            f.write("\n".join(vtt_content))
            
        cap.release()
        return True
    except Exception as e:
        print(f"Error generating sprite vtt: {e}")
        return False

def get_manga_thumbnail(manga_path, thumb_path):
    try:
        with zipfile.ZipFile(manga_path, 'r') as z:
            images = sorted([f for f in z.namelist() if any(f.lower().endswith(ext) for ext in IMAGE_EXTENSIONS)])
            if images:
                with z.open(images[0]) as f:
                    img = Image.open(f)
                    if img.mode in ('RGBA', 'P'):
                        img = img.convert('RGB')
                    img.thumbnail((400, 600))
                    img.save(thumb_path, "JPEG")
                    return True
    except Exception as e:
        print(f"Error generating manga thumbnail: {e}")
    return False

def get_image_thumbnail(image_path, thumb_path):
    try:
        img = Image.open(image_path)
        if img.mode in ('RGBA', 'P'):
            img = img.convert('RGB')
        img.thumbnail((400, 600))
        img.save(thumb_path, "JPEG")
        return True
    except Exception as e:
        print(f"Error generating image thumbnail: {e}")
    return False

def get_folder_thumbnail(folder_path, thumb_path):
    try:
        image_exts = {'.jpg', '.jpeg', '.png', '.webp', '.bmp'}
        files = sorted([f for f in os.listdir(folder_path) if any(f.lower().endswith(ext) for ext in image_exts)])
        if files:
            img_path = os.path.join(folder_path, files[0])
            img = Image.open(img_path)
            if img.mode in ('RGBA', 'P'):
                img = img.convert('RGB')
            img.thumbnail((400, 600))
            img.save(thumb_path, "JPEG")
            return True
    except Exception as e:
        print(f"Error generating folder thumbnail: {e}")
    return False


def should_skip_dir(path):
    parts = {part.lower() for part in path.split(os.sep)}
    return bool(parts & SKIP_FOLDERS)


def has_image_file(files):
    return any(os.path.splitext(file)[1].lower() in IMAGE_EXTENSIONS for file in files)


def has_audio_file_recursive(work_root):
    """True iff `work_root` contains at least one audio file at any depth.
    Used by audio_work scan mode to drop tracks.json-marked folders that
    somehow ended up empty (broken download)."""
    for _, _, files in os.walk(work_root):
        for file in files:
            if os.path.splitext(file)[1].lower() in AUDIO_EXTENSIONS:
                return True
    return False


def read_tracks_json(work_root):
    """Try to load the ASMR-downloader-style tracks.json sitting at the work
    root. Returns the parsed dict on success, None on missing / corrupt file.

    Shape (per asmr-one-style tooling):
      {
        "title": str,
        "url": str,                 # asmr.one work page
        "tracks": [
          {"index": int, "title": str, "rel": "■03/01.mp3", "duration": float},
          ...
        ]
      }
    """
    path = os.path.join(work_root, "tracks.json")
    if not os.path.exists(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as exc:  # noqa: BLE001 - any parse / IO failure is "no manifest"
        print(f"  ! Failed to parse tracks.json at {path}: {exc}")
        return None


def count_audio_tracks(work_root):
    """How many audio files inside this work root (any depth). Used as
    page_count for audio_work Media rows so the UI has something to display."""
    total = 0
    for _, _, files in os.walk(work_root):
        total += sum(1 for f in files if os.path.splitext(f)[1].lower() in AUDIO_EXTENSIONS)
    return total


def get_work_cover_path(work_root):
    """First image directly inside the work root, used as the audio_work
    thumbnail source. ASMR downloads sometimes ship a cover next to
    tracks.json; absent that, returns None and the UI shows a placeholder."""
    try:
        for entry in sorted(os.listdir(work_root)):
            ext = os.path.splitext(entry)[1].lower()
            if ext in IMAGE_EXTENSIONS:
                return os.path.join(work_root, entry)
    except OSError:
        pass
    return None


def make_work_thumbnail(cover_path, thumb_path):
    """Copy/resize an existing cover file into the thumbnail cache. Same
    400x600 target as get_folder_thumbnail() so cards line up visually."""
    try:
        img = Image.open(cover_path)
        if img.mode in ("RGBA", "P"):
            img = img.convert("RGB")
        img.thumbnail((400, 600))
        img.save(thumb_path, "JPEG")
        return True
    except Exception as exc:  # noqa: BLE001
        print(f"Error generating work thumbnail: {exc}")
        return False


def apply_local_dedup_precheck(
    media: "models.Media",
    library_title_index: set,
    pending_dedup_paths: list,
) -> None:
    """Compute the normalized title and decide whether this entry needs the dedup worker.

    Cheap: only an O(1) set lookup. The actual fingerprint + classification happens later
    in the background (see dedup.worker).
    """
    norm = dedup_normalize.normalize_title(media.title or "")
    media.normalized_title = norm
    if norm and norm in library_title_index:
        media.duplicate_status = "checking"
        pending_dedup_paths.append(media.absolute_path)
    else:
        # default is 'unique' from the column default; set explicitly so the in-memory
        # object matches what's about to be persisted.
        media.duplicate_status = "unique"
    if norm:
        library_title_index.add(norm)


def media_type_for_extension(scan_mode, ext):
    if scan_mode == "video" and ext in VIDEO_EXTENSIONS:
        return "video"
    if scan_mode == "image" and ext in IMAGE_EXTENSIONS:
        return "image"
    if scan_mode == "audio" and ext in AUDIO_EXTENSIONS:
        return "audio"
    if scan_mode == "auto":
        if ext in VIDEO_EXTENSIONS:
            return "video"
        if ext in MANGA_EXTENSIONS:
            return "manga"
        if ext in IMAGE_EXTENSIONS:
            return "image"
        if ext in AUDIO_EXTENSIONS:
            return "audio"
    return None


def scan_folder(folder_id: int):
    # Create a fresh DB session for the background task
    db = database.SessionLocal()
    try:
        folder = db.query(models.Folder).filter(models.Folder.id == folder_id).first()
        if not folder:
            print(f"Folder with id {folder_id} not found in DB.")
            return

        folder.status = "scanning"
        db.commit()

        thumbnail_dir = os.path.join(os.getcwd(), ".thumbnails")
        if not os.path.exists(thumbnail_dir):
            os.makedirs(thumbnail_dir)

        media_batch = []
        scanned_paths = set()
        BATCH_SIZE = 200
        processed_count = 0
        thumbnail_enabled = bool(folder.thumbnail_enabled)
        thumbnail_interval = max(1, int(folder.thumbnail_interval or 1))
        existing_media = {
            media.absolute_path: media
            for media in db.query(models.Media).filter(models.Media.folder_id == folder.id).all()
        }

        # Cross-folder dedup: keep an in-memory set of normalized titles across the whole
        # library so we can flag candidates without a DB hit per file. We only need to
        # know "does any existing entry share this normalized title" (the worker will pull
        # the actual rows when fingerprinting).
        library_title_index: set[str] = set()
        for row in (
            db.query(models.Media.normalized_title)
            .filter(models.Media.duplicate_status.notin_(["strong_duplicate", "checking"]))
            .all()
        ):
            value = (row[0] or "").strip()
            if value:
                library_title_index.add(value)
        pending_dedup_paths: list[str] = []
        
        print(
            f"--- Starting scan for folder: {folder.path} "
            f"[Mode: {folder.scan_mode}, Thumbnails: {thumbnail_enabled}, Interval: {thumbnail_interval}s] "
            f"at {datetime.now()} ---"
        )

        for root, dirs, files in os.walk(folder.path):
                dirs[:] = [name for name in dirs if name.lower() not in SKIP_FOLDERS]

                if should_skip_dir(root):
                    continue

                # --- MANGA FOLDER LOGIC ---
                if folder.scan_mode == "manga":
                    # A directory is a manga if it contains image files
                    if has_image_file(files):
                            scanned_paths.add(root)
                            existing = existing_media.get(root)
                            if not existing:
                                rel_path = os.path.relpath(root, folder.path)
                                title = os.path.basename(root) if rel_path != "." else (os.path.basename(os.path.normpath(folder.path)) or folder.path)
                                page_count = count_manga_pages(root, ".dir")
                                media = models.Media(
                                    folder_id=folder.id, title=title, relative_path=rel_path,
                                    absolute_path=root, media_type='manga', extension='.dir', file_size=0,
                                    page_count=page_count, is_missing=False
                                )
                                thumb_name = f"thumb_dir_{media.title}_{datetime.now().timestamp()}.jpg"
                                thumb_path = os.path.join(thumbnail_dir, thumb_name)
                                if get_folder_thumbnail(root, thumb_path):
                                    media.cover_path = thumb_name

                                apply_local_dedup_precheck(media, library_title_index, pending_dedup_paths)
                                media_batch.append(media)
                                existing_media[root] = media
                                processed_count += 1
                                print(f"  + Added Folder Manga: {media.title}")
                                
                                if len(media_batch) >= BATCH_SIZE:
                                    db.add_all(media_batch)
                                    db.commit()
                                    media_batch = []
                            else:
                                existing.is_missing = False
                                existing.missing_since = None
                                if existing.page_count is None:
                                    existing.page_count = count_manga_pages(root, ".dir")
                            dirs[:] = []
                    # Skip individual file processing for manga mode
                    continue

                # --- AUDIO WORK FOLDER LOGIC ---
                # Same shape as manga mode but for audio. A "work" is a
                # folder that contains audio files (any depth) — identified
                # by one of:
                #   - a tracks.json manifest (asmr-one-downloader style)
                #   - a source.txt breadcrumb (our own download pipeline)
                #   - audio files directly in this folder (flat album layout)
                # We refuse to recurse past the work root so a nested
                # "■03_本篇" folder full of mp3s does NOT itself become a
                # second work — the parent is the work, that's a sub-bucket.
                if folder.scan_mode == "audio_work":
                    has_marker = ("tracks.json" in files) or ("source.txt" in files)
                    has_audio_here = any(
                        os.path.splitext(f)[1].lower() in AUDIO_EXTENSIONS for f in files
                    )
                    is_work_root = (has_marker and has_audio_file_recursive(root)) or has_audio_here
                    if is_work_root:
                        scanned_paths.add(root)
                        existing = existing_media.get(root)
                        manifest = read_tracks_json(root)
                        track_count = (
                            len(manifest["tracks"])
                            if manifest and isinstance(manifest.get("tracks"), list)
                            else count_audio_tracks(root)
                        )
                        total_duration = None
                        if manifest and isinstance(manifest.get("tracks"), list):
                            durations = [
                                float(t.get("duration") or 0)
                                for t in manifest["tracks"]
                                if isinstance(t, dict)
                            ]
                            total_duration = int(sum(durations)) if durations else None

                        if not existing:
                            rel_path = os.path.relpath(root, folder.path)
                            # Manifest title beats folder name when available — the
                            # folder name is often noisy (RJ suffix, version
                            # markers), the manifest title is the clean one the
                            # site exposes.
                            title = (manifest or {}).get("title") or (
                                os.path.basename(root)
                                if rel_path != "."
                                else (os.path.basename(os.path.normpath(folder.path)) or folder.path)
                            )
                            source_url = (manifest or {}).get("url") or None
                            media = models.Media(
                                folder_id=folder.id,
                                title=title,
                                relative_path=rel_path,
                                absolute_path=root,
                                media_type='audio',
                                extension='.dir',
                                file_size=0,
                                page_count=track_count,
                                duration=total_duration,
                                source_url=source_url,
                                source_site='asmr' if source_url and 'asmr.one' in source_url else None,
                                is_missing=False,
                            )
                            cover_src = get_work_cover_path(root)
                            if cover_src:
                                thumb_name = f"thumb_audio_{hashlib.md5(root.encode()).hexdigest()[:12]}_{datetime.now().timestamp()}.jpg"
                                thumb_path = os.path.join(thumbnail_dir, thumb_name)
                                if make_work_thumbnail(cover_src, thumb_path):
                                    media.cover_path = thumb_name

                            apply_local_dedup_precheck(media, library_title_index, pending_dedup_paths)
                            media_batch.append(media)
                            existing_media[root] = media
                            processed_count += 1
                            print(f"  + Added Audio Work: {media.title}")

                            if len(media_batch) >= BATCH_SIZE:
                                db.add_all(media_batch)
                                db.commit()
                                media_batch = []
                        else:
                            existing.is_missing = False
                            existing.missing_since = None
                            # Refresh derived fields in case the user repacked the
                            # work folder (added tracks, fixed manifest, etc.).
                            if existing.page_count != track_count:
                                existing.page_count = track_count
                            if total_duration is not None and existing.duration != total_duration:
                                existing.duration = total_duration
                        dirs[:] = []
                    # Skip per-file processing — work folders are the unit here
                    continue

                for file in files:
                    try:
                        file_path = os.path.join(root, file)
                        ext = os.path.splitext(file)[1].lower()

                        target_type = media_type_for_extension(folder.scan_mode, ext)
                        if not target_type:
                            continue

                        scanned_paths.add(file_path)
                        existing = existing_media.get(file_path)
                        if existing:
                            existing.is_missing = False
                            existing.missing_since = None
                            try:
                                file_size = os.path.getsize(file_path)
                                if existing.file_size != file_size:
                                    existing.file_size = file_size
                            except OSError:
                                pass
                            continue

                        media = None
                        rel_path = os.path.relpath(file_path, folder.path)
                        file_size = os.path.getsize(file_path)

                        if target_type == 'video':
                            metadata = get_video_metadata(file_path)
                            media = models.Media(
                                folder_id=folder.id, title=file, relative_path=rel_path,
                                absolute_path=file_path, media_type='video', extension=ext, file_size=file_size,
                                duration=metadata["duration"], width=metadata["width"], height=metadata["height"],
                                is_missing=False
                            )
                            file_hash = hashlib.md5(file_path.encode()).hexdigest()[:12]
                            base_name = f"thumb_v_{file_hash}_{datetime.now().timestamp()}".replace(' ', '_')
                            thumb_name = f"{base_name}.jpg"
                            thumb_path = os.path.join(thumbnail_dir, thumb_name)
                            success, t_ms, source = get_video_thumbnail(file_path, thumb_path)
                            if success:
                                media.cover_path = thumb_name
                                media.cover_time_ms = t_ms
                                media.cover_source = source
                                if thumbnail_enabled:
                                    generate_sprite_vtt(
                                        file_path,
                                        base_name,
                                        thumbnail_dir,
                                        interval=thumbnail_interval,
                                    )

                        elif target_type == 'manga':
                            page_count = count_manga_pages(file_path, ext)
                            media = models.Media(
                                folder_id=folder.id, title=file, relative_path=rel_path,
                                absolute_path=file_path, media_type='manga', extension=ext, file_size=file_size,
                                page_count=page_count, is_missing=False
                            )
                            file_hash = hashlib.md5(file_path.encode()).hexdigest()[:12]
                            thumb_name = f"thumb_m_{file_hash}_{datetime.now().timestamp()}.jpg"
                            thumb_path = os.path.join(thumbnail_dir, thumb_name)
                            if get_manga_thumbnail(manga_path=file_path, thumb_path=thumb_path):
                                media.cover_path = thumb_name
                        
                        elif target_type == 'image':
                            metadata = get_image_metadata(file_path)
                            media = models.Media(
                                folder_id=folder.id, title=file, relative_path=rel_path,
                                absolute_path=file_path, media_type='image', extension=ext, file_size=file_size,
                                width=metadata["width"], height=metadata["height"], is_missing=False
                            )
                            file_hash = hashlib.md5(file_path.encode()).hexdigest()[:12]
                            thumb_name = f"thumb_i_{file_hash}_{datetime.now().timestamp()}.jpg"
                            thumb_path = os.path.join(thumbnail_dir, thumb_name)
                            if get_image_thumbnail(file_path, thumb_path):
                                media.cover_path = thumb_name

                        elif target_type == 'audio':
                            # Single-file audio: one row per file, no cover (reading
                            # embedded ID3 art needs mutagen and isn't in deps; the
                            # UI falls back to a placeholder which is good enough for
                            # casual listening). duration is left None — the HTML5
                            # <audio> element + Android ExoPlayer both probe it on
                            # load, so we don't need to call ffprobe at scan time.
                            media = models.Media(
                                folder_id=folder.id, title=file, relative_path=rel_path,
                                absolute_path=file_path, media_type='audio', extension=ext, file_size=file_size,
                                is_missing=False,
                            )

                        if media:
                            apply_local_dedup_precheck(media, library_title_index, pending_dedup_paths)
                            media_batch.append(media)
                            existing_media[file_path] = media
                            processed_count += 1
                            print(f"  + Added {media.media_type}: {file}")

                        if len(media_batch) >= BATCH_SIZE:
                            db.add_all(media_batch)
                            db.commit()
                            media_batch = []
                    
                    except Exception as file_error:
                        print(f"Error processing file {file}: {file_error}")
                        continue

        if media_batch:
            db.add_all(media_batch)
            db.commit()

        for item in existing_media.values():
            if item.absolute_path not in scanned_paths and not os.path.exists(item.absolute_path):
                if not item.is_missing:
                    item.is_missing = True
                    item.missing_since = datetime.now()

        folder.status = "idle"
        folder.last_scanned_at = datetime.now()
        db.commit()

        # Hand off any candidates to the background dedup worker. We persist Media first
        # (above commits) so each one has an id, then look them up by path.
        if pending_dedup_paths:
            checking_ids = [
                row[0]
                for row in db.query(models.Media.id)
                .filter(models.Media.absolute_path.in_(pending_dedup_paths))
                .filter(models.Media.duplicate_status == "checking")
                .all()
            ]
            if checking_ids:
                dedup_worker.enqueue(checking_ids)
                print(f"--- Queued {len(checking_ids)} item(s) for dedup analysis ---")
        print(f"--- Scan completed at {datetime.now()}. Total new items: {processed_count} ---")
    except Exception as e:
        print(f"Scan error: {e}")
        print(f"Full traceback: {traceback.format_exc()}")
        if folder:
            folder.status = "error"
            db.commit()
    finally:
        db.close()
