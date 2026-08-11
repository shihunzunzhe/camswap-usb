#pragma once

#include <cstddef>
#include <cstdint>

// ============================================================================
//  ClockPacer —— 精准 1 倍速物理时钟控速
//
//  以单调时钟维护"下一帧应交付的绝对时刻"(nextDeadlineNs_)。每次 read 产出
//  N 帧后,把截止时刻推进 N/rate 秒,并 sleep 到该时刻——从而无论 App 底层
//  轮询多快,读取都被锁死为严格 1x(快读被 sleep 拖回);且截止时刻按真实每帧
//  时长累加,长期无漂移。
//
//  若 App 长时间不读导致落后过多(超 MAX_LAG),重置基线,避免随后无节制快读突发。
//  绝对时刻始终贴近当前时间,无整数溢出风险。
// ============================================================================

class ClockPacer {
public:
    ClockPacer();

    void reset();   // 下次 pace 重建基线(新建流 / 换采样率)

    // 在本次 read 产出 framesServed 帧(采样率 sampleRate)之后调用,阻塞到应到时刻。
    void pace(size_t framesServed, uint32_t sampleRate);

private:
    bool     started_;
    uint32_t rate_;
    int64_t  nextDeadlineNs_;   // 下一帧应交付的绝对单调时刻(纳秒)
};
