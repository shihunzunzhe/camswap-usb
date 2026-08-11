#include "Resampler.h"

#include <cmath>
#include <cstring>

#include "AudioRingBuffer.h"
#include "vmic_audio_util.h"

Resampler::Resampler(AudioRingBuffer* ring, uint32_t srcRate)
    : ring_(ring),
      srcRate_(srcRate ? srcRate : 48000u),
      havePrev_(false),
      primed_(false),
      prev_{0.0f, 0.0f},
      cur_{0.0f, 0.0f},
      frac_(0.0),
      lastDstRate_(0) {}

void Resampler::reset() {
    havePrev_ = false;
    primed_ = false;
    frac_ = 0.0;
    prev_ = {0.0f, 0.0f};
    cur_ = {0.0f, 0.0f};
}

bool Resampler::pullFrame(SrcFrame* out) {
    if (!ring_) return false;
    if (ring_->availableToRead() < 4) return false;   // 不足一整帧,避免错位
    uint8_t b[4];
    if (ring_->read(b, 4) != 4) return false;
    const int16_t l = static_cast<int16_t>(static_cast<uint16_t>(b[0]) |
                                           (static_cast<uint16_t>(b[1]) << 8));
    const int16_t r = static_cast<int16_t>(static_cast<uint16_t>(b[2]) |
                                           (static_cast<uint16_t>(b[3]) << 8));
    out->l = static_cast<float>(l);
    out->r = static_cast<float>(r);
    return true;
}

// 把一个采样值(以 int16 量级表示的 float)写成目标格式,返回写入字节数。
static size_t writeSample(uint8_t* p, float x, audio_format_t fmt) {
    switch (fmt) {
        case AUDIO_FORMAT_PCM_16_BIT: {
            long v = lrintf(x);
            if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
            p[0] = static_cast<uint8_t>(v & 0xff);
            p[1] = static_cast<uint8_t>((v >> 8) & 0xff);
            return 2;
        }
        case AUDIO_FORMAT_PCM_8_BIT: {          // Android 8-bit 为无符号(偏置 128)
            long v = lrintf(x / 256.0f) + 128;
            if (v > 255) v = 255; else if (v < 0) v = 0;
            p[0] = static_cast<uint8_t>(v);
            return 1;
        }
        case AUDIO_FORMAT_PCM_FLOAT: {          // 归一化到 [-1,1]
            float f = x / 32768.0f;
            if (f > 1.0f) f = 1.0f; else if (f < -1.0f) f = -1.0f;
            memcpy(p, &f, sizeof(float));
            return 4;
        }
        case AUDIO_FORMAT_PCM_32_BIT: {         // 16->32 位左移 16
            double dv = static_cast<double>(x) * 65536.0;
            if (dv > 2147483647.0) dv = 2147483647.0;
            else if (dv < -2147483648.0) dv = -2147483648.0;
            int32_t s = static_cast<int32_t>(lrint(dv));
            memcpy(p, &s, 4);
            return 4;
        }
        case AUDIO_FORMAT_PCM_8_24_BIT: {       // 24-bit 有效值置于 32-bit 低 24 位
            long v = lrintf(x * 256.0f);
            const long lim = 1L << 23;
            if (v > lim - 1) v = lim - 1; else if (v < -lim) v = -lim;
            int32_t s = static_cast<int32_t>(v);
            memcpy(p, &s, 4);
            return 4;
        }
        case AUDIO_FORMAT_PCM_24_BIT_PACKED: {  // 3 字节小端 有符号 24-bit
            long v = lrintf(x * 256.0f);
            const long lim = 1L << 23;
            if (v > lim - 1) v = lim - 1; else if (v < -lim) v = -lim;
            uint32_t u = static_cast<uint32_t>(v);
            p[0] = static_cast<uint8_t>(u & 0xff);
            p[1] = static_cast<uint8_t>((u >> 8) & 0xff);
            p[2] = static_cast<uint8_t>((u >> 16) & 0xff);
            return 3;
        }
        default: {                              // 兜底按 S16
            long v = lrintf(x);
            if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
            p[0] = static_cast<uint8_t>(v & 0xff);
            p[1] = static_cast<uint8_t>((v >> 8) & 0xff);
            return 2;
        }
    }
}

size_t Resampler::fill(void* dst, size_t dstBytes,
                       uint32_t dstRate, audio_channel_mask_t dstMask, audio_format_t dstFormat) {
    if (!dst || dstBytes == 0 || dstRate == 0) return 0;

    const int    dstCh   = vmicChannelCount(dstMask);
    const int    dstBps  = vmicBytesPerSample(dstFormat);
    const size_t frameSz = static_cast<size_t>(dstCh) * static_cast<size_t>(dstBps);
    if (frameSz == 0) return 0;
    const size_t maxFrames = dstBytes / frameSz;
    if (maxFrames == 0) return 0;

    if (dstRate != lastDstRate_) {              // 换采样率:相位归零重同步
        frac_ = 0.0;
        lastDstRate_ = dstRate;
    }
    const double ratio = static_cast<double>(srcRate_) / static_cast<double>(dstRate);  // 源帧/目标帧

    uint8_t* out = static_cast<uint8_t*>(dst);
    size_t produced = 0;

    for (; produced < maxFrames; ++produced) {
        if (!primed_) {
            // 线性插值需要两帧建立窗口 (prev_=src[idx], cur_=src[idx+1])。
            // 若只取到一帧,保留 prev_(havePrev_),下次数据够了再补 cur_,不丢样本。
            if (!havePrev_) {
                if (!pullFrame(&prev_)) break;  // 一帧都没有
                havePrev_ = true;
            }
            if (!pullFrame(&cur_)) break;       // 还差第二帧,下次继续
            frac_ = 0.0;
            primed_ = true;
        }
        // 消费足够的源帧,使相位 frac_ 落回 [0,1)
        bool underrun = false;
        while (frac_ >= 1.0) {
            SrcFrame nf;
            if (!pullFrame(&nf)) { underrun = true; break; }
            prev_ = cur_;
            cur_ = nf;
            frac_ -= 1.0;
        }
        if (underrun) break;

        const float t = static_cast<float>(frac_);
        const float L = prev_.l + (cur_.l - prev_.l) * t;
        const float R = prev_.r + (cur_.r - prev_.r) * t;

        uint8_t* p = out;
        for (int c = 0; c < dstCh; ++c) {
            float s;
            if (dstCh == 1)  s = 0.5f * (L + R);   // 立体声 -> 单声道 下混
            else if (c == 0) s = L;
            else if (c == 1) s = R;
            else             s = 0.5f * (L + R);   // >2 声道:其余填下混单声道
            p += writeSample(p, s, dstFormat);
        }
        out += frameSz;
        frac_ += ratio;
    }
    return produced * frameSz;
}
