package com.mozhi.assistant;

import org.json.*;
import java.io.IOException;
import java.util.UUID;

/** Durable remote runs: submitting is idempotent, polling never creates another task. */
final class RemoteAgentClient {
    private final GatewayClient gateway;
    RemoteAgentClient(String base,String token)throws IOException{gateway=new GatewayClient(base,token);}
    JSONObject request(String path,JSONObject value)throws Exception{try{return gateway.request(path,value);}catch(IOException e){throw new IOException(e.getMessage()==null?"Agent 服务连接失败":e.getMessage().replace("联网服务","Agent 服务"),e);}}
    void disconnect(){gateway.cancel();}
    JSONObject upload(android.content.Context context,JSONObject file)throws Exception{
        return gateway.upload("/v1/agent/attachments",file.getString("name"),ChatAttachments.read(context,file));
    }
    static JSONObject payload(String conversation,JSONArray messages,boolean allowWeb)throws JSONException{
        return payload(conversation,messages,allowWeb,new JSONArray(java.util.Arrays.asList("travel","reminders","health")));
    }
    static JSONObject payload(String conversation,JSONArray messages,boolean allowWeb,JSONArray skills)throws JSONException{
        return payload(conversation,messages,allowWeb,skills,true);
    }
    static JSONObject payload(String conversation,JSONArray messages,boolean allowWeb,JSONArray skills,boolean allowPersonal)throws JSONException{
        JSONArray history=new JSONArray();StringBuilder background=new StringBuilder();
        for(int n=0;n<messages.length();n++){
            JSONObject m=messages.getJSONObject(n);String role=m.optString("role");
            if(role.equals("system"))background.append(m.optString("content")).append('\n');
            else if(role.equals("user")||role.equals("assistant"))history.put(Core.obj("role",role,"content",m.optString("content")));
        }
        if(background.length()>16000)background.setLength(16000);
        String zone=java.time.ZoneId.systemDefault().getId();
        // Java fixed-offset IDs are not IANA names. UTC provides an explicit valid reference in that case.
        if(zone.startsWith("GMT+")||zone.startsWith("GMT-")||zone.startsWith("UTC+")||zone.startsWith("UTC-")||zone.startsWith("+")||zone.startsWith("-"))zone="UTC";
        return Core.obj("client_request_id",UUID.randomUUID().toString(),"conversation_id",conversation,"messages",history,"background",background.toString(),"allow_web",allowWeb,"builtin_skills",skills,"timezone",zone,"allow_personal_data",allowPersonal);
    }
    static String status(JSONObject run){return switch(run.optString("state")){
        case "queued" -> "任务已保存 · 等待执行";
        case "running" -> "Agent 正在执行 · "+run.optInt("rounds")+" 轮 · "+run.optInt("calls")+" 次工具调用";
        case "completed" -> "任务完成";case "cancelled" -> "任务已取消";
        case "interrupted" -> "服务端任务中断 · 可继续";default -> "任务失败";};}
    static String answer(JSONObject run)throws JSONException{
        StringBuilder answer=new StringBuilder(run.optString("output"));JSONArray artifacts=run.optJSONArray("artifacts");
        if(artifacts!=null)for(int n=0;n<artifacts.length();n++){
            JSONObject a=artifacts.getJSONObject(n);String link="mozhi://artifact?id="+a.getString("id");
            if(answer.indexOf(link)<0)answer.append("\n\n[").append(a.getString("name").replace("[", "").replace("]", "")).append("](").append(link).append(")");
        }
        return answer.toString();
    }
}
