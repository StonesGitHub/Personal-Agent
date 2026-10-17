package com.mozhi.assistant;

import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

public class CoreTest {
    private Core.Item memory(String id,String text,boolean global,boolean enabled,long expiry){return new Core.Item(id,"memory","",10,Core.obj("text",text,"category","个人","global",global,"enabled",enabled,"expires",expiry));}
    @Test public void memoryRespectsDisabledExpiryAndTopic(){
        List<Core.Item> source=Arrays.asList(memory("a","喜欢简短回答",true,true,0),memory("b","家庭周末要带孩子",false,true,0),memory("c","家庭旧预算",true,true,90),memory("d","家庭隐私",true,false,0),memory("e","其他健康资料",false,true,0));
        List<Core.Item> result=Core.memories(source,"安排家庭周末",100);
        assertEquals(Arrays.asList("a","b"),Arrays.asList(result.get(0).id,result.get(1).id));assertEquals(2,result.size());
    }
    @Test public void removedMemoryDoesNotRemainInContext(){
        List<Core.Item> source=new ArrayList<>();source.add(memory("a","旧背景",true,true,0));
        assertEquals(1,Core.memories(source,"你好",100).size());source.clear();assertEquals(0,Core.memories(source,"你好",100).size());
    }
    @Test public void secureEndpointNormalizesCompletionSuffix(){assertEquals("https://api.example.com/v1/chat/completions",Core.endpoint("https://api.example.com/v1/chat/completions","/chat/completions"));}
    @Test public void endpointsRejectCredentialsAndCleartext(){for(String bad:Arrays.asList("http://example.com","https://user:pass@example.com","https://example.com?secret=x","https://example.com/#fragment","garbage")){try{Core.endpoint(bad,"/models");fail(bad);}catch(IllegalArgumentException expected){}}}
    @Test public void candidatesAreBoundedAndDeduplicated() throws Exception {assertEquals(Arrays.asList("喜欢简短回答"),Core.candidates("```json\n{\"memories\":[\"喜欢简短回答\",\"喜欢简短回答\",123,\"\"]}\n```"));}
    @Test public void historyDoesNotLeakFailedTurns() throws Exception {
        List<Core.Item> history=Arrays.asList(new Core.Item("a","message","c",1,Core.obj("role","user","text","失败的问题","status","failed")),new Core.Item("b","message","c",2,Core.obj("role","user","text","当前问题","status","pending")));
        JSONArray messages=Core.messages(history,Collections.emptyList());assertEquals(2,messages.length());assertEquals("当前问题",messages.getJSONObject(1).getString("content"));assertFalse(messages.toString().contains("失败的问题"));
    }
    @Test public void budgetKeepsLatestUserAndDropsOrphanAssistant() throws Exception {
        String large=String.join("",Collections.nCopies(17000,"a"));
        List<Core.Item> history=Arrays.asList(new Core.Item("a","message","c",1,Core.obj("role","user","text",large,"status","done")),new Core.Item("b","message","c",2,Core.obj("role","assistant","text",large,"status","done")),new Core.Item("c","message","c",3,Core.obj("role","user","text","现在的问题","status","pending")));
        JSONArray messages=Core.messages(history,Collections.emptyList());assertEquals(2,messages.length());assertEquals("现在的问题",messages.getJSONObject(1).getString("content"));
    }
    @Test public void emptyResponseFailsAndTruncationIsVisible() throws Exception {
        try{Core.response("{\"choices\":[]}");fail();}catch(Exception expected){}
        String answer=Core.response("{\"choices\":[{\"message\":{\"content\":\"建议\"},\"finish_reason\":\"length\"}]}");assertTrue(answer.startsWith("建议"));assertTrue(answer.contains("未完成"));
    }
}
