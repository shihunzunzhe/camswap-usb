#!/system/bin/sh
# ============================================================================
#  service.sh —— late_start(开机基本完成后)记录 HAL 与抽象 UDS 状态,便于验证。
#  纯诊断,不影响功能。抽象 socket 在 @<name>,ss 里显示为 "@virtual_mic_socket"。
# ============================================================================
LOG=/data/local/tmp/virtual_mic_hal.log
{
  echo "==== $(date) service(boot completed) ===="
  # 抽象 UDS 是否已被 HAL 监听(HAL 首次被 open 后才起服务)。
  if command -v ss >/dev/null 2>&1; then
    ss -x -l 2>/dev/null | grep -a "virtual_mic_socket" && echo "OK  UDS listening" \
      || echo "..  UDS 尚未监听(等目标 App 首次采集触发 HAL open)"
  fi
} >> "$LOG" 2>&1
