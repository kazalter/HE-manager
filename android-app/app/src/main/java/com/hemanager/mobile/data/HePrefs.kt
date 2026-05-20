package com.hemanager.mobile.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用本地偏好设置的统一入口。
 *
 * 把散落的 `getSharedPreferences("he_manager", MODE_PRIVATE)` 调用
 * 和裸的 key 字符串（`"server_url"` / `"token"` / `"mobile_image_gallery_tile_dp"`）
 * 收口到一个类型安全的 API。
 *
 * **使用方式**
 * ```kotlin
 * // 一次性创建，可在 Activity 字段或 remember 中持有
 * val prefs = HePrefs(context)
 *
 * // 读
 * if (prefs.serverUrl.isBlank()) { ... }
 *
 * // 写
 * prefs.saveCredentials(server, token)
 * prefs.galleryTileDp = newSize
 * prefs.clearToken()  // 登出
 * ```
 *
 * **未来演进**：现在底层是 SharedPreferences；若要换成 DataStore（异步、Flow），
 * 只需替换这一个文件的实现，调用方不变。
 */
class HePrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 媒体服务器地址，如 `http://192.168.1.23:8010`。未设置时返回空串。 */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value).apply()
        }

    /** 登录后的 access_token。空串表示未登录。 */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    /**
     * 图片库网格瓦片的稳定 dp 大小（受 pinch-to-zoom 影响）。
     * 默认 [DEFAULT_GALLERY_TILE_DP]。
     *
     * **注意**：从 column-count 模型上线后这只用于一次性迁移（见 [galleryColumns]），
     * 新逻辑应优先用 [galleryColumns]。
     */
    var galleryTileDp: Float
        get() = prefs.getFloat(KEY_GALLERY_TILE_DP, DEFAULT_GALLERY_TILE_DP)
        set(value) {
            prefs.edit().putFloat(KEY_GALLERY_TILE_DP, value).apply()
        }

    /**
     * 图片库网格的稳定列数（pinch-to-zoom 在松手时 snap 到的目标）。
     * 默认 [DEFAULT_GALLERY_COLUMNS]。
     *
     * 取值范围由调用方约束（典型 3..7），HePrefs 不强校验，只负责持久化。
     * 迁移：若该 key 不存在（[hasGalleryColumns] 为 false），调用方应根据 [galleryTileDp] +
     * 屏幕宽度推导一个初始列数并 set，使旧用户体感不跳变。
     */
    var galleryColumns: Int
        get() = prefs.getInt(KEY_GALLERY_COLUMNS, DEFAULT_GALLERY_COLUMNS)
        set(value) {
            prefs.edit().putInt(KEY_GALLERY_COLUMNS, value).apply()
        }

    /** [galleryColumns] 是否已显式写入过（用于一次性迁移判断）。 */
    val hasGalleryColumns: Boolean
        get() = prefs.contains(KEY_GALLERY_COLUMNS)

    /** 保存一次登录得到的 (server, token) 组合。 */
    fun saveCredentials(serverUrl: String, token: String) {
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    /** 仅清空 token（保留服务器地址方便下次重登）。 */
    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val PREFS_NAME = "he_manager"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_GALLERY_TILE_DP = "mobile_image_gallery_tile_dp"
        private const val KEY_GALLERY_COLUMNS = "mobile_image_gallery_columns"

        const val DEFAULT_GALLERY_TILE_DP = 78f
        const val DEFAULT_GALLERY_COLUMNS = 5
    }
}
