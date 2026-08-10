package io.github.zensu357.camswap;

/**
 * PCM 采样格式转换（纯逻辑，无 Android 依赖）。
 *
 * <p>{@link StreamPcmBuffer} 内部统一以 <b>16-bit 小端</b> 存取；而播放器写给
 * {@code AudioTrack} 的 PCM 可能是 float（{@code ENCODING_PCM_FLOAT}）或 8-bit。
 * 若把 float 字节直接当 16-bit 存，读出来就是纯静电噪声（"滋啦"）。本类把这些格式
 * 归一化成 16-bit 小端，供采集侧在写入缓冲前转换。
 */
final class PcmConvert {

    private PcmConvert() {
    }

    /** 32-bit float 小端字节（范围 [-1,1]）→ 16-bit 小端 PCM 字节。{@code len} 按 4 字节对齐处理。 */
    static byte[] floatLeToPcm16(byte[] src, int off, int len) {
        if (src == null || len < 4) {
            return new byte[0];
        }
        int usable = Math.min(len, src.length - off) & ~3; // 4 字节对齐
        int samples = usable / 4;
        byte[] out = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            int b = off + i * 4;
            int bits = (src[b] & 0xFF) | ((src[b + 1] & 0xFF) << 8)
                    | ((src[b + 2] & 0xFF) << 16) | ((src[b + 3] & 0xFF) << 24);
            writeSample(out, i * 2, Float.intBitsToFloat(bits));
        }
        return out;
    }

    /** float[] 样本（范围 [-1,1]）→ 16-bit 小端 PCM 字节。 */
    static byte[] floatArrayToPcm16(float[] src, int off, int count) {
        if (src == null || count <= 0) {
            return new byte[0];
        }
        int n = Math.min(count, src.length - off);
        if (n <= 0) {
            return new byte[0];
        }
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            writeSample(out, i * 2, src[off + i]);
        }
        return out;
    }

    /** 8-bit 无符号 PCM（0..255，中点 128）→ 16-bit 小端 PCM 字节。 */
    static byte[] pcm8ToPcm16(byte[] src, int off, int len) {
        if (src == null || len <= 0) {
            return new byte[0];
        }
        int n = Math.min(len, src.length - off);
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int s = ((src[off + i] & 0xFF) - 128) << 8; // 居中并放大到 16-bit
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }

    private static void writeSample(byte[] out, int idx, float f) {
        int s = Math.round(f * 32767f);
        if (s > 32767) {
            s = 32767;
        } else if (s < -32768) {
            s = -32768;
        }
        out[idx] = (byte) (s & 0xFF);
        out[idx + 1] = (byte) ((s >> 8) & 0xFF);
    }
}
