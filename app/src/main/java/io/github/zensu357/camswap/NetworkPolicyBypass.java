package io.github.zensu357.camswap;

import android.content.Context;
import android.content.pm.PackageManager;
import android.security.NetworkSecurityPolicy;

import java.lang.reflect.Method;

import io.github.zensu357.camswap.api101.Api101Runtime;
import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

/**
 * 目标进程网络策略旁路。
 *
 * <p>ExoPlayer / RTMP 跑在被 Hook 的目标 App 进程里，受其网络策略约束：
 * <ul>
 *   <li>Android 9+ 默认禁止明文流量（http / rtmp / 部分 rtsp），会直接
 *       {@code CLEARTEXT_NOT_PERMITTED}，表现为「VLC 能播、模块里黑屏」；</li>
 *   <li>目标 App 若未声明 {@code INTERNET} 权限，socket 会被 system_server 拒绝
 *       —— 这一条只能检测并提示，无法在应用进程内真正放开。</li>
 * </ul>
 *
 * <p>本类在目标进程 Hook {@link NetworkSecurityPolicy}，当模块处于流模式时
 * 放行明文；并在开流前做 INTERNET 权限预检。
 */
public final class NetworkPolicyBypass {

    private static final String TAG = "【CS】【net】";
    private static volatile boolean hooked;

    private NetworkPolicyBypass() {
    }

    /**
     * 在目标进程尽早调用（Application.onCreate 之前或之中均可），幂等。
     * 只 hook 当前进程的 {@link NetworkSecurityPolicy}，不影响其它应用。
     */
    public static void install(ClassLoader classLoader) {
        if (hooked) {
            return;
        }
        synchronized (NetworkPolicyBypass.class) {
            if (hooked) {
                return;
            }
            try {
                hookCleartext(classLoader);
                hooked = true;
                LogUtil.log(TAG + "已安装明文流量旁路（流模式下放行 http/rtmp/rtsp）");
            } catch (Throwable t) {
                LogUtil.log(TAG + "安装明文流量旁路失败: " + t);
            }
        }
    }

    private static void hookCleartext(ClassLoader classLoader) throws Exception {
        Class<?> policyClass = Class.forName("android.security.NetworkSecurityPolicy", false, classLoader);
        // isCleartextTrafficPermitted()
        try {
            Method m = policyClass.getDeclaredMethod("isCleartextTrafficPermitted");
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                if (shouldAllowCleartext()) {
                    return true;
                }
                return chain.proceed(chain.getArgs().toArray(new Object[0]));
            });
        } catch (NoSuchMethodException ignored) {
            // 极老 API
        }
        // isCleartextTrafficPermitted(String hostname) —— Android 7+ 按主机名细分
        try {
            Method m = policyClass.getDeclaredMethod("isCleartextTrafficPermitted", String.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                if (shouldAllowCleartext()) {
                    return true;
                }
                return chain.proceed(chain.getArgs().toArray(new Object[0]));
            });
        } catch (NoSuchMethodException ignored) {
            // API < 24
        }
    }

    /** 仅在流模式下放行，避免无谓扩大目标 App 的明文攻击面。 */
    private static boolean shouldAllowCleartext() {
        try {
            return VideoManager.isStreamMode();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 开流前预检：明文策略 + INTERNET 权限。返回可读的警告（可能多条），无问题返回 null。
     */
    public static String preflight(Context context, String url) {
        if (url == null || url.isEmpty()) {
            return "流地址为空";
        }
        StringBuilder warn = new StringBuilder();
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        boolean cleartext = lower.startsWith("http://") || lower.startsWith("rtmp://")
                || lower.startsWith("rtsp://") || lower.startsWith("rtp://");

        if (cleartext) {
            try {
                NetworkSecurityPolicy policy = NetworkSecurityPolicy.getInstance();
                boolean permitted = policy.isCleartextTrafficPermitted();
                LogUtil.log(TAG + "明文流量检查(hook后): permitted=" + permitted + " url=" + url);
                // hook 生效时这里应为 true；若仍为 false 说明 hook 没装上
                if (!permitted) {
                    warn.append("目标 App 仍禁止明文流量（旁路未生效），")
                            .append(lower.substring(0, Math.min(7, lower.length())))
                            .append("… 可能被拦截；");
                }
            } catch (Throwable t) {
                LogUtil.log(TAG + "明文流量检查异常: " + t);
            }
        }

        if (context != null) {
            try {
                int result = context.checkCallingOrSelfPermission(android.Manifest.permission.INTERNET);
                boolean hasInternet = result == PackageManager.PERMISSION_GRANTED;
                LogUtil.log(TAG + "目标进程 INTERNET 权限=" + hasInternet);
                if (!hasInternet) {
                    warn.append("目标 App 未声明/未授予 INTERNET 权限——")
                            .append("socket 会被系统拒绝（VLC 有该权限所以能播，")
                            .append("相机类 App 若没联网权限则模块无法代它联网）；");
                }
            } catch (Throwable t) {
                LogUtil.log(TAG + "INTERNET 权限检查异常: " + t);
            }
        }

        return warn.length() == 0 ? null : warn.toString();
    }
}
