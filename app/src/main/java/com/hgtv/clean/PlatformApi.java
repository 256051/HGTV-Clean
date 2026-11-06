package com.hgtv.clean;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Content, registration and explicit QR sessions implemented by this application's source. */
public final class PlatformApi implements Closeable {
    private static final String CONTENT = "https://api5-normal-sinfonlineb.fqnovel.com";
    private static final String ACCOUNT = "https://api5-normal-sinfonlinea.fqnovel.com";
    private static final String WEBSITE = "https://hongguoduanju.com";
    private static final String DEVICE_FAILURE = "设备初始化失败，请允许设备注册连接后到设置重试。现有资料已保留。";
    private final Context context;
    private final SharedPreferences preferences;
    private final ProtocolHttp http = new ProtocolHttp();
    private final AtomicReference<PlaybackAttempt> activePlayback = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean automaticRegistrationAttempted = new AtomicBoolean();
    private final ModelDeviceState modelDevices = new ModelDeviceState();
    private final ExecutorService searchWorkers = Executors.newFixedThreadPool(3);
    private final Map<String, JSONObject> searchCursors = Collections.synchronizedMap(new LinkedHashMap<>());
    private Map<String, String> localProfile = Collections.emptyMap();
    private Map<String, String> deviceCookies = Collections.emptyMap();
    private String deviceId = "0", installId = "0";
    private JSONObject account;
    private boolean ready;

    /** Opens the existing private store without reading another application's account. */
    public PlatformApi(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("clean-platform-v1", Context.MODE_PRIVATE);
    }

    /** Returns one page of comic recommendations. */
    public List<Models.Drama> recommended(int page) throws IOException { return recommended(page, "COMIC"); }

    /** Returns category recommendations using the corresponding platform selector. */
    public List<Models.Drama> recommended(int page, String kind) throws IOException {
        checkPage(page); ensureReady();
        String genre;
        if ("COMIC".equals(kind)) genre = "comic_series";
        else if ("REAL".equals(kind)) genre = "short_play";
        else if ("AI".equals(kind)) genre = "ai_series";
        else throw new IOException("未知内容分类");
        JSONObject saved = accountSnapshot();
        try {
            JSONObject body = new JSONObject().put("filter_ids", "").put("req_scene", "default")
                    .put("offset", (page - 1) * 20).put("need_selector_panel", false).put("limit", 20)
                    .put("select_items", new JSONObject().put("genre", new JSONArray().put(genre))
                            .put("sort", new JSONArray().put("hot_score")))
                    .put("session_id", "").put("req_type", "only_content").put("client_req_type", 3);
            return ContentParser.recommended(signed(http, CONTENT + "/reading/distribution/category/landpage/v/",
                    queryFor("73532", "7.3.5.32", saved), body.toString().getBytes(StandardCharsets.UTF_8), saved, true));
        } catch (JSONException error) { throw new IOException("推荐内容格式异常", error); }
    }

    /** Searches the application tabs and retains the official website fallback. */
    public List<Models.Drama> search(String text, int page) throws IOException {
        checkPage(page); ensureReady();
        if (text == null || text.trim().isEmpty() || text.trim().length() > 80) throw new IOException("请输入1至80个字符的剧名");
        String keyword = text.trim();
        LinkedHashMap<String, Models.Drama> result = new LinkedHashMap<>();
        boolean succeeded = false;
        for (int tab : new int[]{19, 11}) {
            try {
                for (Models.Drama item : ContentParser.appSearch(searchTab(keyword, page, tab), tab))
                    if (!result.containsKey(item.id)) result.put(item.id, item);
                succeeded = true;
            } catch (IOException error) {
                if (Thread.currentThread().isInterrupted() || closed.get()) throw new IOException("请求已取消");
                Log.w("HGTV-Clean", "stage=search tab=" + tab + " success=false type=" + error.getClass().getSimpleName());
            }
        }
        if (succeeded) return new ArrayList<>(result.values());
        Log.i("HGTV-Clean", "stage=search fallback=official_web");
        return webSearch(keyword, page);
    }

    private JSONObject searchTab(String keyword, int page, int tab) throws IOException {
        JSONObject saved = accountSnapshot();
        LinkedHashMap<String, String> values = queryFor("72232", "7.2.2.32", saved);
        values.put("query", keyword); values.put("count", "20"); values.put("offset", Integer.toString((page - 1) * 20));
        values.put("tab_type", Integer.toString(tab)); values.put("bookshelf_search_plan", "4");
        values.put("use_correct", "true"); values.put("user_is_login", hasSavedLogin() ? "1" : "0");
        if (page == 1) synchronized (searchCursors) {
            Iterator<String> keys = searchCursors.keySet().iterator();
            while (keys.hasNext()) if (keys.next().startsWith(keyword + ":" + tab + ":")) keys.remove();
        }
        JSONObject cursor = searchCursors.get(keyword + ":" + tab + ":" + (page - 1));
        if (cursor != null && System.currentTimeMillis() - cursor.optLong("saved") < 300000) {
            for (String key : new String[]{"search_id", "passback"})
                if (!cursor.optString(key).isEmpty()) values.put(key, cursor.optString(key));
            String offset = cursor.optString("next_offset");
            if (offset.matches("[0-9]{1,9}")) values.put("offset", offset);
        } else if (tab == 19 && page > 1) throw new IOException("搜索分页已失效，请重新搜索。");
        JSONObject response = signed(http, ACCOUNT + "/reading/bookapi/search/tab/v", values, null, saved, false);
        try {
            JSONArray tabs = response.optJSONArray("search_tabs");
            if (tabs == null && response.optJSONObject("data") != null) tabs = response.getJSONObject("data").optJSONArray("search_tabs");
            if (tabs != null) for (int i = 0; i < tabs.length(); i++) {
                JSONObject item = tabs.optJSONObject(i);
                if (item != null && item.optInt("tab_type") == tab) {
                    JSONObject next = new JSONObject().put("search_id", item.optString("search_id"))
                            .put("passback", item.optString("passback")).put("next_offset", item.optString("next_offset"))
                            .put("saved", System.currentTimeMillis());
                    synchronized (searchCursors) {
                        searchCursors.put(keyword + ":" + tab + ":" + page, next);
                        while (searchCursors.size() > 100) searchCursors.remove(searchCursors.keySet().iterator().next());
                    }
                    break;
                }
            }
        } catch (JSONException error) { throw new IOException("搜索分页格式异常", error); }
        return response;
    }

    private List<Models.Drama> webSearch(String keyword, int page) throws IOException {
        String text = keyword.substring(0, Math.min(30, keyword.length()));
        List<Future<List<Models.Drama>>> tasks = new ArrayList<>();
        for (int i = (page - 1) * 10 + 1; i <= page * 10; i++) {
            final int physicalPage = i;
            tasks.add(searchWorkers.submit(() -> {
                IOException failure = null;
                for (int attempt = 0; attempt < 3; attempt++) {
                    if (attempt > 0) pause(200L * attempt);
                    try {
                        String address = WEBSITE + "/api/search/result?query=" + ProtocolHttp.encode(text).replace("+", "%20").replace("%7E", "~") + "&page=" + physicalPage;
                        Map<String, String> headers = new LinkedHashMap<>();
                        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
                        headers.put("Accept", "application/json"); headers.put("Accept-Language", "zh-CN,zh;q=0.9");
                        return ContentParser.webSearch(http.request("GET", address, headers, null, 2 * 1024 * 1024).json());
                    } catch (IOException error) { failure = error; if (Thread.currentThread().isInterrupted()) throw error; }
                }
                throw failure == null ? new IOException("官网搜索暂不可用") : failure;
            }));
        }
        LinkedHashMap<String, Models.Drama> result = new LinkedHashMap<>(); boolean succeeded = false;
        try {
            for (Future<List<Models.Drama>> task : tasks) {
                try {
                    for (Models.Drama item : task.get()) if (!result.containsKey(item.id)) result.put(item.id, item);
                    succeeded = true;
                }
                catch (java.util.concurrent.ExecutionException error) { Log.w("HGTV-Clean", "stage=webSearch pageFailed=true"); }
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("请求已取消", error); }
        finally { for (Future<?> task : tasks) if (!task.isDone()) task.cancel(true); }
        if (!succeeded) throw new IOException("官网搜索暂不可用，请稍后重试。");
        return new ArrayList<>(result.values());
    }

    /** Reads validated episode order without uploading progress. */
    public List<Models.Episode> episodes(Models.Drama drama) throws IOException {
        if (drama == null || !validId(drama.id)) throw new IOException("无效剧集编号");
        ensureReady(); JSONObject saved = accountSnapshot();
        LinkedHashMap<String, String> values = queryFor("72232", "7.2.2.32", saved); values.put("series_id", drama.id);
        return ContentParser.episodes(signed(http, CONTENT + "/novel/player/video_detail/v1/", values,
                "{}".getBytes(StandardCharsets.UTF_8), saved, false));
    }

    /** Prepares one cancellable source owned by the current player generation. */
    public Models.Playback play(Models.Episode episode, int quality) throws IOException {
        if (episode == null || !validId(episode.id)) throw new IOException("无效视频编号");
        if (quality != 480 && quality != 720 && quality != 1080) throw new IOException("不支持的清晰度");
        ensureReady();
        if (!hasRegisteredDevice() && automaticRegistrationAttempted.compareAndSet(false, true)) initializeDevice();
        if (!hasRegisteredDevice()) throw new DeviceInitializationFailure(null);
        PlaybackAttempt attempt = new PlaybackAttempt();
        PlaybackAttempt previous = activePlayback.getAndSet(attempt); if (previous != null) previous.close();
        boolean transferred = false;
        try {
            int level = deviceLevel();
            JSONObject biz = new JSONObject().put("detail_page_version", 0).put("device_level", level).put("disable_digg_stat", false)
                    .put("need_all_video_definition", true).put("need_mp4_align", false).put("use_os_player", false)
                    .put("use_server_dns", false).put("video_platform", 1024);
            JSONObject multi = new JSONObject().put("biz_param", biz)
                    .put("mixed_video_id_map", new JSONObject().put("1004", new JSONArray().put(episode.id)));
            JSONObject single = new JSONObject().put("video_id", episode.id).put("biz_param", biz);
            JSONObject model = null; IOException last = null;
            JSONObject saved = accountSnapshot();
            String[] paths = {"/novel/player/multi_video_model/v1/", "/novel/player/video_model/v1/"};
            JSONObject[] bodies = {multi, single};
            for (int i = 0; i < paths.length; i++) {
                int limit = i == 0 ? 3 : 1;
                for (int retry = 0; retry < limit; retry++) {
                    attempt.check();
                    LinkedHashMap<String, String> modelProfile = profileFor(saved);
                    String registered = deviceId;
                    modelProfile.put("device_id", modelDevices.current(registered));
                    try {
                        JSONObject response = signed(attempt.http, CONTENT + paths[i], modelProfile,
                                bodies[i].toString().getBytes(StandardCharsets.UTF_8), saved, false);
                        JSONObject data = response.optJSONObject("data");
                        model = data == null ? null : object(data.opt("video_model"));
                        if (model == null && data != null) {
                            Iterator<String> keys = data.keys();
                            while (keys.hasNext() && model == null) {
                                JSONObject item = data.optJSONObject(keys.next());
                                if (item != null) model = object(item.opt("video_model"));
                            }
                        }
                        if (model != null) break;
                        throw new IOException("播放接口没有返回视频模型");
                    } catch (EmptyResponse error) {
                        last = error; modelDevices.reject(registered);
                        if (retry + 1 < limit) pause(200);
                    } catch (IOException error) { last = error; break; }
                }
                if (model != null) break;
            }
            if (model == null) throw last == null ? new IOException("播放源暂不可用") : last;
            String fallback = fallbackAddress(model.opt("fallback_api"));
            JSONObject info = attempt.http.request("GET", fallback,
                    Collections.singletonMap("User-Agent", RequestSigner.userAgent(profile(), true)), null, 16 * 1024 * 1024)
                    .json().optJSONObject("video_info");
            JSONObject data = info == null ? null : info.optJSONObject("data");
            if (data == null) throw new IOException("没有播放资源");
            MediaAddress address = MediaAddress.resolve(data, quality, level);
            PreparedMedia media = new PreparedMedia(address.url, address.key, RequestSigner.userAgent(profile(), true));
            attempt.media = media; attempt.check(); media.prepare(); attempt.check();
            transferred = true;
            return new Models.Playback(media, address.quality);
        } catch (JSONException error) { throw new IOException("播放响应格式异常", error); }
        finally {
            activePlayback.compareAndSet(attempt, null); attempt.http.close();
            if (!transferred && attempt.media != null) attempt.media.close();
        }
    }

    /** Cancels current source preparation without changing the attached source or saved session. */
    public void cancelPlayback() { PlaybackAttempt attempt = activePlayback.getAndSet(null); if (attempt != null) attempt.close(); }

    /** Atomically saves a successful device registration while retaining the account session. */
    public synchronized void initializeDevice() throws IOException {
        ensureReady();
        try {
            JSONObject record = DeviceRegistration.register(http, new LinkedHashMap<>(localProfile));
            String device = record.optString("device_id"), install = record.optString("iid");
            if (!registeredId(device) || !registeredId(install)) throw new IOException(DEVICE_FAILURE);
            Map<String, String> cookies = readMap(record.getJSONObject("cookies"));
            String before = preferences.getString("registered_device", "");
            String oldAccount = preferences.getString("qr_account", ""), nextAccount = oldAccount;
            if (account != null) {
                JSONObject updated = new JSONObject(account.toString());
                Map<String, String> nextProfile = profile(); nextProfile.put("device_id", device); nextProfile.put("iid", install);
                updated.put("d", device).put("e", install).put("i", new JSONObject(nextProfile)); nextAccount = updated.toString();
            }
            if (closed.get() || Thread.currentThread().isInterrupted()) throw new IOException("请求已取消");
            if (!preferences.edit().putString("registered_device", record.toString()).putString("qr_account", nextAccount).commit()) {
                preferences.edit().putString("registered_device", before).putString("qr_account", oldAccount).commit();
                throw new IOException(DEVICE_FAILURE);
            }
            deviceId = device; installId = install; deviceCookies = cookies;
            if (!nextAccount.isEmpty()) account = new JSONObject(nextAccount);
        } catch (Exception error) { throw new DeviceInitializationFailure(error); }
    }

    /** Reports valid local registration without exposing its identifiers. */
    public boolean hasRegisteredDevice() {
        try {
            JSONObject record = new JSONObject(preferences.getString("registered_device", "{}")); readMap(record.getJSONObject("cookies"));
            return registeredId(record.optString("device_id")) && registeredId(record.optString("iid"));
        } catch (Exception ignored) { return false; }
    }

    /** Starts an isolated QR attempt without inheriting the saved account cookies. */
    public synchronized AuthApi startQrLogin() throws IOException {
        ensureReady(); return new AuthApi(context, this, profile(), new LinkedHashMap<>(deviceCookies));
    }

    /** Checks the saved session locally without changing it on failure. */
    public boolean hasSavedLogin() {
        try { validateAccount(new JSONObject(preferences.getString("qr_account", ""))); return true; }
        catch (Exception ignored) { return false; }
    }

    /** Returns only the saved display name. */
    public String savedLoginName() {
        try { return safeName(new JSONObject(preferences.getString("qr_account", "{}")).optString("name")); }
        catch (Exception ignored) { return "已登录账号"; }
    }

    /** Reads account identity without changing login or uploading history. */
    public AccountStatus verifyAccount() throws IOException {
        ensureReady(); JSONObject saved = accountSnapshot();
        return saved == null ? new AccountStatus("尚未登录", false) : verifyAccount(saved);
    }

    private AccountStatus verifyAccount(JSONObject saved) throws IOException {
        JSONObject first = null, second = null;
        try { first = signed(http, ACCOUNT + "/reading/user/info/v1/", queryFor("72232", "7.2.2.32", saved), null, saved, false).optJSONObject("data"); }
        catch (IOException ignored) { /* The second read can still establish account identity. */ }
        try { second = signed(http, ACCOUNT + "/reading/user/basic_info/get/v1/", queryFor("72232", "7.2.2.32", saved), null, saved, false).optJSONObject("data"); }
        catch (IOException ignored) { /* Failure never deletes the saved session. */ }
        if (first != null && registeredId(first.optString("biz_user_id"))) {
            String name = first.optString("user_name");
            if (name.isEmpty() && second != null) name = second.optString("user_name");
            return new AccountStatus(safeName(name.isEmpty() ? saved.optString("name") : name), true);
        }
        throw new IOException("账号验证暂未完成，已保留现有登录。");
    }

    synchronized AccountStatus acceptQrAccount(JSONObject value, String name, AtomicBoolean active) throws IOException {
        ensureReady();
        if (!active.get() || closed.get() || Thread.currentThread().isInterrupted()) throw new IOException("扫码已取消");
        try {
            JSONObject record = new JSONObject(value.toString()).put("name", safeName(name)); validateAccount(record);
            JSONObject candidateProfile = record.getJSONObject("i");
            if (!deviceId.equals(candidateProfile.optString("device_id")) || !installId.equals(candidateProfile.optString("iid")))
                throw new IOException("扫码期间设备资料已变化，请刷新二维码重试。原登录已保留。");
            AccountStatus result;
            try { result = verifyAccount(record); record.put("name", result.name); }
            catch (IOException ignored) { result = new AccountStatus(safeName(name), false); }
            synchronized (active) {
                if (!active.get() || closed.get() || Thread.currentThread().isInterrupted()) throw new IOException("扫码已取消");
                String previous = preferences.getString("qr_account", "");
                if (!preferences.edit().putString("qr_account", record.toString()).commit()) {
                    preferences.edit().putString("qr_account", previous).commit();
                    throw new IOException("登录保存未完成，已保留原状态。");
                }
                account = record;
            }
            return result;
        } catch (JSONException error) { throw new IOException("扫码会话保存未完成，已保留原登录。", error); }
    }

    /** Display-only account validation result. */
    public static final class AccountStatus {
        public final String name; public final boolean verified;
        /** Creates a result without exposing session credentials. */
        public AccountStatus(String name, boolean verified) { this.name = name; this.verified = verified; }
    }

    /** Cancels work without deleting any preferences or login state. */
    @Override public void close() { if (closed.compareAndSet(false, true)) { cancelPlayback(); http.close(); searchWorkers.shutdownNow(); } }

    private synchronized void ensureReady() throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("内容请求不能在界面线程执行");
        if (closed.get()) throw new IOException("内容连接已关闭"); if (ready) return;
        try {
            localProfile = readMap(new JSONObject(preferences.getString("profile", "{}")));
            if (localProfile.isEmpty()) {
                localProfile.put("cdid", UUID.randomUUID().toString()); localProfile.put("clientudid", UUID.randomUUID().toString());
                byte[] random = new byte[8]; new SecureRandom().nextBytes(random); StringBuilder hex = new StringBuilder();
                for (byte b : random) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                localProfile.put("openudid", hex.toString()); localProfile.put("device_type", Build.MODEL);
                localProfile.put("device_brand", Build.BRAND); localProfile.put("os_version", Build.VERSION.RELEASE);
                localProfile.put("os_api", Integer.toString(Build.VERSION.SDK_INT)); localProfile.put("ac", "wifi");
                if (!preferences.edit().putString("profile", new JSONObject(localProfile).toString()).commit()) throw new IOException("本机资料保存失败");
            }
            try {
                JSONObject record = new JSONObject(preferences.getString("registered_device", "{}"));
                if (registeredId(record.optString("device_id")) && registeredId(record.optString("iid"))) {
                    deviceId = record.getString("device_id"); installId = record.getString("iid"); deviceCookies = readMap(record.getJSONObject("cookies"));
                }
            } catch (JSONException ignored) { Log.w("HGTV-Clean", "stage=deviceRestore valid=false"); }
            try { JSONObject saved = new JSONObject(preferences.getString("qr_account", "")); validateAccount(saved); account = saved; }
            catch (JSONException | IOException ignored) { Log.i("HGTV-Clean", "stage=accountRestore saved=false"); }
            ready = true;
        } catch (JSONException error) { throw new IOException("本机连接资料格式异常，已保留原资料。", error); }
    }

    private synchronized LinkedHashMap<String, String> profile() throws IOException { return profileFor(account); }

    private synchronized LinkedHashMap<String, String> profileFor(JSONObject selectedAccount) throws IOException {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        String[][] defaults = {{"iid", "0"}, {"device_id", "0"}, {"ac", "wifi"}, {"channel", "update_64"}, {"aid", "8662"},
                {"app_name", "novelread"}, {"version_code", "71332"}, {"version_name", "7.1.3.32"}, {"device_platform", "android"},
                {"os", "android"}, {"ssmix", "a"}, {"device_type", Build.MODEL}, {"device_brand", Build.BRAND}, {"language", "zh"},
                {"os_api", Integer.toString(Build.VERSION.SDK_INT)}, {"os_version", Build.VERSION.RELEASE}, {"manifest_version_code", "71332"},
                {"resolution", context.getResources().getDisplayMetrics().widthPixels + "*" + context.getResources().getDisplayMetrics().heightPixels},
                {"dpi", Integer.toString(context.getResources().getDisplayMetrics().densityDpi)}, {"update_version_code", "71332"},
                {"host_abi", "arm64-v8a"}, {"dragon_device_type", "phone"}, {"pv_player", "71332"}, {"compliance_status", "0"},
                {"need_personal_recommend", "1"}, {"player_so_load", "1"}, {"is_android_pad_screen", "0"}};
        for (String[] entry : defaults) result.put(entry[0], entry[1]);
        result.putAll(localProfile); result.put("device_id", deviceId); result.put("iid", installId);
        if (selectedAccount != null) try {
            for (Map.Entry<String, String> entry : readMap(selectedAccount.getJSONObject("i")).entrySet())
                if (!entry.getValue().trim().isEmpty()) result.put(entry.getKey(), entry.getValue());
        } catch (JSONException error) { throw new IOException("扫码设备资料格式异常", error); }
        return result;
    }

    private LinkedHashMap<String, String> queryFor(String code, String name, JSONObject selectedAccount) throws IOException {
        LinkedHashMap<String, String> values = profileFor(selectedAccount);
        values.put("ac", "wifi"); values.put("aid", "8662"); values.put("app_name", "novelread"); values.put("ssmix", "a");
        values.put("device_platform", "android"); values.put("os", "android"); values.put("language", "zh");
        values.put("compliance_status", "0"); values.put("need_personal_recommend", "1"); values.put("player_so_load", "1"); values.put("host_abi", "arm64-v8a");
        for (String key : new String[]{"version_code", "manifest_version_code", "update_version_code", "pv_player"}) values.put(key, code);
        values.put("version_name", name); return values;
    }

    private JSONObject signed(ProtocolHttp transport, String address, LinkedHashMap<String, String> values, byte[] body,
                              JSONObject savedAccount, boolean category) throws IOException {
        boolean model = address.endsWith("/video_model/v1/") || address.endsWith("/multi_video_model/v1/");
        Map<String, String> signingProfile = model ? new LinkedHashMap<>(values) : profileFor(savedAccount);
        RequestSigner.Signed signed = RequestSigner.signed(address, values, body, signingProfile);
        Map<String, String> headers = new LinkedHashMap<>(signed.headers);
        headers.put("User-Agent", RequestSigner.userAgent(signingProfile, body != null && !category));
        headers.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9"); headers.put("X-SS-DP", "8662");
        headers.put("X-Tt-Store-Region", "cn"); headers.put("X-Tt-Store-Region-Src", "did");
        headers.put("X-XS-From-Web", "0"); headers.put("Sdk-Version", "2"); headers.put("passport-sdk-version", "5051452");
        if (body != null) headers.put("Content-Type", "application/json; charset=utf-8");
        Map<String, String> cookies; synchronized (this) { cookies = new LinkedHashMap<>(deviceCookies); }
        if (savedAccount != null) {
            cookies = AuthSession.parseCookies(savedAccount.optString("a"));
            String token = savedAccount.optString("b"), session = cookies.get("sessionid");
            boolean player = new URL(address).getPath().startsWith("/novel/player/");
            // The model flow uses the account Cookie without adding the separate account-token header.
            if (!model && !token.isEmpty() && (player || token.length() < 99 || session == null || session.isEmpty() || token.contains(session))) headers.put("x-tt-token", token);
        }
        if (!cookies.isEmpty()) headers.put("Cookie", ProtocolHttp.cookie(cookies));
        ProtocolHttp.Response response = transport.request(signed.method, signed.url, headers, signed.body, 16 * 1024 * 1024);
        if (address.endsWith("_model/v1/")) Log.i("HGTV-Clean", "stage=model http=" + response.status + " bytes=" + response.body.length);
        if (response.status >= 200 && response.status < 300 && response.body.length == 0) throw new EmptyResponse();
        JSONObject json = response.json();
        int code = json.optInt("code", json.optInt("Code", 0));
        if (code != 0) throw new IOException("平台接口暂不可用（" + code + "）");
        return json;
    }

    private synchronized JSONObject accountSnapshot() throws IOException {
        try { return account == null ? null : new JSONObject(account.toString()); }
        catch (JSONException error) { throw new IOException("本机账号资料格式异常", error); }
    }

    private static Map<String, String> readMap(JSONObject object) throws IOException {
        Map<String, String> result = new LinkedHashMap<>(); Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next(); Object value = object.opt(key);
            if (!(value instanceof String) || ((String) value).length() > 32768) throw new IOException("本机连接资料格式异常");
            result.put(key, (String) value);
        }
        return result;
    }

    private static void validateAccount(JSONObject record) throws IOException, JSONException {
        String cookie = record.getString("a");
        if (!AuthSession.headerValue(cookie, 32768) || (!cookie.contains("sessionid=") && !cookie.contains("sessionid_ss="))) throw new IOException("扫码会话内容不完整");
        Map<String, String> cookies = AuthSession.parseCookies(cookie);
        if ((cookies.get("sessionid") == null || cookies.get("sessionid").isEmpty())
                && (cookies.get("sessionid_ss") == null || cookies.get("sessionid_ss").isEmpty()))
            throw new IOException("扫码会话内容不完整");
        for (String key : new String[]{"b", "c"}) {
            String value = record.getString(key);
            if (value.length() > 16384 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0)
                throw new IOException("扫码会话结构异常");
        }
        Map<String, String> profile = readMap(record.getJSONObject("i"));
        if (!validId(profile.get("device_id")) || !validId(profile.get("iid"))) throw new IOException("扫码设备资料无效");
    }

    private static JSONObject object(Object value) {
        if (value instanceof JSONObject) return (JSONObject) value;
        if (value instanceof String) try { return new JSONObject((String) value); } catch (JSONException ignored) { }
        return null;
    }
    private static String fallbackAddress(Object value) throws IOException {
        if (value instanceof JSONArray) value = ((JSONArray) value).opt(0);
        JSONObject object = object(value); String address = object == null ? String.valueOf(value) : object.optString("fallback_api");
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) throw new IOException("播放地址格式异常");
        return address;
    }
    private static int deviceLevel() {
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) if ("video/hevc".equalsIgnoreCase(type)
                        && codec.getCapabilitiesForType(type).getVideoCapabilities().getSupportedHeights().getUpper() > 0) return 4;
            }
        } catch (RuntimeException ignored) { /* Missing HEVC support selects the AVC-compatible profile. */ }
        return 3;
    }
    private static void pause(long millis) throws IOException {
        try { Thread.sleep(millis); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("请求已取消", error); }
    }
    private static void checkPage(int page) throws IOException { if (page < 1 || page > 100) throw new IOException("页码必须在1至100之间"); }
    private static boolean validId(String value) { return value != null && value.matches("[0-9]{1,30}"); }
    private static boolean registeredId(String value) { return validId(value) && value.matches(".*[1-9].*"); }
    private static String safeName(String value) { String text = value == null ? "" : value.trim(); return text.isEmpty() ? "已登录账号" : text.substring(0, Math.min(80, text.length())); }

    private static final class PlaybackAttempt implements Closeable {
        final ProtocolHttp http = new ProtocolHttp(); volatile PreparedMedia media; volatile boolean cancelled;
        void check() throws IOException { if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("请求已取消"); }
        @Override public void close() { cancelled = true; http.close(); PreparedMedia source = media; if (source != null) source.close(); }
    }
    private static final class EmptyResponse extends IOException { EmptyResponse() { super("平台接口返回空响应"); } }
    private static final class DeviceInitializationFailure extends IOException { DeviceInitializationFailure(Throwable cause) { super(DEVICE_FAILURE, cause); } }

    /** Returns local display text without disclosing server messages, media URLs or credentials. */
    public static String userMessage(Throwable error, String fallback) {
        for (int i = 0; error != null && i < 5; i++, error = error.getCause()) {
            if (error instanceof DeviceInitializationFailure) return DEVICE_FAILURE;
            if (error instanceof EmptyResponse) return "播放接口返回空响应，暂时无法取得视频源。请稍后重试。";
        }
        return fallback;
    }
}
