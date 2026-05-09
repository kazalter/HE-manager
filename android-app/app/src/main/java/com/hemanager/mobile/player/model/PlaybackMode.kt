package com.hemanager.mobile.player.model

enum class PlaybackMode(val label: String) {
    SEQUENCE("顺序播放"),
    SINGLE("单集循环"),
    LIST("列表循环"),
    SHUFFLE("随机播放"),
    END_PAUSE("播完暂停");

    fun next(): PlaybackMode {
        val values = values()
        return values[(ordinal + 1) % values.size]
    }

    companion object {
        fun fromName(name: String?): PlaybackMode =
            values().firstOrNull { it.name == name } ?: SEQUENCE
    }
}
