@echo off
REM ============================================================
REM  CamSwap 诊断日志抓取脚本 (Windows)
REM  用法：双击运行，或在 cmd 里执行本 bat
REM  产物：桌面上的 camswap_log_时间戳.txt ，发给开发者分析
REM ============================================================
chcp 65001 >nul
setlocal EnableExtensions

REM ---- 检查 adb ----
where adb >nul 2>&1
if errorlevel 1 (
  echo [错误] 找不到 adb。请先安装 Android platform-tools 并加入 PATH。
  echo 下载: https://developer.android.com/tools/releases/platform-tools
  pause
  exit /b 1
)

adb start-server >nul 2>&1
adb devices
echo.
echo 请确认上面列表里有 device（不是 unauthorized / offline）。
echo 若是 unauthorized：手机上点「允许 USB 调试」。
echo.

set TS=%date:~0,4%%date:~5,2%%date:~8,2%_%time:~0,2%%time:~3,2%%time:~6,2%
set TS=%TS: =0%
set OUT=%USERPROFILE%\Desktop\camswap_log_%TS%.txt

echo [1/5] 清空旧 logcat 缓冲...
adb logcat -c

echo [2/5] 导出当前配置（若可读）...
> "%OUT%" echo ===== CamSwap DIAG %date% %time% =====
>>"%OUT%" echo.
>>"%OUT%" echo ----- adb devices -----
adb devices >> "%OUT%" 2>&1
>>"%OUT%" echo.
>>"%OUT%" echo ----- 已安装 CamSwap -----
adb shell pm path io.github.zensu357.camswap >> "%OUT%" 2>&1
>>"%OUT%" echo.
>>"%OUT%" echo ----- 配置文件 cs_config.json（可能因权限失败，忽略） -----
adb shell "run-as io.github.zensu357.camswap cat /data/data/io.github.zensu357.camswap/files/cs_config.json 2>/dev/null || cat /sdcard/DCIM/Camera1/cs_config.json 2>/dev/null || echo NO_CONFIG" >> "%OUT%" 2>&1
>>"%OUT%" echo.
>>"%OUT%" echo ----- dumpsys package CamSwap -----
adb shell dumpsys package io.github.zensu357.camswap | findstr /i "versionName enabled" >> "%OUT%" 2>&1
>>"%OUT%" echo.

echo [3/5] 请现在操作手机：
echo       1^) 打开 CamSwap 看首页状态
echo       2^) 打开微信直播 / 目标 App 摄像头
echo       3^) 等 15~30 秒（黑屏也继续等）
echo.
echo 按任意键开始抓 45 秒日志...
pause >nul

echo [4/5] 抓取 45 秒 logcat（过滤 CamSwap 相关）...
>>"%OUT%" echo.
>>"%OUT%" echo ----- logcat 45s filtered -----
REM 超时后 taskkill 结束 adb logcat
start /b "" cmd /c "adb logcat -v threadtime > \"%TEMP%\camswap_raw_log.txt\" 2>&1"
timeout /t 45 /nobreak >nul
taskkill /f /im adb.exe >nul 2>&1
REM 重新拉起 adb server，方便后续命令
adb start-server >nul 2>&1

REM 过滤关键行
if exist "%TEMP%\camswap_raw_log.txt" (
  findstr /i /c:"CamSwap" /c:"【CS】" /c:"【ijk】" /c:"【pcm】" /c:"【at-hook】" /c:"【native】" /c:"【net】" /c:"【usb】" /c:"【root】" /c:"ExoPlayer" /c:"Ijk" /c:"UVC" /c:"stream" /c:"MediaPlayer" /c:"Surface" /c:"AndroidRuntime" /c:"FATAL" /c:"tencent" /c:"wechat" "%TEMP%\camswap_raw_log.txt" >> "%OUT%" 2>&1
  >>"%OUT%" echo.
  >>"%OUT%" echo ----- raw log tail 200 lines -----
  powershell -NoProfile -Command "Get-Content -Path $env:TEMP\camswap_raw_log.txt -Tail 200" >> "%OUT%" 2>&1
) else (
  echo [警告] 未生成原始 log 文件 >> "%OUT%"
)

echo [5/5] 完成。
echo.
echo 日志已保存到:
echo   %OUT%
echo.
echo 请把这个 txt 文件发给开发者分析。
echo.
explorer /select,"%OUT%"
pause
endlocal
