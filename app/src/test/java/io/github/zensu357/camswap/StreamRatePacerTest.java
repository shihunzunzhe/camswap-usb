package io.github.zensu357.camswap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link StreamRatePacer} 测试（纯 JVM）。验证：无论目标 App 读多快，真实音频交付量恒为 1 倍实时；
 * 多余快读拿 0；停顿后恢复只补一桶（~50ms），绝不追赶式快放。
 */
public class StreamRatePacerTest {

    private static final int BPS = 48000 * 2 * 2; // 192000
    private static final int REQ = 3840; // 20ms
    private static final int BUCKET = BPS * 50 / 1000; // 9600 = 50ms

    @Test
    public void startupBucket_thenZeroUntilWallclock() {
        StreamRatePacer p = new StreamRatePacer();
        // 启动预给一桶（~50ms=9600 字节），分几次读完
        long s = 0;
        s += p.allowed(REQ, BPS, 0);
        s += p.allowed(REQ, BPS, 0);
        s += p.allowed(REQ, BPS, 0);
        assertEquals("启动桶应为 50ms", BUCKET, s);
        assertEquals("桶空后快读拿 0", 0, p.allowed(REQ, BPS, 0));
    }

    @Test
    public void afterWallclockAdvances_deliversAtOneX() {
        StreamRatePacer p = new StreamRatePacer();
        // 先把启动桶读空
        p.allowed(REQ, BPS, 0);
        p.allowed(REQ, BPS, 0);
        p.allowed(REQ, BPS, 0);
        assertEquals(0, p.allowed(REQ, BPS, 0));
        // 墙钟走了 20ms → 放行 20ms（3840 字节）
        assertEquals(REQ, p.allowed(REQ, BPS, 20));
        assertEquals(0, p.allowed(REQ, BPS, 20));
    }

    @Test
    public void averageDeliveryIsOneX_underFastReader() {
        StreamRatePacer p = new StreamRatePacer();
        long total = 0;
        for (long t = 0; t <= 2000; t++) { // 快读者，每 1ms 一次，共 2 秒
            total += p.allowed(REQ, BPS, t);
        }
        long oneX = 2000L * BPS / 1000; // 384000
        // 1x + 一桶启动量（一次性），误差很小
        assertTrue("交付应≈1x, total=" + total,
                total >= oneX && total <= oneX + 2 * BUCKET);
    }

    @Test
    public void pauseThenResume_catchUpBoundedToOneBucket() {
        StreamRatePacer p = new StreamRatePacer();
        p.allowed(REQ, BPS, 0); // 启动
        // 停顿 1 秒后恢复：追补被限制在一桶内
        long catchUp = 0;
        catchUp += p.allowed(REQ, BPS, 1000);
        catchUp += p.allowed(REQ, BPS, 1000);
        catchUp += p.allowed(REQ, BPS, 1000);
        catchUp += p.allowed(REQ, BPS, 1000);
        assertEquals("追补上限为一桶(50ms)", BUCKET, catchUp);
        assertEquals(0, p.allowed(REQ, BPS, 1000));
    }

    @Test
    public void neverExceedsRequestedPerCall() {
        StreamRatePacer p = new StreamRatePacer();
        p.allowed(REQ, BPS, 0);
        assertTrue(p.allowed(REQ, BPS, 100000) <= REQ);
    }

    @Test
    public void formatChange_reanchors() {
        StreamRatePacer p = new StreamRatePacer();
        p.allowed(REQ, BPS, 0);
        p.allowed(REQ, BPS, 0);
        p.allowed(REQ, BPS, 0);
        assertEquals(0, p.allowed(REQ, BPS, 0));
        // 字节率变化 → 重锚，重新给启动桶
        assertTrue(p.allowed(REQ, 96000, 0) > 0);
    }

    @Test
    public void reset_reanchors() {
        StreamRatePacer p = new StreamRatePacer();
        p.allowed(REQ, BPS, 0);
        p.reset();
        assertTrue(p.allowed(REQ, BPS, 500) > 0);
    }

    @Test
    public void guards_invalidInputs() {
        StreamRatePacer p = new StreamRatePacer();
        assertEquals(0, p.allowed(REQ, 0, 0));
        assertEquals(0, p.allowed(0, BPS, 0));
    }
}
