LOCAL_PATH := $(call my-dir)

# ============================================================================
#  VmicSender 客户端(ndk-build)
#   - libvmicsender.so ：可链接进 App 的 RTMP 解码 native 代码
#   - vmic_sender_demo ：独立 CLI,把裸 PCM 推给 HAL,用于真机验证
#      用法(设备上,root)： ./vmic_sender_demo /sdcard/test_48k_2ch_s16.pcm
#      或： cat x.pcm | ./vmic_sender_demo -
# ============================================================================

# ---- 共享库 ----
include $(CLEAR_VARS)
LOCAL_MODULE   := vmicsender
LOCAL_SRC_FILES := vmic_sender.cpp
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -fvisibility=hidden
LOCAL_LDLIBS   := -llog
include $(BUILD_SHARED_LIBRARY)

# ---- CLI Demo 可执行 ----
include $(CLEAR_VARS)
LOCAL_MODULE   := vmic_sender_demo
LOCAL_SRC_FILES := vmic_sender.cpp
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -DVMIC_SENDER_DEMO
LOCAL_LDLIBS   := -llog
include $(BUILD_EXECUTABLE)
