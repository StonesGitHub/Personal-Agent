package com.mozhi.assistant;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteAgentClientTest {
    @Test public void separatesBackgroundFromMessageRolesAndCarriesWebConsent()throws Exception{
        JSONArray messages=new JSONArray().put(Core.obj("role","system","content","背景：预算500元"))
            .put(Core.obj("role","assistant","content","希望比较什么？"))
            .put(Core.obj("role","user","content","比较这两个产品"));
        JSONObject value=RemoteAgentClient.payload("conversation-123",messages,false);
        assertFalse(value.getBoolean("allow_web"));assertTrue(value.getString("background").contains("预算500元"));
        assertEquals(2,value.getJSONArray("messages").length());
        assertEquals("user",value.getJSONArray("messages").getJSONObject(1).getString("role"));
        assertFalse(value.has("model_key"));assertFalse(value.has("token"));
        assertNotEquals(value.getString("client_request_id"),RemoteAgentClient.payload("conversation-123",messages,false).getString("client_request_id"));
    }
    @Test public void attachesOnlyRealArtifactLinksAndDeduplicates()throws Exception{
        String id="a".repeat(32),link="mozhi://artifact?id="+id;
        JSONObject run=Core.obj("output","完成","artifacts",new JSONArray().put(Core.obj("id",id,"name","成本.csv")));
        assertTrue(RemoteAgentClient.answer(run).contains("[成本.csv]("+link+")"));
        run.put("output","[文件]("+link+")");String answer=RemoteAgentClient.answer(run);
        assertEquals(answer.indexOf(link),answer.lastIndexOf(link));
    }
    @Test public void statusDoesNotInventCompletion()throws Exception{
        assertTrue(RemoteAgentClient.status(Core.obj("state","running","rounds",2,"calls",1)).contains("正在执行"));
        assertTrue(RemoteAgentClient.status(Core.obj("state","interrupted")).contains("中断"));
        assertTrue(RemoteAgentClient.status(Core.obj("state","cancelled")).contains("取消"));
    }
}
