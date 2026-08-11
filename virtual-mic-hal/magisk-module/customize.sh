#!/system/bin/sh
# ============================================================================
#  customize.sh —— Virtual Mic HAL Magisk 安装脚本
#
#  职责:
#    1) 探测设备 SoC 平台与 vendor lib 目录(lib64 / lib)。
#    2) 找到原厂 audio.primary.<soc>.so,备份为 .orig.so(放进模块 overlay)。
#    3) 把预编译代理 .so 以原厂同名放进 overlay,开机由 Magisk magic-mount 覆盖。
#  运行环境: Magisk 安装器(busybox ash),已提供 ui_print/abort/set_perm_recursive
#            及 $MODPATH/$ARCH/$IS64BIT 等变量。
# ============================================================================

ui_print "- Virtual Mic HAL 安装开始"

# ---- SoC 信息(仅用于日志;真正的 <soc> 从原厂文件名提取,最可靠) ----
SOC_PROP=$(getprop ro.board.platform)
[ -z "$SOC_PROP" ] && SOC_PROP=$(getprop ro.hardware)
ui_print "  设备: ABI=$ARCH 64bit=$IS64BIT ro.board.platform=$SOC_PROP"

# ---- 由 $ARCH 选预编译产物子目录 ----
case "$ARCH" in
  arm64) A64_DIR="arm64"; A32_DIR="arm" ;;
  arm)   A64_DIR="";      A32_DIR="arm" ;;
  x64)   A64_DIR="x64";   A32_DIR="x86" ;;
  x86)   A64_DIR="";      A32_DIR="x86" ;;
  *)     A64_DIR="$ARCH"; A32_DIR="$ARCH" ;;
esac

# 把一个 vendor lib 目录里的原厂 HAL 备份并放入代理。
#   $1 = 真实 vendor 目录(如 /vendor/lib64/hw)
#   $2 = 对应预编译代理 .so 路径
install_for_dir() {
  VDIR="$1"
  PREBUILT="$2"
  [ -d "$VDIR" ] || return 0
  [ -n "$PREBUILT" ] || return 0

  # 找原厂 audio.primary.*.so(排除 .orig.so)。
  STOCK=""
  for f in "$VDIR"/audio.primary.*.so; do
    [ -e "$f" ] || continue
    case "$f" in *.orig.so) continue ;; esac
    STOCK="$f"; break
  done
  if [ -z "$STOCK" ]; then
    ui_print "  跳过 $VDIR (无 audio.primary.*.so)"
    return 0
  fi

  SOC=$(basename "$STOCK" .so)
  SOC=${SOC#audio.primary.}
  ui_print "  处理 $VDIR : SoC=$SOC"

  if [ ! -f "$PREBUILT" ]; then
    # 未提供该 ABI 预编译(如只发布了 arm64):跳过此目录,不影响其它目录安装。
    ui_print "  跳过 $VDIR (未提供该 ABI 预编译: $(basename "$PREBUILT"))"
    return 0
  fi

  REL=${VDIR#/}                       # vendor/lib64/hw
  DEST="$MODPATH/system/$REL"
  mkdir -p "$DEST"

  # 处理重装/升级:若当前文件已是我们的代理(overlay 生效中),
  # 真实 HAL 在同目录 .orig.so,不能把代理自己当原厂备份。
  ORIG_SRC="$STOCK"
  if grep -qa "Virtual Mic Proxy HAL" "$STOCK" 2>/dev/null; then
    ui_print "    检测到代理已生效,改用现有 .orig.so 作为原厂源"
    ORIG_SRC="$VDIR/audio.primary.$SOC.orig.so"
    [ -f "$ORIG_SRC" ] || abort "    ! 找不到原厂备份 $ORIG_SRC (请先卸载旧模块并重启)"
  fi

  cp -f "$ORIG_SRC" "$DEST/audio.primary.$SOC.orig.so" || abort "    ! 备份原厂 HAL 失败"
  cp -f "$PREBUILT" "$DEST/audio.primary.$SOC.so"      || abort "    ! 放入代理 HAL 失败"
  ui_print "    OK: 代理 + 原厂备份(.orig.so)已就位"
  INSTALLED=1
}

INSTALLED=0

# 64 位 vendor HAL
if [ "$IS64BIT" = "true" ] && [ -n "$A64_DIR" ]; then
  install_for_dir /vendor/lib64/hw "$MODPATH/prebuilt/$A64_DIR/audio.primary.vmicproxy.so"
fi
# 32 位 vendor HAL(64 位设备也可能同时存在)
if [ -n "$A32_DIR" ]; then
  install_for_dir /vendor/lib/hw "$MODPATH/prebuilt/$A32_DIR/audio.primary.vmicproxy.so"
fi

[ "$INSTALLED" = "1" ] || abort "- 未能安装到任何 vendor 目录,已中止"

# ---- 权限与 SELinux 上下文:与 vendor 库一致 ----
ui_print "- 设置权限与 SELinux 上下文 (vendor_file)"
set_perm_recursive "$MODPATH/system" 0 0 0755 0644 u:object_r:vendor_file:s0

# 预编译目录无需随模块常驻,清理以减小体积。
rm -rf "$MODPATH/prebuilt"

ui_print "- 完成。请重启设备使其生效。"
ui_print "  SELinux 放行由 sepolicy.rule 处理;推流端连接 @virtual_mic_socket。"
