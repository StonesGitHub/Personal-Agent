package com.mozhi.assistant;
import org.junit.Test;import org.json.*;import java.util.*;import static org.junit.Assert.*;
public class PhoneAccountTest {
    @Test public void archivesAreDataNotExecutableRequests()throws Exception {
        JSONArray rows=new JSONArray().put(Core.obj("id","c","kind","conversation","parent","","created",1,"data",Core.obj("title","旧对话")))
            .put(Core.obj("id","m","kind","message","parent","c","created",2,"data",Core.obj("role","user","text","旧问题","status","pending","remote_payload","do not replay","pending_attachments",new JSONArray().put("private-path"))));
        JSONObject data=PhoneAccount.clean(rows).getJSONObject(1).getJSONObject("data");
        assertFalse(data.has("remote_payload"));assertFalse(data.has("pending_attachments"));assertEquals("cancelled",data.getString("status"));
        assertTrue(rows.getJSONObject(1).getJSONObject("data").has("remote_payload"));
    }
    @Test public void utf8BatchLimitAndRecordOrderArePreserved()throws Exception {
        JSONArray rows=new JSONArray();for(int i=0;i<10;i++)rows.put(Core.obj("id","c"+i,"kind","conversation","parent","","created",i,"data",Core.obj("title",String.join("",Collections.nCopies(20000,"中")))));
        List<JSONArray> batches=PhoneAccount.batches(rows);assertTrue(batches.size()>1);int n=0;
        for(JSONArray part:batches){assertTrue(part.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<240000);for(int i=0;i<part.length();i++)assertEquals("c"+n++,part.getJSONObject(i).getString("id"));}assertEquals(10,n);
    }
}
