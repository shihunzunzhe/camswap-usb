package io.github.zensu357.camswap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.nio.ByteBuffer;

import org.junit.Test;

/**
 * {@link MicByteBufferWriter} 测试：验证覆盖 {@code AudioRecord.read(ByteBuffer)} 录音数据时
 * <b>真麦克风字节被抹掉</b>、<b>position/limit 不变</b>、<b>任何情况都不抛 Bad position 崩溃</b>。
 *
 * <p>{@code java.nio.ByteBuffer} 是纯 JVM 类，可直接在单元测试里复现 LiteAV 的 direct buffer 场景。
 */
public class MicByteBufferWriterTest {

    private static ByteBuffer filledWithMic(int cap, boolean direct) {
        ByteBuffer b = direct ? ByteBuffer.allocateDirect(cap) : ByteBuffer.allocate(cap);
        for (int i = 0; i < cap; i++) {
            b.put(i, (byte) 0x55); // 模拟真实麦克风数据
        }
        b.position(0);
        return b;
    }

    /** direct buffer（LiteAV 用）：从 0 覆盖为静音，真麦克风字节全部清零，position 不变。 */
    @Test
    public void overwritesMicBytes_directBuffer_positionUntouched() {
        ByteBuffer buf = filledWithMic(3840, true);
        int savedPos = buf.position();
        int savedLimit = buf.limit();

        int written = MicByteBufferWriter.overwriteFromStart(buf, new byte[3840], 3840);

        assertEquals(3840, written);
        assertEquals(savedPos, buf.position()); // position 未被改动
        assertEquals(savedLimit, buf.limit());
        for (int i = 0; i < 3840; i++) {
            assertEquals("index " + i + " 应被清零（真麦克风字节已抹掉）", 0, buf.get(i));
        }
    }

    /** 复现旧崩溃场景：position 处于末尾时，绝对 put 依然安全、不抛 Bad position。 */
    @Test
    public void neverThrows_evenWhenPositionAtLimit() {
        ByteBuffer buf = filledWithMic(3840, true);
        buf.position(buf.limit()); // 旧代码在此场景 position(pos-result) 会抛 Bad position

        int written = MicByteBufferWriter.overwriteFromStart(buf, new byte[3840], 3840);

        assertEquals(3840, written);
        assertEquals(3840, buf.position()); // 仍在末尾，未被改动
        for (int i = 0; i < 3840; i++) {
            assertEquals(0, buf.get(i));
        }
    }

    /** 注入非静音数据（模拟 RTMP 音频）：字节被替换成注入内容，而非原麦克风。 */
    @Test
    public void injectsStreamData_notRealMic() {
        ByteBuffer buf = filledWithMic(16, false);
        byte[] stream = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

        MicByteBufferWriter.overwriteFromStart(buf, stream, 16);

        for (int i = 0; i < 16; i++) {
            assertEquals(stream[i], buf.get(i));
            assertNotEquals(0x55, buf.get(i));
        }
    }

    /** 越界一律夹取：n / data / capacity 取最小，绝不 BufferOverflow。 */
    @Test
    public void clampsToSmallestBound() {
        ByteBuffer buf = filledWithMic(8, false);
        // n=100 超过 capacity(8) 和 data.length(4) → 只写 4
        int written = MicByteBufferWriter.overwriteFromStart(buf, new byte[] {1, 2, 3, 4}, 100);
        assertEquals(4, written);
        assertEquals(1, buf.get(0));
        assertEquals(4, buf.get(3));
        assertEquals(0x55, buf.get(4)); // 未越界写
    }

    @Test
    public void nullOrEmpty_noop_noThrow() {
        assertEquals(0, MicByteBufferWriter.overwriteFromStart(null, new byte[4], 4));
        ByteBuffer buf = filledWithMic(8, false);
        assertEquals(0, MicByteBufferWriter.overwriteFromStart(buf, null, 4));
        assertEquals(0, MicByteBufferWriter.overwriteFromStart(buf, new byte[4], 0));
        assertEquals(0, MicByteBufferWriter.overwriteFromStart(buf, new byte[4], -1));
    }
}
