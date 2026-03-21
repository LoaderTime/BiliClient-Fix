package com.RobinNotBad.BiliClient.activity.player;

import static android.media.AudioManager.STREAM_MUSIC;
import static com.RobinNotBad.BiliClient.util.NetWorkUtil.USER_AGENT_WEB;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AnimationDrawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.InteractionDebugActivity;
import com.RobinNotBad.BiliClient.adapter.QualitySelectorAdapter;
import com.RobinNotBad.BiliClient.adapter.ViewPointAdapter;
import com.RobinNotBad.BiliClient.api.ConfInfoApi;
import com.RobinNotBad.BiliClient.api.DanmakuApi;
import com.RobinNotBad.BiliClient.api.InteractionVideoApi;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.api.VideoInfoApi;
import com.RobinNotBad.BiliClient.event.SnackEvent;
import com.RobinNotBad.BiliClient.model.DmSegMobileReply;
import com.RobinNotBad.BiliClient.model.HighEnergyData;
import com.RobinNotBad.BiliClient.model.InteractionVideoData;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.model.Subtitle;
import com.RobinNotBad.BiliClient.model.SubtitleLink;
import com.RobinNotBad.BiliClient.model.ViewPoint;
import com.RobinNotBad.BiliClient.ui.widget.BatteryView;
import com.RobinNotBad.BiliClient.ui.widget.HighEnergyProgressBar;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;
import com.google.android.material.snackbar.Snackbar;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Inflater;

import master.flame.danmaku.controller.DrawHandler;
import master.flame.danmaku.controller.IDanmakuView;
import master.flame.danmaku.danmaku.loader.ILoader;
import master.flame.danmaku.danmaku.loader.android.DanmakuLoaderFactory;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.danmaku.model.android.Danmakus;
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser;
import master.flame.danmaku.danmaku.parser.IDataSource;
import master.flame.danmaku.danmaku.parser.android.BiliDanmukuParser;
import master.flame.danmaku.danmaku.parser.android.BiliProtobufDanmakuParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okio.BufferedSink;
import okio.Okio;
import okio.Sink;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

public class PlayerActivity extends Activity implements IjkMediaPlayer.OnPreparedListener {
    // TimerTask/OkHttp/WebSocket 回调运行在后台线程；用 volatile 确保销毁与会话切换状态能被及时看见，避免 release 后仍访问 ijkPlayer。
    private volatile boolean destroyed = false;

    private IjkMediaPlayer ijkPlayer;
    private IDanmakuView mDanmakuView;
    private DanmakuContext mContext;

    /**
     * 弹幕卡死自愈 watchdog（低频轮询）。
     * <p>
     * 兼容 Android 4.4：仅用 Handler + postDelayed，不依赖高版本 API。
     */
    private static final long DANMAKU_WATCHDOG_INTERVAL_MS = 2000L;
    /** 连续判定“弹幕时间轴不前进”的次数阈值（2 次=约 4 秒） */
    private static final int DANMAKU_WATCHDOG_STUCK_THRESHOLD_COUNT = 2;
    /** watchdog 软恢复后，短时间内再次卡住则升级为硬恢复 */
    private static final long DANMAKU_WATCHDOG_HARD_RECOVERY_WINDOW_MS = 8000L;
    private Runnable danmakuWatchdogRunnable;
    private long lastWatchdogVideoPos = -1L;
    private long lastWatchdogDanmakuTime = -1L;
    private int danmakuWatchdogStuckCount = 0;
    private long lastDanmakuWatchdogRecoverUptimeMs = 0L;
    private boolean lastDanmakuWatchdogRecoverWasSoft = false;

    /**
     * 由播放器侧维护的“最新播放位置”缓存。
     * <p>
     * 目的：避免在 DFM 的 updateTimer 线程里高频直接调用 ijkPlayer.getCurrentPosition()，
     * 参考 PiliPlus 的“播放器位置驱动弹幕”思路，改为由播放器侧推送/缓存位置，弹幕线程只消费缓存。
     */
    private volatile long latestPlayerPositionMs = 0L;
    private volatile long latestPlayerPositionUptimeMs = 0L;

    /** 当前弹幕源缓存，供 watchdog 硬恢复时直接重建弹幕会话使用 */
    private volatile String currentDanmakuFilePath;
    private volatile java.util.List<DmSegMobileReply> currentDanmakuSegments;
    private volatile int currentDanmakuSourceSessionId = -1;
    private volatile long currentDanmakuSourceCid = -1L;
    private volatile int currentDanmakuPreparedSessionId = -1;
    private volatile int currentDanmakuPreparedPrepareSeq = -1;

    /** watchdog 硬恢复时，在新弹幕 prepare 完成后自动 start/seek 到当前位置 */
    private volatile boolean pendingDanmakuRestartAfterPrepare = false;
    private volatile long pendingDanmakuRestartPositionMs = 0L;
    private volatile int pendingDanmakuRestartSessionId = -1;
    private volatile int pendingDanmakuRestartPrepareSeq = -1;
    private volatile boolean pendingDanmakuRestartVisible = true;

    /**
     * ijkPlayer.getCurrentPosition() 在部分设备/网络流上可能出现轻微“回跳”，会导致弹幕时间轴倒退，从而产生视觉抖动。
     * 这里定义一个容忍阈值：若回跳很小则钳制为上一次值；若回跳很大则视为用户 seek/跳转，允许重置。
     */
    private static final long DANMAKU_TIMER_BACKWARD_TOLERANCE_MS = 120L;
    private static final long DANMAKU_TIMER_BACKWARD_RESET_THRESHOLD_MS = 2000L;
    private static final long DANMAKU_TIMER_SMOOTH_REBASE_INTERVAL_MS = 400L;
    private static final long DANMAKU_TIMER_SMOOTH_DRIFT_REBASE_MS = 180L;
    private static final long DANMAKU_TIMER_SMOOTH_MAX_LEAD_MS = 250L;
    private static final long DANMAKU_TIMER_SMOOTH_LOG_INTERVAL_MS = 2000L;

    /**
     * 记录最近一次“显式 seek”（用户拖动进度条/方向键快进快退/重播等），用于让弹幕时间轴允许回退。
     * 避免因为时间轴钳制导致“向后 seek 后弹幕短暂冻结”。
     */
    private static final long DANMAKU_EXPLICIT_SEEK_GRACE_MS = 1500L;
    private volatile long lastExplicitSeekUptimeMs = 0L;
    private volatile long lastExplicitSeekTargetMs = -1L;

    private void markExplicitSeek(long targetMs) {
        lastExplicitSeekUptimeMs = android.os.SystemClock.uptimeMillis();
        lastExplicitSeekTargetMs = targetMs;
    }

    /**
     * Danmaku prepare 的幂等控制：每次 streamDanmaku() 递增。
     * <p>
     * 目的：同一 playerSession 内多次刷新弹幕时，保证只有“最后一次” prepare 生效，
     * 避免旧 runnable/回调在新会话后落地执行。
     */
    private final AtomicInteger danmakuPrepareSeq = new AtomicInteger(0);

    /** 上一次用于 prepare 的 parser（用于提前 release，避免 dataSource 泄漏窗口期） */
    private volatile BaseDanmakuParser lastDanmakuParser;

    private SurfaceView surfaceView;
    private TextureView textureView;
    private SurfaceTexture mSurfaceTexture;

    private SubtitleLink[] subtitleLinks = null;
    private Subtitle[] subtitles = null;
    private int subtitle_curr_index, subtitle_count;
    private float subtitle_delta;

    private RelativeLayout layout_control, layout_top, layout_video, layout_card_bg, layout_audio_only;
    private LinearLayout layout_speed, right_control, loading_info;
    private RelativeLayout bottom_buttons;
    private HorizontalScrollView right_second;
    private LinearLayout card_subtitle, card_danmaku_send, card_page_selector, card_quality_selector, card_viewpoint_selector;

    private ImageView img_loading;
    private AnimationDrawable anim_loading;
    private ImageButton btn_control, btn_danmaku, btn_loop, btn_rotate, btn_menu, btn_subtitle, btn_danmaku_send,
            btn_audio_only, btn_page_selector, btn_auto_next, btn_quality, btn_viewpoint, btn_debug;
    private HighEnergyProgressBar seekbar_progress;
    private SeekBar seekbar_speed;
    private TextView text_progress, text_online, text_volume, loading_text0, loading_text1, text_speed, text_newspeed;
    public TextView text_title, text_subtitle, text_audio_title, text_audio_subtitle;

    private Timer progressTimer, speedTimer, loadingTimer, onlineTimer;
    private Handler mainHandler;
    private Runnable danmakuSyncRunnable;
    private String video_url, danmaku_url;
    private MediaSession mediaSession;

    /** 递增的播放会话编号，用于让旧的 TimerTask / Runnable 发现自己已过期并尽快退出 */
    private volatile int playerSessionId = 0;
    /** setDisplay() 已对当前 ijkPlayer 设置完 option（避免 surface 回调过早触发 prepare） */
    private boolean displayConfigured = false;
    /** 当前播放会话是否已经请求过一次 prepare（确保 MPPrepare 每会话只触发一次） */
    private boolean prepareRequested = false;
    /** onDestroy 可能多路径进入，做一次性释放保护 */
    private volatile boolean resourcesReleased = false;

    /** TextureView 模式下由 SurfaceTexture 包装出来的 Surface（需手动 release 避免 native 泄漏） */
    private Surface textureSurface;
    private SurfaceTexture attachedSurfaceTexture; // textureSurface 对应的 SurfaceTexture

    /** SurfaceView 模式下持有唯一的 holder/callback，避免重复 addCallback 导致回调叠加/泄漏 */
    private SurfaceHolder surfaceHolder;
    /**
     * 开启后台播放时，切到其它应用后播放器可能仍在继续播放；
     * 若此时 SurfaceView 被系统销毁并在回前台时重建，不能在 surfaceCreated() 里再主动 seek，
     * 否则 ijk 可能因为非精确 seek 回退到更早关键帧，表现为“前台瞬间时间正确，开始播后倒退几秒”。
     */
    private volatile boolean skipSeekOnNextSurfaceCreatedFromBackgroundPlayback = false;
    /**
     * 后台连续播放期间若渲染 surface 在后台被系统销毁，则回前台时不要在 onResume() 提前消费恢复标记；
     * 应等待 surfaceCreated()/onSurfaceTextureAvailable() 统一走恢复链路。
     */
    private volatile boolean backgroundPlaybackSurfaceRecreated = false;

    /**
     * onResume 中延迟消费后台播放恢复标记，避免部分 ROM 的 surfaceDestroyed/surfaceCreated 时序竞态。
     * <p>
     * 典型现象：回前台时 onResume 先于 surfaceDestroyed/surfaceCreated 执行，导致错误地走“非重建”分支，进而出现错帧/回退。
     */
    private Runnable consumeBackgroundPlaybackFlagRunnable;
    private static final long CONSUME_BACKGROUND_PLAYBACK_FLAG_DELAY_MS = 520L;

    /**
     * Activity 生命周期导致的暂停（未开启后台播放时 onPause() 主动 pause），用于在 onResume() 自动恢复播放。
     * <p>
     * 目的：修复“开始播放/后台切回前台 0~1s 内意外暂停，且无法自动 resume”。
     */
    private volatile boolean pausedByLifecycle = false;

    /**
     * 视频已播放结束后若经历过后台/Surface 销毁，再次点击重播时直接重建 player session。
     * <p>
     * 原因：旧 ijkPlayer 会话在 completion + surface 重建后，可能出现音频恢复但视频渲染链未恢复的黑屏状态。
     */
    private volatile boolean completionReplayNeedsRebuild = false;

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            if (destroyed)
                return;
            Logu.v("surface", "surfaceCreated");
            handleRenderSurfaceAvailable("surfaceCreated", true);
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            Logu.v("surface", "surfaceDestroyed");
            if (skipSeekOnNextSurfaceCreatedFromBackgroundPlayback) {
                backgroundPlaybackSurfaceRecreated = true;
                Logu.d("surface", "surfaceDestroyed during background playback: mark recreate");
            }
            if (finishWatching && isPrepared && !isLiveMode) {
                completionReplayNeedsRebuild = true;
                Logu.d("surface", "surfaceDestroyed after completion: mark replay rebuild");
            }
            if (ijkPlayer != null) {
                try {
                    ijkPlayer.setDisplay(null);
                } catch (Exception ignore) {
                }
            }
        }
    };

    /** 直播弹幕 listener 持有 Activity 引用，需要在销毁时主动 release 避免泄漏 */
    private PlayerDanmuClientListener liveDanmuListener;

    /** 直播弹幕 WebSocket（onDestroy 必须 close，避免线程/引用残留） */
    private WebSocket liveWebSocket = null;

    private boolean isPlaying, isPrepared, hasDanmaku,
            isOnlineVideo, isLiveMode, isSeeking, isDanmakuVisible;
    private boolean menu_opened = false;
    private boolean isAudioOnlyMode = false;
    private boolean isLocalAudioFile = false; // 标记是否为本地音频文件
    /** 当前是否正在等待视频首帧渲染完成（用于避免“裸黑屏”） */
    private volatile boolean waitingForFirstVideoFrame = false;
    /** 当前播放会话是否已经收到过视频首帧渲染事件 */
    private volatile boolean firstVideoFrameRendered = false;
    /** 进入“等待首帧”状态的时间戳（用于兜底：某些场景可能收不到 VIDEO_RENDERING_START） */
    private volatile long waitingForFirstVideoFrameUptimeMs = 0L;
    /** 进入“等待首帧”时的起始播放位置；用于判断是否真的发生了自然推进，避免用绝对进度误判。 */
    private volatile long waitingForFirstVideoFrameStartPosMs = 0L;

    /** 本地/缓存视频在部分设备上可能收不到首帧事件，增加超时兜底避免 loading 永久悬浮 */
    private static final long FIRST_VIDEO_FRAME_FALLBACK_TIMEOUT_MS = 1200L;
    /** “加载画面/恢复画面”提示最短展示时长，避免一闪而过或肉眼完全看不到 */
    private static final long FRAME_LOADING_MIN_SHOW_MS = 300L;
    private Runnable firstVideoFrameFallbackRunnable;
    private Runnable frameLoadingDelayedHideRunnable;
    private volatile long frameLoadingVisibleSinceUptimeMs = 0L;
    /** 在线后台恢复触发 rebuild 后，给新 session 一个更快的首帧 loading 收敛兜底。 */
    private static final long ONLINE_REBUILD_FIRST_VIDEO_FRAME_FALLBACK_MS = 420L;
    private static final long ONLINE_REBUILD_FIRST_VIDEO_FRAME_MOVED_MS = 120L;
    private Runnable onlineRebuildFirstVideoFrameFallbackRunnable;
    private volatile boolean pendingOnlineRebuildFirstVideoFrameFallback = false;

    /**
     * 开启后台播放时回前台，SurfaceView surface 可能被销毁并重建。
     * 这段时间 audio/进度会继续走，但视频帧可能需要“触发渲染”才能尽快显示到新 surface。
     */
    private volatile boolean inBackgroundSurfaceRestore = false;
    private volatile long backgroundSurfaceRestoreStartUptimeMs = 0L;
    private Runnable backgroundSurfaceRestoreRunnable;
    /**
     * 某些 ROM/ijk 组合下，Surface 重建后即使画面已经显示，也可能不触发 MEDIA_INFO_VIDEO_RENDERING_START。
     * 这里加一层 UI 兜底：在恢复流程进行一段时间后，根据“播放进度在推进 + surface 有效”推断画面已恢复，自动关闭 loading。
     */
    private Runnable backgroundSurfaceRestoreAutoHideRunnable;
    private Runnable backgroundSurfaceRestoreProbeRunnable;
    private Runnable localBackgroundSurfaceFrameCorrectionRunnable;
    private Runnable localBackgroundSurfaceAudioRestoreRunnable;
    private volatile long backgroundSurfaceRestoreStartPosMs = 0L;
    private volatile int backgroundSurfaceRestoreStep = 0;
    private volatile long backgroundSurfaceRestoreBasePosMs = -1L;
    /** 背景恢复阶段是否收到过真正的视频首帧事件（而非 outputFps/进度推进推断）。 */
    private volatile boolean backgroundSurfaceRestoreFrameEventReceived = false;
    /** 仅用于日志：记录恢复期是否观测到 outputFps>0，避免重复刷屏。 */
    private volatile boolean backgroundSurfaceRestoreOutputFpsObserved = false;
    /** 第一次观测到 outputFps>0 的时间，用于避免假阳性时过早判定恢复完成。 */
    private volatile long backgroundSurfaceRestoreOutputFpsObservedUptimeMs = 0L;

    private static final long BACKGROUND_SURFACE_RESTORE_STEP0_DELAY_MS = 40L;
    private static final long BACKGROUND_SURFACE_RESTORE_STEP1_DELAY_MS = 60L;
    private static final long BACKGROUND_SURFACE_RESTORE_STEP2_DELAY_MS = 100L;
    private static final long BACKGROUND_SURFACE_RESTORE_STEP3_DELAY_MS = 160L;
    private static final long BACKGROUND_SURFACE_RESTORE_STEP4_DELAY_MS = 260L;
    private static final long BACKGROUND_SURFACE_RESTORE_GIVEUP_DELAY_MS = 650L;
    private static final long BACKGROUND_SURFACE_RESTORE_AUTO_HIDE_DELAY_MS = 850L;
    private static final long BACKGROUND_SURFACE_RESTORE_PROBE_INTERVAL_MS = 60L;
    private static final long BACKGROUND_SURFACE_RESTORE_HEURISTIC_OBSERVED_MS = 220L;
    private static final long BACKGROUND_SURFACE_RESTORE_HEURISTIC_MOVED_MS = 180L;
    private static final long BACKGROUND_SURFACE_RESTORE_HEURISTIC_SURFACELESS_GRACE_MS = 180L;
    private static final long BACKGROUND_SURFACE_RESTORE_HEURISTIC_FORCE_COMPLETE_MS = 650L;
    /** 分级恢复在多次 kick 后，尝试一次极小位移的 render-refresh seek，强制催出首帧。 */
    private static final long BACKGROUND_SURFACE_RESTORE_RENDER_REFRESH_SEEK_OFFSET_MS = 16L;
    /** sticky 记录恢复期曾观测到的输出帧率，避免 progressTimer 漏采样时无法完成恢复。 */
    private volatile float backgroundSurfaceRestoreLastObservedOutputFps = 0f;
    /** 本地后台恢复时做一次极小位移的准确 seek，强制淘汰旧/错帧；音频副作用由短暂静音兜底。 */
    private static final long LOCAL_BACKGROUND_SURFACE_FRAME_CORRECTION_DELAY_MS = 90L;
    private static final long LOCAL_BACKGROUND_SURFACE_FRAME_CORRECTION_SETTLE_MS = 140L;
    private static final long LOCAL_BACKGROUND_SURFACE_FRAME_CORRECTION_SEEK_OFFSET_MS = 8L;
    private static final long LOCAL_BACKGROUND_SURFACE_FRAME_CORRECTION_MOVED_MS = 96L;
    private volatile boolean localBackgroundSurfaceFrameCorrectionPending = false;
    private volatile boolean localBackgroundSurfaceFrameCorrectionTriggered = false;
    private volatile long localBackgroundSurfaceFrameCorrectionTriggeredUptimeMs = 0L;
    /** correction seek 成功后的基准位置；恢复完成判定必须基于这个位置之后的“自然推进”，不能把 seek 自身算进去。 */
    private volatile long localBackgroundSurfaceFrameCorrectionBasePosMs = -1L;
    private static final long LOCAL_BACKGROUND_SURFACE_AUDIO_MUTE_MIN_MS = 160L;
    private static final long LOCAL_BACKGROUND_SURFACE_AUDIO_MUTE_MAX_MS = 360L;
    private static final long LOCAL_BACKGROUND_SURFACE_AUDIO_RESTORE_MARGIN_MS = 48L;
    private volatile boolean localBackgroundSurfaceAudioMutedForCorrection = false;
    private volatile long localBackgroundSurfaceAudioRestorePosMs = -1L;
    private volatile long localBackgroundSurfaceAudioMuteStartUptimeMs = 0L;

    /** 本地/缓存视频后台回前台黑屏兜底：进一步压缩等待窗口，优先更快重建。 */
    private static final long LOCAL_BACKGROUND_SURFACE_BLACK_REBUILD_TIMEOUT_MS = 1200L;

    private Runnable localBackgroundSurfaceBlackRebuildRunnable;

    /**
     * 本地视频拖动进度条时，硬解/关键帧对齐可能导致 seek 落点比目标值落后数秒。
     * 这里做一次延迟校验：若落点明显落后，则把目标往前“推”一次，尽量避免肉眼回退。
     */
    private static final long LOCAL_SEEK_VERIFY_DELAY_MS = 550L;
    private static final long LOCAL_SEEK_BACKWARD_TOLERANCE_MS = 900L;
    private static final int LOCAL_SEEK_VERIFY_MAX_RETRY = 2;
    private Runnable localSeekVerifyRunnable;
    private volatile long localSeekVerifyTargetMs = -1L;
    private volatile int localSeekVerifyRetry = 0;

    /**
     * 后台连续播放返回前台后，若首帧迟迟不出，则延迟触发一次轻量渲染 kick 来催出画面。
     * <p>
     * 参考 PiliPlus：允许后台继续播放时，优先保留旧 session/旧缓冲，不要过早进入 reopen/seek。
     */
    private static final long BACKGROUND_SURFACE_REFRESH_TIMEOUT_MS = 220L;
    /** 保留旧常量名，仅为兼容历史代码注释；当前在线视频恢复不再使用 seek 催首帧。 */
    private static final long BACKGROUND_SURFACE_REFRESH_SEEK_OFFSET_MS = 33L;
    private Runnable backgroundSurfaceRefreshRunnable;
    private volatile boolean backgroundSurfaceRefreshPending = false;
    private volatile boolean backgroundSurfaceRefreshTriggered = false;
    /** 最近一次主动 refresh 触发时刻/目标位置；用于避免 refresh 刚触发就被旧采样误判为“已恢复”。 */
    private volatile long backgroundSurfaceRefreshTriggeredUptimeMs = 0L;
    private volatile long backgroundSurfaceRefreshTriggeredPosMs = -1L;
    /**
     * 后台返回前台但旧 player session 仍在时，对渲染输出做一次短暂健康检查；
     * 若迟迟没有有效输出帧，则直接重建 session，而不是继续等待旧渲染链“自己恢复”。
     */
    /**
     * 在线视频恢复：先给旧渲染链一个短暂自恢复窗口；若仍未恢复，则升级到 refresh / rebuild。
     */
    private static final long BACKGROUND_RESUME_RENDER_HEALTHCHECK_TIMEOUT_ONLINE_MS = 650L;
    /** 在线恢复做过一次 refresh kick 后，再给一次较短观察窗口；仍失败则直接重建 session。 */
    private static final long BACKGROUND_RESUME_RENDER_HEALTHCHECK_TIMEOUT_ONLINE_AFTER_REFRESH_MS = 900L;
    /** refresh 刚触发后，至少等待一小段时间并确认“refresh 后的自然推进”，再允许判定恢复完成。 */
    private static final long BACKGROUND_SURFACE_RESTORE_HEURISTIC_AFTER_REFRESH_MIN_MS = 180L;
    private static final long BACKGROUND_SURFACE_RESTORE_HEURISTIC_AFTER_REFRESH_MOVED_MS = 120L;
    private Runnable backgroundResumeRenderHealthCheckRunnable;
    private volatile long backgroundResumeRenderHealthCheckStartPosMs = -1L;

    // 切换听视频模式时：强制在新会话 onPrepared 后跳回切换前进度，并尽量用准确 seek 避免回退到关键帧。
    private long pendingAudioOnlyToggleSeekMs = -1L;

    /**
     * 强制在本次 onPrepared 后 seek 到指定位置（与“从上次播放位置”开关无关）。
     * 用途：切清晰度/后台恢复超时重建等。
     */
    private volatile long pendingForcedSeekMs = -1L;

    private boolean shouldRestoreFromLastPosition() {
        return SharedPreferencesUtil.getBoolean("player_from_last", true)
                && !isLiveMode
                && progress_history > 5000L;
    }

    /**
     * 返回本次建链/准备阶段是否需要启用 accurate seek 的目标位置。
     * - 听视频模式切换：优先使用 pendingAudioOnlyToggleSeekMs
     * - 历史进度恢复 / 切清晰度后恢复：使用 progress_history
     */
    private long getPendingAccurateSeekTargetMs() {
        if (!isLiveMode && pendingAudioOnlyToggleSeekMs >= 0L) {
            return pendingAudioOnlyToggleSeekMs;
        }
        if (!isLiveMode && pendingForcedSeekMs >= 0L) {
            return pendingForcedSeekMs;
        }
        if (shouldRestoreFromLastPosition()) {
            return progress_history;
        }
        return -1L;
    }

    private int video_all, video_now, video_now_last;
    private long progress_history;
    private String progress_str;

    private int screen_width, screen_height;
    private int video_width, video_height;

    private AudioManager audioManager;

    private ScaleGestureDetector scaleGestureDetector;
    private ViewScaleGestureListener scaleGestureListener;
    private float previousX, previousY;
    private boolean gesture_moved, gesture_scaled, gesture_click_disabled;
    private float video_origX, video_origY;
    private long timestamp_click;
    private boolean onLongClick = false;

    private final float[] speed_values = {0.5F, 0.75F, 1.0F, 1.25F, 1.5F, 1.75F, 2.0F, 3.0F};
    private final String[] speed_strs = {"x 0.5", "x 0.75", "x 1.0", "x 1.25", "x 1.5", "x 1.75", "x 2.0", "x 3.0"};

    /**
     * 当前播放速度（用于弹幕时间轴插值平滑）。
     * 说明：IjkMediaPlayer 不同版本 getSpeed 支持不一致，这里用 Activity 内部状态做单一真相源。
     */
    private volatile float playbackSpeed = 1.0f;

    private float getPlaybackSpeed() {
        return playbackSpeed;
    }

    private void setPlaybackSpeed(float speed) {
        playbackSpeed = speed;
        try {
            if (ijkPlayer != null)
                ijkPlayer.setSpeed(speed);
        } catch (Exception ignore) {
        }
        try {
            if (mDanmakuView != null)
                mDanmakuView.setSpeed(speed);
        } catch (Exception ignore) {
        }
    }

    private void updateLatestPlayerPosition(long positionMs) {
        latestPlayerPositionMs = Math.max(0L, positionMs);
        latestPlayerPositionUptimeMs = android.os.SystemClock.uptimeMillis();
        if (pendingDanmakuRestartAfterPrepare && pendingDanmakuRestartSessionId == playerSessionId) {
            pendingDanmakuRestartPositionMs = latestPlayerPositionMs;
        }
    }

    private long getLatestPlayerPositionForDanmaku() {
        long cached = Math.max(0L, latestPlayerPositionMs);
        long cachedAt = latestPlayerPositionUptimeMs;
        if (cachedAt > 0L) {
            long age = android.os.SystemClock.uptimeMillis() - cachedAt;
            if (age <= 1500L) {
                return cached;
            }
        }
        if (seekbar_progress != null) {
            try {
                return Math.max(cached, seekbar_progress.getProgress());
            } catch (Exception ignore) {
            }
        }
        return cached;
    }

    private void cacheCurrentDanmakuSource(String danmakuFile,
                                           java.util.List<DmSegMobileReply> protobufSegments) {
        if (protobufSegments != null && !protobufSegments.isEmpty()) {
            currentDanmakuFilePath = null;
            currentDanmakuSegments = new ArrayList<>(protobufSegments);
            currentDanmakuSourceSessionId = playerSessionId;
            currentDanmakuSourceCid = cid;
            return;
        }

        if (danmakuFile != null && !danmakuFile.isEmpty()) {
            currentDanmakuFilePath = danmakuFile;
            currentDanmakuSegments = null;
            currentDanmakuSourceSessionId = playerSessionId;
            currentDanmakuSourceCid = cid;
            return;
        }
        // 非直播场景下，若本次传入的是“空源”，保留上一次有效弹幕源。
        // 这样 watchdog 硬恢复时不会因为一次空调用把可重建数据冲掉。
    }

    private void resetDanmakuPreparedState() {
        currentDanmakuPreparedSessionId = -1;
        currentDanmakuPreparedPrepareSeq = -1;
    }

    private void markDanmakuPrepared(int session, int seq) {
        currentDanmakuPreparedSessionId = session;
        currentDanmakuPreparedPrepareSeq = seq;
    }

    private boolean isCurrentDanmakuPrepared() {
        if (currentDanmakuPreparedSessionId != playerSessionId)
            return false;
        if (currentDanmakuPreparedPrepareSeq != danmakuPrepareSeq.get())
            return false;
        if (mDanmakuView == null)
            return false;
        try {
            return mDanmakuView.isPrepared();
        } catch (Exception ignore) {
            return false;
        }
    }

    private boolean isCurrentDanmakuSourceAvailable() {
        if (currentDanmakuSourceSessionId != playerSessionId)
            return false;
        if (currentDanmakuSourceCid != cid)
            return false;
        boolean hasFile = false;
        if (currentDanmakuFilePath != null && !currentDanmakuFilePath.isEmpty()) {
            try {
                hasFile = new File(currentDanmakuFilePath).exists();
            } catch (Exception ignore) {
            }
        }
        boolean hasSegments = currentDanmakuSegments != null && !currentDanmakuSegments.isEmpty();
        return hasFile || hasSegments;
    }

    private void clearDanmakuRecoveryState() {
        pendingDanmakuRestartAfterPrepare = false;
        pendingDanmakuRestartPositionMs = 0L;
        pendingDanmakuRestartSessionId = -1;
        pendingDanmakuRestartPrepareSeq = -1;
        pendingDanmakuRestartVisible = true;
        lastDanmakuWatchdogRecoverUptimeMs = 0L;
        lastDanmakuWatchdogRecoverWasSoft = false;
    }

    private void performDanmakuSoftRecovery(long positionMs, @NonNull String reason) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> performDanmakuSoftRecovery(positionMs, reason));
            return;
        }
        if (destroyed || resourcesReleased || mDanmakuView == null)
            return;
        if (!isDanmakuVisible)
            return;

        long target = Math.max(0L, positionMs);
        try {
            // 重要：这里显式模拟“用户关再开一次弹幕”。
            // 仅 showAndResumeDrawTask(position) 在 handler 仍处于 visible=true 时可能被 DrawHandler 直接短路，
            // 无法真正触发 RESUME，因此必须先 hide 再 show。
            mDanmakuView.hideAndPauseDrawTask();
        } catch (Exception ignore) {
        }
        try {
            mDanmakuView.showAndResumeDrawTask(target);
        } catch (Exception ignore) {
        }
        if (!isPlaying) {
            try {
                mDanmakuView.pause();
            } catch (Exception ignore) {
            }
        }
        Logu.w("danmaku", "watchdog soft recover: reason=" + reason + ", pos=" + target);
    }

    private void requestDanmakuHardRecovery(long positionMs, @NonNull String reason) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> requestDanmakuHardRecovery(positionMs, reason));
            return;
        }
        if (destroyed || resourcesReleased || isLiveMode || !hasDanmaku || mDanmakuView == null)
            return;

        if (!isCurrentDanmakuPrepared()) {
            Logu.w("danmaku", "watchdog hard recover skipped: danmaku not ready in current session, reason=" + reason);
            return;
        }

        if (!isCurrentDanmakuSourceAvailable()) {
            Logu.w("danmaku", "watchdog hard recover skipped: source not match current session/cid, reason="
                    + reason + ", sourceSession=" + currentDanmakuSourceSessionId
                    + ", playerSession=" + playerSessionId
                    + ", sourceCid=" + currentDanmakuSourceCid + ", cid=" + cid);
            performDanmakuSoftRecovery(positionMs, reason + "-fallbackSoft");
            return;
        }

        final String cachedFile = currentDanmakuFilePath;
        final java.util.List<DmSegMobileReply> cachedSegments = currentDanmakuSegments;
        final boolean hasCachedFile = cachedFile != null && !cachedFile.isEmpty() && new File(cachedFile).exists();
        final boolean hasCachedSegments = cachedSegments != null && !cachedSegments.isEmpty();

        if (!hasCachedFile && !hasCachedSegments) {
            Logu.w("danmaku", "watchdog hard recover skipped: no cached source, reason=" + reason);
            performDanmakuSoftRecovery(positionMs, reason + "-fallbackSoft");
            return;
        }

        pendingDanmakuRestartAfterPrepare = true;
        pendingDanmakuRestartPositionMs = Math.max(0L, positionMs);
        pendingDanmakuRestartSessionId = playerSessionId;
        pendingDanmakuRestartVisible = isDanmakuVisible;
        Logu.w("danmaku", "watchdog hard recover: reason=" + reason + ", pos="
                + pendingDanmakuRestartPositionMs + ", source="
                + (hasCachedSegments ? "protobuf" : "file"));

        streamDanmaku(hasCachedSegments ? null : cachedFile,
                hasCachedSegments ? cachedSegments : null,
                playerSessionId,
                danmakuPrepareSeq.incrementAndGet(),
                true,
                true);
    }

    private void handleDanmakuPrepared(int session, int seq, boolean isProtobuf) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> handleDanmakuPrepared(session, seq, isProtobuf));
            return;
        }
        if (!isDanmakuRequestValid(session, seq) || mDanmakuView == null)
            return;

        try {
            mDanmakuView.setSpeed(getPlaybackSpeed());
        } catch (Exception ignore) {
        }

        if (!pendingDanmakuRestartAfterPrepare)
            return;
        if (session != pendingDanmakuRestartSessionId || seq != pendingDanmakuRestartPrepareSeq)
            return;

        long target = Math.max(0L, pendingDanmakuRestartPositionMs);
        boolean shouldShow = pendingDanmakuRestartVisible && isDanmakuVisible;
        pendingDanmakuRestartAfterPrepare = false;
        pendingDanmakuRestartPositionMs = 0L;
        pendingDanmakuRestartSessionId = -1;
        pendingDanmakuRestartPrepareSeq = -1;
        pendingDanmakuRestartVisible = true;

        try {
            mDanmakuView.start(target);
        } catch (Exception e) {
            Logu.w("danmaku", "hard recover start failed: " + e.getMessage());
            try {
                mDanmakuView.resume();
            } catch (Exception ignore) {
            }
        }

        if (shouldShow) {
            applyDanmakuVisibility(true, "hardRecoverPrepared");
        } else {
            try {
                mDanmakuView.hideAndPauseDrawTask();
            } catch (Exception ignore) {
            }
        }
        if (!isPlaying) {
            try {
                mDanmakuView.pause();
            } catch (Exception ignore) {
            }
        }
        Logu.d("danmaku", "hard recover prepared: pos=" + target + ", protobuf=" + isProtobuf);
    }

    private boolean finishWatching = false;
    private boolean loop_enabled;
    private boolean auto_next_enabled = false;

    private BatteryView batteryView;
    private BatteryManager batteryManager;

    private File danmakuFile;

    private boolean screen_landscape, screen_round;

    public String online_number = "0";

    private long aid, cid, mid;

    private ArrayList<String> pagenames;
    private ArrayList<Long> cids;
    private int currentPageIndex = 0;
    private String videoTitle;

    private String[] qnStrList;
    private int[] qnValueList;
    private int currentQuality = 0;

    private InteractionVideoData interactionData;
    private long interactionGraphVersion = 0;
    private long currentEdgeId = 0;
    private long initialEdgeId = 0;
    private InteractionVideoData.InteractionQuestion currentQuestion = null;
    private boolean questionShown = false;
    private LinearLayout interactionChoiceLayout;

    @Override
    public void onBackPressed() {
        if (!SharedPreferencesUtil.getBoolean("back_disable", false))
            super.onBackPressed();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(BiliTerminal.getFitDisplayContext(newBase));
    }

    private boolean getExtras() {
        Intent intent = getIntent();
        if (intent == null)
            return false;

        video_url = intent.getStringExtra("url");
        danmaku_url = intent.getStringExtra("danmaku");
        String title = intent.getStringExtra("title");

        if (video_url == null)
            return false;
        if (danmaku_url != null)
            Logu.v("弹幕", danmaku_url);
        Logu.v("视频", video_url);
        Logu.v("标题", title);
        text_title.setText(title);
        videoTitle = title;

        aid = intent.getLongExtra("aid", 0);
        cid = intent.getLongExtra("cid", 0);
        mid = intent.getLongExtra("mid", 0);

        // 统一进度单位。
        // - PlayerApi.last_play_time 为“秒”，但 IjkMediaPlayer.seekTo 为“毫秒”。
        // - 统一约定：Intent 内部传“毫秒”。
        progress_history = 0L;
        try {
            Bundle extras = intent.getExtras();
            if (extras != null && extras.containsKey("progress")) {
                Object v = extras.get("progress");
                if (v instanceof Long) {
                    progress_history = (Long) v;
                } else if (v instanceof Integer) {
                    // 旧版本：秒
                    progress_history = ((Integer) v).longValue();
                } else if (v instanceof String) {
                    try {
                        progress_history = Long.parseLong((String) v);
                    } catch (Exception ignore) {
                    }
                }
            }
        } catch (Exception ignore) {
        }
        if (progress_history < 0L)
            progress_history = 0L;
        updateLatestPlayerPosition(progress_history);
        Logu.d("history", String.valueOf(progress_history));

        isLiveMode = intent.getBooleanExtra("live_mode", false);
        isOnlineVideo = video_url.contains("http");
        hasDanmaku = !danmaku_url.equals("");

        if (intent.hasExtra("pagenames") && intent.hasExtra("cids")) {
            pagenames = intent.getStringArrayListExtra("pagenames");
            ArrayList<Long> cidList = new ArrayList<>();
            long[] cidArray = intent.getLongArrayExtra("cids");
            if (cidArray != null) {
                for (long c : cidArray) {
                    cidList.add(c);
                }
            }
            cids = cidList;
            currentPageIndex = intent.getIntExtra("currentPageIndex", 0);
        }

        initialEdgeId = intent.getLongExtra("edgeId", 0);
        if (initialEdgeId > 0) {
            currentEdgeId = initialEdgeId;
        }

        if (intent.hasExtra("qnStrList") && intent.hasExtra("qnValueList")) {
            qnStrList = intent.getStringArrayExtra("qnStrList");
            qnValueList = intent.getIntArrayExtra("qnValueList");
            currentQuality = intent.getIntExtra("currentQuality", SharedPreferencesUtil.getInt("play_qn", 16));
        }

        return true;
    }

    @SuppressLint("SimpleDateFormat")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Logu.v("加载", "加载");
        super.onCreate(savedInstanceState);

        screen_landscape = SharedPreferencesUtil.getBoolean("player_autolandscape", false)
                || SharedPreferencesUtil.getBoolean("ui_landscape", false);
        if (SharedPreferencesUtil.getBoolean("dev_player_rotate_software", false) && screen_landscape) {
            MsgUtil.showMsg("不支持默认横屏！");
            screen_landscape = false;
        } else {
            setRequestedOrientation(screen_landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }

        setContentView(R.layout.activity_player);
        findview();
        if (!getExtras()) {
            finish();
            return;
        }

        initUI();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PLAYER_MEDIA_SESSION_ENABLE, false)) {
            initMediaSession();
        }

        IjkMediaPlayer.loadLibrariesOnce(null);

        ijkPlayer = new IjkMediaPlayer();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
            batteryView.setPower(batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        } else
            batteryView.setVisibility(View.GONE);

        loop_enabled = SharedPreferencesUtil.getBoolean("player_loop", false);
        // 从设置读取听视频模式的默认值
        isAudioOnlyMode = SharedPreferencesUtil.getBoolean("player_audio_only", false);
        // 从Intent读取是否为仅音频模式（用于播放本地音频文件）
        isLocalAudioFile = getIntent().getBooleanExtra("audio_only", false);
        if (isLocalAudioFile) {
            isAudioOnlyMode = true;
        }
        img_loading.setImageResource(R.drawable.loading_tv_shaking);
        anim_loading = (AnimationDrawable) img_loading.getDrawable();
        anim_loading.start();

        File cachepath = getCacheDir();
        if (!cachepath.exists())
            cachepath.mkdirs();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        setVideoGestures();
        autohideReset();

        initSeekbars();

        if (isLiveMode) {
            btn_control.setVisibility(View.GONE); // 直播模式隐藏暂停按钮，使用GONE而不是INVISIBLE以保持UI布局正确
            seekbar_progress.setVisibility(View.GONE);
            seekbar_progress.setEnabled(false);
            streamDanmaku(null); // 用来初始化一下弹幕层
            // danmuSocketConnect();
            // 先把弹幕连接注释掉
        }

	    // 此处存在 postDelayed + 线程池链路，快速切会话/退出时可能触发旧任务；用 session 做兜底防护。
	    final int sessionAtInit = playerSessionId;
	    layout_control.postDelayed(() -> CenterThreadPool.run(() -> { // 等界面加载完成
	        if (destroyed || resourcesReleased || sessionAtInit != playerSessionId)
	            return;
            if (isLiveMode) {
                runOnUiThread(() -> {
	                if (destroyed || resourcesReleased || sessionAtInit != playerSessionId)
	                    return;
                    btn_menu.setVisibility(View.GONE);
                    // 直播模式隐藏清晰度按钮
                    btn_quality.setVisibility(View.GONE);
                    // 直播模式隐藏循环按钮
                    btn_loop.setVisibility(View.GONE);
                    // 直播模式隐藏听视频模式按钮
                    btn_audio_only.setVisibility(View.GONE);
                    // 直播模式隐藏自动下一个按钮
                    btn_auto_next.setVisibility(View.GONE);
                    // 直播模式隐藏分P选择器按钮
                    btn_page_selector.setVisibility(View.GONE);
                });
	            if (!destroyed && !resourcesReleased && sessionAtInit == playerSessionId)
	                setDisplay();
                return;
            }

	        runOnUiThread(() -> {
	            if (destroyed || resourcesReleased || sessionAtInit != playerSessionId)
	                return;
                loading_text0.setText("装填弹幕中");
                loading_text1.setText("(≧∇≦)");
            });
	        if (destroyed || resourcesReleased || sessionAtInit != playerSessionId)
	            return;
            if (isOnlineVideo) {
                danmakuFile = new File(cachepath, "danmaku.xml");
                downdanmu();
            } else {
	            runOnUiThread(() -> {
	                if (destroyed || resourcesReleased || sessionAtInit != playerSessionId)
	                    return;
	                btn_danmaku_send.setVisibility(View.GONE);
	            });
                danmakuFile = new File(danmaku_url);
                if (danmakuFile.exists())
                    streamDanmaku(danmakuFile.toString());
                else
                    hasDanmaku = false;
            }

	        if (!destroyed && !resourcesReleased && sessionAtInit == playerSessionId
	                && SharedPreferencesUtil.getBoolean("player_subtitle_autoshow", true))
                downSubtitle(false);

            // 加载高能进度条数据
	        if (!destroyed && !resourcesReleased && sessionAtInit == playerSessionId && isOnlineVideo && aid > 0 && cid > 0) {
                loadHighEnergyData();
            }

	        if (!destroyed && !resourcesReleased && sessionAtInit == playerSessionId
	                && isOnlineVideo && aid > 0 && cid > 0 && SharedPreferencesUtil.getBoolean("player_show_viewpoints", false)) {
                loadViewPoints();
            }

	        if (!destroyed && !resourcesReleased && sessionAtInit == playerSessionId && isOnlineVideo && aid > 0 && cid > 0) {
                loadInteractionVideo();
            }

	        if (!destroyed && !resourcesReleased && sessionAtInit == playerSessionId)
                setDisplay();
        }), 60);
    }

    private void findview() {
        layout_control = findViewById(R.id.control_layout);
        layout_top = findViewById(R.id.top);
        right_control = findViewById(R.id.right_control);
        right_second = findViewById(R.id.right_second);
        layout_card_bg = findViewById(R.id.card_bg);
        card_subtitle = findViewById(R.id.subtitle_card);
        card_danmaku_send = findViewById(R.id.danmaku_send_card);
        card_page_selector = findViewById(R.id.page_selector_card);
        card_quality_selector = findViewById(R.id.quality_selector_card);
        card_viewpoint_selector = findViewById(R.id.viewpoint_selector_card);
        layout_audio_only = findViewById(R.id.audio_only_layout);

        loading_info = findViewById(R.id.loading_info);

        img_loading = findViewById(R.id.circle);
        text_progress = findViewById(R.id.text_progress);
        text_online = findViewById(R.id.text_online);
        btn_danmaku = findViewById(R.id.danmaku_btn);
        btn_loop = findViewById(R.id.loop_btn);
        btn_rotate = findViewById(R.id.rotate_btn);
        btn_menu = findViewById(R.id.menu_btn);
        btn_danmaku_send = findViewById(R.id.danmaku_send_btn);
        btn_subtitle = findViewById(R.id.subtitle_btn);
        btn_audio_only = findViewById(R.id.audio_only_btn);
        btn_control = findViewById(R.id.button_video);
        btn_page_selector = findViewById(R.id.button_page_selector);
        btn_auto_next = findViewById(R.id.auto_next_btn);
        btn_quality = findViewById(R.id.button_quality);
        btn_viewpoint = findViewById(R.id.viewpoint_btn);
        seekbar_progress = findViewById(R.id.videoprogress);
        loading_text0 = findViewById(R.id.loading_text0);
        loading_text1 = findViewById(R.id.loading_text1);
        text_title = findViewById(R.id.text_title);
        text_volume = findViewById(R.id.showsound);
        layout_video = findViewById(R.id.videoArea);
        mDanmakuView = findViewById(R.id.sv_danmaku);
        batteryView = findViewById(R.id.battery);

        text_speed = findViewById(R.id.text_speed);
        layout_speed = findViewById(R.id.layout_speed);
        seekbar_speed = findViewById(R.id.seekbar_speed);
        text_newspeed = findViewById(R.id.text_newspeed);
        bottom_buttons = findViewById(R.id.bottom_buttons);
        btn_debug = findViewById(R.id.btn_debug);

        text_subtitle = findViewById(R.id.text_subtitle);
        text_audio_title = findViewById(R.id.audio_title);
        text_audio_subtitle = findViewById(R.id.audio_subtitle);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setVideoGestures() {
        if (SharedPreferencesUtil.getBoolean("player_scale", true)) {
            scaleGestureListener = new ViewScaleGestureListener(layout_video);
            scaleGestureDetector = new ScaleGestureDetector(this, scaleGestureListener);

            boolean doublemove_enabled = SharedPreferencesUtil.getBoolean("player_doublemove", true); // 是否启用双指移动

            layout_control.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                int pointerCount = event.getPointerCount();
                boolean singleTouch = pointerCount == 1;
                boolean doubleTouch = pointerCount == 2;

                // Logu.v("gesture", event.getEventTime() + "");
                scaleGestureDetector.onTouchEvent(event);
                boolean gesture_scaling = scaleGestureListener.scaling;

                if (!gesture_scaled && gesture_scaling)
                    gesture_scaled = true;

                // Logu.v("gesture", (scaling ? "scaled-yes" : "scaled-no"));

                switch (action) {
                    case MotionEvent.ACTION_MOVE:
                        if (singleTouch) {
                            if (gesture_scaling) {
                                videoMoveBy(0, 0); // 防止单指缩放出框
                            } else if (!(gesture_scaled && !doublemove_enabled)) {
                                float currentX = event.getX(0); // 单指移动
                                float currentY = event.getY(0);
                                float deltaX = currentX - previousX;
                                float deltaY = currentY - previousY;
                                if (deltaX != 0f || deltaY != 0f) {
                                    videoMoveBy(deltaX, deltaY);
                                    previousX = currentX;
                                    previousY = currentY;
                                }
                            }
                        }
                        if (doubleTouch && doublemove_enabled) {
                            float currentX = (event.getX(0) + event.getX(1)) / 2;
                            float currentY = (event.getY(0) + event.getY(1)) / 2;
                            float deltaX = currentX - previousX;
                            float deltaY = currentY - previousY;
                            if (deltaX != 0f || deltaY != 0f) {
                                videoMoveBy(deltaX, deltaY);
                                previousX = currentX;
                                previousY = currentY;
                            }
                        }
                        break;

                    case MotionEvent.ACTION_DOWN:
                        if (singleTouch) { // 如果是单指按下，设置起始位置为当前手指位置
                            previousX = event.getX(0);
                            previousY = event.getY(0);
                            // Logu.v("gesture", "touch_start:" + previousX + "," + previousY);
                        }
                        break;

                    case MotionEvent.ACTION_POINTER_DOWN:
                        if (doubleTouch) { // 如果是双指按下，设置起始位置为两指连线的中心点
                            previousX = (event.getX(0) + event.getX(1)) / 2;
                            previousY = (event.getY(0) + event.getY(1)) / 2;
                            // Logu.v("gesture","double_touch");
                        }
                        break;

                    case MotionEvent.ACTION_POINTER_UP:
                        if (doubleTouch) {
                            int index = event.getActionIndex(); // actionIndex是抬起来的手指位置
                            previousX = event.getX((index == 0 ? 1 : 0));
                            previousY = event.getY((index == 0 ? 1 : 0));
                            // Logu.v("gesture","single_touch");
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                        if (onLongClick) {
                            onLongClick = false;
                            float normalSpeed = speed_values[seekbar_speed.getProgress()];
                            setPlaybackSpeed(normalSpeed);
                            text_speed.setText(speed_strs[seekbar_speed.getProgress()]);
                        }
                        if (gesture_moved)
                            gesture_moved = false;
                        if (gesture_scaled)
                            gesture_scaled = false;
                        break;
                }

                if (!gesture_click_disabled && (gesture_moved || gesture_scaled)) {
                    gesture_click_disabled = true;
                    hidecon.run();
                }

                return false;
            });
        } else {
            layout_control.setOnTouchListener((view, motionEvent) -> {
                if (motionEvent.getAction() == MotionEvent.ACTION_UP && onLongClick) {
                    onLongClick = false;
                    float normalSpeed = speed_values[seekbar_speed.getProgress()];
                    setPlaybackSpeed(normalSpeed);
                    text_speed.setText(speed_strs[seekbar_speed.getProgress()]);
                }
                return false;
            });
        }

        // 这个管普通点击
        layout_control.setOnClickListener(view -> {
            if (gesture_click_disabled)
                gesture_click_disabled = false;
            else
                clickUI();
        });
        // 这个管长按开始
        layout_control.setOnLongClickListener(view -> {
            if (SharedPreferencesUtil.getBoolean("player_longclick", true) && ijkPlayer != null && isPlaying
                    && !isLiveMode) {
                if (!onLongClick && !gesture_click_disabled) {
                    hidecon.run();
                    setPlaybackSpeed(3.0F);
                    text_speed.setText("x 3.0");
                    onLongClick = true;
                    Logu.v("gesture", "longclick_down");
                    return true;
                }
                return false;
            }
            return false;
        });

    }

    private void autohideReset() {
        layout_control.removeCallbacks(hidecon);
        layout_control.postDelayed(hidecon, 4000);
    }

    private void clickUI() {
        long now_timestamp = System.currentTimeMillis();
        if (now_timestamp - timestamp_click < 300) {
            if (SharedPreferencesUtil.getBoolean("player_scale", true) && scaleGestureListener.can_reset) {
                scaleGestureListener.can_reset = false;
                layout_video.setX(video_origX);
                layout_video.setY(video_origY);
                layout_video.setScaleX(1.0f);
                layout_video.setScaleY(1.0f);
            } else if (!isLiveMode) {
                if (isPlaying)
                    playerPause();
                else
                    playerResume();
                showcon();
            }
        } else {
            timestamp_click = now_timestamp;
            if ((layout_top.getVisibility()) == View.GONE)
                showcon();
            else
                hidecon.run();
        }
    }

    @SuppressLint("SetTextI18n")
    private void showcon() {
        right_control.setVisibility(View.VISIBLE);
        layout_top.setVisibility(View.VISIBLE);
        bottom_buttons.setVisibility(View.VISIBLE);
        seekbar_progress.setVisibility(View.VISIBLE);
        seekbar_progress.setEnabled(false);
        seekbar_progress.postDelayed(progressbarEnable, 200);
        if (isPrepared && (!isLiveMode) && (!isAudioOnlyMode)) {
            text_speed.setVisibility(View.VISIBLE);
            updateDebugButtonVisibility();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            batteryView.setPower(batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        }
        if (screen_round) {
            text_progress.setGravity(Gravity.NO_GRAVITY);
            text_progress.setPadding(ToolsUtil.dp2px(24f), 0, 0, 0);
            if (onlineTimer != null)
                text_online.setVisibility(View.VISIBLE);
        }

        autohideReset();
    }

    private final Runnable progressbarEnable = () -> seekbar_progress.setEnabled(true);

    private final Runnable hidecon = () -> {
        right_control.setVisibility(View.GONE);
        layout_top.setVisibility(View.GONE);
        bottom_buttons.setVisibility(View.GONE);
        seekbar_progress.setVisibility(View.GONE);
        if (isPrepared && (!isAudioOnlyMode)) {
            text_speed.setVisibility(View.GONE);
            btn_debug.setVisibility(View.GONE);
        }
        if (screen_round) {
            text_progress.setGravity(Gravity.CENTER);
            text_progress.setPadding(0, 0, 0, ToolsUtil.dp2px(8f));
            if (onlineTimer != null)
                text_online.setVisibility(View.GONE);
        }
        if (menu_opened)
            btn_menu.performClick();
    };

    /** 是否使用 TextureView 显示（由 initUI() 决定，运行时以实际 View 是否存在为准，避免偏好切换导致判断错误） */
    private boolean usingTextureView() {
        return textureView != null;
    }

    /** 当前会话视频渲染 surface 是否已就绪（听视频模式不强制要求） */
    private boolean isRenderSurfaceReady() {
        if (usingTextureView()) {
            return mSurfaceTexture != null;
        }
        if (surfaceHolder != null) {
            try {
                Surface s = surfaceHolder.getSurface();
                return s != null && s.isValid();
            } catch (Exception ignore) {
            }
        }
        return false;
    }

    /**
     * SurfaceView / TextureView 的“渲染 surface 已可用”统一入口。
     * <p>
     * 目的：
     * - 让 TextureView 与 SurfaceView 使用一致的后台回前台恢复逻辑
     * - 避免 onResume() 在 surface 已重建时过早消费后台恢复标记
     */
    private void handleRenderSurfaceAvailable(@NonNull String from, boolean recreated) {
        attachSurfaceIfPossible();

        // 渲染 surface 重建后，如果已准备过则跳回当前进度，避免画面停留在旧首帧/旧渲染缓存。
        if (isPrepared && ijkPlayer != null && !isLiveMode) {
            long restorePosition = Math.max(safeGetPlayerPositionMs(), seekbar_progress.getProgress());
            if (finishWatching) {
                // 结束态回前台：保持“等待手动重播”的状态，不要再对当前会话做 seek/恢复动作。
                // 否则 ijk 可能从结束态被重新拉回“半播放半完成”状态，表现为黑屏播音频或按钮失效。
                skipSeekOnNextSurfaceCreatedFromBackgroundPlayback = false;
                backgroundPlaybackSurfaceRecreated = false;
                syncProgressUiFromPlayer(restorePosition);
                ensureLoadingHidden(from + "-finishWatching");
                Logu.d("surface", from + ": keep completion state, pos=" + restorePosition);
            } else if (skipSeekOnNextSurfaceCreatedFromBackgroundPlayback && recreated && isPlaying) {
                skipSeekOnNextSurfaceCreatedFromBackgroundPlayback = false;
                backgroundPlaybackSurfaceRecreated = false;

                if (isOnlineVideo) {
                    // 实测当前项目 + ijk 组合下，在线视频在 surface recreate 后复用旧 session 的渲染恢复非常不稳定：
                    // 经常走完整个 passive->refresh->rebuild 链路，期间持续黑屏，甚至出现音频继续但画面不回来的情况。
                    // 因此这里改成更激进但更稳定的策略：直接重建在线播放会话，并强制回到当前进度。
                    pendingForcedSeekMs = restorePosition;
                    progress_history = restorePosition;
                    pendingOnlineRebuildFirstVideoFrameFallback = true;
                    showFrameLoading("恢复画面中", "(｀・ω・´)");
                    Logu.w("surface", from + ": direct rebuild after online background playback surface recreate, pos="
                            + restorePosition + ", session=" + playerSessionId);
                    rebuildPlayerSession("backgroundSurfaceRecreateRestore", restorePosition);
                } else {
                    // 本地/缓存视频先保留旧会话音频，走分级恢复；若仍无首帧，再由本地 black timeout 重建。
                    Logu.w("surface", from + ": staged restore after local background playback surface recreate, pos="
                            + restorePosition);
                    startBackgroundSurfaceRestore(restorePosition, "localSurfaceRecreate:" + from);
                }
                return;
            } else {
                skipSeekOnNextSurfaceCreatedFromBackgroundPlayback = false;
                backgroundPlaybackSurfaceRecreated = false;
                try {
                    ijkPlayer.seekTo(restorePosition);
                    markExplicitSeek(restorePosition);
                    if (hasDanmaku && mDanmakuView != null) {
                        try {
                            mDanmakuView.seekTo(restorePosition);
                        } catch (Exception ignore) {
                        }
                    }
                } catch (Exception ignore) {
                }
                if (isPlaying || pausedByLifecycle) {
                    beginWaitingForFirstVideoFrame(from + "-seekRefresh");
                    scheduleBackgroundResumeRenderHealthCheck(restorePosition, from + "-seekRefresh");
                } else {
                    ensureLoadingHidden(from + "-nonPlayingRefresh");
                }
                syncProgressUiFromPlayer(restorePosition);
            }
        }

        maybePrepare(from);
    }

    /**
     * 绑定渲染 Surface（回调驱动；避免定时轮询）。
     * <p>
     * 修复：
     * - 不再在 setDisplay() 里用 Timer 轮询等待 Surface/Texture
     * - TextureView 只创建一个 Surface 并复用，避免频繁 new Surface 导致 native 资源泄漏
     */
    private void attachSurfaceIfPossible() {
        if (ijkPlayer == null)
            return;

        if (usingTextureView()) {
            if (mSurfaceTexture == null)
                return;
            ensureTextureSurface(mSurfaceTexture);
            if (textureSurface != null) {
                try {
                    ijkPlayer.setSurface(textureSurface);
                } catch (Exception ignore) {
                }
            }
        } else {
            if (surfaceHolder == null)
                return;
            // SurfaceView 必须等 surfaceCreated 后 surface 才有效；避免过早 setDisplay 导致异常
            try {
                Surface s = surfaceHolder.getSurface();
                if (s == null || !s.isValid())
                    return;
            } catch (Exception ignore) {
                return;
            }
            try {
                ijkPlayer.setDisplay(surfaceHolder);
            } catch (Exception ignore) {
            }
        }
    }

    private void ensureTextureSurface(@NonNull SurfaceTexture surfaceTexture) {
        if (textureSurface != null && attachedSurfaceTexture == surfaceTexture)
            return;
        releaseTextureSurface();
        try {
            attachedSurfaceTexture = surfaceTexture;
            textureSurface = new Surface(surfaceTexture);
        } catch (Exception ignore) {
            attachedSurfaceTexture = null;
            textureSurface = null;
        }
    }

    private void releaseTextureSurface() {
        if (textureSurface != null) {
            try {
                textureSurface.release();
            } catch (Exception ignore) {
            }
            textureSurface = null;
        }
        attachedSurfaceTexture = null;
    }

    /**
     * 仅当满足条件时发起 prepare：
     * - 每个播放会话只能触发一次（prepareRequested 防重入）
     * - 非听视频模式必须等待渲染 Surface 就绪（由回调驱动）
     */
    private void maybePrepare(String from) {
        if (destroyed || ijkPlayer == null)
            return;
        if (prepareRequested)
            return;
        if (!displayConfigured)
            return;

        // 听视频模式不依赖视频渲染 surface，否则可能永远等不到 surface 导致无法播放
        if (!isAudioOnlyMode && !isRenderSurfaceReady()) {
            Logu.v("prepare", "等待渲染Surface就绪: from=" + from);
            return;
        }

        prepareRequested = true;
        Logu.v("prepare", "触发MPPrepare: from=" + from + ", session=" + playerSessionId);
        MPPrepare(video_url);
    }

    /**
     * 仅使当前会话失效（不意味着立即创建新播放器）。
     * <p>
     * 用途：
     * - onDestroy 等场景，让旧的 TimerTask / 播放器回调尽快识别自己已过期并退出
     * - 避免误导：不会把状态重置为“可再次 prepare”的新会话
     */
    private void invalidatePlayerSession(@NonNull String reason) {
        playerSessionId++;
        // 失效阶段：禁止旧会话再次触发 prepare
        prepareRequested = true;
        displayConfigured = false;
        cancelConsumeBackgroundPlaybackFlag();
        skipSeekOnNextSurfaceCreatedFromBackgroundPlayback = false;
        backgroundPlaybackSurfaceRecreated = false;
        Logu.v("session", "invalidatePlayerSession: " + reason + ", id=" + playerSessionId);
    }

    /**
     * 开启一个新的播放会话。
     * <p>
     * 用途：
     * - 让旧的 TimerTask / 播放器回调能通过 sessionId 判断自己已过期并尽快退出
     * - 重置 prepareRequested，确保新会话仍可触发一次 MPPrepare
     */
    private void startNewPlayerSession(@NonNull String reason) {
        playerSessionId++;
        // 新会话必须允许再次 prepare
        prepareRequested = false;
        displayConfigured = false;
        cancelConsumeBackgroundPlaybackFlag();
        skipSeekOnNextSurfaceCreatedFromBackgroundPlayback = false;
        backgroundPlaybackSurfaceRecreated = false;
        Logu.v("session", "startNewPlayerSession: " + reason + ", id=" + playerSessionId);
    }

    /** 重置与播放会话强相关的状态（避免旧状态影响新会话） */
    private void resetPlaybackFlagsForNewSession() {
        isPrepared = false;
        isPlaying = false;
        isSeeking = false;
        finishWatching = false;
        completionReplayNeedsRebuild = false;
        waitingForFirstVideoFrame = false;
        firstVideoFrameRendered = false;
        video_all = 0;
        video_now = 0;
        video_now_last = 0;
    }

    private void runOnUiThreadIfNeeded(@NonNull Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            runOnUiThread(action);
        }
    }

    private void cancelPendingFrameLoadingHide() {
        if (mainHandler != null && frameLoadingDelayedHideRunnable != null) {
            try {
                mainHandler.removeCallbacks(frameLoadingDelayedHideRunnable);
            } catch (Exception ignore) {
            }
        }
        frameLoadingDelayedHideRunnable = null;
    }

    private void cancelFrameLoadingMinShowGuard() {
        cancelPendingFrameLoadingHide();
        frameLoadingVisibleSinceUptimeMs = 0L;
    }

    private void showFrameLoading(@NonNull String title, @NonNull String subtitle) {
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());
        cancelPendingFrameLoadingHide();
        frameLoadingVisibleSinceUptimeMs = android.os.SystemClock.uptimeMillis();
        runOnUiThreadIfNeeded(() -> {
            if (loading_info != null)
                loading_info.setVisibility(View.VISIBLE);
            if (anim_loading != null)
                anim_loading.start();
            if (loading_text0 != null)
                loading_text0.setText(title);
            if (loading_text1 != null)
                loading_text1.setText(subtitle);
        });
    }

    private void updateFrameLoadingText(@NonNull String title, @NonNull String subtitle) {
        runOnUiThreadIfNeeded(() -> {
            if (loading_text0 != null)
                loading_text0.setText(title);
            if (loading_text1 != null)
                loading_text1.setText(subtitle);
        });
    }

    private void hideFrameLoading(@NonNull String reason) {
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());

        cancelPendingFrameLoadingHide();

        long shownAt = frameLoadingVisibleSinceUptimeMs;
        long elapsed = shownAt > 0L ? (android.os.SystemClock.uptimeMillis() - shownAt) : FRAME_LOADING_MIN_SHOW_MS;
        long remain = shownAt > 0L ? Math.max(0L, FRAME_LOADING_MIN_SHOW_MS - elapsed) : 0L;

        Runnable hideAction = () -> {
            frameLoadingDelayedHideRunnable = null;
            frameLoadingVisibleSinceUptimeMs = 0L;
            runOnUiThreadIfNeeded(() -> {
                if (loading_info != null)
                    loading_info.setVisibility(View.GONE);
                if (anim_loading != null)
                    anim_loading.stop();
            });
            Logu.d("render", "hideFrameLoading: " + reason);
        };

        if (remain > 0L) {
            frameLoadingDelayedHideRunnable = hideAction;
            mainHandler.postDelayed(frameLoadingDelayedHideRunnable, remain);
            return;
        }

        hideAction.run();
    }

    private void beginWaitingForFirstVideoFrame(@NonNull String reason) {
        if (isAudioOnlyMode) {
            waitingForFirstVideoFrame = false;
            firstVideoFrameRendered = true;
            waitingForFirstVideoFrameStartPosMs = 0L;
            return;
        }
        waitingForFirstVideoFrame = true;
        firstVideoFrameRendered = false;
        waitingForFirstVideoFrameUptimeMs = android.os.SystemClock.uptimeMillis();
        long startPos = Math.max(0L, latestPlayerPositionMs);
        if (ijkPlayer != null && isPrepared) {
            try {
                startPos = Math.max(startPos, ijkPlayer.getCurrentPosition());
            } catch (Exception ignore) {
            }
        }
        waitingForFirstVideoFrameStartPosMs = Math.max(0L, startPos);

        // 本地/缓存视频：兜底，避免不触发 MEDIA_INFO_VIDEO_RENDERING_START 时 loading 永久悬浮。
        // 仅对本地启用，避免影响在线 buffering 行为。
        // 注意：后台回前台 surface 重建时，不能仅凭“进度在走”就隐藏 loading，否则容易出现纯黑屏。
        if (!isLiveMode && !isOnlineVideo && !inBackgroundSurfaceRestore) {
            scheduleFirstVideoFrameFallback("beginWaiting:" + reason);
        }
        showFrameLoading("正在加载画面", "(｀・ω・´)");
        Logu.d("render", "beginWaitingForFirstVideoFrame: " + reason);
    }

    private void cancelOnlineRebuildFirstVideoFrameFallback() {
        if (mainHandler != null && onlineRebuildFirstVideoFrameFallbackRunnable != null) {
            try {
                mainHandler.removeCallbacks(onlineRebuildFirstVideoFrameFallbackRunnable);
            } catch (Exception ignore) {
            }
        }
        onlineRebuildFirstVideoFrameFallbackRunnable = null;
        pendingOnlineRebuildFirstVideoFrameFallback = false;
    }

    private void scheduleOnlineRebuildFirstVideoFrameFallback(@NonNull String reason) {
        if (!pendingOnlineRebuildFirstVideoFrameFallback || destroyed || resourcesReleased || !isOnlineVideo || isAudioOnlyMode)
            return;
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());

        cancelOnlineRebuildFirstVideoFrameFallback();
        pendingOnlineRebuildFirstVideoFrameFallback = true;
        final int session = playerSessionId;
        onlineRebuildFirstVideoFrameFallbackRunnable = new Runnable() {
            @Override
            public void run() {
                if (destroyed || resourcesReleased || session != playerSessionId) {
                    onlineRebuildFirstVideoFrameFallbackRunnable = null;
                    pendingOnlineRebuildFirstVideoFrameFallback = false;
                    return;
                }
                if (!pendingOnlineRebuildFirstVideoFrameFallback || !waitingForFirstVideoFrame || firstVideoFrameRendered) {
                    onlineRebuildFirstVideoFrameFallbackRunnable = null;
                    return;
                }

                long pos = safeGetPlayerPositionMs();
                long moved = Math.max(0L, pos - waitingForFirstVideoFrameStartPosMs);
                float outputFps = 0f;
                try {
                    if (ijkPlayer != null) {
                        outputFps = ijkPlayer.getVideoOutputFramesPerSecond();
                    }
                } catch (Exception ignore) {
                }

                if (moved >= ONLINE_REBUILD_FIRST_VIDEO_FRAME_MOVED_MS
                        || (isRenderSurfaceReady() && outputFps > 8f)) {
                    onlineRebuildFirstVideoFrameFallbackRunnable = null;
                    pendingOnlineRebuildFirstVideoFrameFallback = false;
                    onFirstVideoFrameRendered("onlineRebuildFallback:" + reason
                            + ", moved=" + moved + ", fps=" + outputFps);
                    return;
                }

                if (mainHandler != null) {
                    mainHandler.postDelayed(this, 180L);
                }
            }
        };
        mainHandler.postDelayed(onlineRebuildFirstVideoFrameFallbackRunnable,
                ONLINE_REBUILD_FIRST_VIDEO_FRAME_FALLBACK_MS);
    }

    private void cancelBackgroundSurfaceRefreshTimeout() {
        backgroundSurfaceRefreshPending = false;
        backgroundSurfaceRefreshTriggered = false;
        backgroundSurfaceRefreshTriggeredUptimeMs = 0L;
        backgroundSurfaceRefreshTriggeredPosMs = -1L;
        if (mainHandler != null && backgroundSurfaceRefreshRunnable != null) {
            try {
                mainHandler.removeCallbacks(backgroundSurfaceRefreshRunnable);
            } catch (Exception ignore) {
            }
        }
        backgroundSurfaceRefreshRunnable = null;
    }

    private void cancelConsumeBackgroundPlaybackFlag() {
        if (mainHandler != null && consumeBackgroundPlaybackFlagRunnable != null) {
            try {
                mainHandler.removeCallbacks(consumeBackgroundPlaybackFlagRunnable);
            } catch (Exception ignore) {
            }
        }
        consumeBackgroundPlaybackFlagRunnable = null;
    }

    private void scheduleConsumeBackgroundPlaybackFlag(@NonNull String reason) {
        cancelConsumeBackgroundPlaybackFlag();
        if (destroyed || resourcesReleased)
            return;
        if (!skipSeekOnNextSurfaceCreatedFromBackgroundPlayback)
            return;
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());

        final int session = playerSessionId;
        consumeBackgroundPlaybackFlagRunnable = () -> {
            consumeBackgroundPlaybackFlagRunnable = null;
            if (destroyed || resourcesReleased || session != playerSessionId)
                return;
            // 若已确认 surface 在后台被销毁/重建，则不要在这里消费标记，让 surfaceCreated 分支处理。
            if (backgroundPlaybackSurfaceRecreated)
                return;
            skipSeekOnNextSurfaceCreatedFromBackgroundPlayback = false;
            backgroundPlaybackSurfaceRecreated = false;
            Logu.d("surface", "consume background-playback flag(delayed): reason=" + reason);
        };
        mainHandler.postDelayed(consumeBackgroundPlaybackFlagRunnable, CONSUME_BACKGROUND_PLAYBACK_FLAG_DELAY_MS);
    }

    private void cancelBackgroundResumeRenderHealthCheck() {
        backgroundResumeRenderHealthCheckStartPosMs = -1L;
        if (mainHandler != null && backgroundResumeRenderHealthCheckRunnable != null) {
            try {
                mainHandler.removeCallbacks(backgroundResumeRenderHealthCheckRunnable);
            } catch (Exception ignore) {
            }
        }
        backgroundResumeRenderHealthCheckRunnable = null;
    }

    private void scheduleBackgroundResumeRenderHealthCheck(long restorePositionMs, @NonNull String reason) {
        scheduleBackgroundResumeRenderHealthCheck(restorePositionMs, reason,
                BACKGROUND_RESUME_RENDER_HEALTHCHECK_TIMEOUT_ONLINE_MS);
    }

    private void scheduleBackgroundResumeRenderHealthCheck(long restorePositionMs, @NonNull String reason,
                                                           long timeoutMs) {
        cancelBackgroundResumeRenderHealthCheck();
        // 这层“超时后直接重建”的 health check 只保留给在线视频：
        // - 在线视频：优先尽快放弃旧渲染链，减少长时间错误/慢恢复
        // - 本地视频：交给 startBackgroundSurfaceRestore()/local black timeout 兜底，尽量保住音频连续性
        if (destroyed || resourcesReleased || isLiveMode || isAudioOnlyMode || !isOnlineVideo)
            return;
        if (ijkPlayer == null || !isPrepared)
            return;
        if (!isPlaying && !pausedByLifecycle)
            return;
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());

        final int session = playerSessionId;
        backgroundResumeRenderHealthCheckStartPosMs = Math.max(0L, restorePositionMs);
        backgroundResumeRenderHealthCheckRunnable = () -> {
            backgroundResumeRenderHealthCheckRunnable = null;
            if (destroyed || resourcesReleased || session != playerSessionId)
                return;

            IjkMediaPlayer player = ijkPlayer;
            if (player == null || !isPrepared)
                return;
            if (!isPlaying && !pausedByLifecycle)
                return;

            float outputFps = 0f;
            float decodeFps = 0f;
            try {
                outputFps = player.getVideoOutputFramesPerSecond();
            } catch (Exception ignore) {
            }
            try {
                decodeFps = player.getVideoDecodeFramesPerSecond();
            } catch (Exception ignore) {
            }

            boolean restoreCompleted = false;
            if (isRenderSurfaceReady() && outputFps > 0.01f) {
                if (waitingForFirstVideoFrame && !firstVideoFrameRendered) {
                    if (inBackgroundSurfaceRestore) {
                        restoreCompleted = maybeCompleteBackgroundRestoreByHeuristic(
                                "backgroundResumeHealthCheck", outputFps);
                    }
                } else {
                    cancelBackgroundResumeRenderHealthCheck();
                    return;
                }
                if (restoreCompleted)
                    return;
            }

            // 在线恢复单独走“两级兜底”：
            // 1) 首次 health check 未恢复时，先做一次轻量 refresh kick（尽量保留旧 session/缓冲）
            // 2) refresh 后仍未恢复，再直接 rebuild，避免黑屏长尾拖到数秒
            if (!backgroundSurfaceRefreshTriggered) {
                long refreshPosition = Math.max(backgroundResumeRenderHealthCheckStartPosMs,
                        safeGetPlayerPositionMs());
                boolean refreshed = performBackgroundSurfaceRefresh("onlineHealthCheck:" + reason);
                if (refreshed) {
                    Logu.w("surface", "online restore refresh fallback: reason=" + reason
                            + ", pos=" + refreshPosition
                            + ", decodeFps=" + decodeFps
                            + ", outputFps=" + outputFps
                            + ", waiting=" + waitingForFirstVideoFrame);
                    scheduleBackgroundResumeRenderHealthCheck(refreshPosition,
                            "afterRefresh:" + reason,
                            BACKGROUND_RESUME_RENDER_HEALTHCHECK_TIMEOUT_ONLINE_AFTER_REFRESH_MS);
                    return;
                }
            }

            long rebuildPosition = Math.max(backgroundResumeRenderHealthCheckStartPosMs,
                    safeGetPlayerPositionMs());
            pendingForcedSeekMs = rebuildPosition;
            progress_history = rebuildPosition;
            pendingOnlineRebuildFirstVideoFrameFallback = true;
            showFrameLoading("恢复画面中", "(｀・ω・´)");
            Logu.w("surface", "online restore rebuild fallback: reason=" + reason
                    + ", pos=" + rebuildPosition
                    + ", decodeFps=" + decodeFps
                    + ", outputFps=" + outputFps
                    + ", waiting=" + waitingForFirstVideoFrame);
            rebuildPlayerSession("backgroundResumeRenderTimeout", rebuildPosition);
        };
        mainHandler.postDelayed(backgroundResumeRenderHealthCheckRunnable, timeoutMs);
        Logu.d("surface", "schedule background resume health check: reason=" + reason
                + ", timeout=" + timeoutMs + "ms");
    }

    private long getBackgroundRefreshSeekTargetMs(long currentPositionMs) {
        long target = Math.max(0L, currentPositionMs);
        if (video_all > 0) {
            long maxTarget = Math.max(0L, video_all - 1L);
            target = Math.min(maxTarget, target + BACKGROUND_SURFACE_REFRESH_SEEK_OFFSET_MS);
        } else {
            target += BACKGROUND_SURFACE_REFRESH_SEEK_OFFSET_MS;
        }
        return Math.max(0L, target);
    }

    private void markBackgroundRestoreOutputFpsObserved(float outputFps, @NonNull String source) {
        if (!inBackgroundSurfaceRestore || outputFps <= 0.01f)
            return;
        backgroundSurfaceRestoreLastObservedOutputFps = Math.max(backgroundSurfaceRestoreLastObservedOutputFps, outputFps);
        if (!backgroundSurfaceRestoreOutputFpsObserved) {
            backgroundSurfaceRestoreOutputFpsObserved = true;
            backgroundSurfaceRestoreOutputFpsObservedUptimeMs = android.os.SystemClock.uptimeMillis();
            Logu.w("surface", "bgRestore observed outputFps: source=" + source
                    + ", fps=" + outputFps
                    + ", step=" + backgroundSurfaceRestoreStep
                    + ", frameEvent=" + backgroundSurfaceRestoreFrameEventReceived
                    + ", session=" + playerSessionId);
        }
        if (!isOnlineVideo) {
            scheduleLocalBackgroundSurfaceFrameCorrection("outputObserved:" + source);
        }
    }

    private void restoreAudioAfterLocalFrameCorrection(@NonNull String reason) {
        if (!localBackgroundSurfaceAudioMutedForCorrection)
            return;
        localBackgroundSurfaceAudioMutedForCorrection = false;
        localBackgroundSurfaceAudioRestorePosMs = -1L;
        localBackgroundSurfaceAudioMuteStartUptimeMs = 0L;
        IjkMediaPlayer player = ijkPlayer;
        if (player != null) {
            try {
                player.setVolume(1f, 1f);
            } catch (Exception ignore) {
            }
        }
        Logu.d("surface", "local frame correction audio restore: reason=" + reason);
    }

    private void cancelLocalBackgroundSurfaceAudioRestore() {
        if (mainHandler != null && localBackgroundSurfaceAudioRestoreRunnable != null) {
            try {
                mainHandler.removeCallbacks(localBackgroundSurfaceAudioRestoreRunnable);
            } catch (Exception ignore) {
            }
        }
        localBackgroundSurfaceAudioRestoreRunnable = null;
        restoreAudioAfterLocalFrameCorrection("cancel");
    }

    private void scheduleLocalBackgroundSurfaceAudioRestore(long fromPositionMs, @NonNull String reason) {
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());
        cancelLocalBackgroundSurfaceAudioRestore();

        IjkMediaPlayer player = ijkPlayer;
        if (player == null)
            return;
        try {
            player.setVolume(0f, 0f);
            localBackgroundSurfaceAudioMutedForCorrection = true;
            localBackgroundSurfaceAudioMuteStartUptimeMs = android.os.SystemClock.uptimeMillis();
            localBackgroundSurfaceAudioRestorePosMs = Math.max(0L,
                    fromPositionMs + LOCAL_BACKGROUND_SURFACE_AUDIO_RESTORE_MARGIN_MS);
        } catch (Exception ignore) {
            localBackgroundSurfaceAudioMutedForCorrection = false;
            localBackgroundSurfaceAudioRestorePosMs = -1L;
            localBackgroundSurfaceAudioMuteStartUptimeMs = 0L;
            return;
        }

        final int session = playerSessionId;
        localBackgroundSurfaceAudioRestoreRunnable = new Runnable() {
            @Override
            public void run() {
                if (destroyed || resourcesReleased || session != playerSessionId) {
                    localBackgroundSurfaceAudioRestoreRunnable = null;
                    restoreAudioAfterLocalFrameCorrection("sessionChanged");
                    return;
                }
                if (!localBackgroundSurfaceAudioMutedForCorrection) {
                    localBackgroundSurfaceAudioRestoreRunnable = null;
                    return;
                }

                long elapsed = android.os.SystemClock.uptimeMillis() - localBackgroundSurfaceAudioMuteStartUptimeMs;
                long pos = safeGetPlayerPositionMs();
                if (elapsed >= LOCAL_BACKGROUND_SURFACE_AUDIO_MUTE_MIN_MS
                        && (pos >= localBackgroundSurfaceAudioRestorePosMs
                        || elapsed >= LOCAL_BACKGROUND_SURFACE_AUDIO_MUTE_MAX_MS)) {
                    localBackgroundSurfaceAudioRestoreRunnable = null;
                    restoreAudioAfterLocalFrameCorrection(reason + ", pos=" + pos + ", elapsed=" + elapsed);
                    return;
                }
                if (mainHandler != null) {
                    mainHandler.postDelayed(this, 35L);
                }
            }
        };
        mainHandler.postDelayed(localBackgroundSurfaceAudioRestoreRunnable, 35L);
    }

    private void cancelLocalBackgroundSurfaceFrameCorrection() {
        localBackgroundSurfaceFrameCorrectionPending = false;
        localBackgroundSurfaceFrameCorrectionTriggered = false;
        localBackgroundSurfaceFrameCorrectionTriggeredUptimeMs = 0L;
        localBackgroundSurfaceFrameCorrectionBasePosMs = -1L;
        if (mainHandler != null && localBackgroundSurfaceFrameCorrectionRunnable != null) {
            try {
                mainHandler.removeCallbacks(localBackgroundSurfaceFrameCorrectionRunnable);
            } catch (Exception ignore) {
            }
        }
        localBackgroundSurfaceFrameCorrectionRunnable = null;
    }

    private void scheduleLocalBackgroundSurfaceFrameCorrection(@NonNull String reason) {
        if (destroyed || resourcesReleased || isOnlineVideo || isAudioOnlyMode)
            return;
        if (!inBackgroundSurfaceRestore || !waitingForFirstVideoFrame || firstVideoFrameRendered)
            return;
        if (ijkPlayer == null || !isPrepared)
            return;
        if (localBackgroundSurfaceFrameCorrectionPending || localBackgroundSurfaceFrameCorrectionTriggered)
            return;
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());

        final int session = playerSessionId;
        localBackgroundSurfaceFrameCorrectionPending = true;
        localBackgroundSurfaceFrameCorrectionRunnable = () -> {
            localBackgroundSurfaceFrameCorrectionRunnable = null;
            if (destroyed || resourcesReleased || session != playerSessionId) {
                localBackgroundSurfaceFrameCorrectionPending = false;
                return;
            }
            if (!inBackgroundSurfaceRestore || !waitingForFirstVideoFrame || firstVideoFrameRendered) {
                localBackgroundSurfaceFrameCorrectionPending = false;
                return;
            }
            IjkMediaPlayer player = ijkPlayer;
            if (player == null || !isPrepared) {
                localBackgroundSurfaceFrameCorrectionPending = false;
                return;
            }

            long currentPosition = safeGetPlayerPositionMs();
            scheduleLocalBackgroundSurfaceAudioRestore(currentPosition, "frameCorrection:" + reason);
            long target = Math.max(0L, currentPosition + LOCAL_BACKGROUND_SURFACE_FRAME_CORRECTION_SEEK_OFFSET_MS);
            if (video_all > 0) {
                target = Math.min(target, Math.max(0L, video_all - 1L));
            }

            try {
                attachSurfaceIfPossible();
            } catch (Exception ignore) {
            }
            try {
                player.seekTo(target);
            } catch (Exception e) {
                localBackgroundSurfaceFrameCorrectionPending = false;
                Logu.w("surface", "local frame correction seek failed: reason=" + reason + ", err=" + e.getMessage());
                return;
            }

            markExplicitSeek(target);
            if (hasDanmaku && mDanmakuView != null) {
                try {
                    mDanmakuView.seekTo(target);
                } catch (Exception ignore) {
                }
            }
            syncProgressUiFromPlayer(target);
            localBackgroundSurfaceFrameCorrectionPending = false;
            localBackgroundSurfaceFrameCorrectionTriggered = true;
            localBackgroundSurfaceFrameCorrectionTriggeredUptimeMs = android.os.SystemClock.uptimeMillis();
            localBackgroundSurfaceFrameCorrectionBasePosMs = target;
            Logu.w("surface", "local frame correction seek: reason=" + reason
                    + ", current=" + currentPosition
                    + ", target=" + target
                    + ", session=" + playerSessionId);
        };
        mainHandler.postDelayed(localBackgroundSurfaceFrameCorrectionRunnable,
                LOCAL_BACKGROUND_SURFACE_FRAME_CORRECTION_DELAY_MS);
    }

    private boolean maybeCompleteBackgroundRestoreByHeuristic(@NonNull String source, float outputFps) {
        if (!inBackgroundSurfaceRestore || firstVideoFrameRendered || !waitingForFirstVideoFrame)
            return false;
        if (outputFps > 0.01f) {
            markBackgroundRestoreOutputFpsObserved(outputFps, source);
        }
        boolean outputObserved = backgroundSurfaceRestoreOutputFpsObserved;
        float effectiveFps = outputFps > 0.01f ? outputFps : backgroundSurfaceRestoreLastObservedOutputFps;
        if (!outputObserved && !backgroundSurfaceRestoreFrameEventReceived)
            return false;

        long now = android.os.SystemClock.uptimeMillis();
        long observedFor = backgroundSurfaceRestoreOutputFpsObservedUptimeMs > 0L
                ? (now - backgroundSurfaceRestoreOutputFpsObservedUptimeMs)
                : 0L;
        long pos = safeGetPlayerPositionMs();
        long completionBasePos = Math.max(0L, backgroundSurfaceRestoreStartPosMs);
        if (isOnlineVideo && backgroundSurfaceRefreshTriggeredPosMs >= 0L) {
            completionBasePos = Math.max(completionBasePos, backgroundSurfaceRefreshTriggeredPosMs);
        }
        if (!isOnlineVideo && localBackgroundSurfaceFrameCorrectionBasePosMs >= 0L) {
            completionBasePos = Math.max(completionBasePos, localBackgroundSurfaceFrameCorrectionBasePosMs);
        }
        long moved = Math.max(0L, pos - completionBasePos);
        boolean surfaceReady = isRenderSurfaceReady();
        boolean stableObservedOutput = outputObserved
                && observedFor >= (BACKGROUND_SURFACE_RESTORE_HEURISTIC_OBSERVED_MS
                + BACKGROUND_SURFACE_RESTORE_HEURISTIC_SURFACELESS_GRACE_MS);
        // 在线视频：仅凭 passive reattach 阶段观测到 outputFps>0 不能判定“画面真的已经回来了”。
        // 你提供的日志里就出现了 outputFps 正常、loading 被隐藏，但屏幕仍持续黑屏的情况。
        // 因此在线场景必须至少经历一次主动 refresh（render-refresh seek / kick）后，
        // 才允许用启发式判定恢复完成；否则继续等待 health check 走 refresh/rebuild 链路。
        boolean restoreAdvanced = isOnlineVideo
                ? backgroundSurfaceRefreshTriggered
                : (backgroundSurfaceRestoreStep >= 1 || backgroundSurfaceRefreshTriggered);
        boolean correctionSettled = isOnlineVideo
                || (!localBackgroundSurfaceFrameCorrectionPending
                && (!localBackgroundSurfaceFrameCorrectionTriggered
                || now - localBackgroundSurfaceFrameCorrectionTriggeredUptimeMs
                >= LOCAL_BACKGROUND_SURFACE_FRAME_CORRECTION_SETTLE_MS));
        long movedThreshold = !isOnlineVideo && localBackgroundSurfaceFrameCorrectionTriggered
                ? LOCAL_BACKGROUND_SURFACE_FRAME_CORRECTION_MOVED_MS
                : BACKGROUND_SURFACE_RESTORE_HEURISTIC_MOVED_MS;

        if (backgroundSurfaceRestoreFrameEventReceived) {
            onFirstVideoFrameRendered("bgRestore-frameEvent+heuristic:" + source + ", fps=" + effectiveFps);
            return true;
        }

        // 在线视频后台恢复：不再接受基于 outputFps / 进度推进的启发式“恢复完成”。
        // 只要没收到真实的首帧事件，就继续等待 health check 触发 refresh / rebuild，
        // 避免出现“日志显示恢复完成，但屏幕仍黑”的误判。
        if (isOnlineVideo) {
            return false;
        }

        if (isOnlineVideo && backgroundSurfaceRefreshTriggered) {
            long refreshElapsed = backgroundSurfaceRefreshTriggeredUptimeMs > 0L
                    ? (now - backgroundSurfaceRefreshTriggeredUptimeMs)
                    : 0L;
            if (refreshElapsed < BACKGROUND_SURFACE_RESTORE_HEURISTIC_AFTER_REFRESH_MIN_MS
                    || moved < BACKGROUND_SURFACE_RESTORE_HEURISTIC_AFTER_REFRESH_MOVED_MS) {
                return false;
            }
        }

        // 没有 frame event 时，至少要求：
        // 1) 已执行到较后阶段/做过 refresh；
        // 2) outputFps 持续一小段时间；
        // 3) 播放位置确实在推进。
        if ((surfaceReady || stableObservedOutput) && restoreAdvanced && outputObserved && correctionSettled
                && observedFor >= BACKGROUND_SURFACE_RESTORE_HEURISTIC_OBSERVED_MS
                && moved >= movedThreshold) {
            Logu.w("surface", "bgRestore heuristic complete: source=" + source
                    + ", fps=" + effectiveFps
                    + ", observedFor=" + observedFor + "ms"
                    + ", moved=" + moved + "ms"
                    + ", step=" + backgroundSurfaceRestoreStep
                    + ", surfaceReady=" + surfaceReady);
            onFirstVideoFrameRendered("bgRestore-heuristic:" + source + ", fps=" + effectiveFps);
            return true;
        }

        // 长尾兜底：某些设备上 outputFps 已经稳定出现，但 surfaceReady / currentPosition 推进上报仍可能漏判，
        // 导致恢复流程卡在半状态数秒甚至更久。此时在“已观测到稳定输出一段时间”后直接认为画面恢复完成，
        // 优先消除 1~+∞ 的长尾。
        if (restoreAdvanced && outputObserved && correctionSettled
                && observedFor >= BACKGROUND_SURFACE_RESTORE_HEURISTIC_FORCE_COMPLETE_MS
                && effectiveFps > 5f) {
            Logu.w("surface", "bgRestore force complete after stable output: source=" + source
                    + ", fps=" + effectiveFps
                    + ", observedFor=" + observedFor + "ms"
                    + ", moved=" + moved + "ms"
                    + ", step=" + backgroundSurfaceRestoreStep
                    + ", surfaceReady=" + surfaceReady);
            onFirstVideoFrameRendered("bgRestore-forceComplete:" + source + ", fps=" + effectiveFps);
            return true;
        }
        return false;
    }

    /**
     * 恢复期专用高频探针：脱离 progressTimer 的 250ms 低频采样，避免“观测到 outputFps 后又漏判”导致恢复卡住。
     */
    private void startBackgroundSurfaceRestoreProbe(@NonNull String reason) {
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());

        if (backgroundSurfaceRestoreProbeRunnable != null) {
            try {
                mainHandler.removeCallbacks(backgroundSurfaceRestoreProbeRunnable);
            } catch (Exception ignore) {
            }
        }

        final int session = playerSessionId;
        backgroundSurfaceRestoreProbeRunnable = new Runnable() {
            @Override
            public void run() {
                if (destroyed || resourcesReleased || session != playerSessionId)
                    return;
                if (!inBackgroundSurfaceRestore || !waitingForFirstVideoFrame || firstVideoFrameRendered)
                    return;

                IjkMediaPlayer player = ijkPlayer;
                if (player == null || !isPrepared)
                    return;

                // 高频 probe 顺手重复 reattach，避免某些 ROM 上单次 surfaceCreated 绑定丢失后一直等不到画面恢复。
                attachSurfaceIfPossible();

                float outputFps = 0f;
                try {
                    outputFps = player.getVideoOutputFramesPerSecond();
                } catch (Exception ignore) {
                }

                if (!maybeCompleteBackgroundRestoreByHeuristic("restoreProbe", outputFps) && mainHandler != null) {
                    mainHandler.postDelayed(this, BACKGROUND_SURFACE_RESTORE_PROBE_INTERVAL_MS);
                }
            }
        };
        mainHandler.postDelayed(backgroundSurfaceRestoreProbeRunnable, BACKGROUND_SURFACE_RESTORE_PROBE_INTERVAL_MS);
        Logu.d("surface", "start bgRestore probe: reason=" + reason
                + ", interval=" + BACKGROUND_SURFACE_RESTORE_PROBE_INTERVAL_MS + "ms");
    }

    private boolean performBackgroundSurfaceRenderRefreshSeek(@NonNull String reason, long positionMs) {
        if (destroyed || resourcesReleased || isLiveMode || isAudioOnlyMode || !isPrepared)
            return false;

        IjkMediaPlayer player = ijkPlayer;
        if (player == null)
            return false;

        long currentPosition = Math.max(positionMs, safeGetPlayerPositionMs());
        long target = Math.max(0L, currentPosition + BACKGROUND_SURFACE_RESTORE_RENDER_REFRESH_SEEK_OFFSET_MS);
        if (video_all > 0) {
            target = Math.min(target, Math.max(0L, video_all - 1L));
        }
        if (target == currentPosition && currentPosition > 0L) {
            target = Math.max(0L, currentPosition - 1L);
        }

        try {
            attachSurfaceIfPossible();
        } catch (Exception ignore) {
        }
        try {
            player.seekTo(target);
            markExplicitSeek(target);
            if (hasDanmaku && mDanmakuView != null) {
                try {
                    mDanmakuView.seekTo(target);
                } catch (Exception ignore) {
                }
            }
            syncProgressUiFromPlayer(target);
            backgroundSurfaceRefreshTriggered = true;
            backgroundSurfaceRefreshTriggeredUptimeMs = android.os.SystemClock.uptimeMillis();
            backgroundSurfaceRefreshTriggeredPosMs = target;
            if (inBackgroundSurfaceRestore && isOnlineVideo) {
                backgroundSurfaceRestoreOutputFpsObserved = false;
                backgroundSurfaceRestoreOutputFpsObservedUptimeMs = 0L;
                backgroundSurfaceRestoreLastObservedOutputFps = 0f;
            }
            Logu.w("surface", "background render-refresh seek triggered: reason=" + reason
                    + ", current=" + currentPosition
                    + ", target=" + target
                    + ", online=" + isOnlineVideo
                    + ", session=" + playerSessionId);
            return true;
        } catch (Exception e) {
            Logu.w("surface", "background render-refresh seek failed: reason=" + reason + ", err=" + e.getMessage());
            return false;
        }
    }

    private boolean performBackgroundSurfaceRefresh(@NonNull String reason) {
        if (destroyed || resourcesReleased || isLiveMode || isAudioOnlyMode || !isPrepared)
            return false;

        IjkMediaPlayer player = ijkPlayer;
        if (player == null)
            return false;

        long currentPosition = safeGetPlayerPositionMs();

        // 本地/缓存视频：不要用 seek 作为“催首帧”手段。
        // ijk 在非 accurate seek 时可能对齐到前一个关键帧，导致回退数秒。
        if (!isOnlineVideo) {
            try {
                // 轻量 kick：不改变播放位置，尽量催出渲染。
                player.pause();
            } catch (Exception ignore) {
            }
            try {
                player.start();
            } catch (Exception ignore) {
            }
            backgroundSurfaceRefreshTriggered = true;
            backgroundSurfaceRefreshTriggeredUptimeMs = android.os.SystemClock.uptimeMillis();
            backgroundSurfaceRefreshTriggeredPosMs = currentPosition;
            syncProgressUiFromPlayer(currentPosition);
            Logu.w("surface", "background refresh kick(no-seek) triggered: reason=" + reason
                    + ", pos=" + currentPosition);
            return true;
        }

        float outputFps = 0f;
        float decodeFps = 0f;
        try {
            outputFps = player.getVideoOutputFramesPerSecond();
        } catch (Exception ignore) {
        }
        try {
            decodeFps = player.getVideoDecodeFramesPerSecond();
        } catch (Exception ignore) {
        }
        // 在线视频优先尝试一次极小位移的 accurate seek，强制真正往新 surface 推一帧。
        // 若失败，再回退到 pause/start 的轻量 kick。
        if (performBackgroundSurfaceRenderRefreshSeek(reason + ":renderRefresh", currentPosition)) {
            Logu.w("surface", "background refresh render-seek(online) triggered: reason=" + reason
                    + ", pos=" + currentPosition
                    + ", decodeFps=" + decodeFps
                    + ", outputFps=" + outputFps);
            return true;
        }

        try {
            attachSurfaceIfPossible();
        } catch (Exception ignore) {
        }
        try {
            player.pause();
        } catch (Exception ignore) {
        }
        try {
            player.start();
        } catch (Exception ignore) {
        }
        try {
            attachSurfaceIfPossible();
        } catch (Exception ignore) {
        }
        try {
            backgroundSurfaceRefreshTriggered = true;
            backgroundSurfaceRefreshTriggeredUptimeMs = android.os.SystemClock.uptimeMillis();
            backgroundSurfaceRefreshTriggeredPosMs = currentPosition;
            syncProgressUiFromPlayer(currentPosition);
            Logu.w("surface", "background refresh kick(online no-seek) triggered: reason=" + reason
                    + ", pos=" + currentPosition
                    + ", decodeFps=" + decodeFps
                    + ", outputFps=" + outputFps);
            return true;
        } catch (Exception e) {
            Logu.w("surface", "background refresh kick failed: " + e.getMessage());
            return false;
        }
    }

    private void scheduleBackgroundSurfaceRefreshTimeout(@NonNull String reason) {
        cancelBackgroundSurfaceRefreshTimeout();
        if (destroyed || resourcesReleased || isLiveMode || isAudioOnlyMode || !isPrepared || ijkPlayer == null)
            return;
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());

        final int sessionAtSchedule = playerSessionId;
        backgroundSurfaceRefreshPending = true;
        backgroundSurfaceRefreshTriggered = false;
        backgroundSurfaceRefreshRunnable = () -> {
            backgroundSurfaceRefreshRunnable = null;
            if (destroyed || resourcesReleased || sessionAtSchedule != playerSessionId) {
                backgroundSurfaceRefreshPending = false;
                return;
            }
            if (!backgroundSurfaceRefreshPending || firstVideoFrameRendered || !waitingForFirstVideoFrame) {
                backgroundSurfaceRefreshPending = false;
                return;
            }
            IjkMediaPlayer player = ijkPlayer;
            if (player == null || !isPrepared) {
                backgroundSurfaceRefreshPending = false;
                return;
            }
            backgroundSurfaceRefreshPending = false;
            performBackgroundSurfaceRefresh(reason);
        };
        mainHandler.postDelayed(backgroundSurfaceRefreshRunnable, BACKGROUND_SURFACE_REFRESH_TIMEOUT_MS);
        Logu.d("surface", "schedule background refresh kick: reason=" + reason
                + ", timeout=" + BACKGROUND_SURFACE_REFRESH_TIMEOUT_MS + "ms");
    }

    private void onFirstVideoFrameRendered(@NonNull String reason) {
        long restoreElapsedMs = backgroundSurfaceRestoreStartUptimeMs > 0L
                ? (android.os.SystemClock.uptimeMillis() - backgroundSurfaceRestoreStartUptimeMs)
                : -1L;
        boolean restoreFrameEventReceived = backgroundSurfaceRestoreFrameEventReceived;
        boolean restoreOutputObserved = backgroundSurfaceRestoreOutputFpsObserved;
        int restoreStep = backgroundSurfaceRestoreStep;
        cancelBackgroundResumeRenderHealthCheck();
        cancelBackgroundSurfaceRefreshTimeout();
        cancelOnlineRebuildFirstVideoFrameFallback();
        cancelFirstVideoFrameFallback();
        cancelBackgroundSurfaceRestore();
        if (firstVideoFrameRendered)
            return;
        firstVideoFrameRendered = true;
        waitingForFirstVideoFrame = false;
        waitingForFirstVideoFrameUptimeMs = 0L;
        waitingForFirstVideoFrameStartPosMs = 0L;
        inBackgroundSurfaceRestore = false;
        backgroundSurfaceRestoreStartUptimeMs = 0L;
        hideFrameLoading(reason);
        Logu.d("render", "onFirstVideoFrameRendered: " + reason);
        if (restoreElapsedMs >= 0L) {
            Logu.w("surface", "bgRestore complete: reason=" + reason
                    + ", elapsed=" + restoreElapsedMs + "ms"
                    + ", frameEvent=" + restoreFrameEventReceived
                    + ", outputObserved=" + restoreOutputObserved
                    + ", step=" + restoreStep);
        }
    }

    /**
     * 强制隐藏 loading（不依赖首帧事件）。
     * <p>
     * 用于处理“结束/暂停态 surface 重建时误进入等待首帧，导致 loading 永久悬浮”等场景。
     */
    private void ensureLoadingHidden(@NonNull String reason) {
        // 先停掉所有可能让 loading 再次出现/保持的流程
        cancelBackgroundResumeRenderHealthCheck();
        cancelBackgroundSurfaceRefreshTimeout();
        cancelFirstVideoFrameFallback();
        cancelBackgroundSurfaceRestore();

        waitingForFirstVideoFrame = false;
        firstVideoFrameRendered = true;
        waitingForFirstVideoFrameUptimeMs = 0L;
        waitingForFirstVideoFrameStartPosMs = 0L;

        hideFrameLoading(reason);
        Logu.d("render", "ensureLoadingHidden: " + reason);
    }

    private void cancelLocalBackgroundSurfaceBlackRebuild() {
        if (mainHandler != null && localBackgroundSurfaceBlackRebuildRunnable != null) {
            try {
                mainHandler.removeCallbacks(localBackgroundSurfaceBlackRebuildRunnable);
            } catch (Exception ignore) {
            }
        }
        localBackgroundSurfaceBlackRebuildRunnable = null;
    }

    private void scheduleLocalBackgroundSurfaceBlackRebuild(@NonNull String reason) {
        cancelLocalBackgroundSurfaceBlackRebuild();
        if (destroyed || resourcesReleased || isLiveMode || isOnlineVideo || isAudioOnlyMode)
            return;
        if (!inBackgroundSurfaceRestore || !waitingForFirstVideoFrame || firstVideoFrameRendered)
            return;
        if (ijkPlayer == null || !isPrepared || !isPlaying)
            return;
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());

        final int session = playerSessionId;
        localBackgroundSurfaceBlackRebuildRunnable = () -> {
            localBackgroundSurfaceBlackRebuildRunnable = null;
            if (destroyed || resourcesReleased || session != playerSessionId)
                return;
            if (!inBackgroundSurfaceRestore || !waitingForFirstVideoFrame || firstVideoFrameRendered)
                return;
            IjkMediaPlayer player = ijkPlayer;
            if (player == null || !isPrepared || !isPlaying)
                return;

            float outputFps = 0f;
            float decodeFps = 0f;
            try {
                outputFps = player.getVideoOutputFramesPerSecond();
            } catch (Exception ignore) {
            }
            try {
                decodeFps = player.getVideoDecodeFramesPerSecond();
            } catch (Exception ignore) {
            }
            if (outputFps > 0.01f) {
                if (!inBackgroundSurfaceRestore) {
                    onFirstVideoFrameRendered("localBackgroundBlackRebuildSkip-outputFps=" + outputFps);
                } else {
                    maybeCompleteBackgroundRestoreByHeuristic("localBackgroundBlackRebuild", outputFps);
                }
                return;
            }

            long rebuildPosition = safeGetPlayerPositionMs();
            pendingForcedSeekMs = rebuildPosition;
            progress_history = rebuildPosition;
            Logu.w("surface", "local background restore timeout rebuild: reason=" + reason
                    + ", pos=" + rebuildPosition
                    + ", decodeFps=" + decodeFps
                    + ", outputFps=" + outputFps);
            rebuildPlayerSession("localBackgroundSurfaceBlackTimeout", rebuildPosition);
        };
        mainHandler.postDelayed(localBackgroundSurfaceBlackRebuildRunnable,
                LOCAL_BACKGROUND_SURFACE_BLACK_REBUILD_TIMEOUT_MS);
    }

    private void cancelFirstVideoFrameFallback() {
        if (mainHandler != null && firstVideoFrameFallbackRunnable != null) {
            try {
                mainHandler.removeCallbacks(firstVideoFrameFallbackRunnable);
            } catch (Exception ignore) {
            }
        }
        firstVideoFrameFallbackRunnable = null;
    }

    /**
     * 本地点播首帧兜底：超时后若播放进度已在前进，则认为画面应已恢复，强制结束 loading。
     */
    private void scheduleFirstVideoFrameFallback(@NonNull String reason) {
        cancelFirstVideoFrameFallback();
        if (destroyed || resourcesReleased || isLiveMode || isOnlineVideo || isAudioOnlyMode)
            return;
        // 后台回前台 surface 重建：不要用“进度在走”来隐藏 loading，否则容易出现纯黑屏。
        // 该场景用 startBackgroundSurfaceRestore() 的分级动作来更快触发真正首帧。
        if (inBackgroundSurfaceRestore)
            return;
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());
        final int session = playerSessionId;
        firstVideoFrameFallbackRunnable = () -> {
            firstVideoFrameFallbackRunnable = null;
            if (destroyed || resourcesReleased || session != playerSessionId)
                return;
            if (!waitingForFirstVideoFrame || firstVideoFrameRendered)
                return;
            long pos = safeGetPlayerPositionMs();
            if (pos > 0L || (isPrepared && isPlaying)) {
                onFirstVideoFrameRendered("fallbackTimeout:" + reason + ", pos=" + pos);
            }
        };
        mainHandler.postDelayed(firstVideoFrameFallbackRunnable, FIRST_VIDEO_FRAME_FALLBACK_TIMEOUT_MS);
    }

    private void cancelBackgroundSurfaceRestore() {
        // 结束“后台恢复”状态（否则会屏蔽其它兜底逻辑，导致 loading 卡住不消失）
        inBackgroundSurfaceRestore = false;
        backgroundSurfaceRestoreStartUptimeMs = 0L;
        backgroundSurfaceRestoreStartPosMs = 0L;
        cancelLocalBackgroundSurfaceBlackRebuild();

        if (mainHandler != null && backgroundSurfaceRestoreRunnable != null) {
            try {
                mainHandler.removeCallbacks(backgroundSurfaceRestoreRunnable);
            } catch (Exception ignore) {
            }
        }
        backgroundSurfaceRestoreRunnable = null;

        if (mainHandler != null && backgroundSurfaceRestoreAutoHideRunnable != null) {
            try {
                mainHandler.removeCallbacks(backgroundSurfaceRestoreAutoHideRunnable);
            } catch (Exception ignore) {
            }
        }
        backgroundSurfaceRestoreAutoHideRunnable = null;

        if (mainHandler != null && backgroundSurfaceRestoreProbeRunnable != null) {
            try {
                mainHandler.removeCallbacks(backgroundSurfaceRestoreProbeRunnable);
            } catch (Exception ignore) {
            }
        }
        backgroundSurfaceRestoreProbeRunnable = null;

        cancelLocalBackgroundSurfaceFrameCorrection();

        backgroundSurfaceRestoreStep = 0;
        backgroundSurfaceRestoreBasePosMs = -1L;
        backgroundSurfaceRestoreFrameEventReceived = false;
        backgroundSurfaceRestoreOutputFpsObserved = false;
        backgroundSurfaceRestoreOutputFpsObservedUptimeMs = 0L;
        backgroundSurfaceRestoreLastObservedOutputFps = 0f;
    }

    /**
     * 后台播放回前台（SurfaceView surface 重建）时：
     * - 进入“恢复画面”状态，避免仅凭进度推进就隐藏 loading 导致纯黑
     * - 分级触发渲染（kick / 微小准确 seek / 重绑 surface），尽快拿到 MEDIA_INFO_VIDEO_RENDERING_START。
     */
    private void startBackgroundSurfaceRestore(long restorePositionMs, @NonNull String reason) {
        cancelBackgroundSurfaceRestore();
        cancelLocalBackgroundSurfaceAudioRestore();
        cancelBackgroundSurfaceRefreshTimeout();
        cancelBackgroundResumeRenderHealthCheck();

        if (destroyed || resourcesReleased || isLiveMode || isAudioOnlyMode)
            return;
        if (ijkPlayer == null || !isPrepared)
            return;
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());

        inBackgroundSurfaceRestore = true;
        backgroundSurfaceRestoreStartUptimeMs = android.os.SystemClock.uptimeMillis();
        backgroundSurfaceRestoreBasePosMs = Math.max(0L, restorePositionMs);
        backgroundSurfaceRestoreStep = 0;
        backgroundSurfaceRestoreStartPosMs = safeGetPlayerPositionMs();
        backgroundSurfaceRestoreFrameEventReceived = false;
        backgroundSurfaceRestoreOutputFpsObserved = false;
        backgroundSurfaceRestoreOutputFpsObservedUptimeMs = 0L;
        localBackgroundSurfaceFrameCorrectionPending = false;
        localBackgroundSurfaceFrameCorrectionTriggered = false;
        localBackgroundSurfaceFrameCorrectionTriggeredUptimeMs = 0L;
        localBackgroundSurfaceFrameCorrectionBasePosMs = -1L;

        Logu.w("surface", "startBackgroundSurfaceRestore: reason=" + reason
                + ", online=" + isOnlineVideo
                + ", pos=" + backgroundSurfaceRestoreBasePosMs
                + ", startPos=" + backgroundSurfaceRestoreStartPosMs
                + ", session=" + playerSessionId);

        // 显示 loading（避免纯黑），并提示“恢复画面”。
        beginWaitingForFirstVideoFrame("backgroundSurfaceRestore:" + reason);
        runOnUiThread(() -> {
            if (loading_text0 != null)
                loading_text0.setText("恢复画面中");
            if (loading_text1 != null)
                loading_text1.setText("(｀・ω・´)");
        });

        final int session = playerSessionId;

        if (isOnlineVideo) {
            scheduleBackgroundResumeRenderHealthCheck(backgroundSurfaceRestoreBasePosMs,
                    "bgRestore-online:" + reason,
                    BACKGROUND_RESUME_RENDER_HEALTHCHECK_TIMEOUT_ONLINE_MS);
        } else {
            scheduleLocalBackgroundSurfaceBlackRebuild("bgRestore-local:" + reason);
        }
        startBackgroundSurfaceRestoreProbe(reason);

        // UI 兜底：若一直没有 rendering_start 事件，但播放在继续推进，推断画面已恢复，自动关闭 loading。
        // 这样不会无限卡住（你反馈的“画面已出但提示不消失”）。
        backgroundSurfaceRestoreAutoHideRunnable = new Runnable() {
            @Override
            public void run() {
                if (destroyed || resourcesReleased || session != playerSessionId)
                    return;
                if (!inBackgroundSurfaceRestore)
                    return;
                if (!waitingForFirstVideoFrame || firstVideoFrameRendered)
                    return;
                if (ijkPlayer == null || !isPrepared)
                    return;

                boolean surfaceReady = isRenderSurfaceReady();
                float outputFps = 0f;
                try {
                    outputFps = ijkPlayer.getVideoOutputFramesPerSecond();
                } catch (Exception ignore) {
                }
                if (surfaceReady && outputFps > 0.01f) {
                    if (!maybeCompleteBackgroundRestoreByHeuristic("autoHide", outputFps)
                            && mainHandler != null) {
                        mainHandler.postDelayed(this, 220L);
                    }
                    return;
                }

                if (backgroundSurfaceRestoreStep < 2) {
                    if (mainHandler != null)
                        mainHandler.postDelayed(this, 300L);
                    return;
                }

                long pos = safeGetPlayerPositionMs();
                boolean posMoving = pos >= backgroundSurfaceRestoreStartPosMs + 600L;

                if (posMoving && surfaceReady && isPlaying) {
                    onFirstVideoFrameRendered("bgRestore-autoHideNoEvent");
                    return;
                }

                // 再等一小段时间复查一次（最多 1 次），避免误判导致过早露出纯黑。
                if (mainHandler != null)
                    mainHandler.postDelayed(this, 700L);
            }
        };
        mainHandler.postDelayed(backgroundSurfaceRestoreAutoHideRunnable, BACKGROUND_SURFACE_RESTORE_AUTO_HIDE_DELAY_MS);

        backgroundSurfaceRestoreRunnable = new Runnable() {
            @Override
            public void run() {
                if (destroyed || resourcesReleased || session != playerSessionId)
                    return;
                if (!inBackgroundSurfaceRestore)
                    return;
                if (!waitingForFirstVideoFrame || firstVideoFrameRendered)
                    return;
                IjkMediaPlayer p = ijkPlayer;
                if (p == null || !isPrepared)
                    return;

                long basePos = Math.max(backgroundSurfaceRestoreBasePosMs, safeGetPlayerPositionMs());
                if (video_all > 0) {
                    basePos = Math.min(basePos, Math.max(0L, video_all - 1L));
                }

                try {
                    switch (backgroundSurfaceRestoreStep) {
                        case 0: {
                            // Step0：仅重绑 surface，不触碰播放链，避免音频卡顿。
                            Logu.d("surface", "bgRestore step0 reattach(passive): reason=" + reason
                                    + ", online=" + isOnlineVideo + ", pos=" + basePos);
                            attachSurfaceIfPossible();
                            backgroundSurfaceRestoreStep++;
                            mainHandler.postDelayed(this, BACKGROUND_SURFACE_RESTORE_STEP1_DELAY_MS);
                            return;
                        }
                        case 1: {
                            Logu.d("surface", "bgRestore step1 observe(passive): reason=" + reason
                                    + ", online=" + isOnlineVideo + ", pos=" + basePos);
                            attachSurfaceIfPossible();
                            backgroundSurfaceRestoreStep++;
                            mainHandler.postDelayed(this, BACKGROUND_SURFACE_RESTORE_STEP2_DELAY_MS);
                            return;
                        }
                        case 2: {
                            Logu.d("surface", "bgRestore step2 reattach(passive): reason=" + reason
                                    + ", online=" + isOnlineVideo + ", pos=" + basePos);
                            attachSurfaceIfPossible();
                            backgroundSurfaceRestoreStep++;
                            mainHandler.postDelayed(this, BACKGROUND_SURFACE_RESTORE_STEP3_DELAY_MS);
                            return;
                        }
                        case 3: {
                            Logu.d("surface", "bgRestore step3 observe(passive): reason=" + reason
                                    + ", online=" + isOnlineVideo + ", pos=" + basePos);
                            backgroundSurfaceRestoreStep++;
                            mainHandler.postDelayed(this, BACKGROUND_SURFACE_RESTORE_STEP4_DELAY_MS);
                            return;
                        }
                        case 4: {
                            // Step4：最后再做一次纯 reattach，仍不触碰播放链。
                            Logu.d("surface", "bgRestore step4 final reattach(passive): reason=" + reason
                                    + ", online=" + isOnlineVideo + ", pos=" + basePos);
                            attachSurfaceIfPossible();
                            backgroundSurfaceRestoreStep++;
                            mainHandler.postDelayed(this, BACKGROUND_SURFACE_RESTORE_GIVEUP_DELAY_MS);
                            return;
                        }
                        default: {
                            // 仍未拿到首帧：
                            // - 本地视频：由 timeout rebuild 兜底；这里结束 staged restore 状态即可。
                            // - 在线视频：绝不能在这里退出 restore 状态，否则 progressTimer 会回落到
                            //   posMovingFallback，把“音频在走”误判成“画面恢复”，进而取消后续 health check，
                            //   造成“持续黑屏 + 只播音频”的严重问题。
                            Logu.w("surface", "bgRestore active actions exhausted: reason=" + reason
                                    + ", online=" + isOnlineVideo
                                    + ", waiting=" + waitingForFirstVideoFrame
                                    + ", firstFrame=" + firstVideoFrameRendered);
                            runOnUiThread(() -> {
                                if (loading_text0 != null)
                                    loading_text0.setText("画面恢复较慢");
                                if (loading_text1 != null)
                                    loading_text1.setText(isOnlineVideo ? "正在切换渲染链…" : "请稍候…");
                            });
                            if (isOnlineVideo) {
                                backgroundSurfaceRestoreRunnable = null;
                                return;
                            }
                            cancelBackgroundSurfaceRestore();
                            return;
                        }
                    }
                } catch (Exception e) {
                    Logu.w("surface", "background restore action failed: " + e.getMessage());
                    cancelBackgroundSurfaceRestore();
                }
            }
        };
        mainHandler.postDelayed(backgroundSurfaceRestoreRunnable, BACKGROUND_SURFACE_RESTORE_STEP0_DELAY_MS);
    }

    private void cancelLocalSeekVerify() {
        localSeekVerifyTargetMs = -1L;
        localSeekVerifyRetry = 0;
        if (mainHandler != null && localSeekVerifyRunnable != null) {
            try {
                mainHandler.removeCallbacks(localSeekVerifyRunnable);
            } catch (Exception ignore) {
            }
        }
        localSeekVerifyRunnable = null;
    }

    private void scheduleLocalSeekVerify(long targetMs, @NonNull String reason) {
        cancelLocalSeekVerify();
        if (destroyed || resourcesReleased)
            return;
        if (ijkPlayer == null || !isPrepared)
            return;
        if (isLiveMode || isOnlineVideo || isAudioOnlyMode)
            return;
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());

        final int session = playerSessionId;
        localSeekVerifyTargetMs = targetMs;
        localSeekVerifyRetry = 0;
        localSeekVerifyRunnable = new Runnable() {
            @Override
            public void run() {
                if (destroyed || resourcesReleased || session != playerSessionId)
                    return;
                if (localSeekVerifyTargetMs < 0L)
                    return;
                if (ijkPlayer == null || !isPrepared)
                    return;

                long target = localSeekVerifyTargetMs;
                long pos = safeGetPlayerPositionMs();
                long backward = target - pos;

                if (backward > LOCAL_SEEK_BACKWARD_TOLERANCE_MS && localSeekVerifyRetry < LOCAL_SEEK_VERIFY_MAX_RETRY) {
                    localSeekVerifyRetry++;

                    // 推进目标：把“落后量”补回去并再加一点余量，尽量跳到下一个关键帧附近。
                    long nudge = Math.min(8000L, backward + 650L);
                    long newTarget = target + nudge;
                    if (video_all > 0) {
                        newTarget = Math.min(newTarget, Math.max(0L, video_all - 1L));
                    }
                    newTarget = Math.max(0L, newTarget);

                    try {
                        ijkPlayer.seekTo(newTarget);
                    } catch (Exception ignore) {
                        cancelLocalSeekVerify();
                        return;
                    }
                    markExplicitSeek(newTarget);
                    if (hasDanmaku && mDanmakuView != null) {
                        try {
                            mDanmakuView.seekTo(newTarget);
                        } catch (Exception ignore) {
                        }
                    }
                    syncProgressUiFromPlayer(newTarget);
                    Logu.w("seek", "local seek verify retry=" + localSeekVerifyRetry
                            + ", reason=" + reason
                            + ", pos=" + pos
                            + ", target=" + target
                            + ", newTarget=" + newTarget);

                    // 继续校验一次（最多 2 次），避免仍落在更早关键帧。
                    if (mainHandler != null) {
                        mainHandler.postDelayed(this, LOCAL_SEEK_VERIFY_DELAY_MS);
                    }
                    return;
                }

                cancelLocalSeekVerify();
            }
        };
        mainHandler.postDelayed(localSeekVerifyRunnable, LOCAL_SEEK_VERIFY_DELAY_MS);
    }

    /**
     * 安全停止并释放旧 ijkPlayer（避免 IllegalState / NPE）。
     * <p>
     * 注意：该方法主要供“重建播放会话”使用（仍在前台、对 UI 线程时延更敏感）。
     * onDestroy 的释放走后台线程（见 onDestroy）。
     */
    private void releaseIjkPlayerSafely(@NonNull String reason) {
        IjkMediaPlayer old = ijkPlayer;
        if (old == null)
            return;

        releaseIjkPlayerInstanceSafely(old, reason);
        if (ijkPlayer == old)
            ijkPlayer = null;
    }

    /**
     * 释放 IjkMediaPlayer 的无异常兜底版本。
     * <p>
     * 注意：避免在调用侧持有 Activity 引用；该方法只依赖传入实例。
     */
    private static void releaseIjkPlayerInstanceSafely(IjkMediaPlayer player, @NonNull String reason) {
        if (player == null)
            return;

        Logu.v("player", "releaseIjkPlayerInstanceSafely: " + reason);

        // 先清 listener，避免 release 卡住时仍持有 Activity 回调链。
        try {
            player.setOnPreparedListener(null);
        } catch (Exception ignore) {
        }
        try {
            player.setOnCompletionListener(null);
        } catch (Exception ignore) {
        }
        try {
            player.setOnErrorListener(null);
        } catch (Exception ignore) {
        }
        try {
            player.setOnInfoListener(null);
        } catch (Exception ignore) {
        }
        try {
            player.setOnBufferingUpdateListener(null);
        } catch (Exception ignore) {
        }

        try {
            player.setDisplay(null);
        } catch (Exception ignore) {
        }
        try {
            player.setSurface(null);
        } catch (Exception ignore) {
        }
        try {
            player.pause();
        } catch (Exception ignore) {
        }
        try {
            player.stop();
        } catch (Exception ignore) {
        }
        try {
            player.release();
        } catch (Exception ignore) {
        }
    }

    /**
     * 统一且安全的重建播放会话流程（切清晰度/切分P/听视频模式/互动跳转共用）。
     * <p>
     * 流程：
     * a. startNewPlayerSession()（让旧任务/回调尽快失效）
     * b. stopAllPeriodicTasks()
     * c. release old ijkPlayer
     * d. reset 状态 + new IjkMediaPlayer
     * e. setDisplay() -> Surface 回调就绪后 maybePrepare() -> onPrepared 后启动周期任务
     */
    private void rebuildPlayerSession(@NonNull String reason, long startPositionMs) {
        if (destroyed)
            return;

        // 防止“切听视频模式”残留的 pendingSeek 影响其它重建原因（切分P/清晰度/互动跳转等）。
        if (!"toggleAudioOnlyMode".equals(reason)) {
            pendingAudioOnlyToggleSeekMs = -1L;
        }
        if (!"localBackgroundSurfaceBlackTimeout".equals(reason)
                && !"backgroundSurfaceRecreateRestore".equals(reason)
                && !"backgroundResumeRenderTimeout".equals(reason)
                && !"replayAfterCompletion".equals(reason)) {
            pendingForcedSeekMs = -1L;
        }

        startNewPlayerSession(reason);
        stopAllPeriodicTasks();
        releaseIjkPlayerSafely(reason);
        resetPlaybackFlagsForNewSession();

        ijkPlayer = new IjkMediaPlayer();
        progress_history = startPositionMs;
        setDisplay();
    }

    private void setDisplay() {
        // Surface 回调与会话重建可能来自不同线程；统一切到主线程避免 prepareRequested/displayConfigured 竞态导致重复 prepare。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(this::setDisplay);
            return;
        }
        if (destroyed || resourcesReleased)
            return;

        Logu.v("创建播放器");
        Logu.v("url", video_url);

        displayConfigured = false;

        runOnUiThread(() -> loading_text0.setText("初始化播放"));

        if (ijkPlayer == null)
            return;

        // 需要恢复到指定位置时（听视频切换 / 历史进度恢复 / 切清晰度后续播），
        // 统一启用 accurate seek，减少回退到前一个关键帧造成的“时长先对后退”。
        if (getPendingAccurateSeekTargetMs() >= 0L) {
            try {
                ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1);
            } catch (Exception ignore) {
            }
        }

        // 点播统一启用 accurate seek：
        // - 修复在线视频拖动进度条回退到前一个关键帧（约 3 秒）
        // - 修复后台恢复时 refresh seek 落回关键帧，导致画面/时间短暂错乱
        if (!isLiveMode && !isAudioOnlyMode) {
            try {
                ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1);
            } catch (Exception ignore) {
            }
            try {
                // 避免极端情况下准确 seek 卡太久；单位毫秒。
                ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "accurate-seek-timeout", 5000);
            } catch (Exception ignore) {
            }
        }

        if (isAudioOnlyMode) {
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 1); // 禁用视频
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_frame", 48); // 跳过所有视频帧
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
        } else {
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec",
                    (SharedPreferencesUtil.getBoolean("player_codec", true) ? 1 : 0));
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
        }

        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles",
                (SharedPreferencesUtil.getBoolean("player_audio", false) ? 1 : 0));
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 4);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 100);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);

        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "flush_packets");
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);

        // 这个坑死我！请允许我为解决此问题而大大地兴奋一下ohhhhhhhhhhhhhhhhhhhhhhhhhhhh
        // ijkplayer是自带一个useragent的，要把默认的改掉才能用！
        if (isOnlineVideo) {
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 15 * 1024 * 1024);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", USER_AGENT_WEB);
            Logu.v("设置ua");
        }

        displayConfigured = true;

        // 移除 surfaceTimer 轮询，改为回调驱动；如果 surface 已就绪则立即绑定并尝试 prepare。
        attachSurfaceIfPossible();
        maybePrepare("setDisplay");
    }

    private void MPPrepare(String nowurl) {
        ijkPlayer.setOnPreparedListener(this);
        // 记录 prepare 时的会话 id，用于 TimerTask/回调的过期判断
        final int sessionAtPrepare = playerSessionId;

        if (isLiveMode) {
            runOnUiThread(() -> loading_text0.setText("载入直播中"));
            danmuSocketConnect();
        } else
            runOnUiThread(() -> loading_text0.setText("载入视频中"));
        try {
            if (isOnlineVideo) {
                Map<String, String> headers = new HashMap<>();
                headers.put("Referer", "https://www.bilibili.com/");
                headers.put("Cookie", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
                ijkPlayer.setDataSource(nowurl, headers);
            } else
                ijkPlayer.setDataSource(nowurl);
        } catch (IOException e) {
            e.printStackTrace();
        }

        ijkPlayer.setOnCompletionListener(iMediaPlayer -> {
            if (destroyed || resourcesReleased || iMediaPlayer != ijkPlayer || sessionAtPrepare != playerSessionId)
                return;
            finishWatching = true;
            video_now = Math.max(video_now, video_all);
            video_now_last = video_now;
            final long completionPos = Math.max(0L, video_all);
            runOnUiThread(() -> syncProgressUiFromPlayer(completionPos));
            
            if (interactionData != null && interactionData.edges != null && 
                interactionData.edges.questions != null && !questionShown) {
                checkEndInteractionQuestions();
                if (questionShown) {
                    isPlaying = false;
                    if (hasDanmaku && mDanmakuView != null) {
                        mDanmakuView.pause();
                    }
                    btn_control.setImageResource(R.drawable.btn_player_play);
                    return;
                }
            }
            
            if (loop_enabled) {
                finishWatching = false;
                ijkPlayer.seekTo(0);
                if (hasDanmaku && mDanmakuView != null) {
                    mDanmakuView.seekTo(0L);
                }
                ijkPlayer.start();
            } else if (auto_next_enabled && hasMultiplePages() && currentPageIndex < pagenames.size() - 1) {
                switchToPage(currentPageIndex + 1);
            } else {
                isPlaying = false;
                if (hasDanmaku && mDanmakuView != null) {
                    mDanmakuView.pause();
                }
                btn_control.setImageResource(R.drawable.btn_player_play);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
                    updateMediaSessionPlaybackState();
                }
            }
        });

        ijkPlayer.setOnErrorListener((iMediaPlayer, what, extra) -> {
            if (destroyed || resourcesReleased || iMediaPlayer != ijkPlayer || sessionAtPrepare != playerSessionId)
                return false;
            String EReport = "播放器可能遇到错误！\n错误码：" + what + "\n附加：" + extra;
            Logu.e("ijk-err", EReport);
            // Toast.makeText(PlayerActivity.this, EReport, Toast.LENGTH_LONG).show();
            return false;
        });

        ijkPlayer.setOnBufferingUpdateListener((mp, percent) -> {
            if (destroyed || resourcesReleased || mp != ijkPlayer || sessionAtPrepare != playerSessionId)
                return;
            seekbar_progress.setSecondaryProgress(percent * video_all / 100);
        });

        // 重要：本地/缓存视频同样需要监听 MEDIA_INFO_VIDEO_RENDERING_START，
        // 否则 beginWaitingForFirstVideoFrame() 后 loading 永远无法消失。
        // buffering UI/网速显示仅对在线/直播启用。
        ijkPlayer.setOnInfoListener((mp, what, extra) -> {
            if (destroyed || resourcesReleased || mp != ijkPlayer || sessionAtPrepare != playerSessionId)
                return false;

            if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                if (inBackgroundSurfaceRestore) {
                    backgroundSurfaceRestoreFrameEventReceived = true;
                }
                onFirstVideoFrameRendered("MEDIA_INFO_VIDEO_RENDERING_START");
                return false;
            }

            // 本地点播一般不需要展示“正在缓冲/网速”，且部分版本可能不会触发 buffering 事件。
            if (!isOnlineVideo && !isLiveMode)
                return false;

            if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                runOnUiThread(() -> {
                    cancelFrameLoadingMinShowGuard();
                    loading_info.setVisibility(View.VISIBLE);
                    anim_loading.start();
                    loading_text0.setText("正在缓冲");
                    showLoadingSpeed();
                    if (hasDanmaku && mDanmakuView != null && isPlaying) {
                        mDanmakuView.pause();
                    }
                });
            } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                runOnUiThread(() -> {
                    if (loadingTimer != null)
                        loadingTimer.cancel();
                    if (pendingOnlineRebuildFirstVideoFrameFallback
                            && waitingForFirstVideoFrame && !firstVideoFrameRendered) {
                        pendingOnlineRebuildFirstVideoFrameFallback = false;
                        onFirstVideoFrameRendered("MEDIA_INFO_BUFFERING_END-after-online-rebuild");
                        return;
                    }
                    if (!isOnlineVideo && backgroundSurfaceRefreshTriggered
                            && waitingForFirstVideoFrame && !firstVideoFrameRendered) {
                        // 后台恢复时的轻量 refresh seek 未必还能再次收到 VIDEO_RENDERING_START。
                        // 若已经进入过这条恢复分支，则在 buffering 结束时直接结束 loading，
                        // 避免视频已恢复显示但 loading 一直悬浮不消失。
                        onFirstVideoFrameRendered("MEDIA_INFO_BUFFERING_END-after-background-refresh");
                    }
                    if (!waitingForFirstVideoFrame || firstVideoFrameRendered || isAudioOnlyMode) {
                        cancelFrameLoadingMinShowGuard();
                        loading_info.setVisibility(View.GONE);
                        anim_loading.stop();
                    }
                    if (hasDanmaku && mDanmakuView != null && isPlaying) {
                        mDanmakuView.resume();
                    }
                });
            }

            return false;
        });

        ijkPlayer.setScreenOnWhilePlaying(true);
        ijkPlayer.prepareAsync();
        Logu.v("开始准备");
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onPrepared(IMediaPlayer mediaPlayer) {
        // 防止旧会话的 onPrepared 回调在切清晰度/切分P/听视频模式后仍然执行，导致状态错乱/空指针
        if (destroyed || resourcesReleased) {
            try {
                mediaPlayer.release();
            } catch (Exception ignore) {
            }
            return;
        }
        if (mediaPlayer != ijkPlayer) {
            try {
                mediaPlayer.release();
            } catch (Exception ignore) {
            }
            Logu.w("prepare", "忽略过期 onPrepared: mp!=ijkPlayer, session=" + playerSessionId);
            return;
        }

        finishWatching = false;
        completionReplayNeedsRebuild = false;
        isPrepared = true;
        video_all = (int) ijkPlayer.getDuration();

        changeVideoSize();

        if ((isLiveMode || hasDanmaku) && mDanmakuView != null) {
            mDanmakuView.start();
        }
        if (SharedPreferencesUtil.getBoolean("player_ui_showDanmakuBtn", true)) {
            // 重要：不要用 DanmakuView.show()/hide() 做开关。
            // DFM 内部 show(null) 不会触发 START/RESUME/UPDATE，遇到 quitFlag/等待态时会出现“开了但不动/不出”。
            // 改用 hideAndPauseDrawTask()/showAndResumeDrawTask(position!=null) 确保绘制线程真正恢复。
            isDanmakuVisible = SharedPreferencesUtil.getBoolean("pref_switch_danmaku", true);
            btn_danmaku.setImageResource(isDanmakuVisible ? R.mipmap.danmakuon : R.mipmap.danmakuoff);
            btn_danmaku.setOnClickListener(view -> {
                boolean newVisible = !isDanmakuVisible;
                applyDanmakuVisibility(newVisible, "userToggle");
                isDanmakuVisible = newVisible;
                btn_danmaku.setImageResource(isDanmakuVisible ? R.mipmap.danmakuon : R.mipmap.danmakuoff);
                SharedPreferencesUtil.putBoolean("pref_switch_danmaku", isDanmakuVisible);
            });

            // 应用初始显示状态（与历史设置一致）
            applyDanmakuVisibility(isDanmakuVisible, "init");

            btn_danmaku.setVisibility(View.VISIBLE);
        } else
            btn_danmaku.setVisibility(View.GONE);
        // 原作者居然把旋转按钮命名为danmaku_btn，也是没谁了...我改过来了 ----RobinNotBad
        // 他大抵是觉得能用就行

        if (!isLiveMode) {
            if (loop_enabled)
                btn_loop.setImageResource(R.mipmap.loopon);
            else
                btn_loop.setImageResource(R.mipmap.loopoff);
            btn_loop.setOnClickListener(view -> {
                btn_loop.setImageResource((loop_enabled ? R.mipmap.loopoff : R.mipmap.loopon));
                loop_enabled = !loop_enabled;
            });
            btn_loop.setVisibility(View.VISIBLE);

            // 听视频模式按钮
            // 如果是本地音频文件，隐藏听视频开关（因为已经是纯音频了）
            if (isLocalAudioFile) {
                btn_audio_only.setVisibility(View.GONE);
            } else {
                updateAudioOnlyButton();
                btn_audio_only.setOnClickListener(view -> toggleAudioOnlyMode());
                btn_audio_only.setVisibility(View.VISIBLE);
            }

            if (hasMultiplePages()) {
                btn_page_selector.setVisibility(View.VISIBLE);
                btn_page_selector.setOnClickListener(view -> showPageSelectorCard());
                btn_auto_next.setVisibility(View.VISIBLE);
                updateAutoNextButton();
                btn_auto_next.setOnClickListener(view -> toggleAutoNext());
            } else {
                btn_page_selector.setVisibility(View.GONE);
                btn_auto_next.setVisibility(View.GONE);
            }

            if (SharedPreferencesUtil.getBoolean("player_ui_showQualityBtn", true) && isOnlineVideo) {
                btn_quality.setVisibility(View.VISIBLE);
                btn_quality.setOnClickListener(view -> showQualitySelectorCard());
            } else {
                btn_quality.setVisibility(View.GONE);
            }

            if (!SharedPreferencesUtil.getBoolean("player_ui_showPageBtn", true))
                btn_page_selector.setVisibility(View.GONE);
        } else {
            // 直播模式下隐藏这些按钮
            btn_loop.setVisibility(View.GONE);
            btn_audio_only.setVisibility(View.GONE);
            btn_page_selector.setVisibility(View.GONE);
            btn_auto_next.setVisibility(View.GONE);
            btn_quality.setVisibility(View.GONE);
        }

        seekbar_progress.setMax(video_all);
        progress_str = StringUtil.toTime(video_all / 1000);

        if (isAudioOnlyMode) {
            updateAudioOnlyUI();
        }

        // 切换听视频模式：始终跳转到切换前位置（不受“从上次播放位置”开关/5秒阈值影响）。
        if (!isLiveMode && pendingAudioOnlyToggleSeekMs >= 0L) {
            final long target = pendingAudioOnlyToggleSeekMs;
            pendingAudioOnlyToggleSeekMs = -1L;
            try {
                ijkPlayer.seekTo(target);
            } catch (Exception ignore) {
            }
            markExplicitSeek(target);
            if (hasDanmaku && mDanmakuView != null) {
                try {
                    mDanmakuView.seekTo(target);
                } catch (Exception ignore) {
                }
            }
            syncProgressUiFromPlayer(target);
            Logu.d("进度跳转", String.valueOf(target));
        } else if (!isLiveMode && pendingForcedSeekMs >= 0L) {
            final long target = pendingForcedSeekMs;
            pendingForcedSeekMs = -1L;
            try {
                ijkPlayer.seekTo(target);
            } catch (Exception ignore) {
            }
            markExplicitSeek(target);
            if (hasDanmaku && mDanmakuView != null) {
                try {
                    mDanmakuView.seekTo(target);
                } catch (Exception ignore) {
                }
            }
            syncProgressUiFromPlayer(target);
            Logu.d("进度跳转", "forced=" + target);
        } else if (shouldRestoreFromLastPosition()) {
            // progress_history 统一为毫秒；保持旧行为“超过 5 秒才跳转”。
            ijkPlayer.seekTo(progress_history);
            markExplicitSeek(progress_history);
            if (hasDanmaku && mDanmakuView != null) {
                mDanmakuView.seekTo(progress_history);
            }
            syncProgressUiFromPlayer(progress_history);
            Logu.d("进度跳转", String.valueOf(progress_history));
            runOnUiThread(() -> MsgUtil.showMsg("已从上次的位置播放"));
        }

        if (isAudioOnlyMode) {
            cancelFrameLoadingMinShowGuard();
            loading_info.setVisibility(View.GONE);
            anim_loading.stop();
            waitingForFirstVideoFrame = false;
            firstVideoFrameRendered = true;
            waitingForFirstVideoFrameStartPosMs = 0L;
        } else {
            beginWaitingForFirstVideoFrame("onPrepared");
            if (pendingOnlineRebuildFirstVideoFrameFallback) {
                updateFrameLoadingText("恢复画面中", "(｀・ω・´)");
                scheduleOnlineRebuildFirstVideoFrameFallback("onPrepared");
            }
        }
        isPlaying = true;
        btn_control.setImageResource(R.drawable.btn_player_pause);

        text_speed.setVisibility(layout_top.getVisibility());
        if (isLiveMode)
            text_speed.setVisibility(View.GONE);
        text_speed.setOnClickListener(view -> layout_speed.setVisibility(View.VISIBLE));
        layout_speed.setOnClickListener(view -> layout_speed.setVisibility(View.GONE));

        btn_debug.setOnClickListener(view -> showInteractionDebugDialog());
        updateDebugButtonVisibility();

        float normalSpeed = speed_values[seekbar_speed.getProgress()];
        setPlaybackSpeed(normalSpeed);
        text_speed.setText(speed_strs[seekbar_speed.getProgress()]);

        progressChange();
        onlineChange();

        ijkPlayer.start();

        // 仅对点播启用 watchdog（直播进度/弹幕模型不同，容易误判）。
        if (!isLiveMode) {
            startDanmakuWatchdog();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            updateMediaSessionMetadata();
            updateMediaSessionPlaybackState();
        }

        btn_control.setOnClickListener(view -> controlVideo());
        btn_subtitle.setOnClickListener(view -> CenterThreadPool.run(() -> downSubtitle(true)));
    }

    private void showLoadingSpeed() {
        // 防止 buffering 触发多次导致 loadingTimer 叠加
        try {
            if (loadingTimer != null) {
                loadingTimer.cancel();
                loadingTimer = null;
            }
        } catch (Exception ignore) {
            loadingTimer = null;
        }
        final int session = playerSessionId;
        loadingTimer = new Timer();
        loadingTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (destroyed || resourcesReleased || session != playerSessionId)
                    return;
                IjkMediaPlayer p = ijkPlayer;
                if (p == null)
                    return;
                float speed;
                try {
                    speed = p.getTcpSpeed();
                } catch (Exception ignore) {
                    return;
                }
                String text = String.format(Locale.CHINA, "%.1f", speed / 1024f) + "KB/s";
                runOnUiThread(() -> loading_text1.setText(text));
            }
        }, 0, 500);
    }

    private void changeVideoSize() {
        if (!isPrepared || ijkPlayer == null)
            return;
        int width = ijkPlayer.getVideoWidth();
        int height = ijkPlayer.getVideoHeight();
        Logu.v("screen", screen_width + "x" + screen_height);
        Logu.v("video", width + "x" + height);

        // 在听视频模式下，视频宽高可能为0，跳过尺寸调整
        if (width == 0 || height == 0) {
            Logu.v("视频尺寸", "视频宽高为0，跳过尺寸调整（可能处于听视频模式）");
            return;
        }

        if (SharedPreferencesUtil.getBoolean("player_ui_round", false)) {
            float video_mul = (float) height / (float) width;
            double sqrt = Math.sqrt(screen_width * screen_width / ((double) (height * height) / (width * width) + 1));
            video_height = (int) (sqrt * video_mul + 0.5);
            video_width = (int) (sqrt + 0.5);
        } else {
            int width_case1 = width * screen_height / height;
            int height_case2 = height * screen_width / width;

            if (width_case1 <= screen_width) {
                video_width = width_case1;
                video_height = screen_height;
            } else {
                video_width = screen_width;
                video_height = height_case2;
            }
        }

        runOnUiThread(() -> {
            layout_video.setLayoutParams(new RelativeLayout.LayoutParams(video_width, video_height));
            Logu.v("改变视频区域大小", video_width + "x" + video_height);
            video_origX = (screen_width - video_width) / 2f;
            video_origY = (screen_height - video_height) / 2f;

            layout_video.postDelayed(() -> {
                layout_video.setX(video_origX);
                layout_video.setY(video_origY);
                Logu.v("改变视频位置", ((screen_width - video_width) / 2) + "," + ((screen_height - video_height) / 2));
            }, 60); // 别问为什么，问就是必须这么写，要等上面的绘制完成
        });
    }

    private void progressChange() {
        // 防止重复启动进度 Timer（切清晰度/切分P/听视频/互动跳转时）
        try {
            if (progressTimer != null) {
                progressTimer.cancel();
                progressTimer = null;
            }
        } catch (Exception ignore) {
            progressTimer = null;
        }
        final int session = playerSessionId;
        progressTimer = new Timer();
        TimerTask task = new TimerTask() {
            @SuppressLint("SetTextI18n")
            @Override
            public void run() {
                if (destroyed || resourcesReleased || session != playerSessionId)
                    return;
                IjkMediaPlayer p = ijkPlayer;
                if (p == null)
                    return;
                if (isPrepared && isPlaying && !isSeeking) {
                    int pos;
                    try {
                        pos = (int) p.getCurrentPosition();
                    } catch (Exception ignore) {
                        pos = (int) getLatestPlayerPositionForDanmaku();
                        if (pos <= 0)
                            return;
                    }
                    video_now = pos;
                    updateLatestPlayerPosition(pos);

                    // 兜底：部分设备/本地文件场景可能收不到 MEDIA_INFO_VIDEO_RENDERING_START，
                    // 导致 loading 一直不消失。若检测到进度已在前进，则认为画面应已恢复（至少不应无限 loading）。
                    if (waitingForFirstVideoFrame && !firstVideoFrameRendered && !isAudioOnlyMode) {
                        if (inBackgroundSurfaceRestore) {
                            float outputFps = 0f;
                            try {
                                outputFps = p.getVideoOutputFramesPerSecond();
                            } catch (Exception ignore) {
                            }
                            if (outputFps > 0.01f) {
                                maybeCompleteBackgroundRestoreByHeuristic("progressTimer", outputFps);
                            }
                        } else {
                            long nowUptime = android.os.SystemClock.uptimeMillis();
                            long waited = waitingForFirstVideoFrameUptimeMs > 0L
                                    ? (nowUptime - waitingForFirstVideoFrameUptimeMs)
                                    : 0L;
                            if (isOnlineVideo && backgroundResumeRenderHealthCheckRunnable != null) {
                                return;
                            }
                            long movedSinceWaitStart = Math.max(0L, video_now - waitingForFirstVideoFrameStartPosMs);
                            // 等待超过 800ms 且播放进度已明显前进（>300ms）时触发。
                            if (waited >= 800L && movedSinceWaitStart >= 300L) {
                                onFirstVideoFrameRendered("posMovingFallback");
                            }
                        }
                    }

                    if (video_now_last != video_now) { // 检测进度是否在变动
                        video_now_last = video_now;
                        float curr_sec = video_now / 1000f;
                        runOnUiThread(() -> {
                            if (isLiveMode) {
                                text_progress.setText(StringUtil.toTime((int) curr_sec));
                                text_online.setText(online_number);
                            } else {
                                seekbar_progress.setProgress(video_now);
                                // progressBar上有一个onProgressChange的监听器，文字更改在那里
                            }
                        });
                        if (subtitles != null)
                            showSubtitle(curr_sec + subtitle_delta);
                        else
                            runOnUiThread(() -> text_subtitle.setVisibility(View.GONE));
                        
                        if (viewPointAdapter != null && viewPoints != null && !viewPoints.isEmpty()) {
                            viewPointAdapter.updateCurrentPosition((int) curr_sec);
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
                            updateMediaSessionPlaybackState();
                        }
                    }
                }
            }
        };
        progressTimer.schedule(task, 0, 250);
    }

    private void onlineChange() {
        if (!SharedPreferencesUtil.getBoolean("player_show_online", false) || isLiveMode || aid == 0 || cid == 0)
            return;

        // 防止重复启动在线人数 Timer
        try {
            if (onlineTimer != null) {
                onlineTimer.cancel();
                onlineTimer = null;
            }
        } catch (Exception ignore) {
            onlineTimer = null;
        }
        final int session = playerSessionId;
        onlineTimer = new Timer();
        TimerTask task = new TimerTask() {
            @SuppressLint("SetTextI18n")
            @Override
            public void run() {
                if (destroyed || resourcesReleased || session != playerSessionId)
                    return;
                if (ijkPlayer != null) {
                    try {
                        online_number = VideoInfoApi.getWatching(aid, cid);
                        runOnUiThread(() -> {
                            if (!online_number.isEmpty())
                                text_online.setText(online_number + "人在看");
                            else
                                text_online.setText("");
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            MsgUtil.err(e);
                            text_online.setVisibility(View.GONE);
                        });
                        this.cancel();
                    }
                }
            }
        };
        onlineTimer.schedule(task, 0, 5000);
    }

    private void getSubtitle(String subtitle_url) {
        if (subtitle_url == null || subtitle_url.isEmpty())
            return;
        try {
            if (isOnlineVideo)
                subtitles = PlayerApi.getSubtitle(subtitle_url);
            else
                subtitles = PlayerApi.getSubtitle(new File(subtitle_url));

            if (subtitles == null)
                return;

            subtitle_count = subtitles.length;
            subtitle_curr_index = 0;
            runOnUiThread(() -> btn_subtitle.setImageResource(R.mipmap.subtitle_on));
        } catch (Exception e) {
            MsgUtil.err(e);
        }
    }

    private void showSubtitle(float curr_sec) {
        if (subtitles == null || subtitle_count == 0) {
            runOnUiThread(() -> text_subtitle.setVisibility(View.GONE));
            return;
        }
        
        Subtitle subtitle_curr = subtitles[subtitle_curr_index];

        boolean need_adjust = true;
        boolean need_show = true;

        while (need_adjust) {
            if (curr_sec < subtitle_curr.from) { // 进度在当前字幕的起始位置之前
                // 如果不是第一条字幕，且进度在上一条字幕的结束位置之前，那么字幕前移一位
                // 否则字幕不显示且退出校准（当前进度在两条字幕之间）
                if (subtitle_curr_index != 0 && curr_sec < subtitles[subtitle_curr_index - 1].to) {
                    subtitle_curr_index--;
                } else {
                    need_adjust = false;
                    need_show = false;
                }
            } else if (curr_sec > subtitle_curr.to) { // 在当前字幕的结束位置之后
                // 如果不是最后一条字幕，且进度在下一条字幕的开始位置之后，那么字幕后移一位
                // 否则字幕不显示且退出校准（当前进度在两条字幕之间）
                if (subtitle_curr_index + 1 < subtitle_count && curr_sec > subtitles[subtitle_curr_index + 1].from) {
                    subtitle_curr_index++;
                } else {
                    need_adjust = false;
                    need_show = false;
                }
            } else
                need_adjust = false; // 在当前字幕的时间段内，则退出校准
        }

        if (need_show)
            runOnUiThread(() -> {
                text_subtitle.setText(subtitles[subtitle_curr_index].content);
                text_subtitle.setVisibility(View.VISIBLE);
            });
        else
            runOnUiThread(() -> text_subtitle.setVisibility(View.GONE));
    }

    private int subtitle_selected = -1;

    private void downSubtitle(boolean from_btn) {
        try {
            if (subtitleLinks == null) { // 首次运行，获取字幕
                if (isOnlineVideo)
                    subtitleLinks = PlayerApi.getSubtitleLinks(aid, cid);
                else
                    subtitleLinks = PlayerApi.getSubtitleLinks(new File(danmakuFile.getParentFile(), "subtitles"));
            }

            if (subtitleLinks.length == 1) {
                if (from_btn)
                    MsgUtil.showMsg("本视频无字幕");
                return;
            }

            subtitle_delta = SharedPreferencesUtil.getFloat("player_subtitle_delta", 0.3f);

            boolean ai_not_only = (subtitleLinks.length > 2 || (subtitleLinks.length == 2 && !subtitleLinks[0].isAI));
            boolean ai_allowed = (from_btn || SharedPreferencesUtil.getBoolean("player_subtitle_ai_allowed", false));

            if (ai_not_only || ai_allowed) {
                if (subtitle_selected == -1)
                    subtitle_selected = subtitleLinks.length;

                runOnUiThread(() -> {
                    RecyclerView subtitleRecycler = findViewById(R.id.subtitle_list);
                    SubtitleAdapter adapter = new SubtitleAdapter();
                    adapter.setData(subtitleLinks);
                    adapter.setSelectedItemIndex(subtitle_selected);
                    adapter.setOnItemClickListener(index -> {
                        layout_card_bg.setVisibility(View.GONE);
                        card_subtitle.setVisibility(View.GONE);
                        subtitle_selected = index;

                        if (subtitleLinks[index].id == -1) {
                            subtitles = null;
                            btn_subtitle.setImageResource(R.mipmap.subtitle_off);
                        } else
                            CenterThreadPool.run(() -> getSubtitle(subtitleLinks[index].url));
                    });
                    subtitleRecycler
                            .setLayoutManager(new CustomLinearManager(this, LinearLayoutManager.HORIZONTAL, false));
                    subtitleRecycler.setHasFixedSize(true);
                    subtitleRecycler.setAdapter(adapter);
                    layout_card_bg.setVisibility(View.VISIBLE);
                    card_subtitle.setVisibility(View.VISIBLE);
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
            MsgUtil.err(e);
        }
    }

    private void downdanmu() {
        if (danmaku_url.isEmpty())
            return;

        boolean useNewApi = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_DANMAKU_API, true);

        if (useNewApi) {
            downdanmuNew();
        } else {
            downdanmuOld();
        }
    }

    private void downdanmuOld() {
        final int session = playerSessionId;
        try (Response response = NetWorkUtil.get(danmaku_url, NetWorkUtil.webHeaders)) {
            if (destroyed || resourcesReleased || session != playerSessionId)
                return;
            if (response == null || response.body() == null)
                return;

            // 调用解压函数进行解压，返回包含解压后数据的 byte[]。
            byte[] decompressBytes = decompress(response.body().bytes());

            if (destroyed || resourcesReleased || session != playerSessionId)
                return;

            if (!danmakuFile.exists())
                danmakuFile.createNewFile();
            try (BufferedSink sink = Okio.buffer(Okio.sink(danmakuFile))) {
                sink.write(decompressBytes);
            }

            if (destroyed || resourcesReleased || session != playerSessionId)
                return;
            streamDanmaku(danmakuFile.toString(), null);
        } catch (Exception e) {
            runOnUiThread(() -> MsgUtil.err(e));
        }
    }

    private void downdanmuNew() {
        final int session = playerSessionId;
        try {
            int estimatedDuration = 3600;

            if (ijkPlayer != null) {
                long duration = ijkPlayer.getDuration();
                if (duration > 0) {
                    estimatedDuration = (int) (duration / 1000);
                }
            }

            Logu.d("新版弹幕", "开始获取新版弹幕，aid=" + aid + ", cid=" + cid);

            java.util.List<DmSegMobileReply> segments = DanmakuApi.getAllVideoDanmaku(aid, cid, estimatedDuration);

            if (destroyed || resourcesReleased || session != playerSessionId)
                return;

            if (segments.isEmpty()) {
                Logu.w("新版弹幕", "未获取到弹幕，尝试使用旧版接口");
                CenterThreadPool.run(() -> {
                    if (destroyed || resourcesReleased || session != playerSessionId)
                        return;
                    downdanmuOld();
                });
                return;
            }

            Logu.d("新版弹幕", "成功获取 " + segments.size() + " 个弹幕分段");

            streamDanmaku(null, segments);
        } catch (Exception e) {
            e.printStackTrace();
            Logu.e("新版弹幕", "获取失败: " + e.getMessage() + "，回退到旧版接口");
            runOnUiThread(() -> MsgUtil.toast("新版弹幕获取失败，使用旧版接口"));
            CenterThreadPool.run(() -> {
                if (destroyed || resourcesReleased || session != playerSessionId)
                    return;
                downdanmuOld();
            });
        }
    }

    private BaseDanmakuParser createParser(String stream) {
        return createParser(stream, null);
    }

    private BaseDanmakuParser createParser(String stream, java.util.List<DmSegMobileReply> protobufSegments) {
        if (protobufSegments != null && !protobufSegments.isEmpty()) {
            BiliProtobufDanmakuParser parser = new BiliProtobufDanmakuParser();
            parser.sharedPreferences = SharedPreferencesUtil.getSharedPreferences();
            parser.setDanmakuSegments(protobufSegments);
            return parser;
        }

        // 兼容性回退
        if (stream == null) {
            return new BaseDanmakuParser() {
                @Override
                protected Danmakus parse() {
                    return new Danmakus();
                }
            };
        }

        ILoader loader = DanmakuLoaderFactory.create(DanmakuLoaderFactory.TAG_BILI);
        if (loader == null) {
            return new BaseDanmakuParser() {
                @Override
                protected Danmakus parse() {
                    return new Danmakus();
                }
            };
        }
        try {
            loader.load(stream);
        } catch (Exception e) {
            // 文件损坏/读取失败时回退为空弹幕，避免直接崩溃。
            Logu.e("danmaku", "loader.load failed: " + e.getMessage());
            return new BaseDanmakuParser() {
                @Override
                protected Danmakus parse() {
                    return new Danmakus();
                }
            };
        }
        BaseDanmakuParser parser = new BiliDanmukuParser();
        parser.sharedPreferences = SharedPreferencesUtil.getSharedPreferences();
        IDataSource<?> dataSource = loader.getDataSource();
        parser.load(dataSource);
        return parser;
    }

    /** 当前 Danmaku 请求是否仍有效（会话/销毁/序列保护） */
    private boolean isDanmakuRequestValid(int session, int seq) {
        return !destroyed && !resourcesReleased && session == playerSessionId && seq == danmakuPrepareSeq.get();
    }

    /**
     * A/B：在每次 prepare 前统一停止并释放旧 Danmaku 会话。
     * <p>
     * - 主线程执行（必要时 runOnUiThread）
     * - 优先 mDanmakuView.release() 停止 DrawHandler/HandlerThread
     * - 尝试清理 callback，避免 DanmakuView/handler 间接持有 Activity
     * - 显式释放 lastDanmakuParser，确保 dataSource 立刻 close（解决“新 prepare 覆盖旧 prepare”的窗口期）
     */
    private void resetDanmakuBeforePrepare(@NonNull String reason) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> resetDanmakuBeforePrepare(reason));
            return;
        }

        Logu.v("danmaku", "resetDanmakuBeforePrepare: " + reason);
        resetDanmakuPreparedState();

        // 1) 先断开 callback，避免旧 handler 回调链继续强引用 Activity
        if (mDanmakuView != null) {
            try {
                mDanmakuView.setCallback(null);
            } catch (Exception ignore) {
            }
        }

        // 2) 停止并释放旧的 DrawHandler/线程
        if (mDanmakuView != null) {
            try {
                mDanmakuView.release();
            } catch (Exception ignore) {
            }
        }

        // 3) release 后再次置空 callback（DanmakuView 内部也持有 mCallback 引用）
        if (mDanmakuView != null) {
            try {
                mDanmakuView.setCallback(null);
            } catch (Exception ignore) {
            }
        }

        // 4) 显式释放上一次 parser（即使 DFM quit 会 release，这里也提前做一次以缩小泄漏窗口）
        BaseDanmakuParser old = lastDanmakuParser;
        lastDanmakuParser = null;
        if (old != null) {
            try {
                old.release();
            } catch (Exception ignore) {
            }
        }
    }

    private void streamDanmaku(String danmakuFile) {
        streamDanmaku(danmakuFile, null);
    }

    private void streamDanmaku(String danmakuFile, java.util.List<DmSegMobileReply> protobufSegments) {
        // 普通 stream 请求会覆盖之前待完成的硬恢复语义，避免后来的外部调用被误当成“硬恢复完成”。
        pendingDanmakuRestartAfterPrepare = false;
        pendingDanmakuRestartSessionId = -1;
        pendingDanmakuRestartPrepareSeq = -1;
        pendingDanmakuRestartVisible = true;
        resetDanmakuPreparedState();
        streamDanmaku(danmakuFile, protobufSegments, playerSessionId,
                danmakuPrepareSeq.incrementAndGet(), true, false);
    }

    private void streamDanmaku(String danmakuFile,
                               java.util.List<DmSegMobileReply> protobufSegments,
                               int session,
                               int seq,
                               boolean cacheSource,
                               boolean fromHardRecovery) {
        if (cacheSource) {
            cacheCurrentDanmakuSource(danmakuFile, protobufSegments);
        }
        if (fromHardRecovery) {
            pendingDanmakuRestartSessionId = session;
            pendingDanmakuRestartPrepareSeq = seq;
        }
        resetDanmakuPreparedState();

        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> streamDanmakuOnMainThread(danmakuFile, protobufSegments, session, seq));
            return;
        }
        streamDanmakuOnMainThread(danmakuFile, protobufSegments, session, seq);
    }

    private void streamDanmakuOnMainThread(String danmakuFile, java.util.List<DmSegMobileReply> protobufSegments,
                                          int session, int seq) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> streamDanmakuOnMainThread(danmakuFile, protobufSegments, session, seq));
            return;
        }
        if (!isDanmakuRequestValid(session, seq))
            return;

        Logu.v("danmaku", "stream");

        // A/B：每次 prepare 前都先释放旧会话/旧 parser
        resetDanmakuBeforePrepare("streamDanmaku");
        if (!isDanmakuRequestValid(session, seq))
            return;

        if (mDanmakuView == null)
            return;

        mContext = DanmakuContext.create();
        HashMap<Integer, Integer> maxLinesPair = new HashMap<>();
        maxLinesPair.put(BaseDanmaku.TYPE_SCROLL_RL, SharedPreferencesUtil.getInt("player_danmaku_maxline", 15));
        HashMap<Integer, Boolean> overlap = new HashMap<>();
        overlap.put(BaseDanmaku.TYPE_SCROLL_LR, SharedPreferencesUtil.getBoolean("player_danmaku_allowoverlap", true));
        overlap.put(BaseDanmaku.TYPE_FIX_BOTTOM, SharedPreferencesUtil.getBoolean("player_danmaku_allowoverlap", true));
        mContext.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 1)
                .setDuplicateMergingEnabled(SharedPreferencesUtil.getBoolean("player_danmaku_mergeduplicate", false))
                .setScrollSpeedFactor(SharedPreferencesUtil.getFloat("player_danmaku_speed", 1.0f))
                .setScaleTextSize(SharedPreferencesUtil.getFloat("player_danmaku_size", 0.7f))// 缩放值
                .setMaximumLines(maxLinesPair)
                .setDanmakuTransparency(SharedPreferencesUtil.getFloat("player_danmaku_transparency", 0.5f))
                .preventOverlapping(overlap);

        if (!isDanmakuRequestValid(session, seq))
            return;
        BaseDanmakuParser mParser = createParser(danmakuFile, protobufSegments);
        lastDanmakuParser = mParser;

        if (!isDanmakuRequestValid(session, seq)) {
            try {
                if (mParser != null)
                    mParser.release();
            } catch (Exception ignore) {
            }
            if (lastDanmakuParser == mParser)
                lastDanmakuParser = null;
            return;
        }

        final boolean isProtobuf = protobufSegments != null && !protobufSegments.isEmpty();
        mDanmakuView.setCallback(new SafeDanmakuCallback(this, session, seq, isProtobuf));
        mDanmakuView.enableDanmakuDrawingCache(true);
        if (!isDanmakuRequestValid(session, seq))
            return;
        mDanmakuView.prepare(mParser, mContext);
    }

    /** 避免 callback 强引用 Activity；并带 session/seq 校验，防止旧回调落地 */
    private static final class SafeDanmakuCallback implements DrawHandler.Callback {
        private final WeakReference<PlayerActivity> ref;
        private final int session;
        private final int seq;
        private final boolean isProtobuf;

        /** 用于对 updateTimer 的轻微回跳做钳制（防止弹幕时间轴倒退引起抖动） */
        private long lastTimerSyncPosMs = -1L;
        /** 弹幕时间轴插值平滑锚点（在 rawPos 阶梯式变化时，用 uptime 做短时平滑推进） */
        private long smoothAnchorPlayerPosMs = -1L;
        private long smoothAnchorUptimeMs = 0L;
        private long lastRawPlayerPosMs = -1L;
        private long lastSmoothTimerPosMs = -1L;
        /** 限制日志频率，避免刷屏 */
        private long lastClampLogUptimeMs = 0L;
        private long lastSmoothLogUptimeMs = 0L;

        SafeDanmakuCallback(PlayerActivity act, int session, int seq, boolean isProtobuf) {
            this.ref = new WeakReference<>(act);
            this.session = session;
            this.seq = seq;
            this.isProtobuf = isProtobuf;
        }

        private PlayerActivity a() {
            return ref.get();
        }

        private void resetSmoothState(long playerPosMs, long nowUptimeMs, boolean allowBackward) {
            lastTimerSyncPosMs = playerPosMs;
            smoothAnchorPlayerPosMs = playerPosMs;
            smoothAnchorUptimeMs = nowUptimeMs;
            lastRawPlayerPosMs = playerPosMs;
            lastSmoothTimerPosMs = allowBackward ? playerPosMs : Math.max(lastSmoothTimerPosMs, playerPosMs);
        }

        @Override
        public void prepared() {
            PlayerActivity a = a();
            if (a == null || !a.isDanmakuRequestValid(session, seq))
                return;
            a.markDanmakuPrepared(session, seq);
            Logu.v("danmaku", "prepared");
            String msg = isProtobuf ? "弹幕君准备完毕～(是新来的哦～)" : "弹幕君准备完毕～(*≧ω≦)";
            a.addDanmaku(msg, Color.WHITE);
            a.handleDanmakuPrepared(session, seq, isProtobuf);
        }

        @Override
        public void updateTimer(DanmakuTimer timer) {
            PlayerActivity a = a();
            if (a == null || !a.isDanmakuRequestValid(session, seq))
                return;
            // 避免在 release/onDestroy 后仍访问 ijkPlayer 导致崩溃（部分机型 getCurrentPosition 可能抛异常）
            IjkMediaPlayer p = a.ijkPlayer;
            if (p == null || !a.isPrepared)
                return;
            long currentPos = a.getLatestPlayerPositionForDanmaku();

            // 允许“显式 seek”后短窗口内时间轴回退（不做钳制），避免 seek 后弹幕短暂冻结。
            long nowUptime = android.os.SystemClock.uptimeMillis();
            if (nowUptime - a.lastExplicitSeekUptimeMs < DANMAKU_EXPLICIT_SEEK_GRACE_MS) {
                // seek 期间 getCurrentPosition 可能先回到旧值再跳到目标值，这里直接跟随播放器。
                resetSmoothState(currentPos, nowUptime, true);
                timer.update(currentPos);
                return;
            }

            // 初始化
            if (lastTimerSyncPosMs < 0L || smoothAnchorPlayerPosMs < 0L) {
                resetSmoothState(currentPos, nowUptime, true);
                timer.update(currentPos);
                return;
            }

            // 对轻微回跳做钳制，避免弹幕时间轴倒退造成视觉抖动。
            if (lastRawPlayerPosMs >= 0L && currentPos + DANMAKU_TIMER_BACKWARD_TOLERANCE_MS < lastRawPlayerPosMs) {
                long backward = lastRawPlayerPosMs - currentPos;
                if (backward > DANMAKU_TIMER_BACKWARD_RESET_THRESHOLD_MS) {
                    // 大幅回退：认为是异常/跳转（但不在显式 seek grace 内），放行并重置基准
                    resetSmoothState(currentPos, nowUptime, true);
                    timer.update(currentPos);
                    return;
                } else {
                    long raw = currentPos;
                    currentPos = lastRawPlayerPosMs;
                    if (nowUptime - lastClampLogUptimeMs > 2000L) {
                        lastClampLogUptimeMs = nowUptime;
                        Logu.d("danmaku", "timer clamp: raw=" + raw + ", clamp=" + currentPos + ", back=" + backward);
                    }
                }
            }

            float speed = a.getPlaybackSpeed();
            if (speed <= 0f) {
                speed = 1.0f;
            }

            long projectedPos = smoothAnchorPlayerPosMs
                    + Math.round((nowUptime - smoothAnchorUptimeMs) * speed);
            long smoothPos = Math.max(projectedPos, currentPos);
            long maxLead = Math.max(DANMAKU_TIMER_SMOOTH_MAX_LEAD_MS,
                    Math.round(DANMAKU_TIMER_SMOOTH_MAX_LEAD_MS * Math.max(1.0f, speed)));
            if (smoothPos > currentPos + maxLead) {
                smoothPos = currentPos + maxLead;
            }
            if (lastSmoothTimerPosMs >= 0L && smoothPos < lastSmoothTimerPosMs) {
                smoothPos = lastSmoothTimerPosMs;
            }

            timer.update(smoothPos);
            lastTimerSyncPosMs = smoothPos;

            boolean needRebase = currentPos > lastRawPlayerPosMs
                    || nowUptime - smoothAnchorUptimeMs >= DANMAKU_TIMER_SMOOTH_REBASE_INTERVAL_MS
                    || Math.abs(projectedPos - currentPos) >= DANMAKU_TIMER_SMOOTH_DRIFT_REBASE_MS;
            if (needRebase) {
                smoothAnchorPlayerPosMs = smoothPos;
                smoothAnchorUptimeMs = nowUptime;
            }

            lastRawPlayerPosMs = currentPos;
            lastSmoothTimerPosMs = smoothPos;

            if (nowUptime - lastSmoothLogUptimeMs >= DANMAKU_TIMER_SMOOTH_LOG_INTERVAL_MS) {
                lastSmoothLogUptimeMs = nowUptime;
                Logu.d("danmaku", "timer smooth: raw=" + currentPos
                        + ", smooth=" + smoothPos
                        + ", lead=" + (smoothPos - currentPos)
                        + ", speed=" + speed);
            }
        }

        @Override
        public void danmakuShown(BaseDanmaku danmaku) {
        }

        @Override
        public void drawingFinished() {
        }
    }

    public void addDanmaku(String text, int color) {
        addDanmaku(text, color, 25, 1, 0);
    }

    public void addDanmaku(String text, int color, int textSize, int type, int backgroundColor) {
        // 直播 WebSocket 回调可能在销毁/释放后仍触发；这里做防御避免 NPE。
        if (destroyed || resourcesReleased)
            return;
        if (text == null || ijkPlayer == null || mContext == null || mDanmakuView == null)
            return;

        BaseDanmaku danmaku;
        try {
            danmaku = mContext.mDanmakuFactory.createDanmaku(type);
        } catch (Exception ignore) {
            return;
        }
        if (danmaku == null)
            return;
        danmaku.text = text;
        danmaku.padding = 5;
        danmaku.priority = 1;
        danmaku.textColor = color;
        danmaku.backgroundColor = backgroundColor;
        danmaku.textSize = textSize * (mContext.getDisplayer().getDensity() - 0.6f);
        try {
            danmaku.time = mDanmakuView.getCurrentTime() + 100;
            mDanmakuView.addDanmaku(danmaku);
        } catch (Exception ignore) {
        }
    }

    public static byte[] decompress(byte[] data) {
        byte[] output;
        Inflater decompresser = new Inflater(true);// 这个true是关键
        decompresser.reset();
        decompresser.setInput(data);
        ByteArrayOutputStream o = new ByteArrayOutputStream(data.length);
        try {
            byte[] buf = new byte[2048];
            while (!decompresser.finished()) {
                int i = decompresser.inflate(buf);
                o.write(buf, 0, i);
            }
            output = o.toByteArray();
        } catch (Exception e) {
            output = data;
            e.printStackTrace();
        } finally {
            try {
                o.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        decompresser.end();
        return output;
    }

    public void controlVideo() {
        if (isPlaying) {
            playerPause();
        } else {
            if (finishWatching || video_now >= video_all - 250) {
                replayFromStartAfterCompletion();
            } else {
                playerResume();
            }
        }
        autohideReset();
    }

    @SuppressLint("SetTextI18n")
    public void changeVolume(Boolean add_or_cut) {
        int volumeNow = audioManager.getStreamVolume(STREAM_MUSIC);
        int volumeMax = audioManager.getStreamMaxVolume(STREAM_MUSIC);
        int volumeNew = volumeNow + (add_or_cut ? 1 : -1);
        if (volumeNew >= 0 && volumeNew <= volumeMax) {
            audioManager.setStreamVolume(STREAM_MUSIC, volumeNew, 0);
            volumeNow = volumeNew;
        }
        int show = (int) ((float) volumeNow / (float) volumeMax * 100);

        text_volume.setVisibility(View.VISIBLE);
        text_volume.setText("音量：" + show + "%");

        text_volume.removeCallbacks(hideVolume);
        text_volume.postDelayed(hideVolume, 3000);
        autohideReset();
    }

    private final Runnable hideVolume = () -> text_volume.setVisibility(View.GONE);

    /**
     * 软件旋屏，给某些特殊设备用的。
     * 终端屎山又增高啦
     */
    private void softwareRotate() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        screen_width = screen_landscape ? displayMetrics.heightPixels : displayMetrics.widthPixels;
        screen_height = screen_landscape ? displayMetrics.widthPixels : displayMetrics.heightPixels;

        ViewGroup root_layout = findViewById(R.id.root_layout);
        ViewGroup.LayoutParams params = root_layout.getLayoutParams();
        params.width = screen_width;
        params.height = screen_height;

        if (isPrepared && !destroyed)
            runOnUiThread(() -> {
                root_layout.setLayoutParams(params);
                root_layout.setPivotX(0);
                root_layout.setPivotY(0);
                root_layout.setX(screen_landscape ? screen_height : 0);
                root_layout.setRotation(screen_landscape ? 90 : 0);
                if (SharedPreferencesUtil.getBoolean("player_display", Build.VERSION.SDK_INT < 26)) {
                    if (textureView != null) {
                        Matrix matrix = new Matrix();
                        textureView.getTransform(matrix);
                        matrix.postRotate(0);
                        textureView.setTransform(matrix);
                    }
                } else {
                    MsgUtil.showMsg("请切换为TextureView才能支持软件旋屏！");
                }
            });
        changeVideoSize();
    }

    private void videoMoveBy(float dx, float dy) {
        float x = dx + layout_video.getX();
        float y = dy + layout_video.getY();

        float width_delta = 0.5f * video_width * (layout_video.getScaleX() - 1f);
        float height_delta = 0.5f * video_height * (layout_video.getScaleY() - 1f);
        float video_x_min = video_origX - width_delta;
        float video_x_max = video_origX + width_delta;
        float video_y_min = video_origY - height_delta;
        float video_y_max = video_origY + height_delta;

        if (x < video_x_min)
            x = video_x_min;
        if (x > video_x_max)
            x = video_x_max;
        if (y < video_y_min)
            y = video_y_min;
        if (y > video_y_max)
            y = video_y_max;

        if (layout_video.getX() != x || layout_video.getY() != y) {
            // Logu.v("gesture","moveto:" + x + "," + y);
            layout_video.setX(x);
            layout_video.setY(y);
            if (!gesture_moved && (Math.abs(video_origX - x) > 5f || Math.abs(video_origY - y) > 5f)) {
                gesture_moved = true;
            }
        }
    }

    private void playerPause() {
        isPlaying = false;
        if (ijkPlayer != null && isPrepared) {
            ijkPlayer.pause();
            if (hasDanmaku && mDanmakuView != null) {
                mDanmakuView.pause();
            }
        }
        if (btn_control != null)
            btn_control.setImageResource(R.drawable.btn_player_play);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            updateMediaSessionPlaybackState();
        }
    }

    private void playerResume() {
        isPlaying = true;
        if (ijkPlayer != null && isPrepared) {
            ijkPlayer.start();
            if (hasDanmaku && mDanmakuView != null) {
                mDanmakuView.resume();
            }
        }
        if (btn_control != null)
            btn_control.setImageResource(R.drawable.btn_player_pause);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            updateMediaSessionPlaybackState();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Logu.v("开始旋转屏幕");

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        screen_width = displayMetrics.widthPixels;// 获取屏宽
        screen_height = displayMetrics.heightPixels;// 获取屏高
        changeVideoSize();

        Logu.v("旋转屏幕结束");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Logu.v("onNewIntent");
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Logu.v("onPause");
        cancelConsumeBackgroundPlaybackFlag();
        if (finishWatching && isPrepared && !isLiveMode) {
            completionReplayNeedsRebuild = true;
            Logu.d("player", "onPause after completion: mark replay rebuild");
        }
        boolean backgroundEnabled = SharedPreferencesUtil.getBoolean("player_background", false);
        skipSeekOnNextSurfaceCreatedFromBackgroundPlayback = backgroundEnabled
                && isPrepared && isPlaying && !isLiveMode;
        backgroundPlaybackSurfaceRecreated = false;
        if (!backgroundEnabled) {
            pausedByLifecycle = isPrepared && isPlaying && !finishWatching;
            playerPause();
        } else {
            pausedByLifecycle = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Logu.v("onResume");
        cancelConsumeBackgroundPlaybackFlag();
        if (pausedByLifecycle) {
            boolean canAutoResume = isPrepared && !isPlaying && !finishWatching;
            pausedByLifecycle = false;
            if (canAutoResume) {
                playerResume();
            }
        }
        if (skipSeekOnNextSurfaceCreatedFromBackgroundPlayback
                && !backgroundPlaybackSurfaceRecreated
                && isPrepared && !isLiveMode && isRenderSurfaceReady()) {
            long currentPosition = safeGetPlayerPositionMs();
            syncProgressUiFromPlayer(currentPosition);
            if (isOnlineVideo) {
                Logu.w("surface", "onResume: staged online restore without surface recreate, pos="
                        + currentPosition + ", session=" + playerSessionId);
                scheduleConsumeBackgroundPlaybackFlag("onResume-noSurfaceRecreate-online");
                startBackgroundSurfaceRestore(currentPosition, "onResume-noSurfaceRecreate-online");
                return;
            } else {
                // 本地/缓存视频此前在“未发生 surface recreate”的回前台路径里几乎不做恢复动作，
                // 容易出现恢复时长 1~5s 的长尾。
                // 这里统一走一次本地 staged restore：
                // - 能快速恢复则保留旧会话
                // - 500ms 内仍无有效输出帧则由 local black timeout 兜底重建
                Logu.w("surface", "onResume: staged local restore without surface recreate, pos=" + currentPosition);
                scheduleConsumeBackgroundPlaybackFlag("onResume-noSurfaceRecreate-local");
                startBackgroundSurfaceRestore(currentPosition, "onResume-noSurfaceRecreate-local");
                return;
            }
        }
        if (isPrepared && (!isPlaying || finishWatching)) {
            ensureLoadingHidden("onResume-nonPlaying");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Logu.v("onStop");
    }

    @Override
    protected void onDestroy() {
        // 修复 isFinishing() 早退导致的泄漏；并保证 onDestroy 多路径进入时幂等释放。
        // 说明：原注释提到“部分设备启动 activity 会先调用 onDestroy”的异常情况，这里用 try/catch + 判空确保不引入新崩溃。
        if (resourcesReleased) {
            try {
                super.onDestroy();
            } catch (Exception ignore) {
            }
            return;
        }
        resourcesReleased = true;

        final boolean finishing = isFinishing();

        Logu.v("销毁");
        destroyed = true;
        // 让旧会话的回调尽快失效（即使 Timer 未能及时 cancel 也不会再触发实际逻辑）
        invalidatePlayerSession("onDestroy");

        if (eventBusInit) {
            try {
                EventBus.getDefault().unregister(this);
            } catch (Exception ignore) {
            }
            eventBusInit = false;
        }

        stopAllPeriodicTasks();

        // Surface/Texture 回调与 native Surface 释放（避免 callback 持有 Activity、Surface 泄漏）
        try {
            if (surfaceHolder != null) {
                surfaceHolder.removeCallback(surfaceCallback);
            }
        } catch (Exception ignore) {
        }
        try {
            releaseTextureSurface();
        } catch (Exception ignore) {
        }

        if (mDanmakuView != null) {
            // Danmaku 会话也做幂等释放（callback + parser + handlerThread）
            try {
                resetDanmakuBeforePrepare("onDestroy");
            } catch (Exception ignore) {
            }
            mDanmakuView = null;
        }

        // 直播弹幕 WebSocket/Listener/OkHttp 释放（避免线程/Timer/Activity 引用残留）
        if (liveWebSocket != null) {
            try {
                liveWebSocket.close(1000, "");
            } catch (Exception ignore) {
            }
            liveWebSocket = null;
        }
        if (liveDanmuListener != null) {
            try {
                liveDanmuListener.release();
            } catch (Exception ignore) {
            }
            liveDanmuListener = null;
        }
        if (okHttpClient != null) {
            try {
                okHttpClient.dispatcher().cancelAll();
            } catch (Exception ignore) {
            }
            try {
                okHttpClient.connectionPool().evictAll();
            } catch (Exception ignore) {
            }
            try {
                okHttpClient.dispatcher().executorService().shutdown();
            } catch (Exception ignore) {
            }
            okHttpClient = null;
        }

        // 注意：IjkMediaPlayer.stop/release 在部分设备/网络流上可能阻塞，导致退出时假死/ANR。
        // 这里把“重释放”下沉到后台线程，避免阻塞 UI 线程。
        final IjkMediaPlayer playerToRelease = ijkPlayer;
        ijkPlayer = null;
        if (playerToRelease != null) {
            CenterThreadPool.run(() -> releaseIjkPlayerInstanceSafely(playerToRelease, "onDestroy"));
        }

        if (isOnlineVideo && danmakuFile != null && danmakuFile.exists()) {
            try {
                danmakuFile.delete();
            } catch (Exception ignore) {
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            try {
                mediaSession.release();
            } catch (Exception ignore) {
            }
            mediaSession = null;
        }

        // 保持旧行为：仅在真正退出播放器（finishing）时尝试恢复方向，避免影响因配置变更触发的销毁流程。
        if (finishing) {
            try {
                setRequestedOrientation(SharedPreferencesUtil.getBoolean("ui_landscape", false)
                        ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            } catch (Exception ignore) {
            }
        }

        super.onDestroy();
    }

    /**
     * 停止所有周期任务/延迟任务，确保重建会话或销毁时不会出现 Timer 叠加、回调泄漏、release 后访问 ijkPlayer 崩溃。
     * <p>
     * 说明：保留 Timer 实现以最小行为改动，但做到“同类任务任意时刻最多一个”。
     */
    private void stopAllPeriodicTasks() {
        cancelConsumeBackgroundPlaybackFlag();
        cancelBackgroundResumeRenderHealthCheck();
        cancelBackgroundSurfaceRefreshTimeout();
        cancelOnlineRebuildFirstVideoFrameFallback();
        cancelFirstVideoFrameFallback();
        cancelLocalSeekVerify();
        cancelBackgroundSurfaceRestore();
        cancelLocalBackgroundSurfaceAudioRestore();
        cancelFrameLoadingMinShowGuard();
        clearDanmakuRecoveryState();
        try {
            if (progressTimer != null) {
                progressTimer.cancel();
                progressTimer = null;
            }
        } catch (Exception ignore) {
            progressTimer = null;
        }
        try {
            if (onlineTimer != null) {
                onlineTimer.cancel();
                onlineTimer = null;
            }
        } catch (Exception ignore) {
            onlineTimer = null;
        }
        try {
            if (loadingTimer != null) {
                loadingTimer.cancel();
                loadingTimer = null;
            }
        } catch (Exception ignore) {
            loadingTimer = null;
        }
        try {
            if (speedTimer != null) {
                speedTimer.cancel();
                speedTimer = null;
            }
        } catch (Exception ignore) {
            speedTimer = null;
        }

        if (mainHandler != null) {
            try {
                mainHandler.removeCallbacksAndMessages(null);
            } catch (Exception ignore) {
            }
        }

        // 与播放会话绑定的弹幕 watchdog（同时重置状态，避免下个会话误判）
        stopDanmakuWatchdog();

        // 这些是 UI 上的延迟隐藏/启用，不属于播放会话但也应在销毁时清理
        try {
            if (layout_control != null)
                layout_control.removeCallbacks(hidecon);
        } catch (Exception ignore) {
        }
        try {
            if (text_volume != null)
                text_volume.removeCallbacks(hideVolume);
        } catch (Exception ignore) {
        }
        try {
            if (seekbar_progress != null)
                seekbar_progress.removeCallbacks(progressbarEnable);
        } catch (Exception ignore) {
        }
    }

    OkHttpClient okHttpClient;

    private void danmuSocketConnect() {
        CenterThreadPool.run(() -> {
            try {
                // 防止重复连接/旧 listener 泄漏（切会话或异常重连时）
                try {
                    if (liveWebSocket != null) {
                        liveWebSocket.close(1000, "");
                        liveWebSocket = null;
                    }
                } catch (Exception ignore) {
                    liveWebSocket = null;
                }
                try {
                    if (liveDanmuListener != null) {
                        liveDanmuListener.release();
                        liveDanmuListener = null;
                    }
                } catch (Exception ignore) {
                    liveDanmuListener = null;
                }
                try {
                    if (okHttpClient != null) {
                        okHttpClient.dispatcher().cancelAll();
                        okHttpClient.connectionPool().evictAll();
                        okHttpClient.dispatcher().executorService().shutdown();
                        okHttpClient = null;
                    }
                } catch (Exception ignore) {
                    okHttpClient = null;
                }

                String url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?type=0&id=" + aid;
                ArrayList<String> mHeaders = new ArrayList<>() {
                    {
                        add("Cookie");
                        add(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
                        add("Referer");
                        add("https://live.bilibili.com/" + aid);
                        add("Origin");
                        add("https://live.bilibili.com");
                        add("User-Agent");
                        add(USER_AGENT_WEB);
                    }
                };
                JSONObject data;
                // StrictMode/资源泄漏修复：必须关闭 Response，避免连接池泄漏。
                try (Response response = NetWorkUtil.get(ConfInfoApi.signWBI(url), mHeaders)) {
                    data = new JSONObject(Objects.requireNonNull(response.body()).string())
                            .getJSONObject("data");
                }
                JSONObject host = data.getJSONArray("host_list").getJSONObject(0);

                url = "wss://" + host.getString("host") + ":" + host.getInt("wss_port") + "/sub";
                Logu.v("连接WebSocket", url);

                okHttpClient = new OkHttpClient();
                Request request = new Request.Builder()
                        .url(url)
                        .header("Cookie", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""))
                        .header("Origin", "https://live.bilibili.com")
                        .header("User-Agent", USER_AGENT_WEB)
                        .build();

                PlayerDanmuClientListener listener = new PlayerDanmuClientListener();
                listener.mid = mid;
                listener.roomid = aid;
                listener.key = data.getString("token");
                liveDanmuListener = listener;
                listener.playerActivity = this;

                liveWebSocket = okHttpClient.newWebSocket(request, listener);
                // okHttpClient.dispatcher().executorService().shutdown();
            } catch (Exception e) {
                MsgUtil.showMsg("直播弹幕连接失败");
                e.printStackTrace();
            }
        });
    }

    @SuppressLint("WrongConstant")
    private void initMediaSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        mediaSession = new MediaSession(this, "BiliClientPlayer");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                super.onPlay();
                runOnUiThread(() -> {
                    if (!isPlaying) {
                        playerResume();
                        updateMediaSessionPlaybackState();
                    }
                });
            }

            @Override
            public void onPause() {
                super.onPause();
                runOnUiThread(() -> {
                    if (isPlaying) {
                        playerPause();
                        updateMediaSessionPlaybackState();
                    }
                });
            }

            @Override
            public void onSkipToNext() {
                super.onSkipToNext();
                runOnUiThread(() -> {
                    if (hasMultiplePages() && currentPageIndex < pagenames.size() - 1) {
                        switchToPage(currentPageIndex + 1);
                    }
                });
            }

            @Override
            public void onSkipToPrevious() {
                super.onSkipToPrevious();
                runOnUiThread(() -> {
                    if (hasMultiplePages() && currentPageIndex > 0) {
                        switchToPage(currentPageIndex - 1);
                    }
                });
            }

            @Override
            public void onSeekTo(long pos) {
                super.onSeekTo(pos);
                runOnUiThread(() -> {
                    seekToPosition(pos);
                    updateMediaSessionPlaybackState();
                });
            }
        });
        mediaSession.setActive(true);
    }

    private void updateMediaSessionMetadata() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || mediaSession == null) {
            return;
        }
        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder();
        if (videoTitle != null) {
            metadataBuilder.putString(MediaMetadata.METADATA_KEY_TITLE, videoTitle);
        }
        if (video_all > 0) {
            metadataBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, video_all);
        }
        mediaSession.setMetadata(metadataBuilder.build());
    }

    private void updateMediaSessionPlaybackState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || mediaSession == null) {
            return;
        }
        int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        long position = isPrepared && ijkPlayer != null ? ijkPlayer.getCurrentPosition() : 0;
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (!hasMultiplePages() || currentPageIndex >= pagenames.size() - 1) {
            actions &= ~PlaybackState.ACTION_SKIP_TO_NEXT;
        }
        if (!hasMultiplePages() || currentPageIndex <= 0) {
            actions &= ~PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        }
        PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
                .setState(state, position, 1.0f)
                .setActions(actions);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void initUI() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        screen_width = displayMetrics.widthPixels;// 获取屏宽
        screen_height = displayMetrics.heightPixels;// 获取屏高

        if (SharedPreferencesUtil.getBoolean("player_ui_showRotateBtn", true))
            btn_rotate.setVisibility(View.VISIBLE);
        else
            btn_rotate.setVisibility(View.GONE);

        screen_round = SharedPreferencesUtil.getBoolean("player_ui_round", false);
        if (screen_round) {
            int padding = (int) (screen_width * 0.03);

            LinearLayout.LayoutParams progressParams = (LinearLayout.LayoutParams) seekbar_progress.getLayoutParams();
            progressParams.leftMargin = padding * 4;
            progressParams.rightMargin = padding * 4;
            seekbar_progress.setLayoutParams(progressParams);

            text_online.setPadding(0, 0, padding * 3, 0);
            text_progress.setPadding(padding * 3, 0, 0, 0);

            bottom_buttons.setPadding(padding, 0, padding, padding);

            right_control.setPadding(0, 0, padding, 0);

            RelativeLayout.LayoutParams danmakuParams = (RelativeLayout.LayoutParams) mDanmakuView.getLayoutParams();
            danmakuParams.setMargins(0, padding * 3, 0, padding * 3);
            mDanmakuView.setLayoutParams(danmakuParams);

            text_subtitle.setMaxWidth((int) (screen_width * 0.65));

            layout_top.setPadding(padding * 7, padding, padding * 7, 0);

            LinearLayout clockLayout = findViewById(R.id.clock_layout);
            clockLayout.setOrientation(LinearLayout.HORIZONTAL);
            RelativeLayout.LayoutParams clockLayoutParams = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clockLayoutParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
            clockLayout.setLayoutParams(clockLayoutParams);

            RelativeLayout.LayoutParams titleParams = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleParams.addRule(RelativeLayout.BELOW, R.id.clock_layout);
            titleParams.topMargin = padding / 2;
            text_title.setLayoutParams(titleParams);
            text_title.setGravity(Gravity.CENTER);

            TextView textClock = findViewById(R.id.clock);
            LinearLayout.LayoutParams textClockParams = (LinearLayout.LayoutParams) textClock.getLayoutParams();
            textClockParams.leftMargin = padding / 2;
            textClockParams.topMargin = padding / 4;
            textClock.setLayoutParams(textClockParams);
        }

        if ((!SharedPreferencesUtil.getBoolean("player_show_online", false)) || aid == 0 || cid == 0)
            text_online.setVisibility(View.GONE);

        layout_top.setOnClickListener(view -> finish());

        // 提前根据设置隐藏按钮，避免在视频加载完成前显示
        if (!SharedPreferencesUtil.getBoolean("player_ui_showPageBtn", true)) {
            btn_page_selector.setVisibility(View.GONE);
        }
        if (!SharedPreferencesUtil.getBoolean("player_ui_showQualityBtn", true) || !isOnlineVideo) {
            btn_quality.setVisibility(View.GONE);
        }

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        if (SharedPreferencesUtil.getBoolean("player_display", Build.VERSION.SDK_INT < 26)) {
            textureView = new TextureView(this);
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
                    Logu.v("surfacetexture", "available");
                    mSurfaceTexture = surfaceTexture;
                    handleRenderSurfaceAvailable("surfaceTextureAvailable", true);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
                    Logu.v("surfacetexture", "sizechanged");
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                    Logu.v("surfacetexture", "destroyed");
                    if (skipSeekOnNextSurfaceCreatedFromBackgroundPlayback) {
                        backgroundPlaybackSurfaceRecreated = true;
                        Logu.d("surface", "surfaceTextureDestroyed during background playback: mark recreate");
                    }
                    if (finishWatching && isPrepared && !isLiveMode) {
                        completionReplayNeedsRebuild = true;
                        Logu.d("surface", "surfaceTextureDestroyed after completion: mark replay rebuild");
                    }
                    mSurfaceTexture = null;
                    if (ijkPlayer != null) {
                        try {
                            ijkPlayer.setSurface(null);
                        } catch (Exception ignore) {
                        }
                    }
                    releaseTextureSurface();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
                }
            });
            layout_video.addView(textureView, params);
        } else {
            surfaceView = new SurfaceView(this);
            surfaceHolder = surfaceView.getHolder();
            try {
                surfaceHolder.addCallback(surfaceCallback);
            } catch (Exception ignore) {
            }
            layout_video.addView(surfaceView, params);
        }

        btn_rotate.setOnClickListener(view -> {
            Logu.v("点击旋转按钮");
            screen_landscape = !screen_landscape;
            if (SharedPreferencesUtil.getBoolean("dev_player_rotate_software", false))
                softwareRotate();
            else
                setRequestedOrientation(screen_landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        });

        findViewById(R.id.button_sound_add).setOnClickListener(view -> changeVolume(true));
        findViewById(R.id.button_sound_cut).setOnClickListener(view -> changeVolume(false));

        btn_menu.setOnClickListener(view -> {
            if (menu_opened) {
                right_second.setVisibility(View.GONE);
                btn_menu.setImageResource(R.mipmap.morehide);
            } else {
                right_second.setVisibility(View.VISIBLE);
                btn_menu.setImageResource(R.mipmap.moreshow);
            }
            menu_opened = !menu_opened;
        });

        layout_card_bg.setOnClickListener(view -> {
            layout_card_bg.setVisibility(View.GONE);
            card_subtitle.setVisibility(View.GONE);
            card_danmaku_send.setVisibility(View.GONE);
            card_page_selector.setVisibility(View.GONE);
            card_quality_selector.setVisibility(View.GONE);
            card_viewpoint_selector.setVisibility(View.GONE);
        });
        btn_danmaku_send.setOnClickListener(view -> {
            layout_card_bg.setVisibility(View.VISIBLE);
            card_danmaku_send.setVisibility(View.VISIBLE);
        });
        findViewById(R.id.danmaku_send).setOnClickListener(view1 -> {
            EditText editText = findViewById(R.id.danmaku_send_edit);
            if (editText.getText().toString().isEmpty()) {
                MsgUtil.showMsg("不能发送空弹幕喵");
            } else {
                layout_card_bg.setVisibility(View.GONE);
                card_danmaku_send.setVisibility(View.GONE);

                CenterThreadPool.run(() -> {
                    try {
                        MsgUtil.showMsg("正在发送~");

                        int result = DanmakuApi.sendVideoDanmakuByAid(cid, editText.getText().toString(), aid,
                                video_now, ToolsUtil.getRgb888(Color.WHITE), 1);

                        if (result == 0) {
                            MsgUtil.showMsg("发送成功喵~");
                            runOnUiThread(() -> {
                                addDanmaku(editText.getText().toString(), Color.WHITE);
                                editText.setText("");
                            });
                        } else
                            MsgUtil.showMsg("发送失败：" + result);
                    } catch (Exception e) {
                        e.printStackTrace();
                        MsgUtil.err(e);
                    }
                });
            }
        });
    }

    private void initSeekbars() {
        seekbar_progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onProgressChanged(SeekBar seekBar, int position, boolean fromUser) {
                runOnUiThread(() -> {
                    if (!isLiveMode)
                        text_progress.setText(StringUtil.toTime(position / 1000) + "/" + progress_str);
                });
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isSeeking = false;
                if (isPrepared && !destroyed) {
                    int seekPos = seekbar_progress.getProgress();
                    // 统一走 seekToPosition：它会同时同步弹幕，并标记显式 seek（避免时间轴钳制影响正常 seek）。
                    seekToPosition(seekPos);
                    autohideReset();
                }
            }
        });

        seekbar_speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int position, boolean fromUser) {
                if (fromUser) {
                    text_newspeed.setText(speed_strs[position]);
                    text_speed.setText(speed_strs[position]);
                    setPlaybackSpeed(speed_values[position]);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 防止速度条隐藏 Timer 叠加
                try {
                    if (speedTimer != null) {
                        speedTimer.cancel();
                        speedTimer = null;
                    }
                } catch (Exception ignore) {
                    speedTimer = null;
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 防止重复启动；并在 Activity 销毁后不再执行
                try {
                    if (speedTimer != null) {
                        speedTimer.cancel();
                        speedTimer = null;
                    }
                } catch (Exception ignore) {
                    speedTimer = null;
                }
                final int session = playerSessionId;
                speedTimer = new Timer();
                TimerTask timerTask = new TimerTask() {
                    @Override
                    public void run() {
                        if (destroyed || resourcesReleased || session != playerSessionId) {
                            try {
                                cancel();
                            } catch (Exception ignore) {
                            }
                            return;
                        }
                        runOnUiThread(() -> {
                            if (layout_speed != null)
                                layout_speed.setVisibility(View.GONE);
                        });
                    }
                };
                speedTimer.schedule(timerTask, 200);
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isPrepared)
            switch (keyCode) {
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    controlVideo();
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    seekToPosition(ijkPlayer.getCurrentPosition() - 10000L);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    seekToPosition(ijkPlayer.getCurrentPosition() + 10000L);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    changeVolume(true);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    changeVolume(false);
                    break;
            }
        return super.onKeyDown(keyCode, event);
    }

    private void seekToPosition(long position) {
        if (ijkPlayer != null && isPrepared) {
            long target = Math.max(0L, position);
            if (video_all > 0) {
                target = Math.min(target, Math.max(0L, video_all - 1L));
            }

            cancelLocalSeekVerify();
            try {
                ijkPlayer.seekTo(target);
            } catch (Exception ignore) {
                return;
            }
            if (hasDanmaku && mDanmakuView != null) {
                try {
                    mDanmakuView.seekTo(target);
                } catch (Exception ignore) {
                }
            }
            // 同步标记：允许弹幕时间轴在短窗口内回退，防止“回跳钳制”影响正常 seek。
            markExplicitSeek(target);
            updateLatestPlayerPosition(target);
            syncProgressUiFromPlayer(target);

            // 仅对本地/缓存视频做 seek 落点校验，在线流不启用（避免多次 seek 触发额外缓冲）。
            if (!isLiveMode && !isOnlineVideo && !isAudioOnlyMode) {
                scheduleLocalSeekVerify(target, "userSeek");
            }
        }
    }

    private long safeGetPlayerPositionMs() {
        try {
            if (ijkPlayer != null && isPrepared) {
                long pos = Math.max(0L, ijkPlayer.getCurrentPosition());
                updateLatestPlayerPosition(pos);
                return pos;
            }
        } catch (Exception ignore) {
        }
        return 0L;
    }

    private void syncProgressUiFromPlayer(long positionMs) {
        int progress = (int) Math.max(0L, positionMs);
        updateLatestPlayerPosition(progress);
        try {
            if (seekbar_progress != null) {
                seekbar_progress.setProgress(progress);
            }
        } catch (Exception ignore) {
        }
        try {
            if (!isLiveMode && text_progress != null) {
                text_progress.setText(StringUtil.toTime(progress / 1000) + "/" + progress_str);
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * 从播放结束态显式重播。
     * <p>
     * 结束态不能复用“普通暂停恢复”逻辑；需要重新进入等待首帧流程，
     * 否则容易出现音频正常但画面黑屏，或 completion 状态下 start/pause 无响应。
     */
    private void replayFromStartAfterCompletion() {
        if (ijkPlayer == null || !isPrepared)
            return;

        boolean shouldRebuildPlayer = completionReplayNeedsRebuild && !isLiveMode && !isAudioOnlyMode;

        if (shouldRebuildPlayer) {
            try {
                if (hasDanmaku && mDanmakuView != null) {
                    mDanmakuView.pause();
                }
            } catch (Exception ignore) {
            }
            pendingForcedSeekMs = 0L;
            showFrameLoading("重新初始化画面", "(｀・ω・´)");
            Logu.w("player", "replay after completion via rebuild session");
            rebuildPlayerSession("replayAfterCompletion", 0L);
            return;
        }

        finishWatching = false;
        pausedByLifecycle = false;
        skipSeekOnNextSurfaceCreatedFromBackgroundPlayback = false;
        progress_history = 0L;
        video_now = 0;
        video_now_last = 0;

        cancelBackgroundSurfaceRefreshTimeout();
        cancelFirstVideoFrameFallback();
        cancelBackgroundSurfaceRestore();
        cancelLocalSeekVerify();

        attachSurfaceIfPossible();
        beginWaitingForFirstVideoFrame("replayAfterCompletion");

        try {
            ijkPlayer.seekTo(0L);
        } catch (Exception e) {
            Logu.w("player", "replay seekTo(0) failed: " + e.getMessage());
        }
        markExplicitSeek(0L);
        if (hasDanmaku && mDanmakuView != null) {
            try {
                mDanmakuView.seekTo(0L);
            } catch (Exception ignore) {
            }
        }
        syncProgressUiFromPlayer(0L);
        playerResume();
        Logu.v("播完重播");
    }

    /**
     * 应用弹幕显示状态。
     * <p>
     * - visible=false：hideAndPauseDrawTask（确保 quitFlag=true，避免后台空转/卡死）
     * - visible=true：showAndResumeDrawTask(position!=null)（确保触发 START/RESUME/UPDATE）
     */
    private void applyDanmakuVisibility(boolean visible, @NonNull String reason) {
        if (destroyed || resourcesReleased)
            return;
        if (mDanmakuView == null)
            return;
        if (!hasDanmaku && !isLiveMode)
            return;

        // 硬恢复 prepare 尚未完成时，不让外部 show 调用把半初始化状态的弹幕层再次拉起。
        // 但 hide 仍然允许，以便用户在恢复期关闭弹幕。
        if (pendingDanmakuRestartAfterPrepare && visible && !"hardRecoverPrepared".equals(reason)) {
            return;
        }

        try {
            if (!visible) {
                mDanmakuView.hideAndPauseDrawTask();
                return;
            }

            final long pos = safeGetPlayerPositionMs();
            // position!=null 才会走 RESUME 分支，避免 show(null) 只“显示不恢复”
            mDanmakuView.showAndResumeDrawTask(pos);

            // 注意：这里不要额外调用 mDanmakuView.seekTo(pos)。
            // showAndResumeDrawTask(pos!=null) 内部已包含 drawTask.seek(pos)，重复 seek 容易导致弹幕重算位置引起“抖动”。

            // 若视频当前不在播放，则保持弹幕暂停（但可见）
            // 注意：onPrepared 初始化阶段 isPlaying 尚未置 true，此时不要强行 pause，避免“开局弹幕不动”。
            if (!isPlaying && !"init".equals(reason)) {
                try {
                    mDanmakuView.pause();
                } catch (Exception ignore) {
                }
            }
        } catch (Exception ignore) {
        }
    }

    private void startDanmakuWatchdog() {
        stopDanmakuWatchdog();
        if (mainHandler == null)
            mainHandler = new Handler(Looper.getMainLooper());

        // 初始化记录，避免第一次就误判
        lastWatchdogVideoPos = -1L;
        lastWatchdogDanmakuTime = -1L;
        danmakuWatchdogStuckCount = 0;
        lastDanmakuWatchdogRecoverUptimeMs = 0L;
        lastDanmakuWatchdogRecoverWasSoft = false;

        danmakuWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (destroyed || resourcesReleased || isLiveMode) {
                    return;
                }
                // loading_info 可见时一般处于缓冲/切换状态，跳过避免误判。
                try {
                    if (loading_info != null && loading_info.getVisibility() == View.VISIBLE) {
                        mainHandler.postDelayed(this, DANMAKU_WATCHDOG_INTERVAL_MS);
                        return;
                    }
                } catch (Exception ignore) {
                }

                if (!hasDanmaku || !isDanmakuVisible || !isPrepared || !isPlaying || mDanmakuView == null) {
                    mainHandler.postDelayed(this, DANMAKU_WATCHDOG_INTERVAL_MS);
                    return;
                }
                // 弹幕未 prepare 时不判断（等待正常 prepare 完成）
                if (!isCurrentDanmakuPrepared()) {
                    mainHandler.postDelayed(this, DANMAKU_WATCHDOG_INTERVAL_MS);
                    return;
                }

                final long videoPos = getLatestPlayerPositionForDanmaku();
                long dmTime;
                try {
                    dmTime = mDanmakuView.getCurrentTime();
                } catch (Exception e) {
                    mainHandler.postDelayed(this, DANMAKU_WATCHDOG_INTERVAL_MS);
                    return;
                }

                boolean videoMoving = lastWatchdogVideoPos >= 0L && videoPos > lastWatchdogVideoPos + 500L;
                boolean danmakuStuck = lastWatchdogDanmakuTime >= 0L && dmTime == lastWatchdogDanmakuTime;

                if (videoMoving && danmakuStuck) {
                    danmakuWatchdogStuckCount++;
                } else {
                    danmakuWatchdogStuckCount = 0;
                }

                // 额外条件：如果 DanmakuView 自己认为是 paused，也视为可恢复状态。
                boolean viewPaused = false;
                try {
                    viewPaused = mDanmakuView.isPaused();
                } catch (Exception ignore) {
                }

                if (viewPaused || danmakuWatchdogStuckCount >= DANMAKU_WATCHDOG_STUCK_THRESHOLD_COUNT) {
                    long now = android.os.SystemClock.uptimeMillis();
                    boolean hardRecover = lastDanmakuWatchdogRecoverWasSoft
                            && now - lastDanmakuWatchdogRecoverUptimeMs <= DANMAKU_WATCHDOG_HARD_RECOVERY_WINDOW_MS;

                    Logu.w("danmaku", "watchdog recover: videoPos=" + videoPos + ", dmTime=" + dmTime
                            + ", paused=" + viewPaused + ", stuckCount=" + danmakuWatchdogStuckCount
                            + ", hard=" + hardRecover);

                    if (hardRecover) {
                        requestDanmakuHardRecovery(videoPos, "watchdogHard");
                        lastDanmakuWatchdogRecoverWasSoft = false;
                    } else {
                        performDanmakuSoftRecovery(videoPos, "watchdogSoft");
                        lastDanmakuWatchdogRecoverWasSoft = true;
                    }
                    lastDanmakuWatchdogRecoverUptimeMs = now;
                    danmakuWatchdogStuckCount = 0;
                }

                lastWatchdogVideoPos = videoPos;
                lastWatchdogDanmakuTime = dmTime;

                mainHandler.postDelayed(this, DANMAKU_WATCHDOG_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(danmakuWatchdogRunnable, DANMAKU_WATCHDOG_INTERVAL_MS);
    }

    private void stopDanmakuWatchdog() {
        try {
            if (mainHandler != null && danmakuWatchdogRunnable != null) {
                mainHandler.removeCallbacks(danmakuWatchdogRunnable);
            }
        } catch (Exception ignore) {
        }
        danmakuWatchdogRunnable = null;
        lastWatchdogVideoPos = -1L;
        lastWatchdogDanmakuTime = -1L;
        danmakuWatchdogStuckCount = 0;
        lastDanmakuWatchdogRecoverUptimeMs = 0L;
        lastDanmakuWatchdogRecoverWasSoft = false;
    }

    private void toggleAudioOnlyMode() {
        boolean oldMode = isAudioOnlyMode;
        isAudioOnlyMode = !isAudioOnlyMode;
        // 不保存状态，仅在当前播放会话中切换

        if (isPrepared && ijkPlayer != null) {
            long currentPosition = 0;
            try {
                currentPosition = ijkPlayer.getCurrentPosition();
            } catch (Exception ignore) {
            }
            final boolean wasPlaying = isPlaying;

            // 记录切换前位置：用于新会话 onPrepared 强制跳回，避免回退到关键帧。
            pendingAudioOnlyToggleSeekMs = Math.max(0L, currentPosition);

            MsgUtil.showMsg(isAudioOnlyMode ? "正在切换到听视频模式..." : "正在切换到普通模式...");
            try {
                if (hasDanmaku && mDanmakuView != null) {
                    mDanmakuView.pause();
                }

                cancelFrameLoadingMinShowGuard();
                loading_info.setVisibility(View.VISIBLE);
                anim_loading.start();
                loading_text0.setText(isAudioOnlyMode ? "切换到听视频模式" : "切换到普通模式");
                isPrepared = false;
                isPlaying = false;

                updateAudioOnlyButton();
                updateAudioOnlyUI();

                // 统一重建播放会话，确保 stopAllPeriodicTasks + release old ijkPlayer + session 防过期回调
                rebuildPlayerSession("toggleAudioOnlyMode", currentPosition);
                autohideReset();
            } catch (Exception e) {
                MsgUtil.showMsg("切换失败，请重试");
                isAudioOnlyMode = oldMode;
                pendingAudioOnlyToggleSeekMs = -1L;
                // 不保存状态
                updateAudioOnlyButton();
                updateAudioOnlyUI();
                cancelFrameLoadingMinShowGuard();
                loading_info.setVisibility(View.GONE);
                anim_loading.stop();
            }
        } else {
            updateAudioOnlyButton();
            updateAudioOnlyUI();
            MsgUtil.showMsg(isAudioOnlyMode ? "已切换到听视频模式" : "已切换到普通模式");
        }
    }

    private void updateAudioOnlyButton() {
        if (btn_audio_only != null) {
            btn_audio_only
                    .setImageResource(isAudioOnlyMode ? R.drawable.icon_audio_only_on : R.drawable.icon_audio_only_off);
        }
    }

    private void updateAudioOnlyUI() {
        runOnUiThread(() -> {
            if (isAudioOnlyMode) {
                // 进入听视频模式
                text_speed.setVisibility(View.GONE);
                btn_debug.setVisibility(View.GONE);
                btn_danmaku.setVisibility(View.GONE);
                layout_video.setVisibility(View.GONE);
                layout_audio_only.setVisibility(View.VISIBLE);
                if (mDanmakuView != null) {
                    mDanmakuView.setVisibility(View.GONE);
                }
                if (text_audio_title != null) {
                    String title = text_title.getText().toString();
                    text_audio_title.setText(title.isEmpty() ? "听视频模式" : title);
                }
            } else {
                // 退出听视频模式
                text_speed.setVisibility(View.VISIBLE);
                updateDebugButtonVisibility();
                btn_danmaku.setVisibility(View.VISIBLE);
                layout_video.setVisibility(View.VISIBLE);
                layout_audio_only.setVisibility(View.GONE);
                if (mDanmakuView != null && isDanmakuVisible) {
                    mDanmakuView.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private boolean eventBusInit = false;

    @Override
    protected void onStart() {
        super.onStart();
        if (eventBusEnabled() && !eventBusInit) {
            EventBus.getDefault().register(this);
            Logu.v("event", "register");
            eventBusInit = true;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    public void onEvent(SnackEvent snackEvent) {
        if (isFinishing())
            return;
        Logu.v("event", "onEvent");

        long currentTime = System.currentTimeMillis();

        int duration;
        if (snackEvent.getDuration() > 0)
            duration = snackEvent.getDuration();
        else if (snackEvent.getDuration() == Snackbar.LENGTH_SHORT)
            duration = 1950;
        else if (snackEvent.getDuration() == Snackbar.LENGTH_INDEFINITE)
            duration = Integer.MAX_VALUE;
        else
            duration = 2750;

        long endTime = snackEvent.getStartTime() + duration;
        if (currentTime >= endTime) {
            EventBus.getDefault().removeStickyEvent(snackEvent);
        } else {
            MsgUtil.toast(snackEvent.getMessage()); // 由于Theme.Black不支持，只能这样用了
        }
    }

    protected boolean eventBusEnabled() {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SNACKBAR_ENABLE, true);
    }

    /**
     * 加载高能进度条数据
     */
    private void loadHighEnergyData() {
        if (!SharedPreferencesUtil.getBoolean("player_high_energy", false)) {
            Logu.d("高能进度条", "功能已禁用");
            return;
        }

        CenterThreadPool.run(() -> {
            try {
                Logu.d("高能进度条", "开始加载数据 aid=" + aid + " cid=" + cid);
                HighEnergyData data = PlayerApi.getHighEnergyData(cid, aid);

                if (data != null && data.hasValidData()) {
                    runOnUiThread(() -> {
                        if (!destroyed && seekbar_progress != null) {
                            seekbar_progress.setHighEnergyData(data.events, data.stepSec);
                            Logu.d("高能进度条", "数据加载成功并设置到进度条");
                        }
                    });
                } else {
                    Logu.w("高能进度条", "未获取到有效数据");
                }
            } catch (Exception e) {
                Logu.e("高能进度条", "加载失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private boolean hasMultiplePages() {
        return pagenames != null && pagenames.size() > 1;
    }

    private void showPageSelectorCard() {
        if (!hasMultiplePages())
            return;

        runOnUiThread(() -> {
            RecyclerView pageSelectorRecycler = findViewById(R.id.page_selector_list);
            PageSelectorAdapter adapter = new PageSelectorAdapter();
            adapter.setData(pagenames, currentPageIndex);
            adapter.setOnItemClickListener(index -> {
                layout_card_bg.setVisibility(View.GONE);
                card_page_selector.setVisibility(View.GONE);
                if (index != currentPageIndex) {
                    switchToPage(index);
                }
            });
            pageSelectorRecycler.setLayoutManager(new CustomLinearManager(this));
            pageSelectorRecycler.setAdapter(adapter);
            layout_card_bg.setVisibility(View.VISIBLE);
            card_page_selector.setVisibility(View.VISIBLE);
        });
    }

    private void switchToPage(int pageIndex) {
        if (!hasMultiplePages() || pageIndex < 0 || pageIndex >= pagenames.size())
            return;
        if (pageIndex == currentPageIndex)
            return;

        currentPageIndex = pageIndex;
        long newCid = cids.get(pageIndex);
        String newTitle = pagenames.get(pageIndex);

        MsgUtil.showMsg("切换到 P" + (pageIndex + 1));

        CenterThreadPool.run(() -> {
            try {
                PlayerData playerData = new PlayerData();
                playerData.aid = aid;
                playerData.cid = newCid;
                playerData.title = newTitle;
                playerData.mid = mid;
                playerData.qn = SharedPreferencesUtil.getInt("play_qn", 16);
                playerData.pagenames = pagenames;
                playerData.cids = cids;
                playerData.currentPageIndex = currentPageIndex;

                if (isOnlineVideo) {
                    PlayerApi.getVideo(playerData, false);
                } else {
                    runOnUiThread(() -> MsgUtil.showMsg("本地视频暂不支持切换分P"));
                    return;
                }

                runOnUiThread(() -> {
                    if (destroyed)
                        return;

                    long currentPosition = 0;
                    // 切分P时通常从 0 开始，但这里保留读取以兼容未来逻辑（并避免异常）
                    try {
                        if (ijkPlayer != null)
                            currentPosition = ijkPlayer.getCurrentPosition();
                    } catch (Exception ignore) {
                    }

                    cid = newCid;
                    video_url = playerData.videoUrl;
                    danmaku_url = playerData.danmakuUrl;
                    text_title.setText(newTitle);
                    videoTitle = newTitle;

                    if (playerData.qnStrList != null && playerData.qnValueList != null) {
                        qnStrList = playerData.qnStrList;
                        qnValueList = playerData.qnValueList;
                        currentQuality = playerData.qn;
                    }

                    loading_info.setVisibility(View.VISIBLE);
                    cancelFrameLoadingMinShowGuard();
                    anim_loading.start();
                    loading_text0.setText("加载P" + (pageIndex + 1));
                    isPrepared = false;
                    isPlaying = false;
                    finishWatching = false;
                    progress_history = 0;
                    subtitles = null;
                    subtitleLinks = null;
                    subtitle_selected = -1;
                    viewPoints = null;
                    viewPointAdapter = null;
                    if (btn_viewpoint != null) {
                        btn_viewpoint.setVisibility(View.GONE);
                    }

                    interactionData = null;
                    currentEdgeId = 0;
                    currentQuestion = null;
                    questionShown = false;
                    if (interactionChoiceLayout != null) {
                        interactionChoiceLayout.setVisibility(View.GONE);
                        interactionChoiceLayout.removeAllViews();
                    }

                    // 统一重建播放会话（包含 stopAllPeriodicTasks + release old player），并从 0 播放新分P
                    rebuildPlayerSession("switchToPage", 0);
                    autohideReset();

	                    final int sessionAfterRebuild = playerSessionId;
	                    layout_control.postDelayed(() -> CenterThreadPool.run(() -> {
	                        if (destroyed || resourcesReleased || sessionAfterRebuild != playerSessionId)
                            return;

	                        runOnUiThread(() -> {
	                            if (destroyed || resourcesReleased || sessionAfterRebuild != playerSessionId)
	                                return;
                            loading_text0.setText("装填弹幕中");
                            loading_text1.setText("(≧∇≦)");
                        });
	                        if (destroyed || resourcesReleased || sessionAfterRebuild != playerSessionId)
	                            return;

                        if (isOnlineVideo) {
                            danmakuFile = new File(getCacheDir(), "danmaku.xml");
                            if (danmakuFile.exists()) {
                                danmakuFile.delete();
                            }
                            downdanmu();
                        }

	                        if (!destroyed && !resourcesReleased && sessionAfterRebuild == playerSessionId
	                                && SharedPreferencesUtil.getBoolean("player_subtitle_autoshow", true)) {
                            downSubtitle(false);
                        }

	                        if (!destroyed && !resourcesReleased && sessionAfterRebuild == playerSessionId && isOnlineVideo && aid > 0 && cid > 0) {
                            loadHighEnergyData();
                        }

	                        if (!destroyed && !resourcesReleased && sessionAfterRebuild == playerSessionId
	                                && isOnlineVideo && aid > 0 && cid > 0 && SharedPreferencesUtil.getBoolean("player_show_viewpoints", false)) {
                            loadViewPoints();
                        }

	                        if (!destroyed && !resourcesReleased && sessionAfterRebuild == playerSessionId && isOnlineVideo && aid > 0 && cid > 0) {
                            loadInteractionVideo();
                        }
                    }), 60);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    MsgUtil.err(e);
                    MsgUtil.showMsg("切换失败");
                });
            }
        });
    }

    private void toggleAutoNext() {
        auto_next_enabled = !auto_next_enabled;
        updateAutoNextButton();
        MsgUtil.showMsg(auto_next_enabled ? "已开启自动连播" : "已关闭自动连播");
    }

    private void updateAutoNextButton() {
        if (btn_auto_next != null) {
            btn_auto_next
                    .setImageResource(auto_next_enabled ? R.drawable.icon_auto_next_on : R.drawable.icon_auto_next_off);
        }
    }

    private void showQualitySelectorCard() {
        if (qnStrList == null || qnValueList == null || qnStrList.length == 0) {
            MsgUtil.showMsg("清晰度列表未加载");
            return;
        }

        runOnUiThread(() -> {
            RecyclerView qualitySelectorRecycler = findViewById(R.id.quality_selector_list);
            QualitySelectorAdapter adapter = new QualitySelectorAdapter();
            adapter.setData(qnStrList, qnValueList, currentQuality);
            adapter.setOnItemClickListener(index -> {
                layout_card_bg.setVisibility(View.GONE);
                card_quality_selector.setVisibility(View.GONE);
                if (index >= 0 && index < qnValueList.length && qnValueList[index] != currentQuality) {
                    switchQuality(qnValueList[index]);
                }
            });
            qualitySelectorRecycler
                    .setLayoutManager(new CustomLinearManager(this, LinearLayoutManager.HORIZONTAL, false));
            qualitySelectorRecycler.setAdapter(adapter);
            layout_card_bg.setVisibility(View.VISIBLE);
            card_quality_selector.setVisibility(View.VISIBLE);
        });
    }

    private void switchQuality(int newQuality) {
        if (!isOnlineVideo || newQuality == currentQuality) {
            return;
        }

        MsgUtil.showMsg("正在切换清晰度...");

        CenterThreadPool.run(() -> {
            try {
                PlayerData playerData = new PlayerData();
                playerData.aid = aid;
                playerData.cid = cid;
                playerData.title = text_title.getText().toString();
                playerData.mid = mid;
                playerData.qn = newQuality;
                playerData.pagenames = pagenames;
                playerData.cids = cids;
                playerData.currentPageIndex = currentPageIndex;

                PlayerApi.getVideo(playerData, false);

                runOnUiThread(() -> {
                    if (destroyed)
                        return;

                    long currentPosition = 0;
                    try {
                        if (ijkPlayer != null)
                            currentPosition = ijkPlayer.getCurrentPosition();
                    } catch (Exception ignore) {
                    }
                    final boolean wasPlaying = isPlaying;

                    video_url = playerData.videoUrl;
                    currentQuality = newQuality;

                    if (playerData.qnStrList != null && playerData.qnValueList != null) {
                        qnStrList = playerData.qnStrList;
                        qnValueList = playerData.qnValueList;
                    }

                    cancelFrameLoadingMinShowGuard();
                    loading_info.setVisibility(View.VISIBLE);
                    anim_loading.start();
                    loading_text0.setText("切换清晰度中");
                    isPrepared = false;
                    isPlaying = false;

                    // 统一重建播放会话，确保 stopAllPeriodicTasks + release old ijkPlayer + session 防过期回调
                    rebuildPlayerSession("switchQuality", currentPosition);
                    autohideReset();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    MsgUtil.err(e);
                    MsgUtil.showMsg("清晰度切换失败");
                });
            }
        });
    }

    private List<ViewPoint> viewPoints;
    private ViewPointAdapter viewPointAdapter;

    private void loadViewPoints() {
        CenterThreadPool.run(() -> {
            try {
                Logu.d("视频分段", "开始加载分段数据 aid=" + aid + " cid=" + cid);
                viewPoints = PlayerApi.getViewPoints(aid, cid);

                if (viewPoints != null && !viewPoints.isEmpty()) {
                    runOnUiThread(() -> {
                        if (!destroyed && btn_viewpoint != null) {
                            btn_viewpoint.setVisibility(View.VISIBLE);
                            btn_viewpoint.setOnClickListener(view -> showViewPointSelectorCard());
                            Logu.d("视频分段", "成功加载 " + viewPoints.size() + " 个分段");
                        }
                    });
                } else {
                    Logu.d("视频分段", "未获取到分段数据");
                }
            } catch (Exception e) {
                Logu.e("视频分段", "加载失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void showViewPointSelectorCard() {
        if (viewPoints == null || viewPoints.isEmpty())
            return;

        runOnUiThread(() -> {
            RecyclerView viewPointRecycler = findViewById(R.id.viewpoint_selector_list);
            if (viewPointAdapter == null) {
                viewPointAdapter = new ViewPointAdapter();
                viewPointAdapter.setData(viewPoints);
                viewPointAdapter.setOnItemClickListener(index -> {
                    layout_card_bg.setVisibility(View.GONE);
                    card_viewpoint_selector.setVisibility(View.GONE);
                    if (index >= 0 && index < viewPoints.size()) {
                        ViewPoint vp = viewPoints.get(index);
                        seekToPosition(vp.from * 1000L);
                        MsgUtil.showMsg("跳转到: " + vp.content);
                    }
                });
                viewPointRecycler.setLayoutManager(new CustomLinearManager(this, LinearLayoutManager.HORIZONTAL, false));
                viewPointRecycler.setAdapter(viewPointAdapter);
            }
            if (ijkPlayer != null && isPrepared) {
                int currentPos = (int) (ijkPlayer.getCurrentPosition() / 1000);
                viewPointAdapter.updateCurrentPosition(currentPos);
            }
            layout_card_bg.setVisibility(View.VISIBLE);
            card_viewpoint_selector.setVisibility(View.VISIBLE);
        });
    }





    // 再往下就是互动视频的天下了

    private void loadInteractionVideo() {
        CenterThreadPool.run(() -> {
            try {
                long graphVersion = PlayerApi.getInteractionGraphVersion(aid, cid);
                if (graphVersion > 0) {
                    interactionGraphVersion = graphVersion;
                    Logu.d("互动视频", "检测到互动视频，graph_version: " + interactionGraphVersion + ", cid: " + cid);
                    
                    runOnUiThread(() -> {
                        questionShown = false;
                        currentQuestion = null;
                        if (interactionChoiceLayout != null) {
                            interactionChoiceLayout.setVisibility(View.GONE);
                            interactionChoiceLayout.removeAllViews();
                        }
                    });
                    
                    long edgeId = 0;
                    if (initialEdgeId > 0) {
                        edgeId = initialEdgeId;
                        initialEdgeId = 0;
                    } else if (interactionData != null && currentEdgeId > 0) {
                        edgeId = currentEdgeId;
                    }
                    
                    interactionData = InteractionVideoApi.getEdgeInfo(aid, null, interactionGraphVersion, edgeId);
                    if (interactionData != null) {
                        currentEdgeId = interactionData.edgeId;
                        Logu.d("互动视频", "成功加载互动视频数据，edge_id: " + currentEdgeId);
                        runOnUiThread(() -> updateDebugButtonVisibility());
                    }
                } else {
                    interactionData = null;
                    currentEdgeId = 0;
                    runOnUiThread(() -> {
                        questionShown = false;
                        currentQuestion = null;
                        updateDebugButtonVisibility();
                    });
                }
            } catch (Exception e) {
                Logu.e("互动视频", "加载失败: " + e.getMessage());
                e.printStackTrace();
                interactionData = null;
                currentEdgeId = 0;
                runOnUiThread(() -> {
                    questionShown = false;
                    currentQuestion = null;
                    updateDebugButtonVisibility();
                });
            }
        });
    }

    private void updateDebugButtonVisibility() {
        if (btn_debug == null) return;
        boolean debugEnabled = SharedPreferencesUtil.getBoolean("player_interaction_debug", false);
        if (debugEnabled && interactionData != null && interactionData.hiddenVars != null && !interactionData.hiddenVars.isEmpty() && !isLiveMode && !isAudioOnlyMode) {
            btn_debug.setVisibility(layout_top.getVisibility());
        } else {
            btn_debug.setVisibility(View.GONE);
        }
    }

    private void checkEndInteractionQuestions() {
        if (interactionData == null || interactionData.edges == null || 
            interactionData.edges.questions == null || questionShown) {
            return;
        }

        for (InteractionVideoData.InteractionQuestion question : interactionData.edges.questions) {
            if (question.type == 0) {
                if (question.choices != null && !question.choices.isEmpty()) {
                    for (InteractionVideoData.InteractionChoice choice : question.choices) {
                        if (choice.isHidden == 1) continue;
                        
                        if (choice.condition != null && !choice.condition.isEmpty()) {
                            if (!evaluateCondition(choice.condition)) {
                                continue;
                            }
                        }
                        
                        handleChoiceSelection(choice);
                        break;
                    }
                }
                continue;
            }
            showInteractionQuestion(question);
        }
    }

    private void showInteractionQuestion(InteractionVideoData.InteractionQuestion question) {
        if (questionShown || question.choices == null || question.choices.isEmpty()) {
            return;
        }

        runOnUiThread(() -> {
            questionShown = true;
            currentQuestion = question;

            if (question.pauseVideo == 1 && isPlaying) {
                ijkPlayer.pause();
                isPlaying = false;
                btn_control.setImageResource(R.drawable.btn_player_play);
            }

            if (interactionChoiceLayout == null) {
                createInteractionChoiceLayout();
            }

            interactionChoiceLayout.removeAllViews();
            
            for (InteractionVideoData.InteractionChoice choice : question.choices) {
                if (choice.isHidden == 1) continue;
                
                if (choice.condition != null && !choice.condition.isEmpty()) {
                    if (!evaluateCondition(choice.condition)) {
                        continue;
                    }
                }

                TextView choiceView = createChoiceView(choice);
                interactionChoiceLayout.addView(choiceView);
            }

            if (interactionChoiceLayout.getChildCount() > 0) {
                interactionChoiceLayout.setVisibility(View.VISIBLE);
            }
        });
    }

    private TextView createChoiceView(InteractionVideoData.InteractionChoice choice) {
        TextView choiceView = (TextView) LayoutInflater.from(this).inflate(R.layout.cell_interaction_choice, null);
        choiceView.setText(choice.option);
        choiceView.setOnClickListener(v -> handleChoiceSelection(choice));
        return choiceView;
    }

    private void createInteractionChoiceLayout() {
        RelativeLayout rootLayout = findViewById(R.id.root_layout);
        interactionChoiceLayout = new LinearLayout(this);
        interactionChoiceLayout.setOrientation(LinearLayout.VERTICAL);
        interactionChoiceLayout.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        );
        params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        params.setMargins(0, 0, 0, 100);
        
        interactionChoiceLayout.setLayoutParams(params);
        interactionChoiceLayout.setVisibility(View.GONE);
        rootLayout.addView(interactionChoiceLayout);
    }

    private boolean evaluateCondition(String condition) {
        if (interactionData == null || interactionData.hiddenVars == null) {
            return true;
        }
        
        try {
            for (InteractionVideoData.InteractionHiddenVar var : interactionData.hiddenVars) {
                condition = condition.replace(var.idV2, String.valueOf(var.value));
            }
            
            return evaluateExpression(condition);
        } catch (Exception e) {
            Logu.e("互动视频", "条件判断失败: " + e.getMessage());
            return true;
        }
    }

    private boolean evaluateExpression(String expr) {
        try {
            expr = expr.trim();
            if (expr.contains(">=")) {
                String[] parts = expr.split(">=");
                long left = Long.parseLong(parts[0].trim());
                long right = Long.parseLong(parts[1].trim());
                return left >= right;
            } else if (expr.contains("<=")) {
                String[] parts = expr.split("<=");
                long left = Long.parseLong(parts[0].trim());
                long right = Long.parseLong(parts[1].trim());
                return left <= right;
            } else if (expr.contains(">")) {
                String[] parts = expr.split(">");
                long left = Long.parseLong(parts[0].trim());
                long right = Long.parseLong(parts[1].trim());
                return left > right;
            } else if (expr.contains("<")) {
                String[] parts = expr.split("<");
                long left = Long.parseLong(parts[0].trim());
                long right = Long.parseLong(parts[1].trim());
                return left < right;
            } else if (expr.contains("==")) {
                String[] parts = expr.split("==");
                long left = Long.parseLong(parts[0].trim());
                long right = Long.parseLong(parts[1].trim());
                return left == right;
            } else if (expr.contains("!=")) {
                String[] parts = expr.split("!=");
                long left = Long.parseLong(parts[0].trim());
                long right = Long.parseLong(parts[1].trim());
                return left != right;
            }
        } catch (Exception e) {
            Logu.e("互动视频", "表达式计算失败: " + expr);
        }
        return true;
    }

    private void handleChoiceSelection(InteractionVideoData.InteractionChoice choice) {
        hideInteractionChoices();

        if (choice.nativeAction != null && !choice.nativeAction.isEmpty()) {
            executeNativeAction(choice.nativeAction);
        }

        CenterThreadPool.run(() -> {
            try {
                long targetEdgeId = choice.id;
                InteractionVideoData newData = InteractionVideoApi.getEdgeInfo(aid, null, interactionGraphVersion, targetEdgeId);
                
                if (newData == null) {
                    runOnUiThread(() -> MsgUtil.showMsg("获取互动视频数据失败"));
                    return;
                }

                interactionData = newData;
                currentEdgeId = newData.edgeId;
                
                long targetCid = choice.cid;
                if (targetCid > 0 && targetCid != cid) {
                    jumpToInteractionPage(targetCid, newData);
                } else {
                    resumePlaybackIfPaused();
                }
            } catch (Exception e) {
                Logu.e("互动视频", "处理选择失败: " + e.getMessage());
                runOnUiThread(() -> MsgUtil.showMsg("处理选择失败: " + e.getMessage()));
            }
        });
    }

    private void hideInteractionChoices() {
        runOnUiThread(() -> {
            if (interactionChoiceLayout != null) {
                interactionChoiceLayout.setVisibility(View.GONE);
            }
            questionShown = false;
            currentQuestion = null;
        });
    }

    private void jumpToInteractionPage(long targetCid, InteractionVideoData newData) {
        CenterThreadPool.run(() -> {
            try {
                PlayerData playerData = new PlayerData();
                playerData.aid = aid;
                playerData.cid = targetCid;
                playerData.title = newData.title;
                playerData.mid = mid;
                playerData.qn = getTargetQuality();
                
                if (pagenames != null && cids != null) {
                    playerData.pagenames = pagenames;
                    playerData.cids = cids;
                    int newPageIndex = cids.indexOf(targetCid);
                    if (newPageIndex >= 0) {
                        playerData.currentPageIndex = newPageIndex;
                        currentPageIndex = newPageIndex;
                    }
                }
                
                PlayerApi.getVideo(playerData, false);
                
                runOnUiThread(() -> {
                    if (destroyed)
                        return;

                    // 统一重建播放会话（先 stopAllPeriodicTasks + release old ijkPlayer），避免 Timer 叠加/释放后访问崩溃。
                    // 保持旧行为：互动跳转时重置弹幕层（release 后重新 findViewById）。
                    if (mDanmakuView != null) {
                        try {
                            mDanmakuView.release();
                        } catch (Exception ignore) {
                        }
                        mDanmakuView = null;
                    }
                    mDanmakuView = findViewById(R.id.sv_danmaku);
                    
                    cid = targetCid;
                    video_url = playerData.videoUrl;
                    danmaku_url = playerData.danmakuUrl;
                    isOnlineVideo = video_url != null && video_url.contains("http");
                    hasDanmaku = danmaku_url != null && !danmaku_url.equals("");
                    text_title.setText(newData.title);
                    videoTitle = newData.title;
                    currentEdgeId = newData.edgeId;
                    
                    if (playerData.qnStrList != null && playerData.qnValueList != null) {
                        qnStrList = playerData.qnStrList;
                        qnValueList = playerData.qnValueList;
                        currentQuality = playerData.qn;
                    }
                    
                    cancelFrameLoadingMinShowGuard();
                    loading_info.setVisibility(View.VISIBLE);
                    anim_loading.start();
                    loading_text0.setText("加载互动分P");
                    isPrepared = false;
                    isPlaying = false;
                    finishWatching = false;
                    progress_history = 0;
                    subtitles = null;
                    subtitleLinks = null;
                    subtitle_selected = -1;
                    viewPoints = null;
                    viewPointAdapter = null;
                    if (btn_viewpoint != null) {
                        btn_viewpoint.setVisibility(View.GONE);
                    }
                    
                    interactionData = newData;
                    currentQuestion = null;
                    questionShown = false;
                    if (interactionChoiceLayout != null) {
                        interactionChoiceLayout.setVisibility(View.GONE);
                        interactionChoiceLayout.removeAllViews();
                    }

                    rebuildPlayerSession("jumpToInteractionPage", 0);
                    autohideReset();
	                    
	                    final int sessionAfterRebuild = playerSessionId;
	                    layout_control.postDelayed(() -> CenterThreadPool.run(() -> {
	                        if (destroyed || resourcesReleased || sessionAfterRebuild != playerSessionId)
                            return;
	                        
	                        runOnUiThread(() -> {
	                            if (destroyed || resourcesReleased || sessionAfterRebuild != playerSessionId)
	                                return;
                            loading_text0.setText("装填弹幕中");
                            loading_text1.setText("(≧∇≦)");
                        });
	                        if (destroyed || resourcesReleased || sessionAfterRebuild != playerSessionId)
	                            return;
                        
                        if (isOnlineVideo) {
                            danmakuFile = new File(getCacheDir(), "danmaku.xml");
                            if (danmakuFile.exists()) {
                                danmakuFile.delete();
                            }
                            downdanmu();
                        }
                        
	                        if (!destroyed && !resourcesReleased && sessionAfterRebuild == playerSessionId
	                                && SharedPreferencesUtil.getBoolean("player_subtitle_autoshow", true)) {
                            downSubtitle(false);
                        }
                        
	                        if (!destroyed && !resourcesReleased && sessionAfterRebuild == playerSessionId && isOnlineVideo && aid > 0 && cid > 0) {
                            loadHighEnergyData();
                        }
                        
	                        if (!destroyed && !resourcesReleased && sessionAfterRebuild == playerSessionId
	                                && isOnlineVideo && aid > 0 && cid > 0 && SharedPreferencesUtil.getBoolean("player_show_viewpoints", false)) {
                            loadViewPoints();
                        }
                    }), 60);
                });
            } catch (Exception e) {
                Logu.e("互动视频", "跳转失败: " + e.getMessage());
                runOnUiThread(() -> MsgUtil.showMsg("跳转失败: " + e.getMessage()));
            }
        });
    }

    private void resumePlaybackIfPaused() {
        runOnUiThread(() -> {
            if (currentQuestion != null && currentQuestion.pauseVideo == 1 && !isPlaying) {
                ijkPlayer.start();
                isPlaying = true;
                btn_control.setImageResource(R.drawable.btn_player_pause);
            }
        });
    }

    private int getTargetQuality() {
        int defaultQn = SharedPreferencesUtil.getInt("play_qn", 16);
        if (qnValueList == null || qnValueList.length == 0) {
            return currentQuality > 0 ? currentQuality : defaultQn;
        }
        
        for (int qn : qnValueList) {
            if (qn == currentQuality) {
                return currentQuality;
            }
        }
        
        return currentQuality > 0 ? currentQuality : defaultQn;
    }

    private void executeNativeAction(String nativeAction) {
        if (interactionData == null || interactionData.hiddenVars == null || nativeAction == null || nativeAction.isEmpty()) {
            return;
        }

        String[] actions = nativeAction.split(";");
        for (String action : actions) {
            action = action.trim();
            if (action.isEmpty()) continue;

            try {
                if (action.contains("=")) {
                    String[] parts = action.split("=");
                    if (parts.length == 2) {
                        String varId = parts[0].trim();
                        String valueExpr = parts[1].trim();
                        
                        long value = evaluateValueExpression(valueExpr);
                        
                        for (InteractionVideoData.InteractionHiddenVar var : interactionData.hiddenVars) {
                            if (var.idV2.equals(varId)) {
                                var.value = value;
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Logu.e("互动视频", "执行动作失败: " + action);
            }
        }
    }

    private long evaluateValueExpression(String expr) {
        try {
            expr = expr.trim();
            if (expr.contains("+")) {
                String[] parts = expr.split("\\+");
                long sum = 0;
                for (String part : parts) {
                    sum += evaluateValueExpression(part.trim());
                }
                return sum;
            } else if (expr.contains("-")) {
                String[] parts = expr.split("-");
                long result = evaluateValueExpression(parts[0].trim());
                for (int i = 1; i < parts.length; i++) {
                    result -= evaluateValueExpression(parts[i].trim());
                }
                return result;
            } else {
                if (interactionData != null && interactionData.hiddenVars != null) {
                    for (InteractionVideoData.InteractionHiddenVar var : interactionData.hiddenVars) {
                        if (expr.equals(var.idV2)) {
                            return var.value;
                        }
                    }
                }
                if (expr.contains(".")) {
                    return (long) Double.parseDouble(expr);
                } else {
                    return Long.parseLong(expr);
                }
            }
        } catch (Exception e) {
            Logu.e("互动视频", "值表达式计算失败: " + expr + ", 错误: " + e.getMessage());
            return 0;
        }
    }

    private void showInteractionDebugDialog() {
        if (interactionData == null || interactionData.hiddenVars == null || interactionData.hiddenVars.isEmpty()) {
            MsgUtil.showMsg("当前没有互动视频变量");
            return;
        }

        InteractionDebugActivity.setInteractionData(interactionData);
        Intent intent = new Intent(this, InteractionDebugActivity.class);
        startActivity(intent);
    }

    @Override
    public void finish() {
        if (isPlaying)
            playerPause();
        // 尽量保证返回 progress，避免跳转页无法上报进度而停留。
        // 注意：此处不强依赖 ijkPlayer（退出/重建/异常 release 时可能为 null）。
        int progress = 0;
        boolean got = false;
        try {
            if (ijkPlayer != null && isPrepared) {
                progress = (int) Math.max(0L, ijkPlayer.getCurrentPosition());
                got = true;
            }
        } catch (Exception ignore) {
            got = false;
        }
        if (!got) {
            try {
                progress = (int) Math.max(0L, latestPlayerPositionMs);
            } catch (Exception ignore) {
                progress = 0;
            }
            if (progress <= 0 && seekbar_progress != null) {
                try {
                    progress = Math.max(0, seekbar_progress.getProgress());
                } catch (Exception ignore) {
                    progress = 0;
                }
            }
        }
        Intent result = new Intent();
        result.putExtra("progress", progress);
        Logu.d("进度回传", String.valueOf(progress));
        setResult(RESULT_OK, result);
        super.finish();
    }
}
