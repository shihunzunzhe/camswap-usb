package io.github.zensu357.camswap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * {@link StreamPcmBuffer} 环形缓冲的 JVM 单元测试。
 *
 * <p>重点验证「仅推流音频」功能的安全不变量：缓冲无数据 / 未激活时，
 * 读出的是<b>静音</b>而非未初始化/真实麦克风数据——目标 App 绝不会听到真实麦克风。
 */
public class StreamPcmBufferTest {

    private MockedStatic<Log> logMock;
    private MockedStatic<SystemClock> clockMock;

    @Before
    public void setUp() {
        logMock = Mockito.mockStatic(Log.class);
        logMock.when(() -> Log.i(Mockito.anyString(), Mockito.anyString())).thenReturn(0);
        clockMock = Mockito.mockStatic(SystemClock.class);
        clockMock.when(SystemClock::elapsedRealtime).thenReturn(0L);
    }

    @After
    public void tearDown() {
        StreamPcmBuffer.stop();
        clockMock.close();
        logMock.close();
    }

    @Test
    public void writeThenRead_roundTripsBytes() {
        StreamPcmBuffer.start(48000, 1);
        assertTrue(StreamPcmBuffer.isActive());

        byte[] in = {1, 2, 3, 4, 5, 6, 7, 8};
        StreamPcmBuffer.write(in, 0, in.length);

        byte[] out = new byte[8];
        int n = StreamPcmBuffer.read(out, 0, out.length, 48000, 1);
        assertEquals(8, n);
        assertArrayEquals(in, out);
    }

    @Test
    public void read_whenInactive_fillsSilenceNotRealData() {
        // 未 start（inactive）：即便调用方传入非零缓冲，也必须被清成静音
        StreamPcmBuffer.stop();
        assertFalse(StreamPcmBuffer.isActive());

        byte[] out = {9, 9, 9, 9, 9, 9, 9, 9};
        int n = StreamPcmBuffer.read(out, 0, out.length, 48000, 1);
        assertEquals(8, n);
        assertArrayEquals(new byte[8], out); // 全 0
    }

    @Test
    public void read_whenActiveButEmpty_fillsSilence() {
        // 已激活但推流还没产生音频（available=0）→ 静音，绝不泄露真实麦克风
        StreamPcmBuffer.start(48000, 1);
        byte[] out = {7, 7, 7, 7};
        int n = StreamPcmBuffer.read(out, 0, out.length, 48000, 1);
        assertEquals(4, n);
        assertArrayEquals(new byte[4], out);
    }

    @Test
    public void read_withPartialData_fillsRemainderWithSilence() {
        StreamPcmBuffer.start(48000, 1);
        byte[] in = {10, 20, 30, 40};
        StreamPcmBuffer.write(in, 0, in.length);

        byte[] out = new byte[8];
        int n = StreamPcmBuffer.read(out, 0, out.length, 48000, 1);
        assertEquals(8, n);
        // 前 4 字节为写入数据，后 4 字节静音
        assertArrayEquals(new byte[] {10, 20, 30, 40, 0, 0, 0, 0}, out);
    }

    @Test
    public void write_oddLengthTruncatedToEven() {
        StreamPcmBuffer.start(48000, 1);
        // 5 字节 → 只接受偶数长度（16-bit），实际写入 4 字节
        StreamPcmBuffer.write(new byte[] {1, 2, 3, 4, 5}, 0, 5);
        byte[] out = new byte[4];
        StreamPcmBuffer.read(out, 0, out.length, 48000, 1);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, out);
    }

    @Test
    public void writeShorts_readShorts_roundTrip() {
        StreamPcmBuffer.start(48000, 1);
        short[] in = {100, -200, 300, -400};
        StreamPcmBuffer.writeShorts(in, 0, in.length);

        short[] out = new short[4];
        int samples = StreamPcmBuffer.readShorts(out, 0, out.length, 48000, 1);
        assertEquals(4, samples);
        assertArrayEquals(in, out);
    }

    @Test
    public void ringBuffer_wrapsAroundKeepingLatest() {
        StreamPcmBuffer.start(48000, 1);
        // 写满并绕回：容量约 288000 字节；连续写 3 段，验证读出的是最新写入
        int cap = 48000 * 2 * 3;
        byte[] filler = new byte[cap - 4];
        StreamPcmBuffer.write(filler, 0, filler.length); // available = cap-4
        // 再写 6 字节（含最后 2 字节触发绕回覆盖最旧数据）
        StreamPcmBuffer.write(new byte[] {1, 2, 3, 4, 5, 6}, 0, 6);

        // 一次性读走全部 available（应为 cap，绕回后旧数据被覆盖），末尾 6 字节为最新
        byte[] out = new byte[cap];
        int n = StreamPcmBuffer.read(out, 0, cap, 48000, 1);
        assertEquals(cap, n);
        byte[] tail = new byte[6];
        System.arraycopy(out, cap - 6, tail, 0, 6);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, tail);
    }

    @Test
    public void inactiveWrite_isIgnored() {
        StreamPcmBuffer.stop();
        StreamPcmBuffer.write(new byte[] {1, 2, 3, 4}, 0, 4); // 应被忽略
        StreamPcmBuffer.start(48000, 1);
        byte[] out = new byte[4];
        StreamPcmBuffer.read(out, 0, 4, 48000, 1);
        assertArrayEquals(new byte[4], out); // 静音，之前的写入未生效
    }
}
