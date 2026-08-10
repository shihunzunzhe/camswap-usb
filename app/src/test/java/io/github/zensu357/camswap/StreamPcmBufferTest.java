package io.github.zensu357.camswap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;

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
    public void trim_dropsBacklog_keepsOnlyLatest() {
        // 48000/1 → 96000 B/s。写入远超硬上限，验证只保留最新一小段
        StreamPcmBuffer.start(48000, 1);
        StreamPcmBuffer.write(new byte[60000], 0, 60000); // 历史 0x00
        byte[] latest = new byte[60000];
        Arrays.fill(latest, (byte) 0x22);
        StreamPcmBuffer.write(latest, 0, latest.length); // 累计 120000 → 远超硬上限 → 丢弃重同步

        int avail = StreamPcmBuffer.availableBytes();
        // 积压被夹到低水位，远小于写入总量 120000
        assertTrue("avail 应被裁到低水位, 实际=" + avail, avail <= 60000);
        assertTrue("应至少保留最新一段", avail >= 8000);

        // 读出全部积压，全是最新写入的 0x22（历史 0x00 已被丢弃）
        byte[] out = new byte[avail];
        StreamPcmBuffer.read(out, 0, avail, 48000, 1);
        assertEquals((byte) 0x22, out[avail - 1]);
        assertEquals((byte) 0x22, out[0]);
    }

    @Test
    public void trim_notTriggeredForSmallBacklog() {
        StreamPcmBuffer.start(48000, 1);
        // 小于目标延迟的写入不应触发丢弃，数据完整可读
        byte[] in = new byte[4000];
        Arrays.fill(in, (byte) 0x5A);
        StreamPcmBuffer.write(in, 0, in.length);
        assertEquals(4000, StreamPcmBuffer.availableBytes());

        byte[] out = new byte[4000];
        StreamPcmBuffer.read(out, 0, 4000, 48000, 1);
        assertArrayEquals(in, out);
    }

    @Test
    public void writePosWrapsAround_withTrim_boundedAndLatestReadable() {
        StreamPcmBuffer.start(48000, 1);
        int cap = 48000 * 2 * 3; // 288000
        // 写入远超物理容量，触发 writePos 绕回；trim 保证积压始终有界
        byte[] block = new byte[20000];
        for (int i = 0; i < 20; i++) { // 400000 > cap
            StreamPcmBuffer.write(block, 0, block.length);
        }
        assertTrue("积压应有界，不随写入无限增长", StreamPcmBuffer.availableBytes() <= 120000);

        byte[] latest = new byte[8000];
        Arrays.fill(latest, (byte) 0x33);
        StreamPcmBuffer.write(latest, 0, latest.length);

        int avail = StreamPcmBuffer.availableBytes();
        byte[] out = new byte[avail];
        StreamPcmBuffer.read(out, 0, avail, 48000, 1);
        assertEquals((byte) 0x33, out[avail - 1]); // 最新可读
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
