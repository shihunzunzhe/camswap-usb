package io.github.zensu357.camswap;

import java.nio.ByteBuffer;

/**
 * 把替换用 PCM 覆盖进 {@code AudioRecord.read(ByteBuffer, ...)} 的目标缓冲（纯逻辑，无 Android 依赖）。
 *
 * <p>关键事实：framework 的 {@code readInDirectBuffer} 通过
 * {@code GetDirectBufferAddress} 拿到 direct ByteBuffer 的<b>基址</b>，把录音数据写在
 * backing memory 的 <b>[0, result)</b>，<b>完全忽略 position、也不改动 position</b>
 * （腾讯 LiteAV 走此路径，实测读前后 position 恒为 0）。
 *
 * <p>因此覆盖时：
 * <ul>
 *   <li>从<b>绝对索引 0</b> 开始写，正对齐录音数据真实落点；</li>
 *   <li>用<b>绝对 {@code put(index, byte)}</b>，全程不碰 position/limit，
 *       杜绝旧代码 {@code position(pos - result)} 的 {@code Bad position} 崩溃；</li>
 *   <li>越界一律夹取，绝不抛异常。</li>
 * </ul>
 */
final class MicByteBufferWriter {

    private MicByteBufferWriter() {
    }

    /**
     * 用 {@code data[0..n)} 覆盖 {@code buffer} 的绝对区间 {@code [0, n)}。
     * 不改变 buffer 的 position / limit。越界自动夹取。
     *
     * @return 实际覆盖的字节数
     */
    static int overwriteFromStart(ByteBuffer buffer, byte[] data, int n) {
        if (buffer == null || data == null || n <= 0) {
            return 0;
        }
        int count = Math.min(n, Math.min(buffer.capacity(), data.length));
        for (int i = 0; i < count; i++) {
            buffer.put(i, data[i]); // 绝对 put：不依赖也不修改 position，永不越界抛 Bad position
        }
        return count;
    }
}
