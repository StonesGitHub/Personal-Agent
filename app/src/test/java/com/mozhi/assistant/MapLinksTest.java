package com.mozhi.assistant;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Collections;
import static org.junit.Assert.*;

public class MapLinksTest {
    private JSONObject places() { return Core.obj("places",new JSONArray().put(Core.obj("name","东郊记忆","city","成都","address","成华区建设南支路4号"))); }
    private String param(String url,String name) throws Exception {
        String raw=url.substring(url.indexOf('?')+1);
        for(String s:raw.split("&")) {String[] pair=s.split("=",2);if(pair[0].equals(name))return URLDecoder.decode(pair[1],"UTF-8");}
        return null;
    }
    @Test public void unicodeAndReservedCharactersCannotBecomeNewParameters() throws Exception {
        MapLinks.Destination d=new MapLinks.Destination("A&B + Cafe #1 (东门)","示例城市","园区1号/2层");
        MapLinks.Destination restored=MapLinks.parse(d.url());
        assertNotNull(restored);assertEquals(d.name,restored.name);assertEquals(d.query(),restored.query());
        assertEquals(d.query(),param(d.amapUri(),"keyword"));assertEquals(d.query(),param(d.geoUri(),"q"));
        assertEquals(3,new URI(d.url()).getRawQuery().split("&").length);assertNull(new URI(d.url()).getRawFragment());
    }
    @Test public void everyProviderReceivesCityBranchAndKnownAddress() throws Exception {
        MapLinks.Destination d=new MapLinks.Destination("同名咖啡（东门店）","成都","建设南路12号");
        assertEquals("成都 建设南路12号 同名咖啡（东门店）",param(d.amapUri(),"keyword"));
        assertEquals(d.query(),param(d.baiduUri(),"destination"));assertEquals("成都",param(d.baiduUri(),"destination_region"));
        assertEquals("我的位置",param(d.baiduUri(),"origin"));assertEquals(d.query(),param(d.webUri(),"keyword"));
        assertFalse(d.amapUri().contains("lat="));assertFalse(d.amapUri().contains("lon="));
    }
    @Test public void forgedLinksAreRejectedInsteadOfLaunchingIntents() {
        String[] invalid={null,"intent://map#Intent;scheme=tel;end","file:///private","javascript:alert(1)",
            "mozhi://map?name=","mozhi://evil?name=公园","mozhi://map/path?name=公园","mozhi://user@map?name=公园",
            "mozhi://map:42?name=公园","mozhi://map?name=公园#fragment","mozhi://map?name=公园&name=车站",
            "mozhi://map?name=公园&package=evil.app","mozhi://map?name=%GG","mozhi://map?name=公园%0A车站",
            "mozhi://map?name="+"字".repeat(121),"geo:0,0?q=公园&mode=evil","geo:91,181?q=公园"};
        for(String s:invalid)assertNull("accepted: "+s,MapLinks.parse(s));
    }
    @Test public void standardGeoTextLinksKeepEncodedQuery() {
        MapLinks.Destination d=MapLinks.parse("geo:0,0?q="+MapLinks.encode("成都 A&B + 咖啡"));
        assertNotNull(d);assertEquals("成都 A&B + 咖啡",d.query());
        assertNotNull(MapLinks.parse("mozhi://map?name=东郊记忆&city=成都"));
    }
    @Test public void olderAmapLinksAreRebuiltFromDestinationTextOnly(){
        MapLinks.Destination d=MapLinks.parse("https://uri.amap.com/search?keyword=A%26B&city=成都&callnative=1");assertNotNull(d);assertEquals("成都 A&B",d.query());assertFalse(d.url().contains("callnative"));
        assertNotNull(MapLinks.parse("androidamap://keywordNavi?keyword=公园&sourceApplication=unknown"));
        assertNull(MapLinks.parse("https://uri.amap.com.evil/search?keyword=公园"));assertNull(MapLinks.parse("https://user@uri.amap.com/search?keyword=公园"));
    }
    @Test public void baiduDestinationRemainsAPlainName() throws Exception {
        MapLinks.Destination d=new MapLinks.Destination("name:咖啡|latlng:0,0","成都","");
        String destination=param(d.baiduUri(),"destination");
        assertFalse(destination.contains("|"));assertFalse(destination.contains(":"));assertTrue(destination.contains("咖啡"));
    }
    @Test public void batchLinksAreValidatedAndMarkdownLabelsEscaped() throws Exception {
        JSONObject result=MapLinks.create(places());JSONObject link=result.getJSONArray("places").getJSONObject(0);
        assertEquals("东郊记忆",MapLinks.parse(link.getString("url")).name);
        assertTrue(link.getString("markdown").contains(link.getString("url")));
        assertTrue(new MapLinks.Destination("Cafe [A] *B*","","").markdown().startsWith("[Cafe \\[A\\] \\*B\\* ↗]"));
        for(int size:new int[]{0,11}){JSONArray a=new JSONArray();for(int n=0;n<size;n++)a.put(Core.obj("name","公园","city","成都","address",""));try{MapLinks.create(Core.obj("places",a));fail();}catch(IllegalArgumentException expected){}}
    }
    @Test public void mapToolWorksWithoutReadingOrWritingPersonalContext() throws Exception {
        Core.Item user=new Core.Item("t","message","c",1,Core.obj("text","推荐成都的地点"));
        AgentSession session=new AgentSession(k->{throw new AssertionError("personal data read");},user,false,false);
        assertTrue(session.instructions().contains("本轮已关闭长期背景"));
        assertFalse(session.call("map_links",places()).has("error"));assertTrue(session.changes().isEmpty());
        assertTrue(session.call("history_search",Core.obj("query","成都")).has("error"));
    }
    @Test public void malformedToolFieldsAreRejected() throws Exception {
        for(Object value:new Object[]{JSONObject.NULL,42,new JSONObject(),new JSONArray()}){
            JSONObject row=Core.obj("name",value,"city","成都","address","");
            try{MapLinks.create(Core.obj("places",new JSONArray().put(row)));fail();}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void agentCanUseToolResultAsClickableFinalAnswer() throws Exception {
        Core.Item user=new Core.Item("t","message","c",1,Core.obj("role","user","text","成都周末去哪儿"));
        AgentSession session=new AgentSession(k->Collections.emptyList(),user,true,false);
        JSONArray messages=Core.messages(Collections.singletonList(user),Collections.emptyList());
        int[] calls={0};String answer=AgentEngine.run(messages,(m,t,last)->{
            if(calls[0]++==0)return Core.obj("content",JSONObject.NULL,"tool_calls",new JSONArray().put(Core.obj("id","map1","type","function","function",Core.obj("name","map_links","arguments",places().toString()))));
            JSONObject observation=new JSONObject(m.getJSONObject(m.length()-1).getString("content"));
            return Core.obj("content","可以考虑 "+observation.getJSONArray("places").getJSONObject(0).getString("markdown"));
        },session,new AgentEngine.Control(){public void check(){}public void progress(String m){}});
        assertTrue(answer.contains("mozhi://map?"));assertEquals(2,calls[0]);assertTrue(session.changes().isEmpty());
        assertTrue(messages.getJSONObject(0).getString("content").contains("不得编造门牌"));
    }
}
