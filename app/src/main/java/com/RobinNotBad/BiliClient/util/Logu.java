package com.RobinNotBad.BiliClient.util;

/*
 * 未完工的log
 */


import android.util.Log;

public class Logu {
    public static boolean LOGV_ENABLED = false;
    public static boolean LOGD_ENABLED = false;
    public static boolean LOGI_ENABLED = false;

    /**
     * 是否在 Log 的 TAG 中输出调用者信息（会触发 getStackTrace，开销较大）。
     * 建议仅在 Debug 环境开启；Release 环境关闭以减少性能损耗。
     */
    public static boolean LOG_CALLER_ENABLED = true;

    private static final String DEFAULT_TAG = "BiliClient";

    private static String getTag() {
        if (!LOG_CALLER_ENABLED) return DEFAULT_TAG;
        String caller = getCaller();
        return caller != null ? caller : DEFAULT_TAG;
    }

    public static void v(String s) {
        if (!LOGV_ENABLED) return;
        Log.v(getTag(), s);
    }

    public static void i(String s) {
        if (!LOGI_ENABLED) return;
        Log.i(getTag(), s);
    }

    public static void d(String s) {
        if (!LOGD_ENABLED) return;
        Log.d(getTag(), s);
    }

    public static void w(String s) {
        Log.w(getTag(), s);
    }

    public static void e(String s) {
        Log.e(getTag(), s);
    }

    public static void wtf(String s) {
        Log.wtf(getTag(), s);
    }


    public static void v(String tag, String info) {
        if (!LOGV_ENABLED) return;
        Log.v(getTag(), tag + ">" + info);
    }

    public static void i(String tag, String info) {
        if (!LOGI_ENABLED) return;
        Log.i(getTag(), tag + ">" + info);
    }

    public static void d(String tag, String info) {
        if (!LOGD_ENABLED) return;
        Log.d(getTag(), tag + ">" + info);
    }

    public static void w(String tag, String info) {
        Log.w(getTag(), tag + ">" + info);
    }

    public static void e(String tag, String info) {
        Log.e(getTag(), tag + ">" + info);
    }

    public static void wtf(String tag, String info) {
        Log.wtf(getTag(), tag + ">" + info);
    }

    private static String getCaller() {
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            boolean reachedThisClass = false;
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                if (Logu.class.getName().equals(className)) {
                    reachedThisClass = true;
                    continue;
                }
                if (!reachedThisClass) continue;

                // 找到第一个离开 Logu 的调用栈帧，即为真实调用者
                String name = className;
                int index = name.lastIndexOf('.');
                if (index >= 0 && index + 1 < name.length()) {
                    name = name.substring(index + 1);
                }
                return name + ">" + element.getMethodName();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
