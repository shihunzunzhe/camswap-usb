package io.github.zensu357.camswap;

/**
 * 计算 {@code AudioRecord.read(ByteBuffer, ...)} 后应覆盖的字节区间（纯逻辑，无 Android 依赖）。
 *
 * <p>背景：不同 Android 实现下，{@code read(ByteBuffer)} 读完后 buffer 的 position
 * <b>可能推进、也可能不推进</b>（腾讯 LiteAV 用的 direct ByteBuffer 读完 position 仍为 0）。
 * 旧代码假设一定推进、用 {@code position(pos - result)} 回退，在不推进时会算出负 position，
 * 抛 {@code IllegalArgumentException: Bad position -N/N}，导致 hooker 崩溃、真实麦克风数据
 * 原样交给目标 App。
 *
 * <p>本类以「读前 position」为数据起点，并把区间夹在 {@code [0, bound]} 内，
 * 保证得到的 start/len 恒合法，绝不产生非法 position。
 */
final class MicByteBufferRegion {

    /** 覆盖区间：从 {@link #start} 起 {@link #len} 个字节。 */
    static final class Region {
        final int start;
        final int len;

        Region(int start, int len) {
            this.start = start;
            this.len = len;
        }
    }

    private MicByteBufferRegion() {
    }

    /**
     * @param posBefore 调用 {@code read} 之前的 buffer position（数据写入起点）
     * @param bound     可写上界（一般取 {@code buffer.limit()}）
     * @param result    {@code read} 返回的实际字节数
     */
    static Region compute(int posBefore, int bound, int result) {
        if (bound < 0) {
            bound = 0;
        }
        int start = posBefore;
        if (start < 0) {
            start = 0;
        }
        if (start > bound) {
            start = bound;
        }
        int len = result;
        if (len < 0) {
            len = 0;
        }
        int maxLen = bound - start;
        if (len > maxLen) {
            len = maxLen;
        }
        return new Region(start, len);
    }
}
