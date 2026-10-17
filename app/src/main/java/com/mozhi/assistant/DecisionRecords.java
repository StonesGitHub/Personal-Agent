package com.mozhi.assistant;

import java.util.*;
import org.json.*;

/** Evidence-backed decisions. Viewing a map is an action, never proof of a visit. */
public final class DecisionRecords {
    private DecisionRecords() {}
    public static final String OPENED_MAP="已打开地图";

    public static Core.Item fromConversation(Core.Item user,JSONObject args,List<Core.Item> existing,long now) throws JSONException {
        if(!"user".equals(user.s("role")))throw new IllegalArgumentException("决定必须来自用户表达");
        String title=field(args,"title",100),choice=field(args,"choice",1200),evidence=field(args,"evidence",800),status=field(args,"status",20);
        if(evidence.length()<2||!user.s("text").contains(evidence))throw new IllegalArgumentException("证据必须逐字来自本轮用户消息");
        String compact=evidence.replaceAll("[\\s，。！!,.]","");
        if(compact.matches("好|好的|好啊|嗯|嗯嗯|谢谢|收到|知道了|明白了|OK|ok")||evidence.contains("？")||evidence.contains("?"))
            throw new IllegalArgumentException("普通回应或问题不代表已做决定");
        if(!Arrays.asList("已决定","已完成","已放弃").contains(status))throw new IllegalArgumentException("决定状态无效");
        Object rawId=args.opt("replaces_id");if(!(rawId instanceof String)||((String)rawId).length()>100)throw new IllegalArgumentException("更新目标无效");
        String id=((String)rawId).trim();Core.Item old=null;
        for(Core.Item item:existing){
            if(!sameConversation(item,user.parent)||item.data.optBoolean("edited_by_user",false))continue;
            if(!id.isEmpty()?item.id.equals(id):item.s("source").equals("agent")&&item.s("title").equals(title))old=item;
        }
        if(!id.isEmpty()&&old==null)throw new IllegalArgumentException("只能更新本对话中尚未手动修改的决定");
        JSONObject value=Core.obj("title",title,"note",choice,"status",status,"outcome",old==null?"":old.s("outcome"),
            "source","agent","evidence",evidence,"source_turn",user.id,"source_conversation",user.parent,"updated",now);
        return revise(old,value,now);
    }

    public static Core.Item fromMap(Core.Item reply,MapLinks.Destination place,List<Core.Item> existing,long now) throws JSONException {
        if(!reply.kind.equals("message")||!reply.s("role").equals("assistant"))throw new IllegalArgumentException("缺少地点来源");
        Core.Item old=null;
        for(Core.Item item:existing)if(sameConversation(item,reply.parent)&&item.s("source").equals("map_action")
            &&item.s("source_reply").equals(reply.id)&&!item.data.optBoolean("edited_by_user",false))old=item;
        if(old!=null&&old.s("destination").equals(place.query()))return old;
        String title="查看地点："+place.name;title=title.substring(0,Math.min(100,title.length()));
        JSONObject value=Core.obj("title",title,"note",place.query()+"\n已把地点传给地图，尚未确认出行。",
            "status",OPENED_MAP,"outcome","","source","map_action","evidence","点击并打开地图："+place.query(),
            "source_reply",reply.id,"source_conversation",reply.parent,"destination",place.query(),"updated",now);
        return revise(old,value,now);
    }

    public static boolean sameConversation(Core.Item item,String conversation){
        return item.kind.equals("decision")&&!conversation.isEmpty()&&item.s("source_conversation").equals(conversation);
    }
    private static String field(JSONObject value,String key,int max){
        Object raw=value.opt(key);if(!(raw instanceof String))throw new IllegalArgumentException(key+" 必须为文字");
        String text=((String)raw).trim();if(text.isEmpty()||text.length()>max)throw new IllegalArgumentException(key+" 为空或过长");return text;
    }
    private static Core.Item revise(Core.Item old,JSONObject value,long now) throws JSONException {
        JSONArray revisions=new JSONArray();
        if(old!=null){
            if(old.s("title").equals(value.optString("title"))&&old.s("note").equals(value.optString("note"))&&old.s("status").equals(value.optString("status")))return old;
            JSONArray previous=old.data.optJSONArray("revisions");
            if(previous!=null)for(int n=Math.max(0,previous.length()-7);n<previous.length();n++)revisions.put(previous.get(n));
            revisions.put(Core.obj("title",old.s("title"),"note",old.s("note"),"status",old.s("status"),"evidence",old.s("evidence"),"updated",old.data.optLong("updated",old.created)));
        }
        value.put("revisions",revisions);
        return new Core.Item(old==null?UUID.randomUUID().toString():old.id,"decision","",old==null?now:old.created,value);
    }
}
