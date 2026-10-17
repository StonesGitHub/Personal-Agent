package com.mozhi.assistant;

import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

public class LocalCommunityTest {
    private static final class Records implements LocalCommunity.Storage {
        final Map<String,Core.Item> rows=new LinkedHashMap<>();
        public List<Core.Item> list(String kind){List<Core.Item> result=new ArrayList<>();for(Core.Item i:rows.values())if(i.kind.equals(kind))result.add(i);return result;}
        public void save(Core.Item item){rows.put(item.id,item);}
        public void remove(Core.Item item){rows.remove(item.id);if(item.kind.equals(LocalCommunity.SHARE))rows.values().removeIf(i->i.parent.equals(String.valueOf(LocalCommunity.number(item)))&&(i.kind.equals(LocalCommunity.COMMENT)||i.kind.equals(LocalCommunity.LIKE)));}
        JSONArray dump(){JSONArray out=new JSONArray();for(Core.Item i:rows.values())out.put(Core.obj("id",i.id,"kind",i.kind,"parent",i.parent,"created",i.created,"data",i.data));return out;}
    }
    private JSONObject pair(String question){return ReplySharing.payload(UUID.randomUUID().toString(),question,"**回答**\n\n只留一件想做的事。");}
    @Test public void samplesAreLabeledHaveNoFakeActivityAndDoNotWritePersonalData()throws Exception{
        Records data=new Records();LocalCommunity c=new LocalCommunity(data);assertTrue(c.available());assertTrue(c.localOnly());JSONArray samples=c.feed(0).getJSONArray("items");assertEquals(4,samples.length());for(int n=0;n<samples.length();n++){JSONObject p=samples.getJSONObject(n);assertTrue(p.getBoolean("demo"));assertFalse(p.getBoolean("mine"));assertEquals(0,p.getInt("likes"));assertEquals(0,p.getInt("comments"));assertEquals(0,p.getLong("created"));}assertTrue(data.rows.isEmpty());assertEquals(0,c.myPosts(0).getJSONArray("items").length());
    }
    @Test public void onlyEditedPairIsSavedAndRetryDoesNotDuplicate()throws Exception{
        Records data=new Records();LocalCommunity c=new LocalCommunity(data);JSONObject pair=pair("自己编辑的问题");pair.put("context","private memory");pair.put("api_key","private key");JSONObject saved=c.publish(pair);assertEquals(saved.getLong("id"),c.publish(pair).getLong("id"));assertEquals(1,data.rows.size());assertEquals(3,data.list(LocalCommunity.SHARE).get(0).data.length());assertFalse(data.dump().toString().contains("private"));assertTrue(saved.getBoolean("mine"));assertTrue(saved.getBoolean("local"));assertFalse(saved.getBoolean("demo"));assertEquals(saved.getLong("id"),c.feed(0).getJSONArray("items").getJSONObject(0).getLong("id"));pair.put("question","冲突的问题");try{c.publish(pair);fail();}catch(java.io.IOException expected){}assertEquals("自己编辑的问题",c.post(saved.getLong("id")).getString("question"));
    }
    @Test public void localInteractionsSurviveAClientRecreationAndCancelCleanly()throws Exception{
        Records data=new Records();LocalCommunity c=new LocalCommunity(data);c.like(4,true);c.like(4,true);String key=UUID.randomUUID().toString();long comment=c.comment(4,"猫猫批准午睡",key).getLong("id");assertEquals(comment,c.comment(4,"猫猫批准午睡",key).getLong("id"));c=new LocalCommunity(data);assertEquals(1,c.post(4).getInt("likes"));assertEquals(1,c.post(4).getInt("comments"));assertEquals("猫猫批准午睡",c.comments(4,0).getJSONArray("items").getJSONObject(0).getString("text"));c.like(4,false);c.like(4,false);c.removeComment(comment);assertEquals(0,c.post(4).getInt("likes"));assertEquals(0,c.post(4).getInt("comments"));
    }
    @Test public void invalidCommentsAndCrossPostRetryNeverWrite()throws Exception{
        Records data=new Records();LocalCommunity c=new LocalCommunity(data);for(String text:new String[]{" ","a".repeat(2001)})try{c.comment(4,text,UUID.randomUUID().toString());fail();}catch(IllegalArgumentException expected){}assertTrue(data.rows.isEmpty());String key=UUID.randomUUID().toString();c.comment(4,"只发一次",key);try{c.comment(3,"只发一次",key);fail();}catch(java.io.IOException expected){}assertEquals(0,c.post(3).getInt("comments"));try{c.like(999,true);fail();}catch(java.io.IOException expected){}assertEquals(1,data.rows.size());
    }
    @Test public void paginationDoesNotRepeatWhenANewShareArrivesAndMineExcludesSamples()throws Exception{
        LocalCommunity c=new LocalCommunity(new Records());for(int n=0;n<23;n++)c.publish(pair("问题 "+n));JSONObject first=c.feed(0);assertEquals(20,first.getJSONArray("items").length());Set<Long> ids=new HashSet<>();for(int n=0;n<20;n++)ids.add(first.getJSONArray("items").getJSONObject(n).getLong("id"));c.publish(pair("后来加入"));JSONObject rest=c.feed(first.getLong("next_before"));assertEquals(7,rest.getJSONArray("items").length());for(int n=0;n<7;n++)assertTrue(ids.add(rest.getJSONArray("items").getJSONObject(n).getLong("id")));assertEquals(0,rest.getLong("next_before"));JSONObject own=c.myPosts(0);JSONArray older=c.myPosts(own.getLong("next_before")).getJSONArray("items");assertEquals(4,older.length());for(int n=0;n<older.length();n++)assertTrue(older.getJSONObject(n).getBoolean("mine"));
    }
    @Test public void localSharingAndSampleInteractionsRoundTripInEncryptedBackup()throws Exception{
        Records data=new Records();LocalCommunity c=new LocalCommunity(data);long id=c.publish(pair("本机分享备份")).getLong("id");c.like(id,true);c.comment(id,"自己的评论",UUID.randomUUID().toString());c.like(4,true);c.comment(4,"示例下的评论",UUID.randomUUID().toString());String encrypted=Backup.encrypt(data.dump(),"test-only-password".toCharArray());assertEquals(4,new JSONObject(encrypted).getInt("version"));assertFalse(encrypted.contains("自己的评论"));Records restored=new Records();for(Core.Item item:Backup.validate(Backup.decrypt(encrypted,"test-only-password".toCharArray())))restored.save(item);LocalCommunity after=new LocalCommunity(restored);assertEquals(1,after.myPosts(0).getJSONArray("items").length());assertEquals(1,after.post(id).getInt("likes"));assertEquals(1,after.post(id).getInt("comments"));assertTrue(after.post(4).getBoolean("liked"));assertEquals(4,after.feed(0).getJSONArray("items").length()-after.myPosts(0).getJSONArray("items").length());
    }
    @Test public void backupRejectsOrphanInteractionAndDuplicateOrHiddenPayload()throws Exception{
        Records data=new Records();LocalCommunity c=new LocalCommunity(data);long id=c.publish(pair("本机问答")).getLong("id");c.comment(id,"评论",UUID.randomUUID().toString());JSONArray rows=data.dump();JSONArray orphan=new JSONArray().put(rows.getJSONObject(1));try{Backup.validate(orphan);fail();}catch(Exception expected){}rows.getJSONObject(0).getJSONObject("data").put("context","must not be shared");try{Backup.validate(rows);fail();}catch(Exception expected){}
    }
    @Test public void removingOwnShareLeavesSamplesAndTheirInteractionsIntact()throws Exception{
        Records data=new Records();LocalCommunity c=new LocalCommunity(data);long id=c.publish(pair("本机删除")).getLong("id");c.like(id,true);c.comment(id,"评论",UUID.randomUUID().toString());c.like(4,true);c.removePost(id);assertEquals(0,c.myPosts(0).getJSONArray("items").length());assertEquals(0,data.list(LocalCommunity.COMMENT).size());assertEquals(1,data.list(LocalCommunity.LIKE).size());assertTrue(c.post(4).getBoolean("liked"));try{c.removePost(4);fail();}catch(java.io.IOException expected){}try{c.report(4,"post");fail();}catch(java.io.IOException expected){}assertEquals(4,c.feed(0).getJSONArray("items").length());
    }
}
