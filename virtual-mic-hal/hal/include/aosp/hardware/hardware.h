#pragma once

// ============================================================================
//  hardware/hardware.h —— 从 AOSP libhardware 精简复刻(仅代理所需部分)
//  用于独立 NDK/CMake 构建时替代 AOSP 源码树里的同名头文件。
//  结构体布局与 AOSP 完全一致，保证与真机 vendor HAL 的 ABI 对齐。
// ============================================================================

#include <stdint.h>
#include <sys/cdefs.h>

__BEGIN_DECLS

#define MAKE_TAG_CONSTANT(A, B, C, D) (((A) << 24) | ((B) << 16) | ((C) << 8) | (D))

#define HARDWARE_MODULE_TAG MAKE_TAG_CONSTANT('H', 'W', 'M', 'T')
#define HARDWARE_DEVICE_TAG MAKE_TAG_CONSTANT('H', 'W', 'D', 'T')

#define HARDWARE_MAKE_API_VERSION(maj, min) \
    ((((maj) & 0xff) << 8) | ((min) & 0xff))
#define HARDWARE_MODULE_API_VERSION(maj, min) HARDWARE_MAKE_API_VERSION(maj, min)
#define HARDWARE_HAL_API_VERSION HARDWARE_MAKE_API_VERSION(1, 0)

struct hw_module_t;
struct hw_module_methods_t;
struct hw_device_t;

typedef struct hw_module_t {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char* id;
    const char* name;
    const char* author;
    struct hw_module_methods_t* methods;
    void* dso;
#ifdef __LP64__
    uint64_t reserved[32 - 7];
#else
    uint32_t reserved[32 - 7];
#endif
} hw_module_t;

typedef struct hw_module_methods_t {
    int (*open)(const struct hw_module_t* module, const char* id,
                struct hw_device_t** device);
} hw_module_methods_t;

typedef struct hw_device_t {
    uint32_t tag;
    uint32_t version;
    struct hw_module_t* module;
#ifdef __LP64__
    uint64_t reserved[12];
#else
    uint32_t reserved[12];
#endif
    int (*close)(struct hw_device_t* device);
} hw_device_t;

// 模块入口符号：AudioFlinger 通过 dlsym("HMI") 找到 audio_module。
#define HAL_MODULE_INFO_SYM        HMI
#define HAL_MODULE_INFO_SYM_AS_STR "HMI"

__END_DECLS
