#!/bin/bash
# LED悬浮屏 - ADB 权限一键设置脚本
# 用法: chmod +x adb_grant.sh && ./adb_grant.sh

ADB_IP="192.168.2.204"
ADB_PORT="5555"
PACKAGE="com.lmq8267.ledclient"

echo "========================================"
echo "  LED悬浮屏 - ADB 权限一键设置"
echo "========================================"
echo ""

# 检查 adb 是否可用
if ! command -v adb &>/dev/null; then
    echo "[!] 错误: adb 未找到，请先安装 Android SDK 平台工具"
    echo "    下载: https://developer.android.com/studio/releases/platform-tools"
    exit 1
fi

echo "[1/4] 正在连接电视 ${ADB_IP}:${ADB_PORT} ..."
adb connect "${ADB_IP}:${ADB_PORT}" 2>/dev/null
echo "      连接成功"
echo ""

echo "[2/4] 设置悬浮窗权限..."
adb shell appops set "${PACKAGE}" SYSTEM_ALERT_WINDOW allow
echo "      OK"
echo ""

echo "[3/4] 设置后台运行权限..."
adb shell appops set "${PACKAGE}" RUN_IN_BACKGROUND allow
echo "      OK"
echo ""

echo "[4/4] 设置任意后台运行权限..."
adb shell appops set "${PACKAGE}" RUN_ANY_IN_BACKGROUND allow
echo "      OK"
echo ""

echo "========================================"
echo "  ✅ 全部权限设置完成！"
echo "  请在电视上打开 LED悬浮屏 应用"
echo "========================================"
