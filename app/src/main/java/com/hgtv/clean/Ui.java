package com.hgtv.clean;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Native controls shared by the catalogue and the local library. */
final class Ui {
    static final int BG = Color.rgb(11, 14, 20), CARD = Color.rgb(22, 29, 39);
    static final int TEXT = Color.rgb(244, 247, 251), MUTED = Color.rgb(166, 180, 196);
    static final int ACCENT = Color.rgb(85, 214, 194);
    static int dp(Context c, float n) { return Math.round(c.getResources().getDisplayMetrics().density * n); }
    static TextView text(Context c, String value, int size, int color) {
        TextView v = new TextView(c); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v;
    }
    static GradientDrawable shape(int color, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(12);
        if (stroke != 0) d.setStroke(3, stroke); return d;
    }
    static StateListDrawable focusBackground() {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, shape(Color.rgb(30, 69, 72), TEXT));
        d.addState(new int[]{android.R.attr.state_pressed}, shape(Color.rgb(30, 69, 72), ACCENT));
        d.addState(new int[]{}, shape(CARD, 0)); return d;
    }
    static Button button(Context c, String label, View.OnClickListener action) {
        Button b = new Button(c); b.setText(label); b.setTextColor(TEXT); b.setTextSize(15);
        b.setAllCaps(false); b.setMinHeight(dp(c, 48)); b.setMinimumHeight(dp(c, 48));
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        b.setBackground(focusBackground()); b.setOnClickListener(action); b.setFocusable(true);
        b.setTypeface(Typeface.DEFAULT, Typeface.NORMAL); return b;
    }
}
