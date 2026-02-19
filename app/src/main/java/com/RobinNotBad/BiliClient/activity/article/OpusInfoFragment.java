package com.RobinNotBad.BiliClient.activity.article;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.adapter.article.OpusContentAdapter;
import com.RobinNotBad.BiliClient.api.HistoryApi;
import com.RobinNotBad.BiliClient.model.Opus;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;

public class OpusInfoFragment extends Fragment {
    long oid;
    RecyclerView recyclerView;
    Opus opus;

    Runnable onFinishLoad;

    public OpusInfoFragment() {
    }

    public static OpusInfoFragment newInstance(long oid) {
        OpusInfoFragment fragment = new OpusInfoFragment();
        Bundle args = new Bundle();
        args.putLong("oid", oid);
        fragment.setArguments(args);
        return fragment;
    }

    public void setOnFinishLoad(Runnable onFinishLoad) {
        this.onFinishLoad = onFinishLoad;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            oid = getArguments().getLong("oid");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_simple_list, container, false);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.recyclerView);

        if (SharedPreferencesUtil.getBoolean("ui_landscape", false)) {
            WindowManager windowManager = (WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE);
            Display display = windowManager.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            if (Build.VERSION.SDK_INT >= 17) display.getRealMetrics(metrics);
            else display.getMetrics(metrics);
            int paddings = metrics.widthPixels / 6;
            recyclerView.setPadding(paddings, 0, paddings, 0);
        }

        TerminalContext.getInstance().getOpusById(oid)
                .observe(getViewLifecycleOwner(), (result) -> result.onSuccess((opus) -> {
                    if (!isAdded()) return;
                    this.opus = opus;
                    OpusContentAdapter adapter = new OpusContentAdapter(requireActivity(), opus);
                    requireActivity().runOnUiThread(() -> {
                        recyclerView.setLayoutManager(new CustomLinearManager(requireContext()));
                        recyclerView.setAdapter(adapter);

                        recyclerView.setFocusable(true);
                        recyclerView.setFocusableInTouchMode(true);
                        recyclerView.requestFocus();
                    });
                    
                    // 历史上报：
                    // 历史策略：
                    // - 普通专栏（listId <= 0）：走 history/report (type=5)。
                    // - 专栏合集（listId > 0）：不走 history/report（避免产生 article-hybrid 错位记录），仅触达 viewinfo
                    //   以模拟网页端链路，观察是否能前置。
                    CenterThreadPool.run(() -> {
                        try {
                            long listId = opus.listId;
                            long cvid = opus.id > 0 ? opus.id : oid;
                            // 安全校验：若 listId 与 cvid 相同，基本可判定并非合集 rlid，回退普通专栏上报。
                            boolean useListReport = listId > 0 && listId != cvid;
                            if (useListReport) {
                                com.RobinNotBad.BiliClient.util.Logu.i("OpusHistory",
                                        "Article-list mode: touch viewinfo only (no history/report). listId=" + listId
                                                + ", cvid=" + cvid
                                                + ", opus.id=" + opus.id
                                                + ", oid=" + oid);
                                HistoryApi.touchArticleViewInfoForDebug(cvid);
                            } else {
                                String fallbackReason = listId <= 0 ? "listId<=0" : "listId==cvid(" + listId + ")";
                                com.RobinNotBad.BiliClient.util.Logu.i("OpusHistory",
                                        "Article mode: history/report(type=5) for oid=" + oid
                                                + ", reason=" + fallbackReason
                                                + ", listId=" + listId
                                                + ", opus.id=" + opus.id
                                                + ", commentId=" + opus.commentId);
                                HistoryApi.reportArticleListHistory(cvid);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }).onFailure(MsgUtil::err));

    }
}
