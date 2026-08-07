package io.github.zensu357.camswap;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.zensu357.camswap.api101.Api101Runtime;
import io.github.zensu357.camswap.utils.LogUtil;

/**
 * 在被 Hook 的目标进程里加载 CamSwap 模块 APK 自带的 native 库。
 *
 * <p>典型场景：Media3 RTMP 依赖 {@code librtmp-jni.so}，它打包在 CamSwap APK 里；
 * 目标进程的 {@link System#loadLibrary(String)} 只搜索目标 App 自己的
 * {@code nativeLibraryDir}，找不到就会 {@link UnsatisfiedLinkError}，
 * 表现为 RTMP 黑屏而 VLC（自身带 so）能播。
 *
 * <p>策略：
 * <ol>
 *   <li>先试 {@code System.loadLibrary}（LSPosed 若已把模块 lib 目录注入则直接成功）；</li>
 *   <li>再试 CamSwap 安装目录的 {@code nativeLibraryDir/lib&lt;name&gt;.so}；</li>
 *   <li>最后从 CamSwap APK 内 {@code lib/&lt;abi&gt;/lib&lt;name&gt;.so} 解压到
 *       目标进程码缓存目录再 {@code System.load}；</li>
 *   <li>Hook {@code Runtime.loadLibrary0}：{@code RtmpClient} 的 {@code <clinit>}
 *       仍会再调一次 {@code System.loadLibrary("rtmp-jni")}，若 ClassLoader 找不到 so
 *       会在「库已 load 进进程」的情况下仍抛 {@link UnsatisfiedLinkError}——hook 拦截后
 *       直接走我们已解析好的绝对路径。</li>
 * </ol>
 */
public final class ModuleNativeLoader {

    private static final String TAG = "【CS】【native】";
    private static final Object LOCK = new Object();
    /** libName(无 lib/前缀、无 .so) → 已解析的绝对路径 */
    private static final Map<String, String> LOADED_PATHS = new HashMap<>();
    private static volatile boolean rtmpLoaded;
    private static volatile boolean loadLibraryHooked;
    /** 防止 hook 回调里再走 System.loadLibrary 造成重入死循环 */
    private static final ThreadLocal<Boolean> IN_LOADER = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ModuleNativeLoader() {
    }

    /** 仅安装 {@code Runtime.loadLibrary0} hook，不实际加载 so。包加载早期调用。 */
    public static void installLoadLibraryHook() {
        ensureLoadLibraryHook();
    }

    /** 确保 {@code rtmp-jni} 已加载；可重复调用，成功后短路。 */
    public static boolean ensureRtmpJni(Context context) {
        if (rtmpLoaded) {
            return true;
        }
        synchronized (LOCK) {
            if (rtmpLoaded) {
                return true;
            }
            ensureLoadLibraryHook();
            boolean ok = loadLibrary(context, "rtmp-jni");
            rtmpLoaded = ok;
            return ok;
        }
    }

    /**
     * 确保 IjkPlayer 三件套已加载：{@code ijkffmpeg → ijksdl → ijkplayer}。
     * 供 {@link IjkPlayerBackend} 在构造播放器前调用；内部仍走 {@link #loadLibrary}。
     */
    public static boolean ensureIjk(Context context) {
        ensureLoadLibraryHook();
        boolean a = loadLibrary(context, "ijkffmpeg");
        boolean b = loadLibrary(context, "ijksdl");
        boolean c = loadLibrary(context, "ijkplayer");
        LogUtil.log(TAG + "ensureIjk ffmpeg=" + a + " sdl=" + b + " player=" + c);
        return a && b && c;
    }

    public static boolean loadLibrary(Context context, String libName) {
        if (libName == null || libName.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            if (LOADED_PATHS.containsKey(libName)) {
                return true;
            }
        }

        boolean reentered = Boolean.TRUE.equals(IN_LOADER.get());
        IN_LOADER.set(Boolean.TRUE);
        try {
            // 1) 常规路径（仅非重入时尝试，避免 hook 回调里再进 System.loadLibrary 死循环）
            if (!reentered) {
                try {
                    System.loadLibrary(libName);
                    synchronized (LOCK) {
                        LOADED_PATHS.put(libName, "system:" + libName);
                    }
                    LogUtil.log(TAG + "System.loadLibrary(\"" + libName + "\") 成功");
                    return true;
                } catch (UnsatisfiedLinkError e) {
                    LogUtil.log(TAG + "System.loadLibrary(\"" + libName + "\") 失败: " + e.getMessage());
                }
            }

            // 2) CamSwap 已安装的 nativeLibraryDir
            ApplicationInfo hostInfo = resolveHostInfo(context);
            if (hostInfo != null && hostInfo.nativeLibraryDir != null) {
                File so = new File(hostInfo.nativeLibraryDir, "lib" + libName + ".so");
                if (so.isFile()) {
                    if (loadAbsolute(libName, so.getAbsolutePath())) {
                        return true;
                    }
                } else {
                    LogUtil.log(TAG + "宿主 nativeLibraryDir 无 " + so.getAbsolutePath());
                }
            }

            // 3) 从 CamSwap APK 按当前 ABI 解压
            if (hostInfo == null || hostInfo.sourceDir == null) {
                LogUtil.log(TAG + "无法定位 CamSwap APK，放弃加载 " + libName);
                return false;
            }
            File outDir = resolveExtractDir(context);
            if (outDir == null) {
                LogUtil.log(TAG + "无法创建解压目录");
                return false;
            }
            File outSo = new File(outDir, "lib" + libName + ".so");
            String abi = preferredAbi();
            String entryName = "lib/" + abi + "/lib" + libName + ".so";
            try {
                if (!outSo.isFile() || outSo.length() == 0) {
                    if (!extractZipEntry(hostInfo.sourceDir, entryName, outSo)) {
                        boolean extracted = false;
                        String[] abis = android.os.Build.SUPPORTED_ABIS;
                        if (abis != null) {
                            for (String a : abis) {
                                if (a == null || a.equals(abi)) {
                                    continue;
                                }
                                if (extractZipEntry(hostInfo.sourceDir,
                                        "lib/" + a + "/lib" + libName + ".so", outSo)) {
                                    extracted = true;
                                    abi = a;
                                    break;
                                }
                            }
                        }
                        if (!extracted) {
                            LogUtil.log(TAG + "APK 中找不到 " + entryName + "（及其它 ABI）");
                            return false;
                        }
                    }
                }
                if (loadAbsolute(libName, outSo.getAbsolutePath())) {
                    LogUtil.log(TAG + "从 APK(" + abi + ") 解压并 load 成功: " + outSo.getAbsolutePath());
                    return true;
                }
                return false;
            } catch (Throwable t) {
                LogUtil.log(TAG + "从 APK 加载 " + libName + " 失败: " + t);
                return false;
            }
        } finally {
            if (!reentered) {
                IN_LOADER.set(Boolean.FALSE);
            }
        }
    }

    private static boolean loadAbsolute(String libName, String absolutePath) {
        try {
            System.load(absolutePath);
            synchronized (LOCK) {
                LOADED_PATHS.put(libName, absolutePath);
            }
            LogUtil.log(TAG + "System.load(" + absolutePath + ") 成功");
            return true;
        } catch (UnsatisfiedLinkError e) {
            LogUtil.log(TAG + "System.load(" + absolutePath + ") 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * Hook {@code Runtime.loadLibrary0}：让后续 {@code System.loadLibrary("rtmp-jni")}
     * （例如 {@code RtmpClient.<clinit>}）在 ClassLoader 找不到 so 时，改走我们已解析的绝对路径。
     * 必须在首次触发 RtmpClient 类加载之前装好。
     */
    private static void ensureLoadLibraryHook() {
        if (loadLibraryHooked) {
            return;
        }
        synchronized (LOCK) {
            if (loadLibraryHooked) {
                return;
            }
            try {
                hookRuntimeLoadLibrary0();
                loadLibraryHooked = true;
                LogUtil.log(TAG + "已 hook Runtime.loadLibrary0，可拦截 rtmp-jni 等模块 so");
            } catch (Throwable t) {
                LogUtil.log(TAG + "hook Runtime.loadLibrary0 失败（将依赖绝对路径预加载）: " + t);
            }
        }
    }

    private static void hookRuntimeLoadLibrary0() throws Exception {
        // Android 版本签名差异：
        //   旧: loadLibrary0(ClassLoader, String)
        //   新: loadLibrary0(ClassLoader, Class, String)  或  loadLibrary0(ClassLoader, String)
        Method target = null;
        Class<?> runtimeClass = Runtime.class;
        for (Method m : runtimeClass.getDeclaredMethods()) {
            if (!"loadLibrary0".equals(m.getName())) {
                continue;
            }
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length == 2 && pts[0] == ClassLoader.class && pts[1] == String.class) {
                target = m;
                break;
            }
            if (pts.length == 3 && pts[0] == ClassLoader.class && pts[2] == String.class) {
                target = m;
                // 继续看有没有更短的，优先 3 参（API 29+ 真实路径）
            }
        }
        if (target == null) {
            // 再试 public API System.loadLibrary —— 某些 ROM 内联后仍可 hook
            target = System.class.getDeclaredMethod("loadLibrary", String.class);
        }
        final Method hooked = target;
        final int libNameIndex = hooked.getParameterTypes().length - 1;
        Api101Runtime.requireModule().hook(hooked).intercept(chain -> {
            Object[] args = chain.getArgs().toArray(new Object[0]);
            String libName = null;
            if (args.length > libNameIndex && args[libNameIndex] instanceof String) {
                libName = (String) args[libNameIndex];
            }
            if (libName != null) {
                String path;
                synchronized (LOCK) {
                    path = LOADED_PATHS.get(libName);
                }
                if (path != null) {
                    if (path.startsWith("system:")) {
                        // 已通过 loadLibrary 成功加载过，直接返回避免重复查找失败
                        return null;
                    }
                    try {
                        System.load(path);
                        return null;
                    } catch (UnsatisfiedLinkError e) {
                        // 可能是「已加载」导致的重复 load，Android 对已加载 so 通常是成功/忽略；
                        // 若仍失败则放行原调用
                        LogUtil.log(TAG + "hook 内 System.load(" + path + ") 失败，回退原调用: " + e.getMessage());
                    }
                }
            }
            try {
                return chain.proceed(args);
            } catch (Throwable original) {
                // ClassLoader 找不到 so：尝试从宿主 APK 现拉
                if (libName != null && original.getClass().getName().contains("UnsatisfiedLinkError")) {
                    Context ctx = HookMain.toast_content;
                    if (loadLibrary(ctx, libName)) {
                        String path;
                        synchronized (LOCK) {
                            path = LOADED_PATHS.get(libName);
                        }
                        if (path != null && !path.startsWith("system:")) {
                            System.load(path);
                            return null;
                        }
                        // system: 路径表示 loadLibrary 本身已成功
                        return null;
                    }
                }
                throw original;
            }
        });
    }

    private static File resolveExtractDir(Context context) {
        try {
            Context ctx = context != null ? context : HookMain.toast_content;
            if (ctx != null) {
                File dir = new File(ctx.getCodeCacheDir(), "camswap-native");
                if (dir.exists() || dir.mkdirs()) {
                    return dir;
                }
            }
        } catch (Throwable t) {
            LogUtil.log(TAG + "codeCacheDir 不可用: " + t);
        }
        return null;
    }

    private static ApplicationInfo resolveHostInfo(Context context) {
        try {
            Context ctx = context != null ? context.getApplicationContext() : null;
            if (ctx == null) {
                ctx = HookMain.toast_content;
            }
            PackageManager pm = null;
            if (ctx != null) {
                pm = ctx.getPackageManager();
            }
            if (pm == null) {
                try {
                    Class<?> at = Class.forName("android.app.ActivityThread");
                    Object app = at.getMethod("currentApplication").invoke(null);
                    if (app instanceof Context) {
                        pm = ((Context) app).getPackageManager();
                    }
                } catch (Throwable ignored) {
                }
            }
            if (pm == null) {
                return null;
            }
            return pm.getApplicationInfo(IpcContract.HOST_PACKAGE_NAME, 0);
        } catch (Throwable t) {
            LogUtil.log(TAG + "resolveHostInfo 失败: " + t);
            return null;
        }
    }

    private static String preferredAbi() {
        try {
            String[] abis = android.os.Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0 && abis[0] != null) {
                return abis[0];
            }
        } catch (Throwable ignored) {
        }
        return "arm64-v8a";
    }

    private static boolean extractZipEntry(String apkPath, String entryName, File outFile) {
        ZipFile zip = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            zip = new ZipFile(apkPath);
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                // 遍历兜底：匹配 */lib<name>.so
                String leaf = outFile.getName(); // libfoo.so
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.getName() != null && e.getName().endsWith("/" + leaf)) {
                        entry = e;
                        break;
                    }
                }
            }
            if (entry == null) {
                return false;
            }
            File tmp = new File(outFile.getAbsolutePath() + ".tmp");
            in = zip.getInputStream(entry);
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            out = null;
            if (outFile.exists() && !outFile.delete()) {
                LogUtil.log(TAG + "无法覆盖旧 so: " + outFile);
            }
            if (!tmp.renameTo(outFile)) {
                // rename 失败则流式拷贝
                try (InputStream in2 = new java.io.FileInputStream(tmp);
                     FileOutputStream out2 = new FileOutputStream(outFile)) {
                    byte[] b = new byte[8192];
                    int k;
                    while ((k = in2.read(b)) > 0) {
                        out2.write(b, 0, k);
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            outFile.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            outFile.setExecutable(true, false);
            return outFile.isFile() && outFile.length() > 0;
        } catch (Throwable t) {
            LogUtil.log(TAG + "extractZipEntry(" + entryName + ") 失败: " + t);
            return false;
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (zip != null) {
                    zip.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
