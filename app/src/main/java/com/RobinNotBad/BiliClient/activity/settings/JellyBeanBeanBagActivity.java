package com.RobinNotBad.BiliClient.activity.settings;

import android.animation.TimeAnimator;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.util.Random;

public class JellyBeanBeanBagActivity extends BaseActivity {
    private Board board;

    private long lastTapTime;
    private float lastTapX;
    private float lastTapY;
    private int doubleTapSlop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        board = new Board(this);
        setContentView(board);

        doubleTapSlop = ViewConfiguration.get(this).getScaledDoubleTapSlop();
        MsgUtil.showMsg("双击以逃离夜晚");
    }

    @Override
    protected void onResume() {
        super.onResume();
        board.startAnimation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        board.stopAnimation();
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

    private static class Board extends FrameLayout {
        private static final Random RNG = new Random();

        private static final int NUM_BEANS = 40;
        private static final float MIN_SCALE = 0.2f;
        private static final float MAX_SCALE = 1f;
        private static final int MAX_RADIUS = (int) (576 * MAX_SCALE);
        private static final float LUCKY = 0.001f;

        private static final int[] BEANS = {
                R.drawable.j_redbean0,
                R.drawable.j_redbean0,
                R.drawable.j_redbean0,
                R.drawable.j_redbean0,
                R.drawable.j_redbean1,
                R.drawable.j_redbean1,
                R.drawable.j_redbean2,
                R.drawable.j_redbean2,
                R.drawable.j_redbeandroid,
        };

        private static final int[] COLORS = {
                0xFF00CC00,
                0xFFCC0000,
                0xFF0000CC,
                0xFFFFFF00,
                0xFFFF8000,
                0xFF00CCFF,
                0xFFFF0080,
                0xFF8000FF,
                0xFFFF8080,
                0xFF8080FF,
                0xFFB0C0D0,
                0xFFDDDDDD,
                0xFF333333,
        };

        private int boardWidth;
        private int boardHeight;
        private TimeAnimator animator;

        public Board(JellyBeanBeanBagActivity context) {
            super(context);
            setBackgroundColor(0xFF121212);
        }

        private static float randf(float min, float max) {
            return min + (max - min) * RNG.nextFloat();
        }

        private static int pickBeanRes() {
            return BEANS[RNG.nextInt(BEANS.length)];
        }

        private void resetBoard() {
            removeAllViews();

            ViewGroup.LayoutParams wrap = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);

            for (int i = 0; i < NUM_BEANS; i++) {
                Bean bean = new Bean(getContext());
                addView(bean, wrap);
                bean.z = (float) i / NUM_BEANS;
                bean.z *= bean.z;
                bean.reset(boardWidth, boardHeight);
                bean.x = randf(0, boardWidth);
                bean.y = randf(0, boardHeight);
            }

            if (animator != null) {
                animator.cancel();
            }

            animator = new TimeAnimator();
            animator.setTimeListener((animation, totalTime, deltaTime) -> {
                float dt = deltaTime / 1000f;
                for (int i = 0; i < getChildCount(); i++) {
                    View view = getChildAt(i);
                    if (!(view instanceof Bean)) continue;
                    Bean bean = (Bean) view;
                    bean.update(dt);
                    bean.setRotation(bean.a);
                    bean.setX(bean.x - bean.getPivotX());
                    bean.setY(bean.y - bean.getPivotY());

                    if (bean.x < -MAX_RADIUS || bean.x > boardWidth + MAX_RADIUS
                            || bean.y < -MAX_RADIUS || bean.y > boardHeight + MAX_RADIUS) {
                        bean.reset(boardWidth, boardHeight);
                    }
                }
            });
        }

        public void startAnimation() {
            stopAnimation();
            if (animator == null) {
                post(() -> {
                    resetBoard();
                    startAnimation();
                });
            } else {
                animator.start();
            }
        }

        public void stopAnimation() {
            if (animator != null) {
                animator.cancel();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            boardWidth = w;
            boardHeight = h;
            if (boardWidth > 0 && boardHeight > 0) {
                resetBoard();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopAnimation();
        }

        @Override
        public boolean isOpaque() {
            return true;
        }

        private static class Bean extends ImageView {
            float x;
            float y;
            float a;
            float va;
            float vx;
            float vy;
            float r;
            float z;
            int h;
            int w;

            boolean grabbed;
            float grabx;
            float graby;
            float grabxOffset;
            float grabyOffset;

            Bean(android.content.Context context) {
                super(context);
                setScaleType(ScaleType.CENTER);
            }

            private void pickDrawable() {
                int beanRes = pickBeanRes();
                if (RNG.nextFloat() <= LUCKY) {
                    beanRes = R.drawable.j_jandycane;
                }
                BitmapDrawable beanDrawable = (BitmapDrawable) getContext().getResources().getDrawable(beanRes);
                beanDrawable.setTargetDensity(480);
                Bitmap bitmap = beanDrawable.getBitmap();
                h = bitmap.getHeight();
                w = bitmap.getWidth();
                setImageDrawable(beanDrawable);

                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                ColorMatrix cm = new ColorMatrix();
                float[] matrix = cm.getArray();
                int beanColor = COLORS[RNG.nextInt(COLORS.length)];
                matrix[0] = Color.red(beanColor) / 255f;
                matrix[5] = Color.green(beanColor) / 255f;
                matrix[10] = Color.blue(beanColor) / 255f;
                paint.setColorFilter(new ColorMatrixColorFilter(matrix));
                setLayerType(View.LAYER_TYPE_HARDWARE, paint);
            }

            void reset(int boardWidth, int boardHeight) {
                pickDrawable();

                float scale = MIN_SCALE + (MAX_SCALE - MIN_SCALE) * z;
                setScaleX(scale);
                setScaleY(scale);

                r = 0.3f * Math.max(h, w) * scale;
                a = randf(0, 360);
                va = randf(-30, 30);
                vx = randf(-40, 40) * z;
                vy = randf(-40, 40) * z;

                if (RNG.nextBoolean()) {
                    x = vx < 0 ? boardWidth + 2 * r : -r * 4f;
                    y = randf(0, Math.max(1, boardHeight - 3 * r));
                } else {
                    y = vy < 0 ? boardHeight + 2 * r : -r * 4f;
                    x = randf(0, Math.max(1, boardWidth - 3 * r));
                }
            }

            void update(float dt) {
                if (grabbed) {
                    vx = (vx * 0.75f) + ((grabx - x) / dt) * 0.25f;
                    vy = (vy * 0.75f) + ((graby - y) / dt) * 0.25f;
                    x = grabx;
                    y = graby;
                } else {
                    x += vx * dt;
                    y += vy * dt;
                    a += va * dt;
                }
            }

            private boolean isTouchedBean(MotionEvent event) {
                if (!(getDrawable() instanceof BitmapDrawable)) return false;
                Bitmap bitmap = ((BitmapDrawable) getDrawable()).getBitmap();
                if (bitmap == null) return false;

                float sx = bitmap.getWidth() * 1f / getWidth();
                float sy = bitmap.getHeight() * 1f / getHeight();
                int x = (int) (event.getX() * sx);
                int y = (int) (event.getY() * sy);
                if (x < 0 || y < 0 || x >= bitmap.getWidth() || y >= bitmap.getHeight()) {
                    return false;
                }
                return Color.alpha(bitmap.getPixel(x, y)) > 0;
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!isTouchedBean(event)) {
                            return false;
                        }
                        grabbed = true;
                        grabxOffset = event.getRawX() - x;
                        grabyOffset = event.getRawY() - y;
                        va = 0;
                    case MotionEvent.ACTION_MOVE:
                        grabx = event.getRawX() - grabxOffset;
                        graby = event.getRawY() - grabyOffset;
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        grabbed = false;
                        float spin = (RNG.nextBoolean() ? 1 : -1) * Math.min(1080f,
                                (float) Math.sqrt(vx * vx + vy * vy) * 0.33f);
                        va = randf(spin * 0.5f, spin);
                        break;
                    default:
                        break;
                }
                return true;
            }
        }
    }
}
