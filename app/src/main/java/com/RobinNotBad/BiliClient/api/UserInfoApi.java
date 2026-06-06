package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ArticleCard;
import com.RobinNotBad.BiliClient.model.LiveRoom;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.DmImgParamUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

//用户信息API

public class UserInfoApi {

    private static final String TRACE_TAG = "user-info-risk";

    private static ArrayList<String> getSpaceHeaders(long mid, boolean dynamicReferer) {
        ArrayList<String> headers = NetWorkUtil.getWebHeadersSnapshot();
        removeHeader(headers, "Sec-Ch-Ua");
        removeHeader(headers, "Sec-Ch-Ua-Platform");
        removeHeader(headers, "Sec-Ch-Ua-Mobile");
        setHeader(headers, "User-Agent", DynamicApi.SPACE_DYNAMIC_USER_AGENT);
        setHeader(headers, "Origin", "https://space.bilibili.com");
        setHeader(headers, "Referer", "https://space.bilibili.com/" + mid + (dynamicReferer ? "/dynamic" : ""));
        CookiesApi.applyPiliPlusAccountHeaders(headers);
        return headers;
    }

    private static void removeHeader(List<String> headers, String key) {
        for (int i = headers.size() - 2; i >= 0; i -= 2) {
            if (key.equalsIgnoreCase(headers.get(i))) {
                headers.remove(i + 1);
                headers.remove(i);
            }
        }
    }

    private static void setHeader(List<String> headers, String key, String value) {
        for (int i = 0; i + 1 < headers.size(); i += 2) {
            if (key.equalsIgnoreCase(headers.get(i))) {
                headers.set(i + 1, value);
                return;
            }
        }
        headers.add(key);
        headers.add(value);
    }

    private static boolean isRiskCode(int code) {
        return code == -352 || code == -412 || code == -403 || code == 421;
    }

    private static boolean shouldRetryForRisk(JSONObject all) {
        return all.optBoolean("retry_failed", false) || isRiskCode(all.optInt("code", -1));
    }

    private static JSONObject getSpaceJsonWithRiskRetry(String url, long mid, boolean dynamicReferer, String label) throws IOException {
        JSONObject all = NetWorkUtil.getJson(url, getSpaceHeaders(mid, dynamicReferer));
        if (!shouldRetryForRisk(all)) return all;

        Logu.w(TRACE_TAG, label + " risk response, code=" + all.optInt("code", Integer.MIN_VALUE)
                + ", httpCode=" + all.optInt("http_code", -1)
                + ", bodyKind=" + all.optString("body_kind", "")
                + ", try activate space dynamic risk, mid=" + mid);
        try {
            CookiesApi.ensurePiliPlusBaseCookies();
            CookiesApi.ensureSpaceDynamicRiskActive(mid, true);
        } catch (Exception e) {
            Logu.w(TRACE_TAG, label + " risk activate failed, mid=" + mid + ", err=" + e.getMessage());
        }
        return NetWorkUtil.getJson(url, getSpaceHeaders(mid, dynamicReferer));
    }

    private static void throwIfRequestFailed(JSONObject all, String label) throws IOException {
        if (all.optBoolean("retry_failed", false)) {
            throw new IOException(label + "失败: " + describeFailure(all));
        }
        int code = all.optInt("code", 0);
        if (code != 0 && !all.has("data")) {
            throw new IOException(label + "失败: API错误 (code=" + code + "): " + all.optString("message", "未知错误"));
        }
    }

    private static String describeFailure(JSONObject all) {
        StringBuilder builder = new StringBuilder(all.optString("message", "网络请求失败"));
        int code = all.optInt("code", Integer.MIN_VALUE);
        int httpCode = all.optInt("http_code", -1);
        String bodyKind = all.optString("body_kind", "");
        if (code != Integer.MIN_VALUE) builder.append(" code=").append(code);
        if (httpCode > 0) builder.append(" http=").append(httpCode);
        if (!bodyKind.isEmpty()) builder.append(" body=").append(bodyKind);
        return builder.toString();
    }

    public static UserInfo getUserInfo(long mid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/card?mid=" + mid;
        JSONObject all = getSpaceJsonWithRiskRetry(url, mid, true, "user-card");
        throwIfRequestFailed(all, "用户信息");
        if (all.has("data") && !all.isNull("data")) {
            String notice = "";
            try {
                JSONObject notice_all = getSpaceJsonWithRiskRetry("https://api.bilibili.com/x/space/notice?mid=" + mid, mid, true, "user-notice");
                if (notice_all.has("data") && !notice_all.isNull("data")) {
                    notice = notice_all.getString("data");
                } else if (notice_all.optBoolean("retry_failed", false) || notice_all.optInt("code", 0) != 0) {
                    Logu.w(TRACE_TAG, "notice fallback empty, mid=" + mid + ", reason=" + describeFailure(notice_all));
                }
            } catch (Exception e) {
                Logu.w(TRACE_TAG, "notice fallback empty, mid=" + mid + ", err=" + e.getMessage());
            }
            JSONObject data = all.getJSONObject("data");
            boolean followed = data.getBoolean("following");
            int fans = data.getInt("follower");

            JSONObject card = data.getJSONObject("card");
            String name = card.getString("name");
            String avatar = card.getString("face");
            String sign = card.getString("sign");
            JSONObject levelInfo = card.getJSONObject("level_info");
            int level = levelInfo.getInt("current_level");
            int attention = card.getInt("attention");

            JSONObject official_data = card.getJSONObject("Official");
            int official = official_data.getInt("role");
            String officialDesc = official_data.getString("title");

            String sys_notice = "";
            LiveRoom liveroom = null;
            boolean is_follow_display = false;
            try {
                JSONObject spaceInfo = getUserSpaceInfo(mid);
                if (spaceInfo != null) {
                    if (!spaceInfo.isNull("sys_notice")) {
                        sys_notice = spaceInfo.getJSONObject("sys_notice").optString("content");
                        if (sys_notice == null) sys_notice = "";
                        else sys_notice = sys_notice.replace("请点此查看纪念账号相关说明", "");
                    }
                    if (!spaceInfo.isNull("live_room")) {
                        JSONObject live_room = spaceInfo.getJSONObject("live_room");
                        if (live_room.getInt("roomStatus") == 1 && live_room.getInt("liveStatus") == 1) {
                            liveroom = new LiveRoom();
                            liveroom.title = "直播中：" + live_room.getString("title");
                            liveroom.user_cover = live_room.getString("cover");
                            liveroom.roomid = live_room.getLong("roomid");
                        }
                    }
                    if (!spaceInfo.isNull("contract")) {
                        JSONObject contract = spaceInfo.getJSONObject("contract");
                        is_follow_display = contract.optBoolean("is_follow_display", false);
                    }
                }
            } catch (Exception ignore) {
            }

            JSONObject vip = card.getJSONObject("vip");
            if (vip.getInt("status") == 1) {
                UserInfo result = new UserInfo(mid, name, avatar, sign, fans, attention, level, followed, notice, official, officialDesc, vip.getInt("role"), sys_notice, liveroom, card.getInt("is_senior_member"));
                result.vip_nickname_color = vip.optString("nickname_color", "");
                result.is_follow_display = is_follow_display;
                return result;
            } else {
                UserInfo result = new UserInfo(mid, name, avatar, sign, fans, attention, level, followed, notice, official, officialDesc, sys_notice, liveroom, card.getInt("is_senior_member"));
                result.is_follow_display = is_follow_display;
                return result;
            }
        } else return null;
    }

    public static JSONObject getUserSpaceInfo(long mid) throws JSONException, IOException {
        String url = "https://api.bilibili.com/x/space/wbi/acc/info?";
        url += "mid=" + mid + "&token=&platform=web&web_location=1550101";
        JSONObject all = getSpaceJsonWithRiskRetry(
                ConfInfoApi.signWBI(DmImgParamUtil.getDmImgParamsUrl(url)),
                mid,
                true,
                "space-info"
        );
        throwIfRequestFailed(all, "空间信息");
        if (all.has("data") && !all.isNull("data")) {
            return all.getJSONObject("data");
        }
        return null;
    }

    public static UserInfo getCurrentUserInfo() throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/space/myinfo";
        JSONObject all = NetWorkUtil.getJson(url);
        if (all.has("data") && !all.isNull("data")) {
            JSONObject data = all.getJSONObject("data");
            long mid = data.getLong("mid");
            String name = data.getString("name");
            String avatar = data.getString("face");
            String sign = data.getString("sign");
            int fans = data.getInt("follower");
            int level = data.getInt("level");

            JSONObject official_data = data.getJSONObject("official");
            int official = official_data.getInt("role");
            String officialDesc = official_data.getString("desc");

            JSONObject level_exp = data.getJSONObject("level_exp");
            long current_exp = level_exp.getLong("current_exp");
            long next_exp = level_exp.getLong("next_exp");

            return new UserInfo(mid, name, avatar, sign, fans, 0, level, false, "", official, officialDesc, current_exp, next_exp, data.getInt("is_senior_member"));
        } else return new UserInfo(0, "加载失败", "", "", 0, 0, 0, false, "", 0, "", 0);
    }

    public static int getCurrentUserCoin() {
        try {
            String url = "https://account.bilibili.com/site/getCoin";
            JSONObject all = NetWorkUtil.getJson(url);
            if (all.has("data") && !all.isNull("data")) {
                JSONObject data = all.getJSONObject("data");
                return data.has("money") ? data.getInt("money") : 0;
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return 0;
    }


    public static int getUserVideos(long mid, int page, String searchKeyword, List<VideoCard> videoList) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/space/wbi/arc/search?";
        url += "keyword=" + searchKeyword + "&mid=" + mid + "&order_avoided=true&order=pubdate&pn=" + page
                + "&ps=40&tid=0&platform=web&web_location=1550101";
        JSONObject all = NetWorkUtil.getJson(
                ConfInfoApi.signWBI(DmImgParamUtil.getDmImgParamsUrl(url)),
                getSpaceHeaders(mid, false)
        );
        if (all.has("data") && !all.isNull("data")) {
            JSONObject data = all.optJSONObject("data");
            if (data == null) return -1;

            JSONObject list = data.optJSONObject("list");
            JSONArray vlist = list != null ? list.optJSONArray("vlist") : data.optJSONArray("vlist");
            if (vlist == null || vlist.length() == 0) return 1;

            for (int i = 0; i < vlist.length(); i++) {
                JSONObject card = vlist.optJSONObject(i);
                if (card == null) continue;

                String cover = card.optString("pic", "");
                long play = card.optLong("play", 0);
                String playStr = StringUtil.toWan(play) + "观看";
                long aid = card.optLong("aid", 0);
                String bvid = card.optString("bvid", "");
                String upName = card.optString("author", "");
                String title = card.optString("title", "");

                if (aid == 0 && bvid.isEmpty()) continue;
                videoList.add(new VideoCard(title, upName, playStr, cover, aid, bvid));
            }
            return videoList.isEmpty() ? 1 : 0;
        } else return -1;
    }


    public static int getUserArticles(long mid, int page, List<ArticleCard> articleList) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/space/wbi/article?";
        url += "mid=" + mid + "&order_avoided=true&order=pubdate&pn=" + page
                + "&ps=30&tid=0";
        JSONObject all = NetWorkUtil.getJson(ConfInfoApi.signWBI(url), NetWorkUtil.webHeaders);
        if (all.has("data") && !all.isNull("data")) {
            JSONObject data = all.getJSONObject("data");
            if (data.has("articles")) {
                JSONArray list = data.getJSONArray("articles");
                if (list.length() == 0) return 1;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject card = list.getJSONObject(i);

                    ArticleCard articleCard = new ArticleCard();
                    articleCard.id = card.getLong("id");
                    articleCard.title = card.getString("title");
                    JSONObject stats = card.getJSONObject("stats");
                    articleCard.view = StringUtil.toWan(stats.getInt("view")) + "阅读";
                    articleCard.cover = card.getString("banner_url");
                    JSONObject author = card.getJSONObject("author");
                    articleCard.upName = author.getString("name");
                    articleList.add(articleCard);
                }
                return 0;
            } else return 1;
        } else return -1;
    }

    public static int followUser(long mid, boolean isFollow) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/relation/modify?";
        String arg = "fid=" + mid + "&csrf=" + NetWorkUtil.getInfoFromCookie("bili_jct", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
        if (isFollow) arg += "&act=1"; //关注
        else arg += "&act=2"; //取消关注
        JSONObject all = new JSONObject(Objects.requireNonNull(NetWorkUtil.post(url, arg, NetWorkUtil.webHeaders).body()).string());
        return all.getInt("code");
    }

    public static void exitLogin() {
        try {
            String url = "https://passport.bilibili.com/login/exit/v2";
            NetWorkUtil.get(url, NetWorkUtil.webHeaders);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static int addContract(long upMid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v1/contract/add_contract";
        String csrf = NetWorkUtil.getInfoFromCookie("bili_jct", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
        String arg = "aid=&up_mid=" + upMid + "&source=4&scene=105&platform=web&mobi_app=pc&csrf=" + csrf;
        JSONObject all = new JSONObject(Objects.requireNonNull(NetWorkUtil.post(url, arg, NetWorkUtil.webHeaders).body()).string());
        return all.getInt("code");
    }

    public static JSONObject getMedalWall(long targetId) throws IOException, JSONException {
        String url = "https://api.live.bilibili.com/xlive/web-ucenter/user/MedalWall?target_id=" + targetId;
        JSONObject all = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders);
        if (all.has("data") && !all.isNull("data")) {
            return all.getJSONObject("data");
        }
        return null;
    }
}
