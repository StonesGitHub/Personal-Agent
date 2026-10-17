package com.mozhi.assistant;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.json.*;

/** Staged local changes: only committed with a successful answer. No Android dependency. */
final class PersonalTools {
    private final AgentSession.Repository repo;
    private final Core.Item user;
    private final Map<String,Core.Item> pending;
    private final boolean health;
    PersonalTools(AgentSession.Repository repo,Core.Item user,Map<String,Core.Item> pending,boolean context){this.repo=repo;this.user=user;this.pending=pending;health=context;}
    static Set<String> enabled(List<Core.Item> skills){Set<String> ids=new LinkedHashSet<>();for(Core.Item i:skills)if(i.data.optBoolean("enabled",true)&&!i.s("builtin_id").isEmpty())ids.add(i.s("builtin_id"));return ids;}
    private static JSONObject schema(String name,String description,String... fields){
        JSONObject props=new JSONObject();JSONArray required=new JSONArray();for(String field:fields){try{props.put(field,Core.obj("type","string"));}catch(JSONException e){throw new IllegalArgumentException(e);}required.put(field);}
        return Core.obj("type","function","function",Core.obj("name",name,"description",description,"parameters",Core.obj("type","object","properties",props,"required",required,"additionalProperties",false)));
    }
    JSONArray schemas(){
        JSONArray result=new JSONArray();Set<String> ids=enabled(repo.list("skill"));
        if(ids.contains("reminders")){
            String fields="title,body,start_at,timezone,repeat,evidence";
            String desc="本轮用户明确要求时保存提醒；start_at 为带 UTC 偏移的 ISO 日期时间，timezone 为 IANA，repeat 仅 once/daily/weekly。evidence 逐字引用本轮操作要求；成功回答后才生效。";
            result.put(schema("create_reminder",desc,fields.split(",")));
            result.put(schema("list_reminders","查看有效提醒和 ID。"));
            result.put(schema("update_reminder",desc+"完整替换指定 ID 的设置。",("id,"+fields).split(",")));
            result.put(schema("cancel_reminder","本轮用户明确要求时取消提醒。","id","evidence"));
        }
        if(health&&ids.contains("health")){
            result.put(schema("save_health_record","本轮用户明确要求记录时保存实际观察。category 仅 diet/sleep/symptom/activity/measurement，occurred_at 为带偏移的 ISO 时间，evidence 逐字引用本轮记录要求。不要记录建议或推测诊断。","subject","category","text","occurred_at","evidence"));
            result.put(schema("list_health_records","读取最近30条已保存健康观察，不能视为完整病历。"));
            result.put(schema("delete_health_record","用户明确要求时删除指定记录。","id","evidence"));
        }
        return result;
    }
    static boolean handles(String name){return Arrays.asList("create_reminder","list_reminders","update_reminder","cancel_reminder","save_health_record","list_health_records","delete_health_record").contains(name);}
    private List<Core.Item> items(String kind){Map<String,Core.Item> rows=new LinkedHashMap<>();for(Core.Item i:repo.list(kind))rows.put(i.id,i);for(Core.Item i:pending.values())if(i.kind.equals(kind))rows.put(i.id,i);return new ArrayList<>(rows.values());}
    private Core.Item find(String kind,String id){for(Core.Item i:items(kind))if(i.id.equals(id))return i;throw new IllegalArgumentException("记录不存在");}
    private void consent(JSONObject a,boolean health){String e=ReminderSchedule.field(a,"evidence",2000,false);String pattern=health?"(?is).*(记录|记下|保存|登记|删除|清除|移除|record|log|delete).*":"(?is).*(提醒|闹钟|定时|通知|设置|改成|改为|修改|调整|取消|删除|停止|remind|schedule|cancel).*";if(e.length()<2||!user.s("text").contains(e)||!e.matches(pattern))throw new IllegalArgumentException("需引用本轮用户明确的操作要求");}
    private String id(String name,JSONObject data){return UUID.nameUUIDFromBytes((user.id+"\n"+name+"\n"+data).getBytes(StandardCharsets.UTF_8)).toString();}
    JSONObject call(String name,JSONObject a)throws Exception{
        if(name.equals("list_reminders")||name.equals("list_health_records")){
            boolean reminders=name.equals("list_reminders");List<Core.Item> all=items(reminders?"reminder":"health_record");if(!reminders)Collections.reverse(all);
            JSONArray rows=new JSONArray();for(Core.Item i:all)if(!i.data.optBoolean("deleted")&&(!reminders||i.s("state").equals("active"))){JSONObject row=new JSONObject(i.data.toString());row.put("id",i.id);rows.put(row);if(rows.length()>=(reminders?100:30))break;}return Core.obj("items",rows);
        }
        boolean isHealth=name.contains("health");consent(a,isHealth);
        if(name.equals("cancel_reminder")||name.equals("delete_health_record")){
            Core.Item old=find(isHealth?"health_record":"reminder",ReminderSchedule.field(a,"id",100,false));JSONObject data=new JSONObject(old.data.toString());
            if(isHealth)data=Core.obj("deleted",true);else{data.put("state","cancelled");data.put("next_fire",0);}
            pending.put(old.id,new Core.Item(old.id,old.kind,"",old.created,data));return Core.obj("id",old.id,"status","暂存，最终回答成功后生效");
        }
        long stamp=System.currentTimeMillis();JSONObject data;
        if(isHealth){
            data=Core.obj("subject",ReminderSchedule.field(a,"subject",40,false),"category",ReminderSchedule.field(a,"category",20,false),"text",ReminderSchedule.field(a,"text",2000,false),"occurred_at",ReminderSchedule.field(a,"occurred_at",40,false));
            if(!Arrays.asList("diet","sleep","symptom","activity","measurement").contains(data.getString("category")))throw new IllegalArgumentException("健康记录类别无效");
            long at;try{at=OffsetDateTime.parse(data.getString("occurred_at")).toInstant().toEpochMilli();}catch(java.time.DateTimeException e){throw new IllegalArgumentException("观察时间需为带偏移的 ISO 时间");}if(at<=0||at>stamp+60000)throw new IllegalArgumentException("只能保存实际发生的健康观察");
            if(items("health_record").size()>=2000)throw new IllegalArgumentException("健康记录已达上限，请先整理");
        }else data=ReminderSchedule.validate(a,stamp);
        JSONObject identity=new JSONObject(a.toString());identity.remove("evidence");
        String kind=isHealth?"health_record":"reminder",ident=id(name,identity);Core.Item previous=null;
        if(name.equals("update_reminder")){previous=find(kind,ReminderSchedule.field(a,"id",100,false));ident=previous.id;}
        else for(Core.Item i:items(kind))if(i.id.equals(ident))return Core.obj("id",ident,"status","已有相同记录", "record",i.data);
        if(!isHealth&&(previous==null||!previous.s("state").equals("active"))&&items(kind).stream().filter(i->i.s("state").equals("active")).count()>=100)throw new IllegalArgumentException("有效提醒最多100个");
        data.put("revision",previous==null?1:previous.data.optInt("revision",1)+1);
        pending.put(ident,new Core.Item(ident,kind,"",previous==null?stamp:previous.created,data));
        return Core.obj("id",ident,"status","暂存，最终回答成功后保存", "record",data,"delivery",isHealth?"保存在本机，可在健康记录查看或删除":"本机通知需允许通知；精确时间需开启闹钟权限，系统限制可能延迟");
    }
}
