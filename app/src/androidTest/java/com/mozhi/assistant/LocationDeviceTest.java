package com.mozhi.assistant;
import android.test.ActivityInstrumentationTestCase2;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated emulator fixture: grant foreground permissions and use an Android test GPS provider. */
public final class LocationDeviceTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public LocationDeviceTest(){super(MainActivity.class);}
    static void shell(android.app.Instrumentation instrument,String command)throws Exception{try(android.os.ParcelFileDescriptor fd=instrument.getUiAutomation().executeShellCommand(command);java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] buffer=new byte[1024];while(input.read(buffer)>=0){}}}
    public static void verifyProvider(MainActivity activity,android.app.Instrumentation instrument)throws Throwable{
        shell(instrument,"pm grant com.mozhi.assistant android.permission.ACCESS_COARSE_LOCATION");shell(instrument,"pm grant com.mozhi.assistant android.permission.ACCESS_FINE_LOCATION");shell(instrument,"appops set com.mozhi.assistant android:mock_location allow");
        shell(instrument,"settings put secure location_mode 3");
        instrument.runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON));
        MozhiApp app=(MozhiApp)activity.getApplication();for(int n=0;n<100&&app.store==null;n++)Thread.sleep(100);assertNotNull(app.store);assertTrue(app.locations.permitted());
        assertEquals("Fixture requires precise foreground permission",android.content.pm.PackageManager.PERMISSION_GRANTED,activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION));
        android.location.LocationManager manager=(android.location.LocationManager)activity.getSystemService(android.content.Context.LOCATION_SERVICE);
        CountDownLatch done=new CountDownLatch(1);AtomicReference<LocationContext.Snapshot> result=new AtomicReference<>();
        final Throwable[] failure={null};
        try{
            instrument.runOnMainSync(()->{try{
                app.locations.clear();manager.addTestProvider("gps",false,true,false,false,true,true,true,android.location.Criteria.POWER_LOW,android.location.Criteria.ACCURACY_FINE);manager.setTestProviderEnabled("gps",true);
                android.location.Location fix=new android.location.Location("gps");fix.setLatitude(30.67);fix.setLongitude(104.07);fix.setAccuracy(15);fix.setTime(System.currentTimeMillis());fix.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());manager.setTestProviderLocation("gps",fix);
            }catch(Throwable e){failure[0]=e;done.countDown();}});
            if(failure[0]!=null)throw failure[0];
            // Wait for the framework to publish the test observation before exercising the app;
            // binder/provider startup on a software emulator is asynchronous.
            android.location.Location observed=null;
            for(int n=0;n<40;n++){
                observed=manager.getLastKnownLocation("gps");if(observed!=null&&Math.abs(observed.getLatitude()-30.67)<.01)break;
                Thread.sleep(250);
            }
            assertNotNull("Fixture GPS not published by Android",observed);assertTrue("Fixture system location must be enabled",app.locations.systemEnabled());
            instrument.runOnMainSync(()->app.locations.request(fix->{result.set(fix);done.countDown();}));
            assertTrue("Request must finish within its timeout",done.await(18,TimeUnit.SECONDS));
            LocationContext.Snapshot fix=result.get();assertNotNull("Framework GPS provider must supply fixture; status="+app.locations.status,fix);assertEquals(30.67,fix.latitude,.01);assertEquals(104.07,fix.longitude,.01);
            org.json.JSONArray request=Core.messages(java.util.Collections.emptyList(),java.util.Collections.emptyList());LocationContext.append(request,true,fix,android.os.SystemClock.elapsedRealtime());assertTrue(request.toString().contains("本次设备位置"));
            assertFalse(app.store.dump().toString().contains("本次设备位置"));
        }finally{instrument.runOnMainSync(()->{app.locations.stop();assertFalse(app.locations.pending);app.locations.clear();assertNull(app.locations.current());try{manager.removeTestProvider("gps");}catch(Exception ignored){}});shell(instrument,"appops set com.mozhi.assistant android:mock_location deny");}
    }
    public void testSystemProviderSuppliesEphemeralContextAndStops()throws Throwable{verifyProvider(getActivity(),getInstrumentation());}
    public void testPermissionDenialReturnsWithoutRequestingOrFailing()throws Throwable{
        MainActivity activity=getActivity();android.content.Context denied=new android.content.ContextWrapper(activity){@Override public android.content.Context getApplicationContext(){return this;}@Override public int checkSelfPermission(String permission){return android.content.pm.PackageManager.PERMISSION_DENIED;}};
        LocationController controller=new LocationController(denied);CountDownLatch done=new CountDownLatch(1);
        runTestOnUiThread(()->controller.request(fix->{assertNull(fix);assertFalse(controller.pending);done.countDown();}));assertTrue(done.await(2,TimeUnit.SECONDS));assertNull(controller.current());
    }
}
