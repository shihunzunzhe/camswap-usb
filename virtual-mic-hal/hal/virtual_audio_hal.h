#pragma once

#include <android/log.h>
#include "vmic_config.h"

// ============================================================================
//  Virtual Mic HAL —— 内部声明(阶段 1 骨架)
// ============================================================================

#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  VMIC_LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  VMIC_LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, VMIC_LOG_TAG, __VA_ARGS__)

// 阶段 2/3 会在此挂载：SocketServer + AudioRingBuffer + Resampler + ClockPacer。
// 阶段 1 仅暴露一个"服务启动一次"的钩子，当前为空实现。
void vmic_ensure_services_started();
