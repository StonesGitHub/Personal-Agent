package com.mozhi.assistant;

import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.widget.TextView;

/** Run on an isolated test device. Map receiver is supplied by the test APK, never the app. */
public final class MapLinkDeviceTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public MapLinkDeviceTest(){super(MainActivity.class);}
    public void testMarkdownTapHandsDestinationToMapIntent() throws Throwable {
        MainActivity activity=getActivity();
        MapLinks.Destination destination=new MapLinks.Destination("东郊记忆","成都","建设南支路4号");
        assertEquals("Use an isolated emulator with only the map test receiver",1,MapNavigator.targets(activity,destination).size());
        final Intent[] launched={null};
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent){launched[0]=new Intent(intent);return new Instrumentation.ActivityResult(0,null);}
        };
        getInstrumentation().addMonitor(monitor);
        final String[] clicked={null};
        try {
            runTestOnUiThread(()->{
                TextView text=new TextView(activity);text.setLayoutParams(new android.widget.LinearLayout.LayoutParams(600,android.view.ViewGroup.LayoutParams.WRAP_CONTENT));text.setTextIsSelectable(true);
                ReplyMarkdown.create(activity,url->{clicked[0]=url;MapNavigator.open(activity,MapLinks.parse(url));})
                    .setMarkdown(text,"建议去 **"+destination.markdown()+"** 散步。");
                Spanned spans=(Spanned)text.getText();ClickableSpan[] links=spans.getSpans(0,spans.length(),ClickableSpan.class);
                assertEquals(1,links.length);assertFalse(text.getText().toString().contains("mozhi://"));
                assertTrue(text.getMovementMethod() instanceof android.text.method.LinkMovementMethod);
                text.measure(android.view.View.MeasureSpec.makeMeasureSpec(600,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(3000,android.view.View.MeasureSpec.AT_MOST));
                text.layout(0,0,600,text.getMeasuredHeight());
                int offset=spans.getSpanStart(links[0])+1,line=text.getLayout().getLineForOffset(offset);
                float x=text.getTotalPaddingLeft()+text.getLayout().getPrimaryHorizontal(offset)+2;
                float y=text.getTotalPaddingTop()+(text.getLayout().getLineTop(line)+text.getLayout().getLineBottom(line))/2f;
                long now=android.os.SystemClock.uptimeMillis();
                for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP}){
                    android.view.MotionEvent event=android.view.MotionEvent.obtain(now,now+40,action,x,y,0);text.dispatchTouchEvent(event);event.recycle();
                }
            });
            assertNotNull(launched[0]);assertEquals(destination.url(),clicked[0]);
            assertEquals(destination.query(),launched[0].getData().getSchemeSpecificPart().substring(6));
            assertEquals("com.mozhi.assistant.test",launched[0].getComponent().getPackageName());
        } finally {getInstrumentation().removeMonitor(monitor);}
    }
    public void testNativeMapIntentsKeepDestinationAndOnlyData() {
        MapLinks.Destination place=new MapLinks.Destination("A&B 咖啡（东门店）","示例城市","工业园区1号");
        for(String pkg:new String[]{MapNavigator.AMAP,MapNavigator.BAIDU}) {
            Intent i=MapNavigator.intentFor(place,pkg);assertEquals(Intent.ACTION_VIEW,i.getAction());
            assertEquals(pkg,i.getPackage());assertNull(i.getExtras());
            assertEquals(place.query(),i.getData().getQueryParameter(pkg.equals(MapNavigator.AMAP)?"keyword":"destination"));
        }
    }
    public void testAmapUnsupportedRouteFallsBackToSearchWithSameDestination() throws Throwable {
        MainActivity activity=getActivity();MapLinks.Destination place=new MapLinks.Destination("A&B（东门店）","成都","测试路1号");
        java.util.List<Intent> attempts=new java.util.ArrayList<>();
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){attempts.add(new Intent(intent));if(attempts.size()==1)throw new android.content.ActivityNotFoundException("test: route handler unavailable");return new Instrumentation.ActivityResult(0,null);}};
        getInstrumentation().addMonitor(monitor);
        try{runTestOnUiThread(()->MapNavigator.launch(activity,place,MapNavigator.intentFor(place,MapNavigator.AMAP)));assertEquals(2,attempts.size());assertEquals("poi",attempts.get(1).getData().getHost());assertEquals(place.query(),attempts.get(1).getData().getQueryParameter("keywords"));assertEquals(MapNavigator.AMAP,attempts.get(1).getPackage());assertNull(attempts.get(1).getExtras());}finally{getInstrumentation().removeMonitor(monitor);}
    }
    public void testSeedMapFixture() throws Exception {
        LocalStore store=new LocalStore(getInstrumentation().getTargetContext());
        Core.Item conversation=store.add("conversation","",Core.obj("title","地点链接验收示例"));
        store.add("message",conversation.id,Core.obj("role","user","text","成都想找个地方走走，有什么建议？（测试示例）","status","done"));
        String text="## 慢慢走，不赶路\n\n可以先看看 **"+new MapLinks.Destination("东郊记忆","成都","").markdown()+"**，或者去 "+new MapLinks.Destination("人民公园","成都","").markdown()+" 散步。\n\n**点地名，就能用手机地图打开。** 地点名称和城市会一起带过去。\n\n> 同名地点请在地图中确认；营业情况以实时信息为准。";
        store.add("message",conversation.id,Core.obj("role","assistant","text",text,"status","done"));
        store.putSetting("current",conversation.id);store.close();
    }
}
