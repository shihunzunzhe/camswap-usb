#include "ClockPacer.h"

#include <cerrno>
#include <ctime>

// 落后超过此阈值(App 长时间没读/系统卡顿)则重置基线,防止随后快读突发。
static const int64_t kMaxLagNs = 50 * 1000000LL;   // 50ms

static int64_t nowMonoNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

static void sleepNs(int64_t ns) {
    if (ns <= 0) return;
    struct timespec req;
    req.tv_sec  = static_cast<time_t>(ns / 1000000000LL);
    req.tv_nsec = static_cast<long>(ns % 1000000000LL);
    struct timespec rem;
    while (nanosleep(&req, &rem) != 0 && errno == EINTR) req = rem;   // 被信号打断续睡
}

ClockPacer::ClockPacer() : started_(false), rate_(0), nextDeadlineNs_(0) {}

void ClockPacer::reset() { started_ = false; }

void ClockPacer::pace(size_t framesServed, uint32_t sampleRate) {
    if (sampleRate == 0) return;

    const int64_t now = nowMonoNs();
    if (!started_ || rate_ != sampleRate) {
        started_ = true;
        rate_ = sampleRate;
        nextDeadlineNs_ = now;
    }

    // 本次这批帧对应的真实播放时长,推进截止时刻。
    const int64_t durNs =
        static_cast<int64_t>(static_cast<double>(framesServed) * 1e9 / static_cast<double>(sampleRate));
    nextDeadlineNs_ += durNs;

    const int64_t diff = nextDeadlineNs_ - now;
    if (diff > 0) {
        sleepNs(diff);                 // 快读:拖回到 1x
    } else if (-diff > kMaxLagNs) {
        nextDeadlineNs_ = now;         // 落后过多:重置基线
    }
}
