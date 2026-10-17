package com.mozhi.assistant;

import org.json.*;
import java.io.IOException;
import java.util.*;

/** Offline discovery. Samples are immutable; only this device's shares and reactions are stored. */
public final class LocalCommunity extends CommunityClient {
    public interface Storage {
        List<Core.Item> list(String kind);
        void save(Core.Item item);
        /** Removing a share also removes its local likes and comments, in one transaction. */
        void remove(Core.Item item);
    }
    public static final String SHARE="share",COMMENT="share_comment",LIKE="share_like";
    private static final int PAGE_SIZE=20;
    private final Storage storage;
    public LocalCommunity(Storage storage){super("",null);this.storage=storage;}
    @Override public boolean available(){return true;}
    @Override public boolean localOnly(){return true;}

    private static JSONObject sample(long id,String question,String answer){return Core.obj("id",id,"nickname","墨知","question",question,"answer",answer,"created",0,"mine",false,"demo",true,"local",true);}
    private static List<JSONObject> samples(){return Arrays.asList(
        sample(4,"如果我的猫是项目经理，它会怎么开周会？","今日议程：\n\n1. 检查所有纸箱是否符合入住标准。\n2. 将激光笔列为高优先级项目。\n3. 全员午睡，会议结束。\n\n**会议纪要：喵。**"),
        sample(3,"怎样把普通周末，过成一个小小的假期？","**只安排一件真正想做的事。**\n\n走一条没走过的小路，带一本读到一半的书，或者给自己做一顿慢慢吃的早餐。\n\n其他时间留白，不必把休息也变成任务。"),
        sample(2,"购物车里那件东西，到底要不要买？","先问自己三句：\n\n- 它解决的是现在的需要，还是一时心动？\n- 家里有没有能替代的东西？\n- 如果没有折扣，我还想买吗？\n\n**还拿不准，就先放一天。** 心动可以留着，下单不用着急。"),
        sample(1,"怎样把「今天什么都没干」换一种说法？","今天给大脑放了个短假。\n\n吃了一顿饭，晒了一会儿太阳，或者只是好好睡了一觉，也都算认真生活。\n\n**休息不用先拿到一张完成任务的证明。**")
    );}
    public static boolean isSample(long id){return id>=1&&id<=4;}
    public static long number(Core.Item item){String prefix=item.kind.equals(SHARE)?"local-share-":item.kind.equals(COMMENT)?"local-comment-":"local-like-";try{return item.id.startsWith(prefix)?Long.parseLong(item.id.substring(prefix.length())):0;}catch(NumberFormatException e){return 0;}}
    private Core.Item find(String kind,long id){for(Core.Item item:storage.list(kind))if(number(item)==id)return item;return null;}
    private JSONObject decorated(JSONObject post){long id=post.optLong("id");boolean liked=find(LIKE,id)!=null;int count=0;for(Core.Item item:storage.list(COMMENT))if(item.parent.equals(String.valueOf(id)))count++;return Core.obj("id",id,"nickname",post.optString("nickname"),"question",post.optString("question"),"answer",post.optString("answer"),"created",post.optLong("created"),"mine",post.optBoolean("mine"),"demo",post.optBoolean("demo"),"local",true,"liked",liked,"likes",liked?1:0,"comments",count);}
    private JSONObject ownPost(Core.Item item){return Core.obj("id",number(item),"nickname","我","question",item.s("question"),"answer",item.s("answer"),"created",item.created,"mine",true,"demo",false);}
    private JSONObject page(List<JSONObject> source,long before){source.sort((a,b)->Long.compare(b.optLong("id"),a.optLong("id")));JSONArray rows=new JSONArray();boolean more=false;for(JSONObject item:source){if(before>0&&item.optLong("id")>=before)continue;if(rows.length()==PAGE_SIZE){more=true;break;}rows.put(item);}return Core.obj("items",rows,"next_before",more?rows.optJSONObject(rows.length()-1).optLong("id"):0);}
    private JSONObject feed(long before,boolean mine){synchronized(storage){List<JSONObject> posts=new ArrayList<>();for(Core.Item item:storage.list(SHARE))posts.add(decorated(ownPost(item)));if(!mine)for(JSONObject sample:samples())posts.add(decorated(sample));return page(posts,before);}}
    @Override public JSONObject feed(long before){return feed(before,false);}
    @Override public JSONObject myPosts(long before){return feed(before,true);}
    @Override public JSONObject post(long id)throws IOException{synchronized(storage){Core.Item item=find(SHARE,id);if(item!=null)return decorated(ownPost(item));for(JSONObject sample:samples())if(sample.optLong("id")==id)return decorated(sample);throw new IOException("这条本机分享已删除");}}
    private long nextId(String kind){long max=1000;for(Core.Item item:storage.list(kind))max=Math.max(max,number(item));if(max==Long.MAX_VALUE)throw new IllegalStateException("本机分享编号已用尽");return Math.max(System.currentTimeMillis(),max+1);}
    @Override public JSONObject publish(JSONObject pair)throws Exception{synchronized(storage){JSONObject allowed=ReplySharing.payload(pair.getString("client_id"),pair.getString("question"),pair.getString("answer"));for(Core.Item item:storage.list(SHARE))if(item.s("client_id").equals(allowed.optString("client_id"))){if(!item.s("question").equals(allowed.optString("question"))||!item.s("answer").equals(allowed.optString("answer")))throw new IOException("这段问答已保存，请先在我的分享中查看");return decorated(ownPost(item));}long id=nextId(SHARE);Core.Item item=new Core.Item("local-share-"+id,SHARE,"",System.currentTimeMillis(),allowed);storage.save(item);return decorated(ownPost(item));}}
    @Override public JSONObject like(long id,boolean liked)throws IOException{synchronized(storage){post(id);Core.Item existing=find(LIKE,id);if(liked&&existing==null)storage.save(new Core.Item("local-like-"+id,LIKE,String.valueOf(id),System.currentTimeMillis(),new JSONObject()));else if(!liked&&existing!=null)storage.remove(existing);return post(id);}}
    private JSONObject commentView(Core.Item item){return Core.obj("id",number(item),"nickname","我","text",item.s("text"),"created",item.created,"mine",true,"local",true);}
    @Override public JSONObject comments(long id,long before)throws IOException{synchronized(storage){post(id);List<JSONObject> comments=new ArrayList<>();for(Core.Item item:storage.list(COMMENT))if(item.parent.equals(String.valueOf(id)))comments.add(commentView(item));return page(comments,before);}}
    private static void checkComment(String text,String key){if(text.trim().isEmpty()||text.length()>2000||!key.matches("[A-Za-z0-9-]{16,64}"))throw new IllegalArgumentException("评论须为 1–2000 字，请检查后重试");}
    @Override public JSONObject comment(long id,String text,String clientId)throws IOException{synchronized(storage){post(id);String value=text.trim();checkComment(value,clientId);for(Core.Item item:storage.list(COMMENT))if(item.s("client_id").equals(clientId)){if(!item.parent.equals(String.valueOf(id))||!item.s("text").equals(value))throw new IOException("这条评论已保存，请先查看");return commentView(item);}Core.Item item=new Core.Item("local-comment-"+nextId(COMMENT),COMMENT,String.valueOf(id),System.currentTimeMillis(),Core.obj("text",value,"client_id",clientId));storage.save(item);return commentView(item);}}
    @Override public void removePost(long id)throws IOException{synchronized(storage){Core.Item item=find(SHARE,id);if(item==null)throw new IOException("示例内容不能删除");storage.remove(item);}}
    @Override public void removeComment(long id)throws IOException{synchronized(storage){Core.Item item=find(COMMENT,id);if(item==null)throw new IOException("这条评论已删除");storage.remove(item);}}
    @Override public void report(long id,String kind)throws IOException{throw new IOException("当前为本机模式，未发送举报");}

    /** Validate references as well as payloads before a backup replaces any local data. */
    public static void validateBackup(List<Core.Item> items)throws Exception{
        Set<Long> posts=new HashSet<>();Set<String> keys=new HashSet<>();
        for(Core.Item item:items)if(item.kind.equals(SHARE)){
            if(number(item)<=1000||!item.id.equals("local-share-"+number(item))||!item.parent.isEmpty()||!posts.add(number(item))||item.data.length()!=3)throw new Exception("备份中的本机分享无效。");
            ReplySharing.payload(item.s("client_id"),item.s("question"),item.s("answer"));if(!keys.add("share:"+item.s("client_id")))throw new Exception("备份包含重复分享。");
        }
        for(Core.Item item:items)if(item.kind.equals(COMMENT)||item.kind.equals(LIKE)){
            long parent;try{parent=Long.parseLong(item.parent);}catch(NumberFormatException e){throw new Exception("备份中的本机互动关系无效。");}
            if(!String.valueOf(parent).equals(item.parent)||(!isSample(parent)&&!posts.contains(parent)))throw new Exception("备份中的本机互动没有对应问答。");
            if(item.kind.equals(COMMENT)){if(number(item)<=1000||!item.id.equals("local-comment-"+number(item))||item.data.length()!=2)throw new Exception("备份中的评论无效。");checkComment(item.s("text"),item.s("client_id"));if(!keys.add("comment:"+item.s("client_id")))throw new Exception("备份包含重复评论。");}
            else if(number(item)!=parent||!item.id.equals("local-like-"+parent)||item.data.length()!=0)throw new Exception("备份中的点赞无效。");
        }
    }
}
