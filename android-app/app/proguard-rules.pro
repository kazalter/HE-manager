# ProGuard / R8 规则
#
# 默认 Android optimize 规则已通过 proguard-android-optimize.txt 加载，
# 这里只补充本项目特有的规则。
#
# 调试 minified release 时，可加 -keepattributes SourceFile,LineNumberTable
# 并配合 R8 mapping file 还原堆栈。

# ---- 保留源文件名和行号，便于解析 release 堆栈 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Jetpack Compose ----
# Compose 编译器已自动处理；这里只兜底，防止反射相关 lambda 被裁。
-keep class androidx.compose.runtime.** { *; }

# ---- Media3 / ExoPlayer ----
# Media3 大量使用反射加载 extractor / decoder，禁止裁剪。
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---- Coil ----
# Coil 通过反射加载 decoder，保留入口。
-keep class coil.** { *; }
-dontwarn coil.**

# ---- Kotlinx Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ---- SubsamplingScaleImageView ----
-keep class com.davemorrissey.labs.subscaleview.** { *; }

# ---- App 自身的 model 类（JSON 反序列化、Parcelable 等） ----
# 当前 MediaItem / TagItem 使用 JSONObject 手动解析，没有反射，不需保留。
# 若未来引入 kotlinx-serialization 或 Gson，请按需添加。
