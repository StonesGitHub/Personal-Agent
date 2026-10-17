package com.mozhi.assistant;

import android.content.*;

/** After unlock/reboot, package update, clock changes or an alarm, recover from encrypted state. */
public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        PendingResult pending=goAsync();
        new Thread(()->{
            try(LocalStore store=new LocalStore(context.getApplicationContext())){Reminders.reconcile(context.getApplicationContext(),store);}
            catch(Exception ignored){}finally{pending.finish();}
        },"mozhi-alarm").start();
    }
}
