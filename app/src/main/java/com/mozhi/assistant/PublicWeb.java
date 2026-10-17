package com.mozhi.assistant;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Anonymous, bounded public-HTTPS reads. Separate client: never carries the model API key. */
final class PublicWeb implements WebClient.Transport {
    static final int MAX_BYTES=2*1024*1024;
    private final OkHttpClient client;
    private volatile Call active;
    private volatile boolean cancelled;
    PublicWeb(){client=new OkHttpClient.Builder().dns(PublicWeb::resolvePublic)
        .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build();}
    static HttpUrl url(String raw)throws IOException {
        if(raw==null||raw.length()>4096||raw.chars().anyMatch(c->c<32||c==127))throw new IOException("网页链接无效或过长。");
        HttpUrl u=HttpUrl.parse(raw.trim());
        if(u==null||!u.isHttps()||u.port()!=443||!u.username().isEmpty()||!u.password().isEmpty())
            throw new IOException("只支持公开的 HTTPS 网页链接。");
        String host=u.host().toLowerCase(Locale.ROOT);
        if(!host.contains(".")||host.endsWith(".")||host.endsWith(".local")||host.endsWith(".localhost")||host.endsWith(".internal")||host.endsWith(".lan"))
            throw new IOException("不支持访问本机或内网地址。");
        if(host.matches("[0-9.]+")||host.contains(":")){
            try{if(!isPublic(InetAddress.getByName(host)))throw new IOException("不支持访问本机或内网地址。");}
            catch(UnknownHostException e){throw new IOException("网页地址无效。");}
        }
        return u.newBuilder().fragment(null).build();
    }
    static boolean isPublic(InetAddress address) {
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] b=address.getAddress();int a=b[0]&255,c=b[1]&255;
        if(b.length==4)return !(a==0||a==10||a==127||a>=224||(a==100&&c>=64&&c<=127)||(a==169&&c==254)||(a==172&&c>=16&&c<=31)||(a==192&&(c==168||c==0||c==2))||(a==198&&(c==18||c==19||c==51))||(a==203&&c==0&&(b[2]&255)==113));
        // Public IPv6 unicast only; excludes local, mapped, translation, and documentation ranges.
        return b.length==16&&(a&0xe0)==0x20&&!(a==0x20&&c==1&&((b[2]&255)==0||(b[2]&255)==0x0d&&(b[3]&255)==0xb8))&&!(a==0x20&&c==2);
    }
    static List<InetAddress> resolvePublic(String host)throws UnknownHostException {
        List<InetAddress> addresses=Arrays.asList(InetAddress.getAllByName(host));
        for(InetAddress address:addresses)if(!isPublic(address))throw new UnknownHostException("不支持访问本机或内网地址。");
        return addresses;
    }
    public void cancel(){cancelled=true;Call c=active;if(c!=null)c.cancel();}
    private void check()throws InterruptedIOException {if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止联网");}
    public WebClient.Page get(String raw)throws IOException {
        HttpUrl next=url(raw);Set<String> seen=new HashSet<>();
        for(int n=0;n<=4;n++){
            check();if(!seen.add(next.toString()))throw new IOException("网页反复重定向，无法读取。");
            // Also validate the target when a device explicitly uses a system proxy.
            resolvePublic(next.host());
            Request request=new Request.Builder().url(next).header("User-Agent","Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36 Mozhi/0.5")
                .header("Accept","text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.1").get().build();
            Call call=client.newCall(request);active=call;
            try {
                check();
                try(Response r=call.execute()){
                    if(r.code()>=300&&r.code()<400){String location=r.header("Location");HttpUrl redirected=location==null?null:next.resolve(location);if(redirected==null)throw new IOException("网页跳转地址无效。");next=url(redirected.toString());continue;}
                    if(r.code()==401||r.code()==403)throw new IOException("网页需要登录或拒绝访问，请改用导出的聊天文件或粘贴文字。");
                    if(r.code()==429)throw new IOException("网站暂时限制访问，请稍后重试。");
                    if(!r.isSuccessful())throw new IOException("网页读取失败（HTTP "+r.code()+"）。");
                    ResponseBody body=r.body();if(body==null)throw new IOException("网页没有正文。");
                    if(body.contentLength()>MAX_BYTES)throw new IOException("网页超过 2 MB，请改用文件导入。");
                    String type=r.header("Content-Type","").toLowerCase(Locale.ROOT);
                    if(!(type.isEmpty()||type.startsWith("text/")||type.contains("json")||type.contains("xml")))throw new IOException("暂不支持读取此文件类型，请使用网页或聊天文本。");
                    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int count;
                    try(InputStream in=body.byteStream()){while((count=in.read(buf))!=-1){check();if(out.size()+count>MAX_BYTES)throw new IOException("网页超过 2 MB，请改用文件导入。");out.write(buf,0,count);}}
                    check();return new WebClient.Page(next.toString(),type,out.toByteArray());
                }
            }finally{active=null;}
        }
        throw new IOException("网页跳转次数过多，无法读取。");
    }
}
