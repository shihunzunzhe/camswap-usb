#!/system/bin/sh
# ============================================================================
#  post-fs-data.sh —— 开机早期(vendor 已挂载,SELinux 已加载)完整性自检 + 日志
#
#  代理 .so 的 overlay 覆盖由 Magisk magic-mount 自动完成(模块 system/ 目录),
#  本脚本不做挂载,仅做一次自检并写日志,便于排障(SELinux 放行见 sepolicy.rule)。
# ============================================================================
MODDIR=${0%/*}
LOG=/data/local/tmp/virtual_mic_hal.log

{
  echo "==== $(date) post-fs-data ===="
  for d in /vendor/lib64/hw /vendor/lib/hw; do
    for f in "$d"/audio.primary.*.so; do
      [ -e "$f" ] || continue
      case "$f" in *.orig.so) continue ;; esac
      if grep -qa "Virtual Mic Proxy HAL" "$f" 2>/dev/null; then
        echo "OK  proxy active : $f"
      else
        echo "??  not proxy    : $f (overlay 未生效?)"
      fi
      orig="${f%.so}.orig.so"
      [ -f "$orig" ] && echo "OK  orig backup : $orig" || echo "!!  MISSING orig: $orig"
    done
  done
} >> "$LOG" 2>&1
