# HE Manager — backend-only API image (the Android app is the sole client, so
# the Vue frontend is intentionally not built here). Target arch: x86_64 (N100
# mini-host). Video thumbnails use cv2/OpenCV, not an external ffmpeg binary,
# so only OpenCV's couple of runtime shared libs are needed.
FROM python:3.12-slim-bookworm

RUN apt-get update && apt-get install -y --no-install-recommends \
        libglib2.0-0 \
        libgomp1 \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv

# Install deps first so the layer caches across code changes.
COPY backend/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

# App package only — DB and media live on mounted volumes, never in the image.
COPY backend/app ./app

# Pre-create the mount targets so first boot works even before a bind exists.
# The app derives these from cwd=/srv: .thumbnails -> /srv/.thumbnails, and
# the external cover cache as abspath(cwd/../covers) -> /covers.
RUN mkdir -p /data /srv/.thumbnails /covers /srv/external_downloads

ENV HE_DATABASE_URL=sqlite:////data/library.db \
    PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8 \
    PYTHONUTF8=1

EXPOSE 8010

# --no-access-log mirrors he.ps1: keeps the ?token= query string out of logs.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8010", "--no-access-log"]
