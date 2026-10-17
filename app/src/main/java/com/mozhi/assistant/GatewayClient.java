package com.mozhi.assistant;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;

/** Optional web gateway; isolated from model and community credentials. */
final class GatewayClient {
    private final HttpUrl base;private final String token;private final OkHttpClient client;
    private volatile Call active;private volatile boolean cancelled;
    GatewayClient(String address,String token)throws IOException {
        this(address,token,false);
    }
    static GatewayClient auth(String address,String token)throws IOException{return new GatewayClient(address,token,true);}
    private GatewayClient(String address,String token,boolean anonymous)throws IOException {
        base=endpoint(address);this.token=token.trim();
        if(!(anonymous&&this.token.isEmpty())&&(this.token.length()<32||this.token.length()>256||!this.token.matches("[A-Za-z0-9_-]+")))throw new IOException("请填写有效的联网服务凭证。");
        client=new OkHttpClient.Builder().dns(PublicWeb::resolvePublic).followRedirects(false).followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES).connectTimeout(12,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).callTimeout(50,TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    }
    static HttpUrl endpoint(String raw)throws IOException {
        HttpUrl u=PublicWeb.url(raw);if(u.query()!=null||!u.encodedPath().equals("/"))throw new IOException("请填写 HTTPS 服务根地址，不含路径或查询参数。");return u;
    }
    void cancel(){cancelled=true;Call c=active;if(c!=null)c.cancel();}
    JSONObject request(String path,JSONObject body)throws Exception {
        if(token.isEmpty()&&!path.matches("/v1/auth/(code|verify)"))throw new IOException("此操作需要登录");
        if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止联网");
        PublicWeb.resolvePublic(base.host());
        int queryAt=path.indexOf('?');HttpUrl endpoint=base.newBuilder().encodedPath(queryAt<0?path:path.substring(0,queryAt)).encodedQuery(queryAt<0?null:path.substring(queryAt+1)).build();
        Request.Builder request=new Request.Builder().url(endpoint).header("Accept","application/json");
        if(!token.isEmpty())request.header("Authorization","Bearer "+token);
        if(body!=null)request.post(RequestBody.create(body.toString(),MediaType.get("application/json; charset=utf-8")));else request.get();
        return execute(request.build());
    }
    JSONObject upload(String path, String filename, byte[] bytes) throws Exception {
        if(cancelled || Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止上传");
        PublicWeb.resolvePublic(base.host());
        HttpUrl endpoint=base.newBuilder().encodedPath(path).build();
        RequestBody file=RequestBody.create(bytes, MediaType.get("application/octet-stream"));
        MultipartBody body=new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file",filename,file).build();
        Request request=new Request.Builder().url(endpoint).header("Authorization","Bearer "+token)
            .header("Accept","application/json").post(body).build();
        return execute(request);
    }
    private JSONObject execute(Request request) throws Exception {
        OkHttpClient current=request.body() instanceof MultipartBody
            ?client.newBuilder().readTimeout(110,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).build()
            :client;
        Call call=current.newCall(request);active=call;
        try {
            if(cancelled)throw new InterruptedIOException("已停止联网");
            try(Response response=call.execute()){
                ResponseBody responseBody=response.body();if(responseBody==null)throw new IOException("联网服务没有返回内容。");
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
                try(InputStream in=responseBody.byteStream()){while((n=in.read(buf))!=-1){if(out.size()+n>ChatImport.MAX_BYTES)throw new IOException("联网服务返回内容过大。");out.write(buf,0,n);}}
                if(!response.isSuccessful()){
                    if(response.code()==401)throw new IOException("联网服务凭证无效，请在设置中更新。");
                    String message="联网服务暂时不可用（HTTP "+response.code()+"）。";
                    try{Object detail=new JSONObject(out.toString("UTF-8")).opt("detail");if(detail instanceof String&&((String)detail).length()<200)message=(String)detail;}catch(JSONException ignored){}
                    throw new IOException(message);
                }
                JSONObject result=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
                if(cancelled)throw new InterruptedIOException("已停止联网");return result;
            }
        }finally{active=null;}
    }
}
