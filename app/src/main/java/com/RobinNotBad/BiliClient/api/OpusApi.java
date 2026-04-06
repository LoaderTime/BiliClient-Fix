package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ArticleInfo;
import com.RobinNotBad.BiliClient.model.Opus;
import com.RobinNotBad.BiliClient.model.OpusParagraph;
import com.RobinNotBad.BiliClient.model.Stats;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;
import okhttp3.ResponseBody;

public class OpusApi {

    /**
     * 兼容读取 B 站返回的富文本 attributes/style 标记。
     * 线上数据可能出现：true/false、1/0、"1"/"0"、"true"/"false" 等。
     */
    private static boolean optBooleanCompat(JSONObject obj, String key) {
        if (obj == null || key == null) return false;
        Object val = obj.opt(key);
        if (val == null) return false;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number) return ((Number) val).intValue() != 0;
        if (val instanceof String) {
            String s = ((String) val).trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) return false;
            if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s)) return true;
            if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "n".equals(s)) return false;
            try {
                return Integer.parseInt(s) != 0;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public static Opus getOpus(long id) throws IOException, JSONException {
        Opus opus = new Opus();
        opus.id = id;
        
        // 首先尝试判断是专栏还是动态
        if (isArticleId(id)) {
            // 专栏ID，优先尝试 opus detail API 获取富文本数据
            opus.type = Opus.TYPE_ARTICLE;
            try {
                Logu.i("尝试通过 opus detail API 获取专栏富文本数据: cv" + id);
                return getArticleOpusDetail(id);
            } catch (Exception e) {
                // opus detail API 失败，回退到传统 ArticleApi（HTML）
                Logu.e("opus detail API 失败，回退到 HTML 方式: " + e.getMessage());
                try {
                    ArticleInfo articleInfo = ArticleApi.getArticle(id);
                    if (articleInfo != null) {
                        convertArticleInfoToOpus(opus, articleInfo);
                        return opus;
                    } else {
                        throw new IOException("ArticleApi 返回 null");
                    }
                } catch (Exception e2) {
                    Logu.e("HTML 方式也失败: " + e2.getMessage());
                    throw new IOException("无法获取专栏数据: " + e2.getMessage());
                }
            }
        } else {
            // 动态ID，使用API获取
            opus.type = Opus.TYPE_DYNAMIC;
            return getOpusByApi(id);
        }
    }
    
    private static boolean isArticleId(long id) {
        // 专栏ID通常小于100000000，且不以0开头
        return id > 0 && id < 100000000;
    }
    
    /**
     * 通过 opus detail API 获取专栏富文本数据
     * API: /x/polymer/web-dynamic/v1/opus/detail
     * 这个 API 返回的是结构化的 paragraphs 数据，包含富文本样式信息
     */
    private static Opus getArticleOpusDetail(long cvid) throws IOException, JSONException {
        Opus opus = new Opus();
        opus.id = cvid;
        opus.type = Opus.TYPE_ARTICLE;
        
        // 首先需要将 cvid 转换为 opus_id
        // 使用 ArticleApi 的转换方法
        Opus opusIdInfo = ArticleApi.opusId2cvid(cvid);
        if (opusIdInfo == null || opusIdInfo.id == 0) {
            throw new IOException("无法将 cv" + cvid + " 转换为 opus_id");
        }
        
        long opusId = opusIdInfo.id;
        Logu.i("cv" + cvid + " 对应的 opus_id: " + opusId);
        
        // 使用 opus detail API 获取富文本数据
        String url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/opus/detail?id=" + opusId + "&timezone_offset=-480";
        JSONObject result = NetWorkUtil.getJson(url);
        
        if (result.optBoolean("retry_failed", false)) {
            throw new IOException(result.optString("message", "网络请求失败"));
        }
        
        int code = result.optInt("code", -1);
        if (code == 0 && result.has("data") && !result.isNull("data")) {
            JSONObject data = result.getJSONObject("data");
            
            // 检查是否有 item 字段（opus detail 格式）
            if (data.has("item") && !data.isNull("item")) {
                JSONObject item = data.getJSONObject("item");
                
                // 解析 basic 信息
                if (item.has("basic")) {
                    JSONObject basic = item.getJSONObject("basic");
                    opus.commentId = Long.parseLong(basic.optString("comment_id_str", String.valueOf(cvid)));
                    opus.commentType = basic.optInt("comment_type", 12);
                    opus.listId = extractListIdFromBasic(basic);
                }
                
                // 解析 modules（数组格式）
                if (item.has("modules") && !item.isNull("modules")) {
                    Object modulesObj = item.get("modules");
                    if (modulesObj instanceof JSONArray) {
                        parseModulesArray(opus, (JSONArray) modulesObj);
                    }
                }

                if (opus.listId <= 0) {
                    opus.listId = extractListIdFromItem(item);
                }

                if (opus.listId <= 0) {
                    try {
                        ArticleInfo listInfo = ArticleApi.getArticle(cvid);
                        if (listInfo != null) {
                            opus.listId = listInfo.listId;
                        }
                    } catch (Exception ignored) {
                    }
                }
                
                // 确保必要字段不为null
                if (opus.upInfo == null) opus.upInfo = new UserInfo();
                if (opus.stats == null) opus.stats = new Stats();
                if (opus.cover == null) opus.cover = "";
                
                Logu.i("成功通过 opus detail API 获取专栏富文本数据");
                return opus;
            }
        }
        
        String message = result.optString("message", "未知错误");
        throw new IOException("opus detail API 错误 (code=" + code + "): " + message);
    }
    
    /**
     * 通过B站API获取动态/图文详情
     * 使用 dynamic detail API: /x/polymer/web-dynamic/v1/detail
     * 参考PiliPlus项目的获取方法
     */
    private static Opus getOpusByApi(long id) throws IOException, JSONException {
        Opus opus = new Opus();
        opus.id = id;
        opus.type = isArticleId(id) ? Opus.TYPE_ARTICLE : Opus.TYPE_DYNAMIC;
        
        // 使用 dynamic detail API（带 itemOpusStyle 特性，支持图文/文字动态）
        try {
            String url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?timezone_offset=-480&id=" + id + "&features=itemOpusStyle,listOnlyfans";
            JSONObject result = NetWorkUtil.getJson(url);
            
            if (result.optBoolean("retry_failed", false)) {
                throw new IOException(result.optString("message", "网络请求失败"));
            }
            
            int code = result.optInt("code", -1);
            if (code == 0 && result.has("data") && !result.isNull("data")) {
                JSONObject data = result.getJSONObject("data");
                JSONObject item = data.getJSONObject("item");

                long articleCvid = resolveArticleCvidFromDynamicItem(item);
                if (articleCvid > 0) {
                    Logu.i("检测到专栏型 opus/dynamic，尝试改用专栏详情加载: id=" + id + ", cvid=" + articleCvid);
                    try {
                        return getArticleOpusDetail(articleCvid);
                    } catch (Exception articleDetailError) {
                        Logu.e("专栏型 opus detail 转换失败，回退 ArticleApi: " + articleDetailError.getMessage());
                        try {
                            ArticleInfo articleInfo = ArticleApi.getArticle(articleCvid);
                            if (articleInfo != null) {
                                Opus articleOpus = new Opus();
                                articleOpus.id = articleCvid;
                                articleOpus.type = Opus.TYPE_ARTICLE;
                                convertArticleInfoToOpus(articleOpus, articleInfo);
                                return articleOpus;
                            }
                        } catch (Exception articleFallbackError) {
                            Logu.e("ArticleApi 回退也失败，继续按动态处理: " + articleFallbackError.getMessage());
                        }
                    }
                }
                
                // 使用analyzeOldStyleDynamic方法解析动态数据为Opus格式
                opus.type = Opus.TYPE_DYNAMIC_OLD_STYLE;
                analyzeOldStyleDynamic(opus, item);
                return opus;
            }
            
            String message = result.optString("message", "未知错误");
            throw new IOException("API错误 (code=" + code + "): " + message);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("获取动态详情失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 解析opus detail API返回的数据
     * API: /x/polymer/web-dynamic/v1/opus/detail
     */
    private static Opus parseOpusDetailData(Opus opus, JSONObject data) throws JSONException {
        // opus detail API返回的数据结构包含 basic 和 modules 数组
        if (data.has("basic")) {
            JSONObject basic = data.getJSONObject("basic");
            opus.commentId = Long.parseLong(basic.optString("comment_id_str", "0"));
            opus.commentType = basic.optInt("comment_type");
        }
        
        if (data.has("modules") && !data.isNull("modules")) {
            // modules可能是数组格式（opus detail API）
            Object modulesObj = data.get("modules");
            if (modulesObj instanceof JSONArray) {
                JSONArray modules = (JSONArray) modulesObj;
                parseModulesArray(opus, modules);
            } else if (modulesObj instanceof JSONObject) {
                // modules是对象格式（dynamic detail API的格式）
                JSONObject modules = (JSONObject) modulesObj;
                parseModulesObject(opus, modules);
            }
        }
        
        // 确保必要字段不为null
        if (opus.upInfo == null) opus.upInfo = new UserInfo();
        if (opus.stats == null) opus.stats = new Stats();
        if (opus.cover == null) opus.cover = "";
        
        return opus;
    }
    
    /**
     * 解析modules数组格式（opus detail API返回的格式）
     */
    private static void parseModulesArray(Opus opus, JSONArray modules) throws JSONException {
        for (int i = 0; i < modules.length(); i++) {
            JSONObject module = modules.getJSONObject(i);
            String moduleType = module.optString("module_type");
            switch (moduleType) {
                case "MODULE_TYPE_TITLE":
                    if (module.has("module_title"))
                        opus.title = module.getJSONObject("module_title").optString("text");
                    break;
                case "MODULE_TYPE_TOP":
                    ArrayList<String> topImages = new ArrayList<>();
                    if (module.has("module_top")) {
                        JSONObject module_top = module.getJSONObject("module_top");
                        JSONObject display = module_top.optJSONObject("display");
                        if (display != null) {
                            int displayType = display.optInt("type");
                            if (displayType == 1) {
                                JSONObject album = display.optJSONObject("album");
                                if (album != null) {
                                    JSONArray pics = album.optJSONArray("pics");
                                    if (pics != null) {
                                        for (int j = 0; j < pics.length(); j++) {
                                            topImages.add(pics.getJSONObject(j).optString("url"));
                                        }
                                    }
                                }
                            }
                        }
                    }
                    opus.topImages = topImages;
                    break;
                case "MODULE_TYPE_AUTHOR":
                    if (module.has("module_author")) {
                        JSONObject module_author = module.getJSONObject("module_author");
                        UserInfo author = new UserInfo();
                        author.mid = module_author.optLong("mid");
                        author.name = module_author.optString("name");
                        author.followed = module_author.optBoolean("following", false);
                        author.avatar = module_author.optString("face");
                        if (!module_author.isNull("vip"))
                            author.vip_nickname_color = module_author.getJSONObject("vip").optString("nickname_color", "");
                        opus.pubTime = module_author.optString("pub_time");
                        opus.upInfo = author;
                    }
                    break;
                case "MODULE_TYPE_CONTENT":
                    if (module.has("module_content")) {
                        JSONObject moduleContent = module.getJSONObject("module_content");
                        if (moduleContent.has("paragraphs")) {
                            JSONArray paragraphs = moduleContent.getJSONArray("paragraphs");
                            opus.paragraphs = analyzeParagraphs(paragraphs);
                        }
                    }
                    break;
                case "MODULE_TYPE_STAT":
                    opus.stats = Stats.fromOpus(module.optJSONObject("module_stat"));
                    break;
            }
        }
    }
    
    /**
     * 解析modules对象格式（dynamic detail API返回的格式）
     * 将其转换为Opus的段落格式
     */
    private static void parseModulesObject(Opus opus, JSONObject modules) throws JSONException {
        // 解析作者信息
        if (!modules.isNull("module_author")) {
            JSONObject module_author = modules.getJSONObject("module_author");
            UserInfo author = new UserInfo();
            author.mid = module_author.optLong("mid");
            author.name = module_author.optString("name");
            author.followed = module_author.optBoolean("following", false);
            author.avatar = module_author.optString("face");
            if (!module_author.isNull("vip"))
                author.vip_nickname_color = module_author.getJSONObject("vip").optString("nickname_color", "");
            opus.pubTime = module_author.optString("pub_time");
            opus.upInfo = author;
        }
        
        // 解析动态内容
        ArrayList<OpusParagraph> paragraphList = new ArrayList<>();
        
        if (!modules.isNull("module_dynamic")) {
            JSONObject module_dynamic = modules.getJSONObject("module_dynamic");
            
            // 解析文字描述
            if (!module_dynamic.isNull("desc")) {
                JSONObject desc = module_dynamic.getJSONObject("desc");
                JSONArray richTextNodes = desc.optJSONArray("rich_text_nodes");
                if (richTextNodes != null) {
                    JSONObject textPara = new JSONObject();
                    textPara.put("para_type", OpusParagraph.TYPE_TEXT_OPUS);
                    textPara.put("data", richTextNodes);
                    paragraphList.add(new OpusParagraph(textPara));
                }
            }
            
            // 解析major内容（图片、视频等）
            if (!module_dynamic.isNull("major")) {
                JSONObject major = module_dynamic.getJSONObject("major");
                String majorType = major.optString("type");
                
                switch (majorType) {
                    case "MAJOR_TYPE_OPUS":
                        if (!major.isNull("opus")) {
                            JSONObject opusObj = major.getJSONObject("opus");
                            
                            // 标题
                            String title = opusObj.optString("title");
                            if (title != null && !title.isEmpty() && !"null".equals(title)) {
                                opus.title = title;
                            }
                            
                            // 文字内容
                            JSONObject summary = opusObj.optJSONObject("summary");
                            if (summary != null) {
                                JSONArray richTextNodes = summary.optJSONArray("rich_text_nodes");
                                if (richTextNodes != null) {
                                    JSONObject textPara = new JSONObject();
                                    textPara.put("para_type", OpusParagraph.TYPE_TEXT_OPUS);
                                    textPara.put("data", richTextNodes);
                                    paragraphList.add(new OpusParagraph(textPara));
                                }
                            }
                            
                            // 图片
                            JSONArray pics = opusObj.optJSONArray("pics");
                            if (pics != null && pics.length() > 0) {
                                JSONObject picPara = new JSONObject();
                                picPara.put("para_type", OpusParagraph.TYPE_PIC);
                                picPara.put("pic", new JSONObject().put("pics", pics));
                                paragraphList.add(new OpusParagraph(picPara));
                            }
                        }
                        break;
                    case "MAJOR_TYPE_DRAW":
                        if (!major.isNull("draw")) {
                            JSONObject draw = major.getJSONObject("draw");
                            JSONArray items = draw.optJSONArray("items");
                            if (items != null && items.length() > 0) {
                                // 将draw items转换为pics格式
                                JSONArray pics = new JSONArray();
                                for (int i = 0; i < items.length(); i++) {
                                    JSONObject item = items.getJSONObject(i);
                                    JSONObject pic = new JSONObject();
                                    pic.put("url", item.optString("src"));
                                    pics.put(pic);
                                }
                                JSONObject picPara = new JSONObject();
                                picPara.put("para_type", OpusParagraph.TYPE_PIC);
                                picPara.put("pic", new JSONObject().put("pics", pics));
                                paragraphList.add(new OpusParagraph(picPara));
                            }
                        }
                        break;
                }
            }
        }
        
        // 解析统计信息
        if (!modules.isNull("module_stat")) {
            JSONObject module_stat = modules.getJSONObject("module_stat");
            Stats stats = new Stats();
            if (module_stat.has("comment"))
                stats.reply = module_stat.getJSONObject("comment").optInt("count");
            if (module_stat.has("like"))
                stats.like = module_stat.getJSONObject("like").optInt("count");
            opus.stats = stats;
        }
        
        opus.paragraphs = paragraphList.toArray(new OpusParagraph[0]);
    }
    
    private static void convertArticleInfoToOpus(Opus opus, ArticleInfo articleInfo) {
        opus.title = articleInfo.title;
        opus.cover = articleInfo.banner;
        opus.content = articleInfo.content;
        opus.listId = articleInfo.listId;
        
        // 修复时间格式 - 将时间戳转换为可读格式
        if (articleInfo.ctime > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.SIMPLIFIED_CHINESE);
            opus.pubTime = sdf.format(articleInfo.ctime * 1000);
        } else {
            opus.pubTime = "";
        }
        
        opus.upInfo = articleInfo.upInfo;
        
        // 确保stats对象存在
        if (articleInfo.stats != null) {
            opus.stats = articleInfo.stats;
        } else {
            opus.stats = new Stats();
        }
        
        // 将HTML内容转换为段落（包含图片）
        if (articleInfo.content != null && !articleInfo.content.isEmpty()) {
            opus.paragraphs = convertHtmlToParagraphs(articleInfo.content);
        }

        // 先尝试使用 article/view 返回的 opusId 走 detail API 拉结构化段落。
        // 这个路径不依赖网页反爬，稳定性高于 read/cv 网页补偿。
        if ((opus.paragraphs == null || opus.paragraphs.length <= 1) && articleInfo.opusId > 0) {
            OpusParagraph[] apiParagraphs = fetchOpusParagraphsByOpusId(articleInfo.opusId);
            if (apiParagraphs.length > 0) {
                opus.paragraphs = apiParagraphs;
                Logu.i("专栏内容解析: 使用 opus/detail API 补偿解析, opusId="
                        + articleInfo.opusId + ", 段落数=" + apiParagraphs.length);
            }
        }

        // 某些专栏在 /x/article/view 的 content 里只有极简HTML，
        // 会导致整段挤在一行且图片缺失；此时回源网页再取 opus paragraphs。
        if ((opus.paragraphs == null || opus.paragraphs.length <= 1) && opus.id > 0) {
            OpusParagraph[] webParagraphs = fetchOpusParagraphsFromWebPage(opus.id, articleInfo.opusId, articleInfo.content);
            if (webParagraphs.length > 0) {
                opus.paragraphs = webParagraphs;
                Logu.i("专栏内容解析: 使用网页补偿解析, cv=" + opus.id + ", 段落数=" + webParagraphs.length);
            }
        }

        if (opus.paragraphs == null) {
            opus.paragraphs = new OpusParagraph[0];
        }
        
        // 设置评论信息
        opus.commentId = articleInfo.id;
        opus.commentType = 12; // 专栏的评论类型通常是12
    }

    private static OpusParagraph[] fetchOpusParagraphsByOpusId(long opusId) {
        if (opusId <= 0) {
            return new OpusParagraph[0];
        }

        try {
            String url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/opus/detail?id="
                    + opusId + "&timezone_offset=-480&features=htmlNewStyle";
            JSONObject result = NetWorkUtil.getJson(url);
            if (result == null || result.optBoolean("retry_failed", false) || result.optInt("code", -1) != 0) {
                return new OpusParagraph[0];
            }

            JSONObject data = result.optJSONObject("data");
            if (data == null) {
                return new OpusParagraph[0];
            }

            JSONObject item = data.optJSONObject("item");
            if (item == null) {
                return new OpusParagraph[0];
            }

            JSONArray modules = item.optJSONArray("modules");
            if (modules == null) {
                return new OpusParagraph[0];
            }

            for (int i = 0; i < modules.length(); i++) {
                JSONObject module = modules.optJSONObject(i);
                if (module == null) {
                    continue;
                }
                if (!"MODULE_TYPE_CONTENT".equals(module.optString("module_type"))) {
                    continue;
                }
                JSONObject moduleContent = module.optJSONObject("module_content");
                if (moduleContent == null) {
                    continue;
                }
                JSONArray paragraphs = moduleContent.optJSONArray("paragraphs");
                if (paragraphs != null && paragraphs.length() > 0) {
                    return analyzeParagraphs(paragraphs);
                }
            }
        } catch (Exception e) {
            Logu.e("专栏内容解析: opus/detail API 补偿失败, opusId=" + opusId + ", err=" + e.getMessage());
        }

        return new OpusParagraph[0];
    }

    private static OpusParagraph[] fetchOpusParagraphsFromWebPage(long cvid, long opusIdHint, String articleHtml) {
        ArrayList<String> urlList = new ArrayList<>();
        HashSet<String> visited = new HashSet<>();

        if (opusIdHint > 0) {
            urlList.add("https://www.bilibili.com/opus/" + opusIdHint);
            Logu.i("专栏内容解析: 网页补偿使用 opusIdHint=" + opusIdHint + ", cv=" + cvid);
        }

        // 专栏稳定入口优先使用 read/cv，避免把 cvid 当成 opusId 造成无效跳转。
        urlList.add("https://www.bilibili.com/read/cv" + cvid);

        long opusId = extractOpusIdFromHtml(articleHtml);
        if (opusId > 0) {
            urlList.add("https://www.bilibili.com/opus/" + opusId);
            Logu.i("专栏内容解析: 网页补偿识别到 opusId=" + opusId + ", cv=" + cvid);
        } else {
            Logu.i("专栏内容解析: 网页补偿未识别 opusId, cv=" + cvid + ", 仅使用 read/cv 入口");
        }

        for (int i = 0; i < urlList.size(); i++) {
            String url = urlList.get(i);
            if (!visited.add(url)) {
                continue;
            }
            try (Response response = NetWorkUtil.get(url, NetWorkUtil.webHeaders);
                 ResponseBody body = response.body()) {
                if (body == null) {
                    continue;
                }

                String html = body.string();
                if (html == null || html.isEmpty()) {
                    continue;
                }

                // 某些 read/cv 页面不会直接带段落数据，但会包含真实 opus 链接。
                long discoverOpusId = extractOpusIdFromHtml(html);
                if (discoverOpusId > 0) {
                    String discoveredUrl = "https://www.bilibili.com/opus/" + discoverOpusId;
                    if (!visited.contains(discoveredUrl) && !urlList.contains(discoveredUrl)) {
                        urlList.add(discoveredUrl);
                        Logu.i("专栏内容解析: 网页补偿追加真实 opus 链接=" + discoveredUrl + ", from=" + url);
                    }
                }

                OpusParagraph[] paragraphs = parseInitialStateContent(html);
                if (paragraphs.length > 0) {
                    Logu.i("专栏内容解析: 网页INITIAL_STATE命中, url=" + url + ", 段落数=" + paragraphs.length);
                    return paragraphs;
                }

                paragraphs = parseHtmlContent(html);
                if (paragraphs.length > 1) {
                    Logu.i("专栏内容解析: 网页HTML命中, url=" + url + ", 段落数=" + paragraphs.length);
                    return paragraphs;
                }
            } catch (Exception e) {
                Logu.e("专栏内容解析: 网页补偿失败, url=" + url + ", err=" + e.getMessage());
            }
        }

        return new OpusParagraph[0];
    }

    private static long extractOpusIdFromHtml(String html) {
        if (html == null || html.isEmpty()) {
            return 0;
        }

        try {
            String marker = "window.__INITIAL_STATE__ =";
            int start = html.indexOf(marker);
            if (start < 0) {
                return 0;
            }

            start += marker.length();
            int end = html.indexOf(";(function()", start);
            if (end < 0) {
                end = html.indexOf("</script>", start);
            }
            if (end < 0) {
                return 0;
            }

            String rawJson = html.substring(start, end).trim();
            if (rawJson.endsWith(";")) {
                rawJson = rawJson.substring(0, rawJson.length() - 1).trim();
            }
            if (rawJson.isEmpty() || !rawJson.startsWith("{")) {
                return 0;
            }

            JSONObject initialState = new JSONObject(rawJson);
            JSONObject detail = initialState.optJSONObject("detail");
            if (detail == null) {
                return 0;
            }

            String idStr = detail.optString("id_str", "");
            if (!idStr.isEmpty()) {
                try {
                    return Long.parseLong(idStr);
                } catch (NumberFormatException ignored) {
                }
            }

            return detail.optLong("id", 0);
        } catch (Exception e) {
            Logu.e("专栏内容解析: 提取 opusId 失败: " + e.getMessage());
        }

        // read/cv 页面可能没有 __INITIAL_STATE__，兜底直接从文本里提取 /opus/{id}
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?:https?:)?\\/\\/www\\.bilibili\\.com\\/opus\\/(\\d+)")
                    .matcher(html);
            if (matcher.find()) {
                return Long.parseLong(matcher.group(1));
            }
        } catch (Exception ignored) {
        }

        return 0;
    }
    
    private static OpusParagraph[] convertHtmlToParagraphs(String content) {
        // 首先尝试解析为JSON格式（新版动态/专栏）
        try {
            OpusParagraph[] paragraphs = parseJsonContent(content);
            if (paragraphs.length > 0) {
                Logu.i("专栏内容解析: 使用 JSON(ops) 解析, 段落数=" + paragraphs.length);
                return paragraphs;
            }
        } catch (Exception e) {
            // JSON解析失败，回退到HTML解析
            Logu.e("JSON(ops)解析失败，继续尝试 INITIAL_STATE: " + e.getMessage());
        }

        // 新版专栏页面（opus-detail）会把完整结构化段落放在 window.__INITIAL_STATE__ 中
        try {
            OpusParagraph[] paragraphs = parseInitialStateContent(content);
            if (paragraphs.length > 0) {
                Logu.i("专栏内容解析: 使用 __INITIAL_STATE__ 解析, 段落数=" + paragraphs.length);
                return paragraphs;
            }
        } catch (Exception e) {
            Logu.e("INITIAL_STATE解析失败，继续尝试HTML: " + e.getMessage());
        }

        OpusParagraph[] paragraphs = parseHtmlContent(content);
        Logu.i("专栏内容解析: 使用 HTML 回退解析, 段落数=" + paragraphs.length);
        return paragraphs;
    }

    private static OpusParagraph[] parseInitialStateContent(String html) throws JSONException {
        if (html == null || html.isEmpty()) {
            return new OpusParagraph[0];
        }

        String marker = "window.__INITIAL_STATE__ =";
        int start = html.indexOf(marker);
        if (start < 0) {
            return new OpusParagraph[0];
        }

        start += marker.length();
        int end = html.indexOf(";(function()", start);
        if (end < 0) {
            end = html.indexOf("</script>", start);
        }
        if (end < 0) {
            return new OpusParagraph[0];
        }

        String rawJson = html.substring(start, end).trim();
        if (rawJson.endsWith(";")) {
            rawJson = rawJson.substring(0, rawJson.length() - 1).trim();
        }
        if (rawJson.isEmpty() || !rawJson.startsWith("{")) {
            return new OpusParagraph[0];
        }

        JSONObject initialState = new JSONObject(rawJson);
        JSONObject detail = initialState.optJSONObject("detail");
        if (detail == null) {
            return new OpusParagraph[0];
        }

        JSONArray modules = detail.optJSONArray("modules");
        if (modules == null || modules.length() == 0) {
            return new OpusParagraph[0];
        }

        for (int i = 0; i < modules.length(); i++) {
            JSONObject module = modules.optJSONObject(i);
            if (module == null) {
                continue;
            }
            if (!"MODULE_TYPE_CONTENT".equals(module.optString("module_type"))) {
                continue;
            }

            JSONObject moduleContent = module.optJSONObject("module_content");
            if (moduleContent == null) {
                continue;
            }

            JSONArray paragraphs = moduleContent.optJSONArray("paragraphs");
            if (paragraphs != null && paragraphs.length() > 0) {
                return analyzeParagraphs(paragraphs);
            }
        }

        return new OpusParagraph[0];
    }
    
    private static OpusParagraph[] parseJsonContent(String jsonContent) {
        ArrayList<OpusParagraph> paragraphs = new ArrayList<>();

        try {
            JSONObject json = new JSONObject(jsonContent);

            // 检查是否是 Quill Delta / ops 格式（B 站旧专栏 content 常见）
            if (!json.has("ops")) {
                return new OpusParagraph[0];
            }

            JSONArray ops = json.getJSONArray("ops");

            // 关键修复：不能把每个 op 当成一个段落。
            // 例如删除线会把“酱”拆成独立 op，旧实现会导致“梦子 / 酱 / 小姐”错位且丢失删除线。
            android.text.SpannableStringBuilder lineBuilder = new android.text.SpannableStringBuilder();

            for (int i = 0; i < ops.length(); i++) {
                JSONObject op = ops.getJSONObject(i);
                if (!op.has("insert")) continue;

                Object insert = op.get("insert");
                JSONObject attrs = op.optJSONObject("attributes");

                // 图片等嵌入对象：先落盘当前段落，再插入图片段
                if (insert instanceof JSONObject) {
                    if (lineBuilder.length() > 0) {
                        trimLeadingWhitespaceInPlace(lineBuilder);
                        if (lineBuilder.length() > 0) {
                            OpusParagraph textParagraph = new OpusParagraph();
                            textParagraph.type = OpusParagraph.TYPE_TEXT;
                            textParagraph.content = lineBuilder;
                            paragraphs.add(textParagraph);
                        }
                        lineBuilder = new android.text.SpannableStringBuilder();
                    }

                    JSONObject insertObj = (JSONObject) insert;
                    if (insertObj.has("native-image")) {
                        JSONObject nativeImage = insertObj.optJSONObject("native-image");
                        if (nativeImage != null && nativeImage.has("url")) {
                            String imgUrl = fixImageUrl(nativeImage.optString("url", ""));
                            if (imgUrl != null && !imgUrl.isEmpty()) {
                                OpusParagraph imgParagraph = new OpusParagraph();
                                imgParagraph.type = OpusParagraph.TYPE_PIC;
                                imgParagraph.content = new String[]{imgUrl};
                                paragraphs.add(imgParagraph);
                            }
                        }
                    }
                    continue;
                }

                if (!(insert instanceof String)) continue;
                String text = (String) insert;
                if (text.isEmpty()) continue;

                // 处理文本（支持 insert 中包含 \n：Quill 用它表示段落结束）
                int cursor = 0;
                while (cursor < text.length()) {
                    int nl = text.indexOf('\n', cursor);
                    String chunk;
                    boolean endsWithNewline;
                    if (nl >= 0) {
                        chunk = text.substring(cursor, nl);
                        endsWithNewline = true;
                    } else {
                        chunk = text.substring(cursor);
                        endsWithNewline = false;
                    }

                    if (!chunk.isEmpty()) {
                        int start = lineBuilder.length();
                        lineBuilder.append(chunk);
                        int end = lineBuilder.length();

                        // 仅实现本次需求：删除线（并顺手兼容粗体/斜体）
                        if (attrs != null && start < end) {
                            boolean bold = optBooleanCompat(attrs, "bold");
                            boolean italic = optBooleanCompat(attrs, "italic");
                            boolean strike = optBooleanCompat(attrs, "strike") || optBooleanCompat(attrs, "strikethrough");
                            int styleInt = (bold ? android.graphics.Typeface.BOLD : 0) + (italic ? android.graphics.Typeface.ITALIC : 0);
                            if (styleInt != 0) {
                                lineBuilder.setSpan(new android.text.style.StyleSpan(styleInt), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            }
                            if (strike) {
                                lineBuilder.setSpan(new android.text.style.StrikethroughSpan(), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            }
                        }
                    }

                    if (endsWithNewline) {
                        // 段落结束，落盘（空行也要保留的话可以不跳过；这里保持原行为：跳过纯空白）
                        trimLeadingWhitespaceInPlace(lineBuilder);
                        if (lineBuilder.length() > 0 && !lineBuilder.toString().trim().isEmpty()) {
                            OpusParagraph textParagraph = new OpusParagraph();
                            textParagraph.type = OpusParagraph.TYPE_TEXT;
                            textParagraph.content = lineBuilder;
                            paragraphs.add(textParagraph);
                        }
                        lineBuilder = new android.text.SpannableStringBuilder();
                        cursor = nl + 1;
                    } else {
                        cursor = text.length();
                    }
                }
            }

            // 末尾残留段落
            trimLeadingWhitespaceInPlace(lineBuilder);
            if (lineBuilder.length() > 0 && !lineBuilder.toString().trim().isEmpty()) {
                OpusParagraph textParagraph = new OpusParagraph();
                textParagraph.type = OpusParagraph.TYPE_TEXT;
                textParagraph.content = lineBuilder;
                paragraphs.add(textParagraph);
            }
        } catch (JSONException e) {
            throw new RuntimeException("JSON解析异常", e);
        }

        return paragraphs.toArray(new OpusParagraph[0]);
    }
    
    private static OpusParagraph[] parseHtmlContent(String html) {
        ArrayList<OpusParagraph> paragraphs = new ArrayList<>();

        // HTML 回退解析下，专栏横线目前已知至少有两种结构：
        // 1) <figure class="opus-para-line"><img src="...png"></figure>
        // 2) <figure class="img-box"><img data-src="...png" class="cut-off-1"></figure>
        // 它们都应该继续按“远程图片”渲染，但不能套用普通正文图的 @0e_25q_512w 规则。
        java.util.regex.Pattern figurePattern =
                java.util.regex.Pattern.compile("<figure([^>]*)>(.*?)</figure>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher figureMatcher = figurePattern.matcher(html);

        int lastIndex = 0;
        while (figureMatcher.find()) {
            String before = html.substring(lastIndex, figureMatcher.start()).trim();
            if (!before.isEmpty()) {
                addTextParagraphs(paragraphs, before, false);
            }

            String figureAttrs = figureMatcher.group(1);
            String figureHtml = figureMatcher.group(2);

            boolean isLineImage = false;
            String lineKind = "";
            if (figureAttrs != null) {
                String lower = figureAttrs.toLowerCase(Locale.ROOT);
                if (lower.contains("opus-para-line")) {
                    isLineImage = true;
                    lineKind = "opus-para-line";
                }
            }

            java.util.regex.Matcher imgTagMatcher =
                    java.util.regex.Pattern.compile("<img([^>]*)>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                            .matcher(figureHtml);

            if (imgTagMatcher.find()) {
                String imgAttrs = imgTagMatcher.group(1);
                if (imgAttrs == null) imgAttrs = "";
                String imgAttrsLower = imgAttrs.toLowerCase(Locale.ROOT);

                if (!isLineImage && imgAttrsLower.contains("cut-off-1")) {
                    isLineImage = true;
                    lineKind = "cut-off-1";
                }

                String imgUrl = "";
                java.util.regex.Matcher srcMatcher = java.util.regex.Pattern
                        .compile("\\bsrc=\"([^\"]+)\"", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(imgAttrs);
                if (srcMatcher.find()) {
                    imgUrl = srcMatcher.group(1);
                } else {
                    java.util.regex.Matcher dataSrcMatcher = java.util.regex.Pattern
                            .compile("\\bdata-src=\"([^\"]+)\"", java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(imgAttrs);
                    if (dataSrcMatcher.find()) {
                        imgUrl = dataSrcMatcher.group(1);
                    }
                }

                imgUrl = fixImageUrl(imgUrl);
                OpusParagraph imgParagraph = new OpusParagraph();
                imgParagraph.type = OpusParagraph.TYPE_PIC;
                imgParagraph.content = new OpusParagraph.ImageContent(
                        new String[]{imgUrl},
                        isLineImage,
                        lineKind
                );
                paragraphs.add(imgParagraph);
            }

            java.util.regex.Matcher captionMatcher =
                    java.util.regex.Pattern.compile("<figcaption[^>]*>(.*?)</figcaption>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                            .matcher(figureHtml);

            if (captionMatcher.find()) {
                String captionHtml = captionMatcher.group(1).trim();
                if (!captionHtml.isEmpty()) {
                    addTextParagraphs(paragraphs, captionHtml, true);
                }
            }

            lastIndex = figureMatcher.end();
        }

        String remaining = html.substring(lastIndex).trim();
        if (!remaining.isEmpty()) {
            addTextParagraphs(paragraphs, remaining, false);
        }

        return paragraphs.toArray(new OpusParagraph[0]);
    }
    
    private static String fixImageUrl(String imgUrl) {
        if (imgUrl == null || imgUrl.isEmpty()) {
            return imgUrl;
        }
        
        // 如果URL以//开头，添加https:
        if (imgUrl.startsWith("//")) {
            return "https:" + imgUrl;
        }
        
        // 如果URL以/开头，添加B站域名
        if (imgUrl.startsWith("/")) {
            return "https://www.bilibili.com" + imgUrl;
        }
        
        // 如果URL没有协议，添加https://
        if (!imgUrl.startsWith("http://") && !imgUrl.startsWith("https://")) {
            return "https://" + imgUrl;
        }
        
        return imgUrl;
    }
    
    private static final String STRIKE_MARK_START = "BC_STRIKE_START_5f2d9c";
    private static final String STRIKE_MARK_END = "BC_STRIKE_END_5f2d9c";

    private static void addTextParagraphs(ArrayList<OpusParagraph> paragraphs, String htmlText, boolean isCaption) {
        if (htmlText == null || htmlText.isEmpty()) {
            return;
        }

        // HTML 回退解析时，专栏正文常见用 <del>/<s>/<strike> 表示删除线。
        // 低版本 Html.fromHtml 对这几个标签兼容不稳定，这里统一转成 style=...line-through...
        // 再复用下方的 markLineThroughRanges() 逻辑。
        htmlText = normalizeStrikeTagsToInlineStyle(htmlText);

        htmlText = applyArticleColorClassMapping(htmlText);
        htmlText = markLineThroughRanges(htmlText);
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            htmlText = convertSpanColorStyleToFontTag(htmlText);
        }

        CharSequence spanned;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            spanned = android.text.Html.fromHtml(htmlText, android.text.Html.FROM_HTML_MODE_LEGACY);
        } else {
            spanned = android.text.Html.fromHtml(htmlText);
        }

        if (!(spanned instanceof android.text.Spanned) || spanned.length() == 0) {
            return;
        }

        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(spanned);

        android.text.Spanned colorSpanned = (android.text.Spanned) spanned;
        android.text.style.ForegroundColorSpan[] colorSpans =
                colorSpanned.getSpans(0, colorSpanned.length(), android.text.style.ForegroundColorSpan.class);

        // 先应用大范围颜色，再应用小范围颜色，避免父级颜色覆盖子级颜色
        java.util.ArrayList<android.text.style.ForegroundColorSpan> orderedColorSpans = new java.util.ArrayList<>();
        java.util.Collections.addAll(orderedColorSpans, colorSpans);
        java.util.Collections.sort(orderedColorSpans, (a, b) -> {
            int aStart = colorSpanned.getSpanStart(a);
            int aEnd = colorSpanned.getSpanEnd(a);
            int bStart = colorSpanned.getSpanStart(b);
            int bEnd = colorSpanned.getSpanEnd(b);
            int aLen = aEnd - aStart;
            int bLen = bEnd - bStart;
            if (aLen != bLen) {
                return Integer.compare(bLen, aLen);
            }
            return Integer.compare(aStart, bStart);
        });

        for (android.text.style.ForegroundColorSpan colorSpan : orderedColorSpans) {
            int start = colorSpanned.getSpanStart(colorSpan);
            int end = colorSpanned.getSpanEnd(colorSpan);
            int flags = colorSpanned.getSpanFlags(colorSpan);
            if (start >= 0 && end > start && end <= ssb.length()) {
                ssb.setSpan(new android.text.style.ForegroundColorSpan(colorSpan.getForegroundColor()),
                        start,
                        end,
                        flags);
            }
        }

        applyStrikeMarkers(ssb);

        // 专栏正文经常会在段首塞入多个空格作为缩进，这会导致小屏换行错位。
        // 仅移除本段开头连续空白，不影响正文中间的空格。
        trimLeadingWhitespaceInPlace(ssb);

        if (isCaption) {
            ssb.setSpan(
                    new android.text.style.AlignmentSpan.Standard(android.text.Layout.Alignment.ALIGN_CENTER),
                    0,
                    ssb.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }

        OpusParagraph paragraph = new OpusParagraph();
        paragraph.type = OpusParagraph.TYPE_TEXT;
        paragraph.content = ssb;
        paragraphs.add(paragraph);
    }

    private static String normalizeStrikeTagsToInlineStyle(String htmlText) {
        if (htmlText == null || htmlText.isEmpty()) {
            return htmlText;
        }

        // 用一个 span 包裹，交给后续 markLineThroughRanges() 识别 line-through。
        // (?is) = ignoreCase + dotAll
        String out = htmlText
                .replaceAll("(?is)<\\s*del\\b[^>]*>", "<span style=\"text-decoration:line-through;\">")
                .replaceAll("(?is)<\\s*/\\s*del\\s*>", "</span>")
                .replaceAll("(?is)<\\s*s\\b[^>]*>", "<span style=\"text-decoration:line-through;\">")
                .replaceAll("(?is)<\\s*/\\s*s\\s*>", "</span>")
                .replaceAll("(?is)<\\s*strike\\b[^>]*>", "<span style=\"text-decoration:line-through;\">")
                .replaceAll("(?is)<\\s*/\\s*strike\\s*>", "</span>");

        return out;
    }

    private static void trimLeadingWhitespaceInPlace(android.text.SpannableStringBuilder ssb) {
        if (ssb == null || ssb.length() == 0) {
            return;
        }
        int i = 0;
        while (i < ssb.length()) {
            char c = ssb.charAt(i);
            if (Character.isWhitespace(c) || c == '\u3000' || c == '\u00A0' || c == '\uFEFF') {
                i++;
            } else {
                break;
            }
        }
        if (i > 0) {
            ssb.delete(0, i);
        }
    }

    /**
     * 低版本 Android 的 Html.fromHtml 对 span style="color:..." 兼容较差，
     * 转换为 <font color="..."> 以提升 API < 24 的颜色解析稳定性。
     */
    private static String convertSpanColorStyleToFontTag(String htmlText) {
        if (htmlText == null || htmlText.isEmpty()) {
            return htmlText;
        }

        java.util.regex.Pattern tagPattern = java.util.regex.Pattern.compile("<(/?)([A-Za-z0-9]+)([^>]*)>");
        java.util.regex.Matcher matcher = tagPattern.matcher(htmlText);
        StringBuilder out = new StringBuilder();
        java.util.ArrayDeque<Boolean> colorFontStack = new java.util.ArrayDeque<>();

        int last = 0;
        while (matcher.find()) {
            out.append(htmlText, last, matcher.start());

            String slash = matcher.group(1);
            String tagName = matcher.group(2);
            String attrs = matcher.group(3);
            boolean isClosing = slash != null && !slash.isEmpty();

            if (!"span".equalsIgnoreCase(tagName)) {
                out.append(matcher.group(0));
                last = matcher.end();
                continue;
            }

            if (isClosing) {
                out.append(matcher.group(0));
                if (!colorFontStack.isEmpty() && colorFontStack.pop()) {
                    out.append("</font>");
                }
                last = matcher.end();
                continue;
            }

            boolean hasColor = false;
            String colorValue = null;

            java.util.regex.Pattern stylePattern = java.util.regex.Pattern.compile(
                    "\\bstyle\\s*=\\s*(['\"])(.*?)\\1",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher styleMatcher = stylePattern.matcher(attrs);
            if (styleMatcher.find()) {
                String styleContent = styleMatcher.group(2);
                java.util.regex.Matcher colorMatcher = java.util.regex.Pattern.compile(
                        "(?i)color\\s*:\\s*([^;]+)"
                ).matcher(styleContent == null ? "" : styleContent);
                if (colorMatcher.find()) {
                    hasColor = true;
                    colorValue = colorMatcher.group(1);
                    if (colorValue != null) {
                        colorValue = colorValue.trim();
                    }
                }
            }

            if (hasColor && colorValue != null && !colorValue.isEmpty()) {
                out.append("<font color=\"").append(colorValue).append("\">");
            }
            out.append(matcher.group(0));
            colorFontStack.push(hasColor && colorValue != null && !colorValue.isEmpty());

            last = matcher.end();
        }

        out.append(htmlText.substring(last));
        return out.toString();
    }

    private static void applyStrikeMarkers(android.text.SpannableStringBuilder ssb) {
        String text = ssb.toString();
        java.util.ArrayDeque<Integer> startStack = new java.util.ArrayDeque<>();
        java.util.ArrayList<int[]> ranges = new java.util.ArrayList<>();

        int cursor = 0;
        while (cursor < text.length()) {
            int startIdx = text.indexOf(STRIKE_MARK_START, cursor);
            int endIdx = text.indexOf(STRIKE_MARK_END, cursor);

            if (startIdx == -1 && endIdx == -1) {
                break;
            }

            if (endIdx == -1 || (startIdx != -1 && startIdx < endIdx)) {
                startStack.push(startIdx);
                cursor = startIdx + STRIKE_MARK_START.length();
            } else {
                if (!startStack.isEmpty()) {
                    int startMark = startStack.pop();
                    ranges.add(new int[]{startMark, endIdx});
                }
                cursor = endIdx + STRIKE_MARK_END.length();
            }
        }

        for (int i = ranges.size() - 1; i >= 0; i--) {
            int[] range = ranges.get(i);
            int start = range[0];
            int end = range[1];
            if (start >= 0 && end > start && end <= ssb.length()) {
                ssb.setSpan(
                        new android.text.style.StrikethroughSpan(),
                        start + STRIKE_MARK_START.length(),
                        end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }
        }

        removeAllMarkerText(ssb, STRIKE_MARK_END);
        removeAllMarkerText(ssb, STRIKE_MARK_START);
    }

    private static void removeAllMarkerText(android.text.SpannableStringBuilder ssb, String marker) {
        int idx = ssb.toString().indexOf(marker);
        while (idx >= 0) {
            ssb.delete(idx, idx + marker.length());
            idx = ssb.toString().indexOf(marker, idx);
        }
    }

    /**
     * 用 marker 标记 line-through 文本范围，避免低版本 Html.fromHtml 丢失删除线 span。
     */
    private static String markLineThroughRanges(String htmlText) {
        if (htmlText == null || htmlText.isEmpty()) {
            return htmlText;
        }

        java.util.regex.Pattern tagPattern = java.util.regex.Pattern.compile("<(/?)([A-Za-z0-9]+)([^>]*)>");
        java.util.regex.Matcher matcher = tagPattern.matcher(htmlText);
        StringBuilder out = new StringBuilder();
        java.util.ArrayDeque<Boolean> strikeTagStack = new java.util.ArrayDeque<>();
        java.util.HashSet<String> voidTags = new java.util.HashSet<>();
        java.util.Collections.addAll(voidTags,
                "br", "img", "hr", "input", "meta", "link", "base", "col",
                "area", "param", "source", "track", "wbr");

        int last = 0;
        while (matcher.find()) {
            out.append(htmlText, last, matcher.start());

            String slash = matcher.group(1);
            String tagName = matcher.group(2);
            String attrs = matcher.group(3);
            boolean isClosing = slash != null && !slash.isEmpty();
            String lowerTagName = tagName == null ? "" : tagName.toLowerCase(Locale.ROOT);

            if (isClosing) {
                out.append(matcher.group(0));
                if (!strikeTagStack.isEmpty() && strikeTagStack.pop()) {
                    out.append(STRIKE_MARK_END);
                }
                last = matcher.end();
                continue;
            }

            boolean hasStrike = false;
            String newAttrs = attrs;
            boolean selfClosing = attrs != null && attrs.trim().endsWith("/");

            java.util.regex.Pattern stylePattern = java.util.regex.Pattern.compile(
                    "\\bstyle\\s*=\\s*(['\"])(.*?)\\1",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher styleMatcher = stylePattern.matcher(attrs);
            if (styleMatcher.find()) {
                String quote = styleMatcher.group(1);
                String styleContent = styleMatcher.group(2);
                if (styleContent != null && styleContent.toLowerCase(Locale.ROOT).contains("line-through")) {
                    hasStrike = true;
                    String cleanedStyle = styleContent.replaceAll(
                            "(?i)text-decoration(?:-line)?\\s*:[^;]*line-through[^;]*;?",
                            ""
                    ).trim();
                    if (cleanedStyle.isEmpty()) {
                        newAttrs = styleMatcher.replaceFirst("");
                    } else {
                        if (!cleanedStyle.endsWith(";")) {
                            cleanedStyle += ";";
                        }
                        newAttrs = styleMatcher.replaceFirst("style=" + quote + cleanedStyle + quote);
                    }
                }
            }

            if (hasStrike) {
                out.append(STRIKE_MARK_START);
            }
            out.append("<").append(tagName).append(newAttrs).append(">");

            if (selfClosing || voidTags.contains(lowerTagName)) {
                if (hasStrike) {
                    out.append(STRIKE_MARK_END);
                }
            } else {
                strikeTagStack.push(hasStrike);
            }
            last = matcher.end();
        }

        out.append(htmlText.substring(last));
        return out.toString();
    }

    private static String applyArticleColorClassMapping(String htmlText) {
        if (htmlText == null || htmlText.isEmpty()) {
            return htmlText;
        }

        java.util.HashMap<String, String> colorMap = new java.util.HashMap<>();
        colorMap.put("color-blue-01", "#56c1fe");
        colorMap.put("color-blue-02", "#02a2ff");
        colorMap.put("color-blue-03", "#0176ba");
        colorMap.put("color-blue-04", "#004e80");
        colorMap.put("color-gray-01", "#d6d5d5");
        colorMap.put("color-gray-02", "#929292");
        colorMap.put("color-gray-03", "#5f5f5f");
        colorMap.put("color-green-01", "#89fa4e");
        colorMap.put("color-green-02", "#60d837");
        colorMap.put("color-green-03", "#1db100");
        colorMap.put("color-green-04", "#017001");
        colorMap.put("color-lblue-01", "#73fdea");
        colorMap.put("color-lblue-02", "#18e7cf");
        colorMap.put("color-lblue-03", "#068f86");
        colorMap.put("color-lblue-04", "#017c76");
        colorMap.put("color-pink-01", "#ff968d");
        colorMap.put("color-pink-02", "#ff654e");
        colorMap.put("color-pink-03", "#ee230d");
        colorMap.put("color-pink-04", "#b41700");
        colorMap.put("color-purple-01", "#ff8cc6");
        colorMap.put("color-purple-02", "#ef5fa8");
        colorMap.put("color-purple-03", "#cb297a");
        colorMap.put("color-purple-04", "#99195e");
        colorMap.put("color-yellow-01", "#fff359");
        colorMap.put("color-yellow-02", "#fbe231");
        colorMap.put("color-yellow-03", "#f8ba00");
        colorMap.put("color-yellow-04", "#ff9201");

        java.util.regex.Pattern classTagPattern = java.util.regex.Pattern.compile(
                "<[^>]*\\bclass\\s*=\\s*(['\"])([^'\"]*)\\1[^>]*>",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = classTagPattern.matcher(htmlText);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String tag = matcher.group(0);
            String classValue = matcher.group(2);

            String mappedColor = null;
            String[] classes = classValue.trim().split("\\s+");
            for (String cls : classes) {
                if (colorMap.containsKey(cls)) {
                    mappedColor = colorMap.get(cls);
                    break;
                }
            }

            if (mappedColor == null) {
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(tag));
                continue;
            }

            String updatedTag = tag;
            java.util.regex.Pattern stylePattern = java.util.regex.Pattern.compile(
                    "\\bstyle\\s*=\\s*(['\"])(.*?)\\1",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher styleMatcher = stylePattern.matcher(updatedTag);

            if (styleMatcher.find()) {
                String quote = styleMatcher.group(1);
                String styleContent = styleMatcher.group(2);
                String newStyle = styleContent;
                if (styleContent.toLowerCase(Locale.ROOT).contains("color:")) {
                    newStyle = styleContent.replaceAll("(?i)color\\s*:[^;]+;?", "color:" + mappedColor + ";");
                } else {
                    if (!newStyle.trim().isEmpty() && !newStyle.trim().endsWith(";")) {
                        newStyle += ";";
                    }
                    newStyle += "color:" + mappedColor + ";";
                }
                updatedTag = styleMatcher.replaceFirst("style=" + quote + newStyle + quote);
            } else {
                int insertPos = updatedTag.lastIndexOf('>');
                if (insertPos > 0) {
                    updatedTag = updatedTag.substring(0, insertPos)
                            + " style=\"color:" + mappedColor + ";\""
                            + updatedTag.substring(insertPos);
                }
            }

            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(updatedTag));
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    public static OpusParagraph[] analyzeParagraphs(JSONArray jsonArray) throws JSONException {
        OpusParagraph[] paragraphs = new OpusParagraph[jsonArray.length()];
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject paragraphJson = jsonArray.getJSONObject(i);
            OpusParagraph paragraph = new OpusParagraph(paragraphJson);
            paragraphs[i] = paragraph;
        }
        return paragraphs;
    }

    public static void analyzeOldStyleDynamic(Opus opus, JSONObject item) throws JSONException {
        JSONObject basic = item.getJSONObject("basic");
        opus.commentId = Long.parseLong(basic.optString("comment_id_str", "0"));
        opus.commentType = basic.optInt("comment_type");

        String dynamicType = item.optString("type", "");

        if (item.isNull("modules")) return;
        JSONObject modules = item.getJSONObject("modules");

        //up主信息
        UserInfo author = new UserInfo();
        if (!modules.isNull("module_author")) {
            JSONObject module_author = modules.getJSONObject("module_author");
            author.mid = module_author.optLong("mid");
            author.name = module_author.optString("name", "");
            author.followed = module_author.optBoolean("following", false);
            author.avatar = module_author.optString("face", "");
            if (!module_author.isNull("vip"))
                author.vip_nickname_color = module_author.getJSONObject("vip").optString("nickname_color", "");
            opus.pubTime = module_author.optString("pub_time", "");
        }
        opus.upInfo = author;

        if (dynamicType.equals("DYNAMIC_TYPE_NONE")) {
            opus.content = "[动态不存在]";
            return;
        }

        //动态主体内容
        if (modules.isNull("module_dynamic")) return;
        JSONObject module_dynamic = modules.getJSONObject("module_dynamic");

        ArrayList<OpusParagraph> paragraphList = new ArrayList<>();

        if (!module_dynamic.isNull("desc")) {
            JSONObject desc = module_dynamic.getJSONObject("desc");
            JSONArray richTextNodes = desc.optJSONArray("rich_text_nodes");
            if (richTextNodes != null) {
                JSONObject object = new JSONObject();
                object.put("para_type", OpusParagraph.TYPE_TEXT_OPUS);
                object.put("data", richTextNodes);
                paragraphList.add(new OpusParagraph(object));
            }
        }

        if (!module_dynamic.isNull("major")) {
            JSONObject major = module_dynamic.getJSONObject("major");

            if (!major.isNull("opus")) {
                JSONObject dynamic_opus = major.getJSONObject("opus");

                // 为了排版正常，这里必须把列表完整传递给OpusParagraph，让OpusParagraph那边解析
                // 这么干主要是为了适配这神秘的代码结构，我研究OpusParagraph的使用方法就研究了半天
                // by Moye

                // 标题
                String title = dynamic_opus.optString("title", "");
                if (!title.isEmpty() && !"null".equals(title)) {
                    opus.title = title;
                }

                // 文字内容
                JSONObject summary = dynamic_opus.optJSONObject("summary");
                if (summary != null) {
                    JSONArray richTextNodes = summary.optJSONArray("rich_text_nodes");
                    if (richTextNodes != null) {
                        JSONObject object = new JSONObject();
                        object.put("para_type", OpusParagraph.TYPE_TEXT_OPUS);
                        object.put("data", richTextNodes);
                        paragraphList.add(new OpusParagraph(object));
                    }
                }

                // 图片（可能不存在，纯文字动态没有图片）
                JSONArray opus_pics = dynamic_opus.optJSONArray("pics");
                if (opus_pics != null && opus_pics.length() > 0) {
                    JSONObject object = new JSONObject();
                    object.put("para_type", OpusParagraph.TYPE_PIC);
                    object.put("pic", new JSONObject().put("pics", opus_pics));
                    paragraphList.add(new OpusParagraph(object));
                }
            }

            // MAJOR_TYPE_DRAW 类型（旧版图片动态格式）
            if (!major.isNull("draw")) {
                JSONObject draw = major.getJSONObject("draw");
                JSONArray items = draw.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    JSONArray pics = new JSONArray();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject drawItem = items.getJSONObject(i);
                        JSONObject pic = new JSONObject();
                        pic.put("url", drawItem.optString("src"));
                        pics.put(pic);
                    }
                    JSONObject object = new JSONObject();
                    object.put("para_type", OpusParagraph.TYPE_PIC);
                    object.put("pic", new JSONObject().put("pics", pics));
                    paragraphList.add(new OpusParagraph(object));
                }
            }

            if (!major.isNull("archive")) {
                // 这里是视频卡片
            }

        }

        opus.paragraphs = paragraphList.toArray(new OpusParagraph[0]);

        // 解析统计信息（使用安全方式）
        Stats stats = new Stats();
        if (!modules.isNull("module_stat")) {
            JSONObject module_stat = modules.getJSONObject("module_stat");
            if (module_stat.has("comment"))
                stats.reply = module_stat.getJSONObject("comment").optInt("count", 0);
            if (module_stat.has("like"))
                stats.like = module_stat.getJSONObject("like").optInt("count", 0);
        }
        opus.stats = stats;
    }

    private static long extractListIdFromBasic(JSONObject basic) {
        if (basic == null) {
            return 0;
        }
        String[] keys = new String[]{
                "list_id",
                "list_id_str",
                "collection_id",
                "collection_id_str",
                "article_list_id",
                "article_list_id_str",
                "listId",
                "collectionId"
        };
        return findIdInJson(basic, keys);
    }

    private static long extractListIdFromItem(JSONObject item) {
        if (item == null) {
            return 0;
        }
        JSONObject list = item.optJSONObject("list");
        if (list != null) {
            long id = list.optLong("id", 0);
            if (id > 0) {
                return id;
            }
        }
        Object modulesObj = item.opt("modules");
        if (modulesObj instanceof JSONArray) {
            JSONArray modules = (JSONArray) modulesObj;
            for (int i = 0; i < modules.length(); i++) {
                JSONObject module = modules.optJSONObject(i);
                if (module == null) {
                    continue;
                }
                long moduleId = extractListIdFromModule(module);
                if (moduleId > 0) {
                    return moduleId;
                }
            }
        }
        String[] keys = new String[]{
                "list_id",
                "list_id_str",
                "collection_id",
                "collection_id_str",
                "article_list_id",
                "article_list_id_str",
                "listId",
                "collectionId"
        };
        return findIdInJson(item, keys);
    }

    private static long extractListIdFromModule(JSONObject module) {
        String moduleType = module.optString("module_type", "");
        if (!moduleType.isEmpty()) {
            String lower = moduleType.toLowerCase(Locale.ROOT);
            if (lower.contains("list") || lower.contains("collect")) {
                long id = findIdInJson(module, new String[]{
                        "list_id",
                        "list_id_str",
                        "collection_id",
                        "collection_id_str",
                        "article_list_id",
                        "article_list_id_str",
                        "listId",
                        "collectionId"
                });
                if (id > 0) {
                    return id;
                }
            }
        }
        return 0;
    }

    private static long resolveArticleCvidFromDynamicItem(JSONObject item) {
        if (item == null) {
            return 0;
        }

        JSONObject basic = item.optJSONObject("basic");
        String dynamicType = item.optString("type", "");
        int commentType = basic == null ? 0 : basic.optInt("comment_type", 0);
        if (commentType != 12 && !"DYNAMIC_TYPE_ARTICLE".equals(dynamicType)) {
            return 0;
        }

        long cvid = 0;
        if (basic != null) {
            cvid = parseIdValue(basic.opt("comment_id_str"));
            if (cvid <= 0) {
                cvid = parseIdValue(basic.opt("rid_str"));
            }
            if (cvid <= 0) {
                cvid = extractArticleCvidFromUrl(basic.optString("jump_url", ""));
            }
        }

        JSONObject modules = item.optJSONObject("modules");
        if (cvid <= 0 && modules != null) {
            JSONObject moduleDynamic = modules.optJSONObject("module_dynamic");
            if (moduleDynamic != null) {
                JSONObject major = moduleDynamic.optJSONObject("major");
                if (major != null && "MAJOR_TYPE_ARTICLE".equals(major.optString("type", ""))) {
                    JSONObject article = major.optJSONObject("article");
                    if (article != null) {
                        cvid = parseIdValue(article.opt("id"));
                    }
                }
            }
        }
        return cvid;
    }

    private static long extractArticleCvidFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return 0;
        }
        Matcher matcher = Pattern.compile("(?:read/cv|/cv)(\\d+)").matcher(url);
        if (matcher.find()) {
            return parseIdValue(matcher.group(1));
        }
        return 0;
    }

    private static long findIdInJson(Object node, String[] keys) {
        java.util.HashSet<String> keySet = new java.util.HashSet<>();
        for (String key : keys) {
            keySet.add(key);
        }
        return findIdInJson(node, keySet);
    }

    private static long findIdInJson(Object node, java.util.Set<String> keySet) {
        if (node == null) {
            return 0;
        }
        if (node instanceof JSONObject) {
            JSONObject jsonObject = (JSONObject) node;
            java.util.Iterator<String> iterator = jsonObject.keys();
            while (iterator.hasNext()) {
                String key = iterator.next();
                Object value = jsonObject.opt(key);
                if (keySet.contains(key)) {
                    long parsed = parseIdValue(value);
                    if (parsed > 0) {
                        return parsed;
                    }
                }
                long nested = findIdInJson(value, keySet);
                if (nested > 0) {
                    return nested;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                long nested = findIdInJson(array.opt(i), keySet);
                if (nested > 0) {
                    return nested;
                }
            }
        }
        return 0;
    }

    private static long parseIdValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String raw = value.toString().trim();
        if (raw.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
        }
        String digits = raw.replaceAll("\\D+", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
