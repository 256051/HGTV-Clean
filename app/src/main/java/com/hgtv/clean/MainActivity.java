package com.hgtv.clean;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.LruCache;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Ad-free television and touch catalogue, independent of the original Activity. */
public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService catalogue = Executors.newFixedThreadPool(2, r -> new Thread(r, "clean-catalogue"));
    private final ExecutorService images = Executors.newFixedThreadPool(3, r -> new Thread(r, "clean-artwork"));
    private final LruCache<String, Bitmap> covers = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private PlatformApi api;
    private LocalLibrary library;
    private LinearLayout content;
    private TextView pageTitle, message;
    private PlayerScreen player;
    private QrLoginScreen loginScreen;
    private int generation, page = 1;
    private Future<?> request;
    private String destination = "home", query = "", savedHeading = "精选推荐";
    private String category = "COMIC";
    private List<Models.Drama> lastItems = new ArrayList<>();
    private List<Models.Episode> detailEpisodes = Collections.emptyList();
    private Models.Drama detailDrama;

    /** Starts an isolated client; no original session or external storage is read. */
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        library = new LocalLibrary(this);
        api = new PlatformApi(getApplicationContext());
        showCatalogue("home");
        if (android.os.Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
    }

    private void shell(String title) {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 760;
        LinearLayout root = new LinearLayout(this); root.setBackgroundColor(Ui.BG);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 14));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(Ui.dp(this, 20) + insets.getSystemWindowInsetLeft(), Ui.dp(this, 14) + insets.getSystemWindowInsetTop(), Ui.dp(this, 20) + insets.getSystemWindowInsetRight(), Ui.dp(this, 14) + insets.getSystemWindowInsetBottom()); return insets;
        });
        LinearLayout navigation = new LinearLayout(this); navigation.setOrientation(LinearLayout.VERTICAL);
        TextView brand = Ui.text(this, "HGTV", wide ? 28 : 22, Ui.TEXT); brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        navigation.addView(brand); TextView clean = Ui.text(this, "C L E A N", 12, Ui.ACCENT);
        clean.setPadding(0, 0, 0, Ui.dp(this, 22)); navigation.addView(clean);
        if (!wide) clean.setVisibility(View.GONE);
        LinearLayout links = new LinearLayout(this); links.setOrientation(wide ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        String[] names = wide ? new String[]{"精选推荐", "搜索", "本地记录", "设置"} : new String[]{"精选", "搜索", "记录", "设置"};
        String[] keys = {"home", "search", "library", "settings"};
        for (int i = 0; i < names.length; i++) {
            final String key = keys[i]; Button b = Ui.button(this, names[i], v -> showCatalogue(key));
            if (!wide) { b.setTextSize(14); b.setSingleLine(true); b.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0); }
            LinearLayout.LayoutParams p = wide ? new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)) : new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1);
            p.setMargins(0, 0, Ui.dp(this, 6), Ui.dp(this, 10)); links.addView(b, p);
        }
        navigation.addView(links);
        root.addView(navigation, new LinearLayout.LayoutParams(wide ? Ui.dp(this, 156) : -1, wide ? -1 : -2));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(wide ? Ui.dp(this, 20) : 0, Ui.dp(this, 6), 0, Ui.dp(this, 20));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1));
        pageTitle = Ui.text(this, title, 28, Ui.TEXT); pageTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD); content.addView(pageTitle);
        TextView subtitle = Ui.text(this, "看故事，让播放保持简单", 14, Ui.MUTED);
        subtitle.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 20)); content.addView(subtitle);
        setContentView(root);
    }

    private void cancelCatalogue() { generation++; if (request != null) request.cancel(true); }
    private void showCatalogue(String key) {
        cancelCatalogue(); destination = key; detailDrama = null; page = 1;
        if (key.equals("search")) { searchPage(); return; }
        if (key.equals("settings")) { settingsPage(); return; }
        if (key.equals("library")) { libraryPage(); return; }
        homePage();
    }

    private void homePage() {
        shell("精选推荐");
        LinearLayout types = new LinearLayout(this);
        String[] names = {"漫剧", "真人短剧", "AI 短剧"};
        String[] values = {"COMIC", "REAL", "AI"};
        for (int i = 0; i < names.length; i++) {
            final String value = values[i];
            types.addView(Ui.button(this, (category.equals(value) ? "✓ " : "") + names[i], v -> {
                category = value; page = 1; homePage();
            }));
        }
        content.addView(types); load(false, true);
    }

    private void searchPage() {
        shell("找到想看的故事");
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(this); input.setText(query); input.setTextColor(Ui.TEXT); input.setTextSize(18);
        input.setHint("输入剧名或关键词"); input.setHintTextColor(Ui.MUTED); input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT); input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        row.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1));
        Button submit = Ui.button(this, "搜索", v -> submitSearch(input)); row.addView(submit);
        input.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { submitSearch(input); return true; } return false; });
        content.addView(row); message = Ui.text(this, "输入关键词后开始搜索", 16, Ui.MUTED); content.addView(message);
    }
    private void submitSearch(EditText input) {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) { input.setError("请输入关键词"); return; }
        if (value.length() > 80) { input.setError("关键词请控制在80字以内"); return; }
        query = value; page = 1;
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
        shell("搜索 · " + query); load(true, true);
    }

    private void load(boolean search, boolean first) {
        cancelCatalogue(); final int ticket = generation;
        message = Ui.text(this, search ? "正在搜索…" : "正在获取推荐…", 16, Ui.MUTED); content.addView(message);
        request = catalogue.submit(() -> {
            try {
                List<Models.Drama> result = search ? api.search(query, page) : api.recommended(page, category);
                main.post(() -> {
                    if (ticket != generation || isFinishing()) return;
                    content.removeView(message); lastItems = new ArrayList<>(result);
                    savedHeading = search ? "搜索 · " + query : "精选推荐";
                    if (result.isEmpty()) { content.addView(Ui.text(this, "暂时没有结果，试试其他关键词或稍后重试。", 16, Ui.MUTED)); return; }
                    renderGrid(result);
                    LinearLayout pages = new LinearLayout(this); pages.setGravity(Gravity.CENTER);
                    if (page > 1) pages.addView(Ui.button(this, "上一页", v -> { page--; shell(savedHeading); load(search, false); }));
                    TextView current = Ui.text(this, "  第 " + page + " 页  ", 15, Ui.MUTED); pages.addView(current);
                    pages.addView(Ui.button(this, "下一页", v -> { page++; shell(savedHeading); load(search, false); }));
                    content.addView(pages);
                });
            } catch (Exception e) { main.post(() -> { if (ticket == generation && !isFinishing()) showFailure("内容暂时未能加载", e, () -> { shell(savedHeading); load(search, first); }); }); }
        });
    }

    private void renderGrid(List<Models.Drama> dramas) {
        int sw = getResources().getConfiguration().screenWidthDp;
        int available = sw - (sw >= 760 ? 220 : 40);
        int columns = Math.max(2, Math.min(5, available / 165));
        int width = Ui.dp(this, available / columns - 12);
        GridLayout grid = new GridLayout(this); grid.setColumnCount(columns);
        grid.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 20));
        for (Models.Drama drama : dramas) {
            LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL); tile.setPadding(7, 7, 7, Ui.dp(this, 10));
            tile.setBackground(Ui.focusBackground()); tile.setFocusable(true); tile.setClickable(true);
            tile.setContentDescription(drama.title + "，" + drama.subtitle); tile.setOnClickListener(v -> openDetails(drama));
            ImageView poster = new ImageView(this); poster.setScaleType(ImageView.ScaleType.CENTER_CROP); poster.setBackgroundColor(Ui.CARD);
            poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(poster, new LinearLayout.LayoutParams(-1, Math.round(width * 1.27f)));
            TextView name = Ui.text(this, drama.title, 16, Ui.TEXT); name.setMaxLines(2); name.setPadding(8, 12, 8, 4); tile.addView(name);
            TextView meta = Ui.text(this, drama.episodeCount > 0 ? drama.episodeCount + " 集  ·  " + drama.subtitle : drama.subtitle, 12, Ui.MUTED);
            meta.setMaxLines(1); meta.setPadding(8, 0, 8, 0); tile.addView(meta);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams(); p.width = width; p.height = -2; p.setMargins(0, 0, Ui.dp(this, 12), Ui.dp(this, 14)); grid.addView(tile, p);
            cover(poster, drama.cover);
        }
        content.addView(grid);
    }

    private void cover(ImageView image, String location) {
        if (location == null || location.isEmpty()) return;
        Bitmap cached = covers.get(location); if (cached != null) { image.setImageBitmap(cached); return; }
        images.submit(() -> {
            HttpURLConnection connection = null;
            try {
                URL u = new URL(location); if (!u.getProtocol().equals("https") && !u.getProtocol().equals("http")) return;
                connection = (HttpURLConnection)u.openConnection(); connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 2;
                Bitmap bitmap = BitmapFactory.decodeStream(connection.getInputStream(), null, options);
                if (bitmap != null) { covers.put(location, bitmap); main.post(() -> { if (!isFinishing()) image.setImageBitmap(bitmap); }); }
            } catch (Exception ignored) { /* Artwork failure must not make content unavailable. */ }
            finally { if (connection != null) connection.disconnect(); }
        });
    }

    private void openDetails(Models.Drama drama) {
        cancelCatalogue(); int ticket = generation; detailDrama = drama;
        shell(drama.title); content.addView(Ui.text(this, drama.subtitle, 16, Ui.MUTED));
        TextView status = Ui.text(this, "正在获取剧集…", 16, Ui.MUTED); content.addView(status);
        request = catalogue.submit(() -> {
            try { List<Models.Episode> result = api.episodes(drama); main.post(() -> {
                if (ticket != generation || isFinishing()) return;
                detailEpisodes = result; content.removeView(status);
                if (result.isEmpty()) { content.addView(Ui.text(this, "暂无可播放剧集", 17, Ui.MUTED)); return; }
                LinearLayout actions = new LinearLayout(this);
                actions.addView(Ui.button(this, "开始播放", v -> play(drama, result, 0)));
                boolean saved = library.contains("favorites", drama.id);
                Button favorite = Ui.button(this, saved ? "取消本地收藏" : "收藏到本机", null);
                favorite.setOnClickListener(v -> { boolean exists = library.contains("favorites", drama.id); library.put("favorites", drama, exists); favorite.setText(exists ? "收藏到本机" : "取消本地收藏"); });
                LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, -2); fp.setMargins(Ui.dp(this, 12), 0, 0, 0); actions.addView(favorite, fp); content.addView(actions);
                TextView label = Ui.text(this, "选集 · 共 " + result.size() + " 集", 19, Ui.TEXT); label.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 12)); content.addView(label);
                GridLayout episodes = new GridLayout(this); int cols = getResources().getConfiguration().screenWidthDp >= 760 ? 8 : 4; episodes.setColumnCount(cols);
                for (int i = 0; i < Math.min(48, result.size()); i++) { final int selected = i; Button b = Ui.button(this, String.valueOf(i + 1), v -> play(drama, result, selected)); b.setContentDescription("播放第 " + (i + 1) + " 集");
                    GridLayout.LayoutParams p = new GridLayout.LayoutParams(); p.width = Ui.dp(this, 66); p.height = Ui.dp(this, 52); p.setMargins(0, 0, Ui.dp(this, 8), Ui.dp(this, 8)); episodes.addView(b, p); }
                content.addView(episodes);
                if (result.size() > 48) content.addView(Ui.button(this, "查看全部 " + result.size() + " 集", v -> {
                    String[] labels = new String[result.size()];
                    for (int i = 0; i < labels.length; i++) labels[i] = "第 " + (i + 1) + " 集";
                    new AlertDialog.Builder(this).setTitle("选择剧集").setItems(labels, (dialog, selected) -> play(drama, result, selected)).show();
                }));
            }); } catch (Exception e) { main.post(() -> { if (ticket == generation) showFailure("剧集暂时未能加载", e, () -> openDetails(drama)); }); }
        });
    }

    private void play(Models.Drama drama, List<Models.Episode> episodes, int index) {
        cancelCatalogue(); library.put("history", drama, false);
        player = new PlayerScreen(this, api, drama, episodes, index, () -> {
            if (player != null) { player.release(); player = null; }
            openDetails(drama);
        });
        setContentView(player.getView());
    }

    private void libraryPage() {
        shell("本地记录"); content.addView(Ui.text(this, "只保存在这台设备，不会写入平台账号", 14, Ui.MUTED));
        List<Models.Drama> history = library.read("history"), favorites = library.read("favorites");
        if (!history.isEmpty()) { content.addView(Ui.text(this, "最近播放", 20, Ui.TEXT)); renderGrid(history); }
        if (!favorites.isEmpty()) { content.addView(Ui.text(this, "本地收藏", 20, Ui.TEXT)); renderGrid(favorites); }
        if (history.isEmpty() && favorites.isEmpty()) content.addView(Ui.text(this, "播放或收藏之后，可以在这里继续。", 17, Ui.MUTED));
    }

    private void settingsPage() {
        shell("设置");
        TextView description = Ui.text(this, "支持官方 App 扫码登录。\n无开屏广告、插屏广告或激励广告。\n播放进度和收藏保存在本机。\n触屏上滑下一集，下滑上一集。\n遥控器左右键快退／快进10秒，返回键退出播放。\n支持 Android TV 与手机布局。", 17, Ui.TEXT);
        description.setLineSpacing(Ui.dp(this, 9), 1); content.addView(description);
        TextView version = Ui.text(this, "HGTV Clean 0.3.0 · 独立客户端", 14, Ui.MUTED); version.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 16)); content.addView(version);
        TextView account = Ui.text(this, api.hasSavedLogin() ? "已保存登录：" + api.savedLoginName() : "尚未登录，设备初始化后也可观看可用内容。", 16, Ui.TEXT);
        account.setPadding(0, 0, 0, Ui.dp(this, 12)); content.addView(account);
        content.addView(Ui.button(this, api.hasSavedLogin() ? "重新扫码登录" : "扫码登录", v -> showQrLogin()));
        if (api.hasSavedLogin()) content.addView(Ui.button(this, "验证账号连接", v -> {
            int ticket = generation; v.setEnabled(false); account.setText("正在验证账号连接…");
            request = catalogue.submit(() -> {
                try {
                    PlatformApi.AccountStatus result = api.verifyAccount();
                    main.post(() -> { if (ticket == generation && !isFinishing()) { v.setEnabled(true); account.setText(result.verified ? "账号连接正常：" + result.name : "账号暂未验证，原登录仍保留。"); } });
                } catch (Exception error) {
                    main.post(() -> { if (ticket == generation && !isFinishing()) { v.setEnabled(true); account.setText("账号暂未验证，原登录仍保留。请稍后重试。"); } });
                }
            });
        }));
        content.addView(Ui.button(this, "查看连接说明", v -> new AlertDialog.Builder(this).setTitle("连接与隐私").setMessage("使用独立的本机设备资料访问内容。不会自动导入其他应用的登录文件，也不会在后台同步收藏或观看进度。平台接口不可用时会显示失败和重试入口。").setPositiveButton("知道了", null).show()));
        TextView connection = Ui.text(this, api.hasRegisteredDevice() ? "本机播放连接已初始化。" : "首次播放会自动初始化本机连接；失败时可在这里重试。", 15, Ui.MUTED);
        connection.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 12)); content.addView(connection);
        content.addView(Ui.button(this, "初始化本机播放连接", v -> {
            int ticket = generation; v.setEnabled(false); connection.setText("正在向平台申请本机设备编号…");
            request = catalogue.submit(() -> {
                try {
                    api.initializeDevice();
                    main.post(() -> { if (ticket == generation && !isFinishing()) { v.setEnabled(true); connection.setText("本机设备连接已准备，可以返回作品重新播放。此操作不登录账号。"); } });
                } catch (Exception error) {
                    main.post(() -> { if (ticket == generation && !isFinishing()) { v.setEnabled(true); connection.setText("设备初始化未完成，请检查网络后重试。现有本机资料和匿名浏览仍保留。"); } });
                }
            });
        }));
    }

    private void showQrLogin() {
        cancelCatalogue(); detailDrama = null;
        loginScreen = new QrLoginScreen(this, api, () -> {
            if (loginScreen != null) { loginScreen.release(); loginScreen = null; }
            showCatalogue("settings");
        });
        setContentView(loginScreen.getView());
    }

    private void showFailure(String title, Exception error, Runnable retry) {
        if (message != null && message.getParent() == content) content.removeView(message);
        content.addView(Ui.text(this, PlatformApi.userMessage(error, title + "。请检查网络后重试。"), 17, Ui.TEXT));
        // Do not display exception messages: a lower layer can include signed URLs.
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
            StringBuilder trace = new StringBuilder("Request failure: ").append(cause.getClass().getName());
            StackTraceElement[] frames = cause.getStackTrace();
            for (int i = 0; i < Math.min(3, frames.length); i++) trace.append(" at ").append(frames[i]);
            Log.w("HGTV-Clean", trace.toString());
        }
        content.addView(Ui.button(this, "重新加载", v -> retry.run()));
    }

    /** Routes remote keys to the active player before normal focus traversal. */
    @android.annotation.SuppressLint("GestureBackNavigation") // Gestures use the API 33 callback; TV and older Android still need hardware back keys.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) navigateBack();
            return true;
        }
        if (player != null && player.handleKey(event.getKeyCode(), event)) return true;
        return super.dispatchKeyEvent(event);
    }
    /** Stops playback when navigating back; browsing state stays local. */
    private void navigateBack() {
        if (loginScreen != null) { loginScreen.release(); loginScreen = null; showCatalogue("settings"); return; }
        if (player != null) { PlayerScreen old = player; player = null; old.release(); openDetails(detailDrama); return; }
        if (detailDrama != null) {
            detailDrama = null;
            if (destination.equals("home")) homePage();
            else if (destination.equals("library")) libraryPage();
            else { shell(savedHeading); load(true, false); }
            return;
        }
        if (!destination.equals("home")) { showCatalogue("home"); return; }
        finish();
    }
    /** Avoids rebuilding or duplicating an active player on screen rotation. */
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (player == null && loginScreen == null) { if (detailDrama != null) openDetails(detailDrama); else showCatalogue(destination); }
    }
    /** Pauses foreground playback without posting any server progress writes. */
    @Override protected void onStop() { super.onStop(); if (player != null) player.pause(); }
    /** Releases the three independent work lanes and the player. */
    @Override protected void onDestroy() {
        if (loginScreen != null) loginScreen.release(); if (player != null) player.release(); cancelCatalogue(); api.close(); catalogue.shutdownNow(); images.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
