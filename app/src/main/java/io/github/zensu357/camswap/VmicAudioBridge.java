package io.github.zensu357.camswap;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.vmic.VirtualMicSender;

/**
 * Magisk HAL 桥:把 {@link StreamPcmBuffer} 收到的解码 PCM(16-bit LE,任意采样率/声道)
 * 重采样为 HAL 约定的 <b>48000Hz / 2ch / S16LE</b>,经 {@link VirtualMicSender} 推到
 * {@code @virtual_mic_socket}。
 *
 * <p>作为 {@link StreamPcmBuffer.PcmTap} 挂在唯一的 PCM 汇聚点上,自动覆盖 Ijk/Exo
 * 等所有解码后端(它们都经 {@code AudioTrack.write} → StreamPcmBuffer)。
 *
 * <p>重采样:线性插值 + 单声道→立体声上混,相位跨回调连续(避免拼接爆音)。
 * HAL 侧还会再从 48000/2ch 高质量重采样到目标 App 实际请求的格式。
 */
public final class VmicAudioBridge {

    private static final String TAG = "【CS】【vmic-bridge】";
    private static final int DST_RATE = 48000;
    private static final int DST_CH = 2;

    private static final Object LK = new Object();
    private static VirtualMicSender sender;
    private static boolean running;

    // 线性重采样状态(跨回调保持连续)。
    private static boolean primed;
    private static double phase;          // 当前源区间 [prev,cur) 内的相位 [0,1)
    private static int prevL, prevR, curL, curR;
    private static int lastSrcRate = -1, lastSrcCh = -1;

    private VmicAudioBridge() {
    }

    /** 启动:创建并连接 sender,并把自己挂为 StreamPcmBuffer 的 PCM tap。 */
    public static void start() {
        synchronized (LK) {
            if (running) return;
            sender = new VirtualMicSender();   // 默认 @virtual_mic_socket
            sender.start();
            resetResampler();
            StreamPcmBuffer.setTap(VmicAudioBridge::onPcm);
            running = true;
            LogUtil.log(TAG + "started (→ @virtual_mic_socket, dst=48000/2ch/S16)");
        }
    }

    /** 停止:摘除 tap 并关闭 sender。 */
    public static void stop() {
        synchronized (LK) {
            if (!running) return;
            running = false;
            StreamPcmBuffer.setTap(null);
            if (sender != null) {
                sender.stop();
                sender = null;
            }
            LogUtil.log(TAG + "stopped");
        }
    }

    public static boolean isRunning() {
        return running;
    }

    private static void resetResampler() {
        primed = false;
        phase = 0.0;
        prevL = prevR = curL = curR = 0;
    }

    /** StreamPcmBuffer.PcmTap 回调:16-bit LE 源 PCM → 48000/2ch → 推流。 */
    private static void onPcm(byte[] data, int off, int len, int srcRate, int srcCh) {
        synchronized (LK) {
            if (!running || sender == null || data == null || len < 2 || srcRate <= 0 || srcCh <= 0) {
                return;
            }
            if (srcRate != lastSrcRate || srcCh != lastSrcCh) {
                lastSrcRate = srcRate;
                lastSrcCh = srcCh;
                resetResampler();   // 格式变化:相位重置,避免错位
                LogUtil.log(TAG + "src format = " + srcRate + "Hz/" + srcCh + "ch");
            }

            final int frameBytes = 2 * srcCh;
            final int srcFrames = len / frameBytes;
            if (srcFrames <= 0) return;

            // 输出上界:srcFrames * 48000/srcRate 帧,再留余量。
            final double up = (double) DST_RATE / (double) srcRate;
            final int maxOutFrames = (int) ((srcFrames + 2) * up) + 8;
            final byte[] out = new byte[maxOutFrames * DST_CH * 2];
            int outPos = 0;

            final double step = (double) srcRate / (double) DST_RATE;  // 每个输出样点前进的源帧数

            for (int i = 0; i < srcFrames; i++) {
                int base = off + i * frameBytes;
                int l = s16(data, base);
                int r = (srcCh >= 2) ? s16(data, base + 2) : l;

                if (!primed) {
                    prevL = curL = l;
                    prevR = curR = r;
                    primed = true;
                    phase = 0.0;
                    continue;   // 需两帧才能插值;首帧仅建窗
                }
                prevL = curL; prevR = curR;
                curL = l;      curR = r;

                // 在区间 [prev,cur] 内按 48000Hz 采样输出
                while (phase < 1.0 && outPos + 4 <= out.length) {
                    int oL = (int) Math.round(prevL + (curL - prevL) * phase);
                    int oR = (int) Math.round(prevR + (curR - prevR) * phase);
                    out[outPos++] = (byte) (oL & 0xFF);
                    out[outPos++] = (byte) ((oL >> 8) & 0xFF);
                    out[outPos++] = (byte) (oR & 0xFF);
                    out[outPos++] = (byte) ((oR >> 8) & 0xFF);
                    phase += step;
                }
                phase -= 1.0;   // 进入下一源区间(下采样时可能仍 >=1,由后续帧继续消化)
            }

            if (outPos > 0) {
                sender.sendPcm(out, outPos);
            }
        }
    }

    private static int s16(byte[] b, int idx) {
        int lo = b[idx] & 0xFF;
        int hi = b[idx + 1];      // 有符号高字节
        return (hi << 8) | lo;
    }
}
