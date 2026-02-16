package com.RobinNotBad.BiliClient.activity.video.local;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.video.DownloadAdapter;
import com.RobinNotBad.BiliClient.listener.OnItemClickListener;
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener;
import com.RobinNotBad.BiliClient.model.DownloadSection;
import com.RobinNotBad.BiliClient.service.DownloadService;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

public class DownloadListActivity extends RefreshListActivity {
    public static WeakReference<DownloadListActivity> weakRef;
    DownloadAdapter adapter;
    Timer timer;
    boolean emptyTipShown;
    boolean firstRefresh = true;
    boolean created;
    ArrayList<DownloadSection> sections;
    private float lastPercent = -1;
    private String lastState = null;
    private long lastDownloadingId = -1;

    private static final long CONFIRM_WINDOW_MS = 3000;
    private long stopConfirmId = -1;
    private long stopConfirmTimestamp = 0;
    private long deleteConfirmId = -1;
    private long deleteConfirmTimestamp = 0;

    /**
     * 在某些操作（如删除）后，空列表提示会覆盖“删除成功”等提示。
     * 这里使用一次性抑制标记，并在触发刷新时立即“消费”该标记，避免因 UI 线程调度延迟而失效。
     */
    private volatile boolean suppressEmptyTipOnce = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setPageName("下载列表");
        setRefreshing(false);
        weakRef = new WeakReference<>(this);

        CenterThreadPool.run(() -> {
            created = true;
            refreshList(false);

            timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (adapter == null || !created || isDestroyed())
                        return;
                    if (DownloadService.section != null) {
                        boolean needUpdate = false;
                        if (lastDownloadingId != DownloadService.section.id) {
                            lastDownloadingId = DownloadService.section.id;
                            needUpdate = true;
                        }
                        if (lastPercent != DownloadService.percent) {
                            lastPercent = DownloadService.percent;
                            needUpdate = true;
                        }
                        if (lastState == null || !lastState.equals(DownloadService.state)) {
                            lastState = DownloadService.state;
                            needUpdate = true;
                        }
                        if (needUpdate) {
                            final int pos = findDownloadingPosition();
                            if (pos >= 0) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        adapter.notifyItemChanged(pos);
                                    }
                                });
                            }
                        }
                    }
                }
            }, 300, 500);
        });

    }

    private int findDownloadingPosition() {
        if (sections == null || DownloadService.section == null)
            return -1;
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).id == DownloadService.section.id) {
                return i;
            }
        }
        return -1;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void refreshList(boolean fromOutside) {
        if (this.isDestroyed() || !created)
            return;
        Log.d("debug", "刷新下载列表");

        sections = DownloadService.getAll();

        if (sections == null || sections.isEmpty()) {
            if (!emptyTipShown) {
                final boolean suppress = suppressEmptyTipOnce;
                suppressEmptyTipOnce = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!suppress) {
                            MsgUtil.showMsg("下载列表为空");
                        }
                        showEmptyView();
                    }
                });
                emptyTipShown = true;
            }
        } else {
            for (DownloadSection s : sections) {
                Log.d("debug-download", s.name_short);
            }

            if (emptyTipShown) {
                emptyTipShown = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hideEmptyView();
                    }
                });
            }

            if (firstRefresh) {
                adapter = new DownloadAdapter(DownloadListActivity.this, sections);
                adapter.setOnClickListener(new OnItemClickListener() {
                    @Override
                    public void onItemClick(int position) {
                        Log.d("debug-download", "click:" + position);
                        if (sections == null || position < 0 || position >= sections.size())
                            return;

                        DownloadSection section = sections.get(position);
                        if (section == null)
                            return;

                        // 1) 下载中的任务：双击停止（在 3 秒内第二次点击才停止）
                        if ("downloading".equals(section.state)) {
                            // 如果服务并不在跑，说明状态可能已不同步：直接允许重新下载
                            if (!DownloadService.started || DownloadService.section == null || DownloadService.section.id != section.id) {
                                DownloadService.setState(section.id, "none");
                                refreshList(false);
                                DownloadService.start(section.id);
                                return;
                            }

                            long now = System.currentTimeMillis();
                            if (stopConfirmId == section.id && (now - stopConfirmTimestamp) <= CONFIRM_WINDOW_MS) {
                                stopConfirmId = -1;
                                stopConfirmTimestamp = 0;
                                // 触发“方案A”清理逻辑，并显示更明确提示
                                DownloadService.requestStopByUser("已停止下载");
                                stopService(new Intent(DownloadListActivity.this, DownloadService.class));
                                MsgUtil.showMsg("正在停止下载...");
                            } else {
                                stopConfirmId = section.id;
                                stopConfirmTimestamp = now;
                                MsgUtil.showMsg("再次点击将停止下载");
                            }
                            return;
                        }

                        // 2) 错误任务：点击重试前先回退为 none
                        if ("error".equals(section.state)) {
                            DownloadService.setState(section.id, "none");
                        }

                        // 3) 其他状态：点击开始下载
                        DownloadService.start(section.id);
                    }
                });

                adapter.setOnLongClickListener(new OnItemLongClickListener() {
                    @Override
                    public void onItemLongClick(int position) {
                        try {
                            if (sections == null || position < 0 || position >= sections.size())
                                return;

                            final DownloadSection delete = sections.get(position);
                            if (delete == null)
                                return;

                            // 下载中的任务不允许长按删除（避免误操作）
                            if ("downloading".equals(delete.state) && DownloadService.started) {
                                MsgUtil.showMsg("下载中，请双击停止");
                                return;
                            }

                            long now = System.currentTimeMillis();
                            if (deleteConfirmId == delete.id && (now - deleteConfirmTimestamp) <= CONFIRM_WINDOW_MS) {
                                deleteConfirmId = -1;
                                deleteConfirmTimestamp = 0;

                                CenterThreadPool.run(() -> {
                                    try {
                                        File folder = delete.getPath();
                                        if (folder != null && folder.exists()) {
                                            FileUtil.deleteFolder(folder);
                                        }
                                        DownloadService.deleteSection(delete.id);
                                        // 删除后下一次刷新会进入空列表，先抑制空列表提示避免覆盖“删除成功”
                                        suppressEmptyTipOnce = true;
                                        MsgUtil.showMsg("删除成功");
                                        refreshList(false);
                                    } catch (Throwable t) {
                                        MsgUtil.err(t);
                                    }
                                });
                            } else {
                                deleteConfirmId = delete.id;
                                deleteConfirmTimestamp = now;
                                MsgUtil.showMsg("再次长按将删除该任务");
                            }
                        } catch (Throwable t) {
                            MsgUtil.err(t);
                        }
                    }
                });

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setAdapter(adapter);
                    }
                });
                firstRefresh = false;
            } else {
                adapter.downloadList = sections;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        adapter.notifyDataSetChanged();
                    }
                });
                Log.d("debug-adapter", String.valueOf(adapter.getItemCount()));
            }
        }

    }

    @Override
    protected void onDestroy() {
        if (timer != null)
            timer.cancel();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        weakRef = null;
        super.onDestroy();
    }
}
