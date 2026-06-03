# HE Manager 功能路线图

> 代码即真相：已完成的只留一行；未完成的保留可执行细节。

## 总览

| # | 计划 | 状态 |
|---|------|------|
| ① | 个人数据看板（`/stats/*` + StatsView） | ✅ 2026-05-17 |
| ② | 标签体系 + 命名空间 + 自动打标 + 回填 | ✅ 2026-05-17 |
| ③ | 创作者聚合页（基于 X 作者，独立实现） | ✅ 2026-05-17 |
| ④ | 感知哈希近似去重 | ✅ 2026-05-19（P1 计算+P2 接入分类+veto；P3 汉明距离已入 reason） |
| ⑤ | 标签管理界面（改名/合并/删除） | ✅ 2026-05-17 |
| ⑥ | ASMR 音频来源（同步/下载/播放/字幕/打标/镜像/体积） | ✅ 全部完成 |
| ⑦ | 手机端传输优化 | 🟡 后端完成；Android 分页搁置 |
| ⑧ | Android 性能 | ✅ release+BaselineProfile；滑动 Option B |
| ⑨ | 公网（FRP）暴露安全加固 | ✅ 代码完成；安卓端经 Sakura https 全程加密 |

**剩余可执行计划：⑦ Tier2② Android 分页、⑧ Option A、④ 可选后续（跨标题 LSH，见下方）。**

---

## ④ 感知哈希近似去重（✅ 完成 2026-05-19）

抽样 SHA-1 精确比对漏掉重编码/重存的副本；pHash 补"近似"层。实测：同标题内真重复
召回 **47%→100%**（77/77 冗余 manga 标记，~3.2GB 可回收），13 个 'checking' 孤儿清零。

- DCT pHash（16hex/64bit）算/存/迁移已上；阈值 **≤6 suspected、≤10 weak**（实测分布双峰
  验证，7–16 几乎空）；`classify()` 顶层把 pHash 作第二信号取较强者，**不改原 3 个 SHA 函数**。
- **pHash veto**：距离 ≥17（实测=视觉无关基线）否决"页数/体积巧合"的元数据假阳
  （同系列不同卷被 `normalize_title` collapse 成一组所致）；STRONG 与 phash 解码失败不否决。
- P3 不另做 UI：汉明距离写进 `duplicate_candidates.reason`，现有 DedupView/`/dedup/*` 直接显示。
- `media_type="audio"` 不入指纹流程，无需处理。
- **可选后续**：pHash 目前仍受 `normalized_title` 候选过滤限制，只抓同标题内近似；要抓
  跨标题改名/重编码副本需 phash 分桶/BK-tree/LSH 全库近邻——独立后续，非必须。

---

## ⑥ ASMR — asmr.one 接口要点（线上实测，非代码可推导，保留）

- 镜像轮换 `api.asmr-200/.one/-100/-300`，客户端多镜像 fallback；用户配置 base 存 `source.favorites_url`。
- 登录 `POST /api/auth/me {name,password}` → `{token}`；token 存 `source.cookie`，密码不落库。
- 用户"喜欢" = **私有播放列表**（非 mark），仅本人可见：`GET /api/playlist/get-playlist-works?id=<uuid>`，
  必须本人 token（否则 `playlist.playlistNotFound` → `AsmrApiError` 中文报错）。
- 文件树 `GET /api/tracks/{id}` 嵌套 folder/file；audio 节点带 `mediaDownloadUrl/size/duration/title`，
  另有 image 与字幕(lrc/vtt/srt)。封面 `/api/cover/<id>.jpg` 免鉴权。

---

## 变更记录（一行）

- 05-17：①stats、③creators、⑤TagsView、②标签体系（`Tag.namespace`+自动打标+回填552 artist）；顺带修 WNACG 收藏页解析。
- 05-18：⑥ P1 同步（私有 playlist 纠偏）/ P2 下载入库+播放 / P3 自动打标 / lrc 字幕 / 多镜像 UI / 体积控制。全部完成。
- 05-19：⑥ Android ASMR 播放器 P1（独立 `audio/` 包：MediaSessionService 后台播放 + Compose Now-Playing：歌词/睡眠定时/循环/续听）。
- 05-19：⑦ Tier1（`JSONGZipMiddleware` 只压 JSON、图片缓存头、tags 消 N+1）+ Tier2①（瘦身 `MobileMediaItem`，列表 ↓~58%）。后端，零回归。
- 05-19：⑦ Tier2② 后端向后兼容分页接口已就位（无 `limit` 仍返数组）；**Android 半搁置**（见下方"未竟"）。
- 05-19：⑧ 滑动卡顿 Option B（`NativeLibraryAdapterV2`：组合不随回收销毁 + 缓存下限12 + DiffUtil）。
- 05-19：⑧ 启动卡顿根因 = 测的是 debug 包。加 `buildTypes.release` + `profileinstaller` + `baseline-prof.txt`，**用户实测已流畅**。
- 05-19：④ P1 — phash 列+幂等迁移+DCT pHash 计算（4 类媒体复用既有采样）+持久化+缓存失效回填+观察脚本。pytest 36 绿，零回归，未改判定。
- 05-19：④ P2 — pHash 接入 `classify()`（≤6/≤10 阈值，取较强者，不改 SHA 函数）+ ≥17 veto 降噪；全库回填+重分类（一次性脚本）。真重复召回 47%→100%，noise pair 579→136，孤儿 13→0。汉明距离入 reason（免改前端）。pytest 36 绿。
- 05-24：① 统计看板优化（P1+P2+P3）。后端：月/日分桶 SQL 化（strftime+GROUP BY），4 endpoint+1 新 `/stats/highlights` 加 30s TTL 缓存，overview 增 `by_type_size`、activity 增 `by_type`；首次冷启 ~26ms，命中 0.004ms。前端 StatsView：① 库增长改"柱（月增）+ 曲线（累计）"；② 活跃热力图加类型 tab（全部/视频/漫画/杂图/音频）；③ 类型/收藏/来源 bars/cards + 关注作品封面可点击跳转 Library；④ 新增 Top 创作者 / 最长视频 / 热门标签三张卡（按媒体数/时长/使用数）。HomeView 加 `?source=` 查询参数。pytest 35 绿，vue-tsc 0。

## ⑨ 公网（FRP）暴露安全加固

为把库通过 FRP 暴露到公网而做的鉴权与防滥用加固。**代码层已完成；传输层对实际使用场景（仅安卓 App）已加密。**

- 06-03（`945047e`）：全局鉴权中间件（除 `/auth/status|login|bootstrap` 白名单外每请求校验 token）+
  admin 路径 403 门禁 + 30 天 token TTL/登出吊销/登录暴力破解节流（429）+ 默认关 docs + CORS 可配 +
  安全响应头 + 停止泄露 `absolute_path` + 密码最小长度 6→10。前端 authUrl 拼 token / 401 自动登出 / admin-only 导航。
- 06-03（`a620b9e`）：堵住登录节流的 **X-Forwarded-For 伪造绕过**（`_client_ip` 默认不信任 XFF，除非
  `HE_TRUST_FORWARDED_FOR=1`）+ 加**按用户名全局兜底**计数（`HE_LOGIN_MAX_FAILURES_PER_USER`，默认 15/窗口）+
  `/manga/{id}/page/{i}` 的 500 不再回显原始异常（避免泄露磁盘绝对路径）。回归测试见 `tests/test_login_throttle.py`。

**传输层现状（运维，代码改不了）：**
- **实际使用 = 仅手机安卓 App。** 安卓 App 默认走 https（`normalizeServerUrl` 裸域名自动补 `https://`），
  服务器地址填 `https://<sakura 子域>` 即由 Sakura 边缘证书加密第①段（手机↔Sakura）；第②段（Sakura↔本机 frpc）
  由 Sakura 客户端默认 TLS 加密。**两段都加密后，安卓端 token 全程不可被路上嗅探。**
- **唯一残留**：Sakura 在边缘解密后转发给本机，故需信任 Sakura 中转服务器本身——这是任何穿透/CDN（含 Cloudflare）
  共有的，非本项目可消除。
- **若将来改用网页 UI 走 FRP**（目前不用）：`config.ts` 默认 base URL 硬编码 `:8010`，会连不上 Sakura 的 443，
  需 `VITE_API_BASE_URL=https://<子域>` 重新构建 + 设 `HE_ALLOWED_ORIGINS` 收紧 CORS + 可加 HSTS 头。
- 在意 token 泄露窗口可调短 `HE_ACCESS_TOKEN_TTL_DAYS`（默认 30 天）。

---

## 未竟（按优先级）

1. **⑦ Tier2② Android 分页** — 后端接口已就位且向后兼容。Android 搁置：实际列表屏 `LibraryScreenV2`
   整库拉入 + 客户端筛选/计数/搜索 + RecyclerView，接分页 = 深度重写主屏（status 筛选后端无参数），
   高回归风险。详见记忆 `project_android_library_screen`。
2. **⑧ Option A** — 若滑动仍不够顺：卡片模式 RecyclerView+ComposeView → Compose LazyVerticalGrid
   （原生复用组合，根治），保留原生 TileHolder 走杂图。
3. **⑥ 可选** — Baseline Profile 升级为 baselineprofile 插件 + macrobench 设备生成（当前为广谱手写）。
4. **④ 可选后续** — pHash 跨标题全库近邻（LSH/BK-tree），抓改名/重编码副本；脱离 `normalized_title` 候选过滤。

> 运维坑（详见记忆 `project_run_scripts`）：改后端代码后确认 8010 无僵尸 uvicorn；
> watchfiles 在含空格路径上热重载常抖，必要时重启 he.ps1。
