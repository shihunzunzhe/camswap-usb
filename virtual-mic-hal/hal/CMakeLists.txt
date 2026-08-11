cmake_minimum_required(VERSION 3.22.1)
project("virtual_mic_hal" LANGUAGES C CXX)

# ============================================================================
#  独立 NDK / CMake 构建 —— 产出 audio.primary.<soc>.so，供 Magisk overlay 部署。
#
#  用法(以 arm64-v8a、SoC=kona 为例)：
#    cmake -B build/arm64 -S . \
#      -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
#      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
#      -DVMIC_SOC=kona
#    cmake --build build/arm64
#  产物：build/arm64/audio.primary.kona.so
#
#  SoC 名(<soc>)= ro.board.platform 的值，可在设备上 `getprop ro.board.platform` 获取；
#  也可用通用名构建后由 Magisk customize.sh 按机型改名(见 magisk-module/)。
# ============================================================================

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED VMIC_SOC)
    set(VMIC_SOC "generic")
endif()

add_library(vmic_hal SHARED
    virtual_audio_hal.cpp
    AudioRingBuffer.cpp
    SocketServer.cpp
    Resampler.cpp
    ClockPacer.cpp
)

target_include_directories(vmic_hal PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/include/aosp   # vendored AOSP HAL 头文件
)

target_compile_options(vmic_hal PRIVATE
    -Wall -Wextra -Wno-unused-parameter
    -fvisibility=hidden
    -DVMIC_HAL_BUILD
)

# 输出名 = audio.primary.<soc>.so(去掉 CMake 默认的 lib 前缀)。
set_target_properties(vmic_hal PROPERTIES
    PREFIX ""
    OUTPUT_NAME "audio.primary.${VMIC_SOC}"
)

find_library(log-lib log)
target_link_libraries(vmic_hal ${log-lib})
