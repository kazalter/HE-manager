# HE Manager

HE Manager 是一个个人自用的本地媒体库管理器，面向视频、漫画/图片文件夹和 ASMR 音频作品。
项目包含 FastAPI 后端、Vue 3 Web 前端，以及一个用于局域网访问的 Android 原生客户端。

这个项目主要面向自托管的个人媒体库场景。不要在没有额外安全加固的情况下直接暴露到公网。

## 功能

- 扫描和管理本地视频、漫画/图片文件夹、音频作品
- Web 端浏览、搜索、标签、统计、去重和媒体播放
- Android 客户端通过局域网连接后端，浏览媒体库并播放视频/音频
- 支持 WNACG、ASMR、X/Twitter 归档等外部收藏同步和下载流程
- 可选 DeepSeek 辅助的漫画推荐流程
- 使用 SQLite 保存本地数据，启动脚本会自动备份数据库

## 目录结构

```text
.
├── backend/       FastAPI 后端、SQLite 模型、扫描器、导入器和测试
├── frontend/      Vue 3 + TypeScript + Vite Web 前端
├── android-app/   Android 原生客户端
├── scripts/       辅助脚本
├── he.ps1         Web 全栈本地启动脚本
└── he-server.ps1  仅后端局域网启动脚本
```

## 快速开始

项目主要在 Windows 环境下开发，推荐使用 PowerShell 7（`pwsh`）。

### 1. 安装前端依赖

```powershell
cd frontend
npm install
cd ..
```

### 2. 安装后端依赖

建议使用项目本地 Python 虚拟环境。后端主要依赖 FastAPI、SQLAlchemy、Pillow、OpenCV、NumPy 和 Uvicorn。

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install fastapi uvicorn sqlalchemy pydantic pillow opencv-python numpy pytest
cd ..
```

### 3. 启动 Web 应用

```powershell
.\he.ps1
```

启动后默认地址：

- 后端 API：`http://localhost:8010`
- 前端页面：`http://localhost:5173`

启动脚本会在后端启动前把 `backend/app/library.db` 备份到 `backend/backups/`。

### 仅启动后端

如果只想给 Android App 或同一局域网内的手机浏览器使用，可以运行：

```powershell
.\he-server.ps1
```

该模式下后端监听 `0.0.0.0:8010`，请只在可信局域网中使用。

## Android

日常预览/debug 构建：

```powershell
.\android_preview.ps1
```

接近 release 体验的本地性能检查构建：

```powershell
.\android_release.ps1
```

也可以直接调用 Gradle：

```powershell
.\android-app\gradlew.bat -p ".\android-app" :app:assembleDebug
```

## 测试

后端测试：

```powershell
cd backend
python -m pytest tests -q
```

前端构建检查：

```powershell
cd frontend
npm run build
```

## 本地数据和密钥

应用会在本地保存个人数据。以下文件和目录已被 `.gitignore` 忽略，不应该提交到仓库：

- `backend/app/library.db`、`*.db`、`*.db-wal`、`*.db-shm`
- `backend/backups/`
- `backend/instance/`，例如 `backend/instance/deepseek.json`
- `backend/x_archive_uploads/`
- `covers/`
- `logs/`
- `.claude/`、`.vscode/`、`.idea/`
- `android-app/local.properties`
- Android/Gradle 构建产物

如果配置了 DeepSeek，API Key 默认会保存在本机的 `backend/instance/deepseek.json`，也可以改用环境变量。

## 安全说明

HE Manager 默认面向本机和可信局域网使用。

- 启动脚本会让后端监听局域网地址（`0.0.0.0:8010`）。
- API 使用 bearer token，部分媒体 URL 会为了客户端播放把 token 放在 query string 中。
- 外部服务的 cookie/token 可能会保存在本地 SQLite 数据库里。
- 前端可能会把登录 token 和 ASMR 账号信息保存在浏览器 `localStorage` 中。
- CORS 设置偏向本地开发便利性。

如果要远程访问或公开部署，请至少加上 HTTPS、严格限制 CORS、避免 query-string token、增加 token 过期机制，并保护好 SQLite 数据库和本地配置文件。

## License

目前还没有添加开源许可证。在许可证补充之前，项目默认保留全部权利。
