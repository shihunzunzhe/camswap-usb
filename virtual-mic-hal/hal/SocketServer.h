#pragma once

#include <atomic>
#include <string>
#include <thread>

class AudioRingBuffer;

// ============================================================================
//  SocketServer —— Abstract Unix Domain Socket 监听 + 收流
//
//  在 HAL(audioserver 进程)内起一个后台线程：bind 抽象命名空间 @<name>，
//  accept 一个 client(RTMP 拉流端 Sender)，循环 recv PCM 并写入 RingBuffer。
//  client 断开后回到 accept 等待下一次连接。用 self-pipe + poll 实现可中断退出。
// ============================================================================

class SocketServer {
public:
    SocketServer(const char* abstractName, AudioRingBuffer* ring);
    ~SocketServer();

    SocketServer(const SocketServer&) = delete;
    SocketServer& operator=(const SocketServer&) = delete;

    bool start();   // 起监听 + 后台线程；成功返回 true
    void stop();    // 通知退出并 join

    bool isClientConnected() const { return clientConnected_.load(std::memory_order_relaxed); }

private:
    void threadLoop();
    int  createListenSocket();
    void serveClient(int clientFd);   // 单个 client 的收流循环

    std::string       name_;
    AudioRingBuffer*  ring_;
    std::thread       thread_;
    std::atomic<bool> running_;
    std::atomic<bool> clientConnected_;
    int               listenFd_;
    int               wakeR_;   // self-pipe 读端(用于中断 poll)
    int               wakeW_;   // self-pipe 写端
};
