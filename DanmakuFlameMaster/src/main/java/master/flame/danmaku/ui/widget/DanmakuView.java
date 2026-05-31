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
import android.graphics.Color;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
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

public class DanmakuView extends View implements IDanmakuView, IDanmakuViewController {

    public static final String TAG = "DanmakuView";

    /**
     * DFM 的更新线程会等待 UI onDraw 完成本帧绘制；这里必须有上限，避免 UI 繁忙或 View 状态竞态时
     * 更新线程长期阻塞，进而拖死 seek / soft recovery / hard recovery。
     */
    private static final long MAX_DRAW_WAIT_MS = 80L;

    private Callback mCallback;

    private HandlerThread mHandlerThread;

    public DrawHandler handler;

    private boolean isSurfaceCreated;

    private boolean mEnableDanmakuDrwaingCache = true;

    private OnDanmakuClickListener mOnDanmakuClickListener;

    private DanmakuTouchHelper mTouchHelper;

    private boolean mShowFps;

    private boolean mDanmakuVisible = true;

    protected int mDrawingThreadType = THREAD_TYPE_NORMAL_PRIORITY;

    private final Object mDrawMonitor = new Object();

    private boolean mDrawFinished = false;

    private boolean mRequestRender = false;

    private long mUiThreadId;

    /** 最近一次真正执行 handler.draw(canvas) 的时间，用于播放器侧判断弹幕渲染链路是否还活着。 */
    private volatile long mLastDrawUptimeMs = 0L;

    /** 成功绘制帧序号。只要真实 onDraw -> handler.draw 发生，就递增。 */
    private volatile long mDrawFrameSeq = 0L;

    /** 因等待 UI 绘制超时而跳过的帧数，用于诊断弱设备/高倍速下的压力。 */
    private volatile long mDroppedDrawFrameCount = 0L;

    private volatile long mLastDrawCostMs = 0L;

    public DanmakuView(Context context) {
        super(context);
        init();
    }

    private void init() {
        mUiThreadId = Thread.currentThread().getId();
        setBackgroundColor(Color.TRANSPARENT);
        setDrawingCacheBackgroundColor(Color.TRANSPARENT);
        DrawHelper.useDrawColorToClearCanvas(true, false);
        mTouchHelper = DanmakuTouchHelper.instance(this);
    }

    public DanmakuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DanmakuView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

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

    public void setCallback(Callback callback) {
        mCallback = callback;
        if (handler != null) {
            handler.setCallback(callback);
        }
    }

    public DrawHandler getHandler() {
        return handler;
    }

    @Override
    public void release() {
        stop();
        if (mDrawTimes != null)
            mDrawTimes.clear();
    }

    @Override
    public void stop() {
        stopDraw();
    }

    private void stopDraw() {
        DrawHandler handler = this.handler;
        this.handler = null;
        unlockCanvasAndPost();
        if (handler != null) {
            try {
                handler.removeCallbacks(mResumeRunnable);
            } catch (Exception ignore) {
            }
            mResumeTryCount = 0;
            handler.quit();
        }
        if (mHandlerThread != null) {
            HandlerThread handlerThread = this.mHandlerThread;
            mHandlerThread = null;
            // 不能在 UI 线程无限 join，否则 watchdog 触发弹幕硬恢复时容易把整个应用主线程卡死。
            // 这里改成：
            // 1) 非 UI 线程只做短暂等待；
            // 2) UI 线程改为后台收尾，不阻塞前台交互。
            if (Thread.currentThread().getId() != mUiThreadId) {
                try {
                    handlerThread.join(300L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (handlerThread.isAlive()) {
                    handlerThread.quit();
                }
            } else {
                final HandlerThread threadToCleanup = handlerThread;
                Thread cleanupThread = new Thread(() -> {
                    try {
                        threadToCleanup.join(800L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (threadToCleanup.isAlive()) {
                        threadToCleanup.quit();
                    }
                }, "DFM-HandlerThread-Cleanup");
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
        String threadName = "DFM Handler Thread #" + priority;
        mHandlerThread = new HandlerThread(threadName, priority);
        mHandlerThread.start();
        return mHandlerThread.getLooper();
    }

    private void prepare() {
        if (handler == null)
            handler = new DrawHandler(getLooper(mDrawingThreadType), this, mDanmakuVisible);
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

    private static final int MAX_RECORD_SIZE = 50;
    private static final int ONE_SECOND = 1000;
    private LinkedList<Long> mDrawTimes;

    private boolean mClearFlag;

    private float fps() {
        long lastTime = SystemClock.uptimeMillis();
        mDrawTimes.addLast(lastTime);
        float dtime = lastTime - mDrawTimes.getFirst();
        int frames = mDrawTimes.size();
        if (frames > MAX_RECORD_SIZE) {
            mDrawTimes.removeFirst();
        }
        return dtime > 0 ? mDrawTimes.size() * ONE_SECOND / dtime : 0.0f;
    }

    @Override
    public long drawDanmakus() {
        if (!isSurfaceCreated)
            return 0;
        if (!isShown())
            return -1;
        long stime = SystemClock.uptimeMillis();
        lockCanvas();
        return SystemClock.uptimeMillis() - stime;
    }

    @SuppressLint("NewApi")
    private void postInvalidateCompat() {
        mRequestRender = true;
        if (Build.VERSION.SDK_INT >= 16) {
            this.postInvalidateOnAnimation();
        } else {
            this.postInvalidate();
        }
    }

    private void lockCanvas() {
        if (mDanmakuVisible == false) {
            return;
        }
        postInvalidateCompat();
        boolean finished;
        synchronized (mDrawMonitor) {
            long deadline = SystemClock.uptimeMillis() + MAX_DRAW_WAIT_MS;
            while ((!mDrawFinished) && (handler != null) && mDanmakuVisible && !handler.isStop()) {
                long waitMs = deadline - SystemClock.uptimeMillis();
                if (waitMs <= 0L) {
                    break;
                }
                try {
                    mDrawMonitor.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            finished = mDrawFinished;
            mDrawFinished = false;
        }
        if (!finished && handler != null && mDanmakuVisible) {
            mDroppedDrawFrameCount++;
        }
    }

    private void lockCanvasAndClear() {
        mClearFlag = true;
        lockCanvas();
    }

    private void unlockCanvasAndPost() {
        synchronized (mDrawMonitor) {
            mDrawFinished = true;
            mDrawMonitor.notifyAll();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if ((!mDanmakuVisible) && (!mRequestRender)) {
            super.onDraw(canvas);
            return;
        }
        if (mClearFlag) {
            DrawHelper.clearCanvas(canvas);
            mClearFlag = false;
        } else {
            if (handler != null) {
                long drawStart = SystemClock.uptimeMillis();
                RenderingState rs = handler.draw(canvas);
                long drawEnd = SystemClock.uptimeMillis();
                mLastDrawCostMs = drawEnd - drawStart;
                mLastDrawUptimeMs = drawEnd;
                mDrawFrameSeq++;
                if (mShowFps) {
                    if (mDrawTimes == null)
                        mDrawTimes = new LinkedList<>();
                    String fps = String.format(Locale.getDefault(),
                            "fps %.2f,time:%d s,cache:%d,miss:%d", fps(), getCurrentTime() / 1000,
                            rs.cacheHitCount, rs.cacheMissCount);
                    DrawHelper.drawFPS(canvas, fps);
                }
            }
        }
        mRequestRender = false;
        unlockCanvasAndPost();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (handler != null) {
            handler.notifyDispSizeChanged(right - left, bottom - top);
        }
        isSurfaceCreated = true;
    }

    public void toggle() {
        if (isSurfaceCreated) {
            if (handler == null)
                start();
            else if (handler.isStop()) {
                resume();
            } else
                pause();
        }
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
            if (mResumeTryCount > 4 || DanmakuView.super.isShown()) {
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
    public void start(long postion) {
        if (handler == null) {
            prepare();
        } else {
            handler.removeCallbacks(mResumeRunnable);
        }
        if (handler != null) {
            handler.obtainMessage(DrawHandler.START, postion).sendToTarget();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (null != mTouchHelper) {
            mTouchHelper.onTouchEvent(event);
        }

        return super.onTouchEvent(event);
    }

    public void seekTo(Long ms) {
        if (handler != null && handler.isPrepared()) {
            handler.seekTo(ms);
        }
    }

    public void setSpeed(float speed) {
        if (handler != null && handler.isPrepared()) {
            handler.setSpeed(speed);
        }
    }

    public void adjust() {
        if (handler != null) {
            handler.syncTimerIfNeeded();
        }
    }

    public void enableDanmakuDrawingCache(boolean enable) {
        mEnableDanmakuDrwaingCache = enable;
    }

    @Override
    public boolean isDanmakuDrawingCacheEnabled() {
        return mEnableDanmakuDrwaingCache;
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
        mClearFlag = false;
        if (handler == null) {
            return;
        }
        handler.showDanmakus(position);
    }

    @Override
    public void hide() {
        mDanmakuVisible = false;
        if (handler == null) {
            return;
        }
        handler.hideDanmakus(false);
    }

    @Override
    public long hideAndPauseDrawTask() {
        mDanmakuVisible = false;
        if (handler == null) {
            return 0;
        }
        return handler.hideDanmakus(true);
    }

    @Override
    public void clear() {
        if (!isViewReady()) {
            return;
        }
        if (!mDanmakuVisible || Thread.currentThread().getId() == mUiThreadId) {
            mClearFlag = true;
            postInvalidateCompat();
        } else {
            lockCanvasAndClear();
        }
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
        // >= 3.0
        if (Build.VERSION.SDK_INT >= 11) {
            return super.isHardwareAccelerated();
        } else {
            return false;
        }
    }

    @Override
    public void clearDanmakusOnScreen() {
        if (handler != null) {
            handler.clearDanmakusOnScreen();
        }
    }

    @Override
    public void setOnDanmakuClickListener(OnDanmakuClickListener listener) {
        mOnDanmakuClickListener = listener;
        setClickable(null != listener);
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
