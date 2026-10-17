package com.mozhi.assistant;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

public class ReplySharingTest {
    private Core.Item message(String id,String chat,String role,String value){return new Core.Item(id,"message",chat,1,Core.obj("role",role,"text",value,"status","done","agent_trace",new JSONArray().put("private trace"),"hidden_context","secret"));}
    @Test public void sharesOnlyTheSelectedTurnNotTheFullConversation(){
        Core.Item before=message("q1","c","user","private past"),q=message("q2","c","user","为什么云是白色的？"),a=message("a2","c","assistant","因为水滴散射光。"),later=message("q3","c","user","private future");
        JSONObject pair=ReplySharing.pair(Arrays.asList(before,q,a,later),a);assertEquals(3,pair.length());assertEquals(q.s("text"),pair.optString("question"));assertEquals(a.s("text"),pair.optString("answer"));assertFalse(pair.toString().contains("private"));assertFalse(pair.toString().contains("secret"));
    }
    @Test public void sameNamedMessagesFromAnotherChatCannotSupplyTheQuestion(){
        Core.Item q=message("q","c","user","正确问题"),wrong=message("other","x","user","别的人的背景"),a=message("a","c","assistant","回答");
        assertEquals("正确问题",ReplySharing.pair(Arrays.asList(q,wrong,a),a).optString("question"));
    }
    @Test public void publicPayloadUsesTheEditedTextAndAnAllowlist(){
        JSONObject data=ReplySharing.payload(UUID.randomUUID().toString(),"  已删去名字的问题  ","**精简的回答**");assertEquals(3,data.length());assertEquals("已删去名字的问题",data.optString("question"));assertEquals("**精简的回答**",data.optString("answer"));
    }
    @Test public void unfinishedOrOrphanRepliesCannotBePublished()throws Exception{
        Core.Item a=message("a","c","assistant","reply");a.data.put("status","pending");try{ReplySharing.pair(Collections.singletonList(a),a);fail();}catch(IllegalArgumentException expected){}
        a.data.put("status","done");try{ReplySharing.pair(Collections.singletonList(a),a);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void limitsRejectInsteadOfSilentlyCuttingContent(){
        for(String answer:new String[]{" ","a".repeat(24001)})try{ReplySharing.payload(UUID.randomUUID().toString(),"question",answer);fail();}catch(IllegalArgumentException expected){}
        try{ReplySharing.payload("invalid","q","a");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void likesAndDislikesAreExclusiveAndCanBeCancelled(){
        assertEquals("like",ReplySharing.toggledFeedback("","like"));assertEquals("dislike",ReplySharing.toggledFeedback("like","dislike"));assertEquals("",ReplySharing.toggledFeedback("dislike","dislike"));assertEquals("",ReplySharing.toggledFeedback("like","like"));
    }
    @Test public void socialCredentialsCannotUseAnUnsafeEndpoint(){
        for(String value:new String[]{"http://example.com","https://user:pass@example.com","https://example.com?token=secret","file:///data/secret","https://example.com#secret"})try{CommunityClient.endpoint(value,"/v1/posts");fail(value);}catch(IllegalArgumentException expected){}
        assertEquals("https://example.com/v1/posts",CommunityClient.endpoint("https://example.com/","/v1/posts"));
    }
}
