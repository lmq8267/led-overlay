#!/bin/bash
# LED悬浮屏 - ADB 权限一键设置脚本（问答式）
# 用法: chmod +x adb_grant.sh && ./adb_grant.sh

set -e

PACKAGE="com.lmq8267.ledclient"

echo "╔══════════════════════════════════════════╗"
echo "║     LED悬浮屏 - ADB 权限一键设置          ║"
echo "╚══════════════════════════════════════════╝"
echo ""

# ── 检查 adb ──
if ! command -v adb &>/dev/null; then
    echo "[!] 未找到 adb，请先安装 Android SDK 平台工具"
    echo "    下载: https://developer.android.com/studio/releases/platform-tools"
    exit 1
fi
echo "[OK] adb 已找到"
echo ""

# ── 输入 IP ──
read -p "请输入电视 IP 地址 (例如 192.168.2.204): " ADB_IP
if [ -z "$ADB_IP" ]; then
    echo "[!] IP 地址不能为空"
    exit 1
fi

# ── 输入端口（默认 5555）──
read -p "请输入端口号 (默认 5555，直接回车): " ADB_PORT_INPUT
ADB_PORT="${ADB_PORT_INPUT:-5555}"

echo ""
echo "============================================"
echo "  目标设备: ${ADB_IP}:${ADB_PORT}"
echo "  包名: ${PACKAGE}"
echo "============================================"
echo ""

# ── 连接 ──
echo "[1/5] 正在连接 ${ADB_IP}:${ADB_PORT} ..."
adb connect "${ADB_IP}:${ADB_PORT}" 2>/dev/null
echo ""

# ── 检查授权 ──
echo "[2/5] 检查授权状态..."
if ! adb -s "${ADB_IP}:${ADB_PORT}" get-state &>/dev/null; then
    echo "[!] adb 未授权或设备离线"
    echo "    请在电视上点击"允许 USB 调试"授权，然后按回车重试..."
    read -r
    adb connect "${ADB_IP}:${ADB_PORT}" 2>/dev/null
    if ! adb -s "${ADB_IP}:${ADB_PORT}" get-state &>/dev/null; then
        echo "[!] 授权失败，无法继续"
        exit 1
    fi
fi
echo "    [OK] 已授权"
echo ""

# ── 确认 ──
echo "╔══════════════════════════════════════════╗"
echo "║  即将执行以下操作：                       ║"
echo "║  1. SYSTEM_ALERT_WINDOW  → 允许悬浮窗    ║"
echo "║  2. RUN_IN_BACKGROUND    → 允许后台运行  ║"
echo "║  3. RUN_ANY_IN_BACKGROUND→ 允许任意后台  ║"
echo "╚══════════════════════════════════════════╝"
echo ""
read -p "是否继续？(Y/n): " CONFIRM
if [ "$CONFIRM" = "n" ] || [ "$CONFIRM" = "N" ]; then
    echo "已取消"
    exit 0
fi
echo ""

# ── 设置权限 ──
echo "[3/5] 设置悬浮窗权限..."
adb -s "${ADB_IP}:${ADB_PORT}" shell appops set "${PACKAGE}" SYSTEM_ALERT_WINDOW allow
echo "    [OK] SYSTEM_ALERT_WINDOW = allow"
echo ""

echo "[4/5] 设置后台运行权限..."
adb -s "${ADB_IP}:${ADB_PORT}" shell appops set "${PACKAGE}" RUN_IN_BACKGROUND allow
echo "    [OK] RUN_IN_BACKGROUND = allow"
echo ""

echo "[5/5] 设置任意后台运行权限..."
adb -s "${ADB_IP}:${ADB_PORT}" shell appops set "${PACKAGE}" RUN_ANY_IN_BACKGROUND allow
echo "    [OK] RUN_ANY_IN_BACKGROUND = allow"
echo ""

# ── 验证 ──
echo "  验证权限..."
adb -s "${ADB_IP}:${ADB_PORT}" shell appops get "${PACKAGE}" 2>/dev/null | grep -E "SYSTEM_ALERT_WINDOW|RUN_IN_BACKGROUND|RUN_ANY_IN_BACKGROUND"
echo ""

echo "╔══════════════════════════════════════════╗"
echo "║   ✅ 全部权限设置完成！                   ║"
echo "║   请在电视上打开 LED悬浮屏 应用           ║"
echo "╚══════════════════════════════════════════╝"
