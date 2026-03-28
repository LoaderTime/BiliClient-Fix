package com.RobinNotBad.BiliClient.adapter.article;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.utils.widget.ImageFilterView;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.ImageViewerActivity;
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity;
import com.RobinNotBad.BiliClient.api.ArticleApi;
import com.RobinNotBad.BiliClient.model.Opus;
import com.RobinNotBad.BiliClient.model.OpusParagraph;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

//文章内容Adapter by RobinNotBad

public class OpusContentAdapter extends RecyclerView.Adapter<OpusContentAdapter.ArticleLineHolder> {

    /**
     * 版本号：用于让历史的“坏缓存”（例如 decoded=5x5）失效。
     * 每次修复缓存相关问题时递增。
     */
    private static final String ARTICLE_IMAGE_SIGNATURE = "ArticleImageFix_v5";

    /**
     * 记录普通图片请求的“刷新版本号”。
     *
     * key: 请求 URL（通常是 @0e_25q_512w.webp）
     * value: 已触发的刷新版本
     *
     * 目的：
     * - 避免使用固定的 :cmp:refresh 签名导致“坏刷新缓存”再次被命中
     * - 每次检测到 tiny decoded 时递增版本号，强制绕过旧缓存重新拉取
     */
    private static final Map<String, Integer> REFRESH_IMAGE_REVISIONS = new ConcurrentHashMap<>();

    /**
     * 普通图片在多次 refresh 仍返回 tiny 图后，临时切换到 baseUrl 原图链路。
     *
     * 注意：
     * - 这不是早前的“直接黑名单强制 baseUrl”逻辑；
     * - 只有在压缩图多次刷新仍失败后，才在当前进程内对该 requestUrl 启用原图兜底。
     */
    private static final Set<String> BASE_FALLBACK_REQUESTS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final int MAX_REFRESH_RETRY = 3;

    final Activity context;
    final Opus article;
    final OpusParagraph[] paragraphs;

    private int coinAdd = 0;

    public OpusContentAdapter(Activity context, Opus article) {
        this.context = context;
        this.article = article;
        this.paragraphs = article.paragraphs;
    }

    private OpusParagraph.ImageContent asImageContent(Object content) {
        if (content instanceof OpusParagraph.ImageContent) {
            return (OpusParagraph.ImageContent) content;
        }
        if (content instanceof String[]) {
            return new OpusParagraph.ImageContent((String[]) content);
        }
        return null;
    }

    @NonNull
    @Override
    public ArticleLineHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view;
        switch (viewType) { // -1=头，0=文本，1=图片
            case -1:
                view = LayoutInflater.from(this.context).inflate(R.layout.cell_article_head, parent, false);
                break;
            case -2:
                view = LayoutInflater.from(this.context).inflate(R.layout.cell_article_end, parent, false);
                break;
            case OpusParagraph.TYPE_PIC:
            case OpusParagraph.TYPE_DIVIDER:
                view = LayoutInflater.from(this.context).inflate(R.layout.cell_article_image, parent, false);
                break;
            case OpusParagraph.TYPE_ARTICLE:
                view = LayoutInflater.from(this.context).inflate(R.layout.cell_article_list, parent, false);
                break;
            case OpusParagraph.TYPE_VIDEO:
                view = LayoutInflater.from(this.context).inflate(R.layout.cell_dynamic_video, parent, false);
                break;
            case OpusParagraph.TYPE_DYNAMIC:
                view = LayoutInflater.from(this.context).inflate(R.layout.cell_dynamic_child, parent, false);
                break;
            case OpusParagraph.TYPE_CODE:
                view = LayoutInflater.from(this.context).inflate(R.layout.cell_article_code, parent, false);
                break;
            case OpusParagraph.TYPE_TEXT:
            case OpusParagraph.TYPE_TEXT_BLOCKQUOTE:
            case OpusParagraph.TYPE_TEXT_OPUS:
            case OpusParagraph.TYPE_LIST:
            default:
                view = LayoutInflater.from(this.context).inflate(R.layout.cell_article_textview, parent, false);
                break;
        }
        return new ArticleLineHolder(view);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull ArticleLineHolder holder, int position) {
        if (position < 0)
            return;
        int viewType = getItemViewType(position);
        int realPosition = position - 1; // 头部占了position 0
        // 只对内容段落检查越界，不影响头部(-1)和尾部(-2)
        if (viewType >= 0 && paragraphs != null && realPosition >= 0 && realPosition >= paragraphs.length)
            return;

        switch (viewType) {
            case OpusParagraph.TYPE_PIC:
            case OpusParagraph.TYPE_DIVIDER:
                if (realPosition < 0 || realPosition >= paragraphs.length)
                    break;
                ImageFilterView imageView = holder.itemView.findViewById(R.id.imageView);
                TextView imageCount = holder.itemView.findViewById(R.id.imageCount);

                OpusParagraph.ImageContent imageContent = asImageContent(paragraphs[realPosition].content);
                if (imageContent != null) {
                    String[] urls = imageContent.urls;
                    int length = urls != null ? urls.length : 0;
                    if (length > 0 && urls[0] != null) {
                        boolean lineImage = imageContent.lineImage;
                        String lineKind = imageContent.lineKind;
                        imageView.setRound(lineImage ? 0f : context.getResources().getDimension(R.dimen.card_round));
                        String rawUrl = urls[0];
                        String baseUrl = GlideUtil.stripBfsTransform(rawUrl);
                        String requestUrl = lineImage
                                ? GlideUtil.buildLineRequestUrl(rawUrl, lineKind)
                                : GlideUtil.buildRequestUrl(rawUrl);
                        String fallbackUrl = lineImage
                                ? GlideUtil.buildLineFallbackUrl(rawUrl, lineKind)
                                : baseUrl;

                        final int expectedWidth = imageContent.width;
                        final int expectedHeight = imageContent.height;
                        boolean baseFallbackVariant = !lineImage
                                && requestUrl != null && !requestUrl.isEmpty()
                                && BASE_FALLBACK_REQUESTS.contains(requestUrl)
                                && baseUrl != null && !baseUrl.isEmpty();
                        int refreshRevision = !lineImage && !baseFallbackVariant && requestUrl != null && !requestUrl.isEmpty()
                                ? Math.max(0, REFRESH_IMAGE_REVISIONS.getOrDefault(requestUrl, 0))
                                : 0;
                        boolean refreshVariant = refreshRevision > 0;
                        final String effectiveUrl = baseFallbackVariant ? baseUrl : requestUrl;

                        if (!effectiveUrl.equals(holder.lastImageUrl)) {
                            holder.lastImageUrl = effectiveUrl;

                            // 调试日志：仅在 URL 发生变化时输出，避免 Recycler 频繁 bind 造成刷屏。
                            Logu.w("ArticleImage",
                                    "cv=" + article.id
                                            + ", pos=" + realPosition
                                            + ", type=" + (viewType == OpusParagraph.TYPE_DIVIDER ? "DIVIDER" : "PIC")
                                            + ", image_request_jpg=" + SharedPreferencesUtil.getBoolean("image_request_jpg", false)
                                            + ", lineImage=" + lineImage
                                            + ", lineKind=" + lineKind
                                            + ", expected=" + expectedWidth + "x" + expectedHeight
                                            + ", raw=" + rawUrl
                                            + ", base=" + baseUrl
                                            + ", fallback=" + fallbackUrl
                                            + ", req=" + effectiveUrl
                                            + ", baseFallbackVariant=" + baseFallbackVariant
                                            + ", refreshVariant=" + refreshVariant
                                            + ", refreshRevision=" + refreshRevision);

                            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> builder;
                            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> errorBuilder = null;
                            if (fallbackUrl != null && !fallbackUrl.isEmpty() && !fallbackUrl.equals(effectiveUrl)) {
                                errorBuilder = Glide.with(BiliTerminal.context)
                                        .asDrawable()
                                        .load(fallbackUrl)
                                        .signature(new ObjectKey(ARTICLE_IMAGE_SIGNATURE + (lineImage ? ":lineFallback" : ":base")));
                            }

                            builder = Glide.with(BiliTerminal.context)
                                    .asDrawable()
                                    .load(effectiveUrl)
                                    .placeholder(lineImage ? R.drawable.empty : R.mipmap.placeholder)
                                    .signature(new ObjectKey(ARTICLE_IMAGE_SIGNATURE
                                            + (lineImage
                                            ? ":line:" + lineKind
                                            : (baseFallbackVariant
                                            ? ":base:final"
                                            : (refreshVariant ? ":cmp:refresh:" + refreshRevision : ":cmp")))));

                            if (refreshVariant || baseFallbackVariant) {
                                builder = builder.skipMemoryCache(true);
                            }

                            if (errorBuilder != null) {
                                builder = builder.error(errorBuilder);
                            } else {
                                builder = builder.error(lineImage ? R.drawable.empty : R.mipmap.placeholder);
                            }

                            builder.listener(new RequestListener<android.graphics.drawable.Drawable>() {
                                        @Override
                                        public boolean onLoadFailed(GlideException e, Object model, Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                                            Logu.e("ArticleImage",
                                                    "FAIL cv=" + article.id
                                                            + ", pos=" + realPosition
                                                            + ", req=" + effectiveUrl
                                                            + ", err=" + (e == null ? "null" : e.getClass().getSimpleName() + ":" + e.getMessage()));
                                            return false;
                                        }

                                        @Override
                                        public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, Target<android.graphics.drawable.Drawable> target, DataSource dataSource, boolean isFirstResource) {
                                            int w = -1, h = -1;
                                            try {
                                                if (resource instanceof android.graphics.drawable.BitmapDrawable) {
                                                    android.graphics.Bitmap bm = ((android.graphics.drawable.BitmapDrawable) resource).getBitmap();
                                                    if (bm != null) {
                                                        w = bm.getWidth();
                                                        h = bm.getHeight();
                                                    }
                                                }
                                            } catch (Exception ignored) {
                                            }
                                            Logu.w("ArticleImage",
                                                    "OK cv=" + article.id
                                                            + ", pos=" + realPosition
                                                            + ", req=" + effectiveUrl
                                                            + ", lineImage=" + lineImage
                                                            + ", lineKind=" + lineKind
                                                            + ", expected=" + expectedWidth + "x" + expectedHeight
                                                            + ", decoded=" + w + "x" + h
                                                            + ", source=" + dataSource
                                                            + ", baseFallbackVariant=" + baseFallbackVariant
                                                            + ", refreshRevision=" + refreshRevision);

                                            boolean looksTiny = w > 0 && h > 0 && w <= 64 && h <= 64;
                                            boolean expectedLarge = expectedWidth > 0 && expectedHeight > 0
                                                    && expectedWidth >= 256 && expectedHeight >= 256;
                                            boolean normalLooksSuspicious = (looksTiny
                                                    || (expectedWidth > 0 && w > 0
                                                    && w < Math.max(64, Math.min(128, expectedWidth / 4)))
                                                    || (expectedHeight > 0 && h > 0
                                                    && h < Math.max(64, Math.min(128, expectedHeight / 4))))
                                                    && (expectedLarge || expectedWidth <= 0 || expectedHeight <= 0);
                                            boolean canRetryMore = refreshRevision < MAX_REFRESH_RETRY;
                                            boolean shouldRefreshRetry = !lineImage && !baseFallbackVariant && canRetryMore
                                                    && normalLooksSuspicious;
                                            boolean shouldFinalBaseFallback = !lineImage
                                                    && !baseFallbackVariant
                                                    && !canRetryMore
                                                    && normalLooksSuspicious
                                                    && baseUrl != null && !baseUrl.isEmpty();

                                            if (shouldRefreshRetry) {
                                                int nextRevision = refreshRevision + 1;
                                                REFRESH_IMAGE_REVISIONS.put(effectiveUrl, nextRevision);
                                                Logu.w("ArticleImage",
                                                        "TINY_DECODED_REFRESH cv=" + article.id
                                                                + ", pos=" + realPosition
                                                                + ", expected=" + expectedWidth + "x" + expectedHeight
                                                                + ", tiny=" + w + "x" + h
                                                                + ", retryReq=" + effectiveUrl
                                                                + ", nextRevision=" + nextRevision);

                                                // 注意：Glide 不允许在 RequestListener 回调里直接再次 into()。
                                                // 这里 post 到主线程消息队列下一轮再触发“刷新签名重试”，避免 CallbackException。
                                                final String currentLoadKey = effectiveUrl;
                                                final int currentNextRevision = nextRevision;
                                                holder.lastImageUrl = currentLoadKey + "#refresh-pending:" + currentNextRevision;
                                                imageView.post(() -> {
                                                    try {
                                                        if (!(currentLoadKey + "#refresh-pending:" + currentNextRevision).equals(holder.lastImageUrl)) {
                                                            return;
                                                        }
                                                        int adapterPosition = holder.getAdapterPosition();
                                                        if (adapterPosition == RecyclerView.NO_POSITION) {
                                                            return;
                                                        }
                                                        holder.lastImageUrl = null;
                                                        notifyItemChanged(adapterPosition);
                                                    } catch (Exception e) {
                                                        Logu.e("ArticleImage", "POST_FALLBACK_FAIL cv=" + article.id
                                                                + ", pos=" + realPosition
                                                                + ", req=" + currentLoadKey
                                                                + ", err=" + e.getMessage());
                                                    }
                                                });
                                                return true;
                                            }

                                            if (shouldFinalBaseFallback) {
                                                BASE_FALLBACK_REQUESTS.add(requestUrl);
                                                Logu.w("ArticleImage",
                                                        "TINY_DECODED_BASE_FALLBACK cv=" + article.id
                                                                + ", pos=" + realPosition
                                                                + ", expected=" + expectedWidth + "x" + expectedHeight
                                                                + ", tiny=" + w + "x" + h
                                                                + ", fallbackBase=" + baseUrl);

                                                holder.lastImageUrl = requestUrl + "#base-pending";
                                                imageView.post(() -> {
                                                    try {
                                                        if (!(requestUrl + "#base-pending").equals(holder.lastImageUrl)) {
                                                            return;
                                                        }
                                                        int adapterPosition = holder.getAdapterPosition();
                                                        if (adapterPosition == RecyclerView.NO_POSITION) {
                                                            return;
                                                        }
                                                        holder.lastImageUrl = null;
                                                        notifyItemChanged(adapterPosition);
                                                    } catch (Exception e) {
                                                        Logu.e("ArticleImage", "POST_BASE_FALLBACK_FAIL cv=" + article.id
                                                                + ", pos=" + realPosition
                                                                + ", req=" + requestUrl
                                                                + ", err=" + e.getMessage());
                                                    }
                                                });
                                                return true;
                                            }

                                            if (baseFallbackVariant && looksTiny) {
                                                Logu.w("ArticleImage",
                                                        "BASE_FALLBACK_STILL_TINY_KEEP_OLD cv=" + article.id
                                                                + ", pos=" + realPosition
                                                                + ", tiny=" + w + "x" + h
                                                                + ", base=" + baseUrl);
                                                return true;
                                            }
                                            return false;
                                        }
                                    })
                                    .transition(GlideUtil.getTransitionOptions());

                            builder = builder.diskCacheStrategy((refreshVariant || baseFallbackVariant)
                                    ? DiskCacheStrategy.NONE
                                    : DiskCacheStrategy.DATA);
                            builder.into(imageView);
                        }

                        if (lineImage) {
                            imageView.setOnClickListener(null);
                            imageView.setClickable(false);
                            imageView.setFocusable(false);
                        } else {
                            imageView.setClickable(true);
                            imageView.setFocusable(true);
                            imageView.setOnClickListener(view -> {
                                Intent intent = new Intent();
                                intent.setClass(context, ImageViewerActivity.class);
                                intent.putExtra("imageList", new ArrayList<>(Arrays.asList(urls)));
                                context.startActivity(intent);
                            });
                        }

                        if (!lineImage && length > 1)
                            imageCount.setText(String.format(Locale.CHINA, "共%d张图片", length));
                        imageCount.setVisibility(!lineImage && length > 1 ? View.VISIBLE : View.GONE);
                    }
                }
                break;

            case -1:
                TextView title = holder.itemView.findViewById(R.id.text_title);
                ImageView topImage = holder.itemView.findViewById(R.id.topImage);
                TextView topCount = holder.itemView.findViewById(R.id.topCount);
                ImageView upIcon = holder.itemView.findViewById(R.id.upInfo_Icon); // 头
                TextView upName = holder.itemView.findViewById(R.id.upInfo_Name);
                MaterialCardView upCard = holder.itemView.findViewById(R.id.upInfo);

                if (!TextUtils.isEmpty(article.title)) {
                    title.setVisibility(View.VISIBLE);
                    title.setText(article.title);
                    StringUtil.setCopy(title);
                } else
                    title.setVisibility(View.GONE);

                if (!TextUtils.isEmpty(article.cover)) {
                    String rawCover = article.cover;
                    String baseCover = GlideUtil.stripBfsTransform(rawCover);
                    String coverUrl = GlideUtil.buildRequestUrl(rawCover);

                    if (!coverUrl.equals(holder.lastTopImageUrl)) {
                        holder.lastTopImageUrl = coverUrl;

                        Logu.w("ArticleImage",
                                "cv=" + article.id
                                        + ", pos=-1"
                                        + ", type=TOP_COVER"
                                        + ", image_request_jpg=" + SharedPreferencesUtil.getBoolean("image_request_jpg", false)
                                        + ", raw=" + rawCover
                                        + ", base=" + baseCover
                                        + ", req=" + coverUrl);

                        Glide.with(BiliTerminal.context).asDrawable().load(coverUrl)
                                .placeholder(R.mipmap.placeholder)
                                .error(Glide.with(BiliTerminal.context).asDrawable().load(baseCover))
                                .transition(GlideUtil.getTransitionOptions())
                                .apply(RequestOptions.bitmapTransform(new RoundedCorners(ToolsUtil.dp2px(4))))
                                .format(DecodeFormat.PREFER_RGB_565)
                                .diskCacheStrategy(DiskCacheStrategy.DATA)
                                .into(topImage);
                    }
                    topCount.setVisibility(View.GONE);
                } else if (article.topImages != null && article.topImages.size() > 0) {
                    String rawTop = article.topImages.get(0);
                    String baseTop = GlideUtil.stripBfsTransform(rawTop);
                    String firstImageUrl = GlideUtil.buildRequestUrl(rawTop);

                    if (!firstImageUrl.equals(holder.lastTopImageUrl)) {
                        holder.lastTopImageUrl = firstImageUrl;

                        Logu.w("ArticleImage",
                                "cv=" + article.id
                                        + ", pos=-1"
                                        + ", type=TOP_IMAGES[0]"
                                        + ", image_request_jpg=" + SharedPreferencesUtil.getBoolean("image_request_jpg", false)
                                        + ", raw=" + rawTop
                                        + ", base=" + baseTop
                                        + ", req=" + firstImageUrl);

                        Glide.with(BiliTerminal.context).asDrawable().load(firstImageUrl)
                                .placeholder(R.mipmap.placeholder)
                                .error(Glide.with(BiliTerminal.context).asDrawable().load(baseTop))
                                .transition(GlideUtil.getTransitionOptions())
                                .apply(RequestOptions.bitmapTransform(new RoundedCorners(ToolsUtil.dp2px(4))))
                                .format(DecodeFormat.PREFER_RGB_565)
                                .diskCacheStrategy(DiskCacheStrategy.DATA)
                                .into(topImage);
                    }
                    topImage.setOnClickListener(view -> {
                        Intent intent = new Intent();
                        intent.setClass(context, ImageViewerActivity.class);
                        intent.putExtra("imageList", article.topImages);
                        context.startActivity(intent);
                    });
                    if (article.topImages.size() > 1) {
                        topCount.setText(String.format(Locale.CHINA, "共%d张图片", article.topImages.size()));
                        topCount.setVisibility(View.VISIBLE);
                    } else
                        topCount.setVisibility(View.GONE);
                } else
                    holder.itemView.findViewById(R.id.topImageLayout).setVisibility(View.GONE);

                upName.setText(article.upInfo.name);
                String avatarUrl = GlideUtil.url(article.upInfo.avatar);
                if (!avatarUrl.equals(holder.lastAvatarUrl)) {
                    holder.lastAvatarUrl = avatarUrl;
                    Glide.with(BiliTerminal.context).asDrawable().load(avatarUrl)
                            .placeholder(R.mipmap.akari)
                            .transition(GlideUtil.getTransitionOptions())
                            .apply(RequestOptions.circleCropTransform())
                            .diskCacheStrategy(DiskCacheStrategy.DATA)
                            .into(upIcon);
                }
                upCard.setOnClickListener(view1 -> {
                    Intent intent = new Intent();
                    intent.setClass(context, UserInfoActivity.class);
                    intent.putExtra("mid", article.upInfo.mid);
                    context.startActivity(intent);
                });

                break;

            case -2:
                TextView viewCount = holder.itemView.findViewById(R.id.viewCount);
                TextView timeText = holder.itemView.findViewById(R.id.timeText);
                TextView cvidText = holder.itemView.findViewById(R.id.cvidText);
                cvidText.setText("cv" + article.id/* + " | " + article.wordCount + "字" */);
                StringUtil.setCopy(cvidText, "cv" + article.id);
                viewCount.setText(StringUtil.toWan(article.stats.view) + "阅读");
                timeText.setText(article.pubTime);

                ImageButton like = holder.itemView.findViewById(R.id.btn_like);
                ImageButton coin = holder.itemView.findViewById(R.id.btn_coin);
                TextView likeLabel = holder.itemView.findViewById(R.id.like_label);
                TextView coinLabel = holder.itemView.findViewById(R.id.coin_label);
                TextView favLabel = holder.itemView.findViewById(R.id.fav_label);
                ImageButton fav = holder.itemView.findViewById(R.id.btn_fav);

                likeLabel.setText(StringUtil.toWan(article.stats.like));
                coinLabel.setText(StringUtil.toWan(article.stats.coin));
                favLabel.setText(StringUtil.toWan(article.stats.favorite));

                like.setOnClickListener(view1 -> CenterThreadPool.run(() -> {
                    try {
                        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) {
                            context.runOnUiThread(() -> MsgUtil.showMsg("还没有登录喵~"));
                            return;
                        }
                        int result = ArticleApi.like(article.id, !article.stats.liked);
                        if (result == 0) {
                            article.stats.liked = !article.stats.liked;
                            context.runOnUiThread(() -> {
                                MsgUtil.showMsg((article.stats.liked ? "点赞成功" : "取消成功"));

                                if (article.stats.liked)
                                    likeLabel.setText(StringUtil.toWan(++article.stats.like));
                                else
                                    likeLabel.setText(StringUtil.toWan(--article.stats.like));
                                like.setImageResource(
                                        article.stats.liked ? R.drawable.icon_like_1 : R.drawable.icon_like_0);
                            });
                        } else {
                            context.runOnUiThread(() -> MsgUtil.showMsg("操作失败：" + result));
                        }
                    } catch (Exception e) {
                        context.runOnUiThread(() -> MsgUtil.err(e));
                    }
                }));

                coin.setOnClickListener(view1 -> CenterThreadPool.run(() -> {
                    if (article.stats.coined < article.stats.coin_limit) {
                        try {
                            if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) {
                                context.runOnUiThread(() -> MsgUtil.showMsg("还没有登录喵~"));
                                return;
                            }
                            int result = ArticleApi.addCoin(article.id, article.upInfo.mid, 1);
                            if (result == 0) {
                                if (++coinAdd <= 2)
                                    article.stats.coined++;
                                context.runOnUiThread(() -> {
                                    MsgUtil.showMsg("投币成功！");
                                    coinLabel.setText(StringUtil.toWan(++article.stats.coin));
                                    coin.setImageResource(R.drawable.icon_coin_1);
                                });
                            } else {
                                String msg = "投币失败：" + result;
                                if (result == 34002) {
                                    msg = "不能给自己投币哦！";
                                }
                                String finalMsg = msg;
                                context.runOnUiThread(() -> MsgUtil.showMsg(finalMsg));
                            }
                        } catch (Exception e) {
                            context.runOnUiThread(() -> MsgUtil.err(e));
                        }
                    } else {
                        context.runOnUiThread(() -> MsgUtil.showMsg("投币数量到达上限"));
                    }
                }));

                fav.setOnClickListener(view1 -> CenterThreadPool.run(() -> {
                    try {
                        if (article.stats.favoured) {
                            if (ArticleApi.delFavorite(article.id) == 0) {
                                context.runOnUiThread(() -> fav.setImageResource(R.drawable.icon_fav_0));
                                article.stats.favorite--;
                            }
                        } else {
                            if (ArticleApi.favorite(article.id) == 0) {
                                context.runOnUiThread(() -> fav.setImageResource(R.drawable.icon_fav_1));
                                article.stats.favorite++;
                            }
                        }
                        article.stats.favoured = !article.stats.favoured;
                        context.runOnUiThread(() -> {
                            favLabel.setText(StringUtil.toWan(article.stats.favorite));
                            MsgUtil.showMsg("操作成功~");
                        });
                    } catch (Exception e) {
                        context.runOnUiThread(() -> MsgUtil.err(e));
                    }
                }));

                if (article.type == Opus.TYPE_DYNAMIC) {
                    holder.itemView.findViewById(R.id.viewIcon).setVisibility(View.GONE);
                    viewCount.setVisibility(View.GONE);
                    holder.itemView.findViewById(R.id.cvidIcon).setVisibility(View.GONE);
                    cvidText.setVisibility(View.GONE);
                }
                break;

            case OpusParagraph.TYPE_VIDEO:

                break;

            case OpusParagraph.TYPE_ARTICLE:

                break;

            case OpusParagraph.TYPE_CODE:
                if (realPosition >= 0 && realPosition < paragraphs.length && paragraphs[realPosition].content != null) {
                    TextView codeText = holder.itemView.findViewById(R.id.codeText);
                    Object content = paragraphs[realPosition].content;
                    if (content instanceof OpusParagraph.CodeBlock) {
                        OpusParagraph.CodeBlock codeBlock = (OpusParagraph.CodeBlock) content;
                        // 不做高亮，避免引入新依赖并确保 Android 4.4 兼容；
                        // 仅保证内容可复制 + 容器背景色固定 #2F3034。
                        codeText.setText(codeBlock.content);
                        StringUtil.setCopy(codeText);
                    } else {
                        codeText.setText(String.valueOf(content));
                        StringUtil.setCopy(codeText);
                    }
                }
                break;

            case OpusParagraph.TYPE_TEXT:
            case OpusParagraph.TYPE_TEXT_BLOCKQUOTE:
            case OpusParagraph.TYPE_TEXT_OPUS:
            case OpusParagraph.TYPE_LIST:
            default:
                if (realPosition >= 0 && realPosition < paragraphs.length && paragraphs[realPosition].content != null) {
                    TextView textView = holder.itemView.findViewById(R.id.textView);
                    if (paragraphs[realPosition].content instanceof CharSequence) {
                        textView.setText((CharSequence) paragraphs[realPosition].content);
                        StringUtil.setCopy(textView);
                        StringUtil.setLink(textView);
                    }
                }
                break;
        }
    }

    @Override
    public int getItemCount() {
        if (paragraphs == null)
            return 2;
        return paragraphs.length + 2;
    }

    @Override
    public void onViewRecycled(@NonNull ArticleLineHolder holder) {
        holder.lastTopImageUrl = null;
        holder.lastAvatarUrl = null;
        holder.lastImageUrl = null;
        holder.lastDividerUrl = null;
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemViewType(int position) {
        if (paragraphs == null)
            return -1;
        if (position == 0)
            return -1;
        else if (position == paragraphs.length + 1)
            return -2;
        else {
            int realPosition = position - 1;
            if (realPosition >= 0 && realPosition < paragraphs.length) {
                return paragraphs[realPosition].type;
            }
            return -1;
        }
    }

    public static class ArticleLineHolder extends RecyclerView.ViewHolder {
        String lastTopImageUrl;
        String lastAvatarUrl;
        String lastImageUrl;
        String lastDividerUrl;

        public ArticleLineHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}
