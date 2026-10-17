package com.mozhi.assistant;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Foreground-only automatic GPS/network updates, using bounded native requests. */
public final class LocationController {
    static final long REFRESH_MAX_AGE_MS=30_000;
    private final Context context;
    private final LocationManager manager;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Consumer<LocationContext.Snapshot>> callbacks=new ArrayList<>();
    private final ExecutorService geocoding=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"mozhi-geocoder");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private LocationContext.Snapshot cached;
    private LocationListener listener;
    private final LocationRefreshLoop automatic;
    private Runnable observer;
    private int generation;
    public boolean pending;
    public String status="位置未开启";
    private final Runnable timeout=()->finish(current(),"暂未获取位置");
    public LocationController(Context context){
        this.context=context.getApplicationContext();manager=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
        automatic=new LocationRefreshLoop(new LocationRefreshLoop.Driver(){
            public void refresh(Runnable complete){request(fix->{notifyObserver();complete.run();},true);notifyObserver();}
            public void schedule(Runnable task,long delay){main.postDelayed(task,delay);}
            public void cancel(Runnable task){main.removeCallbacks(task);}
        });
    }
    public void startAutomatic(Runnable changed){observer=changed;automatic.start();}
    private void notifyObserver(){if(observer!=null)observer.run();}
    public boolean permitted(){return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||precise();}
    private boolean precise(){return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    public boolean systemEnabled(){try{return manager!=null&&(Build.VERSION.SDK_INT>=28?manager.isLocationEnabled():manager.isProviderEnabled("gps")||manager.isProviderEnabled("network"));}catch(RuntimeException e){return false;}}
    public LocationContext.Snapshot current(){return permitted()&&systemEnabled()&&cached!=null&&(cached.approximate||precise())&&cached.fresh(SystemClock.elapsedRealtime())?cached:null;}
    public void clear(){stop();cached=null;status="位置未开启";}
    public void request(Consumer<LocationContext.Snapshot> callback){request(callback,false);}
    private void request(Consumer<LocationContext.Snapshot> callback,boolean refresh){
        LocationContext.Snapshot fresh=current();if(!refresh&&fresh!=null){status=fresh.area.isEmpty()?"位置已就绪":fresh.area;callback.accept(fresh);return;}
        if(!permitted()||!systemEnabled()){status=!permitted()?"未授予位置权限":"系统定位未开启";callback.accept(null);return;}
        callbacks.add(callback);if(pending)return;pending=true;status="正在定位…";final int token=++generation;
        final long maxAge=refresh?REFRESH_MAX_AGE_MS:LocationContext.MAX_AGE_MS;
        listener=new LocationListener(){
            @Override public void onLocationChanged(Location location){accept(location,token,maxAge);}
            @Override public void onStatusChanged(String provider,int status,Bundle extras){}
            @Override public void onProviderEnabled(String provider){}
            @Override public void onProviderDisabled(String provider){}
        };
        main.postDelayed(timeout,12000);
        boolean requested=false;Location best=null;
        for(String provider:new String[]{"network","gps","fused"})try{
            if(!manager.getAllProviders().contains(provider)||!manager.isProviderEnabled(provider)||("gps".equals(provider)&&!precise()))continue;
            Location last=manager.getLastKnownLocation(provider);
            if(last!=null&&snapshot(last,"").fresh(SystemClock.elapsedRealtime(),maxAge)&&(best==null||last.getElapsedRealtimeNanos()>best.getElapsedRealtimeNanos()))best=last;
            manager.requestLocationUpdates(provider,1000,0,listener,Looper.getMainLooper());requested=true;
        }catch(SecurityException|IllegalArgumentException ignored){}
        if(best!=null)accept(best,token,maxAge);else if(!requested)finish(current(),"暂未获取位置");
    }
    private LocationContext.Snapshot snapshot(Location l,String area){return new LocationContext.Snapshot(l.getLatitude(),l.getLongitude(),l.hasAccuracy()?l.getAccuracy():100000,l.getTime(),l.getElapsedRealtimeNanos()/1000000,!precise(),area);}
    private void accept(Location location,int token,long maxAge){
        if(token!=generation||!pending||listener==null)return;
        LocationContext.Snapshot fix=snapshot(location,"");if(!fix.fresh(SystemClock.elapsedRealtime(),maxAge)||(current()!=null&&fix.elapsed<cached.elapsed))return;
        removeUpdates();cached=fix;
        // Geocoder availability is optional; never delay a message indefinitely for a place label.
        main.postDelayed(()->{if(token==generation&&pending)finish(current(),"暂未获取位置");},1500);
        if(!Geocoder.isPresent()){finish(current(),"暂未获取位置");return;}
        Consumer<List<Address>> done=addresses->main.post(()->{
            if(token!=generation||cached==null||!permitted())return;
            String area="";if(addresses!=null&&!addresses.isEmpty()){
                Address a=addresses.get(0);LinkedHashSet<String> parts=new LinkedHashSet<>();
                for(String p:new String[]{a.getAdminArea(),a.getLocality(),a.getSubLocality()})if(p!=null&&!p.trim().isEmpty())parts.add(p.trim());
                area=String.join(" ",parts);if(area.length()>160)area=area.substring(0,160);
            }
            cached=snapshot(location,area);if(pending)finish(current(),"暂未获取位置");else notifyObserver();
        });
        try{
            Geocoder coder=new Geocoder(context,Locale.getDefault());
            if(Build.VERSION.SDK_INT>=33)coder.getFromLocation(fix.latitude,fix.longitude,1,new Geocoder.GeocodeListener(){public void onGeocode(List<Address> values){done.accept(values);}public void onError(String error){done.accept(null);}});
            else geocoding.execute(()->{try{done.accept(coder.getFromLocation(fix.latitude,fix.longitude,1));}catch(Exception ignored){done.accept(null);}});
        }catch(RuntimeException ignored){finish(current(),"暂未获取位置");}
    }
    private void removeUpdates(){if(listener!=null){try{manager.removeUpdates(listener);}catch(RuntimeException ignored){}listener=null;}}
    private void finish(LocationContext.Snapshot result,String failure){
        pending=false;removeUpdates();main.removeCallbacks(timeout);status=result==null?failure:result.area.isEmpty()?"位置已就绪":result.area;
        List<Consumer<LocationContext.Snapshot>> copy=new ArrayList<>(callbacks);callbacks.clear();for(Consumer<LocationContext.Snapshot> c:copy)c.accept(result);
    }
    public void stop(){automatic.stop();observer=null;generation++;removeUpdates();main.removeCallbacksAndMessages(null);pending=false;callbacks.clear();}
}
