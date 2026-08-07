@echo off
REM 简化版：持续抓日志，Ctrl+C 结束，文件在桌面
chcp 65001 >nul
where adb >nul 2>&1
if errorlevel 1 (
  echo 找不到 adb，请安装 platform-tools 并加入 PATH
  pause
  exit /b 1
)

set TS=%date:~0,4%%date:~5,2%%date:~8,2%_%time:~0,2%%time:~3,2%%time:~6,2%
set TS=%TS: =0%
set OUT=%USERPROFILE%\Desktop\camswap_log_%TS%.txt

echo 输出文件: %OUT%
echo 操作手机复现问题后按 Ctrl+C 停止。
echo.
adb logcat -c
adb logcat -v threadtime | findstr /i "CamSwap 【CS】 【ijk】 【pcm】 【at-hook】 【native】 【net】 【usb】 【root】 FATAL AndroidRuntime tencent" > "%OUT%"
echo.
echo 已保存: %OUT%
pause
