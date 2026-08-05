// IUsbCaptureService.aidl
package io.github.zensu357.camswap;

import android.view.Surface;

/**
 * 宿主进程（CamSwap 应用）向被 Hook 的目标进程暴露的 USB 采集卡（UVC）服务接口。
 *
 * 画面传递采用 Surface 零拷贝方案：目标进程把本地 GLVideoRenderer 创建的输入 Surface
 * 通过 Binder 传给宿主，宿主把 UVC 渲染输出直接挂到该 Surface 上，中途不做任何像素拷贝。
 */
interface IUsbCaptureService {

    /**
     * 注册目标进程的渲染 Surface（默认槽位 0）。
     *
     * @param surface 目标进程创建的 Surface（GLVideoRenderer 的输入 Surface）
     * @param width   期望宽度，<=0 表示由服务按配置决定
     * @param height  期望高度，<=0 表示由服务按配置决定
     * @return 注册成功返回 true
     */
    boolean registerTargetSurface(in Surface surface, int width, int height);

    /** 注销调用方进程注册的全部 Surface。 */
    void unregisterTargetSurface();

    /** UVC 设备当前是否已连接并成功开流。 */
    boolean isUvcConnected();

    // ---- 扩展接口：支持单进程多路 Surface（Camera2 预览 + ImageReader 同时存在） ----

    /**
     * 按槽位注册 Surface，用于一个目标进程同时挂载多路输出
     * （如 c2_preview / c2_preview_1 / c2_reader / c2_reader_1）。
     *
     * @param slotId 调用方自定义的槽位号，同一进程内需唯一；重复注册会覆盖旧 Surface
     */
    boolean registerTargetSurfaceSlot(in Surface surface, int width, int height, int slotId);

    /**
     * 带客户端存活令牌的注册（推荐）。
     *
     * clientToken 由目标进程创建并持有整个进程生命周期，宿主对它 linkToDeath；
     * 目标进程一旦被杀，宿主立即清理它注册的全部 Surface。
     *
     * 不能用 /proc/<pid> 判断目标进程是否存活——Android 9 起 /proc 以 hidepid=2 挂载，
     * 应用看不到其它应用的 pid 目录，那种探测恒为"已死亡"。
     */
    boolean registerTargetSurfaceWithToken(in Surface surface, int width, int height, int slotId,
            IBinder clientToken);

    /** 注销调用方进程指定槽位的 Surface。 */
    void unregisterTargetSurfaceSlot(int slotId);

    /** 当前实际开流的 UVC 设备名；未连接时返回空字符串。 */
    String getUvcDeviceName();

    /** 当前实际开流分辨率与帧率，返回 {width, height, fps}；未连接时返回 {0, 0, 0}。 */
    int[] getUvcPreviewSize();

    /** 主动触发一次重连（用于目标进程发现长时间无帧时自救）。 */
    void requestReconnect();

    /**
     * 当前服务状态，取值见 UsbCaptureService.STATE_*：
     * 0=空闲 1=等待设备 2=等待授权 3=开流中 4=已断开等待重连 5=错误
     */
    int getUvcState();
}
