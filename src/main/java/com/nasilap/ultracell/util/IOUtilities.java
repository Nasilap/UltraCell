package com.nasilap.ultracell.util;

import com.nasilap.ultracell.UltraCell;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 外置存储的磁盘 I/O 工具。
 *
 * <p>所有 {@code .dat} 的落盘都走这里的**单线程** I/O worker：
 * <ul>
 *   <li>主线程只负责把内存状态**冻结成 NBT 快照**（必须带注册表，故必须在主线程做）</li>
 *   <li>真正的压缩写盘在 I/O 线程完成，主线程不阻塞</li>
 *   <li>单线程保证同一文件的写入顺序与提交顺序一致</li>
 * </ul>
 *
 * <p>关服时必须调用 {@link #waitUntilIOWorkerComplete()} 把队列排空，
 * 否则最后一批快照可能还没落盘就退出进程。
 *
 * <p>注意：AE2 19.2.17 **没有**提供现成的 IO worker 工具，本类是自建的。
 */
public final class IOUtilities {

    /** 单线程 I/O worker；守护线程，避免卡住 JVM 退出。 */
    private static final ExecutorService IO_WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "UltraCell-IO");
        thread.setDaemon(true);
        return thread;
    });

    /** 排队等待的超时（秒）。 */
    private static final long BARRIER_TIMEOUT_SECONDS = 30L;

    private IOUtilities() {}

    /**
     * 把一个写盘任务丢进 I/O 线程。
     *
     * <p>任务抛出的任何异常都只记日志，不向外传播 —— 单个文件写失败不应中断整批。
     */
    public static void withIOWorker(Runnable task) {
        IO_WORKER.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                UltraCell.LOGGER.error("Ultra Cell: I/O worker task failed", t);
            }
        });
    }

    /**
     * 等待所有已排队的 I/O 任务完成。
     *
     * <p>实现方式：提交一个空任务作为栅栏，单线程队列保证它排在所有已提交任务之后。
     */
    public static void waitUntilIOWorkerComplete() {
        try {
            Future<?> barrier = IO_WORKER.submit(() -> {});
            barrier.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            UltraCell.LOGGER.error("Ultra Cell: timed out or failed while waiting for I/O worker", e);
        }
    }
}
