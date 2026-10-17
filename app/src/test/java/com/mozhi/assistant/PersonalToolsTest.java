package com.mozhi.assistant;

import org.junit.Test;
import org.json.*;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

public class PersonalToolsTest {
    private final List<Core.Item> data=new ArrayList<>();
    private Core.Item skill(String id){return new Core.Item(id,"skill","",1,Core.obj("builtin_id",id,"name",id,"trigger",id,"body","method","enabled",true));}
    private AgentSession session(String text,boolean context){return new AgentSession(k->{List<Core.Item> rows=new ArrayList<>();for(Core.Item i:data)if(i.kind.equals(k))rows.add(i);return rows;},new Core.Item("turn","message","conversation",1,Core.obj("text",text)),context,false);}
    private JSONObject reminder(){return Core.obj("title","出发","body","带水杯","start_at",ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).plusHours(1).toOffsetDateTime().toString(),"timezone","Asia/Shanghai","repeat","daily","evidence","请提醒我出发");}
    private static long ms(String value){return OffsetDateTime.parse(value).toInstant().toEpochMilli();}
    @Test public void scheduleValidatesDatesAndOffsets()throws Exception{
        JSONObject r=reminder();assertTrue(ReminderSchedule.validate(r,System.currentTimeMillis()).getLong("next_fire")>System.currentTimeMillis());
        r.put("start_at","2026-10-09T08:00:00+00:00");try{ReminderSchedule.next(r,0);fail();}catch(IllegalArgumentException expected){}
        r.put("start_at","2000-01-01T08:00:00+08:00");try{ReminderSchedule.validate(r,System.currentTimeMillis());fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void dailyKeepsWallTimeAcrossDstAndCoalescesMisses()throws Exception{
        JSONObject r=Core.obj("start_at","2026-03-07T02:30:00-05:00","timezone","America/New_York","repeat","daily");
        long gap=ReminderSchedule.next(r,ms("2026-03-07T04:00:00-05:00"));assertEquals(ms("2026-03-08T03:30:00-04:00"),gap);assertEquals(ms("2026-03-09T02:30:00-04:00"),ReminderSchedule.next(r,gap));
        r.put("start_at","2026-10-31T01:30:00-04:00");long overlap=ReminderSchedule.next(r,ms("2026-10-31T02:00:00-04:00"));assertEquals(ms("2026-11-01T01:30:00-04:00"),overlap);assertEquals(ms("2026-11-02T01:30:00-05:00"),ReminderSchedule.next(r,overlap));
        r.put("repeat","weekly");assertEquals(ms("2026-11-07T01:30:00-05:00"),ReminderSchedule.next(r,ms("2026-10-31T02:00:00-04:00")));
    }
    @Test public void remindersStageOnceAndDoNotDependOnPersonalContext()throws Exception{
        data.add(skill("reminders"));AgentSession s=session("请提醒我出发",false);JSONObject args=reminder();
        assertFalse(s.call("create_reminder",args).has("error"));s.call("create_reminder",args);assertEquals(1,s.changes().size());assertEquals(1,data.size());
        assertEquals(1,s.call("list_reminders",new JSONObject()).getJSONArray("items").length());
    }
    @Test public void disablingSkillBlocksMutationAndReading()throws Exception{
        Core.Item skill=skill("reminders");skill.data.put("enabled",false);data.add(skill);
        assertTrue(session("请提醒我出发",true).call("create_reminder",reminder()).has("error"));
    }
    @Test public void explicitEvidenceRequiredAndCancellationStages()throws Exception{
        data.add(skill("reminders"));try{session("我想出游",true).call("create_reminder",reminder());fail();}catch(IllegalArgumentException expected){}
        AgentSession created=session("请提醒我出发",true);created.call("create_reminder",reminder());data.addAll(created.changes());Core.Item old=created.changes().iterator().next();
        AgentSession cancel=session("取消提醒",true);cancel.call("cancel_reminder",Core.obj("id",old.id,"evidence","取消提醒"));assertEquals("cancelled",cancel.changes().iterator().next().s("state"));assertEquals("active",old.s("state"));
    }
    @Test public void healthRecordRequiresConsentAndContextAndCanBeRemoved()throws Exception{
        data.add(skill("health"));JSONObject value=Core.obj("subject","本人","category","sleep","text","昨晚睡了7小时","occurred_at",OffsetDateTime.now().toString(),"evidence","请记录昨晚睡了7小时");
        assertTrue(session("请记录昨晚睡了7小时",false).call("save_health_record",value).has("error"));
        AgentSession s=session("请记录昨晚睡了7小时",true);assertFalse(s.call("save_health_record",value).has("error"));data.addAll(s.changes());
        AgentSession remove=session("删除记录",true);String id=s.changes().iterator().next().id;remove.call("delete_health_record",Core.obj("id",id,"evidence","删除记录"));assertTrue(remove.changes().iterator().next().data.getBoolean("deleted"));assertFalse(remove.changes().iterator().next().data.has("text"));
    }
    @Test public void backupAcceptsPersonalDataAndRejectsMalformedSchedule()throws Exception{
        data.add(skill("reminders"));AgentSession s=session("请提醒我出发",true);s.call("create_reminder",reminder());Core.Item i=s.changes().iterator().next();JSONArray rows=new JSONArray().put(Core.obj("id",i.id,"kind",i.kind,"parent","","created",i.created,"data",i.data));
        assertEquals(1,Backup.decrypt(Backup.encrypt(rows,"test-password".toCharArray()),"test-password".toCharArray()).length());
        i.data.put("timezone","not-a-zone");try{Backup.validate(rows);fail();}catch(Exception expected){}
    }
    @Test public void remotePayloadCarriesOnlyEnabledBuiltinIds()throws Exception{
        JSONObject p=RemoteAgentClient.payload("c",new JSONArray().put(Core.obj("role","user","content","你好")),false,new JSONArray().put("travel"));assertEquals("[\"travel\"]",p.getJSONArray("builtin_skills").toString());assertFalse(p.getString("timezone").isEmpty());
    }
}
