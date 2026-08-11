#include "AudioRingBuffer.h"

#include <cstring>
#include <new>

size_t AudioRingBuffer::roundUpPow2(size_t v) {
    if (v < 2) return 2;
    v--;
    v |= v >> 1;
    v |= v >> 2;
    v |= v >> 4;
    v |= v >> 8;
    v |= v >> 16;
#if SIZE_MAX > 0xFFFFFFFFu
    v |= v >> 32;
#endif
    return v + 1;
}

AudioRingBuffer::AudioRingBuffer(size_t requestedCapacityBytes)
    : buf_(nullptr),
      capacity_(roundUpPow2(requestedCapacityBytes)),
      mask_(0),
      head_(0),
      tail_(0) {
    buf_ = new (std::nothrow) uint8_t[capacity_];
    mask_ = capacity_ - 1;
}

AudioRingBuffer::~AudioRingBuffer() {
    delete[] buf_;
}

size_t AudioRingBuffer::availableToRead() const {
    const size_t h = head_.load(std::memory_order_acquire);
    const size_t t = tail_.load(std::memory_order_acquire);
    return h - t;
}

size_t AudioRingBuffer::availableToWrite() const {
    return capacity_ - availableToRead();
}

size_t AudioRingBuffer::write(const void* src, size_t n) {
    if (!buf_ || !src || n == 0) return 0;

    const size_t h = head_.load(std::memory_order_relaxed);   // 生产者独占，relaxed 即可
    const size_t t = tail_.load(std::memory_order_acquire);   // 观察消费者进度
    const size_t freeBytes = capacity_ - (h - t);
    if (n > freeBytes) n = freeBytes;                         // 满：丢弃溢出的新数据
    if (n == 0) return 0;

    const size_t off   = h & mask_;
    size_t       first = capacity_ - off;                    // 到缓冲尾的连续可写长度
    if (first > n) first = n;
    memcpy(buf_ + off, src, first);
    if (n > first) memcpy(buf_, static_cast<const uint8_t*>(src) + first, n - first);

    head_.store(h + n, std::memory_order_release);           // 数据先写、游标后发布
    return n;
}

size_t AudioRingBuffer::read(void* dst, size_t n) {
    if (!buf_ || !dst || n == 0) return 0;

    const size_t t = tail_.load(std::memory_order_relaxed);   // 消费者独占，relaxed 即可
    const size_t h = head_.load(std::memory_order_acquire);   // 观察生产者发布
    const size_t avail = h - t;
    if (n > avail) n = avail;
    if (n == 0) return 0;

    const size_t off   = t & mask_;
    size_t       first = capacity_ - off;
    if (first > n) first = n;
    memcpy(dst, buf_ + off, first);
    if (n > first) memcpy(static_cast<uint8_t*>(dst) + first, buf_, n - first);

    tail_.store(t + n, std::memory_order_release);
    return n;
}

size_t AudioRingBuffer::discardOldestDownTo(size_t keepBytes) {
    const size_t t = tail_.load(std::memory_order_relaxed);
    const size_t h = head_.load(std::memory_order_acquire);
    const size_t avail = h - t;
    if (avail <= keepBytes) return 0;
    const size_t drop = avail - keepBytes;
    tail_.store(t + drop, std::memory_order_release);         // 只前移读游标，丢弃最旧
    return drop;
}

void AudioRingBuffer::clear() {
    const size_t h = head_.load(std::memory_order_acquire);
    tail_.store(h, std::memory_order_release);
}
