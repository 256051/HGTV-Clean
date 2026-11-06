package com.hgtv.clean;

import java.io.IOException;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic response checks; runs on Android's own JSON implementation without any API requests. */
public final class ContentParserTest {
    /** Executes recommendation, episode order, search branch and malformed-response checks. */
    public static void main(String[] args) throws Exception {
        List<Models.Drama> cards = ContentParser.recommended(new JSONObject("{\"code\":0,\"data\":{\"video_data\":[{\"series_id\":\"123\",\"title\":\"合成剧集\",\"episode_cnt\":9,\"cover\":\"https:\\\\/\\\\/example.com/a?x=1&amp;y=2\",\"category_schema\":\"[{\\\"name\\\":\\\"剧情\\\"},{\\\"name\\\":\\\"合成\\\"}]\"},{\"series_id\":\"123\",\"title\":\"重复\"},{\"series_id\":\"invalid\",\"title\":\"跳过\"},{\"series_id\":\"456\",\"title\":null},{\"series_id\":\"789\",\"title\":\"零集\",\"episode_cnt\":-3}]}}"));
        check(cards.size() == 2, "recommendation validation/deduplication");
        check(cards.get(0).cover.equals("https://example.com/a?x=1&y=2"), "cover unescaping");
        check(cards.get(0).subtitle.equals("剧情 · 合成"), "string category schema");
        check(cards.get(1).episodeCount == 0, "negative count normalization");
        boolean immutable = false;
        try { cards.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "immutable result");

        List<Models.Episode> episodes = ContentParser.episodes(new JSONObject("{\"code\":0,\"data\":{\"video_data\":{\"video_list\":[{\"vid\":\"202\",\"vid_index\":2},{\"vid\":\"bad\",\"vid_index\":0},{\"vid\":\"101\",\"vid_index\":1},{\"vid\":\"202\",\"vid_index\":2},{\"vid\":\"303\",\"vid_index\":3}]}}}"));
        check(episodes.size() == 3 && episodes.get(0).id.equals("101") && episodes.get(2).id.equals("303"), "episode index order/deduplication");
        check(episodes.get(1).index == 1 && episodes.get(1).title.equals("第 2 集"), "logical episode numbering");

        JSONObject app = new JSONObject("{\"code\":0,\"data\":{\"search_tabs\":[{\"tab_type\":19,\"data\":[{\"book_id\":\"501\",\"search_high_light\":{\"title\":{\"text\":\"命中剧名\"}},\"video_data\":[{\"title\":\"备用剧名\",\"episode_cnt\":8,\"cover_url\":\"https://example.com/cover\"}]},{\"lynx_data\":\"{\\\"cell_data\\\":[{\\\"search_result_id\\\":\\\"502\\\",\\\"cell_name\\\":\\\"嵌套剧集\\\",\\\"video_data\\\":[{}]}]}\"},{\"lynx_data\":\"not-json\"}]},{\"tab_type\":11,\"data\":[{\"subscribe_items\":[{\"item_id_str\":\"601\",\"name\":\"预约剧集\",\"tag_info\":{\"text\":\"预约\"}}]}]}]}} ");
        cards = ContentParser.appSearch(app, 19);
        check(cards.size() == 2 && cards.get(0).id.equals("501") && cards.get(0).title.equals("命中剧名"), "app card fallbacks/highlight");
        check(cards.get(1).id.equals("502") && cards.get(1).title.equals("嵌套剧集"), "nested lynx cell fallback");
        check(ContentParser.appSearch(app, 11).get(0).subtitle.equals("预约"), "subscription search branch");

        JSONObject web = new JSONObject("{\"searchList\":[{\"keyword\":\"701\",\"name\":\"网页剧集\",\"video_data\":{\"series_cover\":\"https://example.com/poster\",\"episode_cnt\":6,\"category_list\":[{\"name\":\"都市\"}]}},{\"series_id\":\"702\",\"series_title\":\"直接剧集\",\"episode_cnt\":-1},{\"keyword\":\"701\",\"name\":\"重复\"}]}");
        cards = ContentParser.webSearch(web);
        check(cards.size() == 2 && cards.get(0).subtitle.equals("都市") && cards.get(1).title.equals("直接剧集"), "web cards/fallback/deduplication");
        check(ContentParser.webSearch(new JSONObject("{\"code\":\"invalid_query\"}")).isEmpty(), "web invalid query");
        for (JSONObject response : new JSONObject[]{new JSONObject("{\"code\":1,\"secret\":\"must-not-be-logged\"}"), new JSONObject("{\"code\":0}"), new JSONObject("{\"code\":0,\"data\":{}}")}) {
            boolean rejected = false;
            try { ContentParser.recommended(response); }
            catch (IOException expected) { rejected = !expected.getMessage().contains("secret"); }
            check(rejected, "malformed recommendation rejection");
        }
        boolean missingTab = false;
        try { ContentParser.appSearch(app, 999); } catch (IOException expected) { missingTab = true; }
        check(missingTab, "missing search tab rejection");
        boolean emptyEpisode = false;
        try { ContentParser.episodes(new JSONObject("{\"code\":0,\"data\":{\"video_data\":{\"video_list\":[]}}}")); }
        catch (IOException expected) { emptyEpisode = true; }
        check(emptyEpisode, "empty episode rejection");
        System.out.println("Content parser checks passed (synthetic JSON only).");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
