package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResult;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

import okhttp3.Response;
import okhttp3.ResponseBody;

public class HistoryApi {

    public static final int ARTICLE_HISTORY_TYPE = 5;
    public static final int ARTICLE_LIST_HISTORY_TYPE = 5;

    /**
     * 上传历史记录
     *
     * @param aid      视频aid
     * @param cid      分集cid
     * @param progress 观看进度，单位为s
     * @throws IOException
     */
    public static void reportHistory(long aid, long cid, long progress) throws IOException {
        String url = "https://api.bilibili.com/x/v2/history/report";
        String per = "aid=" + aid + "&cid=" + cid
                + "&progress=" + (progress >= 0 ? progress : "")
                + "&platform=pc"
                + "&csrf=" + SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "");
        // StrictMode/资源泄漏修复：必须关闭 Response，避免连接池泄漏
        try (Response ignored = NetWorkUtil.post(url, per, NetWorkUtil.webHeaders)) {
            // no-op
        }
    }

    /**
     * 上传专栏历史记录
     *
     * @param aid  专栏cvid
     * @param type 内容类型，3=article，5=article-list
     * @throws IOException
     */
    public static void reportArticleHistory(long aid, int type) throws IOException {
        String url = "https://api.bilibili.com/x/v2/history/report";
        String per = "aid=" + aid + "&type=" + type
                + "&csrf=" + SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "");
        postHistoryReport(url, per);
    }

    /**
     * 上传 article-list 历史记录。
     */
    public static void reportArticleListHistory(long aid) throws IOException {
        reportArticleHistory(aid, ARTICLE_LIST_HISTORY_TYPE);
    }

    /**
     * 上传 article-list 历史记录（携带当前专栏ID用于回显）。
     *
     * @param listId 合集ID
     * @param cvid   当前专栏ID（用于 history.cid）
     */
    public static void reportArticleListHistory(long listId, long cvid) throws IOException {
        String url = "https://api.bilibili.com/x/v2/history/report";
        String per = "aid=" + listId
                + (cvid > 0 ? "&cid=" + cvid : "")
                + "&type=" + ARTICLE_LIST_HISTORY_TYPE
                + "&csrf=" + SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "");
        postHistoryReport(url, per);
    }

    /**
     * 调试用途：模拟网页端访问 viewinfo，以观察是否触发 view_at 前置。
     */
    public static void touchArticleViewInfoForDebug(long cvid) {
        if (cvid <= 0) return;
        try {
            String url = "https://api.bilibili.com/x/article/viewinfo?"
                    + "id=" + cvid
                    + "&gaia_source=main_web"
                    + "&web_location=333.976"
                    + "&mobi_app=pc"
                    + "&from=web";
            String signedUrl = ConfInfoApi.signWBI(url);

            JSONObject result = NetWorkUtil.getJson(signedUrl);
            if (result == null) {
                Logu.e("HistoryReport", "viewinfo touch: empty response, cvid=" + cvid);
                return;
            }

            int code = result.optInt("code", -1);
            String message = result.optString("message", "");
            JSONObject data = result.optJSONObject("data");
            boolean hasVoucher = data != null && data.has("v_voucher");

            Logu.i("HistoryReport", "viewinfo touch: code=" + code
                    + ", message=" + message
                    + ", hasVoucher=" + hasVoucher
                    + ", cvid=" + cvid);
        } catch (Exception e) {
            Logu.e("HistoryReport", "viewinfo touch failed: cvid=" + cvid + ", err=" + e.getMessage());
        }
    }

    private static void postHistoryReport(String url, String form) throws IOException {
        try (Response response = NetWorkUtil.post(url, form, NetWorkUtil.webHeaders)) {
            if (response == null) return;
            ResponseBody responseBody = response.body();
            if (responseBody == null) return;

            String body = responseBody.string();
            try {
                JSONObject json = new JSONObject(body);
                int code = json.optInt("code", -1);
                String message = json.optString("message", "");
                if (code != 0) {
                    Logu.e("HistoryReport", "history/report failed: code=" + code + ", message=" + message + ", form=" + form
                            + ", body=" + body);
                } else {
                    Logu.i("HistoryReport", "history/report ok: code=" + code + ", message=" + message + ", form=" + form
                            + ", body=" + body);
                    dumpArticleHistorySnapshotForDebug(form);
                }
            } catch (Exception ignored) {
                Logu.e("HistoryReport", "history/report invalid response: " + body);
            }
        }
    }

    /**
     * 调试辅助：上报成功后，立即拉取 article 历史快照，帮助判断服务端是否真正写入/前置。
     */
    private static void dumpArticleHistorySnapshotForDebug(String triggerForm) {
        TargetHistory target = TargetHistory.fromForm(triggerForm);
        if (target == null) {
            Logu.i("HistoryReportSnapshot", "after form=" + triggerForm + ", cannot parse target");
            return;
        }

        long max = 0;
        long viewAt = 0;
        String business = "";
        int page = 0;
        long ts = System.currentTimeMillis();

        try {
            while (page < 3) {
                page++;
                StringBuilder url = new StringBuilder("https://api.bilibili.com/x/web-interface/history/cursor?type=article&ps=30");
                url.append("&view_at=").append(viewAt).append("&max=").append(max).append("&_ts=").append(ts);
                if (!business.isEmpty()) {
                    url.append("&business=").append(business);
                }

                JSONObject result = NetWorkUtil.getJson(url.toString());
                JSONObject data = result.optJSONObject("data");
                if (data == null) {
                    Logu.i("HistoryReportSnapshot", "after form=" + triggerForm + ", page=" + page + ", data is null, result=" + result);
                    return;
                }

                JSONArray list = data.optJSONArray("list");
                if (list == null || list.length() == 0) {
                    Logu.i("HistoryReportSnapshot", "after form=" + triggerForm + ", page=" + page + ", article list empty");
                    return;
                }

                boolean found = false;
                String matchType = "";
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item == null) continue;

                    JSONObject history = item.optJSONObject("history");
                    String title = item.optString("title", "");
                    long itemViewAt = item.optLong("view_at", 0);
                    int progress = item.optInt("progress", 0);

                    String itemBusiness = history == null ? "" : history.optString("business", "");
                    long oid = history == null ? 0 : history.optLong("oid", 0);
                    long cid = history == null ? 0 : history.optLong("cid", 0);

                    matchType = target.matchType(itemBusiness, oid, cid);
                    if (!matchType.isEmpty()) {
                        Logu.i("HistoryReportSnapshot", "after form=" + triggerForm
                                + ", page=" + page
                                + ", idx=" + (i + 1)
                                + ", title=" + title
                                + ", matchType=" + matchType
                                + ", business=" + itemBusiness
                                + ", oid=" + oid
                                + ", cid=" + cid
                                + ", view_at=" + itemViewAt
                                + ", progress=" + progress);
                        found = true;
                        break;
                    }
                }

                if (found) return;

                JSONObject cursor = data.optJSONObject("cursor");
                if (cursor == null) {
                    Logu.i("HistoryReportSnapshot", "after form=" + triggerForm + ", page=" + page + ", cursor missing");
                    return;
                }
                max = cursor.optLong("max", 0);
                viewAt = cursor.optLong("view_at", 0);
                business = cursor.optString("business", "");

                JSONObject first = list.optJSONObject(0);
                String firstTitle = first == null ? "" : first.optString("title", "");
                long firstViewAt = first == null ? 0 : first.optLong("view_at", 0);
                Logu.i("HistoryReportSnapshot", "after form=" + triggerForm + ", page=" + page
                        + ", not found, firstTitle=" + firstTitle + ", firstViewAt=" + firstViewAt);

                if (max == 0 && viewAt == 0) {
                    Logu.i("HistoryReportSnapshot", "after form=" + triggerForm + ", page=" + page + ", cursor ended");
                    return;
                }
            }

            Logu.i("HistoryReportSnapshot", "after form=" + triggerForm + ", not found within 3 pages");
        } catch (Exception e) {
            Logu.e("HistoryReportSnapshot", "snapshot query failed after form=" + triggerForm + ", err=" + e.getMessage());
        }
    }

    private static final class TargetHistory {
        private final long aid;
        private final long cid;
        private final String type;

        private TargetHistory(long aid, long cid, String type) {
            this.aid = aid;
            this.cid = cid;
            this.type = type;
        }

        private String matchType(String business, long oid, long cidVal) {
            if (cid > 0) {
                if ("article-list".equals(business) && oid == aid && cidVal == cid) return "article-list";
                if ("article".equals(business) && oid == aid && cidVal == cid) return "article-hybrid";
                if ("article".equals(business) && oid == cid) return "article-cvid";
                return "";
            }
            return ("article".equals(business) && oid == aid) ? "article-cvid" : "";
        }

        private static TargetHistory fromForm(String form) {
            if (form == null || form.isEmpty()) return null;
            long aid = 0;
            long cid = 0;
            String type = "";
            String[] parts = form.split("&");
            for (String part : parts) {
                int idx = part.indexOf('=');
                if (idx <= 0 || idx >= part.length() - 1) continue;
                String key = part.substring(0, idx);
                String val = part.substring(idx + 1);
                try {
                    switch (key) {
                        case "aid":
                            aid = Long.parseLong(val);
                            break;
                        case "cid":
                            cid = Long.parseLong(val);
                            break;
                        case "type":
                            type = val;
                            break;
                        default:
                            break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (aid <= 0) return null;
            return new TargetHistory(aid, cid, type);
        }
    }

    /**
     * 获取历史记录（支持视频和专栏）
     *
     * @param lastResult 上一次获取返回的ApiResult，如果是第一次就传入新对象
     * @param videoList  已有的视频列表
     * @param type       历史记录类型，"all"表示全部，"archive"表示视频，"article"表示专栏
     * @return 新的ApiResult，包含了返回码、文本信息以及翻页所需的offset
     * @throws IOException
     * @throws JSONException
     */
    public static ApiResult getHistory(ApiResult lastResult, List<VideoCard> videoList, String type) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/history/cursor?type=" + type
                + "&ps=20"
                + "&view_at=" + lastResult.timestamp
                + "&max=" + lastResult.offset;
        JSONObject result = NetWorkUtil.getJson(url);
        ApiResult apiResult = new ApiResult(result);
        if (!result.isNull("data")) {
            JSONObject data = result.getJSONObject("data");
            JSONArray list = data.getJSONArray("list");
            for (int i = 0; i < list.length(); i++) {
                JSONObject videoCard = list.getJSONObject(i);
                String title = videoCard.getString("title");
                String cover = videoCard.optString("cover", "");
                String upName = videoCard.getString("author_name");
                int progress = videoCard.getInt("progress");

                JSONObject history = videoCard.getJSONObject("history");
                long oid = history.getLong("oid");
                long aid = oid;
                long cid = history.optLong("cid", 0);
                long kid = videoCard.optLong("kid", 0);
                String bvid = history.optString("bvid", "");
                String business = history.optString("business", "archive");

                String viewStr;
                String contentType;

                // 兼容专栏异常形态（如 article-list），优先按专栏处理。
                boolean isArticleLike = "article".equals(business)
                        || "article-list".equals(business)
                        || ("专栏".equals(videoCard.optString("badge", ""))
                        && videoCard.optInt("videos", 1) == 0);

                if (isArticleLike) {
                    contentType = "article";
                    // 优先使用 cid（如果存在）作为实际 cvid，避免 article-list 被固定到合集首篇。
                    if (cid > 0) {
                        aid = cid;
                    }
                    viewStr = progress > 0 ? "已阅读" : "还没看过";
                    // 专栏的封面可能在covers数组中
                    if (cover.isEmpty() && videoCard.has("covers") && !videoCard.isNull("covers")) {
                        JSONArray covers = videoCard.getJSONArray("covers");
                        if (covers.length() > 0) {
                            cover = covers.getString(0);
                        }
                    }
                } else {
                    contentType = "video";
                    // progress: 观看进度（秒）。B站接口在“已看完”等情况下可能返回 -1。
                    // 兼容处理：-1 -> 已看完；0 -> 还没看过；>0 -> 看到xx:xx
                    if (progress < 0) viewStr = "已看完";
                    else if (progress == 0) viewStr = "还没看过";
                    else viewStr = "看到" + StringUtil.toTime(progress);
                }

                VideoCard card = new VideoCard(title, upName, viewStr, cover, aid, bvid, contentType);
                card.kid = kid;
                card.historyBusiness = business;
                card.historyOid = oid;
                card.historyCid = cid;
                // 占位符特征：专栏类记录 oid 与 cvid 不一致（oid 常为合集或旧占位值）
                card.historyPlaceholder = isArticleLike && cid > 0 && oid != cid;
                videoList.add(card);
            }
            if (list.length() == 0) apiResult.isBottom = true;

            JSONObject cursor = data.getJSONObject("cursor");
            apiResult.business = cursor.optString("business");
            apiResult.offset = cursor.optLong("max");
            apiResult.timestamp = cursor.optLong("view_at");
        }
        return apiResult;
    }

    /**
     * 获取历史记录（默认获取全部类型）
     *
     * @param lastResult 上一次获取返回的ApiResult，如果是第一次就传入新对象
     * @param videoList  已有的视频列表
     * @return 新的ApiResult，包含了返回码、文本信息以及翻页所需的offset
     * @throws IOException
     * @throws JSONException
     */
    public static ApiResult getHistory(ApiResult lastResult, List<VideoCard> videoList) throws IOException, JSONException {
        return getHistory(lastResult, videoList, "all");
    }

    /**
     * 删除单条历史记录（基于事件ID kid）
     *
     * @param kid 历史事件ID
     */
    public static void deleteHistory(long kid) {
        if (kid <= 0) return;
        try {
            String url = "https://api.bilibili.com/x/v2/history/delete";
            String per = "kid=" + kid
                    + "&csrf=" + SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "");
            // StrictMode/资源泄漏修复：必须关闭 Response，避免连接池泄漏
            try (Response ignored = NetWorkUtil.post(url, per, NetWorkUtil.webHeaders)) {
                // no-op
            }
        } catch (Exception ignored) {
        }
    }

}
