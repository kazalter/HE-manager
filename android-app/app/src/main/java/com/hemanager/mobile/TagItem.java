package com.hemanager.mobile;

import org.json.JSONObject;

public class TagItem {
    public int id;
    public String name;

    public static TagItem fromJson(JSONObject json) {
        TagItem tag = new TagItem();
        tag.id = json.optInt("id");
        tag.name = json.optString("name", "");
        return tag;
    }
}
