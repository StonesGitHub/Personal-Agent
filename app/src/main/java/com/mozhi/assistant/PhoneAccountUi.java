package com.mozhi.assistant;

import android.app.*;
import android.text.InputType;
import android.widget.*;
import android.view.View;
import org.json.*;

/** Authentication never deletes local data; importing is an explicit, retryable snapshot. */
final class PhoneAccountUi {
    private final Activity activity;private final MozhiApp app;private final Runnable refresh;
    PhoneAccountUi(Activity activity,MozhiApp app,Runnable refresh){this.activity=activity;this.app=app;this.refresh=refresh;}
    private void message(String s){Toast.makeText(activity,s,Toast.LENGTH_LONG).show();}
    private EditText field(String hint,String value,int type){EditText e=new EditText(activity);e.setHint(hint);e.setText(value);e.setInputType(type);e.setSingleLine(true);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);return e;}
    private LinearLayout form(){LinearLayout f=new LinearLayout(activity);f.setOrientation(1);int p=(int)(20*activity.getResources().getDisplayMetrics().density);f.setPadding(p,p,p,p);return f;}
    void show(){
        if(app.busy){message("请先等待当前任务结束");return;}
        if(!PhoneAccount.bound(app.store)||PhoneAccount.locked(app.store)){login();return;}
        new AlertDialog.Builder(activity).setTitle(PhoneAccount.label(app.store)).setItems(new String[]{"导入 / 备份本机资料到账号","从账号取回资料","退出账号"},(d,n)->{
            if(n==0)confirmUpload();else if(n==1)listArchives();else logout();
        }).setNegativeButton("关闭",null).show();
    }
    void login(){
        if(app.busy){message("请先等待当前任务结束");return;}
        LinearLayout f=form();TextView hint=new TextView(activity);hint.setText("手机号 + 短信验证码登录，首次使用自动注册。旧手机资料保留，验证后可导入账号。\n已绑定的本机资料仅对原账号开放。");f.addView(hint);
        EditText base=field("你的 HTTPS 服务地址",app.agentBase(),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        EditText phone=field("手机号","",InputType.TYPE_CLASS_PHONE),code=field("6 位短信验证码","",InputType.TYPE_CLASS_NUMBER);
        f.addView(base);f.addView(phone);f.addView(code);Button send=new Button(activity);send.setText("获取验证码");f.addView(send);
        CheckBox bind=new CheckBox(activity);bind.setText("绑定当前服务账号（保留原任务和文件）");boolean legacy=!PhoneAccount.bound(app.store)&&!app.agentToken().isEmpty();bind.setChecked(legacy);bind.setVisibility(legacy?View.VISIBLE:View.GONE);f.addView(bind);
        final String[] challenge={"","",""};final long[] retry={0};
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("手机号账号").setView(f).setNegativeButton("取消",null).setPositiveButton("验证并登录",null).create();
        send.setOnClickListener(v->{
            if(System.currentTimeMillis()<retry[0]){message("请等待 60 秒后再获取验证码");return;}
            try{
                final String selected=GatewayClient.endpoint(base.getText().toString().trim()).toString().replaceAll("/$","");final String number=phone.getText().toString().trim();
                send.setEnabled(false);
                app.communityIo.execute(()->{try{JSONObject result=GatewayClient.auth(selected,"").request("/v1/auth/code",Core.obj("phone",number));
                    app.main.post(()->{if(activity.isDestroyed())return;challenge[0]=result.optString("challenge_id");challenge[1]=number;challenge[2]=selected;retry[0]=System.currentTimeMillis()+60000;send.setEnabled(true);message("验证码已发送，有效期 5 分钟");});
                }catch(Exception e){app.main.post(()->{send.setEnabled(true);message(MozhiApp.friendly(e));});}});
            }catch(Exception e){message(MozhiApp.friendly(e));}
        });
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(w->{
            try{
                final String selected=GatewayClient.endpoint(base.getText().toString().trim()).toString().replaceAll("/$",""),number=phone.getText().toString().trim();
                if(challenge[0].isEmpty()||!challenge[1].equals(number)||!challenge[2].equals(selected))throw new Exception("请先为此手机号获取验证码");
                if(PhoneAccount.bound(app.store)&&!GatewayClient.endpoint(app.agentBase()).toString().replaceAll("/$","").equals(selected))throw new Exception("请使用原账号的服务地址");
                final boolean link=bind.isChecked();if(link&&!GatewayClient.endpoint(app.agentBase()).toString().replaceAll("/$","").equals(selected))throw new Exception("绑定旧账号必须使用原服务地址");
                final String old=link?app.agentToken():"";final JSONObject payload=Core.obj("phone",number,"challenge_id",challenge[0],"code",code.getText().toString().trim(),"bind_legacy",link);
                app.busy=true;dialog.getButton(-1).setEnabled(false);send.setEnabled(false);dialog.setCancelable(false);
                app.communityIo.execute(()->{try{
                    JSONObject result=GatewayClient.auth(selected,old).request("/v1/auth/verify",payload);
                    try{PhoneAccount.accept(app.store,(link||PhoneAccount.bound(app.store))?app.agentBase():selected,result);}catch(Exception e){try{GatewayClient.auth(selected,result.optString("token")).request("/v1/auth/logout",new JSONObject());}catch(Exception ignored){}throw e;}
                    app.main.post(()->{app.busy=false;if(activity.isDestroyed())return;dialog.dismiss();refresh.run();app.resumeRemote();app.syncReminders();confirmUpload();});
                }catch(Exception e){app.main.post(()->{app.busy=false;if(activity.isDestroyed())return;dialog.setCancelable(true);dialog.getButton(-1).setEnabled(true);send.setEnabled(true);message(MozhiApp.friendly(e));});}});
            }catch(Exception e){message(MozhiApp.friendly(e));}
        }));dialog.show();
    }
    private void confirmUpload(){
        new AlertDialog.Builder(activity).setTitle("导入本机资料到 "+PhoneAccount.label(app.store)).setMessage("将聊天、背景、决定、技能、分享、提醒和健康记录保存到你的服务端账号。仅本账号可取回，保留最近 5 份备份。手机原记录保留。\n\n图片原文件、尚未上传的附件及第三方社区身份不包含在备份中。提醒取回后暂停，避免重复通知。")
            .setNegativeButton("稍后",null).setPositiveButton("开始导入",(d,w)->work("正在分批导入…",()->"已导入 "+PhoneAccount.upload(app.store)+" 条记录，手机原资料已保留")).show();
    }
    private interface Job {String run()throws Exception;}
    private void work(String title,Job job){
        if(app.busy){message("请先等待当前任务结束");return;}app.busy=true;
        ProgressDialog progress=ProgressDialog.show(activity,"手机号账号",title,true,false);
        app.communityIo.execute(()->{String result;try{result=job.run();}catch(Exception e){result=MozhiApp.friendly(e);}String text=result;
            app.main.post(()->{app.busy=false;if(activity.isDestroyed())return;progress.dismiss();refresh.run();message(text);});});
    }
    private void listArchives(){
        ProgressDialog progress=ProgressDialog.show(activity,"账号资料","正在读取备份…",true,false);
        app.communityIo.execute(()->{try{JSONArray rows=new GatewayClient(app.agentBase(),app.agentToken()).request("/v1/account/archives",null).getJSONArray("items");
            String[] names=new String[rows.length()];for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);names[i]=android.text.format.DateFormat.format("yyyy-MM-dd HH:mm",row.getLong("created"))+" · "+row.getInt("total")+" 条";}
            app.main.post(()->{if(activity.isDestroyed())return;progress.dismiss();if(names.length==0){message("账号尚无备份，请先在旧手机导入资料");return;}
                new AlertDialog.Builder(activity).setTitle("选择账号备份").setItems(names,(d,n)->new AlertDialog.Builder(activity).setTitle("取回资料？").setMessage("仅补充本机没有的记录，相同编号不会覆盖或重复导入。提醒会保持暂停。")
                    .setNegativeButton("取消",null).setPositiveButton("取回",(x,y)->work("正在取回资料…",()->"已补充 "+PhoneAccount.restore(app.store,rows.getJSONObject(n).getString("id"))+" 条记录")).show()).setNegativeButton("取消",null).show();});
        }catch(Exception e){app.main.post(()->{progress.dismiss();message(MozhiApp.friendly(e));});}});
    }
    private void logout(){new AlertDialog.Builder(activity).setTitle("退出账号？").setMessage("本机加密资料保留并隐藏，重新验证原手机号后恢复访问。")
        .setNegativeButton("取消",null).setPositiveButton("退出",(d,w)->work("正在退出…",()->{
            new GatewayClient(app.agentBase(),app.agentToken()).request("/v1/auth/logout",new JSONObject());
            app.store.putSetting("phone_session","{}");Reminders.cancelAlarm(activity);activity.getSystemService(NotificationManager.class).cancelAll();return "已退出，资料保留";
        })).show();}
}
