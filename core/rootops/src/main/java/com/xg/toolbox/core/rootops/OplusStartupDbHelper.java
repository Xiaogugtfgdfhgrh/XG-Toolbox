package com.xg.toolbox.core.rootops;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OplusStartupDbHelper {
    private static final String DB = "/data/user_de/0/com.oplus.battery/databases/record.db";
    private static final String XML = "/data/oplus/os/startup/startup_dynamic_list.xml";

    public static void main(String[] args) {
        if (args == null || args.length < 2) {
            System.err.println("usage: <package> <on|off|status>");
            System.exit(2);
        }
        String pkg = args[0];
        String action = args[1];
        if ("status".equals(action)) {
            try {
                System.out.println(readStatus(pkg));
                return;
            } catch (Throwable t) {
                t.printStackTrace();
                System.exit(1);
            }
            return;
        }
        boolean enabled = "on".equals(action);
        if (!enabled && !"off".equals(action)) {
            System.err.println("usage: <package> <on|off|status>");
            System.exit(2);
        }
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(DB, null, SQLiteDatabase.OPEN_READWRITE, null);
            db.rawQuery("PRAGMA busy_timeout=3000", null).close();
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM AppToShow WHERE packageName = ? AND isBootStart = 0", new Object[]{pkg});
                if (enabled) {
                    db.execSQL(
                        "INSERT INTO AppToShow (packageName,label,isBootBroadcast,isChecked,isSuggested,isBootStart) " +
                        "VALUES (?,NULL,0,1,0,0)",
                        new Object[]{pkg}
                    );
                    db.execSQL(
                        "UPDATE AppToShow SET isChecked = 0 WHERE packageName = ? AND isBootStart = 1",
                        new Object[]{pkg}
                    );
                } else {
                    db.execSQL(
                        "UPDATE AppToShow SET isChecked = 1 WHERE packageName = ? AND isBootStart = 1",
                        new Object[]{pkg}
                    );
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            db.close();
            db = null;
            System.out.println("DB_OK");
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        } finally {
            if (db != null) {
                try { if (db.inTransaction()) db.endTransaction(); } catch (Throwable ignored) {}
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
        try {
            String text = read(XML);
            String marker = "pkgName=\"" + pkg + "\"";
            if (enabled) {
                text = enableXml(text, pkg, marker);
            } else {
                text = text.replaceAll("<dynamic[^>]*" + Pattern.quote(marker) + "[^>]*/>", "");
            }
            write(XML, text);
            System.out.println("XML_OK");
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static String enableXml(String text, String pkg, String marker) {
        Matcher matcher = Pattern.compile("<dynamic[^>]*>").matcher(text);
        StringBuffer output = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            String tag = matcher.group();
            if (tag.contains(marker)) {
                found = true;
                if (!tag.contains("switch=\"1\"")) {
                    if (tag.contains("switch=\"0\"")) {
                        tag = tag.replace("switch=\"0\"", "switch=\"1\"");
                    } else {
                        tag = tag.replace("/>", " switch=\"1\" />");
                    }
                }
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(tag));
        }
        matcher.appendTail(output);
        if (!found) {
            output.append("<dynamic pkgName=\"").append(pkg)
                .append("\" type=\"1\" source=\"8\" switch=\"1\" />");
        }
        return output.toString();
    }

    private static String readStatus(String pkg) throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(DB, null, SQLiteDatabase.OPEN_READONLY, null);
        int rows = 0;
        int enabledRows = 0;
        try {
            try (Cursor cursor = db.rawQuery(
                "SELECT isChecked, isBootStart FROM AppToShow WHERE packageName = ?",
                new String[]{pkg}
            )) {
                while (cursor.moveToNext()) {
                    rows += 1;
                    if (cursor.getInt(1) == 0 && cursor.getInt(0) == 1) enabledRows += 1;
                }
            }
        } finally {
            db.close();
        }
        boolean xmlEnabled = xmlEnabled(read(XML), "pkgName=\"" + pkg + "\"");
        return "STATUS DB_ROWS=" + rows + " DB_ON=" + (enabledRows > 0 ? 1 : 0) +
            " XML_ON=" + (xmlEnabled ? 1 : 0);
    }

    private static boolean xmlEnabled(String text, String marker) {
        Matcher matcher = Pattern.compile("<dynamic[^>]*>").matcher(text);
        while (matcher.find()) {
            String tag = matcher.group();
            if (tag.contains(marker) && tag.contains("switch=\"1\"")) return true;
        }
        return false;
    }

    private static String read(String path) throws Exception {
        File f = new File(path);
        byte[] bytes = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        int off = 0;
        while (off < bytes.length) {
            int n = in.read(bytes, off, bytes.length - off);
            if (n <= 0) break;
            off += n;
        }
        in.close();
        return new String(bytes, 0, off, StandardCharsets.UTF_8);
    }

    private static void write(String path, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(path, false);
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.close();
    }
}
