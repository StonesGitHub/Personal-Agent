package com.mozhi.assistant;
import org.junit.Test;import org.json.*;import java.io.*;import static org.junit.Assert.*;
public class GatewayClientTest {
    @Test public void serviceEndpointAndCredentialAreSeparateAndRestricted()throws Exception {
        assertEquals("https://example.com/",GatewayClient.endpoint("https://example.com").toString());
        for(String s:new String[]{"http://example.com","https://example.com/api","https://example.com?key=a","https://user:pass@example.com","https://127.0.0.1"})try{GatewayClient.endpoint(s);fail(s);}catch(IOException expected){}
        try{new GatewayClient("https://example.com","short-token");fail();}catch(IOException expected){}
    }
    @Test public void gatewayKeepsOriginalRolesAndDoesNotRepeatWholeTranscriptInToolResult()throws Exception {
        JSONObject t=Core.obj("title","网关导入","omitted",2,"messages",new JSONArray().put(Core.obj("role","user","content","问题")).put(Core.obj("role","assistant","content","回答")));
        JSONObject data=Core.obj("url","https://example.com/chat","title","分享","text","用户：问题\n助手：回答","transcript",t);
        WebClient.ReadResult r=WebClient.gatewayRead(data);assertEquals(2,r.transcript.messages.size());assertEquals(2,r.transcript.omitted);assertFalse(r.data.has("transcript"));assertEquals("assistant",r.transcript.messages.get(1).role);assertTrue(data.has("transcript"));
    }
    @Test public void invalidGatewayDataDoesNotBecomeImport()throws Exception {
        try{WebClient.gatewayRead(Core.obj("url","https://127.0.0.1","text","malicious"));fail();}catch(IOException expected){}
        try{WebClient.gatewayRead(Core.obj("url","https://example.com","text",""));fail();}catch(IOException expected){}
    }
    @Test public void roleMarkupDoesNotStripAnswerMarkdown()throws Exception {ChatImport.Transcript t=ChatImport.parse("**User:** 问题\n**Assistant:** **重点** 和普通文字","test").get(0);assertEquals("**重点** 和普通文字",t.messages.get(1).text);}
}
