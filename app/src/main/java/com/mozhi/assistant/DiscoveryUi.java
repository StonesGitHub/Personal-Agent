package com.mozhi.assistant;

import android.app.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/** Native Q&A discovery, with clearly labeled offline samples and private local sharing. */
public final class DiscoveryUi {
    private static final int PAPER=0xfffffdfa,INK=0xff433833,MUTED=0xff88786d,ACCENT=0xff956047,LINE=0xffeee5dc;
    private final Activity activity;
    private final MozhiApp app;
    private final Consumer<String> openLink;
    private final io.noties.markwon.Markwon markdown;
    private LinearLayout content;
    private ScrollView feedScroll;
    private final List<JSONObject> items=new ArrayList<>();
    private long next;
    private boolean loading,mine;
    private interface Work {JSONObject run()throws Exception;}

    public DiscoveryUi(Activity activity,MozhiApp app,Consumer<String> openLink){this.activity=activity;this.app=app;this.openLink=openLink;markdown=ReplyMarkdown.create(activity,openLink);}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);v.setBackgroundColor(PAPER);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(activity);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color){TextView v=new TextView(activity);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(4),1);return v;}
    private TextView bold(String s,int size){TextView v=text(s,size,INK);v.setTypeface(null,Typeface.BOLD);if(android.os.Build.VERSION.SDK_INT>=29)v.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED);return v;}
    private void fullScreen(Dialog dialog,View contentRoot){
        Window window=dialog.getWindow();if(window==null)return;
        window.setBackgroundDrawableResource(android.R.color.transparent);window.setLayout(-1,-1);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND|WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS|WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);window.setDimAmount(0);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);window.setStatusBarColor(PAPER);window.setNavigationBarColor(PAPER);
        if(android.os.Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(false);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        contentRoot.setOnApplyWindowInsetsListener((v,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(i.left,i.top,i.right,i.bottom);}
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);contentRoot.requestApplyInsets();
    }
    private void gap(LinearLayout parent,int n){Space v=new Space(activity);parent.addView(v,new LinearLayout.LayoutParams(1,dp(n)));}
    private void line(LinearLayout parent){View v=new View(activity);v.setBackgroundColor(LINE);parent.addView(v,new LinearLayout.LayoutParams(-1,dp(1)));}
    private ScrollView scroll(LinearLayout child){ScrollView v=new ScrollView(activity);v.setFillViewport(true);v.setVerticalScrollBarEnabled(false);v.setOverScrollMode(View.OVER_SCROLL_NEVER);v.addView(child);return v;}
    private ImageButton icon(int drawable,String description,Runnable action){ImageButton v=new ImageButton(activity);v.setImageResource(drawable);v.setColorFilter(MUTED);v.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22956047),null,bg(PAPER,16)));v.setPadding(dp(13),dp(13),dp(13),dp(13));v.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));v.setContentDescription(description);v.setTooltipText(description);v.setOnClickListener(x->action.run());return v;}
    private Button button(String label,Runnable action){Button b=new Button(activity);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(ACCENT);b.setBackground(bg(0xffffefdf,16));b.setStateListAnimator(null);b.setElevation(0);b.setMinHeight(dp(48));b.setPadding(dp(16),dp(8),dp(16),dp(8));b.setOnClickListener(v->action.run());return b;}
    private void toast(String value){Toast.makeText(activity,value,Toast.LENGTH_LONG).show();}
    private void run(Work work,Consumer<JSONObject> success,Consumer<Exception> failure){app.communityIo.execute(()->{try{JSONObject value=work.run();app.main.post(()->{if(!activity.isFinishing()&&!activity.isDestroyed())success.accept(value);});}catch(Exception e){app.main.post(()->{if(!activity.isFinishing()&&!activity.isDestroyed())failure.accept(e);});}});}
    private String error(Exception e){return (e instanceof java.io.IOException||e instanceof IllegalArgumentException||e instanceof IllegalStateException)&&e.getMessage()!=null?e.getMessage():app.community.localOnly()?"本机保存失败，请稍后重试":"暂时没有连接上，请稍后重试";}
    private String time(long when){long mins=Math.max(0,(System.currentTimeMillis()-when)/60000);return mins<1?"刚刚":mins<60?mins+"分钟前":mins<1440?mins/60+"小时前":new java.text.SimpleDateFormat("MM月dd日",Locale.CHINA).format(new Date(when));}
    private void empty(String title,String hint){LinearLayout box=column();box.setGravity(Gravity.CENTER);box.setPadding(dp(20),dp(82),dp(20),dp(48));ImageView mark=new ImageView(activity);mark.setImageResource(R.drawable.ic_mozhi);box.addView(mark,new LinearLayout.LayoutParams(dp(56),dp(56)));gap(box,20);box.addView(bold(title,20));gap(box,10);TextView sub=text(hint,14,MUTED);sub.setGravity(Gravity.CENTER);box.addView(sub);content.addView(box);}

    public View feed(){content=column();content.setPadding(dp(18),dp(8),dp(18),dp(24));feedScroll=scroll(content);refresh();return feedScroll;}
    public void refresh(){if(content==null||loading)return;if(!app.community.available()){content.removeAllViews();empty("有趣的问答，值得被看见","发现暂未开放\n可以先把喜欢的问答保存为分享草稿");if(!app.store.setting("community_draft","").isEmpty())content.addView(button("查看分享草稿",this::openDraft));return;}load(false);}
    private void load(boolean more){
        if(loading)return;loading=true;long cursor=more?next:0;int y=feedScroll.getScrollY();
        if(items.isEmpty()){content.removeAllViews();content.addView(text("正在寻找有趣的问答…",14,MUTED));}
        run(()->mine?app.community.myPosts(cursor):app.community.feed(cursor),value->{loading=false;JSONArray page=value.optJSONArray("items");if(!more)items.clear();if(page!=null)for(int n=0;n<page.length();n++)items.add(page.optJSONObject(n));next=value.optLong("next_before");drawFeed();if(more)feedScroll.post(()->feedScroll.scrollTo(0,y));},e->{loading=false;if(items.isEmpty()){content.removeAllViews();empty(app.community.localOnly()?"暂时无法读取本机分享":"暂时连不上发现",error(e));}else{drawFeed();content.addView(text(error(e),13,MUTED));}content.addView(button("重试",()->load(more)));});
    }
    private void drawFeed(){
        content.removeAllViews();TextView intro=text(app.community.localOnly()?(mine?"保存在本机，仅自己可见":"示例与本机分享 · 仅自己可见"):(mine?"你公开分享的一问一答":"有趣的提问，也有新的看法"),13,MUTED);intro.setPadding(dp(2),0,0,dp(16));content.addView(intro);
        if(mine&&!app.store.setting("community_draft","").isEmpty()){TextView drafts=text("分享草稿  ›",14,ACCENT);drafts.setPadding(0,dp(12),0,dp(20));drafts.setOnClickListener(v->openDraft());content.addView(drafts);}
        if(items.isEmpty())empty("第一段分享，从你开始","在墨聊回答下方点分享\n把值得一看的问答留在这里");
        for(JSONObject post:items)if(post!=null)content.addView(card(post));
        if(next>0)content.addView(button("查看更多",()->load(true)));
    }
    private LinearLayout card(JSONObject post){
        LinearLayout card=column();card.setPadding(dp(17),dp(14),dp(17),dp(5));GradientDrawable surface=bg(0xffffffff,20);surface.setStroke(dp(1),LINE);card.setBackground(surface);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);card.setLayoutParams(lp);
        LinearLayout meta=row();TextView name=text(post.optString("nickname","墨友"),12,MUTED);meta.addView(name,new LinearLayout.LayoutParams(0,-2,1));meta.addView(text(post.optBoolean("demo")?"示例":app.community.localOnly()?"仅自己可见":time(post.optLong("created")),11,MUTED));card.addView(meta);gap(card,13);
        TextView question=bold(post.optString("question"),18);question.setMaxLines(3);question.setEllipsize(android.text.TextUtils.TruncateAt.END);question.setContentDescription("发现问题："+post.optString("question"));card.addView(question);gap(card,12);
        TextView answer=text("",15,INK);answer.setText(markdown.toMarkdown(post.optString("answer")));answer.setMaxLines(5);answer.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(answer);
        TextView more=text("查看完整问答  ›",12,ACCENT);more.setPadding(0,dp(12),0,dp(8));card.addView(more);card.setOnClickListener(v->detail(post,false));card.setFocusable(true);card.setContentDescription("查看问答："+post.optString("question"));
        LinearLayout actions=row();addLike(actions,post);addComment(actions,post,()->detail(post,true));actions.addView(new Space(activity),new LinearLayout.LayoutParams(0,1,1));if(!post.optBoolean("demo"))actions.addView(postMore(post,()->{items.remove(post);drawFeed();}));card.addView(actions);return card;
    }
    private void addLike(LinearLayout actions,JSONObject post){
        final ImageButton[] button={null};TextView count=text(String.valueOf(post.optInt("likes")),12,MUTED);
        button[0]=icon(R.drawable.ic_like,"点赞问答",()->{boolean want=!post.optBoolean("liked");button[0].setEnabled(false);run(()->app.community.like(post.optLong("id"),want),value->{updatePost(post,value);count.setText(String.valueOf(post.optInt("likes")));button[0].setEnabled(true);tintLike(button[0],post);},e->{button[0].setEnabled(true);toast(error(e));});});tintLike(button[0],post);actions.addView(button[0]);actions.addView(count);
    }
    private void tintLike(ImageButton view,JSONObject post){view.setSelected(post.optBoolean("liked"));view.setColorFilter(post.optBoolean("liked")?ACCENT:MUTED);view.setContentDescription(post.optBoolean("liked")?"取消点赞问答":"点赞问答");}
    private void addComment(LinearLayout actions,JSONObject post,Runnable action){actions.addView(icon(R.drawable.ic_comment,"评论问答",action));actions.addView(text(String.valueOf(post.optInt("comments")),12,MUTED));}
    private void copy(JSONObject target,JSONObject source){try{Iterator<String> keys=source.keys();while(keys.hasNext()){String key=keys.next();target.put(key,source.get(key));}}catch(JSONException e){throw new IllegalArgumentException(e);}}
    private void updatePost(JSONObject post,JSONObject updated){copy(post,updated);for(JSONObject item:items)if(item!=null&&item!=post&&item.optLong("id")==post.optLong("id"))copy(item,updated);}
    private void refreshPost(JSONObject post){run(()->app.community.post(post.optLong("id")),updated->{updatePost(post,updated);if(content!=null&&content.isAttachedToWindow())drawFeed();},e->{});}
    private ImageButton postMore(JSONObject post,Runnable removed){ImageButton more=icon(R.drawable.ic_more,"问答选项",()->{});more.setOnClickListener(v->{PopupMenu menu=new PopupMenu(activity,more);menu.getMenu().add(post.optBoolean("mine")?"删除分享":"举报内容");menu.setOnMenuItemClickListener(i->{if(post.optBoolean("mine"))new AlertDialog.Builder(activity).setTitle("删除这段分享？").setMessage("从发现中删除问答和评论，本机对话仍保留。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->run(()->{app.community.removePost(post.optLong("id"));return new JSONObject();},r->removed.run(),e->toast(error(e)))).show();else run(()->{app.community.report(post.optLong("id"),"post");return new JSONObject();},r->toast("已提交举报"),e->toast(error(e)));return true;});menu.show();});return more;}

    private void detail(JSONObject post,boolean focusComment){
        Dialog dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout root=column();root.setBackgroundColor(PAPER);LinearLayout header=row();header.setPadding(dp(8),0,dp(8),0);header.addView(icon(R.drawable.ic_back,"返回发现",dialog::dismiss));header.addView(bold("一问一答",18),new LinearLayout.LayoutParams(0,-2,1));if(!post.optBoolean("demo"))header.addView(postMore(post,()->{dialog.dismiss();refresh();}));root.addView(header);
        LinearLayout reading=column();reading.setPadding(dp(24),dp(18),dp(24),dp(20));ScrollView scroller=scroll(reading);root.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));reading.addView(text(post.optString("nickname")+(post.optBoolean("demo")?" · 示例问答":" · "+time(post.optLong("created")))+(app.community.localOnly()?" · 仅本机可见":""),12,MUTED));gap(reading,17);reading.addView(bold(post.optString("question"),22));gap(reading,22);TextView answer=text("",16,INK);answer.setLineSpacing(dp(6),1);markdown.setMarkdown(answer,post.optString("answer"));reading.addView(answer);gap(reading,10);reading.addView(text(post.optBoolean("demo")?"由墨知编写的示例问答":"与 AI 的对话分享",11,MUTED));LinearLayout actions=row();addLike(actions,post);reading.addView(actions);gap(reading,10);line(reading);gap(reading,20);TextView commentsHeading=bold(app.community.localOnly()?"评论 · 仅本机":"评论",17);reading.addView(commentsHeading);gap(reading,12);LinearLayout comments=column();reading.addView(comments);
        LinearLayout compose=row();compose.setPadding(dp(12),dp(8),dp(12),dp(12));EditText input=edit("聊聊你的想法…","",true);input.setMinLines(1);input.setMaxLines(3);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});compose.addView(input,new LinearLayout.LayoutParams(0,-2,1));final ImageButton[] send={null};String[] pendingText={""},pendingKey={""};
        Runnable reload=()->loadComments(post,comments,commentsHeading,scroller,focusComment);
        send[0]=icon(R.drawable.ic_send,"发送评论",()->{String value=input.getText().toString().trim();if(value.isEmpty())return;if(!value.equals(pendingText[0])){pendingText[0]=value;pendingKey[0]=UUID.randomUUID().toString();}send[0].setEnabled(false);input.setEnabled(false);run(()->app.community.comment(post.optLong("id"),value,pendingKey[0]),r->{input.setText("");input.setEnabled(true);send[0].setEnabled(true);pendingText[0]="";pendingKey[0]="";reload.run();refreshPost(post);},e->{input.setEnabled(true);send[0].setEnabled(true);toast(error(e));});});send[0].setColorFilter(ACCENT);compose.addView(send[0]);root.addView(compose);
        dialog.setContentView(root);dialog.setOnDismissListener(d->{if(content!=null&&content.isAttachedToWindow()){drawFeed();refresh();}});dialog.show();fullScreen(dialog,root);reload.run();
    }
    private void loadComments(JSONObject post,LinearLayout comments,TextView heading,ScrollView scroller,boolean focus){
        comments.removeAllViews();comments.addView(text("正在加载评论…",13,MUTED));List<JSONObject> all=new ArrayList<>();long[] cursor={0};boolean[] pending={false};
        Runnable[] fetch={null};fetch[0]=()->{if(pending[0])return;pending[0]=true;run(()->app.community.comments(post.optLong("id"),cursor[0]),value->{pending[0]=false;JSONArray rows=value.optJSONArray("items");if(rows!=null)for(int n=0;n<rows.length();n++)all.add(rows.optJSONObject(n));cursor[0]=value.optLong("next_before");comments.removeAllViews();heading.setText(app.community.localOnly()?"评论 · 仅本机":"评论");if(cursor[0]>0)comments.addView(button("查看更早评论",fetch[0]));if(all.isEmpty())comments.addView(text("说说你的看法吧",14,MUTED));for(int n=all.size()-1;n>=0;n--){JSONObject item=all.get(n);if(item==null)continue;LinearLayout entry=column();entry.setPadding(0,dp(10),0,dp(14));entry.addView(text(item.optString("nickname")+" · "+time(item.optLong("created")),12,MUTED));gap(entry,5);entry.addView(text(item.optString("text"),15,INK));entry.setOnLongClickListener(v->{new AlertDialog.Builder(activity).setItems(new String[]{item.optBoolean("mine")?"删除评论":"举报评论"},(d,w)->run(()->{if(item.optBoolean("mine"))app.community.removeComment(item.optLong("id"));else app.community.report(item.optLong("id"),"comment");return new JSONObject();},r->{if(item.optBoolean("mine")){loadComments(post,comments,heading,scroller,false);refreshPost(post);}else toast("已提交举报");},e->toast(error(e)))).show();return true;});comments.addView(entry);}if(focus)scroller.post(()->scroller.smoothScrollTo(0,heading.getTop()));},e->{pending[0]=false;comments.removeAllViews();comments.addView(text(error(e),13,MUTED));comments.addView(button("重试",fetch[0]));});};fetch[0].run();
    }

    private EditText edit(String hint,String value,boolean multiple){EditText e=new EditText(activity);e.setTextSize(15);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setHint(hint);e.setText(value);e.setBackground(bg(0xfff5efe8,15));e.setPadding(dp(13),dp(12),dp(13),dp(12));e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(multiple?android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));e.setMinLines(multiple?3:1);e.setMaxLines(multiple?12:1);return e;}
    public static void share(Activity activity,MozhiApp app,JSONObject pair,Consumer<String> openLink){new DiscoveryUi(activity,app,openLink).shareEditor(pair);}
    public static void myShares(Activity activity,MozhiApp app,Consumer<String> openLink){DiscoveryUi ui=new DiscoveryUi(activity,app,openLink);ui.mine=true;Dialog dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout root=ui.column();root.setBackgroundColor(PAPER);LinearLayout header=ui.row();header.addView(ui.icon(R.drawable.ic_back,"返回我",dialog::dismiss));header.addView(ui.bold("我的分享",18));root.addView(header);root.addView(ui.feed(),new LinearLayout.LayoutParams(-1,0,1));dialog.setContentView(root);dialog.show();ui.fullScreen(dialog,root);}
    public static void openDraft(Activity activity,MozhiApp app,Consumer<String> openLink){new DiscoveryUi(activity,app,openLink).openDraft();}
    private void openDraft(){String saved=app.store.setting("community_draft","");if(saved.isEmpty()){Toast.makeText(activity,"在回答下方点分享，就能整理一段问答",Toast.LENGTH_LONG).show();return;}try{shareEditor(new JSONObject(saved));}catch(JSONException e){Toast.makeText(activity,"草稿暂时无法打开",Toast.LENGTH_LONG).show();}}
    private void shareEditor(JSONObject initial){
        LinearLayout form=column();form.setPadding(dp(20),dp(10),dp(20),dp(12));TextView note=text(app.community.localOnly()?"只保存这一问一答，可先编辑。":"仅分享下面这一问一答。发布前，请删去不想公开的信息。",13,MUTED);form.addView(note);gap(form,18);form.addView(text("问题",12,ACCENT));gap(form,5);EditText question=edit("把问题补成一句完整的话",initial.optString("question"),true);question.setMinLines(2);question.setMaxLines(4);form.addView(question);gap(form,16);form.addView(text("回答",12,ACCENT));gap(form,5);EditText answer=edit("保留最值得分享的部分",initial.optString("answer"),true);form.addView(answer);gap(form,12);TextView hint=text(app.community.localOnly()?"保存在本机，仅自己可见。":app.community.available()?"发布后，其他人可以查看、点赞和评论。":"发现暂未开放，这段问答可以先存为草稿。",12,MUTED);form.addView(hint);
        AlertDialog editor=new AlertDialog.Builder(activity).setTitle("分享到发现").setView(scroll(form)).setNegativeButton("取消",null).setNeutralButton("存草稿",null).setPositiveButton("预览",null).create();
        editor.setOnShowListener(v->{editor.getButton(-3).setOnClickListener(w->{JSONObject pair=Core.obj("client_id",initial.optString("client_id",UUID.randomUUID().toString()),"question",question.getText().toString(),"answer",answer.getText().toString());app.store.putSetting("community_draft",pair.toString());editor.dismiss();if(content!=null)drawFeed();toast("草稿已保存，可在「我」中继续编辑");});editor.getButton(-1).setOnClickListener(w->{try{JSONObject pair=ReplySharing.payload(initial.optString("client_id",UUID.randomUUID().toString()),question.getText().toString(),answer.getText().toString());previewShare(pair,editor);}catch(Exception e){toast(e.getMessage());}});});editor.show();
    }
    private void previewShare(JSONObject pair,AlertDialog editor){
        LinearLayout reading=column();reading.setPadding(dp(24),dp(12),dp(24),dp(16));reading.addView(text(app.community.localOnly()?"保存后的样子 · 仅自己可见":"其他人将看到",12,MUTED));gap(reading,13);reading.addView(bold(pair.optString("question"),20));gap(reading,18);TextView answer=text("",16,INK);markdown.setMarkdown(answer,pair.optString("answer"));reading.addView(answer);gap(reading,16);TextView state=text(app.community.localOnly()?"随时可从我的分享中删除。":app.community.available()?"发布后可在问答右上角删除分享。":"发现暂未开放，可以先存为草稿。",12,MUTED);reading.addView(state);
        AlertDialog preview=new AlertDialog.Builder(activity).setTitle("问答预览").setView(scroll(reading)).setNegativeButton("继续编辑",null).setPositiveButton(app.community.localOnly()?"保存到发现":app.community.available()?"发布到发现":"存草稿",null).create();
        boolean[] finished={false};preview.setOnDismissListener(d->{if(!finished[0]&&!activity.isFinishing()&&!activity.isDestroyed())editor.show();});editor.hide();
        preview.setOnShowListener(v->preview.getButton(-1).setOnClickListener(w->{app.store.putSetting("community_draft",pair.toString());if(!app.community.available()){finished[0]=true;preview.dismiss();editor.dismiss();toast("草稿已保存");return;}
            preview.setCancelable(false);preview.getButton(-1).setEnabled(false);preview.getButton(-2).setEnabled(false);state.setText(app.community.localOnly()?"正在保存…":"正在发布…");
            run(()->app.community.publish(pair),post->{app.store.putSetting("community_draft","");finished[0]=true;preview.dismiss();editor.dismiss();toast(app.community.localOnly()?"已保存到发现，仅自己可见":"已分享到发现");if(content!=null)refresh();detail(post,false);},e->{preview.setCancelable(true);preview.getButton(-1).setEnabled(true);preview.getButton(-2).setEnabled(true);state.setText(error(e)+"，草稿已保留。");});}));preview.show();
    }
}
