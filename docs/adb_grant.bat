@echo off
setlocal EnableDelayedExpansion
chcp 65001 >nul
title LED悬浮屏 - ADB 一键授权
color 0f

echo ╔══════════════════════════════════════════╗
echo ║     LED悬浮屏 - ADB 权限一键设置          ║
echo ╚══════════════════════════════════════════╝
echo.

:: ── 检查 adb ──
where adb >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [!] 未找到 adb，请先安装 Android SDK 平台工具
    echo     下载: https://developer.android.com/studio/releases/platform-tools
    pause
    exit /b 1
)
echo [OK] adb 已找到
echo.

:: ── 输入 IP ──
set /p ADB_IP=请输入电视 IP 地址 (例如 192.168.2.204)：
if "%ADB_IP%"=="" (
    echo [!] IP 地址不能为空
    pause
    exit /b 1
)

:: ── 输入端口（默认 5555）──
set ADB_PORT=5555
set /p ADB_PORT_INPUT=请输入端口号 (默认 5555，直接回车)：
if not "%ADB_PORT_INPUT%"=="" set ADB_PORT=%ADB_PORT_INPUT%

echo.
echo ============================================
echo  目标设备: %ADB_IP%:%ADB_PORT%
echo  包名: com.lmq8267.ledclient
echo ============================================
echo.

:: ── 连接 ──
echo [1/6] 正在连接 %ADB_IP%:%ADB_PORT% ...
adb connect %ADB_IP%:%ADB_PORT%
if %ERRORLEVEL% neq 0 (
    echo [!] 连接失败，请检查 IP 和端口是否正确
    pause
    exit /b 1
)
echo.
echo [2/6] 检查授权状态...

adb -s %ADB_IP%:%ADB_PORT% get-state >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [!] adb 未授权或设备离线
    echo     请在电视上点击"允许 USB 调试"授权
    echo     然后按任意键重试...
    pause
    adb connect %ADB_IP%:%ADB_PORT%
    adb -s %ADB_IP%:%ADB_PORT% get-state >nul 2>&1
    if %ERRORLEVEL% neq 0 (
        echo [!] 授权失败，无法继续
        pause
        exit /b 1
    )
)
echo     [OK] 已授权
echo.

:: ── 已开启则直接退出 ──
echo 检查无障碍服务是否已开启...
set ACCESSIBILITY_SERVICE=com.lmq8267.ledclient/.LedAccessibilityService
set CURRENT_SERVICES=
set SERVICE_CRASHED=0
for /f "delims=" %%i in ('adb -s %ADB_IP%:%ADB_PORT% shell settings get secure enabled_accessibility_services 2^>nul') do set CURRENT_SERVICES=%%i
set CURRENT_SERVICES=!CURRENT_SERVICES:=!
adb -s %ADB_IP%:%ADB_PORT% shell dumpsys accessibility 2^>nul | findstr /c:"Crashed services" | findstr /c:"com.lmq8267.ledclient" >nul
if !ERRORLEVEL! equ 0 set SERVICE_CRASHED=1
echo !CURRENT_SERVICES! | findstr /c:"!ACCESSIBILITY_SERVICE!" >nul
if !ERRORLEVEL! equ 0 (
    if "!SERVICE_CRASHED!"=="1" (
        echo     已开启，但系统标记该无障碍服务已崩溃，将刷新本应用绑定
        echo.
    ) else (
    echo.
    echo ╔══════════════════════════════════════════╗
    echo ║   [OK] 无障碍服务已经开启，无需重复开启   ║
    echo ╚══════════════════════════════════════════╝
    pause
    exit /b 0
    )
) else (
    echo     未开启，将继续授权
    echo.
)

:: ── 确认 ──
echo ╔══════════════════════════════════════════╗
echo ║  即将执行以下操作：                       ║
echo ║  1. SYSTEM_ALERT_WINDOW  → 允许悬浮窗    ║
echo ║  2. RUN_IN_BACKGROUND    → 允许后台运行  ║
echo ║  3. RUN_ANY_IN_BACKGROUND→ 允许任意后台  ║
echo ║  4. 无障碍服务            → 开启后台驻留  ║
echo ╚══════════════════════════════════════════╝
echo.
set /p CONFIRM=是否继续？(Y/n)：
if /i "%CONFIRM%"=="n" (
    echo 已取消
    pause
    exit /b 0
)

echo.
echo [3/6] 设置悬浮窗权限...
adb -s %ADB_IP%:%ADB_PORT% shell appops set com.lmq8267.ledclient SYSTEM_ALERT_WINDOW allow
echo    [OK] SYSTEM_ALERT_WINDOW = allow
echo.

echo [4/6] 设置后台运行权限...
adb -s %ADB_IP%:%ADB_PORT% shell appops set com.lmq8267.ledclient RUN_IN_BACKGROUND allow
echo    [OK] RUN_IN_BACKGROUND = allow
echo.

echo [5/6] 设置任意后台运行权限...
adb -s %ADB_IP%:%ADB_PORT% shell appops set com.lmq8267.ledclient RUN_ANY_IN_BACKGROUND allow
echo    [OK] RUN_ANY_IN_BACKGROUND = allow
echo.

echo [6/6] 开启无障碍服务（后台保活）...
for /f "delims=" %%i in ('adb -s %ADB_IP%:%ADB_PORT% shell settings get secure enabled_accessibility_services 2^>nul') do set OLD_SERVICES=%%i
set OLD_SERVICES=!OLD_SERVICES:=!
if "!SERVICE_CRASHED!"=="1" (
    set REMOVE_SERVICES=!OLD_SERVICES:%ACCESSIBILITY_SERVICE%:=!
    set REMOVE_SERVICES=!REMOVE_SERVICES::%ACCESSIBILITY_SERVICE%=!
    if "!REMOVE_SERVICES!"=="!ACCESSIBILITY_SERVICE!" set REMOVE_SERVICES=
    adb -s %ADB_IP%:%ADB_PORT% shell settings put secure enabled_accessibility_services "!REMOVE_SERVICES!"
    ping -n 2 127.0.0.1 >nul
    set OLD_SERVICES=!REMOVE_SERVICES!
)
if "!OLD_SERVICES!"=="null" set OLD_SERVICES=
if "!OLD_SERVICES!"=="" (
    set NEW_SERVICES=!ACCESSIBILITY_SERVICE!
) else (
    echo !OLD_SERVICES! | findstr /c:"!ACCESSIBILITY_SERVICE!" >nul
    if !ERRORLEVEL! equ 0 (
        set NEW_SERVICES=!OLD_SERVICES!
    ) else (
        set NEW_SERVICES=!OLD_SERVICES!:!ACCESSIBILITY_SERVICE!
    )
)
adb -s %ADB_IP%:%ADB_PORT% shell settings put secure enabled_accessibility_services "!NEW_SERVICES!"
adb -s %ADB_IP%:%ADB_PORT% shell settings put secure accessibility_enabled 1
echo    [OK] 无障碍服务已开启
echo.

:: ── 验证 ──
echo  验证权限...
adb -s %ADB_IP%:%ADB_PORT% shell appops get com.lmq8267.ledclient | findstr "SYSTEM_ALERT_WINDOW RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND"
adb -s %ADB_IP%:%ADB_PORT% shell settings get secure enabled_accessibility_services | findstr ledclient
echo.

echo ╔══════════════════════════════════════════╗
echo ║   ✅ 全部设置完成！                      ║
echo ║   请在电视上打开 LED悬浮屏 应用          ║
echo ╚══════════════════════════════════════════╝
pause
