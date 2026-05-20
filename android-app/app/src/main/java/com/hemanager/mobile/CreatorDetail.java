package com.hemanager.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Response of /mobile/creators/detail: the creator plus their works. */
public class CreatorDetail {
    public Creator creator;
    public List<MediaItem> media = new ArrayList<>();

    public static CreatorDetail fromJson(JSONObject json) {
        CreatorDetail d = new CreatorDetail();
        JSONObject c = json.optJSONObject("creator");
        d.creator = c != null ? Creator.fromJson(c) : new Creator();
        JSONArray array = json.optJSONArray("media");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject m = array.optJSONObject(i);
                if (m != null) d.media.add(MediaItem.fromJson(m));
            }
        }
        return d;
    }
}
