package io.github.zensu357.camswap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link StreamAudioPacer} 测试（纯 JVM）。验证把注入输出限速到 1 倍实时：
 * 拉取过快 → 需要 sleep；本身就是 1 倍 → 不 sleep；停顿后重锚不追赶。
 */
public class StreamAudioPacerTest {

    private static final int BPS = 48000 * 2 * 2; // 192000：48k 立体声 16-bit
    private static final int CHUNK = 19200; // 0.1 秒

    @Test
    public void firstChunk_anchorsWithoutSleep() {
        StreamAudioPacer p = new StreamAudioPacer();
        assertEquals(0, p.onServed(CHUNK, BPS, 1000));
    }

    @Test
    public void fastConsumer_getsSleptToRealtime() {
        StreamAudioPacer p = new StreamAudioPacer();
        assertEquals(0, p.onServed(CHUNK, BPS, 0)); // 锚点
        // 消费者瞬间连续拉取（墙钟不前进）→ 每次都需 sleep ~100ms 补齐到 1 倍
        assertEquals(100, p.onServed(CHUNK, BPS, 0));
        assertEquals(100, p.onServed(CHUNK, BPS, 100)); // 墙钟走了 100ms，又拉一块 → 仍需 100
    }

    @Test
    public void realtimeConsumer_noSleep() {
        StreamAudioPacer p = new StreamAudioPacer();
        assertEquals(0, p.onServed(CHUNK, BPS, 100)); // 锚点 @100
        // 每块耗时正好 100ms（真实麦克风节奏）→ 无需额外 sleep
        assertEquals(0, p.onServed(CHUNK, BPS, 200));
        assertEquals(0, p.onServed(CHUNK, BPS, 300));
        assertEquals(0, p.onServed(CHUNK, BPS, 400));
    }

    @Test
    public void slightlyFast_accumulatesSmallSleep() {
        StreamAudioPacer p = new StreamAudioPacer();
        assertEquals(0, p.onServed(CHUNK, BPS, 0)); // 锚点
        // 每块只过了 80ms（比 1 倍快）→ 需补 20ms、40ms...
        assertEquals(20, p.onServed(CHUNK, BPS, 80));
        assertEquals(40, p.onServed(CHUNK, BPS, 160));
    }

    @Test
    public void largeLag_resyncsNoCatchUp() {
        StreamAudioPacer p = new StreamAudioPacer();
        assertEquals(0, p.onServed(CHUNK, BPS, 0)); // 锚点
        // 中途长时间停顿（墙钟跳到 5000）→ 落后 4900ms，应重锚返回 0（不追赶式快放）
        assertEquals(0, p.onServed(CHUNK, BPS, 5000));
        // 重锚后恢复正常限速
        assertEquals(100, p.onServed(CHUNK, BPS, 5000));
    }

    @Test
    public void formatChange_reanchors() {
        StreamAudioPacer p = new StreamAudioPacer();
        assertEquals(0, p.onServed(CHUNK, BPS, 0)); // 锚点 @BPS
        assertEquals(100, p.onServed(CHUNK, BPS, 0)); // 快 → sleep
        // 字节率变化（如格式变更）→ 重锚，返回 0
        assertEquals(0, p.onServed(CHUNK, 96000, 0));
    }

    @Test
    public void reset_reanchorsOnNextCall() {
        StreamAudioPacer p = new StreamAudioPacer();
        assertEquals(0, p.onServed(CHUNK, BPS, 0));
        assertTrue(p.onServed(CHUNK, BPS, 0) > 0);
        p.reset();
        assertEquals("reset 后下次调用应重新锚定", 0, p.onServed(CHUNK, BPS, 999));
    }

    @Test
    public void guards_invalidInputs() {
        StreamAudioPacer p = new StreamAudioPacer();
        assertEquals(0, p.onServed(CHUNK, 0, 100)); // bps<=0
        assertEquals(0, p.onServed(-1, BPS, 100)); // 负字节
    }
}
