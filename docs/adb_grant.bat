@echo off
chcp 65001 >nul
title LED悬浮屏 - ADB 一键授权
echo ========================================
echo   LED悬浮屏 - ADB 权限一键设置
echo ========================================
echo.

set ADB_IP=192.168.2.204
set ADB_PORT=5555

echo [1/4] 正在连接电视 %ADB_IP%:%ADB_PORT% ...
adb connect %ADB_IP%:%ADB_PORT% 2>nul
if %ERRORLEVEL% neq 0 (
    echo [!] adb 未找到，请确保 adb 已添加到 PATH
    pause
    exit /b 1
)
echo    连接成功
echo.

echo [2/4] 设置悬浮窗权限...
adb shell appops set com.lmq8267.ledclient SYSTEM_ALERT_WINDOW allow
echo    OK
echo.

echo [3/4] 设置后台运行权限...
adb shell appops set com.lmq8267.ledclient RUN_IN_BACKGROUND allow
echo    OK
echo.

echo [4/4] 设置任意后台运行权限...
adb shell appops set com.lmq8267.ledclient RUN_ANY_IN_BACKGROUND allow
echo    OK
echo.

echo ========================================
echo   ✅ 全部权限设置完成！
echo   请在电视上打开 LED悬浮屏 应用
echo ========================================
pause
