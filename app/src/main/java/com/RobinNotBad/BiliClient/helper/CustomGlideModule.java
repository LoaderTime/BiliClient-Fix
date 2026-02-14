package com.RobinNotBad.BiliClient.helper;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@GlideModule
public class CustomGlideModule extends AppGlideModule {
    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        OkHttpClient.Builder builder = NetWorkUtil.setOkHttpSsl(new OkHttpClient.Builder());
        builder.addInterceptor(chain -> {
            ArrayList<String> headers = NetWorkUtil.getWebHeadersSnapshot();
            Request.Builder requestBuilder = chain.request().newBuilder();
            NetWorkUtil.addHeaders(requestBuilder, headers);
            return chain.proceed(requestBuilder.build());
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
