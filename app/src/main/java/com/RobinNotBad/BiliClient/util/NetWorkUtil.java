package com.RobinNotBad.BiliClient.util;

import android.annotation.SuppressLint;
import android.os.Build;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.api.CookiesApi;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Inflater;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.Dns;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 被 luern0313 创建于 2019/10/13.
 * #以下代码来源于腕上哔哩的开源项目，感谢开源者做出的贡献！
 */

public class NetWorkUtil {
    private static final AtomicReference<OkHttpClient> INSTANCE = new AtomicReference<>();
    private static final String PERF_TAG = "network-perf";
    private static final String BILI_412_TAG = "bili-412-diagnostic";

    public static class Inet4Selector implements Dns {
        @NonNull
        @Override
        public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
            long start = System.currentTimeMillis();
            List<InetAddress> hosts = Dns.SYSTEM.lookup(hostname);
            List<InetAddress> inet4Hosts = new ArrayList<>();
            for (InetAddress host : hosts) {
                if (host.getAddress().length == 4) inet4Hosts.add(host);
            }
            Logu.w(PERF_TAG, "dns host=" + hostname
                    + ", allCount=" + hosts.size()
                    + ", ipv4Count=" + inet4Hosts.size()
                    + ", costMs=" + (System.currentTimeMillis() - start));
            return inet4Hosts;    //筛选IPV4地址，IPV6请求有异常
        }
    }

    public static OkHttpClient getOkHttpInstance() {
        while (INSTANCE.get() == null) {
            INSTANCE.compareAndSet(null, setOkHttpSsl(new OkHttpClient.Builder())
                    .followRedirects(false)
                    .addInterceptor(new RedirectInterceptor())
                    .addInterceptor(new CookieSaveInterceptor())
                    .dns(new Inet4Selector())
                    .pingInterval(8, TimeUnit.SECONDS)
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(16, TimeUnit.SECONDS).build());
        }
        return INSTANCE.get();
    }

    public synchronized static OkHttpClient.Builder setOkHttpSsl(OkHttpClient.Builder okhttpBuilder) {
        if (Build.VERSION.SDK_INT > 22) return okhttpBuilder;
        try {
            final X509TrustManager trustManager = getSystemTrustManager();
            final SSLSocketFactory sslSocketFactory = new SSLSocketFactoryCompat(trustManager);
            okhttpBuilder.sslSocketFactory(sslSocketFactory, trustManager);
            okhttpBuilder.connectionSpecs(Arrays.asList(
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return okhttpBuilder;
    }

    private static X509TrustManager getSystemTrustManager() throws Exception {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((java.security.KeyStore) null);
        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
        if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
            throw new IllegalStateException("Unexpected default trust managers:" + Arrays.toString(trustManagers));
        }
        return (X509TrustManager) trustManagers[0];
    }

    public static JSONObject getJson(String url) throws IOException {
        return getJson(url, webHeaders);
    }

    public static JSONObject getJson(String url, ArrayList<String> headers) throws IOException {
        // 添加重试机制
        int retryCount = 0;
        final int maxRetries = 3;
        long totalStart = System.currentTimeMillis();
        
        while (retryCount < maxRetries) {
            int attempt = retryCount + 1;
            long attemptStart = System.currentTimeMillis();
            int httpCode = -1;
            String responseText = null;
            try (Response response = get(url, headers)) {
                httpCode = response.code();
                ResponseBody body = response.body();
                if (body != null) {
                    long readStart = System.currentTimeMillis();
                    responseText = body.string();
                    Logu.w(PERF_TAG, "getJson body attempt=" + attempt
                            + "/" + maxRetries
                            + ", httpCode=" + httpCode
                            + ", len=" + responseText.length()
                            + ", readCostMs=" + (System.currentTimeMillis() - readStart)
                            + ", attemptCostMs=" + (System.currentTimeMillis() - attemptStart)
                            + ", totalCostMs=" + (System.currentTimeMillis() - totalStart)
                            + ", url=" + summarizeUrl(url));
                    if (httpCode == 412 || httpCode == 421) {
                        Logu.w(BILI_412_TAG, "headers=" + summarizeHeaders(response)
                                + ", setCookieNames=" + summarizeSetCookieNames(response.headers("Set-Cookie"))
                                + ", body=" + summarizeRiskBody(responseText)
                                + ", url=" + summarizeUrl(url));
                        return buildErrorJson(-1000, "网络请求失败，请检查网络连接后重试", url, httpCode, "risk_html", null);
                    }
                    
                    // 检查是否是HTML页面
                    if (responseText.trim().startsWith("<!DOCTYPE") || responseText.trim().startsWith("<html")) {
                        Logu.e("收到HTML页面而不是JSON数据，重试 " + (retryCount + 1) + "/" + maxRetries + ": " + url);
                        retryCount++;
                        if (retryCount < maxRetries) {
                            Thread.sleep(1000); // 等待1秒后重试
                            continue;
                        } else {
                            // 所有重试都失败，返回一个包含错误信息的JSONObject
                            // 而不是抛出异常，这样上层可以统一处理
                            return buildErrorJson(-1000, "网络请求失败，请检查网络连接后重试", url, httpCode, "html", null);
                        }
                    }
                    
                    // 检查响应是否为空或太短（可能是错误页面）
                    if (responseText.trim().isEmpty() || responseText.length() < 10) {
                        Logu.e("响应数据过短，重试 " + (retryCount + 1) + "/" + maxRetries + ": " + url);
                        retryCount++;
                        if (retryCount < maxRetries) {
                            Thread.sleep(1000);
                            continue;
                        } else {
                            // 所有重试都失败，返回错误JSON
                            return buildErrorJson(-1001, "服务器响应异常，请稍后重试", url, httpCode, "too_short", null);
                        }
                    }
                    
                    // 尝试解析JSON
                    long parseStart = System.currentTimeMillis();
                    JSONObject json = new JSONObject(responseText);
                    Logu.w(PERF_TAG, "getJson parsed attempt=" + attempt
                            + "/" + maxRetries
                            + ", parseCostMs=" + (System.currentTimeMillis() - parseStart)
                            + ", totalCostMs=" + (System.currentTimeMillis() - totalStart)
                            + ", code=" + json.optInt("code", Integer.MIN_VALUE)
                            + ", url=" + summarizeUrl(url));
                    
                    // 检查是否是B站API的标准响应格式
                    if (json.has("code")) {
                        int code = json.optInt("code", -1);
                        if (code == 0) {
                            // 成功响应
                            return json;
                        } else {
                            // B站API返回了错误码，直接返回这个JSON
                            // 上层应该检查code字段
                            return json;
                        }
                    }
                    
                    // 如果不是标准格式，直接返回
                    return json;
                } else {
                    // 响应体为空
                    return buildErrorJson(-1002, "服务器无响应，请检查网络连接", url, httpCode, "empty_body", null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("线程被中断", e);
            } catch (JSONException e) {
                if (responseText != null) {
                    Logu.w(PERF_TAG, "getJson parse failed attempt=" + attempt
                            + "/" + maxRetries
                            + ", httpCode=" + httpCode
                            + ", err=" + e.getMessage()
                            + ", body=" + summarizeBody(responseText)
                            + ", url=" + summarizeUrl(url));
                }
                // JSON解析异常，检查是否需要重试
                if (retryCount < maxRetries - 1) {
                    Logu.e("JSON解析失败，重试 " + (retryCount + 1) + "/" + maxRetries + ": " + e.getMessage() + " - " + url);
                    retryCount++;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("线程被中断", ie);
                    }
                    continue;
                } else {
                    // 所有重试都失败，返回错误JSON
                    return buildErrorJson(-1003, "数据解析失败，请稍后重试", url, httpCode, "parse_failed", e.getMessage());
                }
            } catch (Exception e) {
                // 其他异常，检查是否需要重试
                if (retryCount < maxRetries - 1) {
                    Logu.e("网络请求失败，重试 " + (retryCount + 1) + "/" + maxRetries + ": " + e.getMessage() + " - " + url);
                    retryCount++;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("线程被中断", ie);
                    }
                    continue;
                } else {
                    // 所有重试都失败，返回错误JSON
                    return buildErrorJson(-1004, "网络请求异常: " + e.getMessage(), url, httpCode, "exception", e.getMessage());
                }
            }
        }
        
        // 理论上不会执行到这里，因为所有路径都有返回
        return buildErrorJson(-9999, "未知错误", url, -1, "unknown", null);
    }

    private static JSONObject buildErrorJson(int code, String message, String url, int httpCode, String bodyKind, String detail) {
        JSONObject errorJson = new JSONObject();
        try {
            errorJson.put("code", code);
            errorJson.put("message", message);
            errorJson.put("data", new JSONObject());
            errorJson.put("retry_failed", true);
            errorJson.put("original_url", url);
            errorJson.put("url_host_path", summarizeUrl(url));
            errorJson.put("http_code", httpCode);
            errorJson.put("body_kind", bodyKind);
            if (detail != null) errorJson.put("detail", detail);
        } catch (JSONException ignored) {
        }
        return errorJson;
    }

    public static Response get(String url) throws IOException {
        return get(url, webHeaders);
    }

    public static Response get(String url, ArrayList<String> headers) throws IOException {
        return get(url, headers, null);
    }

    public static Response get(String url, ArrayList<String> headers, RedirectHandler redirectHandler) throws IOException {
        Logu.d("get-url", url);
        OkHttpClient client = getOkHttpInstance();
        Request.Builder requestBuilder = new Request.Builder().url(url).get();
        addHeaders(requestBuilder, headers);
        if (redirectHandler != null) requestBuilder.tag(RedirectHandler.class, redirectHandler);
        Request request = requestBuilder.build();
        long start = System.currentTimeMillis();
        try {
            Response response = client.newCall(request).execute();
            Logu.w(PERF_TAG, "get response code=" + response.code()
                    + ", costMs=" + (System.currentTimeMillis() - start)
                    + ", url=" + summarizeUrl(url));
            return response;
        } catch (IOException e) {
            Logu.w(PERF_TAG, "get failed costMs=" + (System.currentTimeMillis() - start)
                    + ", err=" + e.getMessage()
                    + ", url=" + summarizeUrl(url));
            throw e;
        }
    }

    private static String summarizeUrl(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            return uri.getHost() + path;
        } catch (Throwable ignored) {
            return url;
        }
    }

    private static String summarizeRiskBody(String body) {
        if (body == null) return "null";
        String trimmed = body.trim();
        String kind;
        if (trimmed.isEmpty()) {
            kind = "empty";
        } else if (trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html")) {
            kind = "html";
        } else if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            kind = "json_like";
        } else {
            kind = "text";
        }
        return "kind=" + kind + ", len=" + body.length();
    }

    private static String summarizeBody(String body) {
        if (body == null) return "";
        String summary = body.replace("\r", "\\r").replace("\n", "\\n");
        if (summary.length() > 240) {
            return summary.substring(0, 240) + "...";
        }
        return summary;
    }

    private static String summarizeHeaders(Response response) {
        if (response == null) return "";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < response.headers().size(); i++) {
            String name = response.headers().name(i);
            if (!names.contains(name)) names.add(name);
        }
        return names.toString();
    }

    private static String summarizeSetCookieNames(List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return "[]";
        List<String> names = new ArrayList<>();
        for (String setCookie : setCookies) {
            if (setCookie == null) continue;
            String firstPart = setCookie.split(";", 2)[0].trim();
            int index = firstPart.indexOf('=');
            if (index > 0) names.add(firstPart.substring(0, index));
        }
        return names.toString();
    }

    public static Response post(String url, String data, List<String> headers, String contentType) throws IOException {
        Logu.d("post-url", url);
        Logu.d("post-data", data);
        OkHttpClient client = getOkHttpInstance();
        RequestBody body = RequestBody.create(MediaType.parse(contentType + "; charset=utf-8"), data);
        Request.Builder requestBuilder = new Request.Builder().url(url).post(body);
        ArrayList<String> stableHeaders = new ArrayList<>(headers);
        for (int i = 0; i < stableHeaders.size(); i += 2) {
            String key = stableHeaders.get(i);
            String val = stableHeaders.get(i + 1);
            if (key.equalsIgnoreCase("Content-Type")) val = contentType;
            requestBuilder.addHeader(key, val);
        }
        Request request = requestBuilder.build();
        long start = System.currentTimeMillis();
        try {
            Response response = client.newCall(request).execute();
            Logu.w(PERF_TAG, "post response code=" + response.code()
                    + ", costMs=" + (System.currentTimeMillis() - start)
                    + ", url=" + summarizeUrl(url));
            return response;
        } catch (IOException e) {
            Logu.w(PERF_TAG, "post failed costMs=" + (System.currentTimeMillis() - start)
                    + ", err=" + e.getMessage()
                    + ", url=" + summarizeUrl(url));
            throw e;
        }
    }

    public static Response post(String url, String data, List<String> headers) throws IOException {
        return post(url, data, headers, "application/x-www-form-urlencoded");
    }

    public static Response postJson(String url, String data, List<String> headers) throws IOException {
        return post(url, data, headers, "application/json");
    }

    public static Response postJson(String url, String data) throws IOException {
        return post(url, data, webHeaders, "application/json");
    }

    public static Response post(String url, String data) throws IOException {
        return post(url, data, webHeaders);
    }


    public static byte[] readStream(InputStream inStream) throws IOException {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = inStream.read(buffer)) != -1) {
            outStream.write(buffer, 0, len);
        }
        outStream.close();
        inStream.close();
        return outStream.toByteArray();
    }

    public static byte[] uncompress(byte[] inputByte) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(inputByte.length);
        try {
            Inflater inflater = new Inflater(true);
            inflater.setInput(inputByte);
            byte[] buffer = new byte[4 * 1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                outputStream.write(buffer, 0, count);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        byte[] output = outputStream.toByteArray();
        outputStream.close();
        return output;
    }

    public static String getInfoFromCookie(String name, String cookie) {
        String[] cookies = cookie.split("; ");
        for (String i : cookies) {
            if (i.contains(name + "="))
                return i.substring(name.length() + 1);
        }
        return "";
    }

    private static void saveCookiesFromResponse(Response response) {
        List<String> newCookies = response.headers("Set-Cookie");

        //如果没有新cookies，直接返回
        if (newCookies.isEmpty()) return;
        String cookiesStr = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "");
        Cookies oldCookieSnapshot = new Cookies(cookiesStr);
        ArrayList<String> oldCookies = (cookiesStr.equals("") ? new ArrayList<>() : new ArrayList<>(Arrays.asList(cookiesStr.split("; "))));  //转list

        for (String newCookie : newCookies) {  //对每一条新cookie遍历

            Cookies cookies = new Cookies(newCookie);
            if (cookies.containsKey("Domain") && !cookies.get("Domain").endsWith("bilibili.com"))
                continue;

            int index = newCookie.indexOf("; ");
            if (index != -1) newCookie = newCookie.substring(0, index);  //如果没有分号不做处理

            index = newCookie.indexOf("=") + 1;
            if (index == 0) continue;   //如果没有等号，跳过

            String key = newCookie.substring(0, index);    //key=
            Logu.d("newCookie", newCookie);

            boolean added = false;
            for (int i = 0; i < oldCookies.size(); i++) {  //查找旧cookie表有没有
                String oldCookie = oldCookies.get(i);
                if (oldCookie.contains(key)) {
                    oldCookies.set(i, newCookie);    //有的话直接换掉
                    added = true;
                    break;
                }
            }
            if (!added) {
                oldCookies.add(newCookie);  //没有就加项
            }
        }

        StringBuilder setCookies = new StringBuilder();
        for (String setCookie : oldCookies) {
            setCookies.append(setCookie).append("; ");
        }
        //如果一次setCookies都没有，就不要存了， 因为是个空字符串
        if (setCookies.length() >= 2) {
            String updatedCookies = setCookies.substring(0, setCookies.length() - 2);
            Logu.d("save-result", updatedCookies);
            SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, updatedCookies);
            clearRiskCacheIfCookieIdentityChanged(oldCookieSnapshot, new Cookies(updatedCookies), "set-cookie");
            refreshHeaders();
        }
    }

    /**
     * 存储单个Cookie
     *
     * @param key 键
     * @param val 值
     */
    public static void putCookie(String key, String val) {
        synchronized (NetWorkUtil.class) {
            Cookies oldCookieSnapshot = new Cookies(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
            Cookies cookies = new Cookies(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
            cookies.set(key, val);
            SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies.toString());
            clearRiskCacheIfCookieIdentityChanged(oldCookieSnapshot, cookies, "put-cookie:" + key);
            refreshHeaders();
        }
    }

    /**
     * 存储Cookies（覆盖写入）
     *
     * @param cookies cookies
     */
    public static void setCookies(Cookies cookies) {
        synchronized (NetWorkUtil.class) {
            Cookies oldCookieSnapshot = new Cookies(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
            SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies.toString());
            clearRiskCacheIfCookieIdentityChanged(oldCookieSnapshot, cookies, "set-cookies");
            refreshHeaders();
        }
    }

    /**
     * 获取存储的Cookies
     *
     * @return 存储的Cookies
     */
    public static Cookies getCookies() {
        synchronized (NetWorkUtil.class) {
            return new Cookies(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
        }
    }

    public static final String USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.95 Safari/537.36";
    public static final ArrayList<String> webHeaders = new ArrayList<>() {{
        add("Cookie");
        add(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));

        add("Origin");
        add("https://www.bilibili.com");

        add("Referer");
        add("https://www.bilibili.com/");

        add("User-Agent");
        add(USER_AGENT_WEB);

        add("Sec-Ch-Ua");
        add("\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"");

        add("Sec-Ch-Ua-Platform");
        add("\"Windows\"");

        add("Sec-Ch-Ua-Mobile");
        add("?0");
    }};

    public static void refreshHeaders() {
        webHeaders.set(1, SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
    }

    private static void clearRiskCacheIfCookieIdentityChanged(Cookies oldCookies, Cookies newCookies, String reason) {
        if (isRiskIdentityCookieChanged(oldCookies, newCookies)) {
            CookiesApi.clearProcessRiskActiveCache(reason);
        }
    }

    private static boolean isRiskIdentityCookieChanged(Cookies oldCookies, Cookies newCookies) {
        String[] keys = {
                "SESSDATA",
                "DedeUserID",
                "DedeUserID__ckMd5",
                "bili_jct",
                "buvid3",
                "buvid4"
        };
        for (String key : keys) {
            String oldValue = oldCookies == null ? null : oldCookies.get(key);
            String newValue = newCookies == null ? null : newCookies.get(key);
            if (oldValue == null ? newValue != null : !oldValue.equals(newValue)) {
                return true;
            }
        }
        return false;
    }

    public static ArrayList<String> getWebHeadersSnapshot() {
        return new ArrayList<>(webHeaders);
    }

    public static void addHeaders(Request.Builder requestBuilder, List<String> headers) {
        ArrayList<String> stableHeaders = new ArrayList<>(headers);
        for (int i = 0; i + 1 < stableHeaders.size(); i += 2) {
            requestBuilder.addHeader(stableHeaders.get(i), stableHeaders.get(i + 1));
        }
    }

    public static class FormData {
        private final Map<String, String> data;
        private boolean isUrlParam;

        public FormData() {
            data = new HashMap<>();
        }

        public FormData remove(String key) {
            data.remove(key);
            return this;
        }

        public FormData put(String key, Object value) {
            data.put(key, String.valueOf(value));
            return this;
        }

        public FormData setUrlParam(boolean isUrlParam) {
            this.isUrlParam = isUrlParam;
            return this;
        }

        @NonNull
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();

            if (isUrlParam) sb.append("?");

            try {
                for (String key : data.keySet()) {
                    if (sb.length() > (isUrlParam ? 1 : 0)) {
                        sb.append("&");
                    }
                    sb.append(URLEncoder.encode(key, "UTF-8"));
                    sb.append("=");
                    sb.append(URLEncoder.encode(data.get(key), "UTF-8"));
                }
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }

            return sb.toString();
        }
    }

    public interface RedirectHandler {
        void handleRedirect(String location);
    }

    private static class CookieSaveInterceptor implements Interceptor {
        @NonNull
        @Override
        public Response intercept(Chain chain) throws IOException {
            Response response = chain.proceed(chain.request());
            saveCookiesFromResponse(response);
            return response;
        }
    }

    private static class RedirectInterceptor implements Interceptor {
        @NonNull
        @Override
        public Response intercept(@NonNull Chain chain) throws IOException {
            Request request = chain.request();
            Response response = chain.proceed(request);
            RedirectHandler handler;
            String location = response.header("Location");
            boolean isSslRedirect = false;
            try {
                isSslRedirect = location != null
                        && !request.isHttps()
                        && new URI(location).getScheme().equalsIgnoreCase("https")
                        && request.url().host().equalsIgnoreCase(new URI(location).getHost());
            } catch (URISyntaxException ignored) {
            }

            if (response.isRedirect() && location != null) {
                if (request.url().host().equals("b23.tv") && !isSslRedirect && (handler = request.tag(RedirectHandler.class)) != null) {
                    handler.handleRedirect(location);
                } else {
                    Request newRequest = request.newBuilder().url(location).build();
                    response.close();
                    return chain.proceed(newRequest);
                }
            }
            return response;
        }
    }

}
