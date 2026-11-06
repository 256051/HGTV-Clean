package com.hgtv.clean;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native QR login screen with cancellable polling and no access to the original application's state. */
public final class QrLoginScreen {
    private final PlatformApi platform;
    private final Runnable onExit;
    private final ScrollView root;
    private final ImageView image;
    private final TextView status;
    private final Button refresh;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "clean-qr-login"));
    private Future<?> pending;
    private volatile AuthApi auth;
    private volatile long generation;
    private volatile boolean released;

    /** Creates a screen; onExit should restore the host's settings or account page. */
    public QrLoginScreen(Activity activity, PlatformApi platform, Runnable onExit) {
        this.platform = platform;
        this.onExit = onExit;
        root = new ScrollView(activity); root.setFillViewport(true); root.setBackgroundColor(Ui.BG);
        LinearLayout column = new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL); column.setGravity(Gravity.CENTER);
        column.setPadding(Ui.dp(activity, 24), Ui.dp(activity, 32), Ui.dp(activity, 24), Ui.dp(activity, 32)); root.addView(column);
        TextView title = Ui.text(activity, "扫码登录", 28, Ui.TEXT); column.addView(title);
        TextView hint = Ui.text(activity, "用官方 App 扫描二维码，并在手机上确认登录。", 16, Ui.MUTED);
        hint.setGravity(Gravity.CENTER); hint.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 16)); column.addView(hint);
        image = new ImageView(activity); image.setBackgroundColor(Color.WHITE); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("扫码登录二维码，请使用另一台手机扫描");
        int size = Ui.dp(activity, Math.min(300, activity.getResources().getConfiguration().screenWidthDp - 56));
        int pad = Ui.dp(activity, 12); image.setPadding(pad, pad, pad, pad);
        column.addView(image, new LinearLayout.LayoutParams(size, size));
        status = Ui.text(activity, "正在获取二维码…", 17, Ui.TEXT); status.setGravity(Gravity.CENTER);
        status.setPadding(0, Ui.dp(activity, 18), 0, Ui.dp(activity, 14)); column.addView(status);
        LinearLayout actions = new LinearLayout(activity);
        refresh = Ui.button(activity, "刷新二维码", v -> refresh()); actions.addView(refresh);
        Button back = Ui.button(activity, "返回", v -> { release(); onExit.run(); });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.setMargins(Ui.dp(activity, 12), 0, 0, 0); actions.addView(back, bp); column.addView(actions);
        TextView privacy = Ui.text(activity, "登录仅保存在 HGTV Clean 内；不导入旧应用登录，不上传本地收藏和进度。", 13, Ui.MUTED);
        privacy.setGravity(Gravity.CENTER); privacy.setPadding(0, Ui.dp(activity, 18), 0, 0); column.addView(privacy);
        root.setOnApplyWindowInsetsListener((view, insets) -> { view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets; });
        refresh();
    }

    /** Returns the layout for the host activity. */
    public View getView() { return root; }

    /** Cancels this screen's attempt without clearing any confirmed login. */
    public void release() {
        if (released) return;
        released = true; generation++; main.removeCallbacksAndMessages(null);
        AuthApi attempt = auth; if (attempt != null) attempt.close();
        if (pending != null) pending.cancel(true); worker.shutdownNow();
    }

    private void refresh() {
        if (released) return;
        long request = ++generation;
        main.removeCallbacksAndMessages(null);
        AuthApi old = auth; auth = null; if (old != null) old.close();
        if (pending != null) pending.cancel(true);
        image.setImageDrawable(null); image.setVisibility(View.VISIBLE); refresh.setEnabled(false); status.setText("正在获取二维码…");
        pending = worker.submit(() -> {
            try {
                AuthApi attempt = platform.startQrLogin();
                if (released || request != generation) { attempt.close(); return; }
                auth = attempt;
                Bitmap bitmap = bitmap(attempt.request());
                main.post(() -> { if (isCurrent(request)) { image.setImageBitmap(bitmap); refresh.setEnabled(true); status.setText("等待扫码，二维码有效期有限。"); schedule(request); } });
            } catch (Exception error) { fail(request, "二维码获取失败，请检查网络后刷新。"); }
        });
    }

    private void schedule(long request) {
        main.postDelayed(() -> {
            if (!isCurrent(request)) return;
            pending = worker.submit(() -> {
                try {
                    AuthApi.Result result = auth.poll();
                    Bitmap bitmap = result.code == null ? null : bitmap(result.code);
                    main.post(() -> {
                        if (!isCurrent(request)) return;
                        if (bitmap != null) image.setImageBitmap(bitmap);
                        switch (result.state) {
                            case "confirmed":
                                image.setImageDrawable(null); image.setVisibility(View.GONE); refresh.setVisibility(View.GONE);
                                status.setText("登录已保存：" + result.account.name + (result.account.verified ? "\n账号资料验证成功。" : "\n账号资料暂未返回，登录仍已保留。"));
                                auth.close(); return;
                            case "expired":
                                image.setImageDrawable(null); status.setText("二维码已过期或连接中断，请刷新重试。"); refresh.requestFocus(); return;
                            case "scanned": status.setText("已扫码，请在手机上确认登录。"); break;
                            default: status.setText("等待扫码，二维码有效期有限。"); break;
                        }
                        schedule(request);
                    });
                } catch (Exception error) { fail(request, "扫码状态暂未取得，请刷新二维码。"); }
            });
        }, 2000);
    }

    private void fail(long request, String message) {
        main.post(() -> { if (isCurrent(request)) { image.setImageDrawable(null); refresh.setEnabled(true); status.setText(message); refresh.requestFocus(); } });
    }

    private boolean isCurrent(long request) { return !released && generation == request; }

    private static Bitmap bitmap(AuthApi.QrCode code) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(code.png, 0, code.png.length, bounds);
        if (bounds.outWidth < 64 || bounds.outHeight < 64 || bounds.outWidth > 2048 || bounds.outHeight > 2048) throw new IOException("二维码图像格式无效");
        Bitmap value = BitmapFactory.decodeByteArray(code.png, 0, code.png.length);
        if (value == null) throw new IOException("二维码图像不可用");
        return value;
    }
}
