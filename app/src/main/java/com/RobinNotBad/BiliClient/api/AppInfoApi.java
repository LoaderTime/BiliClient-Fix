package com.RobinNotBad.BiliClient.api;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.BuildConfig;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.update.UpdateInfoActivity;
import com.RobinNotBad.BiliClient.model.Announcement;
import com.RobinNotBad.BiliClient.model.ApiResult;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AppInfoApi {

    private static final String UPDATE_ASSET_NAME = "app-debug.apk";
    private static final String GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/LoaderTime/BiliClient-Fix/releases/latest";
    private static final String JSDELIVR_VERSION_JSON_URL = "https://cdn.jsdelivr.net/gh/LoaderTime/BiliClient-Fix/version.json";

    private static final Pattern RELEASE_TAG_PATTERN = Pattern.compile("^release-(\\d+)(?:\\.(\\d+))?$", Pattern.CASE_INSENSITIVE);

    private static class TagVersion {
        final int major;
        final int minor;

        TagVersion(int major, int minor) {
            this.major = major;
            this.minor = minor;
        }
    }

    private static class UpdateInfo {
        String tagName;
        String title;
        String body;
        long publishedAtEpochSeconds;
        String downloadUrl;
    }

    public static void check(Context context) {
        // 讲真这免责声明没啥卵用，写这个也就是半开玩笑的，难道免责声明能挡住律师函吗
        // 而且"fuck_uncle"过分了嗷，咱做第三方软件的真不能这么干……
        if (!SharedPreferencesUtil.getBoolean("disclaimer_shown", false)) {
            MsgUtil.showDialog("免责声明", "使用前请先阅读：\n" + context.getString(R.string.about_to_uncle), 3);
            SharedPreferencesUtil.putBoolean("disclaimer_shown", true);
        }

        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NIGHT_REMINDER_ENABLE, true)) {
            Calendar calendar = Calendar.getInstance();
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            if (hour >= 23 || hour <= 3) {
                MsgUtil.showDialog("温馨提醒", "夜深了，要注意休息呐~", 3);
            }
        }

        try {
            int version = BiliTerminal.getVersion();
            int curr = ConfInfoApi.getDateCurr();

            checkAnnouncement();

            int last_ver = SharedPreferencesUtil.getInt("app_version_last", 0);
            if (last_ver < version) {
                String update_apk = SharedPreferencesUtil.getString("terminal_update_pkg", "");
                if (!TextUtils.isEmpty(update_apk)) {
                    File file = new File(update_apk);
                    if (file.exists()) {
                        if (file.delete()) {
                            SharedPreferencesUtil.putString("terminal_update_pkg", "");
                            MsgUtil.showMsg("更新包已删除");
                        } else MsgUtil.showMsg("更新包删除失败");
                    } else SharedPreferencesUtil.putString("terminal_update_pkg", "");
                }

                MsgUtil.showDialog("提醒", context.getString(R.string.text_update_success), 5);

                if (last_ver != 0) {
                    if (last_ver < 20240606)
                        MsgUtil.showDialog("部分风控问题已解决", "当前的新版本实现了对抗部分类型的风控，建议您重新登录账号以确保成功使用");

                    if (last_ver < 20250329 && SharedPreferencesUtil.getBoolean("player_ui_round", false)) {
                        SharedPreferencesUtil.putInt("paddingV_percent", 3);
                        SharedPreferencesUtil.putInt("paddingH_percent", 7);
                    }
                }
                MsgUtil.showText("更新公告", context.getResources().getString(R.string.update_tip) + "\n\n更新细节：\n" + ToolsUtil.getUpdateLog(context));
                if (ToolsUtil.isDebugBuild())
                    MsgUtil.showDialog("警告", context.getString(R.string.warning_debug));
                SharedPreferencesUtil.putInt("app_version_last", version);
            }

            // 只有启用自动检查更新时才检查应用更新
            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.AUTO_CHECK_UPDATE_ENABLE, true)) {
                if (SharedPreferencesUtil.getInt("app_version_check", 0) < curr) {    //限制一天一次
                    Log.e("debug", "检查更新");
                    SharedPreferencesUtil.putInt("app_version_check", curr);

                    checkUpdate(context, false);
                }
            }
        } catch (IOException e) {
            MsgUtil.showMsg("无法连接到终端公告接口\n也许是服务器宕机了？\n（对软件内容无影响）");
        } catch (JSONException e) {
            MsgUtil.showMsg("解析错误");
        } catch (Exception e) {
            Log.e("debug-terminal", e.toString());
            MsgUtil.err("终端接口出现问题（不影响软件内容）", e);
        }
    }

    public static final ArrayList<String> customHeaders = new ArrayList<>() {{
        add("User-Agent");
        add(NetWorkUtil.USER_AGENT_WEB);    //防止携带b站cookies导致可能存在的开发者盗号问题（
        add("App-Info");
        try {
            add(new JSONObject()
                    .put("versionName", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE)
                    .put("isBeta", BuildConfig.BETA)
                    .put("applicationId", BuildConfig.APPLICATION_ID)
                    .put("buildType", BuildConfig.BUILD_TYPE)
                    .put("debugEnabled", BuildConfig.DEBUG)
                    .toString());
        } catch (JSONException e) {
            MsgUtil.showMsg("版本信息json生成出错\n无影响，正常情况下你应该不会遇到");
        }
        add("Device-Info");
        try {
            add(new JSONObject()
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("release", Build.VERSION.RELEASE)
                    .put("product", Build.PRODUCT)
                    .put("brand", Build.BRAND)
                    .put("device", Build.DEVICE)
                    .put("type", Build.TYPE)
                    .put("id", Build.ID)
                    .toString());
        } catch (JSONException e) {
            MsgUtil.showMsg("设备信息json生成出错\n无影响，正常情况下你应该不会遇到");
        }
    }};

    private static void checkUpdate(Context context, boolean need_toast, boolean debug_ver) {

        // 说明：本 Fork 使用 GitHub Releases 作为更新源；
        // 由于 versionCode/versionName 可能不变，这里用 BuildConfig.GIT_TAG 与 release tag 比较。
        try {
            UpdateInfo latest = null;

            // 1) GitHub Releases/latest（主）
            try {
                JSONObject githubJson = NetWorkUtil.getJson(GITHUB_LATEST_RELEASE_URL, customHeaders);
                latest = parseUpdateInfoFromGithubRelease(githubJson);
            } catch (Throwable t) {
                latest = null;
            }

            // 2) jsDelivr version.json（备）
            if (latest == null) {
                try {
                    // 加一个轻量 cache-bust，避免 jsDelivr 缓存导致更新不及时（按天即可）
                    int curr = ConfInfoApi.getDateCurr();
                    JSONObject cdnJson = NetWorkUtil.getJson(JSDELIVR_VERSION_JSON_URL + "?t=" + curr, customHeaders);
                    latest = parseUpdateInfoFromVersionJson(cdnJson);
                } catch (Throwable t) {
                    latest = null;
                }
            }

            if (latest == null || TextUtils.isEmpty(latest.tagName)) {
                if (need_toast) {
                    MsgUtil.showMsg("网络异常\n请稍后再试");
                }
                return;
            }

            String currentTag = BuildConfig.GIT_TAG;
            boolean hasUpdate = isTagNewer(latest.tagName, currentTag);

            if (!hasUpdate) {
                if (need_toast) {
                    MsgUtil.showMsg(debug_ver ? "没有新的测试版了！" : "当前是最新版本！");
                }
                return;
            }

            Intent intent = new Intent(context, UpdateInfoActivity.class);
            // auto check (Application context) 需要 NEW_TASK
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            intent.putExtra("versionName", latest.tagName);
            intent.putExtra("versionCode", BuildConfig.VERSION_CODE);
            intent.putExtra("isRelease", 1);
            intent.putExtra("ctime", latest.publishedAtEpochSeconds > 0 ? latest.publishedAtEpochSeconds : -1);
            intent.putExtra("updateLog", latest.body == null ? "" : latest.body);
            intent.putExtra("canDownload", TextUtils.isEmpty(latest.downloadUrl) ? 0 : 1);
            intent.putExtra("downloadUrl", latest.downloadUrl == null ? "" : latest.downloadUrl);
            CenterThreadPool.runOnUiThread(() -> {
                try {
                    context.startActivity(intent);
                } catch (Throwable t) {
                    // 极端情况下 startActivity 失败，不要崩溃
                    if (need_toast) {
                        MsgUtil.showMsg("网络异常\n请稍后再试");
                    }
                }
            });
        } catch (Throwable t) {
            if (need_toast) {
                MsgUtil.showMsg("网络异常\n请稍后再试");
            }
        }
    }

    public static void checkUpdate(Context context, boolean need_toast) {
        checkUpdate(context, need_toast, false);
    }

    public static String getDownloadUrl(int versionCode) throws Exception {
        // 更新接口已停用，返回空字符串
        return "";
    }

    private static UpdateInfo parseUpdateInfoFromGithubRelease(JSONObject releaseJson) {
        if (releaseJson == null) return null;
        // GitHub API 如果 rate limit / 异常，可能返回 {message:..., documentation_url:...}
        if (!releaseJson.has("tag_name")) return null;

        UpdateInfo info = new UpdateInfo();
        info.tagName = releaseJson.optString("tag_name", "");
        info.title = releaseJson.optString("name", info.tagName);
        info.body = releaseJson.optString("body", "");
        info.publishedAtEpochSeconds = parseIso8601EpochSeconds(releaseJson.optString("published_at", ""));
        info.downloadUrl = findAssetDownloadUrl(releaseJson.optJSONArray("assets"), UPDATE_ASSET_NAME);
        return TextUtils.isEmpty(info.tagName) ? null : info;
    }

    private static UpdateInfo parseUpdateInfoFromVersionJson(JSONObject versionJson) {
        if (versionJson == null) return null;
        // version.json 建议与 GitHub release json 结构尽量对齐
        if (!versionJson.has("tag_name") && !versionJson.has("tag")) return null;

        UpdateInfo info = new UpdateInfo();
        info.tagName = versionJson.optString("tag_name", versionJson.optString("tag", ""));
        info.title = versionJson.optString("name", info.tagName);
        info.body = versionJson.optString("body", "");
        info.publishedAtEpochSeconds = parseIso8601EpochSeconds(versionJson.optString("published_at", ""));

        // 兼容：assets 数组 or 直接 downloadUrl
        String dl = versionJson.optString("downloadUrl", "");
        if (!TextUtils.isEmpty(dl)) {
            info.downloadUrl = dl;
        } else {
            info.downloadUrl = findAssetDownloadUrl(versionJson.optJSONArray("assets"), UPDATE_ASSET_NAME);
        }
        return TextUtils.isEmpty(info.tagName) ? null : info;
    }

    private static String findAssetDownloadUrl(JSONArray assets, String assetName) {
        if (assets == null || TextUtils.isEmpty(assetName)) return "";
        try {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                if (assetName.equals(name)) {
                    return asset.optString("browser_download_url", "");
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static long parseIso8601EpochSeconds(String iso) {
        if (TextUtils.isEmpty(iso)) return -1;
        try {
            // 兼容 GitHub 格式：2026-03-15T00:00:00Z 或带毫秒
            String s = iso.trim();
            int dot = s.indexOf('.');
            if (dot > 0) {
                int z = s.indexOf('Z', dot);
                if (z > 0) {
                    s = s.substring(0, dot) + "Z";
                }
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = sdf.parse(s);
            if (d == null) return -1;
            return d.getTime() / 1000L;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * 判断 remoteTag 是否比 localTag 新。
     * - 支持 release-20 / release-19.1 这种格式
     * - 任一无法解析时，退化为字符串不等（remote != local 即视为有更新）
     */
    private static boolean isTagNewer(String remoteTag, String localTag) {
        if (TextUtils.isEmpty(remoteTag)) return false;
        if (TextUtils.isEmpty(localTag)) return true;
        if (remoteTag.equalsIgnoreCase(localTag)) return false;

        TagVersion r = parseReleaseTag(remoteTag);
        TagVersion l = parseReleaseTag(localTag);
        if (r != null && l != null) {
            if (r.major != l.major) return r.major > l.major;
            return r.minor > l.minor;
        }

        // 退化策略：无法解析时只做“不等”判断（满足你“tag 变了就提示更新”的核心诉求）
        return true;
    }

    private static TagVersion parseReleaseTag(String tag) {
        if (TextUtils.isEmpty(tag)) return null;
        Matcher m = RELEASE_TAG_PATTERN.matcher(tag.trim());
        if (!m.matches()) return null;
        try {
            int major = Integer.parseInt(m.group(1));
            int minor = 0;
            String minorStr = m.group(2);
            if (!TextUtils.isEmpty(minorStr)) {
                minor = Integer.parseInt(minorStr);
            }
            return new TagVersion(major, minor);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void checkAnnouncement() throws Exception {
        // 原公告接口已停运：保留方法以兼容旧调用，不再访问网络
    }

    public static ArrayList<Announcement> getAnnouncementList() throws Exception {
        // 原公告接口已停运：公告列表固定返回空
        return new ArrayList<>();
    }

    public static ApiResult uploadStack(String stack, Context context) {
        //上传崩溃堆栈
        try {
            String url = "http://api.biliterminal.cn/terminal/upload/stack";

            JSONObject post_data = new JSONObject();
            post_data.put("stack", stack);
            post_data.put("client_version", context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode);
            post_data.put("device_sdk", Build.VERSION.SDK_INT);
            post_data.put("device_product", Build.PRODUCT);
            post_data.put("device_brand", Build.BRAND);

            JSONObject res = new JSONObject(Objects.requireNonNull(NetWorkUtil.postJson(url, post_data.toString(), customHeaders).body()).string());
            String msg = (res.getInt("code") == 200) ? "" : res.getString("msg");
            int id = res.optInt("id", -1);
            return new ApiResult(id, msg);
        } catch (IOException e) {
            return new ApiResult(-1, context.getString(R.string.err_network));
        } catch (JSONException e) {
            return new ApiResult(-514, context.getString(R.string.err_crash_upload_json));
        } catch (PackageManager.NameNotFoundException e) {
            return new ApiResult(-1919, "");
        }
    }

    public static int getSponsors(ArrayList<UserInfo> list, int page) throws Exception {
        String url = "http://api.biliterminal.cn/terminal/afdian/get_sponsor?page=" + page;
        JSONObject result = NetWorkUtil.getJson(url, customHeaders);

        if (result.getInt("code") != 200) throw new Exception("获取失败");
        JSONArray data = result.getJSONArray("data");

        if (data.length() == 0) return 1;

        @SuppressLint("SimpleDateFormat") SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd hh:mm");

        for (int i = 0; i < data.length(); i++) {
            JSONObject sponsor = data.getJSONObject(i);

            UserInfo user = new UserInfo();

            user.name = sponsor.getString("name");
            user.avatar = sponsor.getString("avatar");
            user.sign = "总金额：" + sponsor.getInt("sum_amount") + "r | 捐赠时间：" + sdf.format(sponsor.getLong("last_time") * 1000);
            user.mid = -1;
            user.fans = 0;
            user.followed = true;
            user.level = 6;
            user.notice = "";
            user.official = 0;
            user.officialDesc = "";

            list.add(user);
        }
        return 0;
    }
}
