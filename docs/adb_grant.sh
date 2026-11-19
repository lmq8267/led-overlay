#!/bin/bash
# LED悬浮屏 - ADB 权限一键设置脚本（问答式）
# 用法: chmod +x adb_grant.sh && ./adb_grant.sh

set -e

PACKAGE="com.lmq8267.ledclient"
NOTIF_SERVICE="${PACKAGE}/.LedNotifProbe"

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
case "$ADB_IP" in
    *[!0-9A-Za-z.:_-]*)
        echo "[!] IP 地址包含非法字符"
        exit 1
        ;;
esac

# ── 输入端口（默认 5555）──
read -p "请输入端口号 (默认 5555，直接回车): " ADB_PORT_INPUT
ADB_PORT="${ADB_PORT_INPUT:-5555}"
case "$ADB_PORT" in
    *[!0-9]*|"")
        echo "[!] 端口必须是数字"
        exit 1
        ;;
esac

echo ""
echo "============================================"
echo "  目标设备: ${ADB_IP}:${ADB_PORT}"
echo "  包名: ${PACKAGE}"
echo "============================================"
echo ""

# ── 连接 ──
echo "[1/6] 正在连接 ${ADB_IP}:${ADB_PORT} ..."
adb connect "${ADB_IP}:${ADB_PORT}" 2>/dev/null
echo ""

# ── 检查授权 ──
echo "[2/6] 检查授权状态..."
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

# 应用没装的话，后面的 appops / 通知授权全部会“成功但无效”，先挡住
if ! adb -s "${ADB_IP}:${ADB_PORT}" shell pm path "${PACKAGE}" 2>/dev/null | grep -q "package:"; then
    echo "[!] 设备上没有找到 ${PACKAGE}，请先安装 APK"
    exit 1
fi
echo "    [OK] 应用已安装"
echo ""

# ── 确认 ──
echo "╔══════════════════════════════════════════╗"
echo "║  即将执行以下操作：                       ║"
echo "║  1. SYSTEM_ALERT_WINDOW  → 允许悬浮窗    ║"
echo "║  2. RUN_ANY_IN_BACKGROUND→ 允许后台运行  ║"
echo "║  3. 通知权限 / 电池优化   → 减少被清理    ║"
echo "║  4. 通知使用权            → 强停后复活    ║"
echo "╚══════════════════════════════════════════╝"
echo ""
read -p "是否继续？(Y/n): " CONFIRM
if [ "$CONFIRM" = "n" ] || [ "$CONFIRM" = "N" ]; then
    echo "已取消"
    exit 0
fi
echo ""

# ── 设置权限 ──
echo "[3/6] 设置悬浮窗权限..."
if adb -s "${ADB_IP}:${ADB_PORT}" shell appops set "${PACKAGE}" SYSTEM_ALERT_WINDOW allow 2>/dev/null; then
    echo "    [OK] SYSTEM_ALERT_WINDOW = allow"
else
    echo "    [!] 设置失败，请在应用内点「悬浮窗权限」跳系统设置手动允许"
fi
echo ""

echo "[4/6] 设置后台运行、通知权限与电池优化..."
adb -s "${ADB_IP}:${ADB_PORT}" shell appops set "${PACKAGE}" RUN_IN_BACKGROUND allow || true
adb -s "${ADB_IP}:${ADB_PORT}" shell appops set "${PACKAGE}" RUN_ANY_IN_BACKGROUND allow || true
echo "    [OK] RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND = allow"
# Android 13+ 的通知权限：低版本没有这个权限，pm grant 会报
# "not a changeable permission type"，忽略即可，不影响其他步骤
adb -s "${ADB_IP}:${ADB_PORT}" shell pm grant "${PACKAGE}" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
echo "    [OK] POST_NOTIFICATIONS（Android 13+）"
# 电池优化白名单：电视通常没有 Doze，这条无害；手机/平板上能减少休眠冻结
adb -s "${ADB_IP}:${ADB_PORT}" shell dumpsys deviceidle whitelist +"${PACKAGE}" >/dev/null 2>&1 || true
echo "    [OK] 电池优化白名单"
echo ""

echo "[5/6] 授予通知使用权（强停后响应最快的复活锚点）..."
# 实测强停后约 150ms 系统就会重绑这个监听并把进程拉回来。
# 它不是唯一路径：同步框架自愈（GuardSync）不需要 adb，会在服务启动后自动注册；
# 作业/闹钟兜底同样不需要授权。这里仍然建议授权，因为它的空窗最短。
if adb -s "${ADB_IP}:${ADB_PORT}" shell cmd notification allow_listener "${NOTIF_SERVICE}" 2>/dev/null; then
    echo "    [OK] 通知监听 = ${NOTIF_SERVICE}"
else
    # 老固件没有 cmd notification 子命令时，退回直接写 Secure 设置。
    OLD_LISTENERS="$(adb -s "${ADB_IP}:${ADB_PORT}" shell settings get secure enabled_notification_listeners 2>/dev/null | tr -d '\r')"
    if [ "${OLD_LISTENERS}" = "null" ] || [ -z "${OLD_LISTENERS}" ]; then
        NEW_LISTENERS="${NOTIF_SERVICE}"
    elif echo "${OLD_LISTENERS}" | grep -Fq "${NOTIF_SERVICE}"; then
        NEW_LISTENERS="${OLD_LISTENERS}"
    else
        NEW_LISTENERS="${OLD_LISTENERS}:${NOTIF_SERVICE}"
    fi
    if adb -s "${ADB_IP}:${ADB_PORT}" shell settings put secure enabled_notification_listeners "${NEW_LISTENERS}" 2>/dev/null; then
        echo "    [OK] 通知监听 = ${NOTIF_SERVICE}（secure settings 兜底）"
    else
        echo "    [!] 写入失败，请在应用内查看「关于」页，按提示手动授权"
    fi
fi
echo ""

# ── 验证 ──
echo "[6/6] 验证权限..."
echo "--- appops ---"
adb -s "${ADB_IP}:${ADB_PORT}" shell appops get "${PACKAGE}" 2>/dev/null | grep -E "SYSTEM_ALERT_WINDOW|RUN_IN_BACKGROUND|RUN_ANY_IN_BACKGROUND" || echo "    （没有匹配到，可能固件不支持这些 appops）"
echo "--- 通知使用权 ---"
adb -s "${ADB_IP}:${ADB_PORT}" shell settings get secure enabled_notification_listeners 2>/dev/null | grep -F "${PACKAGE}" || echo "    （没有匹配到，通知监听未授权）"
echo "--- 作业 / 闹钟 / 同步账户（应用启动过一次后才会出现）---"
adb -s "${ADB_IP}:${ADB_PORT}" shell dumpsys jobscheduler 2>/dev/null | grep -i "${PACKAGE}" || true
adb -s "${ADB_IP}:${ADB_PORT}" shell dumpsys alarm 2>/dev/null | grep -i "${PACKAGE}" || true
adb -s "${ADB_IP}:${ADB_PORT}" shell dumpsys account 2>/dev/null | grep -i "${PACKAGE}" || true
echo ""

echo "╔══════════════════════════════════════════╗"
echo "║   全部设置完成！                         ║"
echo "║   请在电视上打开 LED悬浮屏 应用并点启动  ║"
echo "║   之后按电源键/切后台验证是否自动恢复    ║"
echo "╚══════════════════════════════════════════╝"
