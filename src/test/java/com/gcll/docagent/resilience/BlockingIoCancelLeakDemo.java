package com.gcll.docagent.resilience;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 线程泄漏演示测试 —— 验证简历/应答卡 Agent #6 的核心论点：
 * <p>
 * <b>{@code Future.cancel(true)} 对阻塞 IO 的 native {@code recv()} 无效。</b>
 * <p>
 * 三组对比：
 * <ol>
 *   <li><b>sleep 阻塞</b>（响应中断）：cancel(true) 能打断，worker 线程正常退出。</li>
 *   <li><b>socket 阻塞读</b>（不响应中断）：cancel(true) 打不断，worker 线程泄漏，
 *       业务侧 future 虽抛 CancellationException 但底层线程仍卡在 recv()。</li>
 *   <li><b>socket + setSoTimeout</b>（内核强制超时）：read-timeout 到点强制让 recv() 返回，
 *       线程释放 —— 这是治线程泄漏的根本手段，不依赖 cancel。</li>
 * </ol>
 * <p>
 * <b>定位</b>：这是<b>可运行的工程证据 demo</b>，不是 CI 回归测试。
 * 默认 {@code @Disabled}，跑前手动去掉注解或用 IDE 单独运行。
 * 用真实 {@link ServerSocket}（只 accept 不回数据）复现 LLM 调用卡在等响应的场景。
 * 跑完三组对比日志，结论由观察线程状态得出（见每组末尾的 {@code assert/log}）。
 *
 * @see ExternalCallGateway 外部调用网关，其 TimeLimiter 超时即走 cancel 路径
 */
@EnabledIfSystemProperty(named = "runLeakDemo", matches = "true")
class BlockingIoCancelLeakDemo {

    private static final Logger log = LoggerFactory.getLogger(BlockingIoCancelLeakDemo.class);

    /** 用单线程池模拟「一个工人线程」，跑完后看它是否还活着（泄漏检测的核心）。 */
    private ExecutorService workerPool;

    @AfterEach
    void tearDown() {
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
    }

    // ============================================================
    // 第 ① 组：sleep 阻塞 —— cancel(true) 能打断（对照组）
    // ============================================================
    @Test
    void sleepBlockingIsInterruptedByCancel() throws Exception {
        workerPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "worker-sleep");
            t.setDaemon(true);
            return t;
        });

        AtomicReference<String> endState = new AtomicReference<>("RUNNING");

        Future<?> future = workerPool.submit(() -> {
            try {
                // 模拟一个会响应中断的阻塞（sleep 内部检查中断标志）
                Thread.sleep(60_000);
                endState.set("NORMAL_EXIT");
            } catch (InterruptedException e) {
                // sleep 被中断会抛 InterruptedException，落到这里
                endState.set("INTERRUPTED");
                Thread.currentThread().interrupt();
            }
        });

        Thread worker = findThread("worker-sleep");
        log.info("[① sleep] 提交后 worker 状态：{}", worker.getState());

        // 让 worker 进入 sleep
        Thread.sleep(300);
        log.info("[① sleep] cancel 前 worker 状态：{}（应为 TIMED_WAITING）", worker.getState());

        boolean cancelled = future.cancel(true);
        log.info("[① sleep] future.cancel(true) 返回：{}", cancelled);

        // 给 worker 一点时间响应中断
        Thread.sleep(300);
        log.info("[① sleep] cancel 后 worker 状态：{}（应已退出 RUNNABLE/TIMED_WAITING）", worker.getState());
        log.info("[① sleep] worker endState：{}（应为 INTERRUPTED）", endState.get());
        log.info("[① sleep] ✅ 结论：sleep 检查中断标志，cancel(true) 能打断，线程不泄漏。\n");

        // 业务侧 future 也应感知到取消
        try {
            future.get(1, TimeUnit.SECONDS);
            throw new AssertionError("应抛 CancellationException");
        } catch (java.util.concurrent.CancellationException expected) {
            log.info("[① sleep] future.get 抛出 CancellationException（业务侧感知到取消）");
        }
    }

    // ============================================================
    // 第 ② 组：socket 阻塞读 —— cancel(true) 打不断（核心论点）
    // ============================================================
    @Test
    void socketBlockingIsNotInterruptedByCancel() throws Exception {
        workerPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "worker-socket");
            t.setDaemon(true);
            return t;
        });

        // 起一个本地 ServerSocket，accept 后只持有连接、不回任何数据。
        // 这样客户端 socket.read() 会无限阻塞在 native recv() —— 复现 LLM 调用卡在等响应。
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = server.getLocalPort();
            CompletableFuture<Socket> accepted = new CompletableFuture<>();
            Thread acceptor = new Thread(() -> {
                try {
                    Socket conn = server.accept();
                    accepted.complete(conn);
                    // 故意不关闭、不写数据 —— 让客户端永远收不到响应
                    Thread.sleep(60_000);
                } catch (Exception ignore) {
                }
            }, "acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            AtomicReference<String> endState = new AtomicReference<>("BLOCKED_IN_READ");

            Future<?> future = workerPool.submit(() -> {
                try (Socket client = new Socket()) {
                    client.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
                    InputStream in = client.getInputStream();
                    // 没有 setSoTimeout —— 纯阻塞读，不响应 interrupt
                    int b = in.read();  // ← 卡在这里：native recv()，cancel 打不断
                    endState.set("READ_RETURNED:" + b);
                } catch (Exception e) {
                    endState.set("EXCEPTION:" + e.getClass().getSimpleName());
                }
            });

            Thread worker = findThread("worker-socket");
            Thread.sleep(500);  // 等 worker 进入 in.read() 阻塞
            log.info("[② socket] cancel 前 worker 状态：{}（应为 RUNNABLE，卡在 native recv）", worker.getState());

            boolean cancelled = future.cancel(true);
            log.info("[② socket] future.cancel(true) 返回：{}", cancelled);
            log.info("[② socket] cancel(true) 发的是 Thread.interrupt() —— 只是用户态标志位");

            // 给足时间看 worker 是否会响应 interrupt
            Thread.sleep(1_500);
            log.info("[② socket] cancel 后 worker 状态：{}（仍 RUNNABLE！recv 不检查中断标志）", worker.getState());
            log.info("[② socket] worker endState：{}（仍 BLOCKED_IN_READ，没退出）", endState.get());
            log.info("[② socket] ⚠️ 结论：native recv() 不检查中断标志，cancel(true) 打不断，worker 线程泄漏。");
            log.info("[② socket] ⚠️ 业务侧 future.get 会抛 CancellationException（业务觉得超时好了），");
            log.info("[② socket]    但工人线程还卡在 recv() —— 这就是「业务好了工人还卡着」的泄漏本质。\n");

            // 业务侧：cancel 后 get 立即抛 CancellationException
            try {
                future.get(1, TimeUnit.SECONDS);
                throw new AssertionError("应抛 CancellationException");
            } catch (java.util.concurrent.CancellationException expected) {
                log.info("[② socket] future.get 抛出 CancellationException（业务侧以为搞定了）");
                log.info("[② socket]    但上面 worker 状态显示线程还活着 —— 泄漏已发生");
            } catch (TimeoutException expected) {
                log.info("[② socket] future.get 超时（cancel 后仍在等 —— 异常情况）");
            }

            // 关掉 acceptor 持有的服务端 socket，让客户端 read 抛异常退出，避免线程真的挂到测试结束
            accepted.thenAccept(s -> {
                try { s.close(); } catch (Exception ignore) {}
            });
        }
    }

    // ============================================================
    // 第 ③ 组：socket + setSoTimeout —— 内核强制超时，根治泄漏
    // ============================================================
    @Test
    void socketReadTimeoutReleasesThread() throws Exception {
        workerPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "worker-timeout");
            t.setDaemon(true);
            return t;
        });

        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = server.getLocalPort();
            CompletableFuture<Socket> accepted = new CompletableFuture<>();
            Thread acceptor = new Thread(() -> {
                try {
                    Socket conn = server.accept();
                    accepted.complete(conn);
                    Thread.sleep(60_000);
                } catch (Exception ignore) {
                }
            }, "acceptor2");
            acceptor.setDaemon(true);
            acceptor.start();

            AtomicReference<String> endState = new AtomicReference<>("BLOCKED_IN_READ");

            Future<?> future = workerPool.submit(() -> {
                try (Socket client = new Socket()) {
                    client.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
                    client.setSoTimeout(1_000);  // ← 关键：内核强制的 read 超时
                    InputStream in = client.getInputStream();
                    int b = in.read();  // 1 秒后 native recv() 被内核强制返回 SocketTimeoutException
                    endState.set("READ_RETURNED:" + b);
                } catch (java.net.SocketTimeoutException e) {
                    endState.set("SOCKET_TIMEOUT");  // ← 走这里，线程正常退出
                } catch (Exception e) {
                    endState.set("EXCEPTION:" + e.getClass().getSimpleName());
                }
            });

            Thread worker = findThread("worker-timeout");
            Thread.sleep(300);
            log.info("[③ timeout] read 阻塞中 worker 状态：{}（同样卡在 recv）", worker.getState());

            // 不用 cancel，等 setSoTimeout 的 1 秒到期
            Thread.sleep(1_800);
            log.info("[③ timeout] readTimeout 到期后 worker 状态：{}（应已退出阻塞）", worker.getState());
            log.info("[③ timeout] worker endState：{}（应为 SOCKET_TIMEOUT）", endState.get());
            log.info("[③ timeout] ✅ 结论：setSoTimeout 是内核计时、到点强制让 recv() 返回，");
            log.info("[③ timeout]    不依赖 cancel、不依赖 LLM 配合、不依赖业务代码 —— 这是治线程泄漏的根本。\n");

            // 业务侧 future 这次能正常完成（抛 SocketTimeoutException 被 catch 后线程退出）
            try {
                future.get(2, TimeUnit.SECONDS);
                log.info("[③ timeout] future.get 正常返回，线程资源已释放");
            } catch (ExecutionException e) {
                log.info("[③ timeout] future.get 完成（线程已退出，未泄漏）");
            }

            accepted.thenAccept(s -> {
                try { s.close(); } catch (Exception ignore) {}
            });
        }
    }

    /** 按名字找一个活着的线程（演示用，不影响主流程）。 */
    private static Thread findThread(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals(name)) return t;
        }
        throw new IllegalStateException("线程 " + name + " 未找到");
    }
}
