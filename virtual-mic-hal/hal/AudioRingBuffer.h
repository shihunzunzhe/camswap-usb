#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

// ============================================================================
//  AudioRingBuffer —— 单生产者/单消费者(SPSC)无锁环形缓冲
//
//  生产者：SocketServer 收流线程(recv -> write)
//  消费者：HAL 的 audio_stream_in.read 线程(read)
//
//  设计：head_/tail_ 为单调递增的绝对游标，容量取 2 的幂，用位与取模。
//  绝对游标天然区分"满(head-tail==capacity)"与"空(head-tail==0)"，可用满容量。
//  仅生产者写 head_、仅消费者写 tail_，配合 acquire/release 内存序即无需锁。
// ============================================================================

class AudioRingBuffer {
public:
    explicit AudioRingBuffer(size_t requestedCapacityBytes);
    ~AudioRingBuffer();

    AudioRingBuffer(const AudioRingBuffer&) = delete;
    AudioRingBuffer& operator=(const AudioRingBuffer&) = delete;

    bool valid() const { return buf_ != nullptr; }

    // 生产者：写入至多 n 字节，返回实际写入量(缓冲不足则 < n，溢出的新数据被丢弃)。
    size_t write(const void* src, size_t n);

    // 消费者：读出至多 n 字节，返回实际读出量(数据不足则 < n)。
    size_t read(void* dst, size_t n);

    size_t availableToRead() const;
    size_t availableToWrite() const;
    size_t capacity() const { return capacity_; }

    // 消费者侧：若积压超过 keepBytes 则丢弃最旧数据，只保留最新 keepBytes(控制延迟)。
    // 返回被丢弃的字节数。仅消费者调用(与 read 同线程)。
    size_t discardOldestDownTo(size_t keepBytes);

    // 消费者侧：清空缓冲(丢弃全部积压，重同步)。仅消费者调用。
    void clear();

private:
    static size_t roundUpPow2(size_t v);

    uint8_t* buf_;
    size_t   capacity_;   // = 2^k
    size_t   mask_;       // capacity_ - 1
    std::atomic<size_t> head_;  // 写游标(生产者独占写)
    std::atomic<size_t> tail_;  // 读游标(消费者独占写)
};
