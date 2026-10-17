package com.mozhi.assistant;

import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

public class BackupTest {
    private JSONArray fixture(){return new JSONArray().put(Core.obj("id","a","kind","conversation","parent","","created",1,"data",Core.obj("title","家庭计划"))).put(Core.obj("id","b","kind","message","parent","a","created",2,"data",Core.obj("role","user","text","我的私密消息")));}
    @Test public void encryptedBackupRoundtrip() throws Exception {String encrypted=Backup.encrypt(fixture(),"test-password".toCharArray());assertFalse(encrypted.contains("私密消息"));assertEquals(fixture().toString(),Backup.decrypt(encrypted,"test-password".toCharArray()).toString());}
    @Test public void wrongPasswordCannotRestore() throws Exception {String encrypted=Backup.encrypt(fixture(),"test-password".toCharArray());try{Backup.decrypt(encrypted,"wrong-password".toCharArray());fail();}catch(javax.crypto.AEADBadTagException expected){}}
    @Test public void tamperingCannotRestore() throws Exception {JSONObject e=new JSONObject(Backup.encrypt(fixture(),"test-password".toCharArray()));byte[] bytes=Base64.getDecoder().decode(e.getString("ciphertext"));bytes[0]^=1;e.put("ciphertext",Base64.getEncoder().encodeToString(bytes));try{Backup.decrypt(e.toString(),"test-password".toCharArray());fail();}catch(javax.crypto.AEADBadTagException expected){}}
    @Test public void duplicateIDsRejected() throws Exception {JSONArray rows=fixture();rows.put(rows.getJSONObject(0));try{Backup.validate(rows);fail();}catch(Exception expected){assertTrue(expected.getMessage().contains("重复"));}}
    @Test public void orphanMessageRejected() throws Exception {JSONArray rows=fixture();rows.getJSONObject(1).put("parent","missing");try{Backup.validate(rows);fail();}catch(Exception expected){assertTrue(expected.getMessage().contains("所属"));}}
    @Test public void invalidVersionRejected() throws Exception {JSONObject e=new JSONObject(Backup.encrypt(fixture(),"test-password".toCharArray()));e.put("version",999);try{Backup.decrypt(e.toString(),"test-password".toCharArray());fail();}catch(Exception expected){assertTrue(expected.getMessage().contains("格式"));}}
}
