# Virtual Mic HAL 架构文档

系统级"物理麦克风静音 + 强制注入 RTMP 音频"方案。工作在 **vendor audio HAL 层**，
App 走 Native 也绕不过——因为麦克风采集的最底层入口 `audio_stream_in.read` 被接管。

---

## 1. 完整数据链路

```
┌───────────────────────────────────┐          ┌──────────────────────────────────────────────────────────┐
│  推流端进程  (untrusted_app)         │          │  audioserver / hal_audio_default 进程                       │
│                                     │          │  audio.primary.<soc>.so  ← 我们的代理(Magisk overlay)       │
│  RTMP 拉流 → 解码(FFmpeg/Ijk)        │          │                                                            │
│  48000Hz / 2ch / S16LE PCM          │          │   ┌───────────────┐                                        │
│         │                           │          │   │ SocketServer  │ accept + recv                          │
│         ▼                           │          │   │ (后台线程)     │                                        │
│  VmicSender.push()                  │          │   └──────┬────────┘                                        │
│         │  有界 FIFO(满则丢最旧=背压) │          │          │ write                                          │
│         ▼                           │          │          ▼                                                │
│  send(MSG_NOSIGNAL) ─┐              │          │   ┌───────────────┐                                        │
│  断线自动重连         │  @virtual_mic_socket    │   │ AudioRingBuffer│  SPSC 无锁环形缓冲                     │
│                      └──(Abstract UDS,SOCK_STREAM)─►│ (生产:socket) │                                        │
└───────────────────────────────────┘          │   └──────┬────────┘                                        │
                                                │          │ read  ① discardOldestDownTo(≈300ms) 只读最新    │
                                                │          ▼                                                │
                                                │   ┌───────────────┐   ┌───────────────┐                   │
                                                │   │  Resampler    │ → │  ClockPacer   │                   │
                                                │   │ 线性插值+混音   │   │ 单调时钟绝对   │                   │
                                                │   │ +位深转换      │   │ 截止时刻→sleep │                   │
                                                │   │ 到 App 请求格式 │   │ 精准锁 1x     │                   │
                                                │   └──────┬────────┘   └──────┬────────┘                   │
                                                │      ② 不足补 0x00 静音        │ ④                          │
                                                │          └─────────┬──────────┘                            │
                                                │                    ▼                                        │
                                                │   audio_stream_in.read()  ← 被就地劫持的函数指针            │
                                                └────────────────────┬───────────────────────────────────────┘
                                                                     │ 返回注入 PCM(物理麦全程未被读取)
                                                                     ▼
                                                        ┌──────────────────────────┐
                                                        │  目标 App (微信 / LiteAV)  │
                                                        │  AudioRecord.read()        │
                                                        └──────────────────────────┘
```

**四步(HAL 内 `proxy_read`)**：① 丢最旧控延迟 → ② 重采样到 App 动态请求的 `rate/chmask/format`
（不足补静音）→ ④ `ClockPacer` 精准 1x 阻塞。真实麦克风的 `read` 从不被调用。

---

## 2. 控制面:代理如何"成为" HAL

```
安装期 (customize.sh)                          运行期 (audioserver dlopen)
────────────────────                          ─────────────────────────
/vendor/lib64/hw/                              AudioFlinger
  audio.primary.<soc>.so  ──备份──►               │ hw_get_module("primary")
      (原厂)                 │                     ▼
                            ▼                   dlsym("HMI") → 我们的 audio_module
  Magisk overlay 覆盖:                            │ methods->open()
  audio.primary.<soc>.so      = 代理              ▼
  audio.primary.<soc>.orig.so = 原厂            proxy_dev_open:
                                                  dlopen("...orig.so") → 原厂 HAL
                                                  原厂 open() → 真实 audio_hw_device*
                                                  就地覆盖两个函数指针(见 §3)
```

代理 `dlopen` 同目录 `.orig.so`，把**除麦克风读取外的一切调用原样转发**给原厂——
扬声器/路由/参数/其它行为完全不变，风险最小。

---

## 3. HAL 结构体指针劫持原理

Android legacy audio HAL 是一组 **C 结构体 + 函数指针**。代理不重写 HAL，只在两个位置
**就地覆盖函数指针(in-place patch)**：

```
audio_hw_device (真实,原厂分配)                audio_stream_in (真实,每次 open 分配)
┌──────────────────────────┐                 ┌──────────────────────────┐
│ ...                       │                 │ common (audio_stream)     │
│ open_output_stream        │                 │ set_gain                  │
│ close_output_stream       │                 │ read  ───► proxy_read  ◄──┼── 覆盖
│ open_input_stream ──► proxy_open_input_stream│ get_input_frames_lost     │
│ close_input_stream ─► proxy_close_input_stream                          │
│ ...(其余保持原厂指针不动) │                 └──────────────────────────┘
└──────────────────────────┘
        ▲                                     proxy_open_input_stream:
        │ proxy_dev_open 里:                     调原厂 open_input_stream 拿到 stream
        └ 保存原指针后原地改写                     保存 stream->read,原地改成 proxy_read
```

**为什么可行且稳**：

- **只改指针，不重建结构体**：`open_input_stream` / `close_input_stream` / `read` 这几个字段
  在各 `AUDIO_DEVICE_API_VERSION` 间偏移长期稳定，因此天然兼容多机型/多版本。
- **安全截断**：vendored 头文件只复刻到"代理会访问的字段"为止(`read` / `close_input_stream`)。
  结构体由真实 HAL 分配，代理只读写已知偏移，从不越界索引，故截断不破坏 ABI。
- **无需转发桩**：其余几十个方法指针原封不动，调用直达原厂，零维护成本。
- **自定位原厂**：`dladdr` 反查代理自身路径 → 把 `.so` 换成 `.orig.so` 即原厂路径，与 SoC 名无关。

---

## 4. 时钟控速原理(根治 6x 快读 / 卡死)

问题:LiteAV 等在 Native 层高频轮询 `read`，若 HAL 有多少给多少，App 就会"快读快放"，
表现为倍速、卡死或异常。

解法(`ClockPacer`)——把"累计已交付音频时长"钉在真实流逝时间上：

```
每次 read 交付 N 帧(采样率 rate):
    nextDeadlineNs += N / rate * 1e9        // 按真实每帧时长累加"下一帧应到的绝对时刻"
    diff = nextDeadlineNs - now()
    if diff > 0:  sleep(diff)               // 快读 → 阻塞拖回 1x
    elif -diff > 50ms:  nextDeadlineNs=now  // 落后过多(App 长时间没读)→ 重置基线,防突发快读
```

- **锁 1x**：无论轮询多快，`read` 都会被 sleep 到"这批音频的真实时长"，物理时钟锁死 1 倍速。
- **无漂移**：截止时刻按每批真实时长累加(而非每次 `sleep` 固定值)，处理耗时被后续 sleep 吸收。
- **防突发**：空闲后不"零延迟补账"，避免 App 一次性猛读一大段。

配套:
- **断流静音填充**：RingBuffer 欠载时，未读满部分填 `0x00`，`read` 永不返回 0，录音引擎不崩。
- **只读最新**：读前 `discardOldestDownTo(≈300ms)` 丢最旧，把注入延迟钉在低位。

---

## 5. 关键参数(`hal/include/vmic_config.h`)

| 常量 | 值 | 含义 |
|------|-----|------|
| `VMIC_SOCKET_NAME` | `virtual_mic_socket` | 抽象 UDS 名(客户端须一致) |
| `VMIC_SRC_SAMPLE_RATE/CHANNELS` | 48000 / 2 | 源(Sender)格式;S16LE |
| `VMIC_RING_CAPACITY_BYTES` | 512KB | 环形缓冲容量(向上取 2 的幂) |
| `VMIC_MAX_BACKLOG_BYTES` | ≈300ms | 只读最新的积压上限 |
| `VMIC_RECV_CHUNK_BYTES` | 8192 | 单次 recv 分片 |

---

## 6. 组件与线程

| 组件 | 文件 | 线程 | 职责 |
|------|------|------|------|
| 代理入口/劫持 | `virtual_audio_hal.cpp` | audioserver 各 read 线程 | HMI/open 劫持、`proxy_read` |
| 环形缓冲 | `AudioRingBuffer.*` | 生产=socket / 消费=read | SPSC 无锁 FIFO |
| Socket 服务 | `SocketServer.*` | 独立后台线程 | 抽象 UDS 收流 |
| 重采样 | `Resampler.*` | read 线程(锁保护) | 变采样率+混音+位深 |
| 控速 | `ClockPacer.*` | read 线程 | 精准 1x |
| 客户端 | `sender/**` | 独立发送线程 | 推流+重连+背压 |

> 单源单消费者假设:同一时刻只一路有效录音(VoIP 成立);`g_read_mtx` 保护重采样器/ring 读侧。
