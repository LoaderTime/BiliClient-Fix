package com.RobinNotBad.BiliClient.adapter.article;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
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
import com.RobinNotBad.BiliClient.model.ArticleInfo;
import com.RobinNotBad.BiliClient.model.ArticleLine;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

//文章内容Adapter by RobinNotBad

public class ArticleContentAdapter extends RecyclerView.Adapter<ArticleContentAdapter.ArticleLineHolder> {

    /**
     * 专栏正文图的缓存签名版本。
     */
    private static final String ARTICLE_IMAGE_SIGNATURE = "LegacyArticleImageFix_v1";

    /**
     * 对同一压缩图请求做“刷新签名重试”。
     */
    private static final Map<String, Integer> REFRESH_IMAGE_REVISIONS = new ConcurrentHashMap<>();

    /**
     * 当压缩图多次重试仍解码成异常小图时，退回 baseUrl 原图链路。
     */
    private static final Set<String> BASE_FALLBACK_REQUESTS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * baseUrl 兜底链路本身失败时的重试计数。
     */
    private static final Map<String, Integer> BASE_FAIL_RETRY_COUNTS = new ConcurrentHashMap<>();

    private static final int MAX_REFRESH_RETRY = 3;

    private void scheduleImageReload(ArticleLineHolder holder, View reloadAnchor, String pendingKey, String logTag, int position, String req) {
        holder.lastImageUrl = pendingKey;
        reloadAnchor.post(() -> {
            try {
                if (!pendingKey.equals(holder.lastImageUrl)) {
                    return;
                }
                int adapterPosition = holder.getAdapterPosition();
                if (adapterPosition == RecyclerView.NO_POSITION) {
                    return;
                }
                holder.lastImageUrl = null;
                notifyItemChanged(adapterPosition);
            } catch (Exception e) {
                Logu.e("ArticleImage",
                        logTag + " cv=" + articleInfo.id
                                + ", pos=" + position
                                + ", req=" + req
                                + ", err=" + e.getMessage());
            }
        });
    }

    final Activity context;
    final ArrayList<ArticleLine> article;
    final ArticleInfo articleInfo;

    private int coinAdd = 0;

    public ArticleContentAdapter(Activity context, ArticleInfo articleInfo, ArrayList<ArticleLine> article) {
        this.context = context;
        this.article = article;
        this.articleInfo = articleInfo;
    }

    @NonNull
    @Override
    public ArticleLineHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view;
        switch (viewType) { // -1=头，0=文本，1=图片
            case 1:
                view = LayoutInflater.from(this.context).inflate(R.layout.cell_article_image, parent, false);
                break;
            case -1:
                view = LayoutInflater.from(this.context).inflate(R.layout.cell_article_head, parent, false);
                break;
            case -2:
                view = LayoutInflater.from(this.context).inflate(R.layout.cell_article_end, parent, false);
                break;
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
        if (viewType >= 0 && article != null && realPosition >= 0 && realPosition >= article.size())
            return;

        switch (viewType) {
            case 1:
                if (realPosition < 0 || realPosition >= article.size())
                    break;
                ImageFilterView imageView = (ImageFilterView) holder.itemView;
                ArticleLine line = article.get(realPosition);
                if (line == null || line.content == null)
                    break;

                String rawUrl = line.content;
                String baseUrl = GlideUtil.stripBfsTransform(rawUrl);
                String requestUrl = GlideUtil.buildRequestUrl(rawUrl);
                boolean baseFallbackVariant = requestUrl != null && !requestUrl.isEmpty()
                        && BASE_FALLBACK_REQUESTS.contains(requestUrl)
                        && baseUrl != null && !baseUrl.isEmpty();
                int refreshRevision = !baseFallbackVariant && requestUrl != null && !requestUrl.isEmpty()
                        ? Math.max(0, REFRESH_IMAGE_REVISIONS.getOrDefault(requestUrl, 0))
                        : 0;
                int baseFailRetryCount = baseFallbackVariant && requestUrl != null && !requestUrl.isEmpty()
                        ? Math.max(0, BASE_FAIL_RETRY_COUNTS.getOrDefault(requestUrl, 0))
                        : 0;
                boolean refreshVariant = refreshRevision > 0;
                final String effectiveUrl = baseFallbackVariant ? baseUrl : requestUrl;

                if (!effectiveUrl.equals(holder.lastImageUrl)) {
                    holder.lastImageUrl = effectiveUrl;

                    Logu.w("ArticleImage",
                            "legacy cv=" + articleInfo.id
                                    + ", pos=" + realPosition
                                    + ", raw=" + rawUrl
                                    + ", base=" + baseUrl
                                    + ", req=" + effectiveUrl
                                    + ", baseFallbackVariant=" + baseFallbackVariant
                                    + ", refreshRevision=" + refreshRevision
                                    + ", baseFailRetryCount=" + baseFailRetryCount);

                    com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> errorBuilder = null;
                    if (baseUrl != null && !baseUrl.isEmpty() && !baseUrl.equals(effectiveUrl)) {
                        errorBuilder = Glide.with(BiliTerminal.context)
                                .asDrawable()
                                .load(baseUrl)
                                .signature(new ObjectKey(ARTICLE_IMAGE_SIGNATURE + ":base"));
                    }

                    com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> builder = Glide.with(BiliTerminal.context)
                            .asDrawable()
                            .load(effectiveUrl)
                            .placeholder(R.mipmap.placeholder)
                            .signature(new ObjectKey(ARTICLE_IMAGE_SIGNATURE
                                    + (baseFallbackVariant
                                    ? ":base:final:" + baseFailRetryCount
                                    : (refreshVariant ? ":cmp:refresh:" + refreshRevision : ":cmp"))));

                    if (refreshVariant || baseFallbackVariant) {
                        builder = builder.skipMemoryCache(true);
                    }

                    if (errorBuilder != null) {
                        builder = builder.error(errorBuilder);
                    } else {
                        builder = builder.error(R.mipmap.placeholder);
                    }

                    final String finalRequestUrl = requestUrl;
                    final String finalBaseUrl = baseUrl;
                    final int finalRefreshRevision = refreshRevision;
                    final int finalBaseFailRetryCount = baseFailRetryCount;
                    final boolean finalBaseFallbackVariant = baseFallbackVariant;
                    builder.listener(new RequestListener<android.graphics.drawable.Drawable>() {
                                @Override
                                public boolean onLoadFailed(GlideException e, Object model, Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                                    Logu.e("ArticleImage",
                                            "LEGACY_FAIL cv=" + articleInfo.id
                                                    + ", pos=" + realPosition
                                                    + ", req=" + effectiveUrl
                                                    + ", baseFallbackVariant=" + finalBaseFallbackVariant
                                                    + ", refreshRevision=" + finalRefreshRevision
                                                    + ", baseFailRetryCount=" + finalBaseFailRetryCount
                                                    + ", err=" + (e == null ? "null" : e.getClass().getSimpleName() + ":" + e.getMessage()));

                                    if (!finalBaseFallbackVariant && finalRequestUrl != null && !finalRequestUrl.isEmpty()) {
                                        if (finalRefreshRevision < MAX_REFRESH_RETRY) {
                                            int nextRevision = finalRefreshRevision + 1;
                                            REFRESH_IMAGE_REVISIONS.put(finalRequestUrl, nextRevision);
                                            Logu.w("ArticleImage",
                                                    "LEGACY_FAIL_REFRESH cv=" + articleInfo.id
                                                            + ", pos=" + realPosition
                                                            + ", req=" + finalRequestUrl
                                                            + ", nextRevision=" + nextRevision);
                                            scheduleImageReload(holder, imageView,
                                                    finalRequestUrl + "#fail-refresh-pending:" + nextRevision,
                                                    "LEGACY_POST_FAIL_REFRESH_FAIL", realPosition, finalRequestUrl);
                                            return true;
                                        }

                                        if (finalBaseUrl != null && !finalBaseUrl.isEmpty()) {
                                            BASE_FALLBACK_REQUESTS.add(finalRequestUrl);
                                            Logu.w("ArticleImage",
                                                    "LEGACY_FAIL_SWITCH_BASE cv=" + articleInfo.id
                                                            + ", pos=" + realPosition
                                                            + ", req=" + finalRequestUrl
                                                            + ", base=" + finalBaseUrl);
                                            scheduleImageReload(holder, imageView,
                                                    finalRequestUrl + "#fail-base-pending",
                                                    "LEGACY_POST_FAIL_BASE_FAIL", realPosition, finalRequestUrl);
                                            return true;
                                        }
                                    }

                                    if (finalBaseFallbackVariant && finalRequestUrl != null && !finalRequestUrl.isEmpty()) {
                                        int nextBaseRetryCount = finalBaseFailRetryCount + 1;
                                        if (nextBaseRetryCount <= MAX_REFRESH_RETRY) {
                                            BASE_FAIL_RETRY_COUNTS.put(finalRequestUrl, nextBaseRetryCount);
                                            Logu.w("ArticleImage",
                                                    "LEGACY_BASE_FAIL_RETRY cv=" + articleInfo.id
                                                            + ", pos=" + realPosition
                                                            + ", req=" + finalRequestUrl
                                                            + ", base=" + finalBaseUrl
                                                            + ", nextBaseRetryCount=" + nextBaseRetryCount);
                                            scheduleImageReload(holder, imageView,
                                                    finalRequestUrl + "#base-fail-retry-pending:" + nextBaseRetryCount,
                                                    "LEGACY_POST_BASE_RETRY_FAIL", realPosition, finalRequestUrl);
                                            return true;
                                        }
                                    }
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
                                            "LEGACY_OK cv=" + articleInfo.id
                                                    + ", pos=" + realPosition
                                                    + ", req=" + effectiveUrl
                                                    + ", decoded=" + w + "x" + h
                                                    + ", source=" + dataSource
                                                    + ", baseFallbackVariant=" + finalBaseFallbackVariant
                                                    + ", refreshRevision=" + finalRefreshRevision);

                                    if (finalRequestUrl != null && !finalRequestUrl.isEmpty()) {
                                        BASE_FAIL_RETRY_COUNTS.remove(finalRequestUrl);
                                    }

                                    boolean looksTiny = w > 0 && h > 0 && w <= 32 && h <= 32;
                                    boolean canRetryMore = finalRefreshRevision < MAX_REFRESH_RETRY;
                                    boolean shouldRefreshRetry = !finalBaseFallbackVariant && canRetryMore && looksTiny;
                                    boolean shouldFinalBaseFallback = !finalBaseFallbackVariant
                                            && !canRetryMore
                                            && looksTiny
                                            && finalBaseUrl != null && !finalBaseUrl.isEmpty();

                                    if (shouldRefreshRetry) {
                                        int nextRevision = finalRefreshRevision + 1;
                                        REFRESH_IMAGE_REVISIONS.put(finalRequestUrl, nextRevision);
                                        Logu.w("ArticleImage",
                                                "LEGACY_TINY_REFRESH cv=" + articleInfo.id
                                                        + ", pos=" + realPosition
                                                        + ", tiny=" + w + "x" + h
                                                        + ", retryReq=" + finalRequestUrl
                                                        + ", nextRevision=" + nextRevision);

                                        scheduleImageReload(holder, imageView,
                                                finalRequestUrl + "#refresh-pending:" + nextRevision,
                                                "LEGACY_POST_REFRESH_FAIL", realPosition, finalRequestUrl);
                                        return true;
                                    }

                                    if (shouldFinalBaseFallback) {
                                        BASE_FALLBACK_REQUESTS.add(finalRequestUrl);
                                        Logu.w("ArticleImage",
                                                "LEGACY_TINY_BASE_FALLBACK cv=" + articleInfo.id
                                                        + ", pos=" + realPosition
                                                        + ", tiny=" + w + "x" + h
                                                        + ", fallbackBase=" + finalBaseUrl);

                                        scheduleImageReload(holder, imageView,
                                                finalRequestUrl + "#base-pending",
                                                "LEGACY_POST_BASE_FAIL", realPosition, finalRequestUrl);
                                        return true;
                                    }

                                    return false;
                                }
                            })
                            .transition(GlideUtil.getTransitionOptions())
                            .diskCacheStrategy((refreshVariant || baseFallbackVariant)
                                    ? DiskCacheStrategy.NONE
                                    : DiskCacheStrategy.DATA)
                            .into(imageView);
                }

                imageView.setOnClickListener(view -> {
                    Intent intent = new Intent();
                    intent.setClass(context, ImageViewerActivity.class);
                    ArrayList<String> imageList = new ArrayList<>();
                    imageList.add(rawUrl);
                    intent.putExtra("imageList", imageList);
                    context.startActivity(intent);
                });
                break;

            case -1:
                TextView title = holder.itemView.findViewById(R.id.text_title);
                ImageView cover = holder.itemView.findViewById(R.id.img_cover);
                ImageView upIcon = holder.itemView.findViewById(R.id.upInfo_Icon); // 头
                TextView upName = holder.itemView.findViewById(R.id.upInfo_Name);
                MaterialCardView upCard = holder.itemView.findViewById(R.id.upInfo);

                StringUtil.setCopy(title);

                upName.setText(articleInfo.upInfo.name);
                if (articleInfo.banner.isEmpty())
                    cover.setVisibility(View.GONE);
                else {
                    String bannerUrl = GlideUtil.url(articleInfo.banner);
                    if (!bannerUrl.equals(holder.lastTopImageUrl)) {
                        holder.lastTopImageUrl = bannerUrl;
                        Glide.with(BiliTerminal.context).asDrawable().load(bannerUrl)
                                .placeholder(R.mipmap.placeholder)
                                .transition(GlideUtil.getTransitionOptions())
                                .apply(RequestOptions.bitmapTransform(new RoundedCorners(ToolsUtil.dp2px(4))))
                                .format(DecodeFormat.PREFER_RGB_565)
                                .diskCacheStrategy(DiskCacheStrategy.DATA)
                                .into(cover);
                    }
                }

                String avatarUrl = GlideUtil.url(articleInfo.upInfo.avatar);
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
                    intent.putExtra("mid", articleInfo.upInfo.mid);
                    context.startActivity(intent);
                });
                ImageButton like = holder.itemView.findViewById(R.id.btn_like);
                ImageButton coin = holder.itemView.findViewById(R.id.btn_coin);
                TextView likeLabel = holder.itemView.findViewById(R.id.like_label);
                TextView coinLabel = holder.itemView.findViewById(R.id.coin_label);
                TextView favLabel = holder.itemView.findViewById(R.id.fav_label);
                ImageButton fav = holder.itemView.findViewById(R.id.btn_fav);

                likeLabel.setText(StringUtil.toWan(articleInfo.stats.like));
                coinLabel.setText(StringUtil.toWan(articleInfo.stats.coin));
                favLabel.setText(StringUtil.toWan(articleInfo.stats.favorite));

                like.setOnClickListener(view1 -> CenterThreadPool.run(() -> {
                    try {
                        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) {
                            context.runOnUiThread(() -> MsgUtil.showMsg("还没有登录喵~"));
                            return;
                        }
                        int result = ArticleApi.like(articleInfo.id, !articleInfo.stats.liked);
                        if (result == 0) {
                            articleInfo.stats.liked = !articleInfo.stats.liked;
                            context.runOnUiThread(() -> {
                                MsgUtil.showMsg((articleInfo.stats.liked ? "点赞成功" : "取消成功"));

                                if (articleInfo.stats.liked)
                                    likeLabel.setText(StringUtil.toWan(++articleInfo.stats.like));
                                else
                                    likeLabel.setText(StringUtil.toWan(--articleInfo.stats.like));
                                like.setImageResource(
                                        articleInfo.stats.liked ? R.drawable.icon_like_1 : R.drawable.icon_like_0);
                            });
                        } else {
                            context.runOnUiThread(() -> MsgUtil.showMsg("操作失败：" + result));
                        }
                    } catch (Exception e) {
                        context.runOnUiThread(() -> MsgUtil.err(e));
                    }
                }));

                coin.setOnClickListener(view1 -> CenterThreadPool.run(() -> {
                    if (articleInfo.stats.coined < articleInfo.stats.coin_limit) {
                        try {
                            if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) {
                                context.runOnUiThread(() -> MsgUtil.showMsg("还没有登录喵~"));
                                return;
                            }
                            int result = ArticleApi.addCoin(articleInfo.id, articleInfo.upInfo.mid, 1);
                            if (result == 0) {
                                if (++coinAdd <= 2)
                                    articleInfo.stats.coined++;
                                context.runOnUiThread(() -> {
                                    MsgUtil.showMsg("投币成功！");
                                    coinLabel.setText(StringUtil.toWan(++articleInfo.stats.coin));
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
                        if (articleInfo.stats.favoured) {
                            if (ArticleApi.delFavorite(articleInfo.id) == 0) {
                                context.runOnUiThread(() -> fav.setImageResource(R.drawable.icon_fav_0));
                                articleInfo.stats.favorite--;
                            }
                        } else {
                            if (ArticleApi.favorite(articleInfo.id) == 0) {
                                context.runOnUiThread(() -> fav.setImageResource(R.drawable.icon_fav_1));
                                articleInfo.stats.favorite++;
                            }
                        }
                        articleInfo.stats.favoured = !articleInfo.stats.favoured;
                        context.runOnUiThread(() -> {
                            favLabel.setText(StringUtil.toWan(articleInfo.stats.favorite));
                            MsgUtil.showMsg("操作成功~");
                        });
                    } catch (Exception e) {
                        context.runOnUiThread(() -> MsgUtil.err(e));
                    }
                }));

                CenterThreadPool.run(() -> {
                    try {
                        ArticleInfo viewInfo = ArticleApi.getArticleViewInfo(articleInfo.id);
                        if (viewInfo != null) {
                            articleInfo.stats = viewInfo.stats;
                            articleInfo.stats.coin_limit = 1;
                            context.runOnUiThread(() -> {
                                if (articleInfo.stats.coined != 0)
                                    coin.setImageResource(R.drawable.icon_coin_1);
                                if (articleInfo.stats.liked)
                                    like.setImageResource(R.drawable.icon_like_1);
                                if (articleInfo.stats.favoured)
                                    fav.setImageResource(R.drawable.icon_fav_1);
                            });
                        }
                    } catch (Exception e) {
                        context.runOnUiThread(() -> MsgUtil.err(e));
                    }
                });

                title.setText(articleInfo.title);
                break;

            case -2:
                TextView views = holder.itemView.findViewById(R.id.viewCount);
                TextView timeText = holder.itemView.findViewById(R.id.timeText);
                TextView cvidText = holder.itemView.findViewById(R.id.cvidText);
                ImageView viewIcon = holder.itemView.findViewById(R.id.viewIcon);
                ImageView timeIcon = holder.itemView.findViewById(R.id.timeIcon);
                ImageView cvidIcon = holder.itemView.findViewById(R.id.cvidIcon);

                if (articleInfo.id > 0) {
                    cvidText.setText("cv" + articleInfo.id + " | " + articleInfo.wordCount + "字");
                    StringUtil.setCopy(cvidText, "cv" + articleInfo.id);
                    cvidText.setVisibility(View.VISIBLE);
                    cvidIcon.setVisibility(View.VISIBLE);
                } else {
                    cvidText.setVisibility(View.GONE);
                    cvidIcon.setVisibility(View.GONE);
                }

                if (articleInfo.stats != null && articleInfo.stats.view > 0) {
                    views.setText(StringUtil.toWan(articleInfo.stats.view) + "阅读");
                    views.setVisibility(View.VISIBLE);
                    viewIcon.setVisibility(View.VISIBLE);
                } else {
                    views.setVisibility(View.GONE);
                    viewIcon.setVisibility(View.GONE);
                }

                if (articleInfo.ctime > 0) {
                    @SuppressLint("SimpleDateFormat")
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    timeText.setText(sdf.format(articleInfo.ctime * 1000));
                    timeText.setVisibility(View.VISIBLE);
                    timeIcon.setVisibility(View.VISIBLE);
                } else {
                    timeText.setVisibility(View.GONE);
                    timeIcon.setVisibility(View.GONE);
                }
                break;
            default:
                if (realPosition >= 0 && realPosition < article.size()) {
                    ArticleLine textLine = article.get(realPosition);
                    if (textLine != null && textLine.content != null) {
                        TextView textView = holder.itemView.findViewById(R.id.textView);
                        textView.setText(textLine.content);
                        switch (textLine.extra) {
                            case "strong":
                                textView.setAlpha(0.92f);
                                break;
                            case "br":
                                textView.setHeight(ToolsUtil.dp2px(6f));
                                break;
                            default:
                                textView.setAlpha(0.85f);
                                break;
                        }
                        StringUtil.setCopy(textView);
                        StringUtil.setLink(textView);
                    }
                }
                break;
        }
    }

    @Override
    public int getItemCount() {
        return article != null ? article.size() + 2 : 2;
    }

    @Override
    public void onViewRecycled(@NonNull ArticleLineHolder holder) {
        holder.lastImageUrl = null;
        holder.lastTopImageUrl = null;
        holder.lastAvatarUrl = null;
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemViewType(int position) {
        if (article == null)
            return -1;
        if (position == 0)
            return -1;
        else if (position == article.size() + 1)
            return -2;
        else {
            int realPosition = position - 1;
            if (realPosition >= 0 && realPosition < article.size()) {
                return article.get(realPosition).type;
            }
            return 0;
        }
    }

    public static class ArticleLineHolder extends RecyclerView.ViewHolder {
        String lastImageUrl;
        String lastTopImageUrl;
        String lastAvatarUrl;

        public ArticleLineHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}
