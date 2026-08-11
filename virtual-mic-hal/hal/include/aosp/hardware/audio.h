#pragma once

// ============================================================================
//  hardware/audio.h —— 从 AOSP libhardware(legacy audio HAL) 精简复刻
//
//  ⚠ ABI 关键：audio_stream / audio_stream_in / audio_hw_device 的字段顺序
//    必须与真机 vendor HAL 完全一致——代理靠"就地覆盖指定偏移的函数指针"工作。
//    这里按 AOSP 原始顺序复刻，并在"代理只访问到此为止"处安全截断(截断之后的
//    成员代理从不索引，且结构体由真实 HAL 分配，故截断不影响 ABI 正确性)。
// ============================================================================

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/cdefs.h>
#include <sys/types.h>

#include <hardware/hardware.h>
#include <system/audio.h>

__BEGIN_DECLS

#define AUDIO_HARDWARE_MODULE_ID "audio"
#define AUDIO_HARDWARE_INTERFACE "audio_hw_if"

#define AUDIO_MODULE_API_VERSION_0_1     HARDWARE_MODULE_API_VERSION(0, 1)
#define AUDIO_MODULE_API_VERSION_CURRENT AUDIO_MODULE_API_VERSION_0_1

// effect_handle_t 真实为 effect_interface_s**，此处用 void** 占位(指针尺寸一致)。
typedef void** effect_handle_t;

// ---- audio_stream：输入/输出流的公共基类(14 个函数指针，须完整) ----
struct audio_stream {
    uint32_t (*get_sample_rate)(const struct audio_stream* stream);
    int (*set_sample_rate)(struct audio_stream* stream, uint32_t rate);
    size_t (*get_buffer_size)(const struct audio_stream* stream);
    audio_channel_mask_t (*get_channels)(const struct audio_stream* stream);
    audio_format_t (*get_format)(const struct audio_stream* stream);
    int (*set_format)(struct audio_stream* stream, audio_format_t format);
    int (*standby)(struct audio_stream* stream);
    int (*dump)(const struct audio_stream* stream, int fd);
    audio_devices_t (*get_device)(const struct audio_stream* stream);
    int (*set_device)(struct audio_stream* stream, audio_devices_t device);
    int (*set_parameters)(struct audio_stream* stream, const char* kv_pairs);
    char* (*get_parameters)(const struct audio_stream* stream, const char* keys);
    int (*add_audio_effect)(const struct audio_stream* stream, effect_handle_t effect);
    int (*remove_audio_effect)(const struct audio_stream* stream, effect_handle_t effect);
};
typedef struct audio_stream audio_stream_t;

// 输出流：代理不触碰，前向声明即可(设备方法仅按指针引用它)。
struct audio_stream_out;

// ---- audio_stream_in：代理需访问 common / set_gain / read ----
struct audio_stream_in {
    struct audio_stream common;
    int (*set_gain)(struct audio_stream_in* stream, float gain);
    ssize_t (*read)(struct audio_stream_in* stream, void* buffer, size_t bytes);
    uint32_t (*get_input_frames_lost)(struct audio_stream_in* stream);
    int (*get_capture_position)(const struct audio_stream_in* stream,
                                int64_t* frames, int64_t* time);
    // —— 安全截断 —— 真实结构体在此之后仍有 start/stop/mmap/microphone 等成员，
    //    代理从不访问，故省略以避免引入更多类型依赖。
};
typedef struct audio_stream_in audio_stream_in_t;

// ---- audio_hw_device：代理需 patch open_input_stream / close_input_stream ----
struct audio_hw_device {
    struct hw_device_t common;

    uint32_t (*get_supported_devices)(const struct audio_hw_device* dev);
    int (*init_check)(const struct audio_hw_device* dev);
    int (*set_voice_volume)(struct audio_hw_device* dev, float volume);
    int (*set_master_volume)(struct audio_hw_device* dev, float volume);
    int (*get_master_volume)(struct audio_hw_device* dev, float* volume);
    int (*set_mode)(struct audio_hw_device* dev, audio_mode_t mode);
    int (*set_mic_mute)(struct audio_hw_device* dev, bool state);
    int (*get_mic_mute)(const struct audio_hw_device* dev, bool* state);
    int (*set_parameters)(struct audio_hw_device* dev, const char* kv_pairs);
    char* (*get_parameters)(const struct audio_hw_device* dev, const char* keys);
    size_t (*get_input_buffer_size)(const struct audio_hw_device* dev,
                                    const struct audio_config* config);
    int (*open_output_stream)(struct audio_hw_device* dev,
                              audio_io_handle_t handle,
                              audio_devices_t devices,
                              audio_output_flags_t flags,
                              struct audio_config* config,
                              struct audio_stream_out** stream_out,
                              const char* address);
    void (*close_output_stream)(struct audio_hw_device* dev,
                                struct audio_stream_out* stream_out);
    int (*open_input_stream)(struct audio_hw_device* dev,
                             audio_io_handle_t handle,
                             audio_devices_t devices,
                             struct audio_config* config,
                             struct audio_stream_in** stream_in,
                             audio_input_flags_t flags,
                             const char* address,
                             audio_source_t source);
    void (*close_input_stream)(struct audio_hw_device* dev,
                               struct audio_stream_in* stream_in);
    // —— 安全截断 —— 真实结构体在此之后仍有 dump/master_mute/audio_patch/
    //    microphones 等成员，代理从不访问，故省略。
};
typedef struct audio_hw_device audio_hw_device_t;

// ---- audio_module：HAL 模块入口(HAL_MODULE_INFO_SYM 的类型) ----
struct audio_module {
    struct hw_module_t common;
};

__END_DECLS
