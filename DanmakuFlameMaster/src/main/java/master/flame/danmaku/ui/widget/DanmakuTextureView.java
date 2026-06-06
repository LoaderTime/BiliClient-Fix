/*
 * Copyright (C) 2013 Chen Hui <calmer91@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package master.flame.danmaku.ui.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import java.util.LinkedList;
import java.util.Locale;

import master.flame.danmaku.controller.DrawHandler;
import master.flame.danmaku.controller.DrawHandler.Callback;
import master.flame.danmaku.controller.DrawHelper;
import master.flame.danmaku.controller.IDanmakuView;
import master.flame.danmaku.controller.IDanmakuViewController;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.IDanmakus;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser;
import master.flame.danmaku.danmaku.renderer.IRenderer.RenderingState;
import master.flame.danmaku.danmaku.util.SystemClock;

/**
 * Texture backed danmaku view.
 *
 * The legacy DanmakuView asks the UI View draw pass to call DrawHandler.draw(canvas).
 * This implementation keeps the public IDanmakuView contract but lets DrawHandler's
 * update thread lock the TextureView canvas directly, so dense danmaku rendering no
 * longer waits for or runs inside View.onDraw().
 */
public class DanmakuTextureView extends TextureView implements IDanmakuView, IDanmakuViewController,
        TextureView.SurfaceTextureListener {

    public static final String TAG = "DanmakuTextureView";

    private Callback mCallback;
    private HandlerThread mHandlerThread;
    public DrawHandler handler;

    private volatile boolean isSurfaceCreated;
    private boolean mEnableDanmakuDrawingCache = true;
    private OnDanmakuClickListener mOnDanmakuClickListener;
    private DanmakuTouchHelper mTouchHelper;
    private boolean mShowFps;
    private boolean mDanmakuVisible = true;
    protected int mDrawingThreadType = THREAD_TYPE_NORMAL_PRIORITY;
    private long mUiThreadId;

    private volatile long mLastDrawUptimeMs = 0L;
    private volatile long mDrawFrameSeq = 0L;
    private volatile long mDroppedDrawFrameCount = 0L;
    private volatile long mLastDrawCostMs = 0L;
    private final Object mCanvasLock = new Object();

    private static final int MAX_RECORD_SIZE = 50;
    private static final int ONE_SECOND = 1000;
    private LinkedList<Long> mDrawTimes;

    public DanmakuTextureView(Context context) {
        super(context);
        init();
    }

    public DanmakuTextureView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DanmakuTextureView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        mUiThreadId = Thread.currentThread().getId();
        setOpaque(false);
        setSurfaceTextureListener(this);
        DrawHelper.useDrawColorToClearCanvas(true, true);
        mTouchHelper = DanmakuTouchHelper.instance(this);
    }

    @Override
    public void addDanmaku(BaseDanmaku item) {
        if (handler != null) {
            handler.addDanmaku(item);
        }
    }

    @Override
    public void invalidateDanmaku(BaseDanmaku item, boolean remeasure) {
        if (handler != null) {
            handler.invalidateDanmaku(item, remeasure);
        }
    }

    @Override
    public void removeAllDanmakus(boolean isClearDanmakusOnScreen) {
        if (handler != null) {
            handler.removeAllDanmakus(isClearDanmakusOnScreen);
        }
    }

    @Override
    public void removeAllLiveDanmakus() {
        if (handler != null) {
            handler.removeAllLiveDanmakus();
        }
    }

    @Override
    public IDanmakus getCurrentVisibleDanmakus() {
        if (handler != null) {
            return handler.getCurrentVisibleDanmakus();
        }
        return null;
    }

    @Override
    public long getLastDrawUptimeMs() {
        return mLastDrawUptimeMs;
    }

    @Override
    public long getDrawFrameSeq() {
        return mDrawFrameSeq;
    }

    @Override
    public long getDroppedDrawFrameCount() {
        return mDroppedDrawFrameCount;
    }

    public long getLastDrawCostMs() {
        return mLastDrawCostMs;
    }

    @Override
    public void setCallback(Callback callback) {
        mCallback = callback;
        if (handler != null) {
            handler.setCallback(callback);
        }
    }

    @Override
    public DrawHandler getHandler() {
        return handler;
    }

    @Override
    public void release() {
        stop();
        if (mDrawTimes != null) {
            mDrawTimes.clear();
        }
    }

    @Override
    public void stop() {
        stopDraw();
    }

    private void stopDraw() {
        DrawHandler current = handler;
        handler = null;
        if (current != null) {
            try {
                current.removeCallbacks(mResumeRunnable);
            } catch (Exception ignore) {
            }
            mResumeTryCount = 0;
            current.quit();
        }
        if (mHandlerThread != null) {
            HandlerThread handlerThread = mHandlerThread;
            mHandlerThread = null;
            if (Thread.currentThread().getId() != mUiThreadId) {
                try {
                    handlerThread.join(300L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (handlerThread.isAlive()) {
                    handlerThread.quit();
                }
            } else {
                Thread cleanupThread = new Thread(() -> {
                    try {
                        handlerThread.join(800L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (handlerThread.isAlive()) {
                        handlerThread.quit();
                    }
                }, "DFM-TextureHandlerThread-Cleanup");
                cleanupThread.setDaemon(true);
                cleanupThread.start();
            }
        }
    }

    protected Looper getLooper(int type) {
        if (mHandlerThread != null) {
            mHandlerThread.quit();
            mHandlerThread = null;
        }

        int priority;
        switch (type) {
            case THREAD_TYPE_MAIN_THREAD:
                return Looper.getMainLooper();
            case THREAD_TYPE_HIGH_PRIORITY:
                priority = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY;
                break;
            case THREAD_TYPE_LOW_PRIORITY:
                priority = android.os.Process.THREAD_PRIORITY_LOWEST;
                break;
            case THREAD_TYPE_NORMAL_PRIORITY:
            default:
                priority = android.os.Process.THREAD_PRIORITY_DEFAULT;
                break;
        }
        String threadName = "DFM Texture Handler Thread #" + priority;
        mHandlerThread = new HandlerThread(threadName, priority);
        mHandlerThread.start();
        return mHandlerThread.getLooper();
    }

    private void prepare() {
        if (handler == null) {
            handler = new DrawHandler(getLooper(mDrawingThreadType), this, mDanmakuVisible);
        }
    }

    @Override
    public void prepare(BaseDanmakuParser parser, DanmakuContext config) {
        prepare();
        handler.setConfig(config);
        handler.setParser(parser);
        handler.setCallback(mCallback);
        handler.prepare();
    }

    @Override
    public boolean isPrepared() {
        return handler != null && handler.isPrepared();
    }

    @Override
    public DanmakuContext getConfig() {
        if (handler == null) {
            return null;
        }
        return handler.getConfig();
    }

    @Override
    public void showFPS(boolean show) {
        mShowFps = show;
    }

    private float fps() {
        if (mDrawTimes == null) {
            mDrawTimes = new LinkedList<>();
        }
        long lastTime = SystemClock.uptimeMillis();
        mDrawTimes.addLast(lastTime);
        float dtime = lastTime - mDrawTimes.getFirst();
        if (mDrawTimes.size() > MAX_RECORD_SIZE) {
            mDrawTimes.removeFirst();
        }
        return dtime > 0 ? mDrawTimes.size() * ONE_SECOND / dtime : 0.0f;
    }

    @Override
    public long drawDanmakus() {
        if (!mDanmakuVisible) {
            return 0;
        }
        if (!isSurfaceCreated || !super.isShown()) {
            return -1;
        }
        long drawStart = SystemClock.uptimeMillis();
        Canvas canvas = null;
        synchronized (mCanvasLock) {
            try {
                canvas = lockCanvas();
                if (canvas == null) {
                    mDroppedDrawFrameCount++;
                    return 0;
                }
                RenderingState rs = null;
                if (handler != null) {
                    rs = handler.draw(canvas);
                } else {
                    DrawHelper.clearCanvas(canvas);
                }
                long drawEnd = SystemClock.uptimeMillis();
                mLastDrawCostMs = drawEnd - drawStart;
                mLastDrawUptimeMs = drawEnd;
                mDrawFrameSeq++;
                if (mShowFps && rs != null) {
                    String fps = String.format(Locale.getDefault(),
                            "fps %.2f,time:%d s,cache:%d,miss:%d", fps(), getCurrentTime() / 1000,
                            rs.cacheHitCount, rs.cacheMissCount);
                    DrawHelper.drawFPS(canvas, fps);
                }
            } catch (Exception e) {
                mDroppedDrawFrameCount++;
            } finally {
                if (canvas != null) {
                    try {
                        unlockCanvasAndPost(canvas);
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        return SystemClock.uptimeMillis() - drawStart;
    }

    private void clearTextureCanvas() {
        if (!isSurfaceCreated) {
            return;
        }
        Canvas canvas = null;
        synchronized (mCanvasLock) {
            try {
                canvas = lockCanvas();
                if (canvas != null) {
                    DrawHelper.clearCanvas(canvas);
                }
            } catch (Exception ignore) {
            } finally {
                if (canvas != null) {
                    try {
                        unlockCanvasAndPost(canvas);
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        isSurfaceCreated = true;
        clearTextureCanvas();
        if (handler != null) {
            handler.notifyDispSizeChanged(width, height);
            handler.requestRender();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        if (handler != null) {
            handler.notifyDispSizeChanged(width, height);
            handler.requestRender();
        }
        clearTextureCanvas();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        isSurfaceCreated = false;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    @Override
    public void pause() {
        if (handler != null) {
            handler.removeCallbacks(mResumeRunnable);
            handler.pause();
        }
    }

    private int mResumeTryCount = 0;

    private final Runnable mResumeRunnable = new Runnable() {
        @Override
        public void run() {
            if (handler == null) {
                return;
            }
            mResumeTryCount++;
            if (mResumeTryCount > 4 || DanmakuTextureView.super.isShown()) {
                handler.resume();
            } else {
                handler.postDelayed(this, 100 * mResumeTryCount);
            }
        }
    };

    @Override
    public void resume() {
        if (handler != null && handler.isPrepared()) {
            handler.removeCallbacks(mResumeRunnable);
            mResumeTryCount = 0;
            handler.postDelayed(mResumeRunnable, 50);
        } else if (handler == null) {
            restart();
        }
    }

    @Override
    public boolean isPaused() {
        if (handler != null) {
            return handler.isStop();
        }
        return false;
    }

    public void restart() {
        stop();
        start();
    }

    @Override
    public void start() {
        start(0);
    }

    @Override
    public void start(long position) {
        if (handler == null) {
            prepare();
        } else {
            handler.removeCallbacks(mResumeRunnable);
        }
        if (handler != null) {
            handler.obtainMessage(DrawHandler.START, position).sendToTarget();
        }
    }

    @Override
    public void toggle() {
        if (isSurfaceCreated) {
            if (handler == null) {
                start();
            } else if (handler.isStop()) {
                resume();
            } else {
                pause();
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mTouchHelper != null) {
            mTouchHelper.onTouchEvent(event);
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void seekTo(Long ms) {
        if (handler != null && handler.isPrepared()) {
            handler.seekTo(ms);
        }
    }

    @Override
    public void setSpeed(float speed) {
        if (handler != null && handler.isPrepared()) {
            handler.setSpeed(speed);
        }
    }

    @Override
    public void adjust() {
        if (handler != null) {
            handler.syncTimerIfNeeded();
            handler.requestRender();
        }
    }

    @Override
    public void enableDanmakuDrawingCache(boolean enable) {
        mEnableDanmakuDrawingCache = enable;
    }

    @Override
    public boolean isDanmakuDrawingCacheEnabled() {
        return mEnableDanmakuDrawingCache;
    }

    @Override
    public boolean isViewReady() {
        return isSurfaceCreated;
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public void show() {
        showAndResumeDrawTask(null);
    }

    @Override
    public void showAndResumeDrawTask(Long position) {
        mDanmakuVisible = true;
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
        }
        if (handler == null) {
            return;
        }
        handler.showDanmakus(position);
        handler.requestRender();
    }

    @Override
    public void hide() {
        mDanmakuVisible = false;
        if (handler == null) {
            clearTextureCanvas();
            return;
        }
        handler.hideDanmakus(false);
        clearTextureCanvas();
    }

    @Override
    public long hideAndPauseDrawTask() {
        mDanmakuVisible = false;
        if (handler == null) {
            clearTextureCanvas();
            return 0;
        }
        long position = handler.hideDanmakus(true);
        clearTextureCanvas();
        return position;
    }

    @Override
    public void clear() {
        clearTextureCanvas();
    }

    @Override
    public void setVisibility(int visibility) {
        boolean hidden = visibility != View.VISIBLE;
        if (hidden) {
            mDanmakuVisible = false;
            if (handler != null) {
                handler.hideDanmakus(false);
            }
            clearTextureCanvas();
        }
        super.setVisibility(visibility);
    }

    @Override
    public boolean isShown() {
        return mDanmakuVisible && super.isShown();
    }

    @Override
    public void setDrawingThreadType(int type) {
        mDrawingThreadType = type;
    }

    @Override
    public long getCurrentTime() {
        if (handler != null) {
            return handler.getCurrentTime();
        }
        return 0;
    }

    @Override
    @SuppressLint("NewApi")
    public boolean isHardwareAccelerated() {
        return false;
    }

    @Override
    public void clearDanmakusOnScreen() {
        if (handler != null) {
            handler.clearDanmakusOnScreen();
        }
        clearTextureCanvas();
    }

    @Override
    public void setOnDanmakuClickListener(OnDanmakuClickListener listener) {
        mOnDanmakuClickListener = listener;
        setClickable(listener != null);
    }

    @Override
    public OnDanmakuClickListener getOnDanmakuClickListener() {
        return mOnDanmakuClickListener;
    }

    @Override
    public ViewGroup.LayoutParams getLayoutParams() {
        return super.getLayoutParams();
    }

    @Override
    public void setLayoutParams(ViewGroup.LayoutParams params) {
        super.setLayoutParams(params);
    }
}
