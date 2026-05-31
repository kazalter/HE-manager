# HE Manager

HE Manager is a personal media library manager for videos, manga/images, and ASMR audio.
It includes a FastAPI backend, a Vue 3 web frontend, and a native Android client for LAN use.

This project is built for a self-hosted personal library. Do not expose it directly to the public internet without adding stricter deployment security.

## Features

- Local media library scanning for video, manga/image folders, and audio works
- Web UI for browsing, tagging, search, stats, deduplication, and media playback
- Android app for browsing the library and playing video/audio from the backend
- External favorite sync/download workflows for WNACG, ASMR, and X/Twitter archive imports
- Optional DeepSeek-powered manga recommendation flow
- SQLite-backed local data store with automatic launch-time backups

## Repository Layout

```text
.
├── backend/       FastAPI app, SQLite models, scanners, importers, tests
├── frontend/      Vue 3 + TypeScript + Vite web app
├── android-app/   Native Android client
├── scripts/       Helper scripts
├── he.ps1         Full-stack local launcher
└── he-server.ps1  Backend-only LAN launcher
```

## Quick Start

This repository is developed on Windows. PowerShell 7 (`pwsh`) is recommended.

### 1. Install frontend dependencies

```powershell
cd frontend
npm install
cd ..
```

### 2. Install backend dependencies

Use a project-local Python environment. The backend currently uses FastAPI, SQLAlchemy, Pillow, OpenCV, NumPy, and Uvicorn.

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install fastapi uvicorn sqlalchemy pydantic pillow opencv-python numpy pytest
cd ..
```

### 3. Start the web app

```powershell
.\he.ps1
```

The launcher starts:

- Backend API: `http://localhost:8010`
- Frontend: `http://localhost:5173`

It also backs up `backend/app/library.db` into `backend/backups/` before launch.

### Backend-only LAN mode

Use this when testing the Android app or a phone browser on the same network:

```powershell
.\he-server.ps1
```

The backend listens on `0.0.0.0:8010`, so only run it on a trusted LAN.

## Android

Preview/debug build:

```powershell
.\android_preview.ps1
```

Release-like local performance build:

```powershell
.\android_release.ps1
```

You can also build directly:

```powershell
.\android-app\gradlew.bat -p ".\android-app" :app:assembleDebug
```

## Tests

Backend tests:

```powershell
cd backend
python -m pytest tests -q
```

Frontend build check:

```powershell
cd frontend
npm run build
```

## Local Data and Secrets

The app stores personal data locally. These files are intentionally ignored by git and should not be committed:

- `backend/app/library.db`, `*.db`, `*.db-wal`, `*.db-shm`
- `backend/backups/`
- `backend/instance/` such as `backend/instance/deepseek.json`
- `backend/x_archive_uploads/`
- `covers/`
- `logs/`
- `.claude/`, `.vscode/`, `.idea/`
- `android-app/local.properties`
- Android/Gradle build outputs

If you configure DeepSeek, the API key is saved locally in `backend/instance/deepseek.json` unless you use environment variables.

## Security Notes

HE Manager is intended for local personal use.

- The launcher exposes the backend on the LAN (`0.0.0.0:8010`).
- The API uses bearer tokens, and some media URLs include tokens in query strings for client playback.
- External service cookies/tokens may be stored in the local SQLite database.
- The frontend may store session tokens and ASMR credentials in browser `localStorage`.
- CORS is permissive for local development.

For public or remote deployment, put the app behind HTTPS, restrict CORS, avoid query-string tokens, add token expiration, and protect the SQLite database and local storage.

## License

No license has been added yet. Until one is provided, all rights are reserved by the repository owner.
