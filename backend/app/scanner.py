import os
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

# Supported extensions
VIDEO_EXTENSIONS = {'.mp4', '.mkv', '.avi', '.mov', '.wmv', '.webm', '.flv', '.ts', '.m4v'}
MANGA_EXTENSIONS = {'.zip', '.cbz'}
IMAGE_EXTENSIONS = {'.png', '.jpg', '.jpeg', '.webp', '.bmp', '.gif', '.avif'}

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


def media_type_for_extension(scan_mode, ext):
    if scan_mode == "video" and ext in VIDEO_EXTENSIONS:
        return "video"
    if scan_mode == "image" and ext in IMAGE_EXTENSIONS:
        return "image"
    if scan_mode == "auto":
        if ext in VIDEO_EXTENSIONS:
            return "video"
        if ext in MANGA_EXTENSIONS:
            return "manga"
        if ext in IMAGE_EXTENSIONS:
            return "image"
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
                                if existing.page_count is None:
                                    existing.page_count = count_manga_pages(root, ".dir")
                            dirs[:] = []
                    # Skip individual file processing for manga mode
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

                        if media:
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
                item.is_missing = True

        folder.status = "idle"
        folder.last_scanned_at = datetime.now()
        db.commit()
        print(f"--- Scan completed at {datetime.now()}. Total new items: {processed_count} ---")
    except Exception as e:
        print(f"Scan error: {e}")
        print(f"Full traceback: {traceback.format_exc()}")
        if folder:
            folder.status = "error"
            db.commit()
    finally:
        db.close()
