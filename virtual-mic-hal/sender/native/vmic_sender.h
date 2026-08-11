#pragma once

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// ============================================================================
//  VmicSender —— Virtual Mic HAL 的 PCM 推送客户端(Native/C++ 示例)
//
//  用法：RTMP 拉流端解码出 PCM 后,调用 push() 推入;本类内部后台线程负责
//  连接 HAL 的抽象 UDS(@virtual_mic_socket)并稳定发送,断线自动重连。
//
//  源格式约定：48000Hz / 2ch / S16LE(必须与 HAL 端 VMIC_SRC_* 一致)。
//
//  背压(Backpressure)：push() 非阻塞,写入有界 FIFO;当消费(发送)跟不上生产时,
//  FIFO 满则丢弃最旧数据(realtime 音频保低延迟),而不是阻塞解码线程或无限堆积。
//  发送线程用阻塞 send,内核发送缓冲填满时自然回压,叠加 FIFO 丢旧,双重限住延迟。
// ============================================================================

class VmicSender {
public:
    // socketName 必须与 HAL 端 VMIC_SOCKET_NAME 一致(默认 "virtual_mic_socket")。
    explicit VmicSender(const char* socketName = "virtual_mic_socket",
                        size_t capacityBytes = 512 * 1024);
    ~VmicSender();

    VmicSender(const VmicSender&) = delete;
    VmicSender& operator=(const VmicSender&) = delete;

    bool start();   // 起后台发送线程
    void stop();    // 通知退出并 join

    // 解码线程调用：推入 48000/2ch/S16 PCM。非阻塞;缓冲满丢最旧。返回接收字节数(=bytes)。
    size_t push(const void* pcm, size_t bytes);

    bool     isConnected()  const { return connected_.load(std::memory_order_relaxed); }
    uint64_t bytesPushed()  const { return pushed_.load(std::memory_order_relaxed); }
    uint64_t bytesSent()    const { return sent_.load(std::memory_order_relaxed); }
    uint64_t bytesDropped() const { return dropped_.load(std::memory_order_relaxed); }  // 背压丢弃

private:
    void   threadLoop();
    int    connectOnce();                                   // 连一次,成功返回 fd,否则 -1
    bool   sendAll(int fd, const uint8_t* p, size_t n);     // 全量发送,失败(断线)返回 false
    size_t popChunk(uint8_t* out, size_t max);              // 取一批待发数据(阻塞至有/超时/停)
    void   fifoClear();

    static size_t roundUpPow2(size_t v);

    std::string name_;

    // ---- 有界 FIFO(mutex 保护,环形字节缓冲) ----
    std::vector<uint8_t>    buf_;
    size_t                  cap_;
    size_t                  mask_;
    size_t                  head_;   // 写游标
    size_t                  tail_;   // 读游标
    std::mutex              mtx_;
    std::condition_variable cv_;

    std::thread        thread_;
    std::atomic<bool>  running_;
    std::atomic<bool>  connected_;

    std::atomic<uint64_t> pushed_;
    std::atomic<uint64_t> sent_;
    std::atomic<uint64_t> dropped_;
};
