package com.RobinNotBad.BiliClient.util;


import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.TransitionOptions;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory;

public class GlideUtil {
    public static final int QUALITY_HIGH = 80;
    public static final int QUALITY_LOW = 25;
    public static final int MAX_W_HIGH = 1024;
    public static final int MAX_W_LOW = 512;

    /**
     * 移除 B 站 BFS 图片 URL 自带的 "@..." 变换参数。
     * 
     * 背景：部分接口/页面会返回已经带缩略参数的 URL（例如 @200w_200h...）。
     * 现有逻辑在 url() 中遇到 '@' 会直接 return，导致这类图片永远锁死在更小的缩略图上，
     * 表现为“固定模糊、等多久都不会变清晰”。
     * 
     * 注意：
     * - 仅对 bilibili hdslb 的 bfs 图片域名生效，避免误伤其他链接。
     * - gif/非 http 链接不处理。
     */
    public static String stripBfsTransform(String url) {
        if (url == null || url.isEmpty()) return "";
        // 统一协议，避免 //i0.hdslb.com 这种情况
        String u = url;
        if (u.startsWith("//")) u = "https:" + u;

        // 仅处理 BFS 图片（避免误删 afdiancdn / 其他带 @ 的链接）
        String lower = u.toLowerCase();
        boolean isBfs = (lower.contains(".hdslb.com/bfs/") || lower.contains("hdslb.com/bfs/"));
        if (!isBfs) return u;

        // gif 不做处理（gif 经常带参数）
        if (lower.endsWith(".gif")) return u;

        int at = u.indexOf('@');
        if (at <= 0) return u;
        // '@' 之前即为原始资源地址
        return u.substring(0, at);
    }

    /**
     * 生成用于请求的图片 URL。
     * - 先剥离 BFS 自带缩略参数（如 @200w），避免锁死在小图。
     * - 再按用户设置生成 webp/jpeg 与 25q/512w 参数。
     */
    public static String buildRequestUrl(String url) {
        String base = stripBfsTransform(url);
        return url(base);
    }

    /**
     * 专栏横线类图片使用单独的请求策略。
     * 
     * 背景：
     * - 普通正文图适合走 @0e_25q_512w.webp
     * - 横线图（如 opus-para-line / cut-off-1）文件极小，且 B 站返回策略与普通图不同，
     *   强行拼 @0e 可能返回异常极小响应，导致横线消失。
     */
    public static String buildLineRequestUrl(String url, String lineKind) {
        String base = stripBfsTransform(url);
        if (base == null || base.isEmpty()) return "";

        // cut-off-1 这类横线在网页直链中更接近 @progressive.webp
        if ("cut-off-1".equals(lineKind)) {
            if (base.endsWith("@progressive.webp")) return base;
            return base + "@progressive.webp";
        }

        // 其它横线类（如 opus-para-line / api-divider）优先原图
        return base;
    }

    public static String buildLineFallbackUrl(String url, String lineKind) {
        String base = stripBfsTransform(url);
        if (base == null || base.isEmpty()) return "";
        // progressive 失败后统一回退原图
        return base;
    }

    public static String url(String url) {
        if (url == null || url.isEmpty()) return "";
        if (!url.startsWith("http") || url.endsWith("gif") || url.contains("@") || url.contains("afdian"))
            return url;
        if (SharedPreferencesUtil.getBoolean("image_request_jpg", false)) {
            if (url.endsWith("jpeg") || url.endsWith("jpg")) return url;
            return url + "@0e_"
                    + QUALITY_LOW + "q_"
                    //+ MAX_H_LOW + "h_"
                    + MAX_W_LOW + "w.jpeg";
        } else {
            if (url.endsWith("webp")) return url;
            return url + "@0e_"
                    + QUALITY_LOW + "q_"
                    //+ MAX_H_LOW + "h_"
                    + MAX_W_LOW + "w.webp";
        }
    }

    public static String url_hq(String url) {
        if (url == null || url.isEmpty()) return "";
        if (!url.startsWith("http") || url.endsWith("gif") || url.contains("@") || url.contains("afdiancdn.com"))
            return url;
        if (SharedPreferencesUtil.getBoolean("image_request_jpg", false)) {
            if (url.endsWith("jpeg") || url.endsWith("jpg")) return url;
            return url + "@0e_"
                    + QUALITY_HIGH + "q_"
                    //+ MAX_H_HIGH + "h_"
                    + MAX_W_HIGH + "w.jpeg";
        } else {
            if (url.endsWith("webp")) return url;
            return url + "@0e_"
                    + QUALITY_HIGH + "q_"
                    //+ MAX_H_HIGH + "h_"
                    + MAX_W_HIGH + "w.webp";
        }
    }

    public static void request(ImageView view, String url, int placeholder) {
        Glide.with(view).asDrawable().load(url(url))
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .format(DecodeFormat.PREFER_RGB_565)
                .transition(GlideUtil.getTransitionOptions())
                .placeholder(placeholder)
                .into(view);
    }

    public static void requestRound(ImageView view, String url, int placeholder) {
        Glide.with(view).asDrawable().load(url(url))
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .format(DecodeFormat.PREFER_RGB_565)
                .transition(GlideUtil.getTransitionOptions())
                .placeholder(placeholder)
                .apply(RequestOptions.circleCropTransform())
                .into(view);
    }

    public static void request(ImageView view, String url, int roundCorners, int placeholder) {
        Glide.with(view).asDrawable().load(url(url))
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .format(DecodeFormat.PREFER_RGB_565)
                .transition(GlideUtil.getTransitionOptions())
                .placeholder(placeholder)
                .apply(RequestOptions.bitmapTransform(new RoundedCorners(ToolsUtil.dp2px(roundCorners))))
                .into(view);
    }

    public static TransitionOptions<?, ? super Drawable> getTransitionOptions() {
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.LOAD_TRANSITION, true)) {
            return DrawableTransitionOptions.with(new DrawableCrossFadeFactory.Builder(300).setCrossFadeEnabled(true).build());
        } else {
            return new DrawableTransitionOptions();
        }
    }
}
