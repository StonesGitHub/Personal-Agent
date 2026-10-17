package com.mozhi.assistant;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;

/** Visual fixtures only. Run on the disposable emulator, never on a user's data. */
public final class ExperienceDeviceTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public ExperienceDeviceTest(){super(MainActivity.class);}
    private View find(View root,String description){if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View match=find(((ViewGroup)root).getChildAt(i),description);if(match!=null)return match;}return null;}
    private View content(){return getActivity().getWindow().getDecorView();}
    private Dialog history()throws Exception{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("historyDialog");field.setAccessible(true);return (Dialog)field.get(getActivity());}
    private void tap(View root,String description)throws Throwable{runTestOnUiThread(()->{View target=find(root,description);assertNotNull("Missing fixture navigation: "+description,target);target.performClick();});getInstrumentation().waitForIdleSync();}
    private void capture(String name,View view)throws Throwable{
        getInstrumentation().waitForIdleSync();runTestOnUiThread(()->{try{
            assertTrue(view.getWidth()>0&&view.getHeight()>0);Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));
            try(FileOutputStream out=new FileOutputStream(new File(getActivity().getFilesDir(),"verify-"+name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testForegroundLocationProvider()throws Throwable{LocationDeviceTest.verifyProvider(getActivity(),getInstrumentation());}
    public void testCaptureNativeLayouts()throws Throwable{
        MainActivity activity=getActivity();MozhiApp app=(MozhiApp)activity.getApplication();for(int n=0;n<100&&app.store==null;n++)Thread.sleep(100);assertNotNull(app.store);
        runTestOnUiThread(()->{activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);});
        tap(content(),"新建对话");capture("home",content());
        tap(content(),"对话历史");capture("history",history().getWindow().getDecorView());tap(history().getWindow().getDecorView(),"收起历史");
        tap(content(),"我");tap(content(),"设置");capture("settings",content());
        tap(content(),"返回");tap(content(),"我的背景");capture("memories",content());
        tap(content(),"墨聊");
        Core.Item c=app.store.add("conversation","",Core.obj("title","简洁对话验收"));
        app.store.add("message",c.id,Core.obj("role","user","text","周末想在成都走走，有什么建议？","status","done"));
        String reply="可以从 **"+new MapLinks.Destination("人民公园","成都","").markdown()+"** 开始。留一个小时慢慢走，不用排满行程。\n\n### 怎么安排\n\n- **先散步**，找个喜欢的地方坐坐。\n- 想喝茶，再看看附近茶馆。\n- 如果下雨，换成室内活动。\n\n点地名，可以在手机地图里确认路线。";
        app.store.add("message",c.id,Core.obj("role","assistant","text",reply,"status","done"));
        tap(content(),"对话历史");tap(history().getWindow().getDecorView(),"简洁对话验收");capture("conversation",content());
    }
}
