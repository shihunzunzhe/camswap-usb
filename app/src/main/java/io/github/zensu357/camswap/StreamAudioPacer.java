package io.github.zensu357.camswap;

/**
 * 输出节拍器：把「注入到目标 App 麦克风的推流音频」限速到严格 <b>1 倍实时</b>（纯逻辑，无 Android 依赖）。
 *
 * <p>问题背景：目标 App（微信/LiteAV）的 {@code AudioRecord.read} 拉取速度可能<b>快于实时</b>
 * （不像真实麦克风那样按采样率阻塞）。若我们每次读都 1:1 供给缓冲数据，它就会把预攒的音频
 * 瞬间抽干 → 听感「每 2 秒快放一次」。真实麦克风的本质是：数据按采样率<b>匀速</b>产出，读快了要等。
 *
 * <p>本类据此按墙钟给出应 sleep 的时长：累计已供给字节对应的「应流逝时间」若超前于「实际流逝时间」，
 * 就 sleep 补齐，使输出严格 1 倍、连续不快放。停顿/断流导致大幅落后时自动重锚，避免追赶式快放。
 *
 * <p>调用方负责真正的 {@code Thread.sleep} 与提供墙钟（{@code SystemClock.elapsedRealtime}）。
 */
final class StreamAudioPacer {

    /** 落后超过此毫秒（如中途停顿/断流）则重锚时钟，避免恢复时追赶式快放。 */
    private static final long RESYNC_MS = 500;

    private long startMs = -1;
    private long servedBytes;
    private int bytesPerSec;

    /** 新会话开始时重置（下次 onServed 会重新锚定墙钟）。 */
    synchronized void reset() {
        startMs = -1;
        servedBytes = 0;
        bytesPerSec = 0;
    }

    /**
     * 记录本次供给的字节数，返回为维持 1 倍实时应 sleep 的毫秒数（0 表示无需 sleep）。
     *
     * @param bytes       本次注入目标缓冲的字节数（目标 App 的音频格式）
     * @param curBytesPerSec 目标格式的字节率（sampleRate * channels * bytesPerSample）
     * @param nowMs       当前墙钟（单调递增，如 SystemClock.elapsedRealtime）
     */
    synchronized long onServed(long bytes, int curBytesPerSec, long nowMs) {
        if (curBytesPerSec <= 0 || bytes < 0) {
            return 0;
        }
        // 首块 / 字节率变化（格式变更）：作为墙钟锚点，不 sleep、不计债，
        // 避免把「本身已是 1 倍实时」的消费者也拖慢。
        if (startMs < 0 || bytesPerSec != curBytesPerSec) {
            bytesPerSec = curBytesPerSec;
            startMs = nowMs;
            servedBytes = 0;
            return 0;
        }
        servedBytes += bytes;
        long expectedMs = servedBytes * 1000L / bytesPerSec; // 已供给字节应对应的实时时长
        long actualMs = nowMs - startMs;
        long ahead = expectedMs - actualMs; // >0：供给超前于实时 → 应 sleep 补齐
        if (ahead < -RESYNC_MS) {
            // 落后过多（停顿/断流）→ 重锚，防止恢复时把积压快速抽干
            startMs = nowMs;
            servedBytes = 0;
            return 0;
        }
        return ahead > 0 ? ahead : 0;
    }
}
