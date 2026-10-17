package com.mozhi.assistant;

import android.app.Instrumentation;
import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import org.json.*;
import java.util.*;

/** Run only on an isolated test device. No model or installed third-party map is required. */
public class DecisionDeviceTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public DecisionDeviceTest(){super(MainActivity.class);}
    private MozhiApp app()throws Exception{MozhiApp app=(MozhiApp)getActivity().getApplication();for(int n=0;n<100&&app.store==null;n++)Thread.sleep(100);assertNotNull(app.store);return app;}
    public void testSuccessfulMapHandoffRecordsLastDestinationOnce()throws Throwable{
        MozhiApp app=app();LocalStore store=app.store;store.putSetting("use_memory","yes");store.putSetting("auto_learning","yes");
        Core.Item thread=store.add("conversation","",Core.obj("title","自动决定测试"));
        Core.Item reply=store.add("message",thread.id,Core.obj("role","assistant","status","done","text","推荐地点"));
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent i){return new Instrumentation.ActivityResult(0,null);}};
        getInstrumentation().addMonitor(monitor);
        try{
            for(String place:new String[]{"人民公园","人民公园","东郊记忆"})runTestOnUiThread(()->{
                MapLinks.Destination d=new MapLinks.Destination(place,"成都","");
                MapNavigator.launch(getActivity(),d,MapNavigator.intentFor(d,MapNavigator.AMAP),()->{try{store.recordMapAction(reply,d);}catch(Exception e){throw new AssertionError(e);}});
            });
            List<Core.Item> own=new ArrayList<>();for(Core.Item i:store.list("decision",null))if(DecisionRecords.sameConversation(i,thread.id))own.add(i);
            assertEquals(1,own.size());assertEquals(DecisionRecords.OPENED_MAP,own.get(0).s("status"));assertEquals("成都 东郊记忆",own.get(0).s("destination"));
            store.putSetting("use_memory","no");store.recordMapAction(reply,new MapLinks.Destination("杜甫草堂","成都",""));
            for(Core.Item i:store.list("decision",null))if(i.id.equals(own.get(0).id)){assertEquals("成都 东郊记忆",i.s("destination"));store.remove(i);}
        }finally{getInstrumentation().removeMonitor(monitor);store.remove(thread);store.putSetting("use_memory","yes");}
    }
    public void testUnsuccessfulMapHandoffDoesNotRecordChoice()throws Throwable{
        app();final int[] saves={0};
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent i){throw new android.content.ActivityNotFoundException("simulated missing map");}};
        getInstrumentation().addMonitor(monitor);
        try{runTestOnUiThread(()->{MapLinks.Destination d=new MapLinks.Destination("人民公园","成都","");MapNavigator.launch(getActivity(),d,MapNavigator.intentFor(d,MapNavigator.AMAP),()->saves[0]++);});assertEquals(0,saves[0]);}
        finally{getInstrumentation().removeMonitor(monitor);}
    }
    public void testCompletedAgentReplyPersistsDecisionAndEvidenceTogether()throws Exception{
        LocalStore store=app().store;Core.Item c=store.add("conversation","",Core.obj("title","决定原子提交测试"));
        Core.Item user=store.add("message",c.id,Core.obj("role","user","text","就选人民公园","status","pending"));
        Core.Item decision=DecisionRecords.fromConversation(user,Core.obj("title","周末去处","choice","去人民公园","evidence","就选人民公园","status","已决定","replaces_id",""),new ArrayList<>(),System.currentTimeMillis());
        try{store.completeReply(user,"那就去人民公园吧。",Arrays.asList(decision),Arrays.asList("自动整理决定"));assertEquals(2,store.list("message",c.id).size());assertEquals("done",store.list("message",c.id).get(0).s("status"));
            Core.Item saved=null;for(Core.Item i:store.list("decision",null))if(i.id.equals(decision.id))saved=i;assertNotNull(saved);assertEquals("就选人民公园",saved.s("evidence"));
        }finally{store.remove(decision);store.remove(c);}
    }
}
