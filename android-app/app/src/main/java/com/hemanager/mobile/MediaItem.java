package com.hemanager.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MediaItem {
    public int id;
    public String title;
    public String mediaType;
    public String extension;
    public String coverPath;
    public int duration;
    public int pageCount;
    public int progress;
    public int rating;
    public boolean favorite;
    public String viewStatus;
    public boolean missing;
    /** 媒体来源站点。null/空 = 本地；"x" / "wnacg" / "asmr" 等 = 外部导入。
     *  来自 backend Media.source_site，用于"来源"筛选。 */
    public String sourceSite;
    public List<TagItem> tags = new ArrayList<>();

    public static MediaItem fromJson(JSONObject json) {
        MediaItem item = new MediaItem();
        item.id = json.optInt("id");
        item.title = json.optString("title", "Untitled");
        item.mediaType = json.optString("media_type", "");
        item.extension = json.optString("extension", "");
        item.coverPath = json.optString("cover_path", "");
        item.duration = json.optInt("duration", 0);
        item.pageCount = json.optInt("page_count", 0);
        item.progress = json.optInt("progress", 0);
        item.rating = json.optInt("rating", 0);
        item.favorite = json.optBoolean("favorite", false);
        item.viewStatus = json.optString("view_status", "unviewed");
        item.missing = json.optBoolean("is_missing", false);
        // source_site 在 JSON 里可能是 null（本地媒体）；optString 会返回 "null"
        // 字符串（坑），所以用 isNull + optString 分开处理。
        if (json.isNull("source_site")) {
            item.sourceSite = null;
        } else {
            String s = json.optString("source_site", "").trim();
            item.sourceSite = s.isEmpty() ? null : s;
        }
        JSONArray tagsArray = json.optJSONArray("tags");
        if (tagsArray != null) {
            for (int i = 0; i < tagsArray.length(); i++) {
                JSONObject tagJson = tagsArray.optJSONObject(i);
                if (tagJson != null) item.tags.add(TagItem.fromJson(tagJson));
            }
        }
        return item;
    }
}
