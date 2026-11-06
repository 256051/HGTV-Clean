package com.hgtv.clean;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Local-only collection metadata; this class has no network capability. */
final class LocalLibrary {
    private final SharedPreferences prefs;
    LocalLibrary(Context context) { prefs = context.getSharedPreferences("clean-library", Context.MODE_PRIVATE); }
    List<Models.Drama> read(String shelf) {
        List<Models.Drama> result = new ArrayList<>();
        try {
            JSONArray items = new JSONArray(prefs.getString(shelf, "[]"));
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                result.add(new Models.Drama(o.getString("id"), o.getString("title"), o.optString("cover"), o.optString("subtitle"), o.optInt("count")));
            }
        } catch (Exception ignored) { /* Malformed local metadata does not block browsing. */ }
        return result;
    }
    boolean contains(String shelf, String id) {
        for (Models.Drama d : read(shelf)) if (d.id.equals(id)) return true; return false;
    }
    void put(String shelf, Models.Drama drama, boolean remove) {
        List<Models.Drama> items = read(shelf);
        for (java.util.Iterator<Models.Drama> iterator = items.iterator(); iterator.hasNext();) {
            if (iterator.next().id.equals(drama.id)) iterator.remove();
        }
        if (!remove) items.add(0, drama);
        JSONArray data = new JSONArray();
        try {
            for (int i = 0; i < Math.min(100, items.size()); i++) {
                Models.Drama d = items.get(i);
                data.put(new JSONObject().put("id", d.id).put("title", d.title).put("cover", d.cover).put("subtitle", d.subtitle).put("count", d.episodeCount));
            }
            prefs.edit().putString(shelf, data.toString()).apply();
        } catch (Exception ignored) { /* Do not replace a good library on serialization failure. */ }
    }
}
