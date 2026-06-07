package com.RobinNotBad.BiliClient.api;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.WindowManager;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.util.Cookies;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Response;

/**
 * Cookies相关API
 */
public class CookiesApi {
    private static final String RISK_TRACE_TAG = "cookie-risk-active";
    private static final String RISK_SCENE_DEFAULT = "333.1007.fp.risk";
    private static final String RISK_SCENE_SPACE_DYNAMIC = "333.1387.fp.risk";
    private static final String SPACE_DYNAMIC_RISK_USER_AGENT = "Dart/3.6 (dart:io)";
    // Space feed risk is sensitive to browser-cookie noise; keep this list close to PiliPlus's account cookie context.
    private static final String[] SPACE_DYNAMIC_COOKIE_NAMES = {
            "SESSDATA",
            "DedeUserID",
            "DedeUserID__ckMd5",
            "bili_jct",
            "sid",
            "buvid3",
            "b_nut"
    };
    private static final Object PROCESS_RISK_ACTIVE_LOCK = new Object();
    private static final Map<String, Boolean> PROCESS_RISK_ACTIVE = new HashMap<>();

    public static ArrayList<String> genWebHeaders() {
        return new ArrayList<>() {{
            addAll(NetWorkUtil.webHeaders);

            add("Sec-Fetch-Site");
            add("same-site");

            add("Sec-Fetch-Mode");
            add("cors");

            add("Sec-Fetch-Dest");
            add("empty");
        }};
    }

    /**
     * 调用ExClimbWuzhi API激活Cookies，并检查如果有一些可本地生成的Cookie没有就顺带生成一下
     * 注：payload是我瞎jb弄得
     *
     * @return 返回码
     */
    public static int activeCookieInfo() throws JSONException, IOException {
        return activeCookieInfo(RISK_SCENE_DEFAULT);
    }

    public static int activeCookieInfo(String riskScene) throws JSONException, IOException {
        return activeCookieInfo(riskScene, 0);
    }

    public static int activeCookieInfo(String riskScene, long mid) throws JSONException, IOException {
        String url = "https://api.bilibili.com/x/internal/gaia-gateway/ExClimbWuzhi";
        //NetWorkUtil.postJson(url, genCookiePayload().toString(), genWebHeaders());    //b站自己请求两次，所以我也请求两次（？）
        try (Response response = NetWorkUtil.postJson(url, genCookiePayload(riskScene), genRiskActiveHeaders(riskScene, mid))) {
            String body = Objects.requireNonNull(response.body()).string();
            int code = new JSONObject(body).getInt("code");
            Logu.w(RISK_TRACE_TAG, "active response, scene=" + riskScene
                    + ", mid=" + mid
                    + ", httpCode=" + response.code()
                    + ", code=" + code
                    + ", bodyLen=" + body.length());
            return code;
        }
    }

    private static ArrayList<String> genRiskActiveHeaders(String riskScene, long mid) {
        ArrayList<String> headers = RISK_SCENE_SPACE_DYNAMIC.equals(riskScene)
                ? genSpaceDynamicRiskActiveHeaders()
                : genWebHeaders();
        applyPiliPlusAccountHeaders(headers);
        return headers;
    }

    private static ArrayList<String> genSpaceDynamicRiskActiveHeaders() {
        ArrayList<String> headers = new ArrayList<>();
        headers.add("Cookie");
        headers.add(buildSpaceDynamicCookieHeader());
        headers.add("Referer");
        headers.add("https://www.bilibili.com/");
        headers.add("User-Agent");
        headers.add(SPACE_DYNAMIC_RISK_USER_AGENT);
        return headers;
    }

    public static String buildSpaceDynamicCookieHeader() {
        return buildSpaceDynamicCookieHeader(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
    }

    private static String buildSpaceDynamicCookieHeader(String rawCookie) {
        Map<String, String> cookieMap = parseCookieHeader(rawCookie);
        StringBuilder builder = new StringBuilder();
        for (String name : SPACE_DYNAMIC_COOKIE_NAMES) {
            String value = cookieMap.get(name);
            if (value == null || value.isEmpty()) continue;
            if (builder.length() > 0) builder.append("; ");
            builder.append(name).append("=").append(value);
        }
        String result = builder.toString();
        if (!hasCookieName(result, "SESSDATA") || !hasCookieName(result, "buvid3")) {
            Logu.w(RISK_TRACE_TAG, "space dynamic cookie incomplete, names=" + summarizeCookieNames(result));
        }
        return result;
    }

    private static boolean hasCookieName(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.trim().isEmpty()) return false;
        String[] cookies = cookieHeader.split(";\\s*");
        for (String cookie : cookies) {
            int index = cookie.indexOf('=');
            if (index > 0 && name.equals(cookie.substring(0, index))) return true;
        }
        return false;
    }

    private static Map<String, String> parseCookieHeader(String rawCookie) {
        Map<String, String> result = new HashMap<>();
        if (rawCookie == null || rawCookie.trim().isEmpty()) return result;
        String[] cookies = rawCookie.split(";\\s*");
        for (String cookie : cookies) {
            if (cookie == null) continue;
            int index = cookie.indexOf('=');
            if (index <= 0) continue;
            String key = cookie.substring(0, index).trim();
            String value = cookie.substring(index + 1).trim();
            if (!key.isEmpty()) result.put(key, value);
        }
        return result;
    }

    private static String summarizeCookieNames(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.trim().isEmpty()) return "[]";
        List<String> names = new ArrayList<>();
        String[] cookies = cookieHeader.split(";\\s*");
        for (String cookie : cookies) {
            int index = cookie.indexOf('=');
            if (index > 0) names.add(cookie.substring(0, index));
        }
        return names.toString();
    }

    static void applyPiliPlusAccountHeaders(List<String> headers) {
        setHeader(headers, "env", "prod");
        setHeader(headers, "app-key", "android64");
        setHeader(headers, "x-bili-aurora-zone", "sh001");

        String selfMid = NetWorkUtil.getInfoFromCookie("DedeUserID", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
        if (selfMid != null && !selfMid.isEmpty()) {
            setHeader(headers, "x-bili-mid", selfMid);
            setHeader(headers, "x-bili-aurora-eid", genAuroraEid(selfMid));
        }
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

    private static String genAuroraEid(String uid) {
        if (uid == null || uid.isEmpty()) return "";
        byte[] bytes = uid.getBytes(StandardCharsets.US_ASCII);
        byte[] key = "ad1va46a7lza".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (bytes[i] ^ key[i % key.length]);
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP).replace("=", "");
    }

    /*
     * from https://s1.hdslb.com/bfs/seed/log/report/log-reporter.js
     */
    public static String genCookiePayload() throws JSONException {
        return genCookiePayload(RISK_SCENE_DEFAULT);
    }

    public static String genCookiePayload(String riskScene) throws JSONException {
        JSONObject fp = new JSONObject()
                .put("adca", "Linux")
                .put("bfe9", genRandomPngTail());
        JSONObject payload = new JSONObject()
                .put("3064", 1)
                .put("39c8", riskScene)
                .put("3c43", fp);
        return new JSONObject()
                .put("payload", payload.toString())
                .toString();
    }

    private static String genRandomPngTail() {
        byte[] bytes = new byte[44];
        SecureRandom random = new SecureRandom();
        random.nextBytes(bytes);
        bytes[32] = 0;
        bytes[33] = 0;
        bytes[34] = 0;
        bytes[35] = 0;
        bytes[36] = 73;
        bytes[37] = 69;
        bytes[38] = 78;
        bytes[39] = 68;
        String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
        return encoded.length() > 50 ? encoded.substring(encoded.length() - 50) : encoded;
    }

    /**
     * 仅获取buvid3
     *
     * @return buvid3
     */
    public static String getBuvid3Only() throws JSONException, IOException {
        String url = "https://api.bilibili.com/x/web-frontend/getbuvid";
        JSONObject data = NetWorkUtil.getJson(url, genWebHeaders());
        return data.optString("buvid", "");
    }

    /**
     * 获取buvid3、buvid4，可能需要在上面先获取单个的buvid3
     *
     * @return buvid3、buvid4
     */
    public static Pair<String, String> getWebBuvids() throws JSONException, IOException {
        String url = "https://api.bilibili.com/x/frontend/finger/spi";
        JSONObject data = NetWorkUtil.getJson(url, genWebHeaders()).getJSONObject("data");
        return new Pair<>(data.optString("b_3"), data.optString("b_4"));
    }

    /**
     * 生成bili_ticket
     *
     * @return bili_ticket and create time
     * @throws IOException
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @throws JSONException
     */
    public static Pair<String, Integer> genBiliTicket() throws IOException, NoSuchAlgorithmException, InvalidKeyException, JSONException {
        long ts = System.currentTimeMillis() / 1000;
        String o = hmacSha256("XgwSnGZ1p", "ts" + ts);
        String url = "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket";
        JSONObject result = new JSONObject(Objects.requireNonNull(NetWorkUtil.postJson(url + new NetWorkUtil.FormData()
                        .setUrlParam(true)
                        .put("key_id", "ec02")
                        .put("hexsign", o)
                        .put("context[ts]", String.valueOf(ts))
                        .put("csrf", SharedPreferencesUtil.getString("csrf", "")),
                "", genWebHeaders()).body()).string());
        if (result.has("data") && !result.isNull("data")) {
            JSONObject data = result.getJSONObject("data");
            return new Pair<>(data.optString("ticket"), data.optInt("created_at"));
        } else {
            return new Pair<>(null, -1);
        }
    }

    public static String hmacSha256(String key, String message)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac sha256Hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(), "HmacSHA256");
        sha256Hmac.init(secretKey);
        byte[] hashBytes = sha256Hmac.doFinal(message.getBytes());
        StringBuilder hexHash = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexHash.append('0');
            hexHash.append(hex);
        }
        return hexHash.toString();
    }

    private static final Map<String, String> otherCookieMap = new HashMap<>() {{
        put("enable_web_push", "DISABLE");
        put("header_theme_version", "undefined");
        put("home_feed_column", "4");
        put("PVID", "1");
    }};

    public static void checkCookies() throws JSONException, IOException {
        // StrictMode/资源泄漏修复：确保 Response 被关闭，避免连接池泄漏
        try (Response response = NetWorkUtil.get("https://www.bilibili.com/")) {
            // no-op
        }

        Cookies cookies = NetWorkUtil.getCookies();

        // _uuid
        if (!cookies.containsKey("_uuid")) {
            NetWorkUtil.putCookie("_uuid", gen_uuid_infoc());
        }

        // b_nut
        if (!cookies.containsKey("b_nut")) {
            NetWorkUtil.putCookie("b_nut", gen_b_nut());
        }

        // b_lsid
        if (!cookies.containsKey("b_lsid")) {
            NetWorkUtil.putCookie("b_lsid", gen_b_lsid());
        }

        // buvid3 根据浏览器端的网络请求顺序，B站自己是先获取单个buvid3的，虽然我并不知道为什么要这样做…？
        if (!cookies.containsKey("buvid3")) {
            String buvid3 = getBuvid3Only();
            NetWorkUtil.putCookie("buvid3", buvid3);
        }

        // buvid3 & buvid4. Get from http API.
        if (!cookies.containsKey("buvid4")) {
            Pair<String, String> buvids = getWebBuvids();
            NetWorkUtil.putCookie("buvid3", buvids.first);
            NetWorkUtil.putCookie("buvid4", buvids.second);
        }

        // LIVE_BUVID
        if (!cookies.containsKey("LIVE_BUVID")) {
            long min = 1000000000000000L;
            long max = 9999999999999999L;
            NetWorkUtil.putCookie("LIVE_BUVID", "AUTO" + (min + (long) (new Random().nextDouble() * (max - min))));
        }

        // browser_resolution
        if (!cookies.containsKey("browser_resolution")) {
            Pair<Integer, Integer> resolution = gen_browser_resolution();
            NetWorkUtil.putCookie("browser_resolution", resolution.first + "-" + resolution.second);
        }

        // bili_ticket
        if (!cookies.containsKey("bili_ticket") || cookies.get("bili_ticket").equals("null") || !cookies.containsKey("bili_ticket_expires") || parseInt(cookies.get("bili_ticket_expires")) == null || parseInt(cookies.get("bili_ticket_expires")) < System.currentTimeMillis() / 1000) {
            try {
                Pair<String, Integer> bili_ticket = genBiliTicket();
                NetWorkUtil.putCookie("bili_ticket", bili_ticket.first);
                NetWorkUtil.putCookie("bili_ticket_expires", String.valueOf(bili_ticket.second + (3 * 24 * 60 * 60)));
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            } catch (InvalidKeyException e) {
                throw new RuntimeException(e);
            }
        }

        // buvid_fp
        if (!cookies.containsKey("buvid_fp")) {
            NetWorkUtil.putCookie("buvid_fp", gen_buvid_fp(NetWorkUtil.USER_AGENT_WEB + System.currentTimeMillis(), 31));
        }

        // Others
        for (Map.Entry<String, String> entry : otherCookieMap.entrySet()) {
            if (!cookies.containsKey(entry.getKey())) {
                NetWorkUtil.putCookie(entry.getKey(), entry.getValue());
            }
        }

        //activeCookieInfo();
    }

    public static void ensurePiliPlusBaseCookies() {
        Cookies cookies = NetWorkUtil.getCookies();
        if (!cookies.containsKey("buvid3")) {
            NetWorkUtil.putCookie("buvid3", genPiliPlusBuvid3());
        }
    }

    public static boolean ensureRiskActive(boolean force) {
        return ensureRiskActive(RISK_SCENE_DEFAULT, force);
    }

    public static boolean ensureSpaceDynamicRiskActive(boolean force) {
        return ensureSpaceDynamicRiskActive(0, force);
    }

    public static boolean ensureSpaceDynamicRiskActive(long mid, boolean force) {
        return ensureRiskActive(RISK_SCENE_SPACE_DYNAMIC, mid, force);
    }

    public static boolean ensureRiskActive(String riskScene, boolean force) {
        return ensureRiskActive(riskScene, 0, force);
    }

    public static boolean ensureRiskActive(String riskScene, long mid, boolean force) {
        if (RISK_SCENE_SPACE_DYNAMIC.equals(riskScene)) {
            return ensureProcessRiskActive(riskScene, mid, force);
        }
        int today = ConfInfoApi.getDateCurr();
        String cacheKey = SharedPreferencesUtil.COOKIE_RISK_ACTIVE_DAY + "_" + riskScene.replace('.', '_');
        if (!force && SharedPreferencesUtil.getInt(cacheKey, 0) >= today) {
            Logu.w(RISK_TRACE_TAG, "skip active by daily cache, scene=" + riskScene + ", mid=" + mid);
            return false;
        }
        try {
            int code = activeCookieInfo(riskScene, mid);
            if (code == 0) {
                SharedPreferencesUtil.putInt(cacheKey, today);
                Logu.w(RISK_TRACE_TAG, "active ok, scene=" + riskScene + ", mid=" + mid + ", force=" + force);
                return true;
            }
            SharedPreferencesUtil.removeValue(cacheKey);
            Logu.w(RISK_TRACE_TAG, "active rejected, scene=" + riskScene + ", mid=" + mid + ", code=" + code + ", force=" + force);
            return false;
        } catch (Exception e) {
            SharedPreferencesUtil.removeValue(cacheKey);
            Logu.w(RISK_TRACE_TAG, "active failed, scene=" + riskScene + ", mid=" + mid + ", force=" + force + ", err=" + e.getMessage());
            return false;
        }
    }

    private static boolean ensureProcessRiskActive(String riskScene, long targetMid, boolean force) {
        ensurePiliPlusBaseCookies();
        String activeKey = riskScene + "_" + currentRiskAccountKey();
        synchronized (PROCESS_RISK_ACTIVE_LOCK) {
            if (!force && Boolean.TRUE.equals(PROCESS_RISK_ACTIVE.get(activeKey))) {
                Logu.w(RISK_TRACE_TAG, "skip active by process cache, scene=" + riskScene
                        + ", targetMid=" + targetMid
                        + ", account=" + activeKey);
                return false;
            }

            String staleDailyKey = SharedPreferencesUtil.COOKIE_RISK_ACTIVE_DAY + "_" + riskScene.replace('.', '_');
            SharedPreferencesUtil.removeValue(staleDailyKey);
            try {
                int code = activeCookieInfo(riskScene, 0);
                if (code == 0) {
                    PROCESS_RISK_ACTIVE.put(activeKey, true);
                    Logu.w(RISK_TRACE_TAG, "active ok, scene=" + riskScene
                            + ", targetMid=" + targetMid
                            + ", account=" + activeKey
                            + ", force=" + force);
                    return true;
                }
                PROCESS_RISK_ACTIVE.remove(activeKey);
                Logu.w(RISK_TRACE_TAG, "active rejected, scene=" + riskScene
                        + ", targetMid=" + targetMid
                        + ", account=" + activeKey
                        + ", code=" + code
                        + ", force=" + force);
                return false;
            } catch (Exception e) {
                PROCESS_RISK_ACTIVE.remove(activeKey);
                Logu.w(RISK_TRACE_TAG, "active failed, scene=" + riskScene
                        + ", targetMid=" + targetMid
                        + ", account=" + activeKey
                        + ", force=" + force
                        + ", err=" + e.getMessage());
                return false;
            }
        }
    }

    private static String currentRiskAccountKey() {
        Cookies cookies = NetWorkUtil.getCookies();
        String mid = cookies.get("DedeUserID");
        if (mid != null && !mid.isEmpty()) {
            return "mid=" + mid;
        }
        String buvid3 = cookies.get("buvid3");
        if (buvid3 != null && !buvid3.isEmpty()) {
            return "buvid3=" + buvid3;
        }
        return "anonymous";
    }

    private static String genPiliPlusBuvid3() {
        int tail = new SecureRandom().nextInt(100000);
        return UUID.randomUUID().toString().toUpperCase(Locale.US)
                + String.format(Locale.US, "%05d", tail)
                + "infoc";
    }

    public static void clearProcessRiskActiveCache(String reason) {
        synchronized (PROCESS_RISK_ACTIVE_LOCK) {
            if (!PROCESS_RISK_ACTIVE.isEmpty()) {
                PROCESS_RISK_ACTIVE.clear();
                Logu.w(RISK_TRACE_TAG, "clear process risk cache, reason=" + reason);
            }
        }
    }

    public static boolean ensureRiskActiveDaily() {
        return ensureRiskActive(false);
    }

    public static boolean ensureSpaceDynamicRiskActiveDaily() {
        return ensureSpaceDynamicRiskActive(false);
    }

    public static boolean ensureSpaceDynamicRiskActiveDaily(long mid) {
        return ensureSpaceDynamicRiskActive(mid, false);
    }

    private static Integer parseInt(String string) {
        try {
            return Integer.parseInt(string);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final String[] MP = {
            "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "A", "B", "C", "D", "E", "F", "10"
    };
    private static final int[] PCK = {8, 4, 4, 4, 12};
    private static final String CHARSET = "0123456789ABCDEF";

    private static String gen_b_lsid() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CHARSET.charAt(random.nextInt(CHARSET.length())));
        }
        String randomString = sb.toString();
        long currentTimeMillis = System.currentTimeMillis();
        return randomString + "_" + Long.toHexString(currentTimeMillis).toUpperCase();
    }

    @SuppressLint("DefaultLocale")
    private static String gen_uuid_infoc() {
        long t = System.currentTimeMillis() % 100000;
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        for (int len : PCK) {
            for (int i = 0; i < len; i++) {
                sb.append(MP[random.nextInt(16)]);
            }
            sb.append("-");
        }
        sb.deleteCharAt(sb.length() - 1);
        sb.append(String.format("%05d", t)).append("infoc");
        return sb.toString();
    }

    private static String gen_b_nut() {
        long timestampInSeconds = System.currentTimeMillis() / 1000;
        return String.valueOf(timestampInSeconds);
    }

    private static Pair<Integer, Integer> gen_browser_resolution() {
        WindowManager windowManager = (WindowManager) BiliTerminal.context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        return new Pair<>(metrics.widthPixels, metrics.heightPixels);
    }

    private static final BigInteger MOD = BigInteger.ONE.shiftLeft(64);
    private static final BigInteger C1 = new BigInteger("87C37B91114253D5", 16);
    private static final BigInteger C2 = new BigInteger("4CF5AD432745937F", 16);
    private static final BigInteger C3 = BigInteger.valueOf(0x52DCE729L);
    private static final BigInteger C4 = BigInteger.valueOf(0x38495AB5L);
    private static final int R1 = 27;
    private static final int R2 = 31;
    private static final int R3 = 33;
    private static final int M = 5;

    public static String gen_buvid_fp(String key, long seed) throws IOException {
        InputStream source = new ByteArrayInputStream(key.getBytes("US-ASCII"));
        BigInteger m = murmur3_x64_128(source, BigInteger.valueOf(seed));
        return String.format("%016x%016x", m.mod(MOD), m.shiftRight(64).mod(MOD));
    }

    private static BigInteger rotateLeft(BigInteger x, int k) {
        return x.shiftLeft(k).or(x.shiftRight(64 - k)).mod(MOD);
    }

    private static BigInteger murmur3_x64_128(InputStream source, BigInteger seed) throws IOException {
        BigInteger h1 = seed;
        BigInteger h2 = seed;
        long processed = 0;
        byte[] buffer = new byte[16];
        while (true) {
            int bytesRead = source.read(buffer);
            processed += bytesRead;
            if (bytesRead == 16) {
                long k1 = ByteBuffer.wrap(buffer, 0, 8).getLong();
                long k2 = ByteBuffer.wrap(buffer, 8, 8).getLong();
                h1 = h1.xor(rotateLeft(BigInteger.valueOf(k1).multiply(C1).mod(MOD), R2).multiply(C2).mod(MOD));
                h1 = (rotateLeft(h1, R1).add(h2).multiply(BigInteger.valueOf(M)).add(C3)).mod(MOD);
                h2 = h2.xor(rotateLeft(BigInteger.valueOf(k2).multiply(C2).mod(MOD), R3).multiply(C1).mod(MOD));
                h2 = (rotateLeft(h2, R2).add(h1).multiply(BigInteger.valueOf(M)).add(C4)).mod(MOD);
            } else if (bytesRead == -1) {
                h1 = h1.xor(BigInteger.valueOf(processed));
                h2 = h2.xor(BigInteger.valueOf(processed));
                h1 = h1.add(h2).mod(MOD);
                h2 = h2.add(h1).mod(MOD);
                h1 = fmix64(h1);
                h2 = fmix64(h2);
                h1 = h1.add(h2).mod(MOD);
                h2 = h2.add(h1).mod(MOD);
                return h2.shiftLeft(64).or(h1);
            } else {
                long k1 = 0;
                long k2 = 0;
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead);
                if (bytesRead >= 15) {
                    k2 ^= (long) byteBuffer.get(14) << 48;
                }
                if (bytesRead >= 14) {
                    k2 ^= (long) byteBuffer.get(13) << 40;
                }
                if (bytesRead >= 13) {
                    k2 ^= (long) byteBuffer.get(12) << 32;
                }
                if (bytesRead >= 12) {
                    k2 ^= (long) byteBuffer.get(11) << 24;
                }
                if (bytesRead >= 11) {
                    k2 ^= (long) byteBuffer.get(10) << 16;
                }
                if (bytesRead >= 10) {
                    k2 ^= (long) byteBuffer.get(9) << 8;
                }
                if (bytesRead >= 9) {
                    k2 ^= byteBuffer.get(8);
                    h2 = h2.xor(rotateLeft(BigInteger.valueOf(k2).multiply(C2).mod(MOD), R3).multiply(C1).mod(MOD));
                }
                if (bytesRead >= 8) {
                    k1 ^= (long) byteBuffer.get(7) << 56;
                }
                if (bytesRead >= 7) {
                    k1 ^= (long) byteBuffer.get(6) << 48;
                }
                if (bytesRead >= 6) {
                    k1 ^= (long) byteBuffer.get(5) << 40;
                }
                if (bytesRead >= 5) {
                    k1 ^= (long) byteBuffer.get(4) << 32;
                }
                if (bytesRead >= 4) {
                    k1 ^= (long) byteBuffer.get(3) << 24;
                }
                if (bytesRead >= 3) {
                    k1 ^= (long) byteBuffer.get(2) << 16;
                }
                if (bytesRead >= 2) {
                    k1 ^= (long) byteBuffer.get(1) << 8;
                }
                if (bytesRead >= 1) {
                    k1 ^= byteBuffer.get(0);
                    h1 = h1.xor(rotateLeft(BigInteger.valueOf(k1).multiply(C1).mod(MOD), R2));
                }
            }
        }
    }

    private static BigInteger fmix64(BigInteger k) {
        final BigInteger C1 = new BigInteger("FF51AFD7ED558CCD", 16);
        final BigInteger C2 = new BigInteger("C4CEB9FE1A85EC53", 16);
        final int R = 33;
        k = k.xor(k.shiftRight(R)).multiply(C1).mod(MOD);
        k = k.xor(k.shiftRight(R)).multiply(C2).mod(MOD);
        k = k.xor(k.shiftRight(R)).mod(MOD);
        return k;
    }
}
