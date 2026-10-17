package com.mozhi.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit user-tap handoff; no location permission, network request or automatic navigation. */
public final class MapNavigator {
    private MapNavigator() {}
    static final String AMAP="com.autonavi.minimap", BAIDU="com.baidu.BaiduMap";
    static final class Target {
        final String label;
        final Intent intent;
        Target(String label, Intent intent) { this.label=label; this.intent=intent; }
    }
    static Intent intentFor(MapLinks.Destination place, String pkg) {
        String url=AMAP.equals(pkg)?place.amapUri():BAIDU.equals(pkg)?place.baiduUri():place.geoUri();
        return new Intent(Intent.ACTION_VIEW,Uri.parse(url)).setPackage(pkg);
    }
    static List<Target> targets(Context context, MapLinks.Destination place) {
        PackageManager pm=context.getPackageManager();
        Map<String,Target> result=new LinkedHashMap<>();
        for (String pkg:new String[]{AMAP,BAIDU}) {
            Intent intent=intentFor(place,pkg);
            boolean installed=false;
            try { installed=pm.getApplicationInfo(pkg,0).enabled; } catch(PackageManager.NameNotFoundException ignored) {}
            if (installed||intent.resolveActivity(pm)!=null) result.put(pkg,new Target(AMAP.equals(pkg)?"高德地图":"百度地图",intent));
        }
        for (ResolveInfo info:pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW,Uri.parse(place.geoUri())),PackageManager.MATCH_DEFAULT_ONLY)) {
            String pkg=info.activityInfo.packageName;
            if (!pkg.equals(context.getPackageName()) && !result.containsKey(pkg))
                result.put(pkg,new Target(info.loadLabel(pm).toString(),new Intent(Intent.ACTION_VIEW,Uri.parse(place.geoUri())).setClassName(pkg,info.activityInfo.name)));
        }
        return new ArrayList<>(result.values());
    }
    public static void open(Activity activity, MapLinks.Destination place) {
        open(activity,place,()->{});
    }
    public static void open(Activity activity, MapLinks.Destination place,Runnable opened) {
        List<Target> targets=targets(activity,place);
        if (targets.isEmpty()) { fallback(activity,place,opened); return; }
        if (targets.size()==1) { launch(activity,place,targets.get(0).intent,opened); return; }
        String[] names=new String[targets.size()];
        for (int n=0;n<names.length;n++) names[n]=targets.get(n).label;
        new AlertDialog.Builder(activity).setTitle("用地图打开 · "+place.name)
            .setItems(names,(dialog,n)->launch(activity,place,targets.get(n).intent,opened))
            .setNegativeButton("取消",null).show();
    }
    static void launch(Activity activity, MapLinks.Destination place, Intent intent) {
        launch(activity,place,intent,()->{});
    }
    static void launch(Activity activity, MapLinks.Destination place, Intent intent,Runnable opened) {
        // Try the explicit intent even when an OEM resolver's preflight is incomplete.
        List<Intent> attempts=new ArrayList<>();attempts.add(intent);
        if(AMAP.equals(intent.getPackage())) {
            attempts.add(new Intent(Intent.ACTION_VIEW,Uri.parse(place.amapSearchUri())).setPackage(AMAP));
            attempts.add(new Intent(Intent.ACTION_VIEW,Uri.parse(place.geoUri())).setPackage(AMAP));
        }
        for(Intent attempt:attempts){
            try{activity.startActivity(attempt);}catch(android.content.ActivityNotFoundException|SecurityException unavailable){continue;}
            opened.run();return;
        }
        fallback(activity,place,opened);
    }
    static void fallback(Activity activity, MapLinks.Destination place) {
        fallback(activity,place,()->{});
    }
    static void fallback(Activity activity, MapLinks.Destination place,Runnable opened) {
        new AlertDialog.Builder(activity).setTitle("暂时无法打开地图 App")
            .setMessage(place.query()+"\n\n可以复制地点，或用网页地图继续查看。")
            .setPositiveButton("网页地图",(d,w)->{
                try { activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(place.webUri()))); }
                catch (android.content.ActivityNotFoundException | SecurityException e) { Toast.makeText(activity,"没有可打开网页的应用，请复制地点",Toast.LENGTH_SHORT).show();return; }
                opened.run();
            }).setNeutralButton("复制地点",(d,w)->{
                ((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("目的地",place.query()));
                Toast.makeText(activity,"地点已复制",Toast.LENGTH_SHORT).show();
            }).setNegativeButton("取消",null).show();
    }
}
