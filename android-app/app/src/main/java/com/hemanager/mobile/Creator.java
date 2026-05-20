package com.hemanager.mobile;

import org.json.JSONObject;

/** A unified creator: an X author ("x" kind) or a manga artist ("artist"). */
public class Creator {
    public String kind = "";       // "x" | "artist"
    public String key = "";        // unified id: "x:<screen_name>" | "a:<artist>"
    public String screenName = ""; // X only; empty for manga artists
    public String displayName = "";
    public int mediaCount;
    public int postsKnown;
    public int postsPending;
    public String coverPath = "";

    public static Creator fromJson(JSONObject json) {
        Creator c = new Creator();
        c.kind = json.optString("kind", "");
        c.key = json.optString("key", "");
        c.screenName = json.isNull("screen_name") ? "" : json.optString("screen_name", "");
        c.displayName = json.isNull("display_name") ? "" : json.optString("display_name", "");
        c.mediaCount = json.optInt("media_count", 0);
        c.postsKnown = json.optInt("posts_known", 0);
        c.postsPending = json.optInt("posts_pending", 0);
        c.coverPath = json.isNull("cover_path") ? "" : json.optString("cover_path", "");
        return c;
    }

    /** Best label for the card / header. */
    public String label() {
        if (displayName != null && !displayName.isEmpty()) return displayName;
        if (screenName != null && !screenName.isEmpty()) return "@" + screenName;
        return key;
    }
}
