package io.github.zensu357.camswap;

import java.io.DataOutputStream;
import io.github.zensu357.camswap.utils.LogUtil;

/** 极简 root 命令执行器：用 su 放开 USB 设备节点权限，为免授权直连铺路。 */
public final class RootShell {
    private static final String TAG_PREFIX = "\u3010CS\u3011\u3010usb\u3011";
    private RootShell() {}

    public static String exec(String command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            int code = process.waitFor();
            if (code != 0) { LogUtil.log(TAG_PREFIX + "root cmd rc=" + code + ": " + command); return null; }
            return sb.toString();
        } catch (Exception e) {
            LogUtil.log(TAG_PREFIX + "root cmd fail(" + command + "): " + e);
            return null;
        } finally {
            if (process != null) { try { process.destroy(); } catch (Exception ignored) {} }
        }
    }

    public static boolean chmodUsbNode(String deviceNode) {
        if (deviceNode == null || !deviceNode.startsWith("/dev/bus/usb/")) {
            LogUtil.log(TAG_PREFIX + "bad usb node: " + deviceNode); return false;
        }
        String out = exec("chmod 666 " + deviceNode);
        boolean ok = out != null;
        LogUtil.log(TAG_PREFIX + "chmod " + deviceNode + " -> " + (ok ? "ok" : "fail"));
        return ok;
    }
}
