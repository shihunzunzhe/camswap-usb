#pragma once

#include <cstddef>
#include <cstdint>

#include <system/audio.h>

class AudioRingBuffer;

// ============================================================================
//  Resampler —— 把源 PCM(固定 srcRate / 双声道 / S16LE)动态重采样为
//  App 请求的 rate / channel_mask / format。
//
//  流水线：从 RingBuffer 增量取源帧 -> 线性插值做采样率转换 -> 声道混合
//         (立体声<->单声道等) -> 位深/格式转换(S16/FLOAT/8/32/8.24/24packed)。
//  插值状态(相位 frac_ 与相邻源帧 prev_/cur_)跨多次 read 连续保持,避免拼接爆音。
//
//  注：轻量线性插值,下采样(如 48k->16k)不含抗混叠低通,追求低依赖/低延迟;
//  若需更高保真可换 libswresample(见 README),接口保持不变。
// ============================================================================

class Resampler {
public:
    Resampler(AudioRingBuffer* ring, uint32_t srcRate);

    // 填充 dst(至多 dstBytes)为目标格式 PCM。返回实际产出字节数
    // (源数据不足时 < dstBytes,剩余部分由调用方补静音)。
    size_t fill(void* dst, size_t dstBytes,
                uint32_t dstRate, audio_channel_mask_t dstMask, audio_format_t dstFormat);

    void reset();   // 复位插值状态(新建流 / 重连 / 换采样率)

private:
    struct SrcFrame { float l; float r; };
    bool pullFrame(SrcFrame* out);   // 从 ring 取一整源帧(4 字节 = S16 stereo)

    AudioRingBuffer* ring_;
    uint32_t         srcRate_;

    bool     havePrev_;      // 已取到插值窗口左端点 prev_
    bool     primed_;        // 插值窗口(prev_,cur_)已建立
    SrcFrame prev_;          // 源帧 src[idx]   (插值左端)
    SrcFrame cur_;           // 源帧 src[idx+1] (插值右端)
    double   frac_;          // 当前相位 [0,1)：prev_->cur_ 之间的插值位置
    uint32_t lastDstRate_;   // 目标采样率变化时复位相位
};
