#include "SocketServer.h"

#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>

#include "AudioRingBuffer.h"
#include "virtual_audio_hal.h"   // ALOG* 宏
#include "vmic_config.h"

SocketServer::SocketServer(const char* abstractName, AudioRingBuffer* ring)
    : name_(abstractName ? abstractName : ""),
      ring_(ring),
      running_(false),
      clientConnected_(false),
      listenFd_(-1),
      wakeR_(-1),
      wakeW_(-1) {}

SocketServer::~SocketServer() {
    stop();
}

int SocketServer::createListenSocket() {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        ALOGE("socket() failed: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;

    const size_t nlen = name_.size();
    if (nlen + 1 > sizeof(addr.sun_path)) {
        ALOGE("socket name too long: %s", name_.c_str());
        close(fd);
        return -1;
    }
    // 抽象命名空间：sun_path[0] = '\0'，名字紧随其后(不落地文件系统)。
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, name_.data(), nlen);
    const socklen_t alen =
        static_cast<socklen_t>(offsetof(struct sockaddr_un, sun_path) + 1 + nlen);

    if (bind(fd, reinterpret_cast<struct sockaddr*>(&addr), alen) < 0) {
        ALOGE("bind(@%s) failed: %s", name_.c_str(), strerror(errno));
        close(fd);
        return -1;
    }
    if (listen(fd, 1) < 0) {
        ALOGE("listen() failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    ALOGI("listening on abstract UDS @%s", name_.c_str());
    return fd;
}

bool SocketServer::start() {
    if (running_.load()) return true;

    int p[2];
    if (pipe2(p, O_CLOEXEC | O_NONBLOCK) != 0) {
        ALOGE("pipe2() failed: %s", strerror(errno));
        return false;
    }
    wakeR_ = p[0];
    wakeW_ = p[1];

    listenFd_ = createListenSocket();
    if (listenFd_ < 0) {
        close(wakeR_);
        close(wakeW_);
        wakeR_ = wakeW_ = -1;
        return false;
    }

    running_.store(true);
    thread_ = std::thread(&SocketServer::threadLoop, this);
    return true;
}

void SocketServer::stop() {
    running_.store(false);
    if (wakeW_ >= 0) {
        const char c = 1;
        ssize_t r = ::write(wakeW_, &c, 1);   // 唤醒 poll
        (void)r;
    }
    if (thread_.joinable()) thread_.join();

    if (listenFd_ >= 0) { close(listenFd_); listenFd_ = -1; }
    if (wakeR_ >= 0)    { close(wakeR_);    wakeR_ = -1; }
    if (wakeW_ >= 0)    { close(wakeW_);    wakeW_ = -1; }
}

void SocketServer::threadLoop() {
    ALOGI("SocketServer thread started");
    while (running_.load()) {
        struct pollfd fds[2];
        fds[0].fd = listenFd_; fds[0].events = POLLIN; fds[0].revents = 0;
        fds[1].fd = wakeR_;    fds[1].events = POLLIN; fds[1].revents = 0;

        int rc = poll(fds, 2, -1);
        if (rc < 0) {
            if (errno == EINTR) continue;
            ALOGE("poll(listen) failed: %s", strerror(errno));
            break;
        }
        if (fds[1].revents & POLLIN) break;   // 收到停止信号

        if (fds[0].revents & POLLIN) {
            int cfd = accept4(listenFd_, nullptr, nullptr, SOCK_CLOEXEC);
            if (cfd < 0) {
                if (errno == EINTR || errno == EAGAIN) continue;
                ALOGE("accept4() failed: %s", strerror(errno));
                continue;
            }
            ALOGI("sender client connected");
            clientConnected_.store(true, std::memory_order_relaxed);
            if (ring_) ring_->clear();   // 新客户端接入，丢弃陈旧积压，重新同步
            serveClient(cfd);
            clientConnected_.store(false, std::memory_order_relaxed);
            close(cfd);
            ALOGI("sender client disconnected");
        }
    }
    ALOGI("SocketServer thread exiting");
}

void SocketServer::serveClient(int clientFd) {
    uint8_t buf[VMIC_RECV_CHUNK_BYTES];
    while (running_.load()) {
        struct pollfd fds[2];
        fds[0].fd = clientFd; fds[0].events = POLLIN; fds[0].revents = 0;
        fds[1].fd = wakeR_;   fds[1].events = POLLIN; fds[1].revents = 0;

        int rc = poll(fds, 2, -1);
        if (rc < 0) {
            if (errno == EINTR) continue;
            ALOGE("poll(client) failed: %s", strerror(errno));
            return;
        }
        if (fds[1].revents & POLLIN) return;                       // 停止信号
        if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) return;  // 对端异常

        if (fds[0].revents & POLLIN) {
            ssize_t got = recv(clientFd, buf, sizeof(buf), 0);
            if (got == 0) return;                                 // 对端正常关闭
            if (got < 0) {
                if (errno == EINTR || errno == EAGAIN) continue;
                ALOGE("recv() failed: %s", strerror(errno));
                return;
            }
            if (ring_) {
                size_t w = ring_->write(buf, static_cast<size_t>(got));
                if (w < static_cast<size_t>(got)) {
                    ALOGW("ring full, dropped %zu bytes", static_cast<size_t>(got) - w);
                }
            }
        }
    }
}
