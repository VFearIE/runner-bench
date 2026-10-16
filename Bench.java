// 纯算力基准：不联网、不依赖缓存、三平台用同一份代码与同一个 JDK。
// 目的：隔离出「runner 本身的算力差异」，用于把已测得的项目构建耗时按比例外推。
public class Bench {
    // 定点递推：无分支、无可向量化的明显机会，接近编译器/R8 这类串行 JVM 负载
    static long work(long n) {
        long x = 1;
        for (long i = 0; i < n; i++) x = x * 6364136223846793005L + 1442695040888963407L;
        return x;
    }

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        long N = 300_000_000L;

        // 预热，让 JIT 完成编译，测的是稳态吞吐而非解释执行
        work(N / 10);

        long t0 = System.nanoTime();
        long sink = work(N);
        double singleMs = (System.nanoTime() - t0) / 1e6;

        // 多核：每核一份相同工作量，取墙钟时间
        long t1 = System.nanoTime();
        Thread[] ts = new Thread[cores];
        for (int i = 0; i < cores; i++) {
            ts[i] = new Thread(() -> sinkAdd.set(sinkAdd.get() ^ work(N)));
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        double multiMs = (System.nanoTime() - t1) / 1e6;

        // 内存分配带宽：反复申请 8MB 并触碰首尾页
        long t2 = System.nanoTime();
        long msink = 0;
        for (int i = 0; i < 256; i++) {
            byte[] b = new byte[8 * 1024 * 1024];
            b[0] = 1; b[b.length - 1] = 2;
            msink += b[0] + b[b.length - 1];
        }
        double memMs = (System.nanoTime() - t2) / 1e6;

        System.out.printf("RESULT cores=%d single_ms=%.0f multi_ms=%.0f mem_ms=%.0f sink=%d%n",
                cores, singleMs, multiMs, memMs, sink + msink);
    }

    static final java.util.concurrent.atomic.AtomicLong sinkAdd =
            new java.util.concurrent.atomic.AtomicLong(0);
}
