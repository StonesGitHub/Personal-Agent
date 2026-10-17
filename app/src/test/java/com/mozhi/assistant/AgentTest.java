package com.mozhi.assistant;
import org.junit.Test;import org.json.*;import java.util.*;import static org.junit.Assert.*;
public class AgentTest {
    private final List<Core.Item> data=new ArrayList<>();
    private Core.Item user(){return new Core.Item("turn","message","conversation",1,Core.obj("role","user","text","我喜欢安静的咖啡馆，回答请简洁。请把咖啡馆选择流程保存为技能。"));}
    private AgentSession session(boolean context,boolean learn){return new AgentSession(k->{List<Core.Item> out=new ArrayList<>();for(Core.Item i:data)if(i.kind.equals(k))out.add(i);return out;},user(),context,learn);}
    private JSONObject memory(){return Core.obj("slot","user.cafe","text","喜欢安静的咖啡馆","evidence","我喜欢安静的咖啡馆","scope","relevant","days","0");}
    private JSONObject skill(){return Core.obj("name","咖啡馆选择","trigger","咖啡馆 安静 选择","body","# 选择方法\n比较距离、安静程度与预算；没有实时信息时请用户核实。");}
    private JSONObject call(String id,String tool,JSONObject args){return Core.obj("role","assistant","content",JSONObject.NULL,"reasoning_content","opaque-provider-field","tool_calls",new JSONArray().put(Core.obj("id",id,"type","function","function",Core.obj("name",tool,"arguments",args.toString()))));}
    private AgentEngine.Control control(){return new AgentEngine.Control(){public void check(){}public void progress(String s){}};}
    @Test public void toolLoopFeedsObservationAndStagesLearning()throws Exception{
        AgentSession s=session(true,true);final int[] calls={0};JSONArray messages=new JSONArray().put(Core.obj("role","user","content",user().s("text")));
        String answer=AgentEngine.run(messages,(ms,tools,last)->{int n=calls[0]++;if(n==0)return call("one","remember",memory());if(n==1){assertEquals("one",ms.getJSONObject(ms.length()-1).getString("tool_call_id"));assertEquals("opaque-provider-field",ms.getJSONObject(ms.length()-2).getString("reasoning_content"));return call("two","save_skill",skill());}return Core.obj("role","assistant","content","**已整理**");},s,control());
        assertEquals("**已整理**",answer);assertEquals(3,calls[0]);assertEquals(2,s.changes().size());assertTrue(data.isEmpty());
    }
    @Test public void savedSkillAutoloadsInNextSessionAndCanBeDisabled()throws Exception{
        AgentSession s=session(true,true);s.call("save_skill",skill());data.addAll(s.changes());AgentSession next=session(true,true);assertTrue(next.instructions().contains("自动加载的技能资料"));data.get(0).data.put("enabled",false);assertFalse(session(true,true).instructions().contains("自动加载的技能资料"));
    }
    @Test public void contextOffBlocksPersonalToolsAndSkills()throws Exception{
        AgentSession s=session(false,true);assertEquals(1,s.schemas().length());assertTrue(s.call("remember",memory()).has("error"));assertTrue(s.call("memory_search",Core.obj("query","咖啡馆")).has("error"));assertTrue(s.changes().isEmpty());assertFalse(s.instructions().contains("技能目录"));
    }
    @Test public void learningOffStillAllowsRecallButNotWrites()throws Exception{
        AgentSession s=session(true,false);assertEquals(4,s.schemas().length());assertTrue(s.call("save_skill",skill()).has("error"));assertFalse(s.call("memory_search",Core.obj("query","咖啡馆")).has("error"));
    }
    @Test public void fabricatedEvidenceRejected()throws Exception{
        JSONObject m=memory();m.put("evidence","用户在示例城市有两套房");try{session(true,true).call("remember",m);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void memoryUpdateRetainsPreviousVersion()throws Exception{
        AgentSession first=session(true,true);first.call("remember",memory());data.addAll(first.changes());JSONObject m=memory();m.put("text","偏好安静环境的咖啡馆");AgentSession second=session(true,true);second.call("remember",m);Core.Item edited=second.changes().iterator().next();assertEquals(data.get(0).id,edited.id);assertEquals("喜欢安静的咖啡馆",edited.s("previous_text"));
    }
    @Test public void unknownToolNeverRuns()throws Exception{assertTrue(session(true,true).call("shell",Core.obj("command","rm -rf /")).has("error"));}
    @Test public void cancellationAfterToolDoesNotTouchRepository()throws Exception{
        AgentSession s=session(true,true);final int[] n={0};try{AgentEngine.run(new JSONArray(),(ms,t,l)->call("x","remember",memory()),s,new AgentEngine.Control(){public void progress(String v){}public void check()throws Exception{if(++n[0]>=4)throw new InterruptedException();}});fail();}catch(InterruptedException expected){}assertTrue(data.isEmpty());
    }
    @Test public void runawayAgentStopsAtRoundBudget()throws Exception{
        final int[] n={0};try{AgentEngine.run(new JSONArray(),(ms,t,last)->call("x"+(n[0]++),"memory_search",Core.obj("query","咖啡")),session(true,true),control());fail();}catch(java.io.IOException expected){}assertEquals(AgentEngine.MAX_ROUNDS,n[0]);
    }
    @Test public void backupRoundtripIncludesSkillsAndRejectsMalformed()throws Exception{
        AgentSession s=session(true,true);s.call("save_skill",skill());Core.Item i=s.changes().iterator().next();JSONArray rows=new JSONArray().put(Core.obj("id",i.id,"kind",i.kind,"parent","","created",1,"data",i.data));assertEquals(1,Backup.decrypt(Backup.encrypt(rows,"test-password".toCharArray()),"test-password".toCharArray()).length());i.data.put("body","");try{Backup.validate(rows);fail();}catch(Exception expected){}
    }
    @Test public void providerMayReuseIdAcrossDifferentRounds()throws Exception{
        int[] count={0};String result=AgentEngine.run(new JSONArray(),(ms,ts,last)->{if(count[0]++<2)return call("call_0","memory_search",Core.obj("query","咖啡"));return Core.obj("content","完成");},session(true,true),control());assertEquals("完成",result);assertEquals(3,count[0]);
    }

}
