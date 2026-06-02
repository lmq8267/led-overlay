package com.lmq8267.ledclient;

import android.content.Context;
import android.text.format.DateFormat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;

final class LogStore {
    private static final long LIMIT = 20L * 1024L * 1024L;

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
        StringBuilder out = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            char[] buffer = new char[4096];
            int n;
            while ((n = reader.read(buffer)) != -1) out.append(buffer, 0, n);
            reader.close();
        } catch (IOException ignored) {
        }
        return out.toString();
    }

    static synchronized void clear(Context context) {
        File file = file(context);
        if (file.exists()) file.delete();
    }

    private static void trimIfNeeded(File file) throws IOException {
        if (!file.exists() || file.length() < LIMIT) return;
        BufferedReader reader = new BufferedReader(new FileReader(file));
        StringBuilder tail = new StringBuilder();
        String line;
        long keep = LIMIT / 2;
        while ((line = reader.readLine()) != null) {
            tail.append(line).append('\n');
            if (tail.length() > keep) tail.delete(0, tail.length() - (int) keep);
        }
        reader.close();
        FileWriter writer = new FileWriter(file, false);
        writer.write(tail.toString());
        writer.close();
    }
}
