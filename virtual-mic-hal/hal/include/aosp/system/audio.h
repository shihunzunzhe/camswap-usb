#pragma once

// ============================================================================
//  system/audio.h —— 从 AOSP system/media/audio 精简复刻(仅代理所需类型)
//  枚举取值与 AOSP 保持一致：真机 HAL 的 get_format()/get_channels() 返回值
//  需要按这些常量解释(用于重采样与时钟控速的 bytes/frame 计算)。
// ============================================================================

#include <stdint.h>
#include <sys/cdefs.h>

__BEGIN_DECLS

typedef int      audio_io_handle_t;
typedef uint32_t audio_devices_t;
typedef uint32_t audio_channel_mask_t;

// audio_format_t 为 32 位；此处只列出代理会判定的 PCM 子格式，取值同 AOSP。
typedef enum {
    AUDIO_FORMAT_INVALID           = 0xFFFFFFFFu,
    AUDIO_FORMAT_DEFAULT           = 0u,
    AUDIO_FORMAT_PCM_16_BIT        = 0x1u,
    AUDIO_FORMAT_PCM_8_BIT         = 0x2u,
    AUDIO_FORMAT_PCM_32_BIT        = 0x3u,
    AUDIO_FORMAT_PCM_8_24_BIT      = 0x4u,
    AUDIO_FORMAT_PCM_FLOAT         = 0x5u,
    AUDIO_FORMAT_PCM_24_BIT_PACKED = 0x6u,
} audio_format_t;

typedef enum {
    AUDIO_SOURCE_DEFAULT            = 0,
    AUDIO_SOURCE_MIC                = 1,
    AUDIO_SOURCE_VOICE_UPLINK       = 2,
    AUDIO_SOURCE_VOICE_DOWNLINK     = 3,
    AUDIO_SOURCE_VOICE_CALL         = 4,
    AUDIO_SOURCE_CAMCORDER          = 5,
    AUDIO_SOURCE_VOICE_RECOGNITION  = 6,
    AUDIO_SOURCE_VOICE_COMMUNICATION= 7,
    AUDIO_SOURCE_REMOTE_SUBMIX      = 8,
    AUDIO_SOURCE_UNPROCESSED        = 9,
    AUDIO_SOURCE_VOICE_PERFORMANCE  = 10,
} audio_source_t;

// 以下枚举仅为满足 audio_hw_device 函数指针签名的 ABI(均为 32 位 int)。
typedef enum { AUDIO_MODE_NORMAL        = 0 } audio_mode_t;
typedef enum { AUDIO_INPUT_FLAG_NONE    = 0x0 } audio_input_flags_t;
typedef enum { AUDIO_OUTPUT_FLAG_NONE   = 0x0 } audio_output_flags_t;

// 代理只透传 audio_config* 指针，不解引用其字段，故前向声明即可。
struct audio_config;

__END_DECLS
