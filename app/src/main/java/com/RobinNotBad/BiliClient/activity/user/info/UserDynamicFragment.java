package com.RobinNotBad.BiliClient.activity.user.info;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.activity.base.RefreshListFragment;
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicHolder;
import com.RobinNotBad.BiliClient.adapter.dynamic.UserDynamicAdapter;
import com.RobinNotBad.BiliClient.api.CookiesApi;
import com.RobinNotBad.BiliClient.api.DynamicApi;
import com.RobinNotBad.BiliClient.api.UserInfoApi;
import com.RobinNotBad.BiliClient.model.Dynamic;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

//用户动态
//2023-09-30
//2024-05-03

public class UserDynamicFragment extends RefreshListFragment {

    private static final String TRACE_TAG = "user-dynamic-trace";

    private long mid;
    private ArrayList<Dynamic> dynamicList;
    private UserDynamicAdapter adapter;
    private long offset = 0;

    private static class FirstPageLoadResult {
        final ArrayList<Dynamic> list;
        final long nextOffset;

        FirstPageLoadResult(ArrayList<Dynamic> list, long nextOffset) {
            this.list = list;
            this.nextOffset = nextOffset;
        }
    }

    public UserDynamicFragment() {

    }

    public static UserDynamicFragment newInstance(long mid) {
        UserDynamicFragment fragment = new UserDynamicFragment();
        Bundle args = new Bundle();
        args.putLong("mid", mid);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mid = getArguments().getLong("mid");
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dynamicList = new ArrayList<>();
        offset = 0;
        bottom = false;
        setOnLoadMoreListener(page -> continueLoading());

        loadFirstPage();
    }

    private void loadFirstPage() {
        final long totalStart = System.currentTimeMillis();
        Logu.w(TRACE_TAG, "fragment first page start, mid=" + mid);

        CenterThreadPool.run(() -> {
            try {
                try {
                    long preflightStart = System.currentTimeMillis();
                    CookiesApi.ensurePiliPlusBaseCookies();
                    CookiesApi.ensureSpaceDynamicRiskActive(mid, false);
                    Logu.w(TRACE_TAG, "fragment space preflight finished, mid=" + mid
                            + ", costMs=" + (System.currentTimeMillis() - preflightStart));
                } catch (Throwable e) {
                    Logu.w(TRACE_TAG, "fragment space preflight failed, mid=" + mid + ", err=" + e.getMessage());
                }

                FirstPageLoadResult result;
                try {
                    long start = System.currentTimeMillis();
                    ArrayList<Dynamic> firstPageList = new ArrayList<>();
                    long nextOffset = DynamicApi.getDynamicList(firstPageList, 0, mid, null);
                    Logu.w(TRACE_TAG, "fragment dynamic first page finished, mid=" + mid
                            + ", resultSize=" + firstPageList.size()
                            + ", nextOffset=" + nextOffset
                            + ", costMs=" + (System.currentTimeMillis() - start));
                    result = new FirstPageLoadResult(firstPageList, nextOffset);
                } catch (Exception e) {
                    Logu.w(TRACE_TAG, "fragment dynamic first page failed, mid=" + mid + ", err=" + e);
                    throw new IOException("动态加载失败: " + e, e);
                }

                long userInfoStart = System.currentTimeMillis();
                UserInfo userInfo = UserInfoApi.getUserInfo(mid);
                Logu.w(TRACE_TAG, "fragment user info finished after dynamic, mid=" + mid
                        + ", success=" + (userInfo != null)
                        + ", costMs=" + (System.currentTimeMillis() - userInfoStart));
                if (userInfo == null) {
                    runOnUiThread(() -> {
                        setRefreshing(false);
                        MsgUtil.showMsg("用户不存在");
                        requireActivity().finish();
                    });
                    return;
                }

                if (isAdded()) {
                    runOnUiThread(() -> {
                        if (!isAdded()) return;
                        dynamicList.clear();
                        dynamicList.addAll(result.list);
                        offset = result.nextOffset;
                        bottom = (offset == -1);

                        adapter = new UserDynamicAdapter(requireContext(), dynamicList, userInfo);
                        setAdapter(adapter);
                        setRefreshing(false);

                        Logu.w(TRACE_TAG, "fragment first page bind finished, mid=" + mid
                                + ", resultSize=" + dynamicList.size()
                                + ", nextOffset=" + offset
                                + ", totalCostMs=" + (System.currentTimeMillis() - totalStart));
                    });
                }
            } catch (Exception e) {
                Logu.w(TRACE_TAG, "fragment first page failed, mid=" + mid
                        + ", costMs=" + (System.currentTimeMillis() - totalStart)
                        + ", err=" + e.getMessage());
                loadFail(e);
            }
        });
    }

    @SuppressLint("NotifyDataSetChanged")
    private void continueLoading() {
        CenterThreadPool.run(() -> {
            try {
                long start = System.currentTimeMillis();
                List<Dynamic> list = new ArrayList<>();
                offset = DynamicApi.getDynamicList(list, offset, mid, null);
                runOnUiThread(() -> {
                    dynamicList.addAll(list);
                    adapter.notifyItemRangeInserted(dynamicList.size() - list.size() + 1, list.size());
                });
                bottom = (offset == -1);
                setRefreshing(false);
                Logu.w(TRACE_TAG, "fragment load more finished, mid=" + mid
                        + ", appendSize=" + list.size()
                        + ", nextOffset=" + offset
                        + ", costMs=" + (System.currentTimeMillis() - start));
            } catch (Exception e) {
                loadFail(e);
            }
        });
    }

    public void onDynamicRemove(int position) {
        try {
            DynamicHolder.removeDynamicFromList(dynamicList, position, adapter);
        } catch (Throwable ignored) {
        }
    }
}
