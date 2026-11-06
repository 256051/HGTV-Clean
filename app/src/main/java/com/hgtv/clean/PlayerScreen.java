package com.hgtv.clean;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** TV and touch playback with isolated source preparation and local-only resume state. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerScreen {
    private static final int BG = Color.rgb(14, 18, 25);
    private static final int TEXT = Color.rgb(242, 246, 250);
    private static final int MUTED = Color.rgb(177, 190, 205);
    private static final int ACCENT = Color.rgb(80, 216, 209);
    private static final ExecutorService CLOSER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "clean-source-close");
        thread.setDaemon(true);
        return thread;
    });

    private final Activity activity;
    private final PlatformApi api;
    private final Models.Drama drama;
    private final List<Models.Episode> episodes;
    private final Runnable onExit;
    private final FrameLayout root;
    private final PlayerView playerView;
    private final ExoPlayer player;
    private final LinearLayout loading;
    private final ProgressBar spinner;
    private final TextView status;
    private final TextView title;
    private final TextView position;
    private final Button retry;
    private final Button pause;
    private final Button qualityButton;
    private final Button speedButton;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private final int initialSystemUiVisibility;
    private final ExecutorService sourceExecutor = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "clean-playback-source"));
    private Future<?> pending;
    private Runnable timeout;
    private Models.Playback current;
    private long generation;
    private long requestedPosition;
    private int index;
    private int quality;
    private float speed = 1f;
    private boolean released;
    private boolean preparing;
    private boolean sourceAttached;
    private boolean controlsVisible = true;

    /** Builds the player. The activity owns navigation and must display getView() and call release(). */
    public PlayerScreen(Activity activity, PlatformApi api, Models.Drama drama,
                        List<Models.Episode> episodes, int index, Runnable onExit) {
        if (episodes == null || episodes.isEmpty()) throw new IllegalArgumentException("No episodes");
        this.activity = activity;
        this.api = api;
        this.drama = drama;
        this.episodes = episodes;
        this.index = Math.max(0, Math.min(index, episodes.size() - 1));
        this.onExit = onExit;
        this.preferences = activity.getSharedPreferences("clean-playback", Activity.MODE_PRIVATE);
        initialSystemUiVisibility = activity.getWindow().getDecorView().getSystemUiVisibility();
        this.quality = preferences.getInt("quality", 480);
        if (quality != 480 && quality != 720 && quality != 1080) quality = 480;
        root = new EpisodeTouchFrame();
        root.setBackgroundColor(BG);
        root.setFocusableInTouchMode(true);
        player = new ExoPlayer.Builder(activity).build();
        playerView = new PlayerView(activity);
        // The renderer has one media item; the UI's next/previous commands use the drama's episode list.
        playerView.setPlayer(new ForwardingPlayer(player) {
            @Override public boolean isCommandAvailable(int command) {
                if (command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    return PlayerScreen.this.index + 1 < episodes.size();
                if (command == Player.COMMAND_SEEK_TO_PREVIOUS || command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    return PlayerScreen.this.index > 0;
                return super.isCommandAvailable(command);
            }

            @Override public Player.Commands getAvailableCommands() {
                Player.Commands.Builder commands = super.getAvailableCommands().buildUpon();
                for (int command : new int[]{Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM}) {
                    commands.remove(command).addIf(command, isCommandAvailable(command));
                }
                return commands.build();
            }

            @Override public void seekToNext() { changeEpisode(1); }
            @Override public void seekToNextMediaItem() { changeEpisode(1); }
            @Override public void seekToPrevious() { changeEpisode(-1); }
            @Override public void seekToPreviousMediaItem() { changeEpisode(-1); }
        });
        View next = playerView.findViewById(androidx.media3.ui.R.id.exo_next);
        View previous = playerView.findViewById(androidx.media3.ui.R.id.exo_prev);
        if (next != null) next.setContentDescription("下一集");
        if (previous != null) previous.setContentDescription("上一集");
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(4000);
        playerView.setControllerAnimationEnabled(false);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(12), dp(20), dp(10));
        header.setBackgroundColor(0xC9141820);
        title = text(19, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        position = text(13, MUTED);
        header.addView(title);
        header.addView(position);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(header, hp);

        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(12), dp(6), dp(12), dp(8));
        controls.setBackgroundColor(0xEE141820);
        controls.addView(button("返回", v -> exit()));
        controls.addView(button("选集", v -> chooseEpisode()));
        controls.addView(button("−10 秒", v -> seek(-10000)));
        pause = button("暂停", v -> togglePause());
        controls.addView(pause);
        controls.addView(button("+10 秒", v -> seek(10000)));
        qualityButton = button(quality + "P", v -> chooseQuality());
        controls.addView(qualityButton);
        speedButton = button("1×", v -> chooseSpeed());
        controls.addView(speedButton);
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setContentDescription("播放控制，左右滑动查看更多操作");
        scroll.setFillViewport(true);
        scroll.addView(controls);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        // Keep the custom bars on Media3's existing pause/error/touch-aware controller timer.
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            controlsVisible = visibility == View.VISIBLE;
            header.setVisibility(visibility);
            scroll.setVisibility(visibility);
            if (!controlsVisible) root.requestFocus();
        });
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            header.setPadding(dp(20) + insets.getSystemWindowInsetLeft(),
                    dp(12) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(), dp(10));
            scroll.setPadding(insets.getSystemWindowInsetLeft(), 0,
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        loading = new LinearLayout(activity);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(dp(24), dp(18), dp(24), dp(18));
        loading.setBackground(shape(0xF01B2330, 0));
        spinner = new ProgressBar(activity);
        loading.addView(spinner, new LinearLayout.LayoutParams(dp(40), dp(40)));
        status = text(16, TEXT);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(12), 0, dp(8));
        loading.addView(status);
        retry = button("重试", v -> select(this.index, requestedPosition));
        loading.addView(retry);
        root.addView(loading, new FrameLayout.LayoutParams(dp(300), -2, Gravity.CENTER));

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (released || !sourceAttached) return;
                Log.i("HGTV-Clean", "player_state=" + state + " generation=" + generation);
                if (state == Player.STATE_READY) {
                    preparing = false;
                    clearTimeout();
                    loading.setVisibility(View.GONE);
                    pause.setText(player.isPlaying() ? "暂停" : "播放");
                    main.removeCallbacks(progressTick);
                    main.post(progressTick);
                } else if (state == Player.STATE_BUFFERING) {
                    showLoading("正在缓冲…");
                } else if (state == Player.STATE_ENDED) {
                    preferences.edit().putLong(progressKey(), 0).apply();
                    if (PlayerScreen.this.index + 1 < episodes.size()) {
                        select(PlayerScreen.this.index + 1, 0);
                    } else {
                        position.setText("本集已结束");
                    }
                }
            }

            @Override public void onRenderedFirstFrame() {
                if (!released && sourceAttached) Log.i("HGTV-Clean", "first_frame generation=" + generation);
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                pause.setText(playing ? "暂停" : "播放");
                root.setKeepScreenOn(playing);
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (released) return;
                Log.w("HGTV-Clean", "playback_error=" + error.getErrorCodeName());
                saveProgress();
                requestedPosition = Math.max(requestedPosition, player.getCurrentPosition());
                preparing = false;
                sourceAttached = false;
                clearTimeout();
                showError("播放中断，请重试或切换清晰度");
            }
        });
        select(this.index, preferences.getLong(progressKey(), 0));
        setFullscreen(true);
        main.post(progressTick);
    }

    /** Returns the native playback layout for the host activity. */
    public View getView() { return root; }

    /** Handles TV transport keys while allowing normal focus navigation for up/down and confirmation. */
    public boolean handleKey(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || released) return false;
        if (!controlsVisible && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            seek(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -10000 : 10000);
            return true;
        }
        if (!controlsVisible && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_ENTER)) {
            playerView.showController();
            pause.requestFocus();
            return true;
        }
        playerView.showController();
        View focus = activity.getCurrentFocus();
        boolean buttonFocused = focus instanceof Button || focus instanceof android.widget.ImageButton;
        if (buttonFocused && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
            seek(-10000); return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            seek(10000); return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_SPACE) {
            togglePause(); return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
            if (sourceAttached) player.play(); return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) { player.pause(); return true; }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) { changeEpisode(1); return true; }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { changeEpisode(-1); return true; }
        if (keyCode == KeyEvent.KEYCODE_BACK) { exit(); return true; }
        return false;
    }

    /** Saves local progress, cancels source work and releases the renderer without a cloud write. */
    public void release() {
        if (released) return;
        saveProgress();
        released = true;
        generation++;
        clearTimeout();
        main.removeCallbacks(progressTick);
        if (pending != null) pending.cancel(true);
        api.cancelPlayback();
        closeLater(current);
        current = null;
        playerView.setPlayer(null);
        root.setKeepScreenOn(false);
        player.release();
        setFullscreen(false);
        sourceExecutor.shutdownNow();
    }

    /** Pauses and stores resume state when the host activity leaves the foreground. */
    public void pauseForBackground() {
        if (!released) { saveProgress(); player.pause(); }
    }

    /** Persists local resume state and pauses playback for the host's onStop callback. */
    public void pause() { pauseForBackground(); }

    private void select(int nextIndex, long resume) {
        if (released) return;
        saveProgress();
        index = Math.max(0, Math.min(nextIndex, episodes.size() - 1));
        requestedPosition = Math.max(0, resume);
        long request = ++generation;
        Log.i("HGTV-Clean", "episode_selected index=" + (index + 1) + " generation=" + request);
        preparing = true;
        sourceAttached = false;
        clearTimeout();
        if (pending != null) pending.cancel(true);
        api.cancelPlayback();
        player.pause();
        title.setText(drama.title + " · 第 " + (index + 1) + " / " + episodes.size() + " 集");
        position.setText("正在准备播放…");
        qualityButton.setText(quality + "P");
        showLoading("正在准备第 " + (index + 1) + " 集…");
        Models.Episode episode = episodes.get(index);
        int requestedQuality = quality;
        timeout = () -> {
            if (released || generation != request || !preparing) return;
            generation++;
            preparing = false;
            sourceAttached = false;
            if (pending != null) pending.cancel(true);
            api.cancelPlayback();
            closeLater(current);
            current = null;
            player.stop();
            showError("准备时间较长，请重试");
        };
        main.postDelayed(timeout, 20000);
        pending = sourceExecutor.submit(() -> {
            Models.Playback result;
            try {
                result = api.play(episode, requestedQuality);
            } catch (IOException | RuntimeException error) {
                logSourceFailure(error);
                main.post(() -> {
                    if (released || generation != request) return;
                    preparing = false;
                    clearTimeout();
                    showError(PlatformApi.userMessage(error, "暂时无法取得播放源，请重试"));
                });
                return;
            }
            main.post(() -> {
                if (released || generation != request) { closeLater(result); return; }
                Models.Playback old = current;
                current = result;
                DataSource.Factory factory = () -> new PlaybackDataSource(result.source);
                MediaItem item = new MediaItem.Builder()
                        .setUri(Uri.parse("hgtv://episode/" + Uri.encode(episode.id)))
                        .setMimeType(MimeTypes.VIDEO_MP4).build();
                player.setMediaSource(new ProgressiveMediaSource.Factory(factory).createMediaSource(item));
                player.seekTo(requestedPosition);
                sourceAttached = true;
                player.setPlaybackSpeed(speed);
                player.prepare();
                player.play();
                closeLater(old);
                qualityButton.setText(result.quality);
                Log.i("HGTV-Clean", "source_attached generation=" + request + " quality=" + requestedQuality);
            });
        });
    }

    private void chooseEpisode() {
        String[] labels = new String[episodes.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = "第 " + (i + 1) + " 集";
        showMenu(new AlertDialog.Builder(activity).setTitle("选择剧集")
                .setSingleChoiceItems(labels, index, (dialog, which) -> {
                    dialog.dismiss();
                    select(which, preferences.getLong("position:" + episodes.get(which).id, 0));
                }).setNegativeButton("取消", null).create());
    }

    private void changeEpisode(int delta) {
        if (released) return;
        int next = index + delta;
        playerView.showController();
        if (next < 0 || next >= episodes.size()) {
            Toast.makeText(activity, next < 0 ? "已是第一集" : "已是最后一集", Toast.LENGTH_SHORT).show();
            return;
        }
        select(next, preferences.getLong("position:" + episodes.get(next).id, 0));
    }

    private final class EpisodeTouchFrame extends FrameLayout {
        private float startX, startY;
        private boolean eligible, swiping;

        EpisodeTouchFrame() { super(activity); }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (released) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startX = event.getX(); startY = event.getY(); swiping = false;
                // Reserve the title, seek bar and bottom controls for their existing touch actions.
                eligible = startY > Math.max(dp(80), ((View) title.getParent()).getBottom())
                        && startY < getHeight() - dp(96);
            }
            if (event.getPointerCount() != 1) eligible = false;
            if (swiping) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && eligible && direction(event) != 0) {
                swiping = true;
                return true;
            }
            return false;
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility") // Only swipes are intercepted; taps use PlayerView and performClick below.
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!swiping) return super.onTouchEvent(event);
            if (event.getPointerCount() != 1) eligible = false;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                int delta = eligible ? direction(event) : 0;
                swiping = false; eligible = false;
                if (delta != 0) changeEpisode(delta);
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                swiping = false; eligible = false;
            }
            return true;
        }

        @Override public boolean performClick() {
            return super.performClick() || playerView.performClick();
        }

        private int direction(MotionEvent event) {
            float dy = event.getY() - startY;
            if (Math.abs(dy) < dp(72) || Math.abs(dy) < Math.abs(event.getX() - startX) * 1.5f) return 0;
            return dy < 0 ? 1 : -1;
        }
    }

    private void chooseQuality() {
        int[] values = {480, 720, 1080};
        String[] labels = {"480P", "720P", "1080P"};
        int selected = quality == 1080 ? 2 : quality == 720 ? 1 : 0;
        showMenu(new AlertDialog.Builder(activity).setTitle("清晰度")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    dialog.dismiss();
                    if (quality == values[which] && sourceAttached) return;
                    long resume = sourceAttached ? player.getCurrentPosition() : requestedPosition;
                    quality = values[which];
                    preferences.edit().putInt("quality", quality).apply();
                    select(index, resume);
                }).setNegativeButton("取消", null).create());
    }

    private void chooseSpeed() {
        float[] values = {.75f, 1f, 1.25f, 1.5f, 2f};
        String[] labels = {"0.75×", "1×", "1.25×", "1.5×", "2×"};
        showMenu(new AlertDialog.Builder(activity).setTitle("播放速度").setItems(labels, (dialog, which) -> {
            speed = values[which];
            player.setPlaybackSpeed(speed);
            speedButton.setText(labels[which]);
        }).create());
    }

    private void showMenu(AlertDialog dialog) {
        playerView.setControllerShowTimeoutMs(0);
        playerView.showController();
        dialog.setOnDismissListener(ignored -> {
            if (released) return;
            playerView.setControllerShowTimeoutMs(4000);
            playerView.showController();
        });
        dialog.show();
    }

    private void setFullscreen(boolean enabled) {
        View decor = activity.getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30 && decor.getWindowInsetsController() != null) {
            WindowInsetsController insets = decor.getWindowInsetsController();
            if (enabled) {
                insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                insets.hide(WindowInsets.Type.systemBars());
            } else insets.show(WindowInsets.Type.systemBars());
        } else {
            decor.setSystemUiVisibility(enabled ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION : initialSystemUiVisibility);
        }
    }

    private void seek(long delta) {
        if (!sourceAttached || released) return;
        long duration = player.getDuration();
        long target = Math.max(0, player.getCurrentPosition() + delta);
        if (duration > 0) target = Math.min(target, Math.max(0, duration - 500));
        player.seekTo(target);
        playerView.showController();
    }

    private void togglePause() {
        if (released || preparing || !sourceAttached) return;
        if (player.isPlaying()) player.pause(); else player.play();
    }

    private void exit() { release(); onExit.run(); }

    private void saveProgress() {
        if (released || !sourceAttached || current == null) return;
        long duration = player.getDuration();
        long at = Math.max(0, player.getCurrentPosition());
        if (duration > 0 && at >= duration - 2000) at = 0;
        preferences.edit().putLong(progressKey(), at).apply();
    }

    private String progressKey() { return "position:" + episodes.get(index).id; }

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (released) return;
            if (sourceAttached) {
                position.setText(time(player.getCurrentPosition()) + " / " + time(player.getDuration())
                        + "   ·   进度仅保存在本机");
                saveProgress();
            }
            main.postDelayed(this, 5000);
        }
    };

    private void clearTimeout() { if (timeout != null) main.removeCallbacks(timeout); timeout = null; }
    private void showLoading(String message) {
        loading.setVisibility(View.VISIBLE); spinner.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE); status.setText(message);
    }
    private void showError(String message) {
        loading.setVisibility(View.VISIBLE); spinner.setVisibility(View.GONE);
        retry.setVisibility(View.VISIBLE); status.setText(message); retry.requestFocus();
        playerView.showController();
    }
    private static void closeLater(Models.Playback value) {
        if (value != null) CLOSER.execute(() -> { try { value.close(); } catch (IOException ignored) { } });
    }
    private static void logSourceFailure(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 5; depth++) {
            Log.w("HGTV-Clean", "source_failure depth=" + depth
                    + " type=" + cause.getClass().getName());
            StackTraceElement[] frames = cause.getStackTrace();
            for (int i = 0; i < Math.min(3, frames.length); i++) {
                StackTraceElement frame = frames[i];
                Log.w("HGTV-Clean", "source_frame depth=" + depth + " at="
                        + frame.getClassName() + "." + frame.getMethodName()
                        + ":" + frame.getLineNumber());
            }
            Throwable next = cause.getCause();
            if (next == cause) break;
            cause = next;
        }
    }
    private TextView text(int size, int color) {
        TextView view = new TextView(activity); view.setTextColor(color); view.setTextSize(size); return view;
    }
    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(activity);
        button.setText(label); button.setTextSize(15); button.setTextColor(TEXT); button.setAllCaps(false);
        button.setMinHeight(dp(48)); button.setMinWidth(dp(88)); button.setFocusable(true);
        button.setPadding(dp(16), 0, dp(16), 0);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, shape(0xFF264A55, Color.WHITE));
        states.addState(new int[]{android.R.attr.state_pressed}, shape(0xFF264A55, ACCENT));
        states.addState(new int[]{}, shape(0xFF202A38, 0));
        button.setBackground(states);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(48));
        params.setMargins(dp(4), dp(4), dp(4), dp(4)); button.setLayoutParams(params);
        button.setOnClickListener(view -> {
            playerView.showController();
            listener.onClick(view);
        }); return button;
    }
    private GradientDrawable shape(int color, int stroke) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(dp(8)); if (stroke != 0) drawable.setStroke(dp(2), stroke); return drawable;
    }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static String time(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
