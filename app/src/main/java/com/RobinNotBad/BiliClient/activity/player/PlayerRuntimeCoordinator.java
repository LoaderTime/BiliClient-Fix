package com.RobinNotBad.BiliClient.activity.player;

import com.RobinNotBad.BiliClient.util.Logu;

/**
 * 播放运行时协调器：集中裁决播放器界面内视频、弹幕、loading、seek、临时倍速等运行态优先级。
 *
 * 这个类不直接操作 IjkMediaPlayer / DanmakuView / View。它只保存“当前谁拥有状态”的事实，
 * PlayerActivity 仍负责执行具体副作用。先用于收敛最容易互相打架的状态线，后续可逐步把更多
 * scattered booleans 迁移进来。
 */
public final class PlayerRuntimeCoordinator {
    public enum LoadingOwner {
        NONE(0),
        BUFFERING(10),
        FIRST_FRAME(20),
        SEEK(30),
        BACKGROUND_RESTORE(40),
        AUDIO_ONLY_TOGGLE(50);

        private final int priority;

        LoadingOwner(int priority) {
            this.priority = priority;
        }

        boolean canOverride(LoadingOwner current) {
            return this.priority >= current.priority;
        }
    }

    public enum SeekState {
        IDLE,
        USER_DRAGGING,
        REQUESTED,
        WAITING_RENDER,
        VERIFYING
    }

    private static final long SEEK_WATCHDOG_GRACE_MS = 1500L;
    private static final long SPEED_TRANSITION_GRACE_MS = 600L;

    private boolean danmakuVisibleByUser = true;
    private boolean longPressSpeedActive = false;
    private long lastSpeedTransitionUptimeMs = 0L;

    private SeekState seekState = SeekState.IDLE;
    private long seekToken = 0L;
    private long seekTargetMs = -1L;
    private long seekStartMs = -1L;
    private long seekGraceUntilUptimeMs = 0L;
    private long seekRequestedUptimeMs = 0L;

    private LoadingOwner loadingOwner = LoadingOwner.NONE;
    private long loadingToken = 0L;
    private long loadingVisibleSinceUptimeMs = 0L;

    public void resetForNewSession(int sessionId, boolean userDanmakuVisible, long nowUptimeMs) {
        danmakuVisibleByUser = userDanmakuVisible;
        seekState = SeekState.IDLE;
        seekTargetMs = -1L;
        seekStartMs = -1L;
        seekGraceUntilUptimeMs = 0L;
        seekRequestedUptimeMs = 0L;
        loadingOwner = LoadingOwner.NONE;
        loadingVisibleSinceUptimeMs = 0L;
        longPressSpeedActive = false;
        lastSpeedTransitionUptimeMs = nowUptimeMs;
        Logu.d("runtime", "reset session=" + sessionId + ", danmaku=" + userDanmakuVisible);
    }

    public void setDanmakuVisibleByUser(boolean visible, String reason) {
        if (danmakuVisibleByUser == visible) {
            return;
        }
        danmakuVisibleByUser = visible;
        Logu.d("runtime", "danmaku user-visible=" + visible + ", reason=" + reason);
    }

    public boolean isDanmakuVisibleByUser() {
        return danmakuVisibleByUser;
    }

    public void beginUserSeekDrag(long nowUptimeMs) {
        seekState = SeekState.USER_DRAGGING;
        seekGraceUntilUptimeMs = nowUptimeMs + SEEK_WATCHDOG_GRACE_MS;
        seekRequestedUptimeMs = nowUptimeMs;
        Logu.d("runtime", "seek drag start token=" + seekToken);
    }

    public long commitUserSeek(long fromMs, long targetMs, long nowUptimeMs, String reason) {
        seekToken++;
        seekState = SeekState.REQUESTED;
        seekTargetMs = Math.max(0L, targetMs);
        seekStartMs = Math.max(0L, fromMs);
        seekGraceUntilUptimeMs = nowUptimeMs + SEEK_WATCHDOG_GRACE_MS;
        seekRequestedUptimeMs = nowUptimeMs;
        if (loadingOwner == LoadingOwner.NONE || loadingOwner == LoadingOwner.SEEK) {
            beginLoading(LoadingOwner.SEEK, nowUptimeMs, reason);
        } else {
            Logu.d("runtime", "seek loading deferred current=" + loadingOwner + ", reason=" + reason);
        }
        Logu.d("runtime", "seek commit token=" + seekToken
                + ", from=" + seekStartMs
                + ", pos=" + seekTargetMs
                + ", reason=" + reason);
        return seekToken;
    }

    public void markSeekWaitingRender(long token, long nowUptimeMs, String reason) {
        if (token != seekToken) {
            Logu.d("runtime", "seek waiting ignored token=" + token + ", current=" + seekToken + ", reason=" + reason);
            return;
        }
        if (seekState == SeekState.IDLE) {
            Logu.d("runtime", "seek waiting ignored idle token=" + token + ", reason=" + reason);
            return;
        }
        seekState = SeekState.WAITING_RENDER;
        seekGraceUntilUptimeMs = nowUptimeMs + SEEK_WATCHDOG_GRACE_MS;
        Logu.d("runtime", "seek waiting-render token=" + token + ", reason=" + reason);
    }

    public void finishSeek(long token, String reason) {
        if (token != seekToken) {
            Logu.d("runtime", "seek finish ignored token=" + token + ", current=" + seekToken + ", reason=" + reason);
            return;
        }
        if (seekState == SeekState.IDLE) {
            Logu.d("runtime", "seek finish ignored idle token=" + token + ", reason=" + reason);
            return;
        }
        seekState = SeekState.IDLE;
        seekTargetMs = -1L;
        seekStartMs = -1L;
        seekRequestedUptimeMs = 0L;
        endLoading(LoadingOwner.SEEK, loadingToken, reason);
        Logu.d("runtime", "seek finish token=" + token + ", reason=" + reason);
    }

    public void cancelSeek(String reason) {
        seekState = SeekState.IDLE;
        seekTargetMs = -1L;
        seekStartMs = -1L;
        seekGraceUntilUptimeMs = 0L;
        seekRequestedUptimeMs = 0L;
        Logu.d("runtime", "seek cancel reason=" + reason);
    }

    public boolean isSeekGraceActive(long nowUptimeMs) {
        expireStaleSeekIfNeeded(nowUptimeMs, "isSeekGraceActive");
        return seekState != SeekState.IDLE || nowUptimeMs < seekGraceUntilUptimeMs;
    }

    public boolean expireStaleSeekIfNeeded(long nowUptimeMs, String reason) {
        if (seekState == SeekState.IDLE) {
            return false;
        }
        if (seekGraceUntilUptimeMs > 0L && nowUptimeMs < seekGraceUntilUptimeMs) {
            return false;
        }
        Logu.d("runtime", "seek expire state=" + seekState
                + ", token=" + seekToken
                + ", from=" + seekStartMs
                + ", pos=" + seekTargetMs
                + ", reason=" + reason);
        seekState = SeekState.IDLE;
        seekTargetMs = -1L;
        seekStartMs = -1L;
        seekGraceUntilUptimeMs = 0L;
        seekRequestedUptimeMs = 0L;
        if (loadingOwner == LoadingOwner.SEEK) {
            clearLoading("seekExpired:" + reason);
        }
        return true;
    }

    public long currentSeekToken() {
        return seekToken;
    }

    public long currentSeekTargetMs() {
        return seekTargetMs;
    }

    public long currentSeekStartMs() {
        return seekStartMs;
    }

    public long currentSeekRequestedUptimeMs() {
        return seekRequestedUptimeMs;
    }

    public boolean isCurrentSeekTokenActive(long token) {
        return token == seekToken && seekState != SeekState.IDLE;
    }

    public long beginLoading(LoadingOwner owner, long nowUptimeMs, String reason) {
        if (owner == LoadingOwner.NONE) {
            return loadingToken;
        }
        if (loadingOwner != LoadingOwner.NONE && !owner.canOverride(loadingOwner)) {
            Logu.d("runtime", "loading begin ignored owner=" + owner + ", current=" + loadingOwner + ", reason=" + reason);
            return loadingToken;
        }
        loadingToken++;
        loadingOwner = owner;
        loadingVisibleSinceUptimeMs = nowUptimeMs;
        Logu.d("runtime", "loading begin owner=" + owner + ", token=" + loadingToken + ", reason=" + reason);
        return loadingToken;
    }

    public boolean endLoading(LoadingOwner owner, long token, String reason) {
        if (loadingOwner != owner) {
            Logu.d("runtime", "loading end ignored owner=" + owner + ", current=" + loadingOwner + ", reason=" + reason);
            return false;
        }
        if (token > 0L && token != loadingToken) {
            Logu.d("runtime", "loading end ignored token=" + token + ", current=" + loadingToken + ", reason=" + reason);
            return false;
        }
        Logu.d("runtime", "loading end owner=" + owner + ", token=" + loadingToken + ", reason=" + reason);
        loadingOwner = LoadingOwner.NONE;
        loadingVisibleSinceUptimeMs = 0L;
        return true;
    }

    public void clearLoading(String reason) {
        if (loadingOwner == LoadingOwner.NONE) {
            return;
        }
        Logu.d("runtime", "loading clear owner=" + loadingOwner + ", token=" + loadingToken + ", reason=" + reason);
        loadingOwner = LoadingOwner.NONE;
        loadingVisibleSinceUptimeMs = 0L;
    }

    public LoadingOwner loadingOwner() {
        return loadingOwner;
    }

    public long loadingToken() {
        return loadingToken;
    }

    public long loadingVisibleSinceUptimeMs() {
        return loadingVisibleSinceUptimeMs;
    }

    public boolean isLoadingOwnedBy(LoadingOwner owner) {
        return loadingOwner == owner;
    }

    public void setLongPressSpeedActive(boolean active, long nowUptimeMs, String reason) {
        if (longPressSpeedActive == active) {
            return;
        }
        longPressSpeedActive = active;
        lastSpeedTransitionUptimeMs = nowUptimeMs;
        Logu.d("runtime", "long-press speed=" + active + ", reason=" + reason);
    }

    public boolean isLongPressSpeedActive() {
        return longPressSpeedActive;
    }

    public boolean isSpeedTransitionGraceActive(long nowUptimeMs) {
        return lastSpeedTransitionUptimeMs > 0L
                && nowUptimeMs - lastSpeedTransitionUptimeMs < SPEED_TRANSITION_GRACE_MS;
    }

    public boolean canRunDanmakuWatchdog(long nowUptimeMs,
                                         boolean hasDanmaku,
                                         boolean isPrepared,
                                         boolean isPlaying) {
        expireStaleSeekIfNeeded(nowUptimeMs, "danmakuWatchdog");
        if (!hasDanmaku || !isPrepared || !isPlaying) {
            return false;
        }
        if (!danmakuVisibleByUser) {
            return false;
        }
        if (isSeekGraceActive(nowUptimeMs)) {
            return false;
        }
        if (loadingOwner != LoadingOwner.NONE) {
            return false;
        }
        return !isSpeedTransitionGraceActive(nowUptimeMs);
    }
}
