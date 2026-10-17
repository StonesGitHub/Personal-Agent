package com.mozhi.assistant;

import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

public class DecisionRecordsTest {
    private Core.Item user(String id,String text){return new Core.Item(id,"message","thread",1,Core.obj("role","user","text",text));}
    private Core.Item reply(){return new Core.Item("reply","message","thread",1,Core.obj("role","assistant","text","地点建议"));}
    private JSONObject args(String choice,String evidence,String id){return Core.obj("title","周末去处","choice",choice,"evidence",evidence,"status","已决定","replaces_id",id);}
    private AgentSession session(Core.Item user,List<Core.Item> items,boolean context,boolean learn){return new AgentSession(kind->{List<Core.Item> out=new ArrayList<>();for(Core.Item i:items)if(i.kind.equals(kind))out.add(i);return out;},user,context,learn);}
    @Test public void explicitChoiceStagesDecisionWithUserEvidence()throws Exception{
        Core.Item u=user("u1","就选人民公园，带孩子散步。");AgentSession s=session(u,new ArrayList<>(),true,true);
        s.call("record_decision",args("去人民公园带孩子散步","就选人民公园",""));Core.Item result=s.changes().iterator().next();
        assertEquals("decision",result.kind);assertEquals("u1",result.s("source_turn"));assertEquals("thread",result.s("source_conversation"));assertEquals("已决定",result.s("status"));
    }
    @Test public void fabricatedEvidenceAndOrdinaryAcknowledgementRejected()throws Exception{
        for(String evidence:new String[]{"我决定去北京","好的","去公园吗？"}){
            try{DecisionRecords.fromConversation(user("u","好的，去公园吗？"),args("去公园",evidence,""),new ArrayList<>(),2);fail();}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void sameChoiceIsIdempotentAndRevisionKeepsOriginalEvidence()throws Exception{
        Core.Item first=DecisionRecords.fromConversation(user("u1","选人民公园"),args("去人民公园","选人民公园",""),new ArrayList<>(),2);
        Core.Item duplicate=DecisionRecords.fromConversation(user("u1","选人民公园"),args("去人民公园","选人民公园",""),Arrays.asList(first),3);assertSame(first,duplicate);
        Core.Item changed=DecisionRecords.fromConversation(user("u2","改去东郊记忆"),args("去东郊记忆","改去东郊记忆",first.id),Arrays.asList(first),4);
        assertEquals(first.id,changed.id);assertEquals("u2",changed.s("source_turn"));assertEquals("选人民公园",changed.data.getJSONArray("revisions").getJSONObject(0).getString("evidence"));
    }
    @Test public void cannotOverwriteAnotherConversationOrManualCorrection()throws Exception{
        Core.Item first=DecisionRecords.fromConversation(user("u1","选人民公园"),args("去人民公园","选人民公园",""),new ArrayList<>(),2);
        first.data.put("source_conversation","elsewhere");
        try{DecisionRecords.fromConversation(user("u2","改去东郊记忆"),args("去东郊记忆","改去东郊记忆",first.id),Arrays.asList(first),3);fail();}catch(IllegalArgumentException expected){}
        first.data.put("source_conversation","thread");first.data.put("edited_by_user",true);
        try{DecisionRecords.fromConversation(user("u2","改去东郊记忆"),args("去东郊记忆","改去东郊记忆",first.id),Arrays.asList(first),3);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void finalMapActionUpdatesSameReplyWithoutClaimingVisit()throws Exception{
        MapLinks.Destination a=new MapLinks.Destination("人民公园","成都",""),b=new MapLinks.Destination("东郊记忆","成都","");
        Core.Item first=DecisionRecords.fromMap(reply(),a,new ArrayList<>(),2);
        assertSame(first,DecisionRecords.fromMap(reply(),a,Arrays.asList(first),3));
        Core.Item last=DecisionRecords.fromMap(reply(),b,Arrays.asList(first),4);
        assertEquals(first.id,last.id);assertEquals(b.query(),last.s("destination"));assertEquals("已打开地图",last.s("status"));assertTrue(last.s("note").contains("尚未确认出行"));assertEquals(1,last.data.getJSONArray("revisions").length());
    }
    @Test public void explicitConfirmationCanPromoteMapAction()throws Exception{
        Core.Item map=DecisionRecords.fromMap(reply(),new MapLinks.Destination("人民公园","成都",""),new ArrayList<>(),2);
        Core.Item finalChoice=DecisionRecords.fromConversation(user("u","就去人民公园"),args("去人民公园","就去人民公园",map.id),Arrays.asList(map),3);
        assertEquals(map.id,finalChoice.id);assertEquals("已决定",finalChoice.s("status"));assertEquals("agent",finalChoice.s("source"));
    }
    @Test public void closingMemoryOrLearningBlocksAutomaticDecisions()throws Exception{
        for(boolean[] flags:new boolean[][]{{false,true},{true,false}}){AgentSession s=session(user("u","选人民公园"),new ArrayList<>(),flags[0],flags[1]);assertTrue(s.call("record_decision",args("去人民公园","选人民公园","")).has("error"));assertTrue(s.changes().isEmpty());}
    }
    @Test public void failedFinalAnswerDoesNotCommitStagedDecision()throws Exception{
        List<Core.Item> repository=new ArrayList<>();AgentSession s=session(user("u","选人民公园"),repository,true,true);int[] calls={0};
        try{AgentEngine.run(new JSONArray(),(messages,schemas,last)->{
            if(calls[0]++==0)return Core.obj("content",JSONObject.NULL,"tool_calls",new JSONArray().put(Core.obj("id","d1","type","function","function",Core.obj("name","record_decision","arguments",args("去人民公园","选人民公园","").toString()))));
            throw new java.io.IOException("simulated model failure");
        },s,new AgentEngine.Control(){public void check(){}public void progress(String value){}});fail();}catch(java.io.IOException expected){}
        assertEquals(1,s.changes().size());assertTrue(repository.isEmpty());
    }
    @Test public void automaticDecisionsSurviveEncryptedBackup()throws Exception{
        Core.Item item=DecisionRecords.fromConversation(user("u","选人民公园"),args("去人民公园","选人民公园",""),new ArrayList<>(),2);
        JSONArray rows=new JSONArray().put(Core.obj("id",item.id,"kind",item.kind,"parent","","created",item.created,"data",item.data));
        JSONObject restored=Backup.decrypt(Backup.encrypt(rows,"test-password".toCharArray()),"test-password".toCharArray()).getJSONObject(0).getJSONObject("data");assertEquals("选人民公园",restored.getString("evidence"));
    }
}
