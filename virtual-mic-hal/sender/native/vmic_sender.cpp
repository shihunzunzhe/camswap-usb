#include "vmic_sender.h"

#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <cstddef>
#include <cstring>
#include <chrono>

// 日志：Android 上用 logcat,非 Android(host 示例/测试)用 stderr。
#if defined(__ANDROID__)
#include <android/log.h>
#define SLOGI(...) __android_log_print(ANDROID_LOG_INFO,  "VmicSender", __VA_ARGS__)
#define SLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VmicSender", __VA_ARGS__)
#else
#include <cstdio>
#define SLOGI(...) do { fprintf(stderr, "[VmicSender] " __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define SLOGE(...) do { fprintf(stderr, "[VmicSender][E] " __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

static const size_t kSendChunk     = 8192;
static const int    kBackoffStartMs = 100;
static const int    kBackoffMaxMs   = 2000;

size_t VmicSender::roundUpPow2(size_t v) {
    if (v < 2) return 2;
    v--;
    v |= v >> 1; v |= v >> 2; v |= v >> 4; v |= v >> 8; v |= v >> 16;
#if SIZE_MAX > 0xFFFFFFFFu
    v |= v >> 32;
#endif
    return v + 1;
}

VmicSender::VmicSender(const char* socketName, size_t capacityBytes)
    : name_(socketName ? socketName : "virtual_mic_socket"),
      cap_(roundUpPow2(capacityBytes < 4096 ? 4096 : capacityBytes)),
      mask_(0), head_(0), tail_(0),
      running_(false), connected_(false),
      pushed_(0), sent_(0), dropped_(0) {
    buf_.resize(cap_);
    mask_ = cap_ - 1;
}

VmicSender::~VmicSender() {
    stop();
}

bool VmicSender::start() {
    if (running_.load()) return true;
    running_.store(true);
    thread_ = std::thread(&VmicSender::threadLoop, this);
    return true;
}

void VmicSender::stop() {
    running_.store(false);
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
}

size_t VmicSender::push(const void* pcm, size_t bytes) {
    if (!pcm || bytes == 0) return 0;
    const uint8_t* src = static_cast<const uint8_t*>(pcm);
    size_t n = bytes;
    {
        std::lock_guard<std::mutex> lk(mtx_);
        // 单块超过容量：只保留末尾 cap_ 字节(前面的直接算丢弃)。
        if (n > cap_) {
            const size_t skip = n - cap_;
            src += skip;
            n = cap_;
            dropped_.fetch_add(skip, std::memory_order_relaxed);
        }
        // 背压：空间不足则丢最旧。
        const size_t used = head_ - tail_;
        if (used + n > cap_) {
            const size_t need = used + n - cap_;
            tail_ += need;
            dropped_.fetch_add(need, std::memory_order_relaxed);
        }
        // 环形写入。
        const size_t off = head_ & mask_;
        size_t first = cap_ - off;
        if (first > n) first = n;
        memcpy(&buf_[off], src, first);
        if (n > first) memcpy(&buf_[0], src + first, n - first);
        head_ += n;
        pushed_.fetch_add(n, std::memory_order_relaxed);
    }
    cv_.notify_one();
    return bytes;
}

size_t VmicSender::popChunk(uint8_t* out, size_t max) {
    std::unique_lock<std::mutex> lk(mtx_);
    cv_.wait_for(lk, std::chrono::milliseconds(100),
                 [&] { return (head_ - tail_) > 0 || !running_.load(); });
    const size_t used = head_ - tail_;
    if (used == 0) return 0;
    size_t n = used < max ? used : max;
    const size_t off = tail_ & mask_;
    size_t first = cap_ - off;
    if (first > n) first = n;
    memcpy(out, &buf_[off], first);
    if (n > first) memcpy(out + first, &buf_[0], n - first);
    tail_ += n;
    return n;
}

void VmicSender::fifoClear() {
    std::lock_guard<std::mutex> lk(mtx_);
    tail_ = head_;
}

int VmicSender::connectOnce() {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        SLOGE("socket: %s", strerror(errno));
        return -1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    const size_t nlen = name_.size();
    if (nlen + 1 > sizeof(addr.sun_path)) {
        SLOGE("name too long: %s", name_.c_str());
        close(fd);
        return -1;
    }
    addr.sun_path[0] = '\0';   // 抽象命名空间
    memcpy(addr.sun_path + 1, name_.data(), nlen);
    const socklen_t alen =
        static_cast<socklen_t>(offsetof(struct sockaddr_un, sun_path) + 1 + nlen);
    if (connect(fd, reinterpret_cast<struct sockaddr*>(&addr), alen) < 0) {
        close(fd);   // HAL 未起/被拒,交给退避重连
        return -1;
    }
    return fd;
}

// 非阻塞探测对端是否已关闭(用于空闲期也能及时发现断线,让 isConnected() 准确)。
static bool socketDead(int fd) {
    struct pollfd p;
    p.fd = fd;
    p.events = POLLIN | POLLRDHUP;
    p.revents = 0;
    int r = poll(&p, 1, 0);
    if (r < 0) return errno != EINTR;                 // poll 出错视为不可用
    if (r == 0) return false;                         // 无事件,连接健康
    if (p.revents & (POLLHUP | POLLERR | POLLNVAL | POLLRDHUP)) return true;
    if (p.revents & POLLIN) {
        char c;
        ssize_t n = recv(fd, &c, 1, MSG_PEEK | MSG_DONTWAIT);
        if (n == 0) return true;                      // 对端有序关闭(EOF)
        if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK) return true;
    }
    return false;
}

bool VmicSender::sendAll(int fd, const uint8_t* p, size_t n) {
    size_t off = 0;
    while (off < n) {
        // MSG_NOSIGNAL：对端关闭时返回 EPIPE 而非触发 SIGPIPE 杀进程。
        ssize_t w = send(fd, p + off, n - off, MSG_NOSIGNAL);
        if (w > 0) {
            off += static_cast<size_t>(w);
            sent_.fetch_add(static_cast<uint64_t>(w), std::memory_order_relaxed);
            continue;
        }
        if (w < 0 && errno == EINTR) continue;
        if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) continue;  // 阻塞 socket 一般不至此
        return false;   // EPIPE / ECONNRESET 等 -> 断线
    }
    return true;
}

void VmicSender::threadLoop() {
    SLOGI("sender thread started, target @%s", name_.c_str());
    int backoff = kBackoffStartMs;
    uint8_t chunk[kSendChunk];

    while (running_.load()) {
        int fd = connectOnce();
        if (fd < 0) {
            connected_.store(false, std::memory_order_relaxed);
            // 退避重连(可被 stop() 打断)。
            std::unique_lock<std::mutex> lk(mtx_);
            cv_.wait_for(lk, std::chrono::milliseconds(backoff),
                         [&] { return !running_.load(); });
            backoff = backoff < kBackoffMaxMs ? backoff * 2 : kBackoffMaxMs;
            continue;
        }
        SLOGI("connected @%s", name_.c_str());
        connected_.store(true, std::memory_order_relaxed);
        backoff = kBackoffStartMs;
        fifoClear();   // 连上后丢弃陈旧积压,发送新鲜音频(与 HAL 端 ring->clear 呼应)。

        while (running_.load()) {
            size_t got = popChunk(chunk, sizeof(chunk));
            if (got == 0) {
                // 空闲期(无数据):主动探测对端,及时发现断线并重连。
                if (socketDead(fd)) { SLOGI("peer closed while idle, reconnect"); break; }
                continue;
            }
            if (!sendAll(fd, chunk, got)) {
                SLOGE("send failed: %s, reconnect", strerror(errno));
                break;
            }
        }
        close(fd);
        connected_.store(false, std::memory_order_relaxed);
    }
    SLOGI("sender thread exiting");
}

// ============================================================================
//  可选 CLI Demo：把裸 PCM(48000/2ch/S16LE)推给 HAL,用于真机快速验证。
//  编译：定义 VMIC_SENDER_DEMO(见 Android.mk 的 vmic_sender_demo 目标)。
//  用法：vmic_sender_demo [pcm_file|-]   ('-' 或省略 = 读 stdin)
// ============================================================================
#ifdef VMIC_SENDER_DEMO
#include <cstdio>
#include <ctime>

int main(int argc, char** argv) {
    const char* path = (argc > 1) ? argv[1] : "-";
    FILE* f = (strcmp(path, "-") == 0) ? stdin : fopen(path, "rb");
    if (!f) {
        fprintf(stderr, "open %s failed\n", path);
        return 1;
    }
    VmicSender sender;   // 默认连 @virtual_mic_socket
    sender.start();

    uint8_t buf[8192];
    size_t r;
    // 全速推入,靠 HAL 端 ClockPacer 控速;背压由本类 FIFO 丢旧处理。
    while ((r = fread(buf, 1, sizeof(buf), f)) > 0) {
        sender.push(buf, r);
    }
    // 给发送线程一点时间把队列清空。
    struct timespec ts { 1, 0 };
    nanosleep(&ts, nullptr);

    printf("pushed=%llu sent=%llu dropped=%llu connected=%d\n",
           static_cast<unsigned long long>(sender.bytesPushed()),
           static_cast<unsigned long long>(sender.bytesSent()),
           static_cast<unsigned long long>(sender.bytesDropped()),
           sender.isConnected() ? 1 : 0);

    sender.stop();
    if (f != stdin) fclose(f);
    return 0;
}
#endif
