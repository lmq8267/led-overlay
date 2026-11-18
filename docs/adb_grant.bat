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

:: ── 确认 ──
echo ╔══════════════════════════════════════════╗
echo ║  即将执行以下操作：                       ║
echo ║  1. SYSTEM_ALERT_WINDOW  → 允许悬浮窗    ║
echo ║  2. RUN_ANY_IN_BACKGROUND→ 允许后台运行  ║
echo ║  3. 通知使用权            → 强停后复活    ║
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
adb -s %ADB_IP%:%ADB_PORT% shell appops set com.lmq8267.ledclient RUN_ANY_IN_BACKGROUND allow
echo    [OK] RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND = allow
echo.

echo [5/6] 授予通知使用权（强停后的复活锚点）...
adb -s %ADB_IP%:%ADB_PORT% shell cmd notification allow_listener com.lmq8267.ledclient/com.lmq8267.ledclient.LedNotifProbe
if !ERRORLEVEL! neq 0 (
    echo     当前固件不支持 cmd notification，改用 secure settings 兜底
    set OLD_LISTENERS=
    for /f "delims=" %%i in ('adb -s %ADB_IP%:%ADB_PORT% shell settings get secure enabled_notification_listeners 2^>nul') do set OLD_LISTENERS=%%i
    set OLD_LISTENERS=!OLD_LISTENERS:=!
    if "!OLD_LISTENERS!"=="null" set OLD_LISTENERS=
    if "!OLD_LISTENERS!"=="" (
        set NEW_LISTENERS=com.lmq8267.ledclient/.LedNotifProbe
    ) else (
        echo !OLD_LISTENERS! | findstr /c:"LedNotifProbe" >nul
        if !ERRORLEVEL! equ 0 (
            set NEW_LISTENERS=!OLD_LISTENERS!
        ) else (
            set NEW_LISTENERS=!OLD_LISTENERS!:com.lmq8267.ledclient/.LedNotifProbe
        )
    )
    adb -s %ADB_IP%:%ADB_PORT% shell settings put secure enabled_notification_listeners "!NEW_LISTENERS!"
)
echo    [OK] 通知监听已设置
echo.

:: ── 验证 ──
echo [6/6] 验证权限...
adb -s %ADB_IP%:%ADB_PORT% shell appops get com.lmq8267.ledclient | findstr "SYSTEM_ALERT_WINDOW RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND"
adb -s %ADB_IP%:%ADB_PORT% shell settings get secure enabled_notification_listeners | findstr ledclient
echo.

echo ╔══════════════════════════════════════════╗
echo ║   全部设置完成！                         ║
echo ║   请在电视上打开 LED悬浮屏 应用并点启动  ║
echo ║   之后按电源键/切后台验证是否自动恢复    ║
echo ╚══════════════════════════════════════════╝
pause
