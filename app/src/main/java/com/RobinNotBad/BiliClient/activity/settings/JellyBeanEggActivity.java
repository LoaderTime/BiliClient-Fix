package com.RobinNotBad.BiliClient.activity.settings;

import android.content.Intent;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.LinearLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.util.MsgUtil;

public class JellyBeanEggActivity extends BaseActivity {
    private static final int BEAN_COLOR = 0xFFCCFF00;

    private long lastTapTime;
    private float lastTapX;
    private float lastTapY;
    private int doubleTapSlop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF121212);

        ImageView beanView = new ImageView(this);
        beanView.setImageResource(R.drawable.j_platlogo_alt);
        beanView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = (int) (32 * metrics.density);
        beanView.setPadding(p, p, p, p);
        tintBean(beanView);
        root.addView(beanView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        LinearLayout textGroup = makeTextGroup(metrics);
        textGroup.setVisibility(TextView.GONE);

        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        textLp.bottomMargin = Math.max((int) (metrics.heightPixels * 0.16f), (int) (64 * metrics.density));
        root.addView(textGroup, textLp);

        beanView.setOnClickListener(v -> {
            beanView.setImageResource(R.drawable.j_platlogo);
            tintBean(beanView);
            textGroup.setVisibility(TextView.VISIBLE);
        });

        beanView.setOnLongClickListener(v -> {
            startActivity(new Intent(this, JellyBeanBeanBagActivity.class));
            finish();
            return true;
        });

        setContentView(root);

        doubleTapSlop = ViewConfiguration.get(this).getScaledDoubleTapSlop();
        MsgUtil.showMsg("双击以逃离夜晚");
    }

    private void tintBean(ImageView beanView) {
        float r = ((BEAN_COLOR >> 16) & 0xFF) / 255f;
        float g = ((BEAN_COLOR >> 8) & 0xFF) / 255f;
        float b = (BEAN_COLOR & 0xFF) / 255f;

        ColorMatrix cm = new ColorMatrix(new float[]{
                r, 0, 0, 0, 0,
                g, 0, 0, 0, 0,
                b, 0, 0, 0, 0,
                0, 0, 0, 1, 0
        });
        beanView.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    private LinearLayout makeTextGroup(DisplayMetrics metrics) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
        final float size = 14f * metrics.density;

        LinearLayout.LayoutParams lpTop = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpTop.gravity = Gravity.CENTER_HORIZONTAL;
        lpTop.bottomMargin = (int) (-4 * metrics.density);

        LinearLayout.LayoutParams lpBottom = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpBottom.gravity = Gravity.CENTER_HORIZONTAL;

        TextView lineTop = new TextView(this);
        if (light != null) lineTop.setTypeface(light);
        lineTop.setText("Jelly Beans");
        lineTop.setTextColor(0xFFFFFFFF);
        lineTop.setGravity(Gravity.CENTER);
        lineTop.setTextSize(1.25f * size);
        lineTop.setShadowLayer(4 * metrics.density, 0, 2 * metrics.density, 0x66000000);
        view.addView(lineTop, lpTop);

        TextView lineBottom = new TextView(this);
        if (bold != null) lineBottom.setTypeface(bold);
        lineBottom.setText("Wriggle");
        lineBottom.setTextColor(0xFFFFFFFF);
        lineBottom.setGravity(Gravity.CENTER);
        lineBottom.setTextSize(size);
        lineBottom.setPadding(0, 0, 0, (int) (2 * metrics.density));
        lineBottom.setShadowLayer(4 * metrics.density, 0, 2 * metrics.density, 0x66000000);
        view.addView(lineBottom, lpBottom);

        return view;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            long now = SystemClock.uptimeMillis();
            float x = ev.getRawX();
            float y = ev.getRawY();
            if (now - lastTapTime < 250) {
                float dx = x - lastTapX;
                float dy = y - lastTapY;
                if (dx * dx + dy * dy <= (float) doubleTapSlop * doubleTapSlop) {
                    finish();
                    return true;
                }
            }
            lastTapTime = now;
            lastTapX = x;
            lastTapY = y;
        }
        return super.dispatchTouchEvent(ev);
    }
}
