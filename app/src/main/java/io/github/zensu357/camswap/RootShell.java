package io.github.zensu357.camswap;

import java.io.DataOutputStream;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * Root 命令执行器。CamSwap 作为 Xposed/LSPosed 模块运行在已 root 设备上，
 * 用 su 放开 USB 设备节点权限，为免授权直连铺路。
 *
 * <p>每一步都打详细日志（TAG 【CS】【usb】【root】），失败时能从 logcat 精确定位。
 */
public final class RootShell {

    private static final String TAG = "【CS】【usb】【root】";

    private RootShell() {
    }

    /** su 是否可用（能拿到 root shell）。会触发 Magisk 授权弹窗。 */
    public static boolean isRootAvailable() {
        String out = exec("id");
        boolean ok = out != null && out.contains("uid=0");
        LogUtil.log(TAG + "isRootAvailable=" + ok + " (id -> " + (out == null ? "null" : out.trim()) + ")");
        return ok;
    }

    /**
     * 执行一批 root 命令（用换行分隔），返回标准输出；失败返回 null。
     * 用一个 su 会话跑多条，减少多次弹 Magisk。
     */
    public static String exec(String command) {
        Process process = null;
        try {
            // 优先 `su`；部分环境需要 `su -c`，这里用交互式写入方式兼容大多数 su 实现
            process = Runtime.getRuntime().exec(new String[] { "su" });
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            StringBuilder out = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
            // 读取 stderr 便于诊断
            StringBuilder err = new StringBuilder();
            java.io.BufferedReader er = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getErrorStream()));
            while ((line = er.readLine()) != null) {
                err.append(line).append('\n');
            }
            int code = process.waitFor();
            if (code != 0) {
                LogUtil.log(TAG + "cmd rc=" + code + " cmd=[" + command + "] err=" + err.toString().trim());
                return null;
            }
            if (err.length() > 0) {
                LogUtil.log(TAG + "cmd ok(rc=0) but stderr=" + err.toString().trim());
            }
            return out.toString();
        } catch (Exception e) {
            // 最常见：su 不存在（未 root）或 Magisk 未授权 → IOException
            LogUtil.log(TAG + "exec FAIL (su 不可用/未授权?) cmd=[" + command + "] ex=" + e);
            return null;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 放开 USB 设备节点，使 App 能用 ParcelFileDescriptor 直接打开。
     * <p>
     * 仅 chmod 在 SELinux enforcing 下往往不够（untrusted_app 域打不开 usb_device 节点），
     * 因此一并：改属主为当前应用 uid、放宽 SELinux（临时 permissive，best-effort）、
     * 打印节点 ls -Z 便于诊断。
     *
     * @param deviceNode /dev/bus/usb/BBB/DDD
     * @param appUid     本应用 uid（用于 chown，让 App 域可访问）
     */
    public static boolean prepareUsbNode(String deviceNode, int appUid) {
        if (deviceNode == null || !deviceNode.startsWith("/dev/bus/usb/")) {
            LogUtil.log(TAG + "非法节点: " + deviceNode);
            return false;
        }
        // 先确认 root 可用（触发授权）
        if (!isRootAvailable()) {
            LogUtil.log(TAG + "root 不可用——请在 Magisk/超级用户里授予 CamSwap root 权限后重试");
            return false;
        }
        // 组合命令：chmod 666 + chown 到本 app uid + 打印诊断 + 尝试放宽 SELinux
        String cmd = "chmod 666 " + deviceNode + " 2>&1; "
                + "chown " + appUid + ":" + appUid + " " + deviceNode + " 2>&1; "
                + "ls -lZ " + deviceNode + " 2>&1; "
                + "getenforce 2>&1";
        String out = exec(cmd);
        if (out == null) {
            LogUtil.log(TAG + "prepareUsbNode 命令执行失败");
            return false;
        }
        LogUtil.log(TAG + "prepareUsbNode 诊断:\n" + out.trim());
        // 若 SELinux enforcing，尝试临时 permissive（best-effort，可能被策略禁止）
        if (out.contains("Enforcing")) {
            String se = exec("setenforce 0 2>&1; getenforce");
            LogUtil.log(TAG + "SELinux 处理: " + (se == null ? "失败" : se.trim()));
        }
        return true;
    }
}
