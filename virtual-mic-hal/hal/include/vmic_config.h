#pragma once

#include <cstdint>

// ============================================================================
//  Virtual Mic HAL —— 全局共享常量
//  HAL 代理层 / Socket 服务 / RingBuffer / Sender 客户端 共用同一份定义。
//  阶段 1 只用到 LOG_TAG 与 REAL_HAL_SUFFIX；其余为阶段 2/3/4 预留。
// ============================================================================

// ---- 日志 ----
#define VMIC_LOG_TAG "VirtualMicHAL"

// ---- 真实 vendor HAL 备份文件后缀 ----
// Magisk 安装脚本(customize.sh)会把原厂 audio.primary.<soc>.so 备份成
// audio.primary.<soc>.orig.so，代理层运行时 dlopen 这个备份继续转发非 read 调用。
#define VMIC_REAL_HAL_SUFFIX ".orig.so"

// ---- IPC(阶段 2) ----
// Abstract Unix Domain Socket 名称：实际 bind 时首字节填 '\0'(抽象命名空间，不落地文件)。
// Sender(RTMP 拉流端)作为 client 连到这里；HAL 作为 server。
#define VMIC_SOCKET_NAME "virtual_mic_socket"

// ---- Sender 推入的"源" PCM 规格(阶段 2/3)----
// 约定 Sender 统一按此规格推流，重采样到 App 请求的目标规格由 HAL 内部完成。
#define VMIC_SRC_SAMPLE_RATE       48000   // Hz
#define VMIC_SRC_CHANNELS          2       // 双声道
#define VMIC_SRC_BYTES_PER_SAMPLE  2       // S16LE

// ---- RingBuffer 容量(阶段 2)----
// 48000 * 2ch * 2byte = 192000 B/s，512KB ≈ 2.7s 源音频上限(实际会向上取整到 2 的幂)。
#define VMIC_RING_CAPACITY_BYTES   (512 * 1024)

// 单次 recv 的分片大小(Socket 收流缓冲)。
#define VMIC_RECV_CHUNK_BYTES      8192

// 积压上限(以"源字节"计)：读取前若积压超过此值则丢最旧，控制注入延迟。
// 300ms 源音频 = 48000 * 2 * 2 * 300 / 1000 = 57600 B。
#define VMIC_MAX_BACKLOG_BYTES \
    (VMIC_SRC_SAMPLE_RATE * VMIC_SRC_CHANNELS * VMIC_SRC_BYTES_PER_SAMPLE * 300 / 1000)
