package io.github.zensu357.camswap;

/**
 * 交付限流器：把「注入到目标 App 麦克风的推流音频」按 <b>1 倍实时</b>限量交付（纯逻辑，无 Android 依赖）。
 *
 * <p>问题：目标 App（微信/LiteAV）以远快于实时（实测约 6.7x）的速度<b>非阻塞连续快读</b>
 * {@code AudioRecord}。若每次都 1:1 供给，就会把预攒音频瞬间抽干 → 快放/断续。
 *
 * <p>本类按墙钟维护「到现在应交付的真实字节」（漏桶）。每次读取只返回其中<b>尚未交付</b>的部分
 * （上限为本次请求量）；多余的快读拿 0——正是真实非阻塞麦克风「暂无新数据」时的行为。
 * 由此无论目标 App 读多快，真实音频恒以 1 倍速交付；<b>不 sleep、不阻塞读取线程</b>。
 *
 * <p>漏桶容量上限 {@code maxBacklog}（约 50ms）：即使目标 App 中途停顿后恢复，也只补最多 50ms，
 * 绝不追赶式快放；启动时给一桶（约 50ms）作为最小起播量。
 */
final class StreamRatePacer {

    /** 漏桶容量与启动预给量对应的时长（毫秒）。 */
    private static final int BUCKET_MS = 50;

    private long anchorMs = -1;
    private long served;   // 自锚点起已交付的真实字节（初值 -bucket，给启动一桶）
    private int bytesPerSec;

    synchronized void reset() {
        anchorMs = -1;
        served = 0;
        bytesPerSec = 0;
    }

    /**
     * @param requested      本次读取请求的字节数
     * @param curBytesPerSec 目标格式字节率（sampleRate*channels*bytesPerSample）
     * @param nowMs          当前墙钟（单调递增）
     * @return 本次允许交付的真实音频字节数（0..requested），按 1 倍实时限流
     */
    synchronized long allowed(long requested, int curBytesPerSec, long nowMs) {
        if (curBytesPerSec <= 0 || requested <= 0) {
            return 0;
        }
        long maxBacklog = (long) curBytesPerSec * BUCKET_MS / 1000;
        if (anchorMs < 0 || bytesPerSec != curBytesPerSec) {
            // 首次 / 格式变更：锚定墙钟，预置一桶启动量（served 为负 = 可先交付 maxBacklog）
            anchorMs = nowMs;
            bytesPerSec = curBytesPerSec;
            served = -maxBacklog;
        }
        long due = (nowMs - anchorMs) * bytesPerSec / 1000; // 到现在应交付的真实字节
        long backlog = due - served;
        if (backlog > maxBacklog) {
            // 积压超桶（如中途停顿后恢复）→ 丢弃多余允许量，最多补 maxBacklog，绝不追赶
            served = due - maxBacklog;
            backlog = maxBacklog;
        }
        if (backlog <= 0) {
            return 0; // 已交付到位，本次无新数据（快读拿 0）
        }
        long allow = Math.min(requested, backlog);
        served += allow;
        return allow;
    }
}
