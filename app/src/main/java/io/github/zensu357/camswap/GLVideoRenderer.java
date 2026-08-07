package io.github.zensu357.camswap;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import io.github.zensu357.camswap.utils.LogUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.graphics.Bitmap;

/**
 * OpenGL ES 旋转渲染器。
 * 在 MediaPlayer 输出和目标 Surface 之间插入 GL 旋转层，
 * 实现 GPU 加速的实时画面旋转。
 *
 * 使用流程：
 * 1. new GLVideoRenderer(targetSurface, tag)
 * 2. mediaPlayer.setSurface(renderer.getInputSurface())
 * 3. renderer.setRotation(90) // 实时调整
 * 4. renderer.release() // 释放资源
 */
public class GLVideoRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "GLVideoRenderer";

    // EGL
    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;

    // GL
    private int mProgram;
    private int mTextureId;
    private int maPositionHandle;
    private int maTextureHandle;
    private int muSTMatrixHandle;
    private int muRotMatrixHandle;

    // Input/Output
    private SurfaceTexture mInputSurfaceTexture;
    private Surface mInputSurface;
    private Surface mTargetSurface; // keep reference for validity checks

    // Matrices
    private final float[] mSTMatrix = new float[16];
    private final float[] mRotMatrix = new float[16];

    // State
    private volatile int mRotationDegrees = 0;
    /** 水平镜像（绕 Y 轴 scale -1）。流模式补偿前置摄像头预览的二次镜像。 */
    private volatile boolean mMirrorHorizontal = false;
    private volatile boolean mReleased = false;
    private boolean mInitialized = false;
    private volatile int mSurfaceWidth = 0;
    private volatile int mSurfaceHeight = 0;
    /** 已渲染帧数，用于诊断「只有一帧」 */
    private volatile long mDrawnFrameCount = 0;

    // Thread
    private HandlerThread mGLThread;
    private Handler mGLHandler;

    // Tag for logging
    private final String mTag;

    // Geometry buffers
    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;

    // Reusable capture buffer (avoid per-frame allocation)
    private ByteBuffer mCaptureBuffer;
    private int mCaptureBufferSize;

    // Shader sources, vertices, and tex coords shared via GLHelper

    /**
     * 创建 GL 旋转渲染器。
     *
     * @param targetSurface 渲染目标 Surface（预览或 ImageReader 的 Surface）
     * @param tag           日志标识
     */
    public GLVideoRenderer(Surface targetSurface, String tag) {
        mTag = tag;
        mTargetSurface = targetSurface;
        Matrix.setIdentityM(mRotMatrix, 0);
        Matrix.setIdentityM(mSTMatrix, 0);

        mGLThread = new HandlerThread("GLRenderer-" + tag);
        mGLThread.start();
        mGLHandler = new Handler(mGLThread.getLooper());

        CountDownLatch latch = new CountDownLatch(1);
        mGLHandler.post(() -> {
            try {
                initEGL(targetSurface);
                initGL();
                mInitialized = true;
                LogUtil.log("【CS】【GL】" + mTag + " 初始化成功");
            } catch (Exception e) {
                LogUtil.log("【CS】【GL】" + mTag + " 初始化失败: " + e);
                mInitialized = false;
            }
            latch.countDown();
        });

        try {
            if (!latch.await(3000, TimeUnit.MILLISECONDS)) {
                LogUtil.log("【CS】【GL】" + mTag + " 初始化超时");
            }
        } catch (InterruptedException e) {
            LogUtil.log("【CS】【GL】" + mTag + " 初始化被中断");
        }
    }

    public boolean isInitialized() {
        return mInitialized && !mReleased;
    }

    /**
     * 获取输入 Surface，供 MediaPlayer.setSurface() 使用。
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    /**
     * 设置输入 SurfaceTexture 的默认缓冲区尺寸。
     * <p>
     * 当生产者是本进程的 MediaPlayer 时，MediaPlayer 会自行按视频尺寸设置缓冲区，
     * 无需调用本方法；但当输入 Surface 被跨进程交给宿主的 UVC 渲染器时，
     * 远端 {@code eglCreateWindowSurface} 会读取该默认尺寸——若不设置则为 1x1，
     * 画面会退化成单像素。USB 采集卡模式下必须在把 Surface 交给宿主之前调用。
     */
    public void setInputBufferSize(int width, int height) {
        if (width <= 0 || height <= 0 || mReleased) {
            return;
        }
        SurfaceTexture texture = mInputSurfaceTexture;
        if (texture == null) {
            return;
        }
        try {
            texture.setDefaultBufferSize(width, height);
            LogUtil.log("【CS】【GL】" + mTag + " 输入缓冲区尺寸设为 " + width + "x" + height);
        } catch (Exception e) {
            LogUtil.log("【CS】【GL】" + mTag + " 设置输入缓冲区尺寸失败: " + e);
        }
    }

    public int getSurfaceWidth() {
        return mSurfaceWidth;
    }

    public int getSurfaceHeight() {
        return mSurfaceHeight;
    }

    /**
     * 设置旋转角度（0/90/180/270），实时生效。
     */
    public void setRotation(int degrees) {
        mRotationDegrees = ((degrees % 360) + 360) % 360;
    }

    public int getRotation() {
        return mRotationDegrees;
    }

    /**
     * 水平镜像开关。微信/多数 App 前置预览会再镜像一次，
     * 对 RTMP/OBS 画面需要在 GL 层先翻一次，抵消二次镜像。
     */
    public void setMirrorHorizontal(boolean mirror) {
        mMirrorHorizontal = mirror;
    }

    public boolean isMirrorHorizontal() {
        return mMirrorHorizontal;
    }

    public long getDrawnFrameCount() {
        return mDrawnFrameCount;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (mReleased || !mInitialized || mGLHandler == null)
            return;
        // 必须 post 到 GL 线程：Ijk/解码器在自己的线程产帧，
        // 若在回调线程直接 updateTexImage 会偶发只出一帧后卡住。
        mGLHandler.post(this::drawFrame);
    }

    private void drawFrame() {
        if (mReleased || !mInitialized)
            return;
        // Bail out if the target surface has been destroyed to avoid native crash
        if (mTargetSurface != null && !mTargetSurface.isValid()) {
            LogUtil.log("【CS】【GL】" + mTag + " target surface 已失效，停止渲染");
            mReleased = true;
            return;
        }
        try {
            renderToBackBuffer();
            if (!EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface)) {
                int err = EGL14.eglGetError();
                LogUtil.log("【CS】【GL】" + mTag + " eglSwapBuffers 失败, err=" + err);
                if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW) {
                    mReleased = true;
                }
            } else {
                long n = ++mDrawnFrameCount;
                if (n == 1 || n == 30 || n == 150) {
                    LogUtil.log("【CS】【GL】" + mTag + " 已渲染 " + n + " 帧"
                            + " mirror=" + mMirrorHorizontal
                            + " rot=" + mRotationDegrees);
                }
            }
        } catch (Exception e) {
            LogUtil.log("【CS】【GL】" + mTag + " drawFrame 异常: " + e);
        }
    }

    /**
     * 渲染一帧到后缓冲（不调用 eglSwapBuffers）。
     * drawFrame() 和 captureFrameRaw() 共用此方法。
     */
    private void renderToBackBuffer() {
        if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
            return;
        }

        mInputSurfaceTexture.updateTexImage();
        mInputSurfaceTexture.getTransformMatrix(mSTMatrix);

        // 旋转 + 可选水平镜像（先旋转再 scaleX=-1）
        Matrix.setIdentityM(mRotMatrix, 0);
        if (mRotationDegrees != 0) {
            Matrix.setRotateM(mRotMatrix, 0, -mRotationDegrees, 0, 0, 1.0f);
        }
        if (mMirrorHorizontal) {
            // 水平翻转：绕中心 scale(-1, 1)
            float[] mirror = new float[16];
            Matrix.setIdentityM(mirror, 0);
            mirror[0] = -1.0f;
            float[] combined = new float[16];
            Matrix.multiplyMM(combined, 0, mirror, 0, mRotMatrix, 0);
            System.arraycopy(combined, 0, mRotMatrix, 0, 16);
        }

        // Query surface dimensions for viewport
        int[] width = new int[1];
        int[] height = new int[1];
        EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_WIDTH, width, 0);
        EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_HEIGHT, height, 0);
        if (width[0] > 0) {
            mSurfaceWidth = width[0];
        }
        if (height[0] > 0) {
            mSurfaceHeight = height[0];
        }

        // Set viewport to the EGL surface's actual dimensions
        if (width[0] > 0 && height[0] > 0) {
            GLES20.glViewport(0, 0, width[0], height[0]);
        }

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(mProgram);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);

        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);
        GLES20.glUniformMatrix4fv(muRotMatrixHandle, 1, false, mRotMatrix, 0);

        mVertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);

        mTexCoordBuffer.position(0);
        GLES20.glEnableVertexAttribArray(maTextureHandle);
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /**
     * 截取当前渲染帧为 Bitmap（使用渲染器当前旋转角度）。
     */
    public Bitmap captureFrame(int width, int height) {
        return captureFrameWithRotation(width, height, -1);
    }

    /**
     * 截取当前帧并应用指定旋转角度（不影响预览 Surface）。
     * 用于 WhatsApp YUV 帧生成：预览渲染器旋转为 0°（本机自拍正确），
     * 但 YUV 截帧需要应用 video_rotation_offset 才能让对方看到正确方向。
     *
     * @param rotationDegrees 要应用的旋转角度，-1 表示使用渲染器当前旋转
     */
    public Bitmap captureFrameWithRotation(int width, int height, int rotationDegrees) {
        if (!isInitialized() || mReleased)
            return null;
        final Bitmap[] result = { null };
        CountDownLatch latch = new CountDownLatch(1);
        mGLHandler.post(() -> {
            int savedRotation = mRotationDegrees;
            try {
                if (rotationDegrees >= 0) {
                    mRotationDegrees = ((rotationDegrees % 360) + 360) % 360;
                }

                // 渲染到后缓冲（不 swap，不影响预览显示）
                renderToBackBuffer();

                // 立即恢复旋转
                mRotationDegrees = savedRotation;

                int bufSize = width * height * 4;
                if (mCaptureBuffer == null || mCaptureBufferSize != bufSize) {
                    mCaptureBuffer = ByteBuffer.allocateDirect(bufSize);
                    mCaptureBuffer.order(ByteOrder.nativeOrder());
                    mCaptureBufferSize = bufSize;
                }
                mCaptureBuffer.clear();
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mCaptureBuffer);
                mCaptureBuffer.rewind();

                Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(mCaptureBuffer);

                // glReadPixels 输出的图像是上下颠倒的, 翻转
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.postScale(1, -1);
                result[0] = Bitmap.createBitmap(bmp, 0, 0, width, height, matrix, true);

                bmp.recycle();
                // 不 swap — 不影响预览 Surface 的显示内容
            } catch (Exception e) {
                mRotationDegrees = savedRotation;
                LogUtil.log("【CS】【GL】captureFrame 失败: " + e);
            }
            latch.countDown();
        });
        try {
            latch.await(2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return result[0];
    }

    private void initEGL(Surface targetSurface) {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("eglGetDisplay failed");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            throw new RuntimeException("eglInitialize failed");
        }

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
            throw new RuntimeException("eglChooseConfig failed");
        }
        if (numConfigs[0] == 0) {
            throw new RuntimeException("No matching EGL config");
        }

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (mEGLContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("eglCreateContext failed");
        }

        int[] surfaceAttribs = { EGL14.EGL_NONE };
        mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], targetSurface, surfaceAttribs, 0);
        if (mEGLSurface == EGL14.EGL_NO_SURFACE) {
            int eglError = EGL14.eglGetError();
            throw new RuntimeException("eglCreateWindowSurface failed with error: " + eglError);
        }

        if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }

        int[] width = new int[1];
        int[] height = new int[1];
        EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_WIDTH, width, 0);
        EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_HEIGHT, height, 0);
        mSurfaceWidth = width[0];
        mSurfaceHeight = height[0];
        LogUtil.log("【CS】【GL】EGL Surface dimensions initialized: " + width[0] + "x" + height[0]);

        // 如果 EGL Surface 尺寸太小（例如 SurfaceHolder buffer 尚未分配），视为初始化失败
        if (width[0] <= 1 && height[0] <= 1) {
            throw new RuntimeException(
                    "EGL Surface too small (" + width[0] + "x" + height[0] + "), skipping GL renderer");
        }
    }

    private void initGL() {
        int vertexShader = GLHelper.loadShader(GLES20.GL_VERTEX_SHADER, GLHelper.VERTEX_SHADER);
        int fragmentShader = GLHelper.loadShader(GLES20.GL_FRAGMENT_SHADER, GLHelper.FRAGMENT_SHADER);
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            String error = GLES20.glGetProgramInfoLog(mProgram);
            GLES20.glDeleteProgram(mProgram);
            throw new RuntimeException("Program link failed: " + error);
        }

        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
        muRotMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uRotMatrix");

        // Create external OES texture
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Create input SurfaceTexture bound to the external texture.
        // 关键：把 frame-available 回调绑到本 GL 线程 Handler。
        // 无 Handler 时回调落在任意 binder 线程，Ijk 高频产帧时容易丢后续回调 → 只显示一帧。
        mInputSurfaceTexture = new SurfaceTexture(mTextureId);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP
                && mGLHandler != null) {
            mInputSurfaceTexture.setOnFrameAvailableListener(this, mGLHandler);
        } else {
            mInputSurfaceTexture.setOnFrameAvailableListener(this);
        }
        mInputSurface = new Surface(mInputSurfaceTexture);

        mVertexBuffer = GLHelper.createFloatBuffer(GLHelper.VERTICES);
        mTexCoordBuffer = GLHelper.createFloatBuffer(GLHelper.TEX_COORDS);
    }

    /**
     * 释放所有 GL/EGL 资源。调用后该渲染器不可再使用。
     */
    public void release() {
        if (mReleased)
            return;
        mReleased = true;
        CountDownLatch releaseLatch = new CountDownLatch(1);
        if (mGLHandler != null) {
            mGLHandler.post(() -> {
                try {
                    releaseInternal();
                } finally {
                    releaseLatch.countDown();
                }
            });
        } else {
            releaseLatch.countDown();
        }

        try {
            releaseLatch.await(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        if (mGLThread != null) {
            mGLThread.quitSafely();
            try {
                mGLThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        mGLHandler = null;
        mGLThread = null;
    }

    private void releaseInternal() {
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mInputSurfaceTexture != null) {
            mInputSurfaceTexture.release();
            mInputSurfaceTexture = null;
        }
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        if (mTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[] { mTextureId }, 0);
            mTextureId = 0;
        }
        if (mEGLSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
            mEGLSurface = EGL14.EGL_NO_SURFACE;
        }
        if (mEGLContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            mEGLContext = EGL14.EGL_NO_CONTEXT;
        }
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglTerminate(mEGLDisplay);
            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }

    // ---- Static helpers for managing multiple renderers ----

    /**
     * 安全创建渲染器，失败时返回 null 而非抛异常。
     */
    public static GLVideoRenderer createSafely(Surface targetSurface, String tag) {
        if (targetSurface == null || !targetSurface.isValid()) {
            LogUtil.log("【CS】【GL】" + tag + " 目标 Surface 无效，跳过创建");
            return null;
        }
        try {
            GLVideoRenderer renderer = new GLVideoRenderer(targetSurface, tag);
            if (renderer.isInitialized()) {
                return renderer;
            } else {
                renderer.release();
                LogUtil.log("【CS】【GL】" + tag + " 初始化失败，回退到直接播放");
                return null;
            }
        } catch (Exception e) {
            LogUtil.log("【CS】【GL】" + tag + " 创建异常: " + e);
            return null;
        }
    }

    /**
     * 安全释放渲染器。
     */
    public static void releaseSafely(GLVideoRenderer renderer) {
        if (renderer != null) {
            try {
                renderer.release();
            } catch (Exception e) {
                LogUtil.log("【CS】【GL】释放渲染器异常: " + e);
            }
        }
    }
}
