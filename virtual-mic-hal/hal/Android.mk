LOCAL_PATH := $(call my-dir)

# ============================================================================
#  独立 ndk-build 构建(CMake 之外的备选) —— 产出 audio.primary.<soc>.so
#
#  用法：在本目录执行(需先 export ANDROID_NDK)：
#    $ANDROID_NDK/ndk-build VMIC_SOC=kona
#  产物：libs/arm64-v8a/audio.primary.kona.so
#
#  说明：ndk-build 默认会给 SHARED_LIBRARY 加 "lib" 前缀，这里用
#  LOCAL_MODULE_FILENAME 显式指定最终文件名，去掉前缀，直接得到
#  audio.primary.<soc>.so，无需再改名。
# ============================================================================

include $(CLEAR_VARS)

VMIC_SOC ?= generic

LOCAL_MODULE          := vmic_hal
LOCAL_MODULE_FILENAME := audio.primary.$(VMIC_SOC)

LOCAL_SRC_FILES := \
    virtual_audio_hal.cpp \
    AudioRingBuffer.cpp \
    SocketServer.cpp \
    Resampler.cpp \
    ClockPacer.cpp

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH) \
    $(LOCAL_PATH)/include \
    $(LOCAL_PATH)/include/aosp

LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Wno-unused-parameter \
                  -fvisibility=hidden -DVMIC_HAL_BUILD
LOCAL_LDLIBS   := -llog

include $(BUILD_SHARED_LIBRARY)
