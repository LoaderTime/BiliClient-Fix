package com.RobinNotBad.BiliClient.activity.video;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.DownloadActivity;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.HistoryApi;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONException;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class JumpToPlayerActivity extends BaseActivity {
    String title;
    TextView textView;

    PlayerData playerData;

    int download;

    private final AtomicBoolean exitRequested = new AtomicBoolean(false);
    private final AtomicBoolean playerResultHandled = new AtomicBoolean(false);
    private static final String PLAYER_RESULT_FALLBACK_TOAST = "播放器已退出，但未返回播放进度";

    final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new ActivityResultCallback<>() {
        @Override
        public void onActivityResult(ActivityResult o) {
            if (!playerResultHandled.compareAndSet(false, true)) {
                Logu.w("进度回调", "重复收到播放器返回结果，忽略后续结果");
                return;
            }

            int code = o.getResultCode();
            Intent result = o.getData();
            Logu.d("进度回调", "onActivityResult");
            if (code == RESULT_OK && result != null && result.hasExtra("progress")) {
                int progress = result.getIntExtra("progress", 0);
                Logu.d("进度回调", String.valueOf(progress));

                reportHistoryAsync(progress);
                requestExitOnce();
            } else {
                Logu.w("进度回调", "播放器返回异常：code=" + code + ", result=" + result);
                MsgUtil.toast(PLAYER_RESULT_FALLBACK_TOAST);
                setClickExit("播放器已退出，但未返回播放进度\n点击返回");
            }
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player_jump);

        textView = findViewById(R.id.text_title);

        Intent intent = getIntent();
        Log.e("debug-哔哩终端-跳转页", "已接收数据");

        playerData = (PlayerData) intent.getParcelableExtra("data");

        title = playerData.title;

        download = intent.getIntExtra("download", 0);

        playerData.qn = playerData.qn != -1 ? playerData.qn : SharedPreferencesUtil.getInt("play_qn", 16);

        requestVideo();
    }

    @SuppressLint("SetTextI18n")
    private void requestVideo() {
        CenterThreadPool.run(() -> {

            try {
                if (playerData.isBangumi()) PlayerApi.getBangumi(playerData);
                else PlayerApi.getVideo(playerData, download != 0);

                Logu.d("history", String.valueOf(playerData.progress));
                // ActivityResultLauncher.launch 必须在主线程调用；否则可能导致回调丢失/界面卡住。
                runOnUiThread(this::jump);
            } catch (IOException e) {
                setClickExit("网络错误！\n请检查你的网络连接是否正常");
            } catch (JSONException e) {
                setClickExit("视频获取失败！\n可能的原因：\n1.本视频仅大会员可播放\n2.视频获取接口失效\n\n清除应用数据也许可以解决" + e.getMessage());
                e.printStackTrace();
            } catch (ActivityNotFoundException e) {
                setClickExit("跳转失败！\n请安装对应的播放器\n或在设置中选择正确的播放器\n或将哔哩终端和播放器同时更新到最新版本");
                e.printStackTrace();
            }
        });
    }

    private void jump() {
        if (isDestroyed()) return;
        if (download == 0) {
            Intent intent = PlayerApi.jumpToPlayer(playerData);
            launcher.launch(intent);
            setClickExit("等待退出播放后上报进度\n（点击跳过）");
        } else {
            Intent intent = new Intent();
            intent.setClass(this, DownloadActivity.class);
            intent.putExtra("type", download);
            intent.putExtra("link", playerData.videoUrl);
            intent.putExtra("danmaku", playerData.danmakuUrl);
            intent.putExtra("title", title);
            intent.putExtra("cover", getIntent().getStringExtra("cover"));
            if (download == 2)
                intent.putExtra("parent_title", getIntent().getStringExtra("parent_title"));
            startActivity(intent);
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        requestExitOnce();
    }

    private void setClickExit(String reason) {
        runOnUiThread(() -> {
            textView.setText(reason);
            textView.setOnClickListener((view) -> requestExitOnce());
        });
    }

    private void requestExitOnce() {
        if (!exitRequested.compareAndSet(false, true)) {
            return;
        }
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                finish();
            }
        });
    }

    private void reportHistoryAsync(int progress) {
        CenterThreadPool.run(() -> {
            if (playerData == null || playerData.mid == 0 || playerData.aid == 0) {
                Logu.w("进度上报", "缺少必要参数，跳过上报");
                return;
            }
            try {
                HistoryApi.reportHistory(playerData.aid, playerData.cid, progress / 1000);
            } catch (IOException e) {
                Logu.e("进度上报", "上报失败：" + e.getMessage());
            } catch (Exception e) {
                Logu.e("进度上报", "上报异常：" + e.getMessage());
            }
        });
    }
}