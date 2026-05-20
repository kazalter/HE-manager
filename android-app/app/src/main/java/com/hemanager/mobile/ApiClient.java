package com.hemanager.mobile;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ApiClient {
    public final String baseUrl;
    public final String token;

    public ApiClient(String baseUrl, String token) {
        this.baseUrl = trimSlash(baseUrl);
        this.token = token;
    }

    public static String trimSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    public static String tokenQuery(String token) {
        return "token=" + Uri.encode(token);
    }

    public String mobileUrl(String path) {
        String separator = path.contains("?") ? "&" : "?";
        return baseUrl + path + separator + tokenQuery(token);
    }

    public JSONObject postJson(String path, JSONObject body, boolean auth) throws Exception {
        HttpURLConnection conn = open(path, "POST", auth);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        return new JSONObject(readResponse(conn));
    }

    public JSONObject patchJson(String path, JSONObject body, boolean auth) throws Exception {
        HttpURLConnection conn = open(path, "PATCH", auth);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        return new JSONObject(readResponse(conn));
    }

    public JSONObject deleteJson(String path, boolean auth) throws Exception {
        HttpURLConnection conn = open(path, "DELETE", auth);
        return new JSONObject(readResponse(conn));
    }

    public MediaItem toggleFavorite(int mediaId, boolean favorite) throws Exception {
        JSONObject body = new JSONObject();
        body.put("favorite", favorite);
        JSONObject json = patchJson("/media/" + mediaId, body, true);
        return MediaItem.fromJson(json);
    }

    public List<TagItem> getTags() throws Exception {
        JSONArray array = getJsonArray("/tags");
        List<TagItem> tags = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            tags.add(TagItem.fromJson(array.getJSONObject(i)));
        }
        return tags;
    }

    public MediaItem addTag(int mediaId, String tagName) throws Exception {
        JSONObject body = new JSONObject();
        body.put("name", tagName);
        JSONObject json = postJson("/media/" + mediaId + "/tags", body, true);
        return MediaItem.fromJson(json);
    }

    public void deleteMedia(int mediaId) throws Exception {
        deleteJson("/media/" + mediaId, true);
    }

    public JSONObject getJsonObject(String path) throws Exception {
        HttpURLConnection conn = open(path, "GET", true);
        return new JSONObject(readResponse(conn));
    }

    public JSONArray getJsonArray(String path) throws Exception {
        HttpURLConnection conn = open(path, "GET", true);
        return new JSONArray(readResponse(conn));
    }

    public List<MediaItem> getMedia() throws Exception {
        return getMedia("", "", "date");
    }

    public List<MediaItem> getMedia(String mediaType, String search, String sort) throws Exception {
        Uri.Builder builder = Uri.parse(baseUrl + "/mobile/media").buildUpon();
        builder.appendQueryParameter("sort", sort == null || sort.isEmpty() ? "date" : sort);
        if (mediaType != null && !mediaType.isEmpty()) builder.appendQueryParameter("media_type", mediaType);
        if (search != null && !search.trim().isEmpty()) builder.appendQueryParameter("search", search.trim());
        JSONArray array = getJsonArray(builder.build().toString().substring(baseUrl.length()));
        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            items.add(MediaItem.fromJson(array.getJSONObject(i)));
        }
        return items;
    }

    /** 列出所有创作者（按类型筛 / 模糊搜索 / 排序）。后端：GET /mobile/creators */
    public List<Creator> getCreators(String typeFilter, String search, String sort) throws Exception {
        Uri.Builder builder = Uri.parse(baseUrl + "/mobile/creators").buildUpon();
        if (typeFilter != null && !typeFilter.isEmpty() && !"all".equals(typeFilter)) {
            builder.appendQueryParameter("kind", typeFilter);
        }
        if (search != null && !search.trim().isEmpty()) {
            builder.appendQueryParameter("search", search.trim());
        }
        if (sort != null && !sort.isEmpty()) {
            builder.appendQueryParameter("sort", sort);
        }
        JSONArray array = getJsonArray(builder.build().toString().substring(baseUrl.length()));
        List<Creator> creators = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            creators.add(Creator.fromJson(array.getJSONObject(i)));
        }
        return creators;
    }

    /** 单个创作者详情（含作品列表）。后端：GET /mobile/creators/detail?key=... */
    public CreatorDetail getCreatorDetail(String key) throws Exception {
        Uri.Builder builder = Uri.parse(baseUrl + "/mobile/creators/detail").buildUpon();
        builder.appendQueryParameter("key", key == null ? "" : key);
        JSONObject json = getJsonObject(builder.build().toString().substring(baseUrl.length()));
        return CreatorDetail.fromJson(json);
    }

    private HttpURLConnection open(String path, String method, boolean auth) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "application/json");
        if (auth && token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
        }
        if (code < 200 || code >= 300) {
            String message = builder.toString();
            try {
                JSONObject json = new JSONObject(message);
                message = json.optString("detail", message);
            } catch (Exception ignored) {
            }
            throw new RuntimeException(message.length() == 0 ? "HTTP " + code : message);
        }
        return builder.toString();
    }
}
