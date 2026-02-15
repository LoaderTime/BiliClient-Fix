package com.RobinNotBad.BiliClient.util;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.util.Consumer;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.Dispatchers;

/**
 * @author silent碎月
 * 核心运行线程池
 * BuildersKt.launch 系列调用可以在java端调起协程,更加轻量
 */
public class CenterThreadPool {

    private static final String TAG = "CenterThreadPool";

    private static final boolean FORCE_DISABLED = false;
    private static final Handler MAIN_THREAD_HANDLER = new Handler(Looper.getMainLooper());
    private static final CoroutineScope COROUTINE_SCOPE;
    private static final AtomicReference<ExecutorService> THREAD_POOL = new AtomicReference<>();
    private static final AtomicInteger FALLBACK_THREAD_ID = new AtomicInteger(0);
    private static final AtomicInteger EXECUTOR_THREAD_ID = new AtomicInteger(0);

    private static ExecutorService getThreadPoolInstance() {
        ExecutorService existed = THREAD_POOL.get();
        if (existed != null) return existed;

        // 兼容：部分设备 availableProcessors 可能返回 0 或 1，需做下限保护
        int cpu = Math.max(1, Runtime.getRuntime().availableProcessors());
        int corePoolSize = Math.max(1, cpu / 2);
        int maxPoolSize = Math.max(corePoolSize, cpu * 2);

        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r);
            t.setName("CenterThreadPool-" + EXECUTOR_THREAD_ID.incrementAndGet());
            return t;
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60,
                TimeUnit.SECONDS,
                // 注意：这里队列不要太小，否则旧设备上容易触发拒绝策略导致任务丢失。
                new ArrayBlockingQueue<>(64),
                threadFactory
        );
        executor.allowCoreThreadTimeOut(true);

        if (THREAD_POOL.compareAndSet(null, executor)) {
            return executor;
        }

        // 竞争失败，释放刚创建的 executor，返回已存在实例
        try {
            executor.shutdown();
        } catch (Throwable ignored) {
        }
        return THREAD_POOL.get();
    }

    static {
        CoroutineScope scope = null;
        if (!FORCE_DISABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            try {
                scope = CoroutineScopeKt.CoroutineScope((CoroutineContext) Dispatchers.getIO());
            } catch (Throwable t) {
                // 协程初始化失败时（例如依赖缺失/被裁剪/ROM兼容问题），回退到 Java 线程池
                Log.w(TAG, "Coroutine init failed, fallback to ThreadPoolExecutor", t);
                scope = null;
            }
        }
        COROUTINE_SCOPE = scope;
    }


    /**
     * 在后台运行, 用于网络请求等耗时操作
     *
     * @param runnable 要运行的任务
     */
    public static void run(Runnable runnable) {
        if (runnable == null) return;

        // 兜底：避免后台任务异常直接触发全局 UncaughtExceptionHandler 导致整个进程崩溃
        Runnable safeRunnable = () -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                Log.e(TAG, "Uncaught exception in background task", t);
            }
        };

        // 强制禁用（调试/兼容用途）
        if (FORCE_DISABLED) {
            startFallbackThread(safeRunnable);
            return;
        }

        // 优先协程
        if (COROUTINE_SCOPE != null) {
            try {
                BuildersKt.launch(COROUTINE_SCOPE, EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT, (CoroutineScope scope, Continuation<? super Unit> continuation) -> {
                    safeRunnable.run();
                    return Unit.INSTANCE;
                });
                return;
            } catch (Throwable t) {
                Log.w(TAG, "Coroutine launch failed, fallback to ThreadPoolExecutor", t);
            }
        }

        // 协程不可用则回退线程池
        try {
            ExecutorService service = getThreadPoolInstance();
            if (service != null) {
                service.submit(safeRunnable);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Executor submit failed, fallback to new Thread", t);
        }

        // 最终兜底，保证任务不丢
        startFallbackThread(safeRunnable);
    }

    private static void startFallbackThread(Runnable runnable) {
        Thread t = new Thread(runnable);
        t.setName("CenterThreadPool-fallback-" + FALLBACK_THREAD_ID.incrementAndGet());
        t.start();
    }

    /**
     * 在后台运行, 用于网络请求等耗时操作, 有返回值,
     * 在fragment, activity等位置使用LiveData.observe()获取返回值, 会自动切到主线程,不需要再runOnUiThread().
     *
     * @param supplier 要运行的任务
     * @param <T>      返回值类型
     * @return LiveData包装的返回值
     */
    public static <T> LiveData<Result<T>> supplyAsyncWithLiveData(Callable<T> supplier) {
        MutableLiveData<Result<T>> retval = new MutableLiveData<>();
        CenterThreadPool.run(() -> {
            try {
                T res = supplier.call();
                retval.postValue(Result.success(res));
            } catch (Exception e) {
                retval.postValue(Result.failure(e));
                MsgUtil.err(e);
            }
        });
        return retval;
    }

    /**
     * 在后台运行， 有返回值
     * 使用 CenterThreadPool.observe方法对返回值进行观察
     *
     * @param supplier 一个带返回值的lambda表达式或Supplier的实现类
     * @param <T>      返回值类型
     * @return 返回一个可供CenterThreadPool观察的Future对象
     */
    public static <T> Future<T> supplyAsyncWithFuture(Callable<T> supplier) {
        FutureTask<T> ftask = new FutureTask<>(supplier);
        CenterThreadPool.run(ftask);
        return ftask;
    }

    /**
     * 对Deferred 对象进行观察， 无需切换线程， 自动在ui线程进行观察
     *
     * @param deferred 一个将要在未来返回一个 T 类型对象的对象
     * @param consumer 对T进行观察的lambda表达式或者类
     * @param <T>      要观察的类型
     */
    public static <T> void observe(Future<T> deferred, Consumer<T> consumer) {
        CenterThreadPool.run(() -> {
            try {
                T value = deferred.get();
                CenterThreadPool.runOnUiThread(() -> consumer.accept(value));
            } catch (Throwable t) {
                Log.w(TAG, "observe() failed", t);
            }
        });
    }


    public static <T> void observe(Future<T> future, Consumer<T> consumer, Consumer<Throwable> onFailure) {
        CenterThreadPool.run(() -> {
            try {
                T value = future.get();
                CenterThreadPool.runOnUiThread(() -> consumer.accept(value));
            } catch (Exception e) {
                onFailure.accept(e);
            }
        });
    }

    /**
     * 在主线程运行, 用于更新UI, 例如Toast, Snackbar等
     *
     * @param runnable 要运行的任务
     */
    public static void runOnUiThread(Runnable runnable) {
        MAIN_THREAD_HANDLER.post(runnable);
    }

    public static void runOnUIThreadAfter(long time, TimeUnit unit, Runnable runnable) {
        long millis = TimeUnit.MILLISECONDS.convert(time, unit);
        MAIN_THREAD_HANDLER.postDelayed(runnable, millis);
    }

    public static void runOnUIThreadAfter(long time, Runnable runnable) {
        MAIN_THREAD_HANDLER.postDelayed(runnable, time);
    }

}