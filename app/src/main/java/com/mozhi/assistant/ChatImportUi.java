package com.mozhi.assistant;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.widget.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Preview-first import. Parsing is local; only an explicitly entered link uses the network. */
final class ChatImportUi {
    static final int PICK_FILE=61;
    private final Activity activity;private final MozhiApp app;private final Consumer<String> opened;
    private volatile int generation;private volatile WebClient web;private AlertDialog preview;private ProgressDialog progress;
    ChatImportUi(Activity activity,MozhiApp app,Consumer<String> opened){this.activity=activity;this.app=app;this.opened=opened;}
    private int dp(int n){return (int)(n*activity.getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout form(){LinearLayout f=new LinearLayout(activity);f.setOrientation(LinearLayout.VERTICAL);f.setPadding(dp(22),dp(10),dp(22),dp(14));return f;}
    private TextView text(String s,int size){TextView t=new TextView(activity);t.setText(s);t.setTextColor(0xff433833);t.setTextSize(size);t.setPadding(0,dp(5),0,dp(8));t.setLineSpacing(dp(3),1);return t;}
    private ScrollView scroll(LinearLayout f){ScrollView s=new ScrollView(activity);s.addView(f);return s;}
    private void toast(String s){Toast.makeText(activity,s,Toast.LENGTH_LONG).show();}
    void show(){if(app.busy){toast("请先等待当前回答结束");return;}new AlertDialog.Builder(activity).setTitle("导入聊天记录")
        .setItems(new String[]{"分享链接","选择文件","粘贴文字"},(d,w)->{if(w==0)input(true);else if(w==1)file();else input(false);}).setNegativeButton("取消",null).show();}
    private void file(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","text/markdown","application/octet-stream"});
        try{activity.startActivityForResult(i,PICK_FILE);}catch(ActivityNotFoundException e){toast("没有可用的文件选择器，请改用粘贴文字");}
    }
    void fileResult(int result,Intent data){if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        load("正在读取聊天文件…",()->{
            String name="聊天文件";try(android.database.Cursor c=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}
            return ChatImport.parse(ChatImport.read(activity.getContentResolver().openInputStream(uri)),"文件 · "+name);
        });
    }
    private void input(boolean link){
        LinearLayout f=form();f.addView(text(link?"支持 DeepSeek 公开分享链接。需要登录或验证的页面可改用导出文件。":"支持 DeepSeek / ChatGPT 导出 JSON，以及 TXT、Markdown 对话。没有角色标记的文字会作为资料导入。",13));
        EditText input=new EditText(activity);input.setHint(link?"粘贴分享链接":"粘贴聊天记录…");input.setTextSize(15);input.setMinLines(link?2:6);input.setMaxLines(9);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);f.addView(input);
        AlertDialog d=new AlertDialog.Builder(activity).setTitle(link?"读取分享链接":"粘贴聊天记录").setView(scroll(f)).setNegativeButton("取消",null).setPositiveButton("预览",null).create();
        d.setOnShowListener(v->d.getButton(-1).setOnClickListener(w->{String value=input.getText().toString().trim();if(value.isEmpty()){toast("请先填写内容");return;}
            if(link&&!app.store.setting("use_web","yes").equals("yes")){toast("请先在设置中开启联网搜索与读取");return;}
            WebClient c;try{c=link?new WebClient(app.store):null;}catch(Exception e){toast(MozhiApp.friendly(e));return;}d.dismiss();load(link?"正在读取分享内容…":"正在整理预览…",()->link?c.importLink(value):ChatImport.parse(value,"粘贴文字"),c);}));d.show();
    }
    private void load(String message,Callable<List<ChatImport.Transcript>> task){
        load(message,task,null);
    }
    private void load(String message,Callable<List<ChatImport.Transcript>> task,WebClient reader){
        if(app.busy){toast("请先等待当前回答结束");return;}int ticket=++generation;
        web=reader;progress=ProgressDialog.show(activity,"导入聊天",message,true,true);progress.setOnCancelListener(d->{generation++;if(reader!=null)reader.cancel();});
        app.io.execute(()->{try{if(ticket!=generation)return;List<ChatImport.Transcript> transcripts=task.call();if(ticket!=generation)return;
            app.main.post(()->{if(ticket!=generation||activity.isDestroyed())return;if(progress!=null)progress.dismiss();progress=null;preview(transcripts);});
        }catch(Exception e){app.main.post(()->{if(ticket!=generation||activity.isDestroyed())return;if(progress!=null)progress.dismiss();progress=null;toast(MozhiApp.friendly(e));});}finally{if(ticket==generation)web=null;}});
    }
    void preview(List<ChatImport.Transcript> transcripts){
        if(activity.isDestroyed()||transcripts.isEmpty())return;if(preview!=null&&preview.isShowing())preview.dismiss();
        LinearLayout f=form();int count=0,omitted=0;boolean reference=false;for(ChatImport.Transcript t:transcripts){count+=t.messages.size();omitted+=t.omitted;reference|=t.referenceOnly;}
        f.addView(text(transcripts.size()+" 个对话 · "+count+" 条记录",17));
        f.addView(text("保存在本机，可在对话历史中继续聊。不会直接写为个人事实，也不会覆盖已有对话。",13));
        if(reference)f.addView(text("部分文字没有角色标记，将作为引用资料保存。",12));
        if(omitted>0)f.addView(text("已略过思考过程、系统提示、工具信息或非文字附件。",12));
        int budget=6500;for(ChatImport.Transcript t:transcripts){if(budget<=0)break;f.addView(text(t.title,16));String value=t.text();String shown=value.substring(0,Math.min(value.length(),budget));budget-=shown.length();TextView body=text(shown,14);body.setTextIsSelectable(true);f.addView(body);if(shown.length()<value.length())f.addView(text("这里只显示部分预览，确认后保存完整记录。",12));}
        if(transcripts.size()>1)f.addView(text("将导入上方文件中的全部 "+transcripts.size()+" 个对话。",12));
        preview=new AlertDialog.Builder(activity).setTitle("确认导入").setView(scroll(f)).setNegativeButton("取消",null).setPositiveButton("导入",null).create();
        AlertDialog d=preview;d.setOnShowListener(v->d.getButton(-1).setOnClickListener(w->{if(app.busy){toast("请先等待当前回答结束");return;}
            d.getButton(-1).setEnabled(false);d.getButton(-2).setEnabled(false);d.setCancelable(false);
            app.io.execute(()->{try{ChatImport.Plan plan=app.store.importChats(transcripts);app.main.post(()->{if(activity.isDestroyed())return;d.dismiss();toast(plan.added==0?"这段记录已导入，已打开原对话":"已导入 "+plan.added+" 个对话 · "+plan.messages+" 条记录"+(plan.duplicates>0?"，跳过重复对话":""));opened.accept(plan.conversations.get(0));});}
                catch(Exception e){app.main.post(()->{if(activity.isDestroyed())return;d.getButton(-1).setEnabled(true);d.getButton(-2).setEnabled(true);d.setCancelable(true);toast("导入未完成："+MozhiApp.friendly(e));});}});
        }));d.show();
    }
    void close(){generation++;WebClient c=web;if(c!=null)c.cancel();if(progress!=null)progress.dismiss();if(preview!=null)preview.dismiss();}
}
