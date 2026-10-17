package com.mozhi.assistant;

import android.test.InstrumentationTestCase;
import android.content.Context;
import org.json.*;
import java.util.*;

/** Device tests exercise SQLite + Android Keystore rather than simulated storage. */
public final class LocalStoreTest extends InstrumentationTestCase {
    public void testEncryptedPersistenceAndDeletion() throws Exception {
        Context context=getInstrumentation().getTargetContext();
        LocalStore first=new LocalStore(context);
        Core.Item memory=first.add("memory","",Core.obj("text","设备测试偏好","enabled",true,"global",true));
        String id=memory.id;first.close();
        LocalStore second=new LocalStore(context);Core.Item found=null;
        for(Core.Item i:second.list("memory",null))if(i.id.equals(id))found=i;
        assertNotNull(found);assertEquals("设备测试偏好",found.s("text"));
        try(android.database.Cursor cursor=second.getReadableDatabase().rawQuery("SELECT payload FROM entries WHERE id=?",new String[]{id})){
            assertTrue(cursor.moveToFirst());assertFalse(cursor.getString(0).contains("设备测试偏好"));
        }
        second.remove(found);for(Core.Item i:second.list("memory",null))assertFalse(id.equals(i.id));second.close();
    }
    public void testConversationDeletionCascadesButPreservesMemory() throws Exception {
        LocalStore s=new LocalStore(getInstrumentation().getTargetContext());
        Core.Item c=s.add("conversation","",Core.obj("title","测试对话"));s.add("message",c.id,Core.obj("role","user","text","测试消息"));
        Core.Item m=s.add("memory","",Core.obj("text","独立背景"));s.remove(c);
        assertTrue(s.list("message",c.id).isEmpty());assertTrue(s.list("memory",null).stream().anyMatch(i->i.id.equals(m.id)));s.remove(m);s.close();
    }
    public void testInvalidRestoreLeavesExistingRows() throws Exception {
        LocalStore s=new LocalStore(getInstrumentation().getTargetContext());Core.Item i=s.add("decision","",Core.obj("title","保留原数据"));
        JSONArray invalid=new JSONArray().put(Core.obj("id","x","kind","message","parent","missing","created",1,"data",Core.obj("role","user","text","错误")));
        try{s.restore(invalid);fail("Invalid restore must fail");}catch(Exception expected){}
        assertTrue(s.list("decision",null).stream().anyMatch(d->d.id.equals(i.id)));s.remove(i);s.close();
    }
    public void testEncryptedSettingsSurviveReopen() throws Exception {
        Context context=getInstrumentation().getTargetContext();LocalStore s=new LocalStore(context);s.putSetting("test-key","test-only-not-real-secret");s.close();
        LocalStore reopened=new LocalStore(context);assertEquals("test-only-not-real-secret",reopened.setting("test-key",""));
        assertFalse(context.getSharedPreferences("local_settings",Context.MODE_PRIVATE).getString("test-key","").contains("test-only"));reopened.putSetting("test-key","");reopened.close();
    }
}
