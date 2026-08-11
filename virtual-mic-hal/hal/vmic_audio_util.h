#pragma once

#include <cstddef>
#include <cstdint>

#include <system/audio.h>

// ============================================================================
//  音频格式辅助计算(不依赖 AOSP 内联函数，自带实现，便于独立 NDK 构建)
// ============================================================================

static inline int vmicBytesPerSample(audio_format_t f) {
    switch (f) {
        case AUDIO_FORMAT_PCM_16_BIT:        return 2;
        case AUDIO_FORMAT_PCM_8_BIT:         return 1;
        case AUDIO_FORMAT_PCM_32_BIT:        return 4;
        case AUDIO_FORMAT_PCM_8_24_BIT:      return 4;
        case AUDIO_FORMAT_PCM_FLOAT:         return 4;
        case AUDIO_FORMAT_PCM_24_BIT_PACKED: return 3;
        default:                             return 2;  // 兜底按 16-bit
    }
}

// 位置掩码/索引掩码通用：representation 标记位在高 2 位，
// 统计低 30 位的置位数即声道数(两种掩码都成立)。
static inline int vmicChannelCount(audio_channel_mask_t mask) {
    int n = __builtin_popcount(mask & 0x3FFFFFFFu);
    return n > 0 ? n : 1;
}

static inline size_t vmicFrameSize(audio_format_t f, audio_channel_mask_t mask) {
    return static_cast<size_t>(vmicChannelCount(mask)) *
           static_cast<size_t>(vmicBytesPerSample(f));
}
