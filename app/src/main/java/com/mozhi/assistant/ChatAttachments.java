package com.mozhi.assistant;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;
import java.util.Locale;

/** Android document picker metadata and bounded, on-demand streaming reads. */
final class ChatAttachments {
    static final int PICK_FILE = 46;
    static final int MAX_FILES = 3;
    static final int MAX_BYTES = 8 * 1024 * 1024;

    static JSONObject describe(Context context, Uri uri) throws Exception {
        String name = null;
        long size = -1;
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int z = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0) name = cursor.getString(n);
                if (z >= 0 && !cursor.isNull(z)) size = cursor.getLong(z);
            }
        }
        if (name == null || name.isEmpty() || name.length() > 120 ||
                name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || containsControl(name))
            throw new IOException("无法识别附件名称");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".docx") || lower.endsWith(".xlsx") || lower.endsWith(".png") ||
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif")))
            throw new IOException("支持 docx、xlsx、JPG、PNG、WebP、GIF。旧版 doc/xls 请先转换。");
        if (size > MAX_BYTES) throw new IOException("附件不能超过 8 MiB");
        return Core.obj("uri", uri.toString(), "name", name);
    }

    private static boolean containsControl(String name) {
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) < 32) return true;
        return false;
    }

    static byte[] read(Context context, JSONObject file) throws Exception {
        Uri uri = Uri.parse(file.getString("uri"));
        try (InputStream stream = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (stream == null) throw new IOException("附件已无法访问，请重新选择文件");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) != -1) {
                if (output.size() + n > MAX_BYTES) throw new IOException("附件不能超过 8 MiB");
                output.write(buffer, 0, n);
            }
            if (output.size() == 0) throw new IOException("文件为空");
            return output.toByteArray();
        } catch (SecurityException e) {
            throw new IOException("附件读取权限失效，请重新选择文件", e);
        }
    }
}
