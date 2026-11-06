package com.hgtv.clean;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Parses content responses into display models without retaining account or transport data. */
final class ContentParser {
    private ContentParser() { }

    static List<Models.Drama> recommended(JSONObject response) throws IOException {
        success(response);
        JSONObject data = response.optJSONObject("data");
        JSONArray videos = data == null ? null : data.optJSONArray("video_data");
        if (videos == null) throw new IOException("推荐列表结构已变化。");
        Map<String, Models.Drama> result = new LinkedHashMap<>();
        for (int i = 0; i < videos.length(); i++) {
            JSONObject video = videos.optJSONObject(i);
            if (video == null) continue;
            int count = count(video);
            add(result, text(video, "series_id"), text(video, "title"), text(video, "cover"),
                    subtitle(video, "category_schema", count > 0 ? count + " 集" : ""), count);
        }
        return cards(result);
    }

    static List<Models.Episode> episodes(JSONObject response) throws IOException {
        success(response);
        JSONObject data = response.optJSONObject("data");
        JSONObject series = data == null ? null : data.optJSONObject("video_data");
        JSONArray videos = series == null ? null : series.optJSONArray("video_list");
        if (videos == null) throw new IOException("剧集列表结构已变化。");
        List<JSONObject> ordered = new ArrayList<>();
        for (int i = 0; i < videos.length(); i++) {
            JSONObject video = videos.optJSONObject(i);
            if (video != null && validId(text(video, "vid"))) ordered.add(video);
        }
        Collections.sort(ordered, (left, right) -> Integer.compare(left.optInt("vid_index", 0), right.optInt("vid_index", 0)));
        Map<String, Models.Episode> result = new LinkedHashMap<>();
        for (JSONObject video : ordered) {
            String id = text(video, "vid");
            if (result.containsKey(id)) continue;
            int index = result.size();
            result.put(id, new Models.Episode(id, "第 " + (index + 1) + " 集", index));
        }
        if (result.isEmpty()) throw new IOException("此作品暂无可播放剧集。");
        return Collections.unmodifiableList(new ArrayList<>(result.values()));
    }

    static List<Models.Drama> appSearch(JSONObject response, int tab) throws IOException {
        success(response);
        JSONArray tabs = response.optJSONArray("search_tabs");
        if (tabs == null) {
            JSONObject data = response.optJSONObject("data");
            tabs = data == null ? null : data.optJSONArray("search_tabs");
        }
        if (tabs == null) throw new IOException("搜索结果结构已变化。");
        JSONObject selected = null;
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject item = tabs.optJSONObject(i);
            if (item != null && item.optInt("tab_type", -1) == tab) { selected = item; break; }
        }
        if (selected == null) throw new IOException("搜索分类暂未返回。");
        Map<String, Models.Drama> result = new LinkedHashMap<>();
        JSONArray rows = selected.optJSONArray("data");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            searchRow(row, result);
            JSONObject lynx = object(row.opt("lynx_data"));
            JSONArray cells = lynx == null ? null : lynx.optJSONArray("cell_data");
            for (int j = 0; cells != null && j < cells.length(); j++) {
                JSONObject cell = cells.optJSONObject(j);
                if (cell != null) searchRow(cell, result);
            }
        }
        return cards(result);
    }

    static List<Models.Drama> webSearch(JSONObject response) throws IOException {
        if (response == null) throw new IOException("官网搜索响应无效。");
        if (response.has("code")) {
            if ("invalid_query".equals(text(response, "code"))) return Collections.emptyList();
            throw new IOException("官网搜索暂不可用。");
        }
        JSONArray rows = response.optJSONArray("searchList");
        if (rows == null) throw new IOException("官网搜索结构已变化。");
        Map<String, Models.Drama> result = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            JSONObject video = row.optJSONObject("video_data");
            if (video == null || video.length() == 0) video = row;
            int count = count(video);
            String badge = text(video, "episode_right_text");
            if (badge.isEmpty() && count > 0) badge = count + " 集";
            add(result, first(text(video, "series_id"), text(row, "keyword")),
                    first(text(video, "series_title"), text(row, "name")), text(video, "series_cover"),
                    subtitle(video, "category_list", badge), count);
        }
        return cards(result);
    }

    private static void searchRow(JSONObject row, Map<String, Models.Drama> result) {
        JSONArray videos = row.optJSONArray("video_data");
        JSONObject video = videos == null ? null : videos.optJSONObject(0);
        if (video != null) {
            JSONObject highlight = row.optJSONObject("search_high_light");
            JSONObject title = highlight == null ? null : highlight.optJSONObject("title");
            int count = count(video);
            add(result, first(text(video, "series_id"), first(text(row, "book_id"), text(row, "search_result_id"))),
                    first(text(title, "text"), first(text(video, "title"), text(row, "cell_name"))),
                    first(text(video, "cover"), text(video, "cover_url")),
                    subtitle(video, "category_schema", count > 0 ? count + " 集" : text(video, "rec_text")), count);
            return;
        }
        JSONArray subscriptions = row.optJSONArray("subscribe_items");
        JSONObject subscription = subscriptions == null ? null : subscriptions.optJSONObject(0);
        if (subscription == null) return;
        JSONObject tag = subscription.optJSONObject("tag_info");
        String badge = first(text(subscription, "sub_info"), text(tag, "text"));
        add(result, first(text(subscription, "item_id"), text(subscription, "item_id_str")),
                text(subscription, "name"), text(subscription, "cover"), subtitle(subscription, "category_schema", badge), 0);
    }

    private static void add(Map<String, Models.Drama> result, String id, String title, String cover, String subtitle, int count) {
        if (!validId(id) || title.trim().isEmpty() || result.containsKey(id)) return;
        result.put(id, new Models.Drama(id, title, unescape(cover), subtitle, count));
    }

    private static String subtitle(JSONObject item, String field, String fallback) {
        JSONArray values = array(item.opt(field));
        String tags = tags(values);
        if (tags.isEmpty()) tags = text(item, "category");
        if (tags.isEmpty()) tags = tags(item.optJSONArray("category_list"));
        return tags.isEmpty() ? fallback : tags;
    }

    private static String tags(JSONArray categories) {
        StringBuilder tags = new StringBuilder();
        int count = 0;
        for (int i = 0; categories != null && i < categories.length() && count < 5; i++) {
            String name = text(categories.optJSONObject(i), "name");
            if (name.trim().isEmpty()) continue;
            if (tags.length() > 0) tags.append(" · ");
            tags.append(name); count++;
        }
        return tags.toString();
    }

    private static JSONObject object(Object value) {
        if (value instanceof JSONObject) return (JSONObject) value;
        if (!(value instanceof String)) return null;
        try { return new JSONObject((String) value); }
        catch (JSONException ignored) { return null; }
    }

    private static JSONArray array(Object value) {
        if (value instanceof JSONArray) return (JSONArray) value;
        if (!(value instanceof String)) return null;
        try { return new JSONArray((String) value); }
        catch (JSONException ignored) { return null; }
    }

    private static String text(JSONObject item, String field) {
        if (item == null) return "";
        Object value = item.opt(field);
        return value instanceof String || value instanceof Number ? String.valueOf(value) : "";
    }

    private static int count(JSONObject item) { return Math.max(0, item.optInt("episode_cnt", 0)); }
    private static String first(String preferred, String fallback) { return preferred.isEmpty() ? fallback : preferred; }
    private static boolean validId(String id) { return id.matches("[0-9]{1,30}"); }

    private static String unescape(String value) {
        return value.replace("\\/", "/").replace("\\u002f", "/").replace("\\u002F", "/")
                .replace("\\u0026", "&").replace("&amp;", "&");
    }

    private static List<Models.Drama> cards(Map<String, Models.Drama> result) {
        return Collections.unmodifiableList(new ArrayList<>(result.values()));
    }

    private static void success(JSONObject response) throws IOException {
        if (response == null || response.optInt("code", -1) != 0) throw new IOException("内容接口暂不可用。");
    }
}
