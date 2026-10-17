package com.mozhi.assistant;

import org.json.*;
import java.util.*;

/** Bounded, cancellable model -> tool -> observation loop. No tools execute outside the allowlist. */
public final class AgentEngine {
    public interface Model { JSONObject complete(JSONArray messages,JSONArray tools,boolean finalOnly) throws Exception; }
    public interface Toolbox { JSONArray schemas(); JSONObject call(String name,JSONObject args) throws Exception; }
    public interface Control { void check() throws Exception; void progress(String message); }
    public static final int MAX_ROUNDS=6, MAX_CALLS=12;
    public static String run(JSONArray messages,Model model,Toolbox tools,Control control) throws Exception {
        int calls=0;Set<String> ids=new HashSet<>();JSONArray schemas=tools.schemas();
        for(int round=0;round<MAX_ROUNDS;round++){
            control.check();boolean finalOnly=round==MAX_ROUNDS-1||calls>=MAX_CALLS;
            control.progress("正在思考 · 第 "+(round+1)+" 轮");
            JSONObject reply=model.complete(messages,schemas,finalOnly);control.check();
            JSONArray tc=reply.optJSONArray("tool_calls");
            if(tc==null||tc.length()==0){String answer=reply.isNull("content")?"":reply.optString("content","").trim();if(answer.isEmpty())throw new java.io.IOException("模型未返回有效回答，请更换支持工具调用的模型或重试。");return answer;}
            if(finalOnly||tc.length()>MAX_CALLS-calls)throw new java.io.IOException("本次 Agent 已达到调用上限，请缩小任务后重试。本轮记忆、技能和决定未保存。");
            // IDs must be unique within a batch; some compatible providers reuse IDs across rounds.
            ids.clear();
            // Validate the complete batch before executing any tool.
            for(int n=0;n<tc.length();n++){
                JSONObject t=tc.getJSONObject(n);String id=t.getString("id");
                if(id.isEmpty()||id.length()>200||!ids.add(id)||!"function".equals(t.getString("type")))throw new JSONException("Invalid tool call");
                if(t.getJSONObject("function").getString("arguments").length()>20000)throw new JSONException("Tool arguments too long");
            }
            JSONObject normalized=Core.obj("role","assistant","content",reply.isNull("content")?JSONObject.NULL:reply.optString("content",""),"tool_calls",tc);if(reply.has("reasoning_content"))normalized.put("reasoning_content",reply.get("reasoning_content"));messages.put(normalized);
            for(int n=0;n<tc.length();n++){
                control.check();JSONObject t=tc.getJSONObject(n),f=t.getJSONObject("function");JSONObject result;
                try{result=tools.call(f.getString("name"),new JSONObject(f.getString("arguments")));}
                catch(IllegalArgumentException|JSONException e){result=Core.obj("error","参数无效："+e.getMessage());}
                control.check();messages.put(Core.obj("role","tool","tool_call_id",t.getString("id"),"content",result.toString()));calls++;
            }
        }
        throw new java.io.IOException("Agent 未能在限制内完成任务。");
    }
}
