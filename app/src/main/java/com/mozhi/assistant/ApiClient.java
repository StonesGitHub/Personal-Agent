package com.mozhi.assistant;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Legacy name retained only for existing file-reading call sites.
 * There is intentionally NO model endpoint, token, or LLM transport here:
 * all model inference goes through RemoteAgentClient and the server.
 */
public final class ApiClient {
    private ApiClient() {}

    public static String read(InputStream in, int limit) throws IOException {
        if (in == null) throw new IOException("没有读取到文件");
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = stream.read(buf)) != -1) {
                if (out.size() + n > limit) throw new IOException("内容超过可读取的大小限制");
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
