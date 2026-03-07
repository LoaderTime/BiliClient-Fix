package com.RobinNotBad.BiliClient.helper;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@GlideModule
public class CustomGlideModule extends AppGlideModule {

    /**
     * Glide 磁盘缓存大小（避免默认缓存过大）。
     * 取值偏保守，以兼容低存储设备。
     */
    private static final int DISK_CACHE_SIZE_BYTES = 8 * 1024 * 1024;

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, DISK_CACHE_SIZE_BYTES));
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        OkHttpClient.Builder builder = NetWorkUtil.setOkHttpSsl(new OkHttpClient.Builder());
        builder.addInterceptor(chain -> {
            ArrayList<String> headers = NetWorkUtil.getWebHeadersSnapshot();
            Request.Builder requestBuilder = chain.request().newBuilder();
            NetWorkUtil.addHeaders(requestBuilder, headers);
            return chain.proceed(requestBuilder.build());
        });

        // 防止弱网/异常返回导致“极小图片(如 5x5) 被写入磁盘缓存”，从而滚动复用时稳定变糊。
        // 仅对我们拼接的 @0e_* 压缩请求生效，避免误伤站内小图标。
        builder.addInterceptor(chain -> {
            Request req = chain.request();
            String url = req.url().toString();

            Response resp = chain.proceed(req);

            try {
                // 只校验 BFS 图片 + 我们的压缩参数（专栏正文图用）
                boolean isBfs = url.contains(".hdslb.com/bfs/") || url.contains("hdslb.com/bfs/");
                boolean isCompressed = url.contains("@0e_");
                if (!isBfs || !isCompressed) {
                    return resp;
                }

                // 响应体为空直接返回
                if (resp.body() == null) {
                    return resp;
                }

                String contentType = resp.header("Content-Type", "");
                long len = resp.body().contentLength();

                // 非 image/* 直接视为失败（避免 HTML/JSON 被当图片缓存）
                if (contentType != null && !contentType.isEmpty()) {
                    String lower = contentType.toLowerCase();
                    if (!lower.startsWith("image/")) {
                        resp.close();
                        throw new IOException("Non-image response for image url, contentType=" + contentType);
                    }
                }

                // 对极小响应做拒绝：5x5 webp 通常 content-length 很小。
                // 阈值取保守值（2KB），且仅对 @0e_ 压缩请求生效。
                if (len > 0 && len < 2048) {
                    Logu.w("ArticleImage", "Reject tiny image response: len=" + len + ", url=" + url);
                    resp.close();
                    throw new IOException("Tiny image response rejected, len=" + len);
                }

                // 某些场景 content-length 可能为 -1（chunked），此时用 peekBody 再兜底一次。
                if (len < 0) {
                    try {
                        long peekBytes = resp.peekBody(2048).bytes().length;
                        if (peekBytes > 0 && peekBytes < 2048) {
                            Logu.w("ArticleImage", "Reject tiny image response(peek): len=-1, peek=" + peekBytes + ", url=" + url);
                            resp.close();
                            throw new IOException("Tiny image response rejected(peek), peek=" + peekBytes);
                        }
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception ignored) {
                    }
                }
            } catch (IOException e) {
                // 继续抛出给 Glide，触发 error() 回退逻辑，并阻止写入缓存。
                throw e;
            } catch (Exception ignored) {
                // 不影响正常加载
            }

            return resp;
        });

        // 修复 Android 4.x 上的 OkHttp 响应头解析兼容性问题
        // 在 Android 4.x 上，某些响应头值可能导致类型转换异常
        if (Build.VERSION.SDK_INT < 21) {
            builder.addInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                // 重新构建响应头，确保所有值都是字符串类型
                Headers.Builder newHeadersBuilder = new Headers.Builder();
                for (String name : response.headers().names()) {
                    String value = response.header(name);
                    if (value != null) {
                        newHeadersBuilder.add(name, value);
                    }
                }
                return response.newBuilder()
                        .headers(newHeadersBuilder.build())
                        .build();
            });
        }

        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(builder
                .dns(new NetWorkUtil.Inet4Selector())
                .pingInterval(8, TimeUnit.SECONDS)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(16, TimeUnit.SECONDS).build()));
    }
}
