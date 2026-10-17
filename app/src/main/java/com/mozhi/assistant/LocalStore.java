package com.mozhi.assistant;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.util.*;

public final class LocalStore extends SQLiteOpenHelper implements LocalCommunity.Storage {
    public Runnable onMutation;
    private long revision=0;
    public synchronized long revision(){return revision;}
    private void mutated(){revision++;if(onMutation!=null)onMutation.run();}
    private final Vault vault;
    private final SharedPreferences preferences;
    public LocalStore(Context context) throws Exception {
        super(context,"mozhi.db",null,1);
        vault=new Vault();preferences=context.getSharedPreferences("local_settings",Context.MODE_PRIVATE);
        getWritableDatabase();
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entries(id TEXT PRIMARY KEY,kind TEXT NOT NULL,parent TEXT NOT NULL,created INTEGER NOT NULL,payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX entry_lookup ON entries(kind,parent,created)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to) { throw new IllegalStateException("需要数据迁移"); }
    public synchronized Core.Item add(String kind,String parent,JSONObject data) {
        Core.Item i=new Core.Item(UUID.randomUUID().toString(),kind,parent,System.currentTimeMillis(),data);save(i);return i;
    }
    public synchronized void save(Core.Item i) {
        ContentValues v=new ContentValues();v.put("id",i.id);v.put("kind",i.kind);v.put("parent",i.parent);v.put("created",i.created);v.put("payload",vault.encrypt(i.data.toString()));
        if(getWritableDatabase().insertWithOnConflict("entries",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("资料保存失败");mutated();
    }
    public List<Core.Item> list(String kind){return list(kind,null);}
    public synchronized List<Core.Item> list(String kind,String parent) {
        List<Core.Item> out=new ArrayList<>();
        String where=parent==null?"kind=?":"kind=? AND parent=?";
        String[] args=parent==null?new String[]{kind}:new String[]{kind,parent};
        try(Cursor c=getReadableDatabase().query("entries",null,where,args,null,null,"created ASC,rowid ASC")) {
            while(c.moveToNext()) {
                try {out.add(new Core.Item(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),new JSONObject(vault.decrypt(c.getString(4)))));}
                catch(JSONException e){throw new IllegalStateException("本机资料格式异常",e);}
            }
        }
        return out;
    }
    public synchronized void remove(Core.Item i) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try { db.delete("entries","id=?",new String[]{i.id});
            if(i.kind.equals("conversation"))db.delete("entries","kind='message' AND parent=?",new String[]{i.id});
            if(i.kind.equals(LocalCommunity.SHARE))db.delete("entries","kind IN ('share_comment','share_like') AND parent=?",new String[]{String.valueOf(LocalCommunity.number(i))});
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}mutated();
    }
    public synchronized void completeReply(Core.Item user,String reply) throws JSONException {
        completeReply(user,reply,new ArrayList<>(),new ArrayList<>());
    }
    /** A stale alarm snapshot cannot overwrite an edit/cancellation from another DB handle. */
    public synchronized boolean fireReminder(Core.Item expected,Core.Item delivery,long next)throws JSONException{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            Core.Item current=null;for(Core.Item i:list(expected.kind))if(i.id.equals(expected.id)){current=i;break;}
            if(current==null||!current.s("state").equals("active")||!current.data.toString().equals(expected.data.toString()))return false;
            boolean exists=false;for(Core.Item i:list("reminder_delivery"))if(i.id.equals(delivery.id)){exists=true;break;}
            if(!exists)save(delivery);
            current.data.put("next_fire",next);current.data.put("state",next==0?"completed":"active");save(current);db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    public synchronized void completeRemoteReply(Core.Item user,String reply,String origin,JSONObject run)throws JSONException{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            // Check persisted state, since the same run may be polled again after a process restart.
            for(Core.Item old:list("message",user.parent))if(old.id.equals(user.id)&&old.s("status").equals("done"))return;
            JSONArray trace=new JSONArray().put("服务端任务："+run.optString("id")).put("模型轮次："+run.optInt("rounds")+"；工具调用："+run.optInt("calls"));
            add("message",user.parent,Core.obj("role","assistant","text",reply,"status","done","agent_trace",trace,"remote_origin",origin,"remote_run_id",run.optString("id")));
            user.data.put("status","done");user.data.remove("remote_payload");save(user);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized void recordMapAction(Core.Item reply,MapLinks.Destination place) throws JSONException {
        if(!setting("use_memory","yes").equals("yes")||!setting("auto_learning","yes").equals("yes"))return;
        boolean exists=false;for(Core.Item item:list("message",reply.parent))if(item.id.equals(reply.id)){exists=true;break;}
        if(!exists)return;
        List<Core.Item> decisions=list("decision",null);Core.Item next=DecisionRecords.fromMap(reply,place,decisions,System.currentTimeMillis());
        for(Core.Item item:decisions)if(item==next)return;
        save(next);
    }
    public synchronized void completeReply(Core.Item user,String reply,Collection<Core.Item> changes,List<String> trace) throws JSONException {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            for(Core.Item i:changes){if(i.data.optBoolean("deleted"))remove(i);else save(i);}
            add("message",user.parent,Core.obj("role","assistant","text",reply,"status","done","agent_trace",new JSONArray(trace)));
            user.data.put("status","done");save(user);db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    public synchronized ChatImport.Plan importChats(List<ChatImport.Transcript> transcripts)throws java.io.IOException {
        ChatImport.Plan plan=ChatImport.plan(transcripts,list("conversation",null),System.currentTimeMillis());
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{for(Core.Item row:plan.rows)save(row);db.setTransactionSuccessful();}finally{db.endTransaction();}
        return plan;
    }
    /** Remove obsolete BYOK model settings without ever decrypting the retired secret. */
    public synchronized void clearLegacyModelSettings() {
        if(!preferences.edit().remove("base").remove("model").remove("key")
                .remove("approved_endpoint").remove("agent_mode").commit())
            throw new IllegalStateException("旧模型密钥清理失败");
    }
    public synchronized String setting(String key,String fallback) {
        String value=preferences.getString(key,null);return value==null?fallback:vault.decrypt(value);
    }
    public synchronized void putSetting(String key,String value) {
        if(!preferences.edit().putString(key,vault.encrypt(value)).commit())throw new IllegalStateException("设置保存失败");
    }
    public synchronized void clear() {getWritableDatabase().delete("entries",null,null);if(!preferences.edit().clear().commit())throw new IllegalStateException("设置清理失败");}
    public synchronized JSONArray dump() {
        JSONArray rows=new JSONArray();
        for(String kind:new String[]{"conversation","message","memory","decision","skill","share","share_comment","share_like","reminder","health_record"})for(Core.Item i:list(kind,null))
            rows.put(Core.obj("id",i.id,"kind",i.kind,"parent",i.parent,"created",i.created,"data",i.data));
        return rows;
    }
    public synchronized int mergeArchive(JSONArray rows)throws Exception {
        List<Core.Item> incoming=Backup.validate(rows);SQLiteDatabase db=getWritableDatabase();int added=0;
        db.beginTransaction();try{
            JSONArray combined=dump();Set<String> existing=new HashSet<>();for(int n=0;n<combined.length();n++)existing.add(combined.getJSONObject(n).getString("id"));
            for(int n=0;n<rows.length();n++)if(!existing.contains(rows.getJSONObject(n).getString("id")))combined.put(rows.getJSONObject(n));
            Backup.validate(combined);
            for(Core.Item item:incoming){try(Cursor c=db.rawQuery("SELECT kind,parent FROM entries WHERE id=?",new String[]{item.id})){
                if(c.moveToFirst()){if(!c.getString(0).equals(item.kind)||!c.getString(1).equals(item.parent))throw new Exception("记录编号冲突，已停止导入并保留原资料");continue;}}
                save(item);added++;
            }db.setTransactionSuccessful();
        }finally{db.endTransaction();}return added;
    }
    public synchronized void restore(JSONArray rows) throws Exception {
        List<Core.Item> items=Backup.validate(rows);
        for(Core.Item i:items)if(i.kind.equals("reminder")){i.data.put("state","paused");i.data.put("next_fire",0);}
        for(Core.Item i:items)if(i.kind.equals("message")&&i.s("status").equals("pending"))i.data.put("status","interrupted");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {db.delete("entries",null,null);for(Core.Item i:items)save(i);db.setTransactionSuccessful();}
        finally {db.endTransaction();}mutated();
    }
}
