package com.mozhi.assistant;

import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Independent social API. The model API key, chats, memory and location never enter this client. */
public class CommunityClient {
    public interface Identity {String token();void save(String token,String nickname);String nickname();}
    private final String base;
    private final Identity identity;
    public CommunityClient(String base,Identity identity){this.base=base.trim().replaceAll("/+$","");this.identity=identity;if(!this.base.isEmpty())endpoint(this.base,"");}
    public boolean available(){return !base.isEmpty();}
    public boolean localOnly(){return false;}
    public static String endpoint(String base,String path){
        try{URI u=new URI(base);if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)throw new Exception();return base.replaceAll("/+$","")+path;}
        catch(Exception e){throw new IllegalArgumentException("社区地址须使用 HTTPS");}
    }
    protected JSONObject request(String method,String path,JSONObject body,boolean authenticated)throws Exception{
        if(!available())throw new IOException("发现暂未开放，问答可以先保存为草稿");
        String token=identity.token();if(authenticated&&token.isEmpty())throw new IOException("社区身份尚未建立");
        HttpsURLConnection c=(HttpsURLConnection)new URL(endpoint(base,path)).openConnection();
        try{
            c.setConnectTimeout(12000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);c.setRequestProperty("Accept","application/json");
            if(!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);
            if(body!=null){byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}}
            int status=c.getResponseCode();if(status<200||status>=300)throw new IOException(switch(status){case 401->"社区身份已失效，请联系维护者恢复";case 403->"你没有权限进行此操作";case 404->"这条内容已不存在";case 409->"这次分享已提交，请先在发现中查看";case 422->"内容格式不符合要求，请检查字数";case 429->"操作有点频繁，请稍后再试";default->"发现暂时连接不上，请稍后重试";});
            return new JSONObject(ApiClient.read(c.getInputStream(),2*1024*1024));
        }finally{c.disconnect();}
    }
    private synchronized void identify()throws Exception{
        if(!identity.token().isEmpty())return;
        JSONObject result=request("POST","/v1/sessions",Core.obj("nickname",identity.nickname()),false);
        identity.save(result.getString("token"),result.getString("nickname"));
    }
    public JSONObject feed(long before)throws Exception{return request("GET","/v1/posts"+(before>0?"?before="+before:""),null,false);}
    public JSONObject myPosts(long before)throws Exception{if(identity.token().isEmpty())return Core.obj("items",new JSONArray(),"next_before",0);return request("GET","/v1/posts?mine=true"+(before>0?"&before="+before:""),null,true);}
    public JSONObject post(long id)throws Exception{return request("GET","/v1/posts/"+id,null,false);}
    public JSONObject publish(JSONObject pair)throws Exception{identify();return request("POST","/v1/posts",ReplySharing.payload(pair.getString("client_id"),pair.getString("question"),pair.getString("answer")),true);}
    public JSONObject like(long id,boolean liked)throws Exception{identify();return request("PUT","/v1/posts/"+id+"/like",Core.obj("liked",liked),true);}
    public JSONObject comments(long id,long before)throws Exception{return request("GET","/v1/posts/"+id+"/comments"+(before>0?"?before="+before:""),null,false);}
    public JSONObject comment(long id,String text,String clientId)throws Exception{identify();return request("POST","/v1/posts/"+id+"/comments",Core.obj("text",text.trim(),"client_id",clientId),true);}
    public void removePost(long id)throws Exception{identify();request("DELETE","/v1/posts/"+id,null,true);}
    public void removeComment(long id)throws Exception{identify();request("DELETE","/v1/comments/"+id,null,true);}
    public void report(long id,String kind)throws Exception{identify();request("POST","/v1/reports",Core.obj("target_id",id,"kind",kind,"reason","用户举报不适合公开展示的内容"),true);}
}
