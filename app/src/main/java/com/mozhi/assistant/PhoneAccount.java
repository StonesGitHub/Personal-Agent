package com.mozhi.assistant;

import org.json.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/** Phone identity is tied to the existing encrypted vault; never relabel another user's records. */
final class PhoneAccount {
    static JSONObject record(LocalStore store,String key){try{return new JSONObject(store.setting(key,"{}"));}catch(Exception e){throw new IllegalStateException("账号资料无法读取，请勿清空应用数据",e);}}
    static boolean bound(LocalStore s){return !record(s,"phone_account").optString("id").isEmpty();}
    static boolean locked(LocalStore s){return bound(s)&&(record(s,"phone_session").optString("token").isEmpty()||record(s,"phone_session").optLong("expires_at")<=System.currentTimeMillis());}
    static String label(LocalStore s){return record(s,"phone_account").optString("phone_masked","手机号注册 / 登录");}
    static String token(LocalStore s){return bound(s)?(locked(s)?"":record(s,"phone_session").optString("token")):s.setting("agent_token","");}
    static String base(LocalStore s){return bound(s)?record(s,"phone_account").optString("base"):s.setting("agent_base","");}
    static void accept(LocalStore s,String base,JSONObject response)throws Exception {
        JSONObject user=response.getJSONObject("user"),previous=record(s,"phone_account");
        if(bound(s)&&(!previous.getString("id").equals(user.getString("id"))||!previous.getString("base").equals(base)))throw new IOException("本机资料属于另一账号，请使用原手机号和服务地址登录。切换账号前请导出备份并清空本机资料。");
        if(!user.optBoolean("phone_verified")||response.getString("token").length()<32)throw new IOException("手机号验证未完成");
        // The guard is saved first: a interrupted write locks the vault instead of exposing it.
        s.putSetting("phone_account",Core.obj("id",user.getString("id"),"base",base,"phone_masked",user.getString("phone_masked")).toString());
        s.putSetting("phone_session",Core.obj("token",response.getString("token"),"expires_at",response.getLong("expires_at")).toString());
    }
    static JSONArray clean(JSONArray input)throws Exception {
        JSONArray rows=new JSONArray(input.toString());
        for(int n=0;n<rows.length();n++){
            JSONObject row=rows.getJSONObject(n),data=row.getJSONObject("data");
            data.remove("remote_payload");data.remove("pending_attachments");
            if(row.getString("kind").equals("message")&&Arrays.asList("pending","interrupted").contains(data.optString("status")))data.put("status","cancelled");
            if(row.getString("kind").equals("reminder")){data.put("state","paused");data.put("next_fire",0);}
        }
        Backup.validate(rows);return rows;
    }
    static List<JSONArray> batches(JSONArray rows)throws Exception {
        List<JSONArray> out=new ArrayList<>();JSONArray part=new JSONArray();int bytes=2;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);int size=row.toString().getBytes(StandardCharsets.UTF_8).length+1;
            if(size>230000)throw new IOException("单条记录过大，请先导出文件备份");
            if(part.length()>0&&(part.length()>=100||bytes+size>230000)){out.add(part);part=new JSONArray();bytes=2;}
            part.put(row);bytes+=size;
        }
        if(part.length()>0)out.add(part);return out;
    }
    static int upload(LocalStore s)throws Exception {
        if(!bound(s)||locked(s))throw new IOException("请先登录手机号账号");
        JSONArray rows=clean(s.dump());if(rows.length()==0)return 0;
        String source=s.setting("account_source","");if(source.isEmpty()){source=UUID.randomUUID().toString();s.putSetting("account_source",source);}
        String ident=UUID.randomUUID().toString().replace("-","");GatewayClient client=new GatewayClient(base(s),token(s));
        List<JSONArray> parts=batches(rows);
        for(int i=0;i<parts.size();i++)client.request("/v1/account/archives/"+ident+"/chunks",Core.obj("source",source,"seq",i,"items",parts.get(i)));
        JSONObject result=client.request("/v1/account/archives/"+ident+"/complete",Core.obj("chunks",parts.size(),"total",rows.length()));
        if(!result.optBoolean("ok")||result.optInt("total")!=rows.length())throw new IOException("服务端尚未确认导入，请稍后重试");
        s.putSetting("account_last_import",String.valueOf(System.currentTimeMillis()));return rows.length();
    }
    static int restore(LocalStore s,String ident)throws Exception {
        if(!ident.matches("[a-f0-9]{32}"))throw new IOException("备份编号无效");
        JSONArray all=new JSONArray();GatewayClient client=new GatewayClient(base(s),token(s));int bytes=0;
        for(int seq=0;seq<=50000;seq++){
            JSONObject response=client.request("/v1/account/archives/"+ident+"/chunks?seq="+seq,null);JSONArray rows=response.getJSONArray("items");
            if(rows.length()==0)break;
            bytes+=rows.toString().getBytes(StandardCharsets.UTF_8).length;
            if(bytes>24*1024*1024||all.length()+rows.length()>50000)throw new IOException("备份过大");
            for(int i=0;i<rows.length();i++)all.put(rows.getJSONObject(i));
        }
        return s.mergeArchive(clean(all));
    }
}
