package com.mozhi.assistant;

import android.app.*;
import android.content.*;
import android.os.Build;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Server schedules are mirrored to encrypted storage; AlarmManager delivers without an open chat. */
final class Reminders {
    private static final Object LOCK=new Object();
    static final String CHANNEL="mozhi_reminders";
    private Reminders() {}
    static String scope(LocalStore store){
        String base=PhoneAccount.base(store),token=PhoneAccount.token(store);if(base.isEmpty()||token.isEmpty())return "";
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest((base+"\n"+(PhoneAccount.bound(store)?PhoneAccount.record(store,"phone_account").optString("id"):token)).getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}
    }
    static void channel(Context c){NotificationManager m=c.getSystemService(NotificationManager.class);m.createNotificationChannel(new NotificationChannel(CHANNEL,"墨知提醒",NotificationManager.IMPORTANCE_DEFAULT));}
    static boolean permitted(Context c){channel(c);NotificationManager m=c.getSystemService(NotificationManager.class);NotificationChannel channel=m.getNotificationChannel(CHANNEL);return m.areNotificationsEnabled()&&channel!=null&&channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;}
    static boolean exact(Context c){return Build.VERSION.SDK_INT<31||c.getSystemService(AlarmManager.class).canScheduleExactAlarms();}
    private static PendingIntent alarm(Context c){return PendingIntent.getBroadcast(c,71,new Intent(c,ReminderReceiver.class).setAction(c.getPackageName()+".REMIND"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    static void cancelAlarm(Context c){c.getSystemService(AlarmManager.class).cancel(alarm(c));}
    static void clear(Context c,LocalStore store){synchronized(LOCK){cancelAlarm(c);c.getSystemService(NotificationManager.class).cancelAll();store.clear();}}
    static void restore(Context c,LocalStore store,JSONArray rows)throws Exception{synchronized(LOCK){store.restore(rows);cancelAlarm(c);c.getSystemService(NotificationManager.class).cancelAll();}}
    static List<Core.Item> active(LocalStore store){
        List<Core.Item> rows=new ArrayList<>(store.list("reminder"));String scope=scope(store);
        for(Core.Item i:store.list("reminder_cache"))if(!scope.isEmpty()&&i.s("scope").equals(scope))rows.add(i);
        rows.removeIf(i->!i.s("state").equals("active"));rows.sort(Comparator.comparingLong(i->i.data.optLong("next_fire")));return rows;
    }
    private static String deliveryId(String scope,String id,long due){return UUID.nameUUIDFromBytes((scope+":"+id+":"+due).getBytes(StandardCharsets.UTF_8)).toString();}
    private static Core.Item deliveryRow(String scope,String id,long due,String title,String body){return new Core.Item(deliveryId(scope,id,due),"reminder_delivery","",System.currentTimeMillis(),Core.obj("scope",scope,"reminder_id",id,"scheduled_at",due,"title",title,"body",body,"notified",false));}
    private static void delivery(LocalStore store,String scope,String id,long due,String title,String body)throws JSONException{
        String ident=deliveryId(scope,id,due);for(Core.Item i:store.list("reminder_delivery"))if(i.id.equals(ident))return;
        store.save(deliveryRow(scope,id,due,title,body));
    }
    static void reconcile(Context c,LocalStore store){
        if(PhoneAccount.locked(store)){cancelAlarm(c);c.getSystemService(NotificationManager.class).cancelAll();return;}
        synchronized(LOCK){try{
            channel(c);long stamp=System.currentTimeMillis(),next=Long.MAX_VALUE;
            for(Core.Item item:active(store)){
                long due=item.data.optLong("next_fire",ReminderSchedule.first(item.data));
                if(due<=stamp){
                    long following=ReminderSchedule.next(item.data,stamp);
                    Core.Item received=deliveryRow(item.s("scope"),item.kind.equals("reminder_cache")?item.s("server_id"):item.id,due,item.s("title"),item.s("body"));
                    store.fireReminder(item,received,following);
                    due=following;
                }
                if(due>0)next=Math.min(next,due);
            }
            List<Core.Item> messages=store.list("reminder_delivery");Collections.reverse(messages);int posted=0;String current=scope(store);
            for(Core.Item item:messages){
                if(!item.s("scope").isEmpty()&&!item.s("scope").equals(current))continue;
                if(!item.data.optBoolean("notified")&&item.created>=stamp-86400000&&permitted(c)&&posted<20){
                    Intent target=new Intent(c,MainActivity.class).putExtra("open_reminders",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    PendingIntent click=PendingIntent.getActivity(c,72,target,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                    Notification notice=new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_mozhi)
                        .setContentTitle(item.s("title")).setContentText(item.s("body").isEmpty()?"打开墨知查看提醒":item.s("body"))
                        .setStyle(new Notification.BigTextStyle().bigText(item.s("body")))
                        .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_mozhi).setContentTitle("墨知提醒").setContentText("打开查看内容").build())
                        .setWhen(item.data.optLong("scheduled_at")).setContentIntent(click).setAutoCancel(true).setOnlyAlertOnce(true).build();
                    try{c.getSystemService(NotificationManager.class).notify(item.id,0,notice);item.data.put("notified",true);store.save(item);posted++;}catch(SecurityException ignored){}
                }
            }
            // Keep a month of received reminders. Older history is available only in exports made earlier.
            for(Core.Item item:messages)if(item.created<stamp-30L*86400000)store.remove(item);
            // An edit might have raced with a stale snapshot above; schedule from committed rows.
            next=Long.MAX_VALUE;for(Core.Item i:active(store))next=Math.min(next,i.data.optLong("next_fire"));
            AlarmManager manager=c.getSystemService(AlarmManager.class);PendingIntent alarm=alarm(c);
            if(next==Long.MAX_VALUE)manager.cancel(alarm);
            else if(exact(c)){try{manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,alarm);}catch(SecurityException e){manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,alarm);}}
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,alarm);
            store.putSetting("reminder_alarm_error","");
        }catch(Exception e){store.putSetting("reminder_alarm_error","提醒调度失败，请打开提醒管理检查时间或权限");}}
    }
    static void sync(MozhiApp app)throws Exception{
        LocalStore store=app.store;String expected=scope(store);if(expected.isEmpty()){reconcile(app,store);return;}
        RemoteAgentClient api=new RemoteAgentClient(app.agentBase(),app.agentToken());
        JSONArray rows=api.request("/v1/reminders",null).getJSONArray("items");
        // Fetch before replacing the snapshot: a failed inbox request must not discard a due local alarm.
        JSONArray firstEvents=api.request("/v1/reminders/inbox",null).getJSONArray("items");
        synchronized(LOCK){
            if(!expected.equals(scope(store)))return;
            Set<String> present=new HashSet<>();
            for(int n=0;n<rows.length();n++){
                JSONObject data=new JSONObject(rows.getJSONObject(n).toString());String id=data.getString("id"),cacheId="remote-"+id;
                ReminderSchedule.next(data,System.currentTimeMillis());data.put("scope",expected);data.put("server_id",id);present.add(cacheId);
                store.save(new Core.Item(cacheId,"reminder_cache","",data.optLong("created",System.currentTimeMillis()),data));
            }
            for(Core.Item old:store.list("reminder_cache"))if(!old.s("scope").equals(expected)||!present.contains(old.id))store.remove(old);
        }
        // Persist received events before acknowledgement; duplicate pulls reuse the same delivery ID.
        for(int page=0;page<5;page++){
            JSONArray events=page==0?firstEvents:api.request("/v1/reminders/inbox",null).getJSONArray("items"),ack=new JSONArray();
            synchronized(LOCK){
                if(!expected.equals(scope(store)))return;
                for(int n=0;n<events.length();n++){JSONObject e=events.getJSONObject(n);delivery(store,expected,e.getString("reminder_id"),e.getLong("scheduled_at"),e.getString("title"),e.getString("body"));ack.put(e.getLong("id"));}
            }
            if(ack.length()>0)api.request("/v1/reminders/inbox/ack",Core.obj("ids",ack));if(events.length()<200)break;
        }
        store.putSetting("reminder_last_sync",String.valueOf(System.currentTimeMillis()));store.putSetting("reminder_sync_error","");reconcile(app,store);
    }
}
