# Virtual Mic HAL 真机联调排障手册

按"**先看装没装上 → 再看连没连通 → 再看 SELinux → 最后看音频质量**"的顺序排查。
所有 `adb shell` 命令若无 root，前面加 `su -c '...'`。

---

## 0. 一分钟自检

安装并重启后，模块脚本会写一份日志:

```bash
adb shell su -c 'cat /data/local/tmp/virtual_mic_hal.log'
```

期望看到:

```
OK  proxy active : /vendor/lib64/hw/audio.primary.<soc>.so
OK  orig backup : /vendor/lib64/hw/audio.primary.<soc>.orig.so
OK  UDS listening        # 目标 App 首次采集后才出现
```

- 没有 `proxy active` → overlay 没生效(见 §A)。
- 没有 `orig backup` → 备份缺失,代理会 dlopen 失败(见 §A)。
- 没有 `UDS listening` → HAL 尚未被 open(还没 App 录音),或起服务失败(见 §B)。

---

## A. HAL 是否真正加载(Logcat)

代理内部 TAG 为 **`VirtualMicHAL`**;客户端为 **`VmicSender`**。

```bash
# 只看代理 + 客户端(最直接)
adb logcat -s VirtualMicHAL:V VmicSender:V

# 连同音频框架一起看(定位 HAL 加载/open 链路)
adb logcat | grep -iE 'VirtualMicHAL|audio_hw|AudioFlinger|audioserver|hal_audio|primary'

# 清缓冲后复现(推荐):先清,再启动目标 App 录音
adb logcat -c && adb logcat -s VirtualMicHAL:V
```

期望关键行:

```
VirtualMicHAL: proxy_dev_open: id=audio_hw_if
VirtualMicHAL: load_real_module: real HAL loaded from /vendor/lib64/hw/audio.primary.<soc>.orig.so
VirtualMicHAL: proxy_dev_open: input hooks patched on primary device
VirtualMicHAL: services started: ring=... UDS @virtual_mic_socket ...
VirtualMicHAL: open_input_stream: source=.. rate=.. ch=.. fmt=.. -> read HOOKED
```

- 看不到任何 `VirtualMicHAL` 行 → 该 App 的采集没走这个 HAL(见故障表"录音仍是真麦")。
- 有 `dlopen real HAL failed` → `.orig.so` 缺失/路径错/损坏(重装模块,确认 §0 的 orig backup)。
- 有 `proxy_dev_open` 但无 `read HOOKED` → 结构体偏移可能与该机型不符(见故障表"App 崩溃")。

---

## B. UDS 通道校验(`ss -x`)

抽象 UDS 在 `ss` 里显示为 `@virtual_mic_socket`(前导 `@` 代表抽象命名空间)。

```bash
# 是否有人在监听(HAL 服务端)——需目标 App 已触发过 HAL open
adb shell su -c 'ss -x -l | grep -a virtual_mic_socket'

# 监听 + 已建立的连接(服务端 + 客户端都在)
adb shell su -c 'ss -x -a | grep -a virtual_mic_socket'
```

判读:

| 现象 | 含义 |
|------|------|
| `LISTEN ... @virtual_mic_socket` | 服务端已就绪(HAL 已 open) |
| 另有 `ESTAB ... @virtual_mic_socket` | 客户端已连上,数据应在流动 |
| 只有 `LISTEN`，无 `ESTAB` | 推流端没连上 → 看 §C(SELinux `connectto`)或客户端是否启动 |
| 连 `LISTEN` 都没有 | HAL 没起服务 → 目标 App 还没录音,或 §A 未加载 |

客户端侧统计(用 CLI demo 或你的 App 日志):`VmicSender` 会打印 `connected` / `bytesSent` / `bytesDropped`。

---

## C. SELinux 拒绝定位与修复

最常见的"装上了但没声"就是 SELinux 拦了 `connectto` / `create` / `bind`。

**1) 抓 avc 拒绝**（复现录音后立刻抓）:

```bash
# 内核环形缓冲
adb shell su -c 'dmesg | grep -i avc | grep -iE "unix_stream_socket|audio|virtual"'
# 或从 logcat 事件缓冲
adb logcat -b events -d | grep -i avc
```

典型拒绝(客户端连不上服务端):

```
avc: denied { connectto } for pid=... comm="..." path=@virtual_mic_socket
     scontext=u:r:untrusted_app_29:s0 tcontext=u:r:hal_audio_default:s0 tclass=unix_stream_socket
```

**2) 翻译成规则**——语法即 `allow <scontext域> <tcontext域> <tclass> <权限>`：

上例补进 `magisk-module/sepolicy.rule`：

```
allow untrusted_app_29 hal_audio_default unix_stream_socket connectto
```

服务端侧若被拒(少见,规则已覆盖 audioserver/hal_audio_default):

```
avc: denied { create } ... scontext=u:r:audioserver:s0 tclass=unix_stream_socket
→ allow audioserver audioserver unix_stream_socket { create bind listen accept read write }
```

**3) 快速验证(免重启)**——先用 magiskpolicy 热加载测试，通了再写进 `sepolicy.rule`：

```bash
adb shell su -c 'magiskpolicy --live "allow untrusted_app_29 hal_audio_default unix_stream_socket connectto"'
# 复现录音,若 OK,再把该行加入 sepolicy.rule,重打包/重装 + 重启使其持久化
```

> 注意:域名带版本号(`untrusted_app_29` / `_30` …)随 targetSdk/系统变化,以实际 avc 里的
> `scontext` 为准。`sepolicy.rule` 已预置 `untrusted_app` / `platform_app`，多数情况够用。

---

## D. 故障快速对照表

| 症状 | 最可能原因 | 排查命令 | 处理 |
|------|-----------|---------|------|
| **完全无声(纯静音)** | ① overlay 未生效 ② 客户端没连上 ③ SELinux 拦 connectto ④ 推流端没推数据 | §0 日志 / `ss -x -a`(有无 ESTAB) / §C avc | 依次:重启确认 proxy active → 补 connectto 规则 → 确认 Sender `bytesSent` 增长 |
| **倍速/卡死/每N秒快放** | ClockPacer 未生效(走了兜底路径) 或 频繁欠载 | `logcat -s VirtualMicHAL` 看是否 `services started` / 看 `ring full`/欠载 | 确认服务已起;网络抖动导致欠载则让 Sender 稳定推流;必要时调大 `VMIC_MAX_BACKLOG_BYTES` |
| **杂音/滋啦/音调不对** | 源格式不符约定(非 48000/2ch/S16),或 Sender 推的不是整帧(字节错位) | 核对 Sender 解码输出格式;`open_input_stream` 日志里的 App 请求格式 | 让 Sender 严格输出 48000Hz/2ch/S16LE、按 4 字节整帧推送 |
| **音画延迟大** | 积压过多 | 观察延迟 | 调小 `VMIC_MAX_BACKLOG_BYTES`(丢更多旧数据) |
| **周期性断音** | 欠载(生产 < 消费) | logcat 看欠载补静音频率 | 稳定推流;适当增大缓冲/backlog |
| **目标 App 崩溃** | 结构体偏移与该机型 HAL 版本不符(patch 打歪) 或 read 异常 | logcat 崩溃栈;是否有 `read HOOKED` | 若无 `read HOOKED` 则该机型 HAL 布局不同,需按其版本核对 `audio_stream_in`/`audio_hw_device` 字段 |
| **录音仍是真麦克风** | 该 App 采集没走 primary HAL(走了 usb/a2dp/remote_submix 等其它 HAL),或用了 AAudio MMAP 直通 | `logcat` 无 `VirtualMicHAL` / `dumpsys media.audio_flinger` 看用的哪个 HAL | 覆盖对应 HAL 的 `.so`(如 `audio.usb.*`);MMAP 直通需另行处理 |
| **开机无音频/bootloop** | 代理与设备 ABI 不符 / dlopen `.orig.so` 失败 | `logcat` `dlopen ... failed`;`/data/local/tmp/virtual_mic_hal.log` | Magisk 安全模式(开机连按音量下)禁用模块恢复;确认 arm64 产物与设备匹配、orig 备份存在 |
| **重装后 dlopen 死循环/无声** | 旧版把代理误备份成 `.orig.so` | 检查 `.orig.so` 是否含 `Virtual Mic Proxy HAL` 字样 | 卸载模块并**重启**(恢复原厂),再装新版;新版 customize.sh 已用 marker 防误备份 |

---

## E. 常用一览

```bash
# 当前生效的音频 HAL / 输入流信息
adb shell su -c 'dumpsys media.audio_flinger | sed -n "1,120p"'

# 确认 vendor 下代理与备份
adb shell su -c 'ls -l /vendor/lib64/hw/audio.primary.*'
adb shell su -c 'grep -a "Virtual Mic Proxy HAL" /vendor/lib64/hw/audio.primary.*.so'

# 用 CLI 客户端推一段测试音频(48k/2ch/S16 裸 PCM)
adb push test_48k_2ch_s16.pcm /data/local/tmp/
adb shell su -c '/data/local/tmp/vmic_sender_demo /data/local/tmp/test_48k_2ch_s16.pcm'

# 生成一段测试 PCM(电脑上,需 ffmpeg):1kHz 正弦 5 秒
ffmpeg -f lavfi -i "sine=frequency=1000:duration=5" -ar 48000 -ac 2 -f s16le test_48k_2ch_s16.pcm
```

> 复现问题的黄金流程:`adb logcat -c` 清缓冲 → 启动目标 App 录音 →
> `adb logcat -s VirtualMicHAL:V` + 另开 `ss -x -a` 观察，同时 `dmesg | grep avc` 兜底。
