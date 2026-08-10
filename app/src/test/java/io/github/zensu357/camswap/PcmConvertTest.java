package io.github.zensu357.camswap;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link PcmConvert} 采样格式转换测试（纯 JVM）。
 * 验证 float / 8-bit PCM 正确归一化为 16-bit 小端——否则注入的推流音频会变"滋啦"噪声。
 */
public class PcmConvertTest {

    private static byte[] floatLe(float... fs) {
        byte[] out = new byte[fs.length * 4];
        for (int i = 0; i < fs.length; i++) {
            int bits = Float.floatToIntBits(fs[i]);
            out[i * 4] = (byte) (bits & 0xFF);
            out[i * 4 + 1] = (byte) ((bits >> 8) & 0xFF);
            out[i * 4 + 2] = (byte) ((bits >> 16) & 0xFF);
            out[i * 4 + 3] = (byte) ((bits >> 24) & 0xFF);
        }
        return out;
    }

    private static short sampleAt(byte[] pcm16, int i) {
        int lo = pcm16[i * 2] & 0xFF;
        int hi = pcm16[i * 2 + 1] << 8;
        return (short) (hi | lo);
    }

    @Test
    public void floatLeToPcm16_mapsRangeAndClamps() {
        byte[] in = floatLe(0f, 1f, -1f, 0.5f, 2f, -2f);
        byte[] out = PcmConvert.floatLeToPcm16(in, 0, in.length);
        assertEquals(6 * 2, out.length);
        assertEquals(0, sampleAt(out, 0));
        assertEquals(32767, sampleAt(out, 1));
        assertEquals(-32767, sampleAt(out, 2));
        assertEquals(16384, sampleAt(out, 3)); // round(0.5*32767)=16384
        assertEquals(32767, sampleAt(out, 4)); // 2.0 clamp
        assertEquals(-32768, sampleAt(out, 5)); // -2.0 clamp
    }

    @Test
    public void floatLeToPcm16_alignsToFourBytes() {
        byte[] in = floatLe(1f, -1f);
        // 多出 3 个字节（非 4 对齐）应被忽略，不越界
        byte[] padded = new byte[in.length + 3];
        System.arraycopy(in, 0, padded, 0, in.length);
        byte[] out = PcmConvert.floatLeToPcm16(padded, 0, padded.length);
        assertEquals(2 * 2, out.length);
        assertEquals(32767, sampleAt(out, 0));
        assertEquals(-32767, sampleAt(out, 1));
    }

    @Test
    public void floatArrayToPcm16_mapsSamples() {
        byte[] out = PcmConvert.floatArrayToPcm16(new float[] {0f, 1f, -1f, 0.25f}, 0, 4);
        assertEquals(4 * 2, out.length);
        assertEquals(0, sampleAt(out, 0));
        assertEquals(32767, sampleAt(out, 1));
        assertEquals(-32767, sampleAt(out, 2));
        assertEquals(8192, sampleAt(out, 3)); // round(0.25*32767)=8192
    }

    @Test
    public void pcm8ToPcm16_centersAndScales() {
        byte[] out = PcmConvert.pcm8ToPcm16(new byte[] {(byte) 128, (byte) 255, (byte) 0}, 0, 3);
        assertEquals(3 * 2, out.length);
        assertEquals(0, sampleAt(out, 0)); // 128 → 中点 0
        assertEquals((127) << 8, sampleAt(out, 1)); // 255 → +127<<8
        assertEquals((-128) << 8, sampleAt(out, 2)); // 0 → -128<<8
    }

    @Test
    public void guards_emptyAndNull() {
        assertEquals(0, PcmConvert.floatLeToPcm16(null, 0, 4).length);
        assertEquals(0, PcmConvert.floatLeToPcm16(new byte[2], 0, 2).length); // <4 字节
        assertEquals(0, PcmConvert.floatArrayToPcm16(null, 0, 4).length);
        assertEquals(0, PcmConvert.pcm8ToPcm16(null, 0, 4).length);
    }
}
