package com.mozhi.assistant;

import android.app.*;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.test.ActivityInstrumentationTestCase2;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import org.json.*;
import java.io.*;

/** Native UI checks: isolated remote fixtures plus the real offline discovery and encrypted store. */
public final class DiscoveryDeviceTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public DiscoveryDeviceTest(){super(MainActivity.class);}
    private View find(View root,String description){if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int n=0;n<((ViewGroup)root).getChildCount();n++){View v=find(((ViewGroup)root).getChildAt(n),description);if(v!=null)return v;}return null;}
    private View content(){return getActivity().getWindow().getDecorView();}
    private void tap(String description)throws Throwable{View[] target={null};for(int n=0;n<100;n++){runTestOnUiThread(()->target[0]=find(content(),description));if(target[0]!=null)break;Thread.sleep(100);}assertNotNull("Missing "+description,target[0]);runTestOnUiThread(()->target[0].performClick());getInstrumentation().waitForIdleSync();}
    private AccessibilityNodeInfo node(AccessibilityNodeInfo root,String label){if(root==null)return null;if(label.contentEquals(root.getText()==null?"":root.getText())||label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;for(int n=0;n<root.getChildCount();n++){AccessibilityNodeInfo found=node(root.getChild(n),label);if(found!=null)return found;}return null;}
    private AccessibilityNodeInfo awaitNode(String label)throws Exception{for(int n=0;n<60;n++){AccessibilityNodeInfo found=node(getInstrumentation().getUiAutomation().getRootInActiveWindow(),label);if(found!=null)return found;Thread.sleep(100);}throw new AssertionError("Missing window control: "+label);}
    private void clickWindow(String label)throws Exception{AccessibilityNodeInfo found=awaitNode(label);while(found!=null&&!found.isClickable())found=found.getParent();assertNotNull(found);assertTrue(found.performAction(AccessibilityNodeInfo.ACTION_CLICK));getInstrumentation().waitForIdleSync();}
    private void awaitReadyWindow()throws Exception{
        // A cold software-emulated device can show this OS dialog while boot services settle.
        // Only acknowledge the observed System UI warning; never dismiss an app ANR.
        for(int n=0;n<100;n++){
            AccessibilityNodeInfo active=getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if(node(active,"新建对话")!=null)return;
            if(node(active,"System UI isn't responding")!=null)clickWindow("Wait");
            Thread.sleep(200);
        }
        capture("040-home");StringBuilder labels=new StringBuilder();windowLabels(getInstrumentation().getUiAutomation().getRootInActiveWindow(),labels);
        throw new AssertionError("App window not ready: "+labels);
    }
    private void windowLabels(AccessibilityNodeInfo root,StringBuilder out){if(root==null||out.length()>1500)return;if(root.getText()!=null)out.append(root.getText()).append(" | ");if(root.getContentDescription()!=null)out.append(root.getContentDescription()).append(" | ");for(int n=0;n<root.getChildCount();n++)windowLabels(root.getChild(n),out);}
    private void capture(String name)throws Exception{getInstrumentation().waitForIdleSync();Thread.sleep(1000);Bitmap image=getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);try(FileOutputStream out=new FileOutputStream(new File(getActivity().getFilesDir(),"verify-"+name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
    private void navFits()throws Throwable{runTestOnUiThread(()->{int right=0;for(String label:new String[]{"墨聊","发现","决定","我"}){View v=find(content(),label);assertNotNull(v);int[] xy=new int[2];v.getLocationOnScreen(xy);assertTrue(v.getWidth()>=48);assertTrue(xy[0]>=right);right=xy[0]+v.getWidth();assertTrue(right<=content().getWidth());}});}
    private void commentFitsAboveSystemNavigation()throws Exception{android.graphics.Rect bounds=new android.graphics.Rect();awaitNode("发送评论").getBoundsInScreen(bounds);android.util.DisplayMetrics screen=new android.util.DisplayMetrics();getActivity().getWindowManager().getDefaultDisplay().getRealMetrics(screen);int id=getActivity().getResources().getIdentifier("navigation_bar_height","dimen","android");int inset=id==0?0:getActivity().getResources().getDimensionPixelSize(id);assertTrue("Comment action overlaps system navigation: "+bounds,bounds.bottom<=screen.heightPixels-inset);}
    private static final class FixtureCommunity extends CommunityClient {
        final JSONArray posts=new JSONArray();int likes,commentCount;
        FixtureCommunity(){super("",null);posts.put(Core.obj("id",1,"nickname","墨友小满 · 演示","question","怎样把普通周末，过成一个小小的假期？","answer","**只安排一件真正想做的事。**\n\n走一条没走过的小路，带一本读到一半的书，或者给自己做一顿慢慢吃的早餐。\n\n其他时间留白，不必把休息也变成任务。","created",System.currentTimeMillis()-1200000,"likes",12,"comments",2,"liked",false,"mine",false));posts.put(Core.obj("id",2,"nickname","阿墨 · 演示","question","如果我的猫是项目经理，它会怎么开周会？","answer","今日议程：\n\n1. 检查所有纸箱是否符合入住标准。\n2. 将激光笔列为高优先级项目。\n3. 全员午睡，会议结束。","created",System.currentTimeMillis()-3600000,"likes",28,"comments",5,"liked",false,"mine",false));}
        private JSONObject clone(JSONObject item){try{return new JSONObject(item.toString());}catch(JSONException e){throw new RuntimeException(e);}}
        @Override public boolean available(){return true;}
        @Override public JSONObject feed(long before){return Core.obj("items",posts,"next_before",0);}
        @Override public JSONObject post(long id){return clone(posts.optJSONObject((int)id-1));}
        @Override public JSONObject like(long id,boolean liked){JSONObject post=posts.optJSONObject((int)id-1);try{post.put("liked",liked);post.put("likes",liked?13:12);}catch(Exception e){throw new RuntimeException(e);}likes=liked?1:0;return clone(post);}
        @Override public JSONObject comments(long id,long before){JSONArray data=new JSONArray().put(Core.obj("id",1,"nickname","林间 · 演示","text","把休息留白，这一句很喜欢。","created",System.currentTimeMillis()-300000,"mine",false));if(commentCount>0)data.put(Core.obj("id",2,"nickname","我 · 演示","text","给周末留一点空白","created",System.currentTimeMillis(),"mine",true));return Core.obj("items",data,"next_before",0);}
        @Override public JSONObject comment(long id,String text,String clientId){assertEquals("给周末留一点空白",text);commentCount++;return Core.obj("id",2);}
    }
    public void testNavigationSharingAndNativeDiscovery()throws Throwable{
        MainActivity activity=getActivity();MozhiApp app=(MozhiApp)activity.getApplication();for(int n=0;n<100&&app.store==null;n++)Thread.sleep(100);assertNotNull(app.store);FixtureCommunity fixture=new FixtureCommunity();
        runTestOnUiThread(()->{activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);app.community=fixture;});
        awaitReadyWindow();tap("新建对话");navFits();capture("040-home");
        tap("发现");awaitNode("查看问答：怎样把普通周末，过成一个小小的假期？");capture("040-discovery");navFits();
        tap("点赞问答");for(int n=0;n<30&&fixture.likes==0;n++)Thread.sleep(100);assertEquals(1,fixture.likes);
        tap("评论问答");awaitNode("把休息留白，这一句很喜欢。");capture("040-question");commentFitsAboveSystemNavigation();AccessibilityNodeInfo input=awaitNode("聊聊你的想法…");Bundle text=new Bundle();text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"给周末留一点空白");assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text));clickWindow("发送评论");awaitNode("给周末留一点空白");assertEquals(1,fixture.commentCount);clickWindow("返回发现");
        tap("我");capture("040-me");tap("我的背景");assertNotNull(find(content(),"添加背景"));
        runTestOnUiThread(()->{app.suggestions.add("我更喜欢安静的地方");app.changed();});awaitNode("待确认");awaitNode("我更喜欢安静的地方");
        runTestOnUiThread(()->{app.suggestions.clear();app.changed();});getInstrumentation().waitForIdleSync();
        tap("我");tap("设置");assertNotNull(find(content(),"自动定位"));tap("墨聊");
        Core.Item conversation=app.store.add("conversation","",Core.obj("title","问答分享验收"));Core.Item user=app.store.add("message",conversation.id,Core.obj("role","user","status","done","text","周末不想赶行程，怎么安排？"));Core.Item reply=app.store.add("message",conversation.id,Core.obj("role","assistant","status","done","text","**只挑一件想做的事。**\n\n散步、读书，或者慢慢吃一顿早餐。剩下的时间留白，让休息也有一点余地。"));
        tap("对话历史");clickWindow("问答分享验收");tap("点赞回答");assertEquals("like",app.store.list("message",conversation.id).get(1).s("feedback"));tap("点踩回答");assertEquals("dislike",app.store.list("message",conversation.id).get(1).s("feedback"));capture("040-chat");
        tap("分享到发现");awaitNode("仅分享下面这一问一答。发布前，请删去不想公开的信息。");capture("040-share-edit");clickWindow("预览");awaitNode("其他人将看到");capture("040-share-preview");clickWindow("继续编辑");clickWindow("存草稿");assertEquals(3,new JSONObject(app.store.setting("community_draft","")).length());
    }

    private MozhiApp offlineApp()throws Exception{
        MozhiApp app=(MozhiApp)getActivity().getApplication();for(int n=0;n<100&&app.store==null;n++)Thread.sleep(100);assertNotNull(app.store);assertTrue("Default discovery must be offline",app.community instanceof LocalCommunity);awaitReadyWindow();return app;
    }
    private void typeComment(String value)throws Exception{AccessibilityNodeInfo input=awaitNode("聊聊你的想法…");Bundle text=new Bundle();text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text));clickWindow("发送评论");awaitNode("聊聊你的想法…");awaitNode(value);}
    private int countWindowText(AccessibilityNodeInfo root,String label){if(root==null)return 0;int count=label.contentEquals(root.getText()==null?"":root.getText())?1:0;for(int n=0;n<root.getChildCount();n++)count+=countWindowText(root.getChild(n),label);return count;}
    private void awaitInteractionCounts()throws Exception{for(int n=0;n<60;n++){if(countWindowText(getInstrumentation().getUiAutomation().getRootInActiveWindow(),"1")==2)return;Thread.sleep(100);}throw new AssertionError("My shares did not refresh the saved like/comment counts");}

    /** Run on a clean disposable emulator, then force-stop before testOfflineDiscoveryAfterRestart. */
    public void testOfflineDiscoveryFlow()throws Throwable{
        MozhiApp app=offlineApp();assertEquals(0,app.community.myPosts(0).getJSONArray("items").length());
        runTestOnUiThread(()->getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED));
        tap("发现");navFits();awaitNode("示例与本机分享 · 仅自己可见");awaitNode("查看问答：如果我的猫是项目经理，它会怎么开周会？");capture("041-samples");
        tap("点赞问答");awaitNode("取消点赞问答");assertTrue(app.community.post(4).getBoolean("liked"));tap("评论问答");awaitNode("评论 · 仅本机");commentFitsAboveSystemNavigation();typeComment("周会安排一场午睡");clickWindow("返回发现");
        tap("墨聊");Core.Item conversation=app.store.add("conversation","",Core.obj("title","本机分享验收"));app.store.add("message",conversation.id,Core.obj("role","user","status","done","text","周末不赶行程，可以怎么过？"));app.store.add("message",conversation.id,Core.obj("role","assistant","status","done","text","**只留一件想做的事。**\n\n找一条没走过的小路，或者慢慢吃一顿早餐。\n\n剩下的时间，留给临时起意。"));
        tap("对话历史");clickWindow("本机分享验收");tap("分享到发现");awaitNode("只保存这一问一答，可先编辑。");clickWindow("预览");awaitNode("保存后的样子 · 仅自己可见");capture("041-share-preview");clickWindow("继续编辑");clickWindow("存草稿");
        tap("我");tap("我的分享");awaitNode("分享草稿  ›");clickWindow("分享草稿  ›");clickWindow("预览");clickWindow("保存到发现");awaitNode("一问一答");awaitNode("周末不赶行程，可以怎么过？");commentFitsAboveSystemNavigation();typeComment("这段问答只留在本机");clickWindow("点赞问答");awaitNode("取消点赞问答");awaitNode("这段问答只留在本机");capture("041-local-question");clickWindow("返回发现");
        awaitNode("查看问答：周末不赶行程，可以怎么过？");awaitInteractionCounts();assertNull(node(getInstrumentation().getUiAutomation().getRootInActiveWindow(),"分享草稿  ›"));capture("041-my-shares");clickWindow("返回我");tap("发现");awaitNode("查看问答：周末不赶行程，可以怎么过？");capture("041-discovery");navFits();
        JSONObject saved=app.community.myPosts(0).getJSONArray("items").getJSONObject(0);assertEquals(1,saved.getInt("comments"));assertEquals(1,saved.getInt("likes"));assertEquals("",app.store.setting("community_draft",""));assertEquals("",app.store.setting("community_identity",""));
    }

    /** A separate instrumentation process verifies real disk persistence, backup and deletion. */
    public void testOfflineDiscoveryAfterRestart()throws Throwable{
        MozhiApp app=offlineApp();JSONArray own=app.community.myPosts(0).getJSONArray("items");assertEquals(1,own.length());long id=own.getJSONObject(0).getLong("id");assertEquals("周末不赶行程，可以怎么过？",app.community.post(id).getString("question"));assertTrue(app.community.post(4).getBoolean("liked"));assertEquals(1,app.community.post(4).getInt("comments"));assertEquals("这段问答只留在本机",app.community.comments(id,0).getJSONArray("items").getJSONObject(0).getString("text"));
        try(android.database.Cursor cursor=app.store.getReadableDatabase().rawQuery("SELECT payload FROM entries WHERE kind IN ('share','share_comment')",null)){while(cursor.moveToNext()){assertFalse(cursor.getString(0).contains("本机"));assertFalse(cursor.getString(0).contains("周末"));}}
        String encrypted=Backup.encrypt(app.store.dump(),"native-test-password".toCharArray());app.store.restore(Backup.decrypt(encrypted,"native-test-password".toCharArray()));assertEquals(1,app.community.post(id).getInt("likes"));assertEquals(1,app.community.post(id).getInt("comments"));
        tap("我");tap("我的分享");awaitNode("查看问答：周末不赶行程，可以怎么过？");assertNull(node(getInstrumentation().getUiAutomation().getRootInActiveWindow(),"查看问答：如果我的猫是项目经理，它会怎么开周会？"));clickWindow("问答选项");clickWindow("删除分享");awaitNode("删除这段分享？");clickWindow("删除");awaitNode("第一段分享，从你开始");
        assertEquals(0,app.community.myPosts(0).getJSONArray("items").length());assertEquals(0,app.store.list(LocalCommunity.COMMENT,String.valueOf(id)).size());assertEquals(0,app.store.list(LocalCommunity.LIKE,String.valueOf(id)).size());assertEquals(2,app.store.list("message",null).size());assertEquals(1,app.community.post(4).getInt("comments"));assertEquals("",app.store.setting("community_identity",""));
    }
}
