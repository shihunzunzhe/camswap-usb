# ndk-build 全局配置(配合 Android.mk 使用)
# HAL 部署到 vendor，需自带 C++ 运行时，用 c++_static 避免依赖设备上的 libc++_shared。
APP_ABI      := arm64-v8a
APP_PLATFORM := android-26
APP_STL      := c++_static
APP_CPPFLAGS := -std=c++17 -fexceptions
APP_OPTIM    := release
