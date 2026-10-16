// 纯算力基准：不联网、不依赖缓存、三平台同码同 JDK。
// 目的：隔离出「runner 本身的算力差异」，用于把项目构建耗时按比例外推。
//
// 设计要点：
//  1) 预热后再计时，测的是 JIT 稳态吞吐，不是解释执行；
//  2) 多核负载用「每线程独占槽位」写回，禁止共享原子量 —— 否则缓存行争用
//     会把并行结果压到与单核持平，测不出并行度（第一版就踩了这个坑）；
//  3) 三个子项分别对应真实构建负载的不同侧面：
//       seq   —— 纯串行算术：对应 R8/编译器这类单线程 IR 变换
//       par   —— 同等工作量分摊到 N 核：衡量并行阶段的可扩展性
//       alloc —— 反复申请并触碰内存：衡量 GC / 分配带宽
public class Bench {

    /** 定点线性同余递推：数据依赖强、无可向量化空间，接近编译器前端的串行负载。 */
    static long work(long n) {
        long x = 1;
        for (long i = 0; i < n; i++) x = x * 6364136223846793005L + 1442695040888963407L;
        return x;
    }

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        long N = 400_000_000L;

        work(N / 10);                       // 预热，触发 JIT C2 编译

        long t0 = System.nanoTime();
        long sink = work(N);
        double seqMs = (System.nanoTime() - t0) / 1e6;

        // 多核：每线程写自己的槽位，事后归并 —— 无共享写，才能反映真实并行度
        long[] slot = new long[cores];
        long t1 = System.nanoTime();
        Thread[] ts = new Thread[cores];
        for (int i = 0; i < cores; i++) {
            final int k = i;
            ts[i] = new Thread(() -> { slot[k] = work(N); });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        double parMs = (System.nanoTime() - t1) / 1e6;

        for (long v : slot) sink ^= v;

        // 分配带宽：反复申请 8MB 并触碰首尾页（迫使真实映射与清零）
        long t2 = System.nanoTime();
        long memSink = 0;
        for (int i = 0; i < 256; i++) {
            byte[] b = new byte[8 * 1024 * 1024];
            b[0] = 1; b[b.length - 1] = 2;
            memSink += b[0] + b[b.length - 1];
        }
        double allocMs = (System.nanoTime() - t2) / 1e6;

        // 并行加速比 = 串行单份耗时 / 并行 N 份墙钟耗时
        double speedup = seqMs / parMs;

        System.out.printf(
            "RESULT cores=%d seq_ms=%.0f par_ms=%.0f alloc_ms=%.0f speedup=%.2f sink=%d%n",
            cores, seqMs, parMs, allocMs, speedup, sink + memSink);
    }
}
