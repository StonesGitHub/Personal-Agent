package com.mozhi.assistant;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int INK=0xff433833,ACCENT=0xff956047,MUTED=0xff79685d,PAPER=0xfffffdfa,LINE=0xffecdfd2;
    private MozhiApp app;
    private AlertDialog skillListDialog;
    private Dialog historyDialog;
    private io.noties.markwon.Markwon markdown;
    private LinearLayout root,body,navigation;
    private DiscoveryUi discovery;
    private PersonalUi personalUi;
    private boolean openReminders;
    private ChatImportUi imports;
    private EditText composer;
    private TextView locationLabel;
    private boolean resumed,waitingForLocation;
    private int sendGeneration;
    private int tab=0;
    private String conversation="",draft="";
    private JSONArray pendingAttachments=new JSONArray();
    private boolean useMemory=true,loadedSelection=false,sharedInput=false;
    // Picker password lives only in Application memory until result or process death.

    private final Runnable observer=()->{if(!isFinishing()&&!isDestroyed()&&(tab!=5||body==null))render();};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);app=(MozhiApp)getApplication();personalUi=new PersonalUi(this,app);
        imports=new ChatImportUi(this,app,id->{conversation=id;draft="";composer=null;tab=0;app.store.putSetting("current",id);app.store.putSetting("draft","");render();});
        markdown=ReplyMarkdown.create(this,(view,url)->openReplyLink(url,view.getTag() instanceof Core.Item?(Core.Item)view.getTag():null));
        if(state!=null){tab=state.getInt("tab");if(tab==4)tab=3;conversation=state.getString("conversation","");draft=state.getString("draft","");useMemory=state.getBoolean("memory",true);try{pendingAttachments=new JSONArray(state.getString("pendingAttachments","[]"));}catch(JSONException ignored){pendingAttachments=new JSONArray();}}
        acceptShare(getIntent());
        getWindow().setStatusBarColor(PAPER);getWindow().setNavigationBarColor(PAPER);
        render();
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);acceptShare(i);render();}
    private void openReplyLink(String url,Core.Item source){
        Uri artifact=Uri.parse(url);
        if("mozhi".equals(artifact.getScheme())&&"artifact".equals(artifact.getHost())){openArtifact(artifact.getQueryParameter("id"),source);return;}
        MapLinks.Destination place=MapLinks.parse(url);
        if(place!=null){MapNavigator.open(this,place,()->{
            if(source==null)return;
            try{app.store.recordMapAction(source,place);}catch(Exception e){toast("地图已打开，记录暂未保存");}
        });return;}
        Uri link=Uri.parse(url);String scheme=link.getScheme();
        if(("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme))&&link.getHost()!=null){
            try{startActivity(new Intent(Intent.ACTION_VIEW,link));}catch(ActivityNotFoundException|SecurityException e){toast("没有可打开此链接的应用");}
        }else toast("链接无效，请让墨知重新生成地点链接");
    }
    private void acceptShare(Intent i){if(i.getBooleanExtra("open_reminders",false)){openReminders=true;i.removeExtra("open_reminders");}if(Intent.ACTION_SEND.equals(i.getAction())&&"text/plain".equals(i.getType())){
        String text=i.getStringExtra(Intent.EXTRA_TEXT);if(text!=null){composer=null;sharedInput=true;loadedSelection=true;loadedDraft=true;draft=text.substring(0,Math.min(8000,text.length()));tab=0;conversation="";}i.setAction(null);
    }}
    @Override protected void onStart(){super.onStart();app.observe(observer);render();}
    @Override protected void onResume(){super.onResume();resumed=true;if(app.store!=null){render();app.syncReminders();}}
    @Override protected void onStop(){app.unobserve(observer);super.onStop();}
    @Override protected void onSaveInstanceState(Bundle b){captureDraft();b.putInt("tab",tab);b.putString("conversation",conversation);b.putString("draft",draft);b.putBoolean("memory",useMemory);b.putString("pendingAttachments",pendingAttachments.toString());super.onSaveInstanceState(b);}
    @Override protected void onPause(){resumed=false;sendGeneration++;waitingForLocation=false;app.locations.stop();captureDraft();if(app.store!=null){app.store.putSetting("draft",draft);app.store.putSetting("current",conversation);}super.onPause();}
    @Override protected void onDestroy(){closeHistory();if(personalUi!=null)personalUi.close();if(imports!=null)imports.close();super.onDestroy();}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private void captureDraft(){if(composer!=null)draft=composer.getText().toString();}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private android.graphics.drawable.Drawable touch(int color,int radius){return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22956047),bg(color,radius),null);}
    private ImageView art(int drawable,int size,String description){ImageView v=new ImageView(this);v.setImageResource(drawable);v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setContentDescription(description);v.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));return v;}
    private void empty(LinearLayout parent,String title,String description){LinearLayout c=card(parent);c.setGravity(Gravity.CENTER_HORIZONTAL);c.addView(art(R.drawable.motuan,140,"墨团陪你慢慢整理"));TextView h=heading(title,21);h.setGravity(Gravity.CENTER);c.addView(h);gap(c,10);TextView d=text(description,14,MUTED);d.setGravity(Gravity.CENTER);c.addView(d);gap(c,12);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String value,int sp,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(sp);v.setTextColor(color);v.setLineSpacing(dp(3),1);return v;}
    private TextView heading(String value,int size){TextView v=text(value,size,INK);v.setTypeface(null,Typeface.BOLD);return v;}
    private void gap(LinearLayout l,int size){Space s=new Space(this);l.addView(s,new LinearLayout.LayoutParams(1,dp(size)));}
    private Button button(String value,Runnable click){Button b=new Button(this);b.setText(value);b.setTextSize(13);b.setTextColor(ACCENT);b.setAllCaps(false);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setStateListAnimator(null);b.setElevation(0);b.setTranslationZ(0);b.setPadding(dp(12),dp(6),dp(12),dp(6));b.setBackground(touch(0xffffefdf,16));b.setOnClickListener(v->click.run());return b;}
    private ImageButton iconButton(int drawable,String description,Runnable click){
        ImageButton b=new ImageButton(this);b.setImageResource(drawable);b.setColorFilter(ACCENT);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);b.setPadding(dp(12),dp(12),dp(12),dp(12));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));b.setMinimumWidth(dp(48));b.setMinimumHeight(dp(48));
        b.setStateListAnimator(null);b.setElevation(0);b.setTranslationZ(0);
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22956047),null,bg(Color.WHITE,16)));
        b.setContentDescription(description);b.setTooltipText(description);b.setOnClickListener(v->click.run());return b;
    }
    private void primary(Button b){b.setTextColor(Color.WHITE);b.setBackground(touch(ACCENT,18));}
    private LinearLayout card(LinearLayout parent){LinearLayout c=column();c.setPadding(dp(16),dp(15),dp(16),dp(15));c.setBackground(bg(0xfffffdf9,24));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);parent.addView(c,lp);return c;}
    private EditText input(String hint,String value,boolean multiline){EditText e=new EditText(this);e.setTextSize(15);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setHint(hint);e.setText(value);e.setPadding(dp(12),dp(12),dp(12),dp(12));e.setBackground(bg(0xfff5ebdf,18));e.setInputType(InputType.TYPE_CLASS_TEXT|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));e.setMinLines(multiline?3:1);e.setMaxLines(multiline?8:1);return e;}
    private LinearLayout form(){LinearLayout f=column();f.setPadding(dp(22),dp(8),dp(22),dp(10));return f;}
    private ScrollView scroll(View content){ScrollView s=new ScrollView(this);s.setFillViewport(true);s.setVerticalScrollBarEnabled(false);s.setOverScrollMode(View.OVER_SCROLL_NEVER);s.setVerticalFadingEdgeEnabled(false);s.addView(content);return s;}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void info(String title,String message){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了",null).show();}
    private void confirm(String title,String message,Runnable action){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确认",(d,w)->action.run()).show();}
    private boolean idle(){if(app.busy||waitingForLocation){toast("请先等待当前请求结束或点击停止");return false;}return true;}
    private String date(long millis){return new SimpleDateFormat("MM月dd日 HH:mm",Locale.CHINA).format(new Date(millis));}

    private void render(){
        captureDraft();composer=null;locationLabel=null;navigation=null;
        root=column();root.setBackgroundColor(PAPER);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(i.left,i.top,i.right,i.bottom);if(navigation!=null)navigation.setVisibility(insets.isVisible(WindowInsets.Type.ime())?View.GONE:View.VISIBLE);}
            else {v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());if(navigation!=null)navigation.setVisibility(insets.getSystemWindowInsetBottom()>dp(150)?View.GONE:View.VISIBLE);}
            return insets;
        });root.requestApplyInsets();
        if(app.store==null){LinearLayout loading=column();loading.setPadding(dp(28),dp(60),dp(28),0);loading.addView(heading("墨知",34));gap(loading,20);loading.addView(text(app.startupError.isEmpty()?"正在打开本机资料…":app.startupError,16,MUTED));root.addView(loading);return;}
        if(PhoneAccount.locked(app.store)){LinearLayout gate=form();gate.addView(heading("登录后继续",24));gate.addView(text("本机资料已保留，请验证原手机号 "+PhoneAccount.label(app.store)+"。",15,MUTED));Button login=new Button(this);login.setText("手机号登录");login.setOnClickListener(v->phoneAccount());gate.addView(login);root.addView(gate);return;}
        if(openReminders){openReminders=false;root.post(()->personalUi.reminders());}
        useMemory=app.store.setting("use_memory","yes").equals("yes");
        if(!loadedSelection){if(conversation.isEmpty()&&!sharedInput)conversation=app.store.setting("current","");loadedSelection=true;}
        // Restore a persisted draft only on the first display, never after a successful send.
        if(!loadedDraft){if(draft.isEmpty())draft=app.store.setting("draft","");loadedDraft=true;}
        LinearLayout header=row();header.setPadding(dp(12),dp(4),dp(8),dp(4));
        if(tab==1||tab==3)header.addView(iconButton(R.drawable.ic_back,"返回",()->showPage(6)));
        else if(tab==0){ImageView mark=art(R.drawable.ic_mozhi,30,"墨知");LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)mark.getLayoutParams();lp.leftMargin=dp(6);lp.rightMargin=dp(8);header.addView(mark);}
        String label=new String[]{"墨知","我的背景","决定","设置","服务端连接","发现","我"}[tab];
        TextView title=heading(label,20);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);header.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));title.setGravity(Gravity.CENTER_VERTICAL);
        if(tab==0){ImageButton add=iconButton(R.drawable.ic_add,"新建对话",this::newConversation);add.setEnabled(!app.busy&&!waitingForLocation);add.setAlpha(add.isEnabled()?1f:.4f);header.addView(add);header.addView(iconButton(R.drawable.ic_history,"对话历史",this::history));}
        else if(tab==1)header.addView(iconButton(R.drawable.ic_add,"添加背景",()->memoryEditor(null,"",null)));
        else if(tab==5)header.addView(iconButton(R.drawable.ic_refresh,"刷新发现",()->{if(discovery!=null)discovery.refresh();}));
        root.addView(header);
        body=column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        if(!app.notice.isEmpty()){TextView banner=text(app.notice,13,ACCENT);banner.setPadding(dp(22),dp(8),dp(22),dp(8));banner.setOnClickListener(v->{app.notice="";render();});body.addView(banner);}
        if(tab==0)chat();else if(tab==1)memories();else if(tab==2)decisions();else if(tab==3)settings();else if(tab==4)settings();else if(tab==5){discovery=new DiscoveryUi(this,app,url->openReplyLink(url,null));body.addView(discovery.feed(),new LinearLayout.LayoutParams(-1,-1));}else me();
        bottomNavigation();root.requestApplyInsets();
        if(resumed&&!app.busy&&!app.pendingImports.isEmpty()){List<ChatImport.Transcript> pending=app.pendingImports;app.pendingImports=Collections.emptyList();root.post(()->{if(!isDestroyed())imports.preview(pending);});}
        if(resumed&&locationEnabled())app.locations.startAutomatic(this::updateLocationLabel);
        else app.locations.stop();
    }
    private void showPage(int page){if(waitingForLocation){toast("正在定位，请稍候或停止");return;}captureDraft();closeHistory();tab=page;render();}
    @Override public void onBackPressed(){if(historyDialog!=null){closeHistory();return;}if(tab!=0){showPage(tab==4?3:tab==1||tab==3?6:0);return;}super.onBackPressed();}
    private void bottomNavigation(){
        navigation=column();View line=new View(this);line.setBackgroundColor(LINE);navigation.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));LinearLayout tabs=row();tabs.setPadding(dp(10),dp(5),dp(10),dp(5));navigation.addView(tabs);
        int[] pages={0,5,2,6},icons={R.drawable.ic_chat,R.drawable.ic_discover,R.drawable.ic_decision,R.drawable.ic_person};String[] names={"墨聊","发现","决定","我"};int active=tab==1||tab==3||tab==4?6:tab;
        for(int n=0;n<pages.length;n++){final int page=pages[n];boolean selected=active==page;LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setPadding(0,dp(5),0,dp(5));item.setMinimumHeight(dp(56));item.setBackground(touch(selected?0xffffefdf:PAPER,18));ImageView icon=art(icons[n],23,null);icon.setColorFilter(selected?ACCENT:MUTED);item.addView(icon);TextView label=text(names[n],11,selected?ACCENT:MUTED);label.setGravity(Gravity.CENTER);label.setLineSpacing(0,1);if(selected)label.setTypeface(null,Typeface.BOLD);item.addView(label);item.setContentDescription(names[n]);item.setSelected(selected);item.setFocusable(true);item.setOnClickListener(v->{if(tab!=page)showPage(page);});tabs.addView(item,new LinearLayout.LayoutParams(0,-2,1));}
        root.addView(navigation);
    }
    private void phoneAccount(){if(idle())new PhoneAccountUi(this,app,this::render).show();}
    private void me(){
        LinearLayout content=column();content.setPadding(dp(18),dp(24),dp(18),dp(24));body.addView(scroll(content),new LinearLayout.LayoutParams(-1,-1));
        LinearLayout profile=column();profile.setGravity(Gravity.CENTER);profile.addView(art(R.drawable.ic_mozhi,64,"墨团"));gap(profile,14);TextView greeting=heading("慢慢聊，更懂你",20);greeting.setGravity(Gravity.CENTER);profile.addView(greeting);gap(profile,7);TextView subtitle=text("重要的小事，都会留在这里",13,MUTED);subtitle.setGravity(Gravity.CENTER);profile.addView(subtitle);content.addView(profile);gap(content,30);
        content.addView(actionRow("个人账号",PhoneAccount.bound(app.store)?PhoneAccount.label(app.store):"手机号注册 / 登录 · 绑定旧资料",this::phoneAccount));
        content.addView(actionRow("我的背景",app.store.list("memory",null).size()+" 条背景 · 记忆与技能",()->showPage(1)));
        content.addView(actionRow("内置技能","出游 · 定时任务提醒 · 健康管理",this::skills));
        content.addView(actionRow("提醒与日程","查看、设置和取消提醒",()->personalUi.reminders()));
        content.addView(actionRow("健康记录","饮食、睡眠与健康观察",()->personalUi.health(app.remoteMode())));
        content.addView(actionRow("我的分享","问答与草稿",()->DiscoveryUi.myShares(this,app,url->openReplyLink(url,null))));
        content.addView(actionRow("导入聊天记录","分享链接、文件或文字",()->{if(idle())imports.show();}));
        content.addView(actionRow("设置","模型、定位与备份",()->showPage(3)));
    }
    private LinearLayout actionRow(String title,String detail,Runnable action){
        LinearLayout row=row();row.setPadding(dp(14),dp(10),dp(8),dp(10));row.setMinimumHeight(dp(60));row.setBackground(touch(PAPER,14));
        LinearLayout words=column();words.addView(text(title,16,INK));if(detail!=null&&!detail.isEmpty()){TextView note=text(detail,12,MUTED);note.setMaxLines(2);note.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(note);}row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        ImageView arrow=art(R.drawable.ic_chevron,20,null);arrow.setColorFilter(MUTED);row.addView(arrow);row.setOnClickListener(v->action.run());row.setContentDescription(title);row.setFocusable(true);return row;
    }
    private TextView section(String title){TextView t=text(title,12,MUTED);t.setPadding(dp(14),dp(24),0,dp(8));return t;}
    private boolean loadedDraft;
    private List<Core.Item> selected(String query){return useMemory?Core.memories(app.store.list("memory",null),query,System.currentTimeMillis()):new ArrayList<>();}
    private void chat(){
        List<Core.Item> messages=conversation.isEmpty()?new ArrayList<>():app.store.list("message",conversation);
        LinearLayout content=column();content.setPadding(dp(20),dp(16),dp(20),dp(8));ScrollView scroller=scroll(content);body.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        if(messages.isEmpty()){
            content.setGravity(Gravity.CENTER);ImageView mark=art(R.drawable.ic_mozhi,64,"墨团");content.addView(mark);gap(content,20);
            TextView hi=heading("想聊点什么？",23);hi.setGravity(Gravity.CENTER);content.addView(hi);
            if(!app.ready()){gap(content,14);TextView connect=text("连接 Agent 服务，开始聊天",14,ACCENT);connect.setGravity(Gravity.CENTER);connect.setPadding(dp(16),dp(12),dp(16),dp(12));connect.setBackground(touch(PAPER,14));connect.setOnClickListener(v->phoneAccount());content.addView(connect);}
        }
        for(Core.Item message:messages){
            boolean user=message.s("role").equals("user");LinearLayout block=column();LinearLayout.LayoutParams blockLp=new LinearLayout.LayoutParams(-1,-2);blockLp.bottomMargin=dp(user?24:30);content.addView(block,blockLp);
            if(message.data.optBoolean("imported")){TextView origin=text(message.data.optBoolean("reference_only")?"导入资料":user?"导入 · 原用户":"导入 · 原助手",11,MUTED);block.addView(origin);}
            TextView answer=text(message.s("text"),16,INK);answer.setLineSpacing(dp(5),1);answer.setPadding(0,0,0,0);
            if(user){block.setGravity(Gravity.END);answer.setTextIsSelectable(true);answer.setPadding(dp(15),dp(11),dp(15),dp(11));answer.setBackground(bg(0xfff3e9df,20));answer.setMaxWidth(Math.max(dp(180),getResources().getDisplayMetrics().widthPixels-dp(64)));block.addView(answer);}
            else {
                answer.setTag(message);markdown.setMarkdown(answer,message.s("text"));block.addView(answer,new LinearLayout.LayoutParams(-1,-2));
                LinearLayout actions=row();actions.setContentDescription("回复操作");replyActions(actions,message);
                gap(block,2);block.addView(actions);
            }
            if(user&&Arrays.asList("failed","cancelled","interrupted","remote_interrupted").contains(message.s("status"))){TextView retry=text(message.s("remote_payload").isEmpty()?"未完成 · 点此重试":"未完成 · 同步或继续服务端任务",12,ACCENT);retry.setPadding(0,dp(8),0,dp(8));retry.setOnClickListener(v->retry(message));block.addView(retry);}
        }
        if(app.busy||waitingForLocation){TextView progress=text(waitingForLocation?"正在获取位置…":app.operation,13,MUTED);progress.setPadding(0,dp(6),0,dp(18));content.addView(progress);}
        LinearLayout outer=column();outer.setPadding(dp(12),dp(5),dp(12),dp(10));LinearLayout box=column();GradientDrawable surface=bg(0xfff6f2ec,24);surface.setStroke(dp(1),0xffeee7dd);box.setBackground(surface);box.setPadding(dp(6),dp(4),dp(6),dp(4));outer.addView(box);
        if(pendingAttachments.length()>0){
            LinearLayout attachments=column();attachments.setPadding(dp(10),dp(3),dp(10),dp(3));
            for(int i=0;i<pendingAttachments.length();i++){
                final int index=i;JSONObject item=pendingAttachments.optJSONObject(i);
                if(item==null)continue;
                TextView label=text("📎 "+item.optString("name")+"  ×",12,ACCENT);
                label.setPadding(dp(8),dp(6),dp(8),dp(6));
                label.setOnClickListener(v->{if(idle()){captureDraft();pendingAttachments.remove(index);render();}});
                attachments.addView(label);
            }
            box.addView(attachments);
        }
        composer=input("发消息…",draft,true);composer.setBackgroundColor(Color.TRANSPARENT);composer.setMinLines(1);composer.setMaxLines(5);composer.setTextSize(16);composer.setEnabled(!waitingForLocation);composer.setPadding(dp(12),dp(10),dp(12),dp(5));box.addView(composer,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout tools=row();tools.addView(iconButton(R.drawable.ic_attach,"添加附件",this::pickAttachment));tools.addView(iconButton(R.drawable.ic_context,"本次背景",this::preview));
        ImageButton location=iconButton(R.drawable.ic_location,"当前位置",this::locationOptions);location.setAlpha(locationEnabled()?1f:.55f);tools.addView(location);
        locationLabel=text("",11,MUTED);locationLabel.setSingleLine(true);locationLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);tools.addView(locationLabel,new LinearLayout.LayoutParams(0,-2,1));updateLocationLabel();
        boolean stop=app.busy||waitingForLocation;ImageButton send=iconButton(stop?R.drawable.ic_stop:R.drawable.ic_send,stop?"停止":"发送",()->{if(waitingForLocation){sendGeneration++;waitingForLocation=false;app.locations.stop();render();}else if(app.busy)app.cancel();else send(null);});
        send.setColorFilter(Color.WHITE);send.setBackground(touch(ACCENT,24));tools.addView(send);box.addView(tools);body.addView(outer);
        if(!messages.isEmpty())scroller.post(()->scroller.scrollTo(0,content.getHeight()));
    }
    private void pickAttachment(){
        if(!idle())return;
                if(pendingAttachments.length()>=ChatAttachments.MAX_FILES){toast("每次最多添加 3 个附件");return;}
        captureDraft();
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "image/png","image/jpeg","image/webp","image/gif"});
        try{startActivityForResult(intent,ChatAttachments.PICK_FILE);}
        catch(ActivityNotFoundException e){toast("手机上没有可用的文件管理器");}
    }
    private void attachmentResult(int result,Intent data){
        if(result!=RESULT_OK||data==null||!idle())return;
        List<Uri> picked=new ArrayList<>();
        if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)picked.add(data.getClipData().getItemAt(i).getUri());
        else if(data.getData()!=null)picked.add(data.getData());
        for(Uri uri:picked){
            if(pendingAttachments.length()>=ChatAttachments.MAX_FILES){toast("每次最多添加 3 个附件");break;}
            boolean duplicate=false;
            for(int i=0;i<pendingAttachments.length();i++)if(uri.toString().equals(pendingAttachments.optJSONObject(i).optString("uri")))duplicate=true;
            if(duplicate)continue;
            try{
                getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                pendingAttachments.put(ChatAttachments.describe(this,uri));
            }catch(Exception e){toast(e.getMessage()==null?"无法添加此附件":e.getMessage());}
        }
        render();
    }
    private void copyMessage(Core.Item message){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("墨知",message.s("text")));toast("已复制");}
    private void replyActions(LinearLayout actions,Core.Item message){
        actions.removeAllViews();String[] values={"like","dislike"},labels={"点赞回答","点踩回答"};int[] icons={R.drawable.ic_like,R.drawable.ic_dislike};
        for(int n=0;n<2;n++){String value=values[n];ImageButton b=replyIcon(icons[n],labels[n],()->{try{message.data.put("feedback",ReplySharing.toggledFeedback(message.s("feedback"),value));app.store.save(message);replyActions(actions,message);}catch(Exception e){toast("反馈暂未保存");}});boolean selected=value.equals(message.s("feedback"));b.setSelected(selected);b.setAlpha(selected?1f:.72f);b.setColorFilter(selected?ACCENT:MUTED);if(selected)b.setBackground(touch(0xffffefdf,16));actions.addView(b);}
        actions.addView(replyIcon(R.drawable.ic_copy,"复制回复",()->copyMessage(message)));
        actions.addView(replyIcon(R.drawable.ic_share,"分享到发现",()->{try{DiscoveryUi.share(this,app,ReplySharing.pair(app.store.list("message",message.parent),message),url->openReplyLink(url,null));}catch(Exception e){toast(e.getMessage());}}));
        ImageButton more=replyIcon(R.drawable.ic_more,"更多回复操作",()->{});more.setOnClickListener(v->{PopupMenu menu=new PopupMenu(this,more);menu.getMenu().add("选择文字");JSONArray trace=message.data.optJSONArray("agent_trace");if(trace!=null&&trace.length()>0)menu.getMenu().add("助理记录");menu.setOnMenuItemClickListener(i->{if(i.getTitle().equals("选择文字"))selectMessage(message);else showTrace(message);return true;});menu.show();});actions.addView(more);
    }
    private ImageButton replyIcon(int drawable,String description,Runnable action){
        ImageButton icon=iconButton(drawable,description,action);icon.setColorFilter(MUTED);icon.setAlpha(.8f);icon.setPadding(dp(14),dp(14),dp(14),dp(14));return icon;
    }
    private void selectMessage(Core.Item message){
        LinearLayout f=form();TextView text=text(markdown.toMarkdown(message.s("text")).toString(),16,INK);text.setTextIsSelectable(true);f.addView(text);
        new AlertDialog.Builder(this).setTitle("选择文字").setView(scroll(f)).setPositiveButton("完成",null).show();
    }
    private void showTrace(Core.Item message){
        JSONArray trace=message.data.optJSONArray("agent_trace");StringBuilder lines=new StringBuilder();
        if(trace!=null)for(int n=0;n<trace.length();n++)lines.append("• ").append(trace.optString(n)).append("\n");
        info("助理记录",lines.toString());
    }
    private boolean locationEnabled(){return app.store!=null&&app.store.setting("use_location","no").equals("yes");}
    private void updateLocationLabel(){if(locationLabel==null)return;LocationContext.Snapshot fix=app.locations.current();locationLabel.setText(!locationEnabled()?"":fix!=null?(fix.area.isEmpty()?"位置已加入":fix.area):app.locations.status);}
    private void locationOptions(){
        if(!idle())return;
        if(!locationEnabled()){
            new AlertDialog.Builder(this).setTitle("使用当前位置？").setMessage("开启后，使用墨知时会自动更新位置，并随消息发送给你选择的模型。可在设置中关闭。")
                .setNegativeButton("暂不开启",(d,w)->render()).setOnCancelListener(d->render()).setPositiveButton("开启",(d,w)->{app.store.putSetting("use_location","yes");requestLocationPermission();}).show();return;
        }
        if(!app.locations.permitted()){requestLocationPermission();return;}
        LocationContext.Snapshot fix=app.locations.current();String state=fix==null?app.locations.status:fix.area+(fix.area.isEmpty()?"":"\n")+String.format(Locale.ROOT,"%.5f, %.5f · 约 %.0f 米",fix.latitude,fix.longitude,fix.accuracy);
        new AlertDialog.Builder(this).setTitle("当前位置").setMessage(state+"\n\n使用期间自动更新").setPositiveButton("完成",null).setNeutralButton("定位设置",(d,w)->showPage(3)).show();
    }
    private void requestLocationPermission(){
        if(!app.locations.permitted()){requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_FINE_LOCATION},51);return;}
        if(!app.locations.systemEnabled()){new AlertDialog.Builder(this).setTitle("请开启系统定位").setMessage("开启手机的位置服务后，墨知才能获取当前位置。").setPositiveButton("去开启",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))).setNegativeButton("稍后",null).show();}
        render();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==73){app.syncReminders();personalUi.reminders();}if(request==51){if(app.locations.permitted())requestLocationPermission();else{app.store.putSetting("use_location","no");app.locations.clear();toast("未开启定位，仍可正常聊天");render();}}}
    private void preview(){
        captureDraft();
        List<Core.Item> ms=selected(draft);
        StringBuilder remote=new StringBuilder("发送到：").append(app.agentBase())
            .append("\n\n将发送本轮消息、当前对话最近约18000字、选中的背景、附件及已开启的位置。")
            .append("服务端保存任务与相关上下文，可在手机断开后继续；不会自动上传整个记忆库。")
            .append("\n\n选中的个人背景：\n");
        for(Core.Item m:ms)remote.append("• ").append(m.s("text")).append("\n");
        info("本次会参考什么",remote.toString());
    }
    private void send(Core.Item retry){
        if(!idle())return;captureDraft();String value=retry==null?draft.trim():retry.s("text");
        if(value.isEmpty()&&pendingAttachments.length()==0)return;
        if(value.length()>8000){toast("单次消息请控制在 8000 字以内");return;}
        if(!app.ready()){agentSettings();toast("请先连接 Agent 服务");return;}
        List<Core.Item> mem=selected(value);
        String approvedKey="approved_agent_endpoint";
        if(!app.store.setting(approvedKey,"").equals(app.destination())){
            confirm("连接 Agent 服务","本次消息、当前对话片段和选中的 "+mem.size()+" 条背景将发送到：\n"+app.destination()+"\n\n服务端会保存任务、相关上下文和生成文件，并调用管理员配置的模型。手机断开后任务仍可继续；手机不持有大模型 API Key。",()->{app.store.putSetting(approvedKey,app.destination());send(retry);});return;
        }
        if(locationEnabled()&&app.locations.permitted()&&app.locations.current()==null){
            waitingForLocation=true;int ticket=++sendGeneration;render();
            app.locations.request(fix->{if(ticket!=sendGeneration||!resumed||isDestroyed())return;waitingForLocation=false;if(fix==null)toast("暂未获取位置，本条消息不携带位置");sendNow(retry);});return;
        }
        sendNow(retry);
    }
    private void sendNow(Core.Item retry){
        captureDraft();String value=retry==null?draft.trim():retry.s("text");
        if(value.isEmpty()&&pendingAttachments.length()>0)value="请分析附件内容";
        List<Core.Item> mem=selected(value);
        if(conversation.isEmpty()){
            Core.Item c=app.store.add("conversation","",Core.obj("title",value.substring(0,Math.min(24,value.length()))));conversation=c.id;app.store.putSetting("current",conversation);
        }
        Core.Item user=retry;
        if(user==null){
            String visible=value;
            if(pendingAttachments.length()>0){
                StringBuilder names=new StringBuilder();
                for(int i=0;i<pendingAttachments.length();i++)names.append("\n📎 ").append(pendingAttachments.optJSONObject(i).optString("name"));
                visible+=names.toString();
            }
            user=app.store.add("message",conversation,Core.obj("role","user","text",visible,"status","pending"));
            if(pendingAttachments.length()>0){
                try{user.data.put("pending_attachments",new JSONArray(pendingAttachments.toString()));app.store.save(user);}
                catch(Exception e){toast("无法保存附件列表");return;}
            }
        }
        else {try{user.data.put("status","pending");app.store.save(user);}catch(Exception e){toast("无法保存消息");return;}}
        List<Core.Item> history=app.store.list("message",conversation);
        if(retry!=null){int at=-1;for(int n=0;n<history.size();n++)if(history.get(n).id.equals(user.id))at=n;if(at>=0)history=new ArrayList<>(history.subList(0,at+1));}
        JSONArray request=Core.messages(history,mem);
        LocationContext.append(request,locationEnabled(),app.locations.current(),SystemClock.elapsedRealtime());
        draft="";pendingAttachments=new JSONArray();if(composer!=null)composer.setText("");app.store.putSetting("draft","");app.send(conversation,user,request,useMemory);render();
    }
    private void retry(Core.Item message){
        if(!idle())return;
        if(!message.s("remote_payload").isEmpty()){
            if(!message.s("remote_origin").equals(app.agentBase())){toast("请先连接此任务原来的 Agent 服务");return;}
            app.continueRemote(message);return;
        }
        // A retry of an older turn branches, avoiding out-of-order assistant messages.
        List<Core.Item> all=app.store.list("message",conversation);
        if(!all.isEmpty()&&!all.get(all.size()-1).id.equals(message.id)){
            draft=message.s("text");composer.setText(draft);toast("已放回输入框，将作为新的追问发送");return;
        }send(message);
    }
    private void closeHistory(){Dialog d=historyDialog;historyDialog=null;if(d!=null)d.dismiss();}
    private void newConversation(){
        if(!idle())return;closeHistory();conversation="";draft="";pendingAttachments=new JSONArray();if(composer!=null)composer.setText("");
        app.store.putSetting("current","");app.store.putSetting("draft","");render();
    }
    private void openConversation(Core.Item item){
        if(!idle())return;closeHistory();if(conversation.equals(item.id))return;
        conversation=item.id;draft="";pendingAttachments=new JSONArray();if(composer!=null)composer.setText("");
        app.store.putSetting("current",item.id);app.store.putSetting("draft","");render();
    }
    private void history(){
        if(historyDialog!=null&&historyDialog.isShowing())return;
        captureDraft();View focus=getCurrentFocus();if(focus!=null)((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);
        List<Core.Item> items=app.store.list("conversation",null);Collections.reverse(items);
        Dialog dialog=new Dialog(this);historyDialog=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);dialog.setCanceledOnTouchOutside(true);
        LinearLayout panel=column();panel.setPadding(dp(16),dp(12),dp(16),dp(16));
        GradientDrawable surface=bg(PAPER,0);surface.setCornerRadii(new float[]{dp(24),dp(24),0,0,0,0,dp(24),dp(24)});panel.setBackground(surface);
        panel.setOnApplyWindowInsetsListener((v,insets)->{int top,bottom;
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars());top=i.top;bottom=i.bottom;}
            else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}
            v.setPadding(dp(16),dp(12)+top,dp(16),dp(16)+bottom);return insets;
        });
        LinearLayout top=row();top.addView(heading("对话",18),new LinearLayout.LayoutParams(0,-2,1));top.addView(iconButton(R.drawable.ic_close,"收起历史",dialog::dismiss));panel.addView(top);gap(panel,12);
        LinearLayout list=column();ScrollView listScroll=scroll(list);panel.addView(listScroll,new LinearLayout.LayoutParams(-1,0,1));
        if(items.isEmpty()){LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(16),dp(56),dp(16),dp(24));ImageView mark=art(R.drawable.ic_chat,32,null);mark.setColorFilter(MUTED);empty.addView(mark);gap(empty,16);TextView hint=text("聊过的内容，会留在这里",14,MUTED);hint.setGravity(Gravity.CENTER);empty.addView(hint);list.addView(empty);}
        for(Core.Item item:items){
            boolean selected=conversation.equals(item.id);LinearLayout entry=column();entry.setPadding(dp(12),dp(12),dp(12),dp(12));entry.setMinimumHeight(dp(68));entry.setBackground(touch(selected?0xfff6e4d2:PAPER,16));entry.setSelected(selected);
            TextView name=text(item.s("title"),15,INK);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);if(selected)name.setTypeface(null,Typeface.BOLD);entry.addView(name);gap(entry,4);entry.addView(text(date(item.created),11,MUTED));
            entry.setContentDescription(item.s("title")+(selected?"，当前对话":""));entry.setFocusable(true);entry.setOnClickListener(v->openConversation(item));
            entry.setOnLongClickListener(v->{PopupMenu menu=new PopupMenu(this,entry);menu.getMenu().add("删除对话");menu.setOnMenuItemClickListener(action->{if(!idle())return true;closeHistory();confirm("删除这段对话？","消息将从本机删除。你另存的个人背景和决定仍保留。",()->{if(!idle())return;app.store.remove(item);if(conversation.equals(item.id)){conversation="";draft="";if(composer!=null)composer.setText("");app.store.putSetting("current","");app.store.putSetting("draft","");}render();});return true;});menu.show();return true;});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(6);list.addView(entry,lp);
        }
        dialog.setContentView(panel);Window window=dialog.getWindow();
        if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.END|Gravity.TOP);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.22f);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);window.setWindowAnimations(R.style.HistoryDrawerAnimation);}
        dialog.setOnDismissListener(d->{if(historyDialog==dialog)historyDialog=null;});dialog.show();
        if(window!=null){int available=root.getWidth()>0?root.getWidth():getResources().getDisplayMetrics().widthPixels;window.setLayout(Math.min(dp(340),(int)(available*.88f)),WindowManager.LayoutParams.MATCH_PARENT);}panel.requestApplyInsets();
    }
    private void memories(){
        LinearLayout content=column();content.setPadding(dp(14),0,dp(14),dp(16));body.addView(scroll(content),new LinearLayout.LayoutParams(-1,0,1));
        content.addView(actionRow("记忆与技能",app.store.list("memory",null).size()+" 条背景 · "+app.store.list("skill",null).size()+" 个技能",this::memoryOptions));
        if(!app.suggestions.isEmpty()){
            content.addView(section("待确认"));for(String suggestion:new ArrayList<>(app.suggestions))content.addView(actionRow(suggestion,"点此编辑并保存",()->memoryEditor(null,suggestion,()->app.suggestions.remove(suggestion))));
            TextView dismiss=text("忽略这些内容",13,MUTED);dismiss.setPadding(dp(14),dp(12),0,dp(18));dismiss.setOnClickListener(v->{app.suggestions.clear();render();});content.addView(dismiss);
        }
        List<Core.Item> memories=app.store.list("memory",null);Collections.reverse(memories);
        if(memories.isEmpty()){LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(80),dp(24),dp(60));empty.addView(art(R.drawable.ic_mozhi,52,"墨团"));gap(empty,18);empty.addView(text("慢慢聊，慢慢了解你",18,INK));gap(empty,8);TextView hint=text("也可以点右上角 ＋，记下一件小事。",13,MUTED);hint.setGravity(Gravity.CENTER);empty.addView(hint);content.addView(empty);}
        for(Core.Item item:memories){boolean expired=item.data.optLong("expires",0)>0&&item.data.optLong("expires")<=System.currentTimeMillis();String state=expired?"已过期":!item.data.optBoolean("enabled",true)?"已停用":item.data.optBoolean("global",false)?"始终参考":"相关时参考";content.addView(actionRow(item.s("text"),state,()->memoryEditor(item,"",null)));}
    }
    private void memoryOptions(){
        LinearLayout f=form();Switch learn=new Switch(this);learn.setText("自动记忆");learn.setTextColor(INK);learn.setChecked(app.store.setting("auto_learning","yes").equals("yes"));learn.setPadding(0,dp(12),0,dp(12));learn.setOnCheckedChangeListener((v,on)->{if(idle())app.store.putSetting("auto_learning",on?"yes":"no");});f.addView(learn);
        f.addView(text("自动整理明确的偏好、背景与决定，可随时修改。",13,MUTED));gap(f,16);f.addView(actionRow("技能库",null,this::skills));new AlertDialog.Builder(this).setTitle("记忆与技能").setView(f).setPositiveButton("完成",null).show();
    }
    private void memoryEditor(Core.Item item,String seed,Runnable after){
        if(!idle())return;LinearLayout f=form();EditText text=input("写下明确的事实、偏好或约束",item==null?seed:item.s("text"),true);f.addView(text);gap(f,10);
        CheckBox organize=new CheckBox(this);organize.setText("整理为多条背景");organize.setTextSize(13);organize.setTextColor(MUTED);if(item==null){f.addView(organize);TextView hint=text("勾选后由当前模型整理，确认后才保存。",12,MUTED);f.addView(hint);}
        LinearLayout extra=column();extra.setVisibility(View.GONE);TextView expand=text("更多选项",13,ACCENT);expand.setPadding(0,dp(12),0,dp(12));expand.setOnClickListener(v->{extra.setVisibility(extra.getVisibility()==View.GONE?View.VISIBLE:View.GONE);});f.addView(expand);f.addView(extra);
        EditText category=input("类别，例如家庭、工作、偏好",item==null?"个人":item.s("category"),false);extra.addView(category);gap(extra,8);
        CheckBox global=new CheckBox(this);global.setText("始终参考（适合通用偏好）");global.setChecked(item!=null&&item.data.optBoolean("global",false));extra.addView(global);
        CheckBox enabled=new CheckBox(this);enabled.setText("启用这条背景");enabled.setChecked(item==null||item.data.optBoolean("enabled",true));extra.addView(enabled);
        EditText days=input("有效天数；留空表示长期有效","",false);days.setInputType(InputType.TYPE_CLASS_NUMBER);if(item!=null&&item.data.optLong("expires",0)>0)days.setText(String.valueOf(Math.max(0,(item.data.optLong("expires")-System.currentTimeMillis()+86399999)/86400000)));extra.addView(days);gap(extra,6);extra.addView(text("0 表示立即过期。保存时更新有效期。",12,MUTED));
        if(item!=null){if(!item.s("evidence").isEmpty()){gap(extra,10);extra.addView(text("原话："+item.s("evidence"),12,MUTED));}if(!item.s("previous_text").isEmpty())extra.addView(button("查看上次内容",()->info("上次背景",item.s("previous_text"))));}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(item==null?"添加背景":"编辑背景").setView(scroll(f)).setNegativeButton("取消",null).setPositiveButton("保存",null).setNeutralButton(item==null?"":"删除",null).create();dialog.setOnShowListener(v->{
            organize.setOnCheckedChangeListener((box,on)->dialog.getButton(-1).setText(on?"整理":"保存"));
            dialog.getButton(-1).setOnClickListener(w->{if(!idle())return;String value=text.getText().toString().trim();
                if(item==null&&organize.isChecked()){if(value.isEmpty()||value.length()>12000){toast("请填写 1 至 12000 字");return;}if(!app.ready()){toast("请先在设置中连接模型");return;}dialog.dismiss();app.extract(value);return;}
                if(value.isEmpty()||value.length()>2000){toast("背景内容需为 1 至 2000 字");return;}
                try{String dd=days.getText().toString().trim();long expiry=0;if(!dd.isEmpty()){int day=Integer.parseInt(dd);if(day>36500)throw new IllegalArgumentException();expiry=System.currentTimeMillis()+day*86400000L;}
                    JSONObject data=Core.obj("text",value,"category",category.getText().toString().trim(),"global",global.isChecked(),"enabled",enabled.isChecked(),"expires",expiry,"updated",System.currentTimeMillis(),"source","用户确认");
                    if(item==null)app.store.add("memory","",data);else {JSONObject merged=new JSONObject(item.data.toString());Iterator<String> fields=data.keys();while(fields.hasNext()){String field=fields.next();merged.put(field,data.get(field));}app.store.save(new Core.Item(item.id,item.kind,item.parent,item.created,merged));}if(after!=null)after.run();dialog.dismiss();render();
                }catch(Exception e){toast("请检查有效天数，最大 36500 天");}
            });
            if(item!=null)dialog.getButton(-3).setOnClickListener(w->confirm("忘记这条背景？","之后的新请求将不再从记忆库调用它。原对话中的内容仍存在，可另行删除对话。",()->{app.store.remove(item);dialog.dismiss();render();}));
        });dialog.show();
    }
    private void extract(){
        if(!idle())return;if(!app.ready()){tab=3;render();toast("请先配置模型");return;}
        LinearLayout f=form();f.addView(text("粘贴你自己的介绍或对话。只有本框内容会发送给模型整理，不会自动保存。",13,MUTED));gap(f,10);EditText source=input("粘贴文字…","",true);f.addView(source);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("整理背景").setView(scroll(f)).setNegativeButton("取消",null).setPositiveButton("发送并整理",null).create();d.setOnShowListener(v->d.getButton(-1).setOnClickListener(w->{String s=source.getText().toString().trim();if(s.isEmpty()||s.length()>12000){toast("请填写 1 至 12000 字");return;}d.dismiss();app.extract(s);}));d.show();
    }
    private void decisions(){
        LinearLayout content=column();content.setPadding(dp(14),dp(6),dp(14),dp(16));body.addView(scroll(content),new LinearLayout.LayoutParams(-1,0,1));
        List<Core.Item> items=app.store.list("decision",null);items.sort((a,b)->Long.compare(b.data.optLong("updated",b.created),a.data.optLong("updated",a.created)));
        if(items.isEmpty()){
            gap(content,72);content.setGravity(Gravity.CENTER_HORIZONTAL);content.addView(art(R.drawable.ic_mozhi,64,"墨团"));gap(content,20);content.addView(heading("聊着聊着，就有了决定",20));gap(content,10);
            TextView hint=text("告诉我你的选择，我会自动整理在这里。",14,MUTED);hint.setGravity(Gravity.CENTER);content.addView(hint);
        }
        for(Core.Item item:items)content.addView(actionRow(item.s("title"),item.s("status")+" · "+date(item.data.optLong("updated",item.created)),()->decisionEditor(item,"")));
    }
    private void decisionEditor(Core.Item item,String seed){
        if(!idle())return;LinearLayout f=form();EditText title=input("决定的标题",item==null?"":item.s("title"),false);f.addView(title);gap(f,9);EditText note=input("备选项、理由、当前建议",item==null?seed:item.s("note"),true);f.addView(note);gap(f,9);
        Spinner status=new Spinner(this);String[] states={"考虑中","已决定","已完成","已放弃",DecisionRecords.OPENED_MAP};status.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,states));if(item!=null)for(int i=0;i<states.length;i++)if(states[i].equals(item.s("status")))status.setSelection(i);f.addView(status);gap(f,8);EditText outcome=input("实际结果与复盘",item==null?"":item.s("outcome"),true);f.addView(outcome);
        if(item!=null&&!item.s("evidence").isEmpty()){gap(f,12);f.addView(text("记录依据："+item.s("evidence"),13,MUTED));}
        AlertDialog d=new AlertDialog.Builder(this).setTitle(item==null?"记录决定":"查看决定").setView(scroll(f)).setNegativeButton("取消",null).setPositiveButton("保存",null).setNeutralButton(item==null?"":"删除",null).create();d.setOnShowListener(v->{d.getButton(-1).setOnClickListener(w->{String name=title.getText().toString().trim();if(name.isEmpty()){toast("请填写标题");return;}
            if(name.length()>100||note.length()>20000||outcome.length()>8000){toast("内容过长，请精简后保存");return;}
            JSONObject data=Core.obj("title",name,"note",note.getText().toString(),"status",states[status.getSelectedItemPosition()],"outcome",outcome.getText().toString());
            try{
                JSONObject merged=item==null?new JSONObject():new JSONObject(item.data.toString());Iterator<String> keys=data.keys();while(keys.hasNext()){String key=keys.next();merged.put(key,data.get(key));}
                merged.put("edited_by_user",true);merged.put("updated",System.currentTimeMillis());
                if(item==null)app.store.add("decision","",merged);else app.store.save(new Core.Item(item.id,item.kind,item.parent,item.created,merged));d.dismiss();render();
            }catch(JSONException e){toast("暂时无法保存修改");}
        });if(item!=null&&!item.s("builtin_id").isEmpty())d.getButton(-3).setVisibility(View.GONE);else if(item!=null)d.getButton(-3).setOnClickListener(w->confirm("删除决定？","将从本机删除这条记录。",()->{app.store.remove(item);d.dismiss();render();}));});d.show();
    }
    private void settings(){
        LinearLayout content=column();content.setPadding(dp(14),0,dp(14),dp(24));body.addView(scroll(content),new LinearLayout.LayoutParams(-1,0,1));
        content.addView(section("个人账号"));content.addView(actionRow("手机号账号",PhoneAccount.bound(app.store)?PhoneAccount.label(app.store):"注册 / 登录与旧数据导入",this::phoneAccount));
        content.addView(section("连接"));content.addView(actionRow("Agent 服务",app.ready()?"服务端已连接":"请配置服务端地址和用户凭证",this::agentSettings));
        content.addView(text("大模型和 API Key 由服务器统一管理，手机不配置或直接调用模型。",12,MUTED));
        content.addView(actionRow("同步服务端任务","取回断线后仍在执行的任务",()->{if(idle()){app.resumeRemote();toast(app.busy?"正在同步任务":"没有待同步的任务");}}));
        Switch web=new Switch(this);web.setText("联网搜索与读取");web.setTextSize(16);web.setTextColor(INK);web.setPadding(dp(14),dp(12),dp(14),dp(12));web.setMinHeight(dp(56));web.setChecked(app.store.setting("use_web","yes").equals("yes"));web.setContentDescription("联网搜索与读取");
        web.setOnCheckedChangeListener((button,enabled)->{if(!idle()){button.setChecked(app.store.setting("use_web","yes").equals("yes"));return;}app.store.putSetting("use_web",enabled?"yes":"no");});content.addView(web);
        TextView webHint=text("按需联网，发送搜索词或目标链接。搜索及网页读取由服务端统一执行。",12,MUTED);webHint.setPadding(dp(14),0,dp(14),dp(8));content.addView(webHint);
        Switch location=new Switch(this);location.setText("自动定位");location.setTextSize(16);location.setTextColor(INK);location.setPadding(dp(14),dp(12),dp(14),dp(12));location.setMinHeight(dp(56));location.setChecked(locationEnabled());location.setContentDescription("自动定位");
        location.setOnCheckedChangeListener((button,enabled)->{if(enabled==locationEnabled())return;if(enabled){button.setChecked(false);locationOptions();}else{app.store.putSetting("use_location","no");app.locations.clear();updateLocationLabel();}});content.addView(location);
        TextView locationHint=text("使用时自动更新位置，作为对话背景。",12,MUTED);locationHint.setPadding(dp(14),0,dp(14),dp(8));content.addView(locationHint);
        content.addView(section("助理"));
        Switch memory=new Switch(this);memory.setText("长期记忆");memory.setTextSize(16);memory.setTextColor(INK);memory.setPadding(dp(14),dp(12),dp(14),dp(12));memory.setMinHeight(dp(56));memory.setChecked(useMemory);memory.setContentDescription("长期记忆");
        memory.setOnCheckedChangeListener((button,enabled)->{if(!idle()){button.setChecked(useMemory);return;}useMemory=enabled;app.store.putSetting("use_memory",enabled?"yes":"no");});content.addView(memory);
        TextView hint=text("默认开启，自动参考背景、整理明确的决定。",12,MUTED);hint.setPadding(dp(14),0,dp(14),dp(8));content.addView(hint);
        content.addView(section("备份"));content.addView(actionRow("自动备份",app.portable!=null&&app.portable.enabled()?app.portable.status():"未开启",this::backupSettings));
        content.addView(actionRow("导入旧版备份","补充记录，保留本机已有资料",()->backup(true,true)));
        content.addView(actionRow("导出加密备份",null,()->backup(false)));
        content.addView(actionRow("恢复备份",null,()->backup(true)));
        content.addView(section("其他"));content.addView(actionRow("关于墨知",BuildConfig.VERSION_NAME,()->info("墨知 "+BuildConfig.VERSION_NAME,"陪你想清楚，再做决定。\n\n所有模型调用统一由 Agent 服务端执行；任务与选中的上下文会保存在服务端。手机不保存大模型密钥。\n"+(app.community.localOnly()?"发现提供示例问答。你的分享、点赞和评论仅保存在本机。":"发现仅上传你确认发布的问答，以及主动提交的点赞、评论。")+"\n支持长期记忆、技能、Markdown、自动定位背景与地图跳转。")));
        content.addView(actionRow("数据管理",null,this::dataSettings));
    }
    private void gatewaySettings(){
        if(!idle())return;LinearLayout f=form();f.addView(text("留空使用手机直连。配置后，仅搜索词与要读取的链接发送到此服务。",13,MUTED));gap(f,12);
        String saved=app.store.setting("web_gateway","");EditText url=input("HTTPS 服务地址",saved,false);url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);f.addView(url);gap(f,10);
        EditText token=input("服务凭证 · 留空保留原凭证","",false);token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);f.addView(token);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("联网服务").setView(f).setNegativeButton("取消",null).setPositiveButton("保存",null).setNeutralButton("手机直连",(v,w)->{app.store.putSetting("web_gateway","");app.store.putSetting("web_token","");render();}).create();
        d.setOnShowListener(v->d.getButton(-1).setOnClickListener(w->{
            String address=url.getText().toString().trim(),credential=token.getText().toString().trim();
            if(address.isEmpty()){app.store.putSetting("web_gateway","");app.store.putSetting("web_token","");d.dismiss();render();return;}
            if(credential.isEmpty()){if(!address.equals(saved)){toast("更换服务地址时请重新填写凭证");return;}credential=app.store.setting("web_token","");}
            try{GatewayClient client=new GatewayClient(address,credential);final String selected=address,key=credential;
                ProgressDialog progress=ProgressDialog.show(this,"连接服务","正在验证…",true,true);progress.setOnCancelListener(x->client.cancel());
                app.io.execute(()->{try{JSONObject status=client.request("/v1/web/status",null);if(!status.optBoolean("ok"))throw new java.io.IOException("服务尚未就绪。");app.main.post(()->{if(isDestroyed())return;progress.dismiss();app.store.putSetting("web_gateway",selected);app.store.putSetting("web_token",key);d.dismiss();render();toast("联网服务已连接");});}
                    catch(Exception e){app.main.post(()->{if(isDestroyed())return;progress.dismiss();toast(MozhiApp.friendly(e));});}});
            }catch(Exception e){toast(MozhiApp.friendly(e));}
        }));d.show();
    }
    private void agentSettings(){
        if(PhoneAccount.bound(app.store)){phoneAccount();return;}
        if(!idle())return;LinearLayout f=form();
        f.addView(text("服务端 Agent 可持续执行任务、联网查资料和生成文件。选中的背景和对话随任务保存在服务器，模型费用由服务运营者承担。手机仅需配置服务地址和用户凭证，无需填写模型密钥。",13,MUTED));gap(f,12);
        EditText address=input("HTTPS Agent 服务根地址",app.agentBase(),false);f.addView(address);gap(f,10);
        EditText token=input("用户凭证 · 留空保留原凭证","",false);token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);f.addView(token);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("连接 Agent 服务").setView(f).setNegativeButton("取消",null).setPositiveButton("连接服务端",null).create();
        d.setOnShowListener(v->d.getButton(-1).setOnClickListener(w->{
            String selected=address.getText().toString().trim(),value=token.getText().toString().trim();
            if(value.isEmpty()){if(!selected.equals(app.agentBase())){toast("更换地址时请重新填写用户凭证");return;}value=app.agentToken();}
            final String credential=value;
            try{RemoteAgentClient client=new RemoteAgentClient(selected,credential);ProgressDialog progress=ProgressDialog.show(this,"连接 Agent","正在验证用户与执行服务…",true,true);progress.setOnCancelListener(x->client.disconnect());
                app.communityIo.execute(()->{try{JSONObject status=client.request("/v1/agent/status",null);
                    app.main.post(()->{if(isDestroyed())return;progress.dismiss();if(!status.optBoolean("model_configured")||!status.optBoolean("worker_ready")){toast("用户验证成功，但模型或执行进程尚未就绪，请联系管理员");return;}
                        app.store.putSetting("agent_base",selected);app.store.putSetting("agent_token",credential);d.dismiss();render();toast("已连接服务端 Agent");app.resumeRemote();app.syncReminders();});
                }catch(Exception e){app.main.post(()->{if(isDestroyed())return;progress.dismiss();toast(MozhiApp.friendly(e));});}});
            }catch(Exception e){toast(MozhiApp.friendly(e));}
        }));d.show();
    }
    private void openArtifact(String id,Core.Item source){
        if(id==null||!id.matches("[a-f0-9]{32}")||source==null||source.s("remote_origin").isEmpty()||!source.s("remote_origin").equals(app.agentBase())){toast("请先连接生成此文件的 Agent 服务");return;}
        final String base=source.s("remote_origin"),token=app.agentToken();
        ProgressDialog progress=ProgressDialog.show(this,"读取文件","正在取回任务成果…",true,false);
        app.communityIo.execute(()->{try{JSONObject file=new RemoteAgentClient(base,token).request("/v1/agent/artifacts/"+id,null);
            app.main.post(()->{if(isDestroyed())return;progress.dismiss();String value=file.optString("content");
                TextView content=text(value.length()>16000?value.substring(0,16000)+"\n…预览截断，保存可取得完整文件":value,14,INK);content.setTextIsSelectable(true);content.setPadding(dp(18),dp(8),dp(18),dp(8));
                new AlertDialog.Builder(this).setTitle(file.optString("name","任务文件")).setView(scroll(content)).setNegativeButton("关闭",null)
                    .setNeutralButton("复制",(a,b)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("墨知文件",value));toast("已复制");})
                    .setPositiveButton("保存文件",(a,b)->{app.artifactExport=value.getBytes(StandardCharsets.UTF_8);Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(file.optString("media_type","text/plain"));i.putExtra(Intent.EXTRA_TITLE,file.optString("name","墨知任务.txt"));startActivityForResult(i,44);}).show();
            });
        }catch(Exception e){app.main.post(()->{if(isDestroyed())return;progress.dismiss();toast(MozhiApp.friendly(e));});}});
    }
    private void backupSettings(){
        if(!idle())return;boolean enabled=app.portable!=null&&app.portable.enabled();LinearLayout f=form();
        f.addView(text(enabled?app.portable.status():"选择手机文件夹，自动保存记忆、对话和本机分享。",14,MUTED));gap(f,14);
        if(enabled){f.addView(text("文件保留在所选文件夹，卸载后可用密码恢复。",13,MUTED));gap(f,12);}
        AlertDialog d=new AlertDialog.Builder(this).setTitle("自动备份").setView(f).setNegativeButton("完成",null).setPositiveButton(enabled?"现在备份":"开启自动备份",(x,w)->{if(enabled)app.savePortableNow();else connectPortable();}).create();
        if(enabled){ImageButton more=iconButton(R.drawable.ic_more,"备份选项",()->{});more.setOnClickListener(v->{PopupMenu menu=new PopupMenu(this,more);menu.getMenu().add("更换文件夹");menu.getMenu().add("关闭自动备份");menu.setOnMenuItemClickListener(i->{d.dismiss();if(i.getTitle().equals("更换文件夹"))connectPortable();else{app.portable.disconnect();render();}return true;});menu.show();});f.addView(more);}
        d.show();
    }
    private void dataSettings(){new AlertDialog.Builder(this).setTitle("数据管理").setItems(new String[]{"清空本机数据"},(d,w)->{if(idle())confirm("清空本机数据？","所有本机对话、记忆、技能、提醒、健康记录、分享、互动和设置将删除，已保存的备份文件保留。服务端提醒和健康记录需分别取消或删除。"+(app.community.localOnly()?"":"社区身份也会移除；已发布问答不会自动删除，请先在发现中删除不想保留的分享。"),()->{app.locations.clear();Reminders.clear(this,app.store);app.ensureBuiltinSkills();app.suggestions.clear();conversation="";draft="";if(composer!=null)composer.setText("");render();});}).setNegativeButton("取消",null).show();}
    private void skills(){
        if(skillListDialog!=null&&skillListDialog.isShowing())skillListDialog.dismiss();
        LinearLayout list=form();list.addView(text("技能是一套可复用的 Markdown 方法。匹配话题时自动加载；Agent 也可按目录主动加载。它不会自动获得执行代码或操作手机的权限。",14,MUTED));gap(list,12);
        list.addView(button("提醒与日程",()->{if(skillListDialog!=null)skillListDialog.dismiss();personalUi.reminders();}));
        list.addView(button("健康记录",()->{if(skillListDialog!=null)skillListDialog.dismiss();personalUi.health(app.remoteMode());}));
        list.addView(button("＋ 手动创建技能",()->skillEditor(null)));gap(list,10);
        for(Core.Item i:app.store.list("skill",null)){LinearLayout c=card(list);c.addView(heading(i.s("name"),18));c.addView(text("v"+i.data.optInt("version",1)+" · "+(i.data.optBoolean("enabled",true)?"启用":"停用")+" · "+i.s("source"),12,MUTED));gap(c,7);c.addView(text(i.s("trigger"),14,MUTED));gap(c,8);if(!i.s("starter").isEmpty()&&i.data.optBoolean("enabled",true))c.addView(button("开始使用",()->{if(!idle())return;skillListDialog.dismiss();newConversation();draft=i.s("starter");composer=null;app.store.putSetting("draft",draft);render();}));c.addView(button("查看与编辑",()->skillEditor(i)));}
        skillListDialog=new AlertDialog.Builder(this).setTitle("墨知技能库").setView(scroll(list)).setPositiveButton("完成",null).show();
    }
    private void skillEditor(Core.Item item){
        if(!idle())return;if(skillListDialog!=null)skillListDialog.dismiss();LinearLayout f=form();EditText name=input("技能名称",item==null?"":item.s("name"),false),trigger=input("什么时候使用它",item==null?"":item.s("trigger"),true),body=input("Markdown 步骤、判断标准、输出格式",item==null?"":item.s("body"),true);f.addView(name);gap(f,8);f.addView(trigger);gap(f,8);f.addView(body);CheckBox enabled=new CheckBox(this);enabled.setText("启用，允许按需加载");enabled.setChecked(item==null||item.data.optBoolean("enabled",true));f.addView(enabled);
        if(item!=null&&!item.s("builtin_id").isEmpty()){name.setEnabled(false);trigger.setEnabled(false);body.setEnabled(false);f.addView(text("内置方法随版本更新；可停用。个性化方法请新建技能。",12,MUTED));}
        if(item!=null&&!item.s("previous_body").isEmpty())f.addView(button("将上个版本放回编辑框",()->body.setText(item.s("previous_body"))));
        AlertDialog d=new AlertDialog.Builder(this).setTitle(item==null?"新技能":"编辑技能").setView(scroll(f)).setNegativeButton("取消",null).setPositiveButton("保存",null).setNeutralButton(item==null?"":"删除",null).create();d.setOnShowListener(v->{d.getButton(-1).setOnClickListener(w->{
            if(!idle())return;String n=name.getText().toString().trim(),t=trigger.getText().toString().trim(),b=body.getText().toString().trim();if(n.isEmpty()||n.length()>60||t.isEmpty()||t.length()>200||b.isEmpty()||b.length()>6000){toast("名称1–60字，适用场景1–200字，步骤1–6000字");return;}
            if(item==null&&app.store.list("skill",null).size()>=50){toast("最多50个技能，请先整理");return;}
            JSONObject value=Core.obj("name",n,"trigger",t,"body",b,"source","user","enabled",enabled.isChecked(),"version",item==null?1:item.data.optInt("version",1)+1,"previous_body",item==null?"":item.s("body"),"updated",System.currentTimeMillis());if(item!=null&&!item.s("builtin_id").isEmpty()){value=Core.obj("builtin_id",item.s("builtin_id"),"name",item.s("name"),"trigger",item.s("trigger"),"body",item.s("body"),"source","builtin","description",item.s("description"),"starter",item.s("starter"),"version",item.data.optInt("version",1),"enabled",enabled.isChecked());}if(item==null)app.store.add("skill","",value);else app.store.save(new Core.Item(item.id,"skill","",item.created,value));d.dismiss();render();skills();toast("技能已保存，下次匹配话题时可用");});
            if(item!=null&&!item.s("builtin_id").isEmpty())d.getButton(-3).setVisibility(View.GONE);else if(item!=null)d.getButton(-3).setOnClickListener(w->{if(idle())confirm("删除这个技能？","之后不会再自动加载。",()->{app.store.remove(item);d.dismiss();render();skills();toast("技能已删除");});});});d.show();
    }
    private void connectPortable(){
        if(!idle())return;LinearLayout f=form();f.addView(text("设置密码并选择手机文件夹。应用使用中自动备份，保留最近3份。卸载后凭文件和密码恢复；忘记密码无法恢复。",14,MUTED));gap(f,12);EditText password=input("备份密码（至少8字符）","",false);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);f.addView(password);gap(f,8);EditText repeat=input("再次输入密码","",false);repeat.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);f.addView(repeat);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("开启自动备份").setView(scroll(f)).setNegativeButton("取消",null).setPositiveButton("选择本机文件夹",null).create();d.setOnShowListener(v->d.getButton(-1).setOnClickListener(w->{if(password.length()<8){toast("密码至少8字符");return;}if(!password.getText().toString().equals(repeat.getText().toString())){toast("两次密码不一致");return;}app.pickerPassword=password.getText().toString().toCharArray();d.dismiss();Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.putExtra(Intent.EXTRA_LOCAL_ONLY,true);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,43);}));d.show();
    }
    private void backup(boolean restore){backup(restore,false);}
    private void backup(boolean restore,boolean merge){
        if(!idle())return;LinearLayout f=form();f.addView(text(merge?"输入旧版备份密码。只补充缺失记录，不覆盖已有内容，导入后可在个人账号中保存到手机号账号。":restore?"恢复会替换当前对话、记忆和决定，技能也一并替换，模型设置保留。请先备份现有数据。":"请设置至少 8 个字符的密码。忘记密码无法恢复备份。",14,MUTED));gap(f,12);EditText password=input("备份密码","",false);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);f.addView(password);
        AlertDialog d=new AlertDialog.Builder(this).setTitle(merge?"导入旧版备份":restore?"恢复加密备份":"导出加密备份").setView(f).setNegativeButton("取消",null).setPositiveButton(merge?"选择备份并导入":restore?"选择备份并替换":"选择保存位置",null).create();d.setOnShowListener(v->d.getButton(-1).setOnClickListener(w->{if(password.length()<8){toast("密码至少 8 个字符");return;}app.pickerPassword=password.getText().toString().toCharArray();d.dismiss();Intent i=new Intent(restore?Intent.ACTION_OPEN_DOCUMENT:Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(restore?"*/*":"application/json");if(!restore)i.putExtra(Intent.EXTRA_TITLE,"mozhi-backup-"+new SimpleDateFormat("yyyyMMdd",Locale.ROOT).format(new Date())+".json");startActivityForResult(i,merge?45:restore?42:41);}));d.show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==ChatAttachments.PICK_FILE){attachmentResult(result,data);return;}
        if(request==44){byte[] bytes=app.artifactExport;app.artifactExport=null;if(result!=RESULT_OK||data==null||data.getData()==null)return;if(bytes==null){toast("文件内容已释放，请重新打开文件保存");return;}Uri uri=data.getData();app.communityIo.execute(()->{String status;try(java.io.OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException();out.write(bytes);status="文件已保存";}catch(Exception e){status="保存失败，请重试";}String msg=status;app.main.post(()->toast(msg));});return;}
        if(request==ChatImportUi.PICK_FILE){imports.fileResult(result,data);return;}
        if(request==43){
            if(result!=RESULT_OK||data==null||app.pickerPassword==null||!idle()){if(app.pickerPassword!=null)Arrays.fill(app.pickerPassword,'\0');app.pickerPassword=null;return;}
            char[] password=app.pickerPassword;app.pickerPassword=null;try{Uri tree=data.getData();if(tree==null)throw new IllegalArgumentException();app.portable.bind(tree,password);app.savePortableNow();toast("已开启，正在保存第一份备份");}catch(Exception e){toast("连接失败，请选择可读写的本机文件夹");}finally{Arrays.fill(password,'\0');}render();return;
        }
        if(request!=41&&request!=42&&request!=45)return;
        if(result!=RESULT_OK||data==null||app.pickerPassword==null||!idle()){if(app.pickerPassword!=null)Arrays.fill(app.pickerPassword,'\0');app.pickerPassword=null;return;}
        Uri uri=data.getData();char[] password=app.pickerPassword;app.pickerPassword=null;if(uri==null){Arrays.fill(password,'\0');return;}
        ProgressDialog progress=ProgressDialog.show(this,"本机备份",request==41?"正在加密导出…":"正在解密恢复…",true,false);
        app.io.execute(()->{String message;boolean success=false;try{
            if(request==41){String encoded=Backup.encrypt(app.store.dump(),password);if(encoded.getBytes(StandardCharsets.UTF_8).length>24*1024*1024)throw new java.io.IOException("文件过大");try(java.io.OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException();out.write(encoded.getBytes(StandardCharsets.UTF_8));}}
            else if(request==45){String encoded=ApiClient.read(getContentResolver().openInputStream(uri),24*1024*1024);app.store.mergeArchive(PhoneAccount.clean(Backup.decrypt(encoded,password)));}
            else {String encoded=ApiClient.read(getContentResolver().openInputStream(uri),24*1024*1024);Reminders.restore(this,app.store,Backup.decrypt(encoded,password));app.store.putSetting("current","");app.store.putSetting("draft","");for(Core.Item m:app.store.list("message",null))if(m.s("status").equals("pending")){m.data.put("status","interrupted");app.store.save(m);}}
            message=request==41?"加密备份已导出":"已恢复本机资料";success=true;
        }catch(Exception e){message=request==42?"恢复未完成：请检查密码和备份文件。格式校验失败时不会替换原有资料。":"导出失败，请检查保存位置。";}
        finally{Arrays.fill(password,'\0');}
        String finalMessage=message;boolean restored=success&&request==42;app.main.post(()->{if(isDestroyed())return;progress.dismiss();if(restored){conversation="";draft="";app.suggestions.clear();app.ensureBuiltinSkills();app.syncReminders();}toast(finalMessage);render();});});
    }
}
