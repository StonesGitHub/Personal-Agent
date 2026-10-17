package com.mozhi.assistant;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import static org.junit.Assert.*;

/** Guards the invariant: the Android app never exposes direct LLM API transport. */
public class ApiClientTest {
    @Test public void noApiKeysOrModelMethodsRemain() {
        for (Method m : ApiClient.class.getDeclaredMethods()) {
            assertEquals("read", m.getName());
            assertTrue(Modifier.isStatic(m.getModifiers()));
        }
    }

    @Test public void readPreservesUnicodeAndClosesStream() throws Exception {
        final boolean[] closed = {false};
        byte[] raw="中文文件内容".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input=new ByteArrayInputStream(raw) {
            @Override public void close() throws IOException {closed[0]=true; super.close();}
        };
        assertEquals("中文文件内容", ApiClient.read(input, 64));
        assertTrue(closed[0]);
    }

    @Test public void readEnforcesByteLimit() throws Exception {
        try {
            ApiClient.read(new ByteArrayInputStream(new byte[100]), 20);
            fail("Unbounded read would be unsafe");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("超过"));
        }
    }
}
