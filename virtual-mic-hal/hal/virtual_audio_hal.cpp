// ============================================================================
//  virtual_audio_hal.cpp —— Magisk Virtual Audio HAL 代理层
//
//  设计要点(与"改写 HAL / 重实现 HAL"相对的"代理/劫持"路线)：
//    1. 本 .so 以原厂同名 audio.primary.<soc>.so 通过 Magisk overlay 部署到
//       /vendor/lib64/hw/，成为 AudioFlinger 实际加载的 HAL。
//    2. 运行时 dlopen 原厂 HAL 的备份(audio.primary.<soc>.orig.so)，把除
//       "麦克风读取"以外的所有调用原样转发给它——扬声器/路由/参数/其它一切
//       行为都与原厂一致，风险最小。
//    3. 只在两个位置做"就地补丁(in-place patch)"：
//         audio_hw_device.open_input_stream  -> 代理，用于拿到输入流对象
//         audio_stream_in.read               -> 代理，接管麦克风采集
//
//  阶段进度：
//    [阶段1] audio_module 入口 + open 链路劫持 + read 三件套骨架        ✅
//    [阶段2] SocketServer(Abstract UDS) 收流 -> AudioRingBuffer -> read  ✅(本次)
//    [阶段3] 在 RingBuffer 与 read 之间插入 Resampler + ClockPacer      ⏳
// ============================================================================

#include <hardware/hardware.h>
#include <hardware/audio.h>
#include <system/audio.h>

#include <dlfcn.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <string>
#include <mutex>
#include <unordered_map>

#include "virtual_audio_hal.h"
#include "vmic_audio_util.h"
#include "AudioRingBuffer.h"
#include "SocketServer.h"
#include "Resampler.h"
#include "ClockPacer.h"

// ---------------------------------------------------------------------------
//  前置声明
// ---------------------------------------------------------------------------
static int  proxy_dev_open(const hw_module_t* module, const char* id, hw_device_t** device);
static int  proxy_open_input_stream(audio_hw_device_t* dev,
                                    audio_io_handle_t handle,
                                    audio_devices_t devices,
                                    audio_config* config,
                                    audio_stream_in** stream_in,
                                    audio_input_flags_t flags,
                                    const char* address,
                                    audio_source_t source);
static void proxy_close_input_stream(audio_hw_device_t* dev, audio_stream_in* stream_in);
static ssize_t proxy_read(audio_stream_in* stream, void* buffer, size_t bytes);

// ---------------------------------------------------------------------------
//  服务(单例)：Socket 服务 + 环形缓冲 + 重采样器 + 时钟控速
//  注：假定同一时刻只有一路有效录音(VoIP 场景成立)——重采样器/控速器为单源
//  单消费者共享;g_read_mtx 保护它们与 ring 的读侧,避免多流并发读时状态错乱。
// ---------------------------------------------------------------------------
static AudioRingBuffer* g_ring      = nullptr;
static SocketServer*    g_server    = nullptr;
static Resampler*       g_resampler = nullptr;
static ClockPacer*      g_pacer     = nullptr;
static std::mutex       g_read_mtx;

// ---------------------------------------------------------------------------
//  真实 HAL 的原始函数指针 + 每流 read 备份
// ---------------------------------------------------------------------------
static int  (*g_orig_open_input_stream)(audio_hw_device_t*, audio_io_handle_t,
                                        audio_devices_t, audio_config*,
                                        audio_stream_in**, audio_input_flags_t,
                                        const char*, audio_source_t) = nullptr;
static void (*g_orig_close_input_stream)(audio_hw_device_t*, audio_stream_in*) = nullptr;

static std::mutex g_streams_mtx;
static std::unordered_map<audio_stream_in*, ssize_t (*)(audio_stream_in*, void*, size_t)>
        g_orig_reads;

// ---------------------------------------------------------------------------
//  加载真实(原厂)HAL：dlopen 与自身同目录的 *.orig.so 备份
// ---------------------------------------------------------------------------
static audio_module* load_real_module() {
    static audio_module* s_real = nullptr;
    static std::once_flag s_once;
    std::call_once(s_once, []() {
        Dl_info info{};
        if (dladdr(reinterpret_cast<void*>(&proxy_dev_open), &info) == 0 || !info.dli_fname) {
            ALOGE("load_real_module: dladdr failed, cannot locate self path");
            return;
        }
        std::string self = info.dli_fname;                 // /vendor/lib64/hw/audio.primary.<soc>.so
        std::string orig = self;
        auto pos = orig.rfind(".so");
        if (pos == std::string::npos) {
            ALOGE("load_real_module: self path has no .so suffix: %s", self.c_str());
            return;
        }
        orig.replace(pos, 3, VMIC_REAL_HAL_SUFFIX);         // -> audio.primary.<soc>.orig.so

        void* h = dlopen(orig.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!h) {
            ALOGE("load_real_module: dlopen real HAL failed: %s (%s)", orig.c_str(), dlerror());
            return;
        }
        auto* m = reinterpret_cast<audio_module*>(dlsym(h, HAL_MODULE_INFO_SYM_AS_STR));
        if (!m) {
            ALOGE("load_real_module: dlsym(%s) failed in %s", HAL_MODULE_INFO_SYM_AS_STR, orig.c_str());
            dlclose(h);
            return;
        }
        s_real = m;
        ALOGI("load_real_module: real HAL loaded from %s", orig.c_str());
    });
    return s_real;
}

// ---------------------------------------------------------------------------
//  就地补丁：把真实 device 的输入相关方法替换为代理
// ---------------------------------------------------------------------------
static void patch_input_hooks(audio_hw_device_t* adev) {
    if (!adev) return;
    if (adev->open_input_stream && adev->open_input_stream != proxy_open_input_stream) {
        g_orig_open_input_stream = adev->open_input_stream;
        adev->open_input_stream  = proxy_open_input_stream;
    }
    if (adev->close_input_stream && adev->close_input_stream != proxy_close_input_stream) {
        g_orig_close_input_stream = adev->close_input_stream;
        adev->close_input_stream  = proxy_close_input_stream;
    }
}

// ---------------------------------------------------------------------------
//  服务启动(仅一次)：拉起 SocketServer + AudioRingBuffer
// ---------------------------------------------------------------------------
void vmic_ensure_services_started() {
    static std::once_flag s_once;
    std::call_once(s_once, []() {
        g_ring = new AudioRingBuffer(VMIC_RING_CAPACITY_BYTES);
        if (!g_ring || !g_ring->valid()) {
            ALOGE("services: ring buffer alloc failed");
            return;
        }
        g_server = new SocketServer(VMIC_SOCKET_NAME, g_ring);
        if (!g_server->start()) {
            ALOGE("services: socket server start failed");
            return;
        }
        g_resampler = new Resampler(g_ring, VMIC_SRC_SAMPLE_RATE);
        g_pacer     = new ClockPacer();
        ALOGI("services started: ring=%zuB, UDS @%s, src=%dHz/%dch/S16",
              g_ring->capacity(), VMIC_SOCKET_NAME, VMIC_SRC_SAMPLE_RATE, VMIC_SRC_CHANNELS);
    });
}

// ---------------------------------------------------------------------------
//  HAL 入口:hw_module_methods_t.open
// ---------------------------------------------------------------------------
static int proxy_dev_open(const hw_module_t* /*module*/, const char* id, hw_device_t** device) {
    ALOGI("proxy_dev_open: id=%s", id ? id : "(null)");

    audio_module* real = load_real_module();
    if (!real || !real->common.methods || !real->common.methods->open) {
        ALOGE("proxy_dev_open: real HAL unavailable, abort open");
        return -EINVAL;
    }

    int rc = real->common.methods->open(&real->common, id, device);
    if (rc != 0 || !device || !*device) {
        ALOGE("proxy_dev_open: real open failed rc=%d", rc);
        return rc;
    }

    // 只对音频设备(primary)打补丁——它才有 open_input_stream。
    if (id && strcmp(id, AUDIO_HARDWARE_INTERFACE) == 0) {
        auto* adev = reinterpret_cast<audio_hw_device_t*>(*device);
        patch_input_hooks(adev);
        vmic_ensure_services_started();
        ALOGI("proxy_dev_open: input hooks patched on primary device");
    }
    return 0;
}

// ---------------------------------------------------------------------------
//  代理:open_input_stream —— 拿到输入流后就地劫持它的 read
// ---------------------------------------------------------------------------
static int proxy_open_input_stream(audio_hw_device_t* dev,
                                   audio_io_handle_t handle,
                                   audio_devices_t devices,
                                   audio_config* config,
                                   audio_stream_in** stream_in,
                                   audio_input_flags_t flags,
                                   const char* address,
                                   audio_source_t source) {
    if (!g_orig_open_input_stream) return -ENOSYS;

    int rc = g_orig_open_input_stream(dev, handle, devices, config,
                                      stream_in, flags, address, source);
    if (rc != 0 || !stream_in || !*stream_in) return rc;

    audio_stream_in* in = *stream_in;
    {
        std::lock_guard<std::mutex> lk(g_streams_mtx);
        g_orig_reads[in] = in->read;      // 备份原始 read(用于 close 时清理)
    }
    in->read = proxy_read;                 // 就地劫持

    // 新建录音会话：复位重采样相位与控速基线,干净起播。
    {
        std::lock_guard<std::mutex> lk(g_read_mtx);
        if (g_ring)      g_ring->clear();
        if (g_resampler) g_resampler->reset();
        if (g_pacer)     g_pacer->reset();
    }

    const audio_channel_mask_t chmask = in->common.get_channels(&in->common);
    const audio_format_t       fmt    = in->common.get_format(&in->common);
    ALOGI("open_input_stream: source=%d rate=%u ch=%#x fmt=%#x frameSize=%zu -> read HOOKED",
          source,
          in->common.get_sample_rate(&in->common),
          chmask, fmt, vmicFrameSize(fmt, chmask));
    return rc;
}

// ---------------------------------------------------------------------------
//  代理:close_input_stream —— 清理备份并转发
// ---------------------------------------------------------------------------
static void proxy_close_input_stream(audio_hw_device_t* dev, audio_stream_in* stream_in) {
    {
        std::lock_guard<std::mutex> lk(g_streams_mtx);
        g_orig_reads.erase(stream_in);
    }
    if (g_orig_close_input_stream) g_orig_close_input_stream(dev, stream_in);
}

// ---------------------------------------------------------------------------
//  代理:audio_stream_in.read —— 麦克风采集接管入口(核心)
//
//  三件套：
//    [屏蔽物理 Mic] 绝不调用被备份的原始 read，真麦噪声无从混入。
//    [时钟控速]     严格按 bytes 对应的真实时长 sleep，锁死 1 倍速物理时钟，
//                   无论 App(如 LiteAV)底层轮询多快都拉不动节奏。
//    [断流兜底]     未读满部分填 0x00，永远返回请求字节数，绝不返回 0。
// ---------------------------------------------------------------------------
static ssize_t proxy_read(audio_stream_in* stream, void* buffer, size_t bytes) {
    if (!stream || !buffer || bytes == 0) return static_cast<ssize_t>(bytes);

    const uint32_t             rate      = stream->common.get_sample_rate(&stream->common);
    const audio_channel_mask_t chmask    = stream->common.get_channels(&stream->common);
    const audio_format_t       fmt       = stream->common.get_format(&stream->common);
    const size_t               frameSize = vmicFrameSize(fmt, chmask);

    // ---- [屏蔽物理 Mic] + [重采样注入] ----
    // 绝不调用被备份的原始 read；从 RingBuffer 取源(48000/2ch/S16),重采样为
    // App 请求的 rate/chmask/fmt。锁保护重采样器/ring 读侧(单源单消费者)。
    size_t got = 0;
    {
        std::lock_guard<std::mutex> lk(g_read_mtx);
        if (g_ring) {
            g_ring->discardOldestDownTo(VMIC_MAX_BACKLOG_BYTES);   // 只读最新,控延迟
        }
        if (g_resampler) {
            got = g_resampler->fill(buffer, bytes, rate, chmask, fmt);
        }
    }

    // ---- [断流静音填充] 未读满补 0x00,绝不返回 0 ----
    if (got < bytes) {
        memset(static_cast<uint8_t*>(buffer) + got, 0, bytes - got);
    }

    // ---- [时钟控速] 精准锁 1 倍速物理时钟(在锁外阻塞,不占用读锁) ----
    if (g_pacer && frameSize > 0) {
        g_pacer->pace(bytes / frameSize, rate);
    } else if (rate > 0 && frameSize > 0) {
        // 兜底:服务未就绪时用简单 usleep 控速。
        const double frames   = static_cast<double>(bytes) / static_cast<double>(frameSize);
        const long   sleep_us = static_cast<long>((frames * 1000000.0) / static_cast<double>(rate));
        if (sleep_us > 0) usleep(static_cast<useconds_t>(sleep_us));
    }

    return static_cast<ssize_t>(bytes);
}

// ---------------------------------------------------------------------------
//  HAL 模块入口符号(HMI)——AudioFlinger 通过 dlsym("HMI") 找到它
//
//  ⚠ 必须显式标记 default 可见性:编译带 -fvisibility=hidden,否则 HMI 会被隐藏,
//  导致 dlsym("HMI") 失败(HAL 加载不了)。加了 default 后即"只导出这一个入口符号"。
// ---------------------------------------------------------------------------
static struct hw_module_methods_t g_module_methods = {
    .open = proxy_dev_open,
};

extern "C" __attribute__((visibility("default"))) struct audio_module HAL_MODULE_INFO_SYM = {
    .common = {
        .tag                = HARDWARE_MODULE_TAG,
        .module_api_version = AUDIO_MODULE_API_VERSION_0_1,
        .hal_api_version    = HARDWARE_HAL_API_VERSION,
        .id                 = AUDIO_HARDWARE_MODULE_ID,   // "audio"
        .name               = "Virtual Mic Proxy HAL",
        .author             = "camswap",
        .methods            = &g_module_methods,
        .dso                = nullptr,
        .reserved           = {0},
    },
};
