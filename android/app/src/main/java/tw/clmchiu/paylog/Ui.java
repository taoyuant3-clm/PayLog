package tw.clmchiu.paylog;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 以程式碼建立的簡潔介面元件（不需 AndroidX）。 */
final class Ui {

    private Ui() {
    }

    static boolean dark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static int brand(Context c) {
        return c.getColor(dark(c) ? R.color.brand_dark : R.color.brand);
    }

    static int surface(Context c) {
        return dark(c) ? 0xFF1E2120 : 0xFFFFFFFF;
    }

    static int line(Context c) {
        return dark(c) ? 0x22FFFFFF : 0x14000000;
    }

    static int muted(Context c) {
        return dark(c) ? 0xFFA3A9A6 : 0xFF66706B;
    }

    static int warn(Context c) {
        return dark(c) ? 0xFFFBBF24 : 0xFFB45309;
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    static TextView text(Context c, CharSequence s, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView label(Context c, CharSequence s) {
        TextView t = text(c, s, 13, false);
        t.setTextColor(muted(c));
        t.setPadding(0, dp(c, 16), 0, dp(c, 6));
        return t;
    }

    /** 圓角卡片背景。 */
    static void card(View v) {
        Context c = v.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(surface(c));
        g.setCornerRadius(dp(c, 16));
        g.setStroke(dp(c, 1), line(c));
        v.setBackground(g);
        int p = dp(c, 16);
        v.setPadding(p, p, p, p);
    }

    static Button primaryButton(Context c, CharSequence s) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(dark(c) ? 0xFF0B1F1C : 0xFFFFFFFF);
        GradientDrawable g = new GradientDrawable();
        g.setColor(brand(c));
        g.setCornerRadius(dp(c, 14));
        b.setBackground(g);
        b.setMinHeight(dp(c, 52));
        b.setStateListAnimator(null);
        return b;
    }

    static Button ghostButton(Context c, CharSequence s) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(brand(c));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(dp(c, 14));
        g.setStroke(dp(c, 1), brand(c));
        b.setBackground(g);
        b.setMinHeight(dp(c, 48));
        b.setStateListAnimator(null);
        return b;
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams topMargin(Context c, float dp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(c, dp);
        return lp;
    }

    /**
     * Android 15 起（targetSdk 35+）畫面強制延伸到系統列之下，
     * 依系統列與鍵盤高度補上內距，避免內容被遮住。
     */
    static void applyInsets(Activity a, View root) {
        if (Build.VERSION.SDK_INT < 35) return;
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(),
                r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(l + i.left, t + i.top, r + i.right, b + i.bottom);
                return WindowInsets.CONSUMED;
            }
        });
    }
}
