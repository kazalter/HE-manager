# CLAUDE.md — HE Manager

个人自用媒体库（视频/漫画/图片/ASMR音频）。后端 FastAPI + 前端 Vue + 安卓原生 App。
本文件是常驻索引，避免每次重进逐个翻文件。路线图见 `FEATURE_PLANS.md`。

## ⚠️ 硬规则 — 防数据/代码丢失（任何 Claude 接手都必须遵守）

**触发条件**：要执行 `git reset --hard` / `git checkout -- <path>` / `git checkout <branch>`
（在脏工作区切分支）/ `git stash drop` / `git clean -fd*` / `git branch -D`（删未合并分支）
/ `git worktree remove --force` 之前。

**强制流程**（不能跳）：
1. **先跑 `git status`**——不要凭"我以为工作区干净"做判断，永远显式确认一次。
2. **如有任何工作区改动**（含 modified tracked + untracked source）：
   - 修改类（`M`）→ 先 `git commit`（即便起名 `WIP: ...` 也行，重点是入仓）。
   - 未追踪源码（`??` 含 .py / .vue / .ts / .kt / .java / .md / .ps1 等）→ 评估应 commit 还是
     应 ignore，逐个分类处理。**不允许"它好像没用就放着"**——已经被覆盖一次了。
3. 如果用户压时间不想 commit，至少 `git stash push -u -m "preflight"`，记下 stash 编号。
4. **完成 commit / stash 之后才能跑那条破坏性命令。**

**反面教材**（这条规则的由来）：上一次会话上下文压缩之后，Claude 不知道工作区有 910 行
未提交的 ASMR/audio 后端代码，直接 `git reset --hard` 把 main 切到 integration 分支，
导致用户的真实功能"看似消失"——花了大半天追查才发现是 reset 偷偷覆盖了 tracked 文件。
完整复盘见本会话历史（5/21-5/22）。

## 跑 / 构建 / 测试

- **Web 栈启动**：两个脚本，逻辑都在 `he.ps1`。
  - `he.ps1` — 全栈（后端 `--reload` + vite + 自动开浏览器），desktop 日常开发用。
  - `he-server.ps1` — 仅后端 LAN 服务（无 vite、无浏览器、无 reload），手机 / 安卓 App 用。3 行 wrapper 调 `he.ps1 -Server`，**改启动逻辑只动 he.ps1**。
  - 启动前自动备份 `library.db` → `backend/backups/`，保留最近 7 份。
  - 关闭走优雅退出（taskkill 不带 /F，给 uvicorn 4 秒清理 WAL checkpoint），4 秒未退才强杀。
  - 改后端代码后：watchfiles 在含空格路径上热重载**常抖**，必要时 Q 退出 → 确认 8010 无僵尸 uvicorn → 重启。
- **后端测试**：解释器只有 `C:\Users\25768\AppData\Local\Programs\Python\Python312\python.exe`
  装了后端依赖+pytest。在 `backend\`：`python -m pytest tests -q`（基线 36 通过）。
  `D:\Hermes\venv` 有 pytest 但无后端依赖，别用。
- **安卓 debug**（日常迭代，快）：`android_preview.bat`（`installDebug`，有 `-Watch`）。
- **安卓 release**（性能/体感检查点，~2min）：`android_release.bat`（`installRelease`，非 debuggable，
  debug 签名可覆盖装，跳 lint 提速）。**判断启动卡/滑动顺必须用 release，debug 不代表性能。**
  Baseline Profile 首启后台装，第二次冷启才是优化后体感。
- 设备：两台连着，脚本只认 `f…` 那台、排除 `hbl…`（`ANDROID_SERIAL` 钉死，防双装）。
- 安卓构建验证：`gradlew.bat -p "D:\HE manager\android-app" :app:assembleDebug -q`（无输出=成功）。

## 改动边界（重要）

- **安卓**：编辑限 `MainActivity`（主浏览 UI）。**别动** `MangaActivity`、`player/PlayerActivity`
  （受保护：自管窗口/视频播放器重写中）。新增**独立新文件/包**可以（如 `audio/`）。
- **后端**：`/mobile/*` 是手机专用命名空间，可独立优化；web 走 `/media` `/stream`，别混改。

## 架构关键事实（省得重新探索）

- 安卓主列表屏是 **`LibraryScreenV2`**（MainActivity ~1710，`setContent`→432 渲染它）；
  老 `LibraryScreen`(~605) 是**死代码**。V2：整库一次拉入 `allItems` + 客户端筛选/计数/搜索 +
  RecyclerView 自定义 adapter（卡片走 ComposeView，杂图走原生 TileHolder）。
- 安卓 ASMR 播放器在 `audio/` 包：`AsmrPlaybackService`(MediaSessionService) +
  `AudioPlayerViewModel`(MediaController) + `AudioPlayerActivity`+`ui/AudioPlayerScreen`。
  续听复用 `media.progress` 列存累计秒。
- 后端音频接口（无鉴权，web 端依赖此）：`/audio/{id}/tracks`、`/audio/{id}/track/{i}`(206 ranged)、
  `/audio/{id}/track/{i}/lyrics`。手机列表 `/mobile/media`（带 `limit` 走分页信封，否则数组）。
- 已建迁移用幂等 `ALTER TABLE`（对标 `sync_position` 写法），零结构迁移惯例。

## 坑

- **Kotlin 块注释会嵌套**：KDoc 里写 `/audio/*` 这类含 `/*` 的文本会开未闭合嵌套注释，整文件编译失败。
- 前端路由组件必须单根（App.vue transition out-in + dev 注释 vnode → 导航空白页）。
- gzip 中间件只压 JSON，故意放行流式/206（守 web `<video>` Range + Media3 identity）。

## 记忆文件（需要时再读，平时不必）

`memory/MEMORY.md` 是索引。重点：`feedback_android_scope`、`project_android_library_screen`、
`project_run_scripts`、`project_asmr_mobile_player`、`feedback_collaboration`（分阶段交付、复用优先）。
