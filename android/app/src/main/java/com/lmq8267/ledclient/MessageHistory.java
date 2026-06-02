package com.lmq8267.ledclient;

import android.content.Context;
import android.text.format.DateFormat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

final class MessageHistory {
    static final class Entry {
        final String time;
        final String taskId;
        final String text;

        Entry(String time, String taskId, String text) {
            this.time = time;
            this.taskId = taskId;
            this.text = text;
        }
    }

    private MessageHistory() {}

    static File file(Context context) {
        return new File(context.getFilesDir(), "messages.log");
    }

    static synchronized void clear(Context context) {
        File file = file(context);
        if (file.exists()) file.delete();
    }

    static synchronized void append(Context context, JSONObject json) {
        append(context, json.optString("taskId", ""), json.optString("text", ""));
    }

    static synchronized void append(Context context, LedMessage message) {
        append(context, message == null ? "" : message.taskId, message == null ? "" : message.text);
    }

    static synchronized void append(Context context, String text) {
        append(context, "", text);
    }

    static synchronized void append(Context context, String taskId, String text) {
        if (text.length() == 0) return;
        String time = DateFormat.format("yyyy-MM-dd HH:mm:ss", new Date()).toString();
        try {
            ArrayList<Entry> entries = read(context, 99);
            FileWriter writer = new FileWriter(file(context), false);
            for (Entry entry : entries) writeEntry(writer, entry);
            writeEntry(writer, new Entry(time, taskId, text));
            writer.close();
        } catch (IOException ignored) {
        }
    }

    private static void writeEntry(FileWriter writer, Entry entry) throws IOException {
        writer.write(entry.time + "\t"
                + cleanField(entry.taskId) + "\t"
                + cleanField(entry.text) + "\n");
    }

    private static String cleanField(String value) {
        return (value == null ? "" : value).replace('\n', ' ').replace('\t', ' ');
    }

    static synchronized ArrayList<Entry> read(Context context, int limit) {
        ArrayList<Entry> all = new ArrayList<Entry>();
        File file = file(context);
        if (!file.exists()) return all;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                int tab2 = line.indexOf('\t', tab + 1);
                if (tab2 > tab) {
                    all.add(new Entry(line.substring(0, tab), line.substring(tab + 1, tab2), line.substring(tab2 + 1)));
                } else {
                    all.add(new Entry(line.substring(0, tab), "", line.substring(tab + 1)));
                }
            }
            reader.close();
        } catch (IOException ignored) {
        }
        int from = Math.max(0, all.size() - limit);
        return new ArrayList<Entry>(all.subList(from, all.size()));
    }
}
