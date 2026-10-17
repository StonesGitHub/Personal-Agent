package com.mozhi.assistant;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.*;
import java.time.*;
import java.util.*;
import org.json.*;

/** Small native management surfaces, independent of model connectivity and chat completion. */
final class PersonalUi {
    private final Activity activity;
    private final MozhiApp app;
    private AlertDialog dialog;
    PersonalUi(Activity activity,MozhiApp app){this.activity=activity;this.app=app;}
    private int dp(int n){return (int)(n*activity.getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout form(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(dp(20),dp(8),dp(20),dp(20));return v;}
    private TextView text(String s){TextView t=new TextView(activity);t.setText(s);t.setTextSize(14);t.setTextColor(0xff433833);t.setPadding(0,dp(7),0,dp(7));return t;}
    private Button button(String title,Runnable click){Button b=new Button(activity);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v->click.run());return b;}
    private EditText input(String hint,String value){EditText e=new EditText(activity);e.setHint(hint);e.setText(value);e.setTextSize(15);return e;}
    private ScrollView scroll(LinearLayout v){ScrollView s=new ScrollView(activity);s.addView(v);return s;}
    private void toast(String value){Toast.makeText(activity,value,Toast.LENGTH_LONG).show();}
    private boolean idle(){if(app.busy){toast("请等待当前任务完成后修改记录");return false;}return true;}
    private void show(String title,LinearLayout body){if(dialog!=null)dialog.dismiss();dialog=new AlertDialog.Builder(activity).setTitle(title).setView(scroll(body)).setPositiveButton("完成",null).create();dialog.show();}
    private interface Work {void run()throws Exception;}
    private void work(Work action,Runnable done){app.reminderIo.execute(()->{try{action.run();activity.runOnUiThread(()->{if(!activity.isDestroyed())done.run();});}catch(Exception e){activity.runOnUiThread(()->{if(!activity.isDestroyed())toast(MozhiApp.friendly(e));});}});}
    void close(){if(dialog!=null)dialog.dismiss();}
    void reminders(){
        LinearLayout f=form();String permission=Reminders.permitted(activity)?"通知已开启":"通知未开启，到期内容会留在提醒记录中";
        f.addView(text(permission+"\n"+(Reminders.exact(activity)?"已允许精确闹钟":"普通提醒模式，系统省电可能延迟")));
        if(!Reminders.permitted(activity))f.addView(button("开启提醒通知",()->{
            if(Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)activity.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},73);
            else activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.getPackageName()));
        }));
        if(!Reminders.exact(activity)&&Build.VERSION.SDK_INT>=31)f.addView(button("允许按时提醒",()->{try{activity.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+activity.getPackageName())));}catch(Exception e){toast("请在系统设置中开启闹钟与提醒权限");}}));
        String sync=app.store.setting("reminder_last_sync","");String error=app.store.setting("reminder_sync_error","");
        if(!Reminders.scope(app.store).isEmpty()){
            f.addView(text(sync.isEmpty()?"服务端提醒尚未同步":("上次同步："+new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(Long.parseLong(sync))))));
            f.addView(button("同步服务端提醒",()->work(()->Reminders.sync(app),this::reminders)));
            if(!error.isEmpty())f.addView(text("同步未完成："+error));
        }
        if(!app.store.setting("reminder_alarm_error","").isEmpty())f.addView(text(app.store.setting("reminder_alarm_error","")));
        f.addView(button("＋ 新建提醒",()->{if(idle())editor(null);}));
        List<Core.Item> rows=Reminders.active(app.store);for(Core.Item i:app.store.list("reminder"))if(i.s("state").equals("paused"))rows.add(i);
        if(rows.isEmpty())f.addView(text("还没有待办提醒。也可以在墨聊里说“明天上午8点提醒我出发”。"));
        for(Core.Item item:rows){
            boolean cloud=item.kind.equals("reminder_cache");f.addView(text(item.s("title")+"\n"+ReminderSchedule.describe(item.data)+"\n"+(cloud?"服务端 · 已同步至本机":item.s("state").equals("paused")?"本机 · 备份恢复后已暂停，请重新设置时间":"本机")+(item.s("body").isEmpty()?"":"\n"+item.s("body"))));
            f.addView(button("修改 · "+item.s("title"),()->{if(idle())editor(item);}));
            f.addView(button("取消 · "+item.s("title"),()->{if(!idle())return;new AlertDialog.Builder(activity).setTitle("取消这个提醒？").setMessage(item.s("title")).setNegativeButton("保留",null).setPositiveButton("取消提醒",(d,w)->work(()->{
                if(cloud){if(!item.s("scope").equals(Reminders.scope(app.store)))throw new IllegalArgumentException("账户已变化，请刷新");new RemoteAgentClient(app.agentBase(),app.agentToken()).request("/v1/reminders/"+item.s("server_id")+"/cancel",new JSONObject());app.store.remove(item);}
                else{item.data.put("state","cancelled");item.data.put("next_fire",0);app.store.save(item);}Reminders.reconcile(app,app.store);
            },this::reminders)).show();}));
        }
        List<Core.Item> received=app.store.list("reminder_delivery");Collections.reverse(received);int count=0;String current=Reminders.scope(app.store);
        for(Core.Item i:received)if((i.s("scope").isEmpty()||i.s("scope").equals(current))&&count++<20)f.addView(text("到期记录 · "+i.s("title")+"\n"+new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(i.data.optLong("scheduled_at")))+" · "+(i.data.optBoolean("notified")?"已提交系统通知":"待通知")+(i.s("body").isEmpty()?"":"\n"+i.s("body"))));
        f.addView(text("提醒保存固定文字，到点不会自动搜索或运行模型。手机关机、被强行停止或通知受限时可能延迟；服务端新增或修改后需同步到本机，离线手机可能显示旧提醒。"));show("提醒与日程",f);
    }
    private void editor(Core.Item old){
        boolean cloud=old==null?app.remoteMode():old.kind.equals("reminder_cache");String origin=app.agentBase(),token=app.agentToken(),scope=Reminders.scope(app.store),requestId=UUID.randomUUID().toString();
        LinearLayout f=form();f.addView(text(cloud?"保存到服务端并同步本机通知":"保存在这部手机"));
        EditText title=input("提醒内容",old==null?"":old.s("title")),body=input("补充说明（可选）",old==null?"":old.s("body")),zone=input("时区，例如 Asia/Shanghai",old==null?ZoneId.systemDefault().getId():old.s("timezone"));f.addView(title);f.addView(body);f.addView(zone);
        LocalDateTime initial=LocalDateTime.now().plusHours(1).withSecond(0).withNano(0);if(old!=null)try{initial=OffsetDateTime.parse(old.s("start_at")).toLocalDateTime();}catch(Exception ignored){}
        final LocalDateTime[] chosen={initial};TextView date=text("");Runnable update=()->date.setText("首次时间："+chosen[0].toString().replace('T',' '));update.run();f.addView(date);
        f.addView(button("选择日期",()->new DatePickerDialog(activity,(v,y,m,d)->{chosen[0]=LocalDateTime.of(LocalDate.of(y,m+1,d),chosen[0].toLocalTime());update.run();},chosen[0].getYear(),chosen[0].getMonthValue()-1,chosen[0].getDayOfMonth()).show()));
        f.addView(button("选择时间",()->new TimePickerDialog(activity,(v,h,m)->{chosen[0]=LocalDateTime.of(chosen[0].toLocalDate(),LocalTime.of(h,m));update.run();},chosen[0].getHour(),chosen[0].getMinute(),true).show()));
        Spinner repeat=new Spinner(activity);repeat.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"一次","每天","每周（首次日期的星期）"}));if(old!=null)repeat.setSelection(old.s("repeat").equals("daily")?1:old.s("repeat").equals("weekly")?2:0);f.addView(repeat);
        AlertDialog edit=new AlertDialog.Builder(activity).setTitle(old==null?"新建提醒":"修改提醒").setView(scroll(f)).setNegativeButton("取消",null).setPositiveButton("保存",null).create();edit.setOnShowListener(x->edit.getButton(-1).setOnClickListener(v->{
            if(!idle())return;
            try{
                ZoneId tz=ZoneId.of(zone.getText().toString().trim());if(tz.getRules().getValidOffsets(chosen[0]).isEmpty())throw new IllegalArgumentException("这个本地时间因夏令时不存在，请另选时间");
                JSONObject value=Core.obj("title",title.getText().toString().trim(),"body",body.getText().toString().trim(),"timezone",tz.getId(),"start_at",chosen[0].atZone(tz).toOffsetDateTime().toString(),"repeat",new String[]{"once","daily","weekly"}[repeat.getSelectedItemPosition()]);
                JSONObject validated=ReminderSchedule.validate(value,System.currentTimeMillis());edit.getButton(-1).setEnabled(false);
                app.reminderIo.execute(()->{try{
                    if(cloud){if(!scope.equals(Reminders.scope(app.store)))throw new IllegalArgumentException("账户已变化，请重新打开提醒");value.put("client_request_id",requestId);new RemoteAgentClient(origin,token).request(old==null?"/v1/reminders":"/v1/reminders/"+old.s("server_id")+"/update",value);
                        try{Reminders.sync(app);}catch(Exception e){app.store.putSetting("reminder_sync_error","提醒已保存，请重新同步到手机");}
                    }else{if((old==null||!old.s("state").equals("active"))&&app.store.list("reminder").stream().filter(i->i.s("state").equals("active")).count()>=100)throw new IllegalArgumentException("有效提醒最多100个");validated.put("revision",old==null?1:old.data.optInt("revision",1)+1);if(old==null)app.store.add("reminder","",validated);else app.store.save(new Core.Item(old.id,"reminder","",old.created,validated));Reminders.reconcile(app,app.store);}
                    activity.runOnUiThread(()->{edit.dismiss();reminders();toast("提醒已保存");});
                }catch(Exception e){activity.runOnUiThread(()->{edit.getButton(-1).setEnabled(true);toast(MozhiApp.friendly(e));});}});
            }catch(Exception e){toast(e instanceof DateTimeException?"请填写有效时区":MozhiApp.friendly(e));}
        }));edit.show();
    }
    void health(boolean cloud){
        LinearLayout f=form();f.addView(text(cloud?"服务端健康记录 · 仅当前用户可读取":"本机健康记录 · 加密保存在这部手机"));
        f.addView(button(cloud?"查看本机记录":"查看服务端记录",()->health(!cloud)));
        f.addView(button("＋ 记录健康观察",()->{if(idle())healthEditor(cloud);}));
        LinearLayout rows=form();rows.setPadding(0,0,0,0);f.addView(rows);show("健康记录",f);
        if(cloud){
            if(Reminders.scope(app.store).isEmpty()){rows.addView(text("先在设置中连接 Agent 服务，或切换查看本机记录。"));return;}
            healthPage(rows,"",Reminders.scope(app.store));
        }else{List<Core.Item> data=app.store.list("health_record");Collections.reverse(data);if(data.isEmpty())rows.addView(text("还没有记录。可以记录饮食、睡眠、活动、症状和测量结果。"));for(Core.Item i:data)healthRow(rows,i.id,i.data,false,"");}
    }
    private void healthPage(LinearLayout rows,String before,String scope){
        TextView loading=text("正在读取…");rows.addView(loading);work(()->{
            JSONObject result=new RemoteAgentClient(app.agentBase(),app.agentToken()).request("/v1/health-records"+(before.isEmpty()?"":"?before="+before),null);
            activity.runOnUiThread(()->{rows.removeView(loading);if(!scope.equals(Reminders.scope(app.store)))return;JSONArray items=result.optJSONArray("items");if(items==null||items.length()==0){rows.addView(text("暂无健康记录"));return;}for(int n=0;n<items.length();n++){JSONObject i=items.optJSONObject(n);healthRow(rows,i.optString("id"),i,true,scope);}long next=result.optLong("next_before");if(next>0){Button more=button("更早的记录",()->{});more.setOnClickListener(v->{rows.removeView(more);healthPage(rows,String.valueOf(next),scope);});rows.addView(more);}});
        },()->{});
    }
    private void healthRow(LinearLayout rows,String id,JSONObject value,boolean cloud,String scope){
        Map<String,String> names=new HashMap<>();names.put("diet","饮食");names.put("sleep","睡眠");names.put("symptom","症状");names.put("activity","活动");names.put("measurement","测量");
        rows.addView(text(value.optString("subject")+" · "+names.getOrDefault(value.optString("category"),"观察")+"\n"+value.optString("occurred_at")+"\n"+value.optString("text")));
        rows.addView(button("删除这条记录",()->{if(!idle())return;new AlertDialog.Builder(activity).setTitle("删除健康记录？").setMessage("已生成的对话或文件中的副本仍需分别删除。").setNegativeButton("保留",null).setPositiveButton("删除",(d,w)->work(()->{
            if(cloud){if(!scope.equals(Reminders.scope(app.store)))throw new IllegalArgumentException("账户已变化，请刷新");new RemoteAgentClient(app.agentBase(),app.agentToken()).request("/v1/health-records/"+id+"/delete",new JSONObject());}
            else for(Core.Item i:app.store.list("health_record"))if(i.id.equals(id))app.store.remove(i);
        },()->health(cloud))).show();}));
    }
    private void healthEditor(boolean cloud){
        LinearLayout f=form();EditText subject=input("记录对象，例如本人、家人","本人"),note=input("实际观察：发生了什么？","");note.setMinLines(3);f.addView(subject);
        Spinner category=new Spinner(activity);category.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"饮食","睡眠","症状","活动","测量"}));f.addView(category);f.addView(note);f.addView(text("记录时间为当前时间；这里只保存实际观察，不能代替诊断。"));String scope=Reminders.scope(app.store),requestId=UUID.randomUUID().toString();
        AlertDialog edit=new AlertDialog.Builder(activity).setTitle("记录健康观察").setView(scroll(f)).setNegativeButton("取消",null).setPositiveButton("保存",null).create();edit.setOnShowListener(v->edit.getButton(-1).setOnClickListener(w->{
            if(!idle())return;String who=subject.getText().toString().trim(),content=note.getText().toString().trim();if(who.isEmpty()||who.length()>40||content.isEmpty()||content.length()>2000){toast("对象1–40字，观察1–2000字");return;}
            JSONObject value=Core.obj("subject",who,"category",new String[]{"diet","sleep","symptom","activity","measurement"}[category.getSelectedItemPosition()],"text",content,"occurred_at",OffsetDateTime.now().toString());edit.getButton(-1).setEnabled(false);
            app.reminderIo.execute(()->{try{
                if(cloud){if(scope.isEmpty()||!scope.equals(Reminders.scope(app.store)))throw new IllegalArgumentException("请先连接 Agent 服务");value.put("client_request_id",requestId);new RemoteAgentClient(app.agentBase(),app.agentToken()).request("/v1/health-records",value);}else{if(app.store.list("health_record").size()>=2000)throw new IllegalArgumentException("健康记录已达2000条，请先整理");app.store.add("health_record","",value);}
                activity.runOnUiThread(()->{edit.dismiss();health(cloud);});
            }catch(Exception e){activity.runOnUiThread(()->{edit.getButton(-1).setEnabled(true);toast(MozhiApp.friendly(e));});}});
        }));edit.show();
    }
}
