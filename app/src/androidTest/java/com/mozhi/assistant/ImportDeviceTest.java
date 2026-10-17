package com.mozhi.assistant;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.test.ActivityInstrumentationTestCase2;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Synthetic fixtures on a disposable emulator; exercises Keystore, SQLite, parsing and UI. */
public final class ImportDeviceTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public ImportDeviceTest(){super(MainActivity.class);}
    private MozhiApp app()throws Exception {MozhiApp a=(MozhiApp)getActivity().getApplication();for(int n=0;n<100&&a.store==null;n++)Thread.sleep(100);assertNotNull(a.store);return a;}
    public void testImportEncryptionDedupAndReopen()throws Exception {
        MozhiApp a=app();int memories=a.store.list("memory").size();
        List<ChatImport.Transcript> input=ChatImport.parse("用户：合成导入验收偏好\n助手：**先比较选项**，再选择。","设备测试");
        ChatImport.Plan p=a.store.importChats(input);assertEquals(1,p.added);assertEquals(2,p.messages);
        assertEquals(1,a.store.importChats(input).duplicates);assertEquals(memories,a.store.list("memory").size());
        LocalStore reopened=new LocalStore(getActivity());List<Core.Item> rows=reopened.list("message",p.conversations.get(0));
        assertEquals("user",rows.get(0).s("role"));assertEquals("assistant",rows.get(1).s("role"));assertTrue(rows.get(1).s("text").contains("**"));
        try(android.database.Cursor cursor=reopened.getReadableDatabase().rawQuery("SELECT payload FROM entries WHERE id=?",new String[]{rows.get(0).id})){
            assertTrue(cursor.moveToFirst());assertFalse(cursor.getString(0).contains("合成导入验收偏好"));}
        Core.Item c=null;for(Core.Item candidate:reopened.list("conversation"))if(candidate.id.equals(p.conversations.get(0)))c=candidate;
        assertNotNull(c);reopened.remove(c);reopened.close();
    }
    public void testAndroidParserAndHttpLibraries()throws Exception {
        String html="<article><h1>合成标题</h1><p>这是可读取的短正文。</p></article>";
        WebClient.Page p=new WebClient.Page("https://example.com/","text/html",html.getBytes(StandardCharsets.UTF_8));
        assertTrue(WebClient.pageData(p).getString("text").contains("短正文"));
        assertEquals("example.com",PublicWeb.url("https://example.com/").host());new WebClient().cancel();
        GatewayClient client=new GatewayClient("https://example.com","synthetic-device-key-1234567890123456");client.cancel();
    }
    public void testNativeImportPreviewCanConfirm()throws Throwable {
        MozhiApp a=app();MainActivity activity=getActivity();final String[] opened={null};ChatImportUi[] ui={null};
        List<ChatImport.Transcript> input=ChatImport.parse("用户：周末想轻松一点\n助手：**先散步，再找个地方坐坐。**","合成预览");
        runTestOnUiThread(()->{ui[0]=new ChatImportUi(activity,a,id->opened[0]=id);ui[0].preview(input);});getInstrumentation().waitForIdleSync();
        java.lang.reflect.Field field=ChatImportUi.class.getDeclaredField("preview");field.setAccessible(true);AlertDialog dialog=(AlertDialog)field.get(ui[0]);assertTrue(dialog.isShowing());
        runTestOnUiThread(()->{try{android.view.View view=dialog.getWindow().getDecorView();Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));try(FileOutputStream out=new FileOutputStream(new File(activity.getFilesDir(),"verify-import-preview.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();dialog.getButton(-1).performClick();}catch(Exception e){throw new AssertionError(e);}});
        for(int n=0;n<100&&opened[0]==null;n++){Thread.sleep(100);getInstrumentation().waitForIdleSync();}assertNotNull(opened[0]);assertEquals(2,a.store.list("message",opened[0]).size());
        for(Core.Item c:a.store.list("conversation"))if(c.id.equals(opened[0]))a.store.remove(c);
        runTestOnUiThread(()->ui[0].close());
    }
}
