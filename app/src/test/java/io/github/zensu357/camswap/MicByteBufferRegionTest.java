package io.github.zensu357.camswap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;

import org.junit.Test;

/**
 * {@link MicByteBufferRegion} 测试 + 复现并验证 {@code AudioRecord.read(ByteBuffer)}
 * 的 "Bad position -3840/3840" 崩溃（腾讯 LiteAV 录音路径）已被修复。
 *
 * <p>{@code java.nio.ByteBuffer} 是纯 JVM 类，可直接在单元测试里复现该崩溃场景。
 */
public class MicByteBufferRegionTest {

    /** 复现历史 bug：读完 position 未推进（仍为 0），旧代码 position(0-3840) 抛 IllegalArgumentException。 */
    @Test
    public void oldApproach_reproducesBadPositionCrash() {
        ByteBuffer buf = ByteBuffer.allocate(3840);
        buf.position(0); // LiteAV：read 后 position 仍为 0
        int result = 3840;
        try {
            buf.position(buf.position() - result); // 旧代码：position(-3840)
            fail("应当抛出 Bad position 异常，证明这是真实存在的崩溃");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("-3840"));
        }
    }

    /** 新逻辑：同一场景下计算出的区间合法，覆盖写入不抛异常。 */
    @Test
    public void newRegion_handlesNonAdvancingBuffer_noCrash() {
        int bound = 3840, result = 3840, posBefore = 0;
        MicByteBufferRegion.Region r = MicByteBufferRegion.compute(posBefore, bound, result);
        assertEquals(0, r.start);
        assertEquals(3840, r.len);

        ByteBuffer buf = ByteBuffer.allocate(bound);
        buf.position(0);
        int posAfter = buf.position();
        // 模拟修复后的写入路径
        buf.position(r.start);
        buf.put(new byte[r.len], 0, r.len);
        buf.position(posAfter); // 不抛异常即通过
        assertEquals(posAfter, buf.position());
    }

    /** position 已推进的实现（posAfter = posBefore + result）也应正确覆盖且不抛。 */
    @Test
    public void newRegion_handlesAdvancingBuffer() {
        int bound = 3840, result = 3840, posBefore = 0;
        MicByteBufferRegion.Region r = MicByteBufferRegion.compute(posBefore, bound, result);

        ByteBuffer buf = ByteBuffer.allocate(bound);
        buf.position(result); // 已推进到末尾
        int posAfter = buf.position();
        buf.position(r.start);
        buf.put(new byte[r.len], 0, r.len);
        buf.position(posAfter);
        assertEquals(3840, buf.position());
    }

    /** 非零起点：区间长度被夹到 limit 内，put 不越界。 */
    @Test
    public void compute_clampsLengthToBound() {
        MicByteBufferRegion.Region r = MicByteBufferRegion.compute(1000, 3840, 3840);
        assertEquals(1000, r.start);
        assertEquals(2840, r.len); // 3840 - 1000

        ByteBuffer buf = ByteBuffer.allocate(3840);
        buf.position(r.start);
        buf.put(new byte[r.len], 0, r.len); // 恰好写到末尾，不抛
        assertEquals(3840, buf.position());
    }

    /** 边界防御：负 position / 负 result / 超界一律夹回合法范围。 */
    @Test
    public void compute_guardsIllegalInputs() {
        MicByteBufferRegion.Region neg = MicByteBufferRegion.compute(-5, 3840, 100);
        assertEquals(0, neg.start);
        assertEquals(100, neg.len);

        MicByteBufferRegion.Region negLen = MicByteBufferRegion.compute(0, 3840, -1);
        assertEquals(0, negLen.len);

        MicByteBufferRegion.Region startBeyond = MicByteBufferRegion.compute(9999, 3840, 100);
        assertEquals(3840, startBeyond.start);
        assertEquals(0, startBeyond.len); // 无可写空间

        MicByteBufferRegion.Region negBound = MicByteBufferRegion.compute(0, -10, 100);
        assertEquals(0, negBound.start);
        assertEquals(0, negBound.len);
    }
}
