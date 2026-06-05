#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <syslog.h>
#include <time.h>
#include <unistd.h>

#include "app_page.h"

#ifndef APP_VERSION
#define APP_VERSION "unknown"
#endif

#define TASK_TEXT_CAP 4096
#define WS_TEXT_CAP 32768
#define STATE_LINE_CAP 65536
#define STATE_BROADCAST_CAP 1048576

typedef struct Task {
    char id[32];
    char text[TASK_TEXT_CAP];
    char background[16];
    char foreground[16];
    int speed;
    int loops;
    char status[32];
    char detail[1024];
    time_t updated_at;
    struct Task *next;
} Task;

typedef struct Client {
    int fd;
    int id;
    int online;
    char key[80];
    char name[128];
    char addr[128];
    Task *tasks;
    struct Client *next;
} Client;

typedef struct Admin {
    int fd;
    struct Admin *next;
} Admin;

typedef struct LogEntry {
    char time[32];
    char text[512];
    struct LogEntry *next;
} LogEntry;

/* ── 会话管理 ─────────────────────────────────────────── */
typedef struct Session {
    char token[64];
    time_t expires;              /* 过期时间戳 */
    struct Session *next;
} Session;

typedef struct LoginAttempt {
    char ip[128];
    int failures;
    time_t locked_until;
    struct LoginAttempt *next;
} LoginAttempt;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static Client *clients = NULL;
static Admin *admins = NULL;
static LogEntry *logs = NULL;
static int next_client_id = 1;
static int next_task_id = 1;
static int log_count = 0;
static const char *STATE_DIR = "/var/lib/led-overlay";
static const char *STATE_FILE = "/var/lib/led-overlay/state.db";
static const char *STATE_TMP = "/var/lib/led-overlay/state.db.tmp";

/* ── 认证相关 ─────────────────────────────────────────── */
static Session *sessions = NULL;
static LoginAttempt *login_attempts = NULL;
static pthread_mutex_t auth_lock = PTHREAD_MUTEX_INITIALIZER;
static char password_hash[64] = {0};   /* hex 编码的 SHA1 密码 */
static char home_path[256] = "/";      /* 管理主页路径，默认 / */

/* ── 登录页 HTML（日间/黑夜自适应） ────────────────────── */
static const char LOGIN_CARD_TOP[] =
"<!DOCTYPE html>"
"<html lang=\"zh-CN\">"
"<head><meta charset=\"utf-8\">"
"<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">"
"<title>LED Overlay</title>"
"<style>"
"*{margin:0;padding:0;box-sizing:border-box}"
":root{color-scheme:light dark}"
"body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
"display:flex;align-items:center;justify-content:center;min-height:100vh;padding:20px;background:#f1f5f9}"
".lc{position:relative;background:#fff;box-shadow:0 4px 24px rgba(0,0,0,0.08)}"
"h1{color:#0f172a}.lp{color:#64748b}"
	"li{color:#0f172a;background:#f8fafc;border-color:#cbd5e1}"
	"li:focus-within{border-color:#3b82f6}"
	"lb{background:#3b82f6;color:#fff}.le{color:#ef4444}"
	".rm{display:flex;align-items:center;gap:8px;margin:-6px 0 16px;color:#64748b;font-size:14px;text-align:left}"
	".rm input{width:16px;height:16px;accent-color:#3b82f6}"
"@media(prefers-color-scheme:light){"
"body{background:#f1f5f9}"
".lc{background:#fff;box-shadow:0 4px 24px rgba(0,0,0,0.08)}"
"h1{color:#0f172a}"
".lp{color:#64748b}"
"li{color:#0f172a;background:#f8fafc;border-color:#cbd5e1}"
"li:focus{border-color:#3b82f6}"
"lb{background:#3b82f6;color:#fff}"
".le{color:#ef4444}"
"}"
"@media(prefers-color-scheme:dark){"
":root:not([data-theme='light']) body{background:#0f172a}"
":root:not([data-theme='light']) .lc{background:#1e293b;box-shadow:0 4px 24px rgba(0,0,0,0.3)}"
":root:not([data-theme='light']) h1{color:#f1f5f9}"
":root:not([data-theme='light']) .lp{color:#94a3b8}"
	":root:not([data-theme='light']) li{color:#f1f5f9;background:#334155;border-color:#475569}"
	":root:not([data-theme='light']) li:focus-within{border-color:#3b82f6}"
	":root:not([data-theme='light']) lb{background:#3b82f6;color:#fff}"
	":root:not([data-theme='light']) .le{color:#f87171}"
	":root:not([data-theme='light']) .rm{color:#94a3b8}"
"}"
":root[data-theme='dark'] body{background:#0f172a}"
":root[data-theme='dark'] .lc{background:#1e293b;box-shadow:0 4px 24px rgba(0,0,0,0.3)}"
":root[data-theme='dark'] h1{color:#f1f5f9}"
":root[data-theme='dark'] .lp{color:#94a3b8}"
":root[data-theme='dark'] li{color:#f1f5f9;background:#334155;border-color:#475569}"
":root[data-theme='dark'] .le{color:#f87171}"
":root[data-theme='light'] body{background:#f1f5f9}"
":root[data-theme='light'] .lc{background:#fff;box-shadow:0 4px 24px rgba(0,0,0,0.08)}"
":root[data-theme='light'] h1{color:#0f172a}"
":root[data-theme='light'] .lp{color:#64748b}"
":root[data-theme='light'] li{color:#0f172a;background:#f8fafc;border-color:#cbd5e1}"
":root[data-theme='light'] .le{color:#ef4444}"
".lc{width:100%%;max-width:380px;border-radius:16px;padding:40px 32px 32px;text-align:center}"
"h1{font-size:24px;margin-bottom:4px}"
".lp{font-size:14px;margin-bottom:28px}"
"li{display:block;width:100%%;padding:14px 16px;border:1.5px solid;border-radius:12px;"
"font-size:16px;outline:none;transition:border-color .2s;margin-bottom:16px}"
"li input{border:none;outline:none;background:transparent;width:100%%;font-size:16px;color:inherit}"
"lb{display:block;width:100%%;padding:14px;border:none;border-radius:12px;"
"font-size:16px;font-weight:700;cursor:pointer;transition:opacity .2s}"
"lb:hover{opacity:0.9}lb:active{opacity:0.8}"
".le{font-size:14px;margin-top:12px;display:none}"
".le.show{display:block}"
".tb{position:absolute;top:12px;right:12px;width:34px;height:34px;border:1px solid currentColor;"
"border-radius:10px;background:transparent;color:inherit;display:grid;place-items:center;cursor:pointer;opacity:.7}"
".tb:hover{opacity:1}.tb svg{width:18px;height:18px}"
"</style></head><body>"
"<div class=\"lc\">"
"<button class=\"tb\" type=\"button\" onclick=\"toggleTheme()\" title=\"切换主题\">"
"<svg id=\"themeIco\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"></svg>"
"</button>"
"<h1>LED Overlay</h1>"
"<p class=\"lp\">管理面板</p>"
	"<form method=\"post\">"
	"<li><input id=\"password\" type=\"password\" name=\"password\" placeholder=\"请输入密码\" autofocus autocomplete=\"current-password\""
	" onfocus=\"this.type='text'\" onblur=\"this.type='password'\"></li>"
	"<label class=\"rm\"><input id=\"rememberPassword\" type=\"checkbox\">记住密码</label>"
	"<lb onclick=\"saveRemember();this.parentElement.submit()\">登 录</lb>"
	"</form>"
	"<script>"
	"let theme=localStorage.getItem('theme')||'auto';"
	"const pwd=document.getElementById('password'),remember=document.getElementById('rememberPassword');"
	"pwd.value=localStorage.getItem('led_login_password')||'';"
	"remember.checked=pwd.value.length>0;"
	"function saveRemember(){if(remember.checked)localStorage.setItem('led_login_password',pwd.value);else localStorage.removeItem('led_login_password');}"
	"document.querySelector('form').addEventListener('submit',saveRemember);"
	"function applyTheme(){"
"if(theme==='auto')document.documentElement.removeAttribute('data-theme');"
"else document.documentElement.setAttribute('data-theme',theme);"
"document.getElementById('themeIco').innerHTML=theme==='dark'"
"?\"<path d='M21 12.8A8.5 8.5 0 1 1 11.2 3 6.5 6.5 0 0 0 21 12.8Z'/>\""
":\"<circle cx='12' cy='12' r='4'/><path d='M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41'/>\";"
"}"
"function toggleTheme(){theme=theme==='auto'?'dark':theme==='dark'?'light':'auto';localStorage.setItem('theme',theme);applyTheme();}"
"applyTheme();"
"</script>"
;

static const char LOGIN_CARD_BOTTOM[] =
"<p class=\"le\"></p>"
"</div></body></html>";

/* ── 日志 ──────────────────────────────────────────────── */
static void print_version(void) {
    printf("LED Overlay 服务端版本：%s\n", APP_VERSION);
}

static void print_help(const char *prog) {
    printf(
        "LED Overlay 服务端\n"
        "\n"
        "用法：%s [选项]\n"
        "\n"
        "选项：\n"
        "  -a <地址>              监听地址，默认 0.0.0.0\n"
        "  -p <端口>              监听端口，默认 12366\n"
        "  --home-path <路径>     管理主页隐藏路径，默认 /\n"
        "  -P, --default-password <密码>\n"
        "                         状态文件缺失或无密码时使用的默认密码，默认 admin\n"
        "  --reset-password <密码> 重置管理密码后退出\n"
        "  -v, --version          输出版本号后退出\n"
        "  -h, --help             输出本帮助后退出\n"
        "\n"
        "版本：%s\n",
        prog, APP_VERSION);
}

static void log_line(const char *fmt, ...) {
    va_list ap;
    time_t now = time(NULL);
    char ts[32];
    char msg[1024];
    strftime(ts, sizeof(ts), "%F %T", localtime(&now));
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    fprintf(stderr, "[%s] ", ts);
    fputs(msg, stderr);
    fputc('\n', stderr);
    syslog(LOG_INFO, "%s", msg);
}

static void add_event_locked(const char *fmt, ...) {
    va_list ap;
    time_t now = time(NULL);
    LogEntry *entry = calloc(1, sizeof(LogEntry));
    char text[512];
    va_start(ap, fmt);
    vsnprintf(text, sizeof(text), fmt, ap);
    va_end(ap);
    syslog(LOG_INFO, "%s", text);
    if (!entry) {
        char ts[32];
        strftime(ts, sizeof(ts), "%F %T", localtime(&now));
        fprintf(stderr, "[%s] %s\n", ts, text);
        return;
    }
    strftime(entry->time, sizeof(entry->time), "%F %T", localtime(&now));
    snprintf(entry->text, sizeof(entry->text), "%s", text);
    fprintf(stderr, "[%s] %s\n", entry->time, entry->text);
    if (!logs) logs = entry;
    else {
        LogEntry *tail = logs;
        while (tail->next) tail = tail->next;
        tail->next = entry;
    }
    log_count++;
    while (log_count > 300 && logs) {
        LogEntry *old = logs;
        logs = logs->next;
        free(old);
        log_count--;
    }
}

/* ── 编解码 ────────────────────────────────────────────── */
static void hex_encode(FILE *f, const char *s) {
    static const char *hex = "0123456789abcdef";
    const unsigned char *p = (const unsigned char *)s;
    while (*p) {
        fputc(hex[*p >> 4], f);
        fputc(hex[*p & 15], f);
        p++;
    }
}

static int hex_val(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static void hex_decode(char *out, size_t cap, const char *hex) {
    size_t p = 0;
    while (hex[0] && hex[1] && p + 1 < cap) {
        int hi = hex_val(hex[0]);
        int lo = hex_val(hex[1]);
        if (hi < 0 || lo < 0) break;
        out[p++] = (char)((hi << 4) | lo);
        hex += 2;
    }
    out[p] = 0;
}

/* ── SHA1 ──────────────────────────────────────────────── */
static uint32_t rol(uint32_t v, int n) { return (v << n) | (v >> (32 - n)); }

static void sha1(const unsigned char *data, size_t len, unsigned char out[20]) {
    uint32_t h0 = 0x67452301, h1 = 0xefcdab89, h2 = 0x98badcfe, h3 = 0x10325476, h4 = 0xc3d2e1f0;
    size_t total = ((len + 8) / 64 + 1) * 64;
    unsigned char *msg = calloc(1, total);
    memcpy(msg, data, len);
    msg[len] = 0x80;
    uint64_t bits = (uint64_t)len * 8;
    for (int i = 0; i < 8; i++) msg[total - 1 - i] = (bits >> (8 * i)) & 0xff;
    for (size_t off = 0; off < total; off += 64) {
        uint32_t w[80];
        for (int i = 0; i < 16; i++) w[i] = (uint32_t)msg[off+i*4]<<24 | (uint32_t)msg[off+i*4+1]<<16 | (uint32_t)msg[off+i*4+2]<<8 | msg[off+i*4+3];
        for (int i = 16; i < 80; i++) w[i] = rol(w[i-3]^w[i-8]^w[i-14]^w[i-16], 1);
        uint32_t a=h0,b=h1,c=h2,d=h3,e=h4;
        for (int i = 0; i < 80; i++) {
            uint32_t f,k;
            if (i < 20) { f=(b&c)|((~b)&d); k=0x5a827999; }
            else if (i < 40) { f=b^c^d; k=0x6ed9eba1; }
            else if (i < 60) { f=(b&c)|(b&d)|(c&d); k=0x8f1bbcdc; }
            else { f=b^c^d; k=0xca62c1d6; }
            uint32_t temp=rol(a,5)+f+e+k+w[i]; e=d; d=c; c=rol(b,30); b=a; a=temp;
        }
        h0+=a; h1+=b; h2+=c; h3+=d; h4+=e;
    }
    free(msg);
    uint32_t h[5] = {h0,h1,h2,h3,h4};
    for (int i = 0; i < 5; i++) for (int j = 0; j < 4; j++) out[i*4+j] = (h[i] >> (24 - j*8)) & 0xff;
}

/* 计算 SHA1 的 hex 字符串 */
static void sha1_hex(const char *input, char *hex_out, size_t cap) {
    unsigned char hash[20];
    sha1((unsigned char *)input, strlen(input), hash);
    static const char *h = "0123456789abcdef";
    size_t p = 0;
    for (int i = 0; i < 20 && p + 2 < cap; i++) {
        hex_out[p++] = h[hash[i] >> 4];
        hex_out[p++] = h[hash[i] & 15];
    }
    hex_out[p] = 0;
}

static void base64(const unsigned char *in, int len, char *out) {
    const char *t = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    int p = 0;
    for (int i = 0; i < len; i += 3) {
        int v = in[i] << 16 | ((i + 1 < len ? in[i+1] : 0) << 8) | (i + 2 < len ? in[i+2] : 0);
        out[p++] = t[(v >> 18) & 63]; out[p++] = t[(v >> 12) & 63];
        out[p++] = i + 1 < len ? t[(v >> 6) & 63] : '=';
        out[p++] = i + 2 < len ? t[v & 63] : '=';
    }
    out[p] = 0;
}

/* ── WebSocket 收发 ───────────────────────────────────── */
static int send_all(int fd, const void *buf, size_t len) {
    const char *p = (const char *)buf;
    while (len > 0) {
        ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if (n <= 0) return 0;
        p += n;
        len -= (size_t)n;
    }
    return 1;
}

static int ws_send(int fd, const char *text) {
    size_t len = strlen(text);
    unsigned char head[10];
    int n = 0;
    head[n++] = 0x81;
    if (len < 126) head[n++] = len;
    else if (len <= 65535) { head[n++] = 126; head[n++] = len >> 8; head[n++] = len & 255; }
    else { head[n++] = 127; for (int i = 7; i >= 0; i--) head[n++] = (len >> (8*i)) & 255; }
    return send_all(fd, head, (size_t)n) && send_all(fd, text, len);
}

static int ws_read(int fd, char *out, size_t cap) {
    unsigned char h[2], mask[4];
    if (recv(fd, h, 2, MSG_WAITALL) != 2) return 0;
    int opcode = h[0] & 15;
    uint64_t len = h[1] & 127;
    if (len == 126) { unsigned char e[2]; if (recv(fd, e, 2, MSG_WAITALL) != 2) return 0; len = e[0] << 8 | e[1]; }
    else if (len == 127) { unsigned char e[8]; if (recv(fd, e, 8, MSG_WAITALL) != 8) return 0; len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | e[i]; }
    int masked = h[1] & 128;
    if (masked && recv(fd, mask, 4, MSG_WAITALL) != 4) return 0;
    size_t keep = len < cap ? (size_t)len : cap - 1;
    size_t off = 0;
    while (off < len) {
        unsigned char chunk[2048];
        size_t want = len - off;
        if (want > sizeof(chunk)) want = sizeof(chunk);
        if (recv(fd, chunk, want, MSG_WAITALL) != (ssize_t)want) return 0;
        if (masked) for (size_t i = 0; i < want; i++) chunk[i] ^= mask[(off + i) % 4];
        if (off < keep) {
            size_t copy = keep - off;
            if (copy > want) copy = want;
            memcpy(out + off, chunk, copy);
        }
        off += want;
    }
    out[keep] = 0;
    return opcode == 1;
}

static void set_socket_timeouts(int fd) {
    struct timeval tv;
    tv.tv_sec = 60;
    tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
}

static void clear_socket_read_timeout(int fd) {
    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
}

/* ── JSON 辅助 ─────────────────────────────────────────── */
static void json_value(const char *json, const char *key, char *out, size_t cap) {
    char pat[64];
    snprintf(pat, sizeof(pat), "\"%s\"", key);
    char *p = strstr((char *)json, pat);
    if (!p) { *out = 0; return; }
    p = strchr(p, ':');
    if (!p) { *out = 0; return; }
    p++;
    while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
    size_t i = 0;
    if (*p == '"') {
        p++;
        while (*p && i + 1 < cap) {
            if (*p == '"') break;
            if (*p == '\\' && p[1]) {
                p++;
                if (*p == 'n') out[i++] = '\n';
                else if (*p == 'r') out[i++] = '\r';
                else if (*p == 't') out[i++] = '\t';
                else if (*p == 'b') out[i++] = '\b';
                else if (*p == 'f') out[i++] = '\f';
                else if (*p == 'u' && p[1] && p[2] && p[3] && p[4]) {
                    int v0 = hex_val(p[1]), v1 = hex_val(p[2]), v2 = hex_val(p[3]), v3 = hex_val(p[4]);
                    if (v0 >= 0 && v1 >= 0 && v2 >= 0 && v3 >= 0) {
                        unsigned code = (unsigned)((v0 << 12) | (v1 << 8) | (v2 << 4) | v3);
                        if (code < 0x80 && i + 1 < cap) out[i++] = (char)code;
                        else if (code < 0x800 && i + 2 < cap) {
                            out[i++] = (char)(0xc0 | (code >> 6));
                            out[i++] = (char)(0x80 | (code & 0x3f));
                        } else if (i + 3 < cap) {
                            out[i++] = (char)(0xe0 | (code >> 12));
                            out[i++] = (char)(0x80 | ((code >> 6) & 0x3f));
                            out[i++] = (char)(0x80 | (code & 0x3f));
                        }
                    }
                    p += 4;
                } else out[i++] = *p;
                p++;
                continue;
            }
            out[i++] = *p++;
        }
    } else {
        while (*p && *p != ',' && *p != '}' && i + 1 < cap) out[i++] = *p++;
    }
    out[i] = 0;
}

static int json_int(const char *json, const char *key, int fallback) {
    char value[64];
    json_value(json, key, value, sizeof(value));
    return *value ? atoi(value) : fallback;
}

static int utf8_seq_len(const unsigned char *s, size_t avail) {
    if (s[0] < 0x80) return 1;
    if (avail < 2) return 0;
    if (s[0] >= 0xc2 && s[0] <= 0xdf
            && (s[1] & 0xc0) == 0x80) return 2;
    if (avail < 3) return 0;
    if (s[0] == 0xe0
            && s[1] >= 0xa0 && s[1] <= 0xbf
            && (s[2] & 0xc0) == 0x80) return 3;
    if (s[0] >= 0xe1 && s[0] <= 0xec
            && (s[1] & 0xc0) == 0x80
            && (s[2] & 0xc0) == 0x80) return 3;
    if (s[0] == 0xed
            && s[1] >= 0x80 && s[1] <= 0x9f
            && (s[2] & 0xc0) == 0x80) return 3;
    if (s[0] >= 0xee && s[0] <= 0xef
            && (s[1] & 0xc0) == 0x80
            && (s[2] & 0xc0) == 0x80) return 3;
    if (avail < 4) return 0;
    if (s[0] == 0xf0
            && s[1] >= 0x90 && s[1] <= 0xbf
            && (s[2] & 0xc0) == 0x80
            && (s[3] & 0xc0) == 0x80) return 4;
    if (s[0] >= 0xf1 && s[0] <= 0xf3
            && (s[1] & 0xc0) == 0x80
            && (s[2] & 0xc0) == 0x80
            && (s[3] & 0xc0) == 0x80) return 4;
    if (s[0] == 0xf4
            && s[1] >= 0x80 && s[1] <= 0x8f
            && (s[2] & 0xc0) == 0x80
            && (s[3] & 0xc0) == 0x80) return 4;
    return 0;
}

static void esc(char *out, size_t cap, const char *in) {
    size_t p = 0;
    for (; in && *in && p + 2 < cap; ) {
        unsigned char ch = (unsigned char)*in;
        if (*in == '"' || *in == '\\') {
            if (p + 3 >= cap) break;
            out[p++] = '\\';
            out[p++] = *in++;
        } else if (ch < 32) {
            in++;
        } else if (ch < 128) {
            out[p++] = *in++;
        } else {
            int n = utf8_seq_len((const unsigned char *)in, strlen(in));
            if (n > 0 && p + (size_t)n < cap) {
                for (int i = 0; i < n; i++) out[p++] = *in++;
            } else {
                out[p++] = '?';
                in++;
            }
        }
    }
    out[p] = 0;
}

static void html_esc(char *out, size_t cap, const char *in) {
    size_t p = 0;
    for (; in && *in && p + 1 < cap; in++) {
        const char *rep = NULL;
        if (*in == '&') rep = "&amp;";
        else if (*in == '<') rep = "&lt;";
        else if (*in == '>') rep = "&gt;";
        else if (*in == '"') rep = "&quot;";
        if (rep) {
            size_t len = strlen(rep);
            if (p + len >= cap) break;
            memcpy(out + p, rep, len);
            p += len;
        } else {
            out[p++] = *in;
        }
    }
    out[p] = 0;
}

static void append_text(char *out, size_t cap, const char *text) {
    size_t used = strlen(out);
    if (used + 1 >= cap) return;
    strncat(out, text, cap - used - 1);
}

static void copy_cstr(char *out, size_t cap, const char *in) {
    if (cap == 0) return;
    size_t i = 0;
    if (in) while (in[i] && i + 1 < cap) { out[i] = in[i]; i++; }
    out[i] = 0;
}

static void trim_ip_value(char *value) {
    char *p = value;
    while (*p == ' ' || *p == '\t') p++;
    if (p != value) memmove(value, p, strlen(p) + 1);
    char *comma = strchr(value, ',');
    if (comma) *comma = 0;
    char *end = value + strlen(value);
    while (end > value && (end[-1] == ' ' || end[-1] == '\t' || end[-1] == '\r' || end[-1] == '\n')) *--end = 0;
}

static void header_value(const char *header, const char *name, char *out, size_t cap) {
    *out = 0;
    size_t nlen = strlen(name);
    const char *line = header;
    while (line && *line) {
        const char *next = strstr(line, "\r\n");
        size_t len = next ? (size_t)(next - line) : strlen(line);
        if (len > nlen && strncasecmp(line, name, nlen) == 0 && line[nlen] == ':') {
            size_t copy = len - nlen - 1;
            if (copy >= cap) copy = cap - 1;
            memcpy(out, line + nlen + 1, copy);
            out[copy] = 0;
            trim_ip_value(out);
            return;
        }
        if (!next) break;
        line = next + 2;
    }
}

static void real_ip_from_header(const char *header, const char *peer, char *out, size_t cap) {
    const char *names[] = {
        "CF-Connecting-IP",
        "X-Real-IP",
        "X-Forwarded-For",
        "Forwarded",
        NULL
    };
    for (int i = 0; names[i]; i++) {
        char value[256];
        header_value(header, names[i], value, sizeof(value));
        if (!value[0]) continue;
        if (strcmp(names[i], "Forwarded") == 0) {
            char *p = strcasestr(value, "for=");
            if (!p) continue;
            p += 4;
            if (*p == '"') p++;
            size_t j = 0;
            while (*p && *p != ';' && *p != ',' && *p != '"' && j + 1 < sizeof(value)) value[j++] = *p++;
            value[j] = 0;
            if (value[0] == '[') {
                memmove(value, value + 1, strlen(value));
                char *end = strchr(value, ']');
                if (end) *end = 0;
            }
        }
        trim_ip_value(value);
        if (value[0]) {
            copy_cstr(out, cap, value);
            return;
        }
    }
    copy_cstr(out, cap, peer && peer[0] ? peer : "unknown");
}

/* ── 表单 POST 解析（URL 编码） ───────────────────────── */
static void form_value(const char *body, const char *name, char *out, size_t cap) {
    /* 在 body 中查找 "name=" */
    size_t nlen = strlen(name);
    const char *p = body;
    while (p) {
        p = strstr(p, name);
        if (!p) break;
        if ((p == body || p[-1] == '&') && p[nlen] == '=') {
            p += nlen + 1;
            break;
        }
        p++;
    }
    if (!p) { *out = 0; return; }
    size_t i = 0;
    while (*p && *p != '&' && i + 1 < cap) {
        if (*p == '%' && p[1] && p[2]) {
            int hi = hex_val(p[1]), lo = hex_val(p[2]);
            if (hi >= 0 && lo >= 0) out[i++] = (char)((hi << 4) | lo);
            p += 3;
        } else if (*p == '+') { out[i++] = ' '; p++; }
        else { out[i++] = *p++; }
    }
    out[i] = 0;
}

/* ── Cookie 解析 ──────────────────────────────────────── */
/* 从 HTTP 头中提取指定名称的 cookie 值 */
static void get_cookie(const char *header, const char *name, char *out, size_t cap) {
    const char *cookie_hdr = strcasestr(header, "Cookie:");
    if (!cookie_hdr) { *out = 0; return; }
    size_t nlen = strlen(name);
    const char *p = cookie_hdr + 7;
    while (p && (p = strstr(p, name)) != NULL) {
        if (p[nlen] == '=' && (p == cookie_hdr + 7 || p[-1] == ' ' || p[-1] == ';')) {
            p += nlen + 1;
            break;
        }
        p++;
    }
    if (!p) { *out = 0; return; }
    size_t i = 0;
    while (*p && *p != ';' && *p != ' ' && *p != '\r' && *p != '\n' && i + 1 < cap) {
        out[i++] = *p++;
    }
    out[i] = 0;
}

/* ── 客户端 / 任务操作 ────────────────────────────────── */
static Client *find_client(int id) {
    for (Client *c = clients; c; c = c->next) if (c->id == id) return c;
    return NULL;
}

static Client *find_client_key(const char *key) {
    for (Client *c = clients; c; c = c->next) if (strcmp(c->key, key) == 0) return c;
    return NULL;
}

static Task *find_task(Client *c, const char *id) {
    for (Task *t = c ? c->tasks : NULL; t; t = t->next) if (strcmp(t->id, id) == 0) return t;
    return NULL;
}

static int is_active_status(const char *status) {
    return strcmp(status, "sent") == 0
            || strcmp(status, "received") == 0
            || strcmp(status, "displaying") == 0;
}

static void append_task(Client *c, Task *t) {
    if (!c->tasks) { c->tasks = t; return; }
    Task *tail = c->tasks;
    while (tail->next) tail = tail->next;
    tail->next = t;
}

static int remove_task(Client *c, const char *id, int *was_active) {
    Task **pp = c ? &c->tasks : NULL;
    while (pp && *pp) {
        Task *t = *pp;
        if (strcmp(t->id, id) == 0) {
            if (was_active) *was_active = is_active_status(t->status);
            *pp = t->next;
            free(t);
            return 1;
        }
        pp = &t->next;
    }
    return 0;
}

static int remove_client_if_offline(int id) {
    Client **pp = &clients;
    while (*pp) {
        Client *c = *pp;
        if (c->id == id) {
            if (c->online) return 0;
            *pp = c->next;
            Task *t = c->tasks;
            while (t) { Task *next = t->next; free(t); t = next; }
            free(c);
            return 1;
        }
        pp = &c->next;
    }
    return 0;
}

static void set_task_status(Task *t, const char *status) {
    if (!t || !status) return;
    snprintf(t->status, sizeof(t->status), "%s", status);
    t->updated_at = time(NULL);
}

static void set_task_status_detail(Task *t, const char *status, const char *detail) {
    set_task_status(t, status);
    if (t && detail && detail[0]) snprintf(t->detail, sizeof(t->detail), "%s", detail);
}

static int is_terminal_status(const char *status) {
    return status && (strcmp(status, "completed") == 0 || strcmp(status, "failed") == 0);
}

static int ack_status_rank(const char *status) {
    if (!status) return -1;
    if (strcmp(status, "pending") == 0) return 0;
    if (strcmp(status, "sent") == 0) return 1;
    if (strcmp(status, "received") == 0) return 2;
    if (strcmp(status, "displaying") == 0) return 3;
    if (strcmp(status, "completed") == 0) return 4;
    if (strcmp(status, "failed") == 0) return 4;
    return -1;
}

static int should_apply_ack_status(const Task *t, const char *status) {
    if (!t || !status || !status[0]) return 0;
    if (strcmp(t->status, "completed") == 0) return 0;
    if (strcmp(t->status, "failed") == 0) return strcmp(status, "completed") == 0;
    int current = ack_status_rank(t->status);
    int incoming = ack_status_rank(status);
    if (incoming < 0) return 0;
    return incoming >= current;
}

static int send_task(Client *c, Task *t) {
    if (!c || !t || !c->online || c->fd < 0) return 0;
    char msg[TASK_TEXT_CAP * 2 + 512], et[TASK_TEXT_CAP * 2];
    esc(et, sizeof(et), t->text);
    snprintf(msg, sizeof(msg), "{\"type\":\"message\",\"taskId\":\"%s\",\"text\":\"%s\",\"background\":\"%s\",\"foreground\":\"%s\",\"speed\":%d,\"loops\":%d}",
             t->id, et, t->background, t->foreground, t->speed, t->loops);
    if (ws_send(c->fd, msg)) {
        set_task_status_detail(t, "sent", "服务端已下发，等待客户端接收确认");
        return 1;
    }
    set_task_status_detail(t, "failed", "服务端 WebSocket 发送失败，客户端可能已断开");
    return 0;
}

static int resend_task_payload(Client *c, Task *t, const char *detail) {
    if (!c || !t || !c->online || c->fd < 0) return 0;
    char msg[TASK_TEXT_CAP * 2 + 512], et[TASK_TEXT_CAP * 2];
    esc(et, sizeof(et), t->text);
    snprintf(msg, sizeof(msg), "{\"type\":\"message\",\"taskId\":\"%s\",\"text\":\"%s\",\"background\":\"%s\",\"foreground\":\"%s\",\"speed\":%d,\"loops\":%d}",
             t->id, et, t->background, t->foreground, t->speed, t->loops);
    if (ws_send(c->fd, msg)) {
        if (detail && detail[0]) snprintf(t->detail, sizeof(t->detail), "%s", detail);
        t->updated_at = time(NULL);
        return 1;
    }
    set_task_status_detail(t, "failed", "服务端重新下发失败，客户端可能已断开");
    return 0;
}

static void cancel_task(Client *c, const char *task_id) {
    if (!c || !c->online || c->fd < 0) return;
    char msg[128], id[64];
    esc(id, sizeof(id), task_id);
    snprintf(msg, sizeof(msg), "{\"type\":\"cancel\",\"taskId\":\"%s\"}", id);
    ws_send(c->fd, msg);
}

static void send_next_task(Client *c) {
    if (!c || !c->online) return;
    for (Task *t = c->tasks; t; t = t->next) {
        if (strcmp(t->status, "pending") == 0 && !send_task(c, t)) return;
    }
}

static void send_active_or_next_task(Client *c) {
    if (!c || !c->online) return;
    for (Task *t = c->tasks; t; t = t->next) {
        if (is_active_status(t->status)
                && !resend_task_payload(c, t, "客户端重连，服务端重新下发任务以确认状态")) return;
    }
    send_next_task(c);
}

static Task *clone_task_as_new(Task *src) {
    if (!src) return NULL;
    Task *t = calloc(1, sizeof(Task));
    snprintf(t->id, sizeof(t->id), "t%d", next_task_id++);
    snprintf(t->text, sizeof(t->text), "%s", src->text);
    snprintf(t->background, sizeof(t->background), "%s", src->background);
    snprintf(t->foreground, sizeof(t->foreground), "%s", src->foreground);
    t->speed = src->speed;
    t->loops = src->loops > 0 ? src->loops : 1;
    set_task_status_detail(t, "pending", "待发送：等待服务端下发到客户端");
    return t;
}

/* ── 状态持久化 ────────────────────────────────────────── */
static void save_state_locked(void) {
    if (mkdir(STATE_DIR, 0755) < 0 && errno != EEXIST)
        { log_line("数据库目录不可用 %s：%s", STATE_DIR, strerror(errno)); return; }
    FILE *f = fopen(STATE_TMP, "w");
    if (!f) { log_line("数据写入失败 %s：%s", STATE_TMP, strerror(errno)); return; }
    fprintf(f, "NEXT\t%d\t%d\n", next_client_id, next_task_id);
    for (Client *c = clients; c; c = c->next) {
        fprintf(f, "CLIENT\t%d\t", c->id);
        hex_encode(f, c->key); fputc('\t', f);
        hex_encode(f, c->name); fputc('\t', f);
        hex_encode(f, c->addr); fputc('\n', f);
        for (Task *t = c->tasks; t; t = t->next) {
            fprintf(f, "TASK\t%d\t", c->id);
            hex_encode(f, t->id); fputc('\t', f);
            hex_encode(f, t->text); fputc('\t', f);
            hex_encode(f, t->background); fputc('\t', f);
            hex_encode(f, t->foreground);
            fprintf(f, "\t%d\t%d\t", t->speed, t->loops);
            hex_encode(f, t->status);
            fputc('\t', f);
            hex_encode(f, t->detail);
            fprintf(f, "\t%ld\n", (long)t->updated_at);
        }
    }
    for (LogEntry *l = logs; l; l = l->next) {
        fprintf(f, "LOG\t");
        hex_encode(f, l->time); fputc('\t', f);
        hex_encode(f, l->text); fputc('\n', f);
    }
    /* 保存密码 hash */
    if (password_hash[0]) fprintf(f, "PASS\t%s\n", password_hash);
    fclose(f);
    if (rename(STATE_TMP, STATE_FILE) < 0)
        log_line("数据文件替换失败：%s", strerror(errno));
}

static void load_state(void) {
    FILE *f = fopen(STATE_FILE, "r");
    if (!f) return;
    char line[STATE_LINE_CAP];
    int max_client = 0, max_task = 0;
    while (fgets(line, sizeof(line), f)) {
        line[strcspn(line, "\r\n")] = 0;
        char *fields[12] = {0};
        char *save = NULL;
        int n = 0;
        for (char *p = strtok_r(line, "\t", &save); p && n < 12; p = strtok_r(NULL, "\t", &save)) fields[n++] = p;
        if (n >= 3 && strcmp(fields[0], "NEXT") == 0) {
            next_client_id = atoi(fields[1]); next_task_id = atoi(fields[2]);
        } else if (n >= 5 && strcmp(fields[0], "CLIENT") == 0) {
            Client *c = calloc(1, sizeof(Client));
            c->fd = -1; c->online = 0; c->id = atoi(fields[1]);
            hex_decode(c->key, sizeof(c->key), fields[2]);
            hex_decode(c->name, sizeof(c->name), fields[3]);
            hex_decode(c->addr, sizeof(c->addr), fields[4]);
            c->next = clients; clients = c;
            if (c->id > max_client) max_client = c->id;
        } else if (n >= 9 && strcmp(fields[0], "TASK") == 0) {
            Client *c = find_client(atoi(fields[1]));
            if (!c) continue;
            Task *t = calloc(1, sizeof(Task));
            hex_decode(t->id, sizeof(t->id), fields[2]);
            hex_decode(t->text, sizeof(t->text), fields[3]);
            hex_decode(t->background, sizeof(t->background), fields[4]);
            hex_decode(t->foreground, sizeof(t->foreground), fields[5]);
            t->speed = atoi(fields[6]); t->loops = atoi(fields[7]);
            if (t->loops <= 0) t->loops = 1;
            hex_decode(t->status, sizeof(t->status), fields[8]);
            if (n >= 11) {
                hex_decode(t->detail, sizeof(t->detail), fields[9]);
                t->updated_at = (time_t)atol(fields[10]);
            } else {
                snprintf(t->detail, sizeof(t->detail), "%s", "");
                t->updated_at = n >= 10 ? (time_t)atol(fields[9]) : time(NULL);
            }
            if (strcmp(t->status, "sent") == 0) set_task_status(t, "pending");
            if (t->updated_at <= 0) t->updated_at = time(NULL);
            append_task(c, t);
            if (t->id[0] == 't') { int id = atoi(t->id + 1); if (id > max_task) max_task = id; }
        } else if (n >= 3 && strcmp(fields[0], "LOG") == 0) {
            LogEntry *entry = calloc(1, sizeof(LogEntry));
            hex_decode(entry->time, sizeof(entry->time), fields[1]);
            hex_decode(entry->text, sizeof(entry->text), fields[2]);
            if (!logs) logs = entry;
            else { LogEntry *tail = logs; while (tail->next) tail = tail->next; tail->next = entry; }
            log_count++;
        } else if (n >= 2 && strcmp(fields[0], "PASS") == 0) {
            snprintf(password_hash, sizeof(password_hash), "%s", fields[1]);
        }
    }
    fclose(f);
    if (next_client_id <= max_client) next_client_id = max_client + 1;
    if (next_task_id <= max_task) next_task_id = max_task + 1;
    log_line("已从 %s 加载数据", STATE_FILE);
}

/* ── WebSocket 握手 ───────────────────────────────────── */
static int websocket_handshake(int fd, const char *header) {
    char *p = strcasestr((char *)header, "Sec-WebSocket-Key:");
    if (!p) return 0;
    p += 18;
    while (*p == ' ') p++;
    char key[128] = {0};
    int i = 0;
    while (*p && *p != '\r' && *p != '\n' && i < 120) key[i++] = *p++;
    char combo[256];
    unsigned char digest[20];
    char accept[64];
    snprintf(combo, sizeof(combo), "%s258EAFA5-E914-47DA-95CA-C5AB0DC85B11", key);
    sha1((unsigned char *)combo, strlen(combo), digest);
    base64(digest, 20, accept);
    char resp[256];
    snprintf(resp, sizeof(resp), "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n", accept);
    return send(fd, resp, strlen(resp), MSG_NOSIGNAL) > 0;
}

/* ── 广播状态（前向声明） ──────────────────────────────── */
static void broadcast_state(void);

/* ── Admin WebSocket 处理 ──────────────────────────────── */
static void handle_admin(int fd) {
    Admin admin = {.fd = fd};
    pthread_mutex_lock(&lock);
    admin.next = admins; admins = &admin;
    pthread_mutex_unlock(&lock);
    broadcast_state();
    char buf[WS_TEXT_CAP];
    while (ws_read(fd, buf, sizeof(buf))) {
        char type[32]; json_value(buf, "type", type, sizeof(type));
        int cid = json_int(buf, "clientId", 0);
        pthread_mutex_lock(&lock);
        Client *c = find_client(cid);
        if (c && strcmp(type, "send") == 0) {
            Task *t = calloc(1, sizeof(Task));
            snprintf(t->id, sizeof(t->id), "t%d", next_task_id++);
            json_value(buf, "text", t->text, sizeof(t->text));
            json_value(buf, "background", t->background, sizeof(t->background)); if (!*t->background) strcpy(t->background, "#000000");
            json_value(buf, "foreground", t->foreground, sizeof(t->foreground)); if (!*t->foreground) strcpy(t->foreground, "#ff0000");
            t->speed = json_int(buf, "speed", 90); t->loops = json_int(buf, "loops", json_int(buf, "duration", 1)); if (t->loops <= 0) t->loops = 1; set_task_status_detail(t, "pending", "待发送：等待服务端下发到客户端");
            append_task(c, t);
            send_next_task(c);
            save_state_locked();
        } else if (c && strcmp(type, "resend") == 0) {
            char tid[32]; json_value(buf, "taskId", tid, sizeof(tid));
            Task *t = find_task(c, tid);
            if (t) {
                if (is_active_status(t->status)) {
                    resend_task_payload(c, t, "管理员手动重新下发当前任务以确认状态");
                } else {
                    Task *copy = clone_task_as_new(t);
                    if (copy) {
                        append_task(c, copy);
                        send_next_task(c);
                    }
                }
            }
            save_state_locked();
        } else if (c && strcmp(type, "delete") == 0) {
            char tid[32]; int was_active = 0;
            json_value(buf, "taskId", tid, sizeof(tid));
            if (remove_task(c, tid, &was_active)) {
                if (was_active) cancel_task(c, tid);
                send_next_task(c);
                save_state_locked();
            }
        } else if (strcmp(type, "deleteClient") == 0) {
            if (remove_client_if_offline(cid)) {
                add_event_locked("已删除离线客户端 #%d", cid);
                save_state_locked();
            }
        }
        pthread_mutex_unlock(&lock);
        broadcast_state();
    }
    pthread_mutex_lock(&lock);
    Admin **pp = &admins;
    while (*pp) { if (*pp == &admin) { *pp = admin.next; break; } pp = &(*pp)->next; }
    pthread_mutex_unlock(&lock);
}

/* ── 广播状态 ─────────────────────────────────────────── */
static void broadcast_state(void) {
    char *buf = calloc(1, STATE_BROADCAST_CAP);
    append_text(buf, STATE_BROADCAST_CAP, "{\"type\":\"state\",\"clients\":[");
    pthread_mutex_lock(&lock);
    for (Client *c = clients; c; c = c->next) {
        char en[256], ea[256], ek[160], item[800];
        esc(en, sizeof(en), c->name); esc(ea, sizeof(ea), c->addr); esc(ek, sizeof(ek), c->key);
        snprintf(item, sizeof(item), "%s{\"id\":%d,\"key\":\"%s\",\"name\":\"%s\",\"addr\":\"%s\",\"online\":%s,\"tasks\":[",
                 c == clients ? "" : ",", c->id, ek, en, ea, c->online ? "true" : "false");
        append_text(buf, STATE_BROADCAST_CAP, item);
        for (Task *t = c->tasks; t; t = t->next) {
            char et[TASK_TEXT_CAP * 2], ed[2048], ti[TASK_TEXT_CAP * 2 + 2300];
            esc(et, sizeof(et), t->text);
            esc(ed, sizeof(ed), t->detail);
            snprintf(ti, sizeof(ti), "%s{\"id\":\"%s\",\"text\":\"%s\",\"status\":\"%s\",\"detail\":\"%s\",\"updatedAt\":%ld}",
                     t == c->tasks ? "" : ",", t->id, et, t->status, ed, (long)t->updated_at);
            append_text(buf, STATE_BROADCAST_CAP, ti);
        }
        append_text(buf, STATE_BROADCAST_CAP, "]}");
    }
    append_text(buf, STATE_BROADCAST_CAP, "],\"logs\":[");
    int first_log = 1;
    for (LogEntry *l = logs; l; l = l->next) {
        char et[1024], ts[80], item[1200];
        esc(et, sizeof(et), l->text);
        esc(ts, sizeof(ts), l->time);
        snprintf(item, sizeof(item), "%s{\"time\":\"%s\",\"text\":\"%s\"}", first_log ? "" : ",", ts, et);
        append_text(buf, STATE_BROADCAST_CAP, item); first_log = 0;
    }
    append_text(buf, STATE_BROADCAST_CAP, "]}");
    Admin **pp = &admins;
    while (*pp) {
        Admin *a = *pp;
        if (ws_send(a->fd, buf)) {
            pp = &a->next;
        } else {
            close(a->fd);
            *pp = a->next;
        }
    }
    pthread_mutex_unlock(&lock);
    free(buf);
}

/* ── 会话管理 ─────────────────────────────────────────── */
/* 生成随机会话 token */
static void generate_token(char *out, size_t cap) {
    time_t now = time(NULL);
    char input[80];
    snprintf(input, sizeof(input), "%ld|%d|%d", (long)now, getpid(), rand());
    sha1_hex(input, out, cap);
}

/* 创建新会话（30 分钟有效期） */
static void create_session(char *token_out, size_t cap) {
    generate_token(token_out, cap);
    Session *s = calloc(1, sizeof(Session));
    snprintf(s->token, sizeof(s->token), "%s", token_out);
    s->expires = time(NULL) + 1800;  /* 30 分钟 */

    pthread_mutex_lock(&auth_lock);
    s->next = sessions;
    sessions = s;
    pthread_mutex_unlock(&auth_lock);
}

/* 校验会话 token，有效返回 1，失效则删除 */
static int validate_session(const char *token) {
    if (!token || !token[0]) return 0;
    pthread_mutex_lock(&auth_lock);
    time_t now = time(NULL);
    Session **pp = &sessions;
    while (*pp) {
        Session *s = *pp;
        if (strcmp(s->token, token) == 0) {
            if (s->expires > now) {
                pthread_mutex_unlock(&auth_lock);
                return 1;
            }
            /* 过期，删除 */
            *pp = s->next;
            free(s);
            pthread_mutex_unlock(&auth_lock);
            return 0;
        }
        pp = &s->next;
    }
    pthread_mutex_unlock(&auth_lock);
    return 0;
}

/* 从 HTTP 头中提取 session cookie 并校验 */
static int validate_session_from_header(const char *header) {
    char token[64] = {0};
    get_cookie(header, "led_session", token, sizeof(token));
    return validate_session(token);
}

static LoginAttempt *login_attempt_for_ip(const char *ip, int create) {
    for (LoginAttempt *a = login_attempts; a; a = a->next) {
        if (strcmp(a->ip, ip) == 0) return a;
    }
    if (!create) return NULL;
    LoginAttempt *a = calloc(1, sizeof(LoginAttempt));
    snprintf(a->ip, sizeof(a->ip), "%s", ip);
    a->next = login_attempts;
    login_attempts = a;
    return a;
}

static int login_locked_seconds(const char *ip) {
    time_t now = time(NULL);
    pthread_mutex_lock(&auth_lock);
    LoginAttempt *a = login_attempt_for_ip(ip, 0);
    int seconds = 0;
    if (a && a->locked_until > now) seconds = (int)(a->locked_until - now);
    else if (a && a->locked_until <= now) { a->locked_until = 0; a->failures = 0; }
    pthread_mutex_unlock(&auth_lock);
    return seconds;
}

static int login_failure_count(const char *ip) {
    pthread_mutex_lock(&auth_lock);
    LoginAttempt *a = login_attempt_for_ip(ip, 0);
    int failures = a ? a->failures : 0;
    pthread_mutex_unlock(&auth_lock);
    return failures;
}

static int record_login_failure(const char *ip) {
    time_t now = time(NULL);
    pthread_mutex_lock(&auth_lock);
    LoginAttempt *a = login_attempt_for_ip(ip, 1);
    if (a->locked_until <= now) a->failures++;
    if (a->failures >= 3) a->locked_until = now + 1800;
    int seconds = a->locked_until > now ? (int)(a->locked_until - now) : 0;
    pthread_mutex_unlock(&auth_lock);
    return seconds;
}

static void clear_login_failures(const char *ip) {
    pthread_mutex_lock(&auth_lock);
    LoginAttempt *a = login_attempt_for_ip(ip, 0);
    if (a) { a->failures = 0; a->locked_until = 0; }
    pthread_mutex_unlock(&auth_lock);
}

/* 校验密码 */
static int check_password(const char *input) {
    pthread_mutex_lock(&auth_lock);
    if (!password_hash[0]) {
        pthread_mutex_unlock(&auth_lock);
        return 0;
    }
    char hash[64];
    sha1_hex(input, hash, sizeof(hash));
    int ok = strcmp(hash, password_hash) == 0;
    pthread_mutex_unlock(&auth_lock);
    return ok;
}

static int read_request_body(int fd, const char *header, int header_len, char *body, size_t cap) {
    char *cl = strcasestr((char *)header, "Content-Length:");
    int body_len = cl ? atoi(cl + 15) : 0;
    if (body_len < 0) body_len = 0;
    if ((size_t)body_len >= cap) body_len = (int)cap - 1;

    int have = 0;
    char *body_start = strstr((char *)header, "\r\n\r\n");
    if (body_start) {
        body_start += 4;
        int remaining = header + header_len - body_start;
        if (remaining > 0) {
            int copy = remaining < body_len ? remaining : body_len;
            memcpy(body, body_start, copy);
            have = copy;
        }
    }
    if (have < body_len) {
        int got = recv(fd, body + have, body_len - have, MSG_WAITALL);
        if (got > 0) have += got;
    }
    body[have] = 0;
    return have;
}

/* ── HTTP 响应辅助 ────────────────────────────────────── */
static void send_http(int fd, int status, const char *status_text,
                       const char *content_type, const char *body, size_t body_len,
                       const char *extra_headers) {
    char resp[4096];
    int n = snprintf(resp, sizeof(resp),
        "HTTP/1.1 %d %s\r\n"
        "Content-Type: %s\r\n"
        "Content-Length: %zu\r\n"
        "Connection: close\r\n"
        "%s"
        "\r\n",
        status, status_text, content_type, body_len, extra_headers ? extra_headers : "");
    send(fd, resp, n, MSG_NOSIGNAL);
    if (body && body_len > 0) send(fd, body, body_len, MSG_NOSIGNAL);
}

static void send_not_found(int fd) {
    const char *body = "404 Not Found\n";
    send_http(fd, 404, "Not Found", "text/plain; charset=utf-8", body, strlen(body), NULL);
}

static int set_home_path(const char *value) {
    char path[256];
    if (!value || !value[0]) return 0;
    if (value[0] != '/') snprintf(path, sizeof(path), "/%s", value);
    else snprintf(path, sizeof(path), "%s", value);

    if (strchr(path, '?') || strchr(path, '#') || strchr(path, '&')) return 0;
    size_t len = strlen(path);
    while (len > 1 && path[len - 1] == '/') path[--len] = 0;

    const char *reserved[] = {"/admin", "/client", "/client-ack", "/change-password", "/api", "/login", NULL};
    for (int i = 0; reserved[i]; i++) {
        if (strcmp(path, reserved[i]) == 0) return 0;
    }

    snprintf(home_path, sizeof(home_path), "%s", path);
    return 1;
}

static void serve_login_page(int fd, const char *error) {
    char html[8192], safe_error[1024], bottom[1200];
    int n;
    if (error && error[0]) {
        html_esc(safe_error, sizeof(safe_error), error);
        snprintf(bottom, sizeof(bottom), "<p class=\"le show\">%s</p></div></body></html>", safe_error);
        n = snprintf(html, sizeof(html), "%s%s", LOGIN_CARD_TOP, bottom);
    } else {
        n = snprintf(html, sizeof(html), "%s%s", LOGIN_CARD_TOP, LOGIN_CARD_BOTTOM);
    }
    send_http(fd, 200, "OK", "text/html; charset=utf-8", html, n, NULL);
}

static void serve_admin_page(int fd) {
    char resp[1024];
    int n = snprintf(resp, sizeof(resp),
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: text/html; charset=utf-8\r\n"
        "Content-Length: %zu\r\n"
        "Connection: close\r\n"
        "\r\n",
        strlen(app_page_html));
    send(fd, resp, n, MSG_NOSIGNAL);
    send(fd, app_page_html, strlen(app_page_html), MSG_NOSIGNAL);
}

/* ── 客户端 WebSocket 处理 ────────────────────────────── */
static void handle_client_ws(int fd, const char *addr) {
    char buf[WS_TEXT_CAP];
    if (!ws_read(fd, buf, sizeof(buf))) return;
    char type[32], key[80], name[128];
    json_value(buf, "type", type, sizeof(type));
    json_value(buf, "clientId", key, sizeof(key));
    json_value(buf, "name", name, sizeof(name));
    if (strcmp(type, "hello") != 0 || key[0] == 0) return;
    clear_socket_read_timeout(fd);

    Client *c;
    pthread_mutex_lock(&lock);
    c = find_client_key(key);
    if (!c) {
        c = calloc(1, sizeof(Client));
        c->fd = -1;
        c->id = next_client_id++;
        snprintf(c->key, sizeof(c->key), "%s", key);
        snprintf(c->name, sizeof(c->name), "client-%d", c->id);
        c->next = clients; clients = c;
    }
    c->fd = fd;
    c->online = 1;
    snprintf(c->addr, sizeof(c->addr), "%s", addr);
    if (name[0]) snprintf(c->name, sizeof(c->name), "%s", name);
    add_event_locked("客户端已连接 #%d %s %s", c->id, c->name, c->addr);
    send_active_or_next_task(c);
    save_state_locked();
    pthread_mutex_unlock(&lock);
    broadcast_state();
    while (ws_read(fd, buf, sizeof(buf))) {
        json_value(buf, "type", type, sizeof(type));
        if (strcmp(type, "ping") == 0) continue;
        pthread_mutex_lock(&lock);
        if (strcmp(type, "ack") == 0) {
            char tid[32], status[32], detail[1024];
            json_value(buf, "taskId", tid, sizeof(tid));
            json_value(buf, "status", status, sizeof(status));
            json_value(buf, "detail", detail, sizeof(detail));
            Task *t = find_task(c, tid);
            if (should_apply_ack_status(t, status)) {
                int was_terminal = is_terminal_status(t->status);
                set_task_status_detail(t, status, detail);
                if (!was_terminal && is_terminal_status(status)) send_next_task(c);
            } else if (is_terminal_status(status)) {
                add_event_locked("客户端 ACK 未匹配任务：#%d 任务 %s 状态 %s", c->id, tid, status);
            }
            if (t) save_state_locked();
        }
        pthread_mutex_unlock(&lock);
        broadcast_state();
    }
    pthread_mutex_lock(&lock);
    if (c->fd == fd) {
        c->fd = -1;
        c->online = 0;
        for (Task *t = c->tasks; t; t = t->next) {
            if (strcmp(t->status, "sent") == 0) {
                set_task_status_detail(t, "pending", "客户端断开，等待重连后重新下发");
            } else if (strcmp(t->status, "received") == 0 || strcmp(t->status, "displaying") == 0) {
                snprintf(t->detail, sizeof(t->detail), "客户端断开，等待重连后重新下发当前任务并确认状态");
                t->updated_at = time(NULL);
            }
        }
        add_event_locked("客户端已断开 #%d %s %s", c->id, c->name, c->addr);
        save_state_locked();
    }
    pthread_mutex_unlock(&lock);
    broadcast_state();
}

/* ── 连接处理线程 ──────────────────────────────────────── */
static void *connection_thread(void *arg) {
    int fd = *(int *)arg;
    free(arg);
    char header[8192] = {0}, addr[128] = "unknown", real_ip[128] = "unknown";
    struct sockaddr_storage ss; socklen_t sl = sizeof(ss);
    if (getpeername(fd, (struct sockaddr *)&ss, &sl) == 0) {
        void *ap = ss.ss_family == AF_INET ? (void *)&((struct sockaddr_in *)&ss)->sin_addr
                                           : (void *)&((struct sockaddr_in6 *)&ss)->sin6_addr;
        inet_ntop(ss.ss_family, ap, addr, sizeof(addr));
    }

    int n = recv(fd, header, sizeof(header) - 1, 0);
    if (n <= 0) { close(fd); return NULL; }
    header[n] = 0;
    real_ip_from_header(header, addr, real_ip, sizeof(real_ip));

    /* 解析请求方法与路径 */
    char method[8] = {0}, path[512] = {0};
    sscanf(header, "%7s %511s", method, path);

    /* 检测是否为 WebSocket 升级请求 */
    int is_ws = (strcasestr(header, "Upgrade:") != NULL
              && strcasestr(header, "Connection:") != NULL
              && strcasestr(header, "Sec-WebSocket-Key:") != NULL);

    /* ── /client WebSocket（无需认证） ── */
    if (strcmp(path, "/client") == 0) {
        if (!is_ws || !websocket_handshake(fd, header)) { close(fd); return NULL; }
        handle_client_ws(fd, real_ip);
        close(fd);
        return NULL;
    }

    /* ── /client-ack HTTP 兜底 ACK（无需认证，不改变在线状态） ── */
    if (strcmp(path, "/client-ack") == 0) {
        if (strcmp(method, "POST") != 0) {
            const char *body = "{\"ok\":false,\"error\":\"仅支持 POST\"}";
            send_http(fd, 405, "Method Not Allowed", "application/json; charset=utf-8", body, strlen(body), NULL);
            close(fd);
            return NULL;
        }

        char body[4096] = {0};
        read_request_body(fd, header, n, body, sizeof(body));

        char key[80], tid[32], status[32], detail[1024];
        json_value(body, "clientId", key, sizeof(key));
        json_value(body, "taskId", tid, sizeof(tid));
        json_value(body, "status", status, sizeof(status));
        json_value(body, "detail", detail, sizeof(detail));

        int accepted = 0;
        pthread_mutex_lock(&lock);
        Client *c = find_client_key(key);
        Task *t = find_task(c, tid);
        if (should_apply_ack_status(t, status)) {
            int was_terminal = is_terminal_status(t->status);
            set_task_status_detail(t, status, detail);
            if (!was_terminal && is_terminal_status(status)) send_next_task(c);
            accepted = 1;
        } else if (t) {
            accepted = 1;
        } else if (is_terminal_status(status)) {
            add_event_locked("客户端 HTTP ACK 未匹配：客户端 %s 任务 %s 状态 %s", key, tid, status);
        }
        if (t) save_state_locked();
        pthread_mutex_unlock(&lock);
        broadcast_state();

        char resp[96];
        int rn = snprintf(resp, sizeof(resp), "{\"ok\":true,\"accepted\":%s}", accepted ? "true" : "false");
        send_http(fd, 200, "OK", "application/json; charset=utf-8", resp, rn, NULL);
        close(fd);
        return NULL;
    }

    /* ── 修改管理密码（需认证） ── */
    if (strcmp(path, "/change-password") == 0) {
        if (!validate_session_from_header(header)) {
            const char *body = "{\"ok\":false,\"error\":\"未登录\"}";
            send_http(fd, 401, "Unauthorized", "application/json; charset=utf-8", body, strlen(body), NULL);
            close(fd);
            return NULL;
        }
        if (strcmp(method, "POST") != 0) {
            const char *body = "{\"ok\":false,\"error\":\"仅支持 POST\"}";
            send_http(fd, 405, "Method Not Allowed", "application/json; charset=utf-8", body, strlen(body), NULL);
            close(fd);
            return NULL;
        }

        char body[1024] = {0};
        read_request_body(fd, header, n, body, sizeof(body));

        char old_password[256] = {0}, new_password[256] = {0}, confirm_password[256] = {0};
        form_value(body, "old_password", old_password, sizeof(old_password));
        form_value(body, "new_password", new_password, sizeof(new_password));
        form_value(body, "confirm_password", confirm_password, sizeof(confirm_password));

        const char *err = NULL;
        int status = 400;
        if (!old_password[0] || !new_password[0] || !confirm_password[0]) {
            err = "请完整填写三个密码框";
        } else if (strcmp(new_password, confirm_password) != 0) {
            err = "两次输入的新密码不一致";
        } else if (!check_password(old_password)) {
            err = "原密码错误";
            status = 403;
        }

        if (err) {
            char resp[256];
            int rn = snprintf(resp, sizeof(resp), "{\"ok\":false,\"error\":\"%s\"}", err);
            send_http(fd, status, status == 403 ? "Forbidden" : "Bad Request", "application/json; charset=utf-8", resp, rn, NULL);
            close(fd);
            return NULL;
        }

        pthread_mutex_lock(&auth_lock);
        sha1_hex(new_password, password_hash, sizeof(password_hash));
        pthread_mutex_unlock(&auth_lock);

        pthread_mutex_lock(&lock);
        add_event_locked("管理密码已修改");
        save_state_locked();
        pthread_mutex_unlock(&lock);

        const char *resp = "{\"ok\":true}";
        send_http(fd, 200, "OK", "application/json; charset=utf-8", resp, strlen(resp), NULL);
        close(fd);
        return NULL;
    }

    /* ── /admin 后台 WebSocket（需认证，不作为页面入口） ── */
    if (strcmp(path, "/admin") == 0) {
        if (!is_ws || !validate_session_from_header(header)) {
            send_not_found(fd);
            close(fd);
            return NULL;
        }
        if (websocket_handshake(fd, header)) {
            clear_socket_read_timeout(fd);
            handle_admin(fd);
        }
        close(fd);
        return NULL;
    }

    /* ── 管理主页（路径由 --home-path 指定，未登录时显示登录页） ── */
    if (strcmp(path, home_path) == 0) {
        if (strcmp(method, "POST") == 0) {
            char body[1024] = {0};
            read_request_body(fd, header, n, body, sizeof(body));

            char password[256] = {0};
            form_value(body, "password", password, sizeof(password));

            int locked = login_locked_seconds(real_ip);
            if (locked > 0) {
                pthread_mutex_lock(&lock);
                add_event_locked("管理登录被锁定：访问者 %s 仍需等待 %d 秒，提交密码：%s", real_ip, locked, password);
                pthread_mutex_unlock(&lock);
                serve_login_page(fd, "登录已锁定，请稍候再试");
            } else if (check_password(password)) {
                clear_login_failures(real_ip);
                pthread_mutex_lock(&lock);
                add_event_locked("管理登录成功：访问者 %s 已成功访问主页", real_ip);
                pthread_mutex_unlock(&lock);
                char token[64] = {0};
                create_session(token, sizeof(token));

                char cookie_hdr[512];
                snprintf(cookie_hdr, sizeof(cookie_hdr),
                    "Set-Cookie: led_session=%s; Path=/; Max-Age=1800; HttpOnly\r\n"
                    "Location: %s\r\n",
                    token, home_path);
                send_http(fd, 302, "Found", "text/plain", NULL, 0, cookie_hdr);
            } else {
                int locked_for = record_login_failure(real_ip);
                pthread_mutex_lock(&lock);
                if (locked_for > 0) {
                    add_event_locked("管理登录失败并锁定：访问者 %s 连续输错 3 次，锁定 1800 秒，错误密码：%s", real_ip, password);
                } else {
                    add_event_locked("管理登录失败：访问者 %s 错误密码：%s", real_ip, password);
                }
                pthread_mutex_unlock(&lock);
                char msg[256];
                if (locked_for > 0) {
                    snprintf(msg, sizeof(msg), "密码错误次数过多，已锁定，请稍候再试");
                } else {
                    int failures = login_failure_count(real_ip);
                    int left = 3 - failures;
                    if (left < 0) left = 0;
                    snprintf(msg, sizeof(msg), "密码错误，请重试。还有 %d 次机会", left);
                }
                serve_login_page(fd, msg);
            }
        } else if (validate_session_from_header(header)) {
            pthread_mutex_lock(&lock);
            pthread_mutex_unlock(&lock);
            serve_admin_page(fd);
        } else {
            serve_login_page(fd, NULL);
        }
        close(fd);
        return NULL;
    }

    /* ── 其他路径不响应入口，避免暴露隐藏主页路径 ── */
    send_not_found(fd);
    close(fd);
    return NULL;
}

/* ── 监听套接字 ────────────────────────────────────────── */
static int listen_socket(const char *addr, const char *port, int family) {
    struct addrinfo hints, *res = NULL, *rp;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = family; hints.ai_socktype = SOCK_STREAM; hints.ai_flags = AI_PASSIVE;
    if (getaddrinfo(addr, port, &hints, &res) != 0) return -1;
    int fd = -1;
    for (rp = res; rp; rp = rp->ai_next) {
        fd = socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
        if (fd < 0) continue;
        int one = 1;
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
#ifdef SO_REUSEPORT
        setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &one, sizeof(one));
#endif
        if (family == AF_INET6) setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &one, sizeof(one));
        if (bind(fd, rp->ai_addr, rp->ai_addrlen) == 0 && listen(fd, 128) == 0) break;
        close(fd); fd = -1;
    }
    freeaddrinfo(res);
    return fd;
}

/* ═══════════════════════════════════════════════════════════
   main
   ═══════════════════════════════════════════════════════════ */
int main(int argc, char **argv) {
    openlog("【LED悬浮消息】", LOG_PID, LOG_USER);
    const char *addr = "0.0.0.0";
    const char *port = "12366";
    int reset_pw = 0;
    const char *new_password = NULL;
    const char *default_password = "admin";

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-h") == 0 || strcmp(argv[i], "--help") == 0) {
            print_help(argv[0]);
            return 0;
        } else if (strcmp(argv[i], "-v") == 0 || strcmp(argv[i], "--version") == 0) {
            print_version();
            return 0;
        } else if (strcmp(argv[i], "-a") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "参数 -a 缺少监听地址。\n");
                return 2;
            }
            addr = argv[++i];
        } else if (strcmp(argv[i], "-p") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "参数 -p 缺少监听端口。\n");
                return 2;
            }
            port = argv[++i];
        } else if (strcmp(argv[i], "-P") == 0 || strcmp(argv[i], "--default-password") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "参数 %s 缺少默认密码。\n", argv[i]);
                return 2;
            }
            default_password = argv[++i];
            if (!default_password[0]) {
                fprintf(stderr, "默认密码不能为空。\n");
                return 2;
            }
        } else if (strcmp(argv[i], "--reset-password") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "参数 --reset-password 缺少新密码。\n");
                return 2;
            }
            reset_pw = 1;
            new_password = argv[++i];
        } else if (strcmp(argv[i], "--home-path") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "参数 --home-path 缺少主页路径。\n");
                return 2;
            }
            const char *hp = argv[++i];
            if (!set_home_path(hp)) {
                fprintf(stderr, "主页路径无效或与保留接口冲突：%s\n", hp);
                return 2;
            }
        } else {
            fprintf(stderr, "未知参数：%s\n使用 -h 查看帮助。\n", argv[i]);
            return 2;
        }
    }

    /* ── 重置密码模式 ── */
    if (reset_pw) {
        load_state();
        sha1_hex(new_password, password_hash, sizeof(password_hash));
        pthread_mutex_lock(&lock);
        save_state_locked();
        pthread_mutex_unlock(&lock);
        printf("密码已更新。\n");
        return 0;
    }

    signal(SIGPIPE, SIG_IGN);
    load_state();

    /* ── 若状态文件缺失、异常或无存储密码，使用启动参数指定的默认密码 ── */
    if (!password_hash[0]) {
        sha1_hex(default_password, password_hash, sizeof(password_hash));
        log_line("主页默认登录密码已启用，请及时修改！");
    }

    /* 确保默认密码被持久化 */
    if (password_hash[0]) {
        pthread_mutex_lock(&lock);
        save_state_locked();
        pthread_mutex_unlock(&lock);
    }

    int fds[2], count = 0;
    fds[count] = listen_socket(addr, port, AF_INET);
    if (fds[count] >= 0) { log_line("正在监听 IPv4 %s:%s", addr, port); count++; }
    else log_line("IPv4 监听失败：%s", strerror(errno));

    fds[count] = listen_socket("::", port, AF_INET6);
    if (fds[count] >= 0) { log_line("正在监听 IPv6 [::]:%s", port); count++; }
    else log_line("IPv6 监听失败：%s", strerror(errno));

    if (count == 0) { log_line("没有可用的监听套接字"); return 1; }

    if (strcmp(home_path, "/") != 0) {
        log_line("管理主页路径：%s", home_path);
    }

    while (1) {
        fd_set set; FD_ZERO(&set);
        int maxfd = -1;
        for (int i = 0; i < count; i++) { FD_SET(fds[i], &set); if (fds[i] > maxfd) maxfd = fds[i]; }
        if (select(maxfd + 1, &set, NULL, NULL, NULL) < 0) continue;
        for (int i = 0; i < count; i++) if (FD_ISSET(fds[i], &set)) {
            int cfd = accept(fds[i], NULL, NULL);
            if (cfd < 0) continue;
            set_socket_timeouts(cfd);
            int *arg = malloc(sizeof(int));
            if (!arg) { close(cfd); continue; }
            *arg = cfd;
            pthread_t tid;
            if (pthread_create(&tid, NULL, connection_thread, arg) != 0) {
                close(cfd);
                free(arg);
                continue;
            }
            pthread_detach(tid);
        }
    }
}
