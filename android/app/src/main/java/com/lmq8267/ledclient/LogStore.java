package com.lmq8267.ledclient;

import android.content.Context;
import android.text.format.DateFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Date;

final class LogStore {
    private static final long LIMIT = 20L * 1024L * 1024L;
    /** 日志页每秒刷新；只读尾部，避免把 20MB 文件整份读进主线程。 */
    private static final int READ_TAIL = 64 * 1024;

    private LogStore() {}

    static File file(Context context) {
        return new File(context.getFilesDir(), "led.log");
    }

    static synchronized void append(Context context, String message) {
        File file = file(context);
        String time = DateFormat.format("yyyy-MM-dd HH:mm:ss", new Date()).toString();
        try {
            trimIfNeeded(file);
            FileWriter writer = new FileWriter(file, true);
            writer.write(time + "  " + message + "\n");
            writer.close();
        } catch (IOException ignored) {
        }
    }

    static synchronized String read(Context context) {
        File file = file(context);
        if (!file.exists()) return "";
        try {
            long length = file.length();
            long start = Math.max(0L, length - READ_TAIL);
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            byte[] data = new byte[(int) (length - start)];
            raf.seek(start);
            raf.readFully(data);
            raf.close();
            int offset = 0;
            if (start > 0) {
                // 从文件中间开始时第一行通常是半截，丢掉它。
                while (offset < data.length && data[offset] != '\n') offset++;
                if (offset < data.length) offset++;
            }
            return new String(data, offset, data.length - offset, "UTF-8");
        } catch (IOException ignored) {
            return "";
        }
    }

    static synchronized void clear(Context context) {
        File file = file(context);
        if (file.exists()) file.delete();
    }

    private static void trimIfNeeded(File file) throws IOException {
        if (!file.exists() || file.length() < LIMIT) return;
        long keep = LIMIT / 2;
        long length = file.length();
        long start = Math.max(0L, length - keep);
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        byte[] data = new byte[(int) (length - start)];
        raf.seek(start);
        raf.readFully(data);
        raf.close();
        int offset = 0;
        if (start > 0) {
            while (offset < data.length && data[offset] != '\n') offset++;
            if (offset < data.length) offset++;
        }
        // 旧实现逐行 append 后反复 delete(0, ...)，20MB 时是 O(n²) 的 memmove，
        // 会把调用线程（通常是主线程）卡到 ANR。这里按字节保留尾部，线性完成。
        FileOutputStream out = new FileOutputStream(file, false);
        out.write(data, offset, data.length - offset);
        out.close();
    }
}
