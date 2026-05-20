package com.hemanager.mobile.ui.util

import android.content.Context
import android.widget.Toast

/**
 * 占位提示：标记 “即将开放” / “Coming soon” 类未实现入口。
 *
 * 集中走一处方便：
 *   - 一眼 grep 出所有未做完的功能
 *   - 以后想换成 Snackbar / 引导跳转时只改一个文件
 *   - 文案统一（避免"即将上线"/"即将开放"/"敬请期待"混用）
 */
fun Context.toastComingSoon(featureName: String) {
    Toast.makeText(this, "$featureName 即将开放", Toast.LENGTH_SHORT).show()
}

/**
 * 简易错误反馈，封装重复的 `Toast.makeText(...).show()` + 错误消息拼接。
 *
 * 用于 sheet / dialog 等没有平行 ErrorPanel 的弱场景；
 * 主屏错误请走 `error` state + ErrorPanel，不要用 Toast。
 */
fun Context.toastError(prefix: String, detail: String) {
    Toast.makeText(this, "$prefix: $detail", Toast.LENGTH_SHORT).show()
}
