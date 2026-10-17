package com.mozhi.assistant;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** User-owned SAF snapshots, independently password-encrypted and retained across uninstall. */
public final class PortableVault {
    private final Context context;private final LocalStore store;
    public PortableVault(Context context,LocalStore store){this.context=context;this.store=store;}
    public boolean enabled(){return !store.setting("vault_tree","").isEmpty();}
    public String status(){return store.setting("vault_status","未开启自动备份");}
    public void bind(Uri tree,char[] password)throws Exception{
        context.getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        store.putSetting("vault_password",new String(password));store.putSetting("vault_tree",tree.toString());store.putSetting("vault_status","已连接文件夹，等待首次保存");
    }
    public void disconnect(){String tree=store.setting("vault_tree","");store.putSetting("vault_tree","");store.putSetting("vault_password","");store.putSetting("vault_status","自动备份已关闭，原文件保留");if(!tree.isEmpty())try{context.getContentResolver().releasePersistableUriPermission(Uri.parse(tree),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}}
    public void snapshot()throws Exception{
        if(!enabled())return;char[] password=store.setting("vault_password","").toCharArray();Uri written=null;
        try{
            JSONArray rows;long revision;synchronized(store){rows=store.dump();revision=store.revision();}String encoded=Backup.encrypt(rows,password);
            if(encoded.getBytes(StandardCharsets.UTF_8).length>24*1024*1024)throw new IOException("记忆文件超过24MB，请先整理历史");
            Uri tree=Uri.parse(store.setting("vault_tree","")),parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            String name="mozhi-vault-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+".json";
            written=DocumentsContract.createDocument(context.getContentResolver(),parent,"application/json",name);
            if(written==null)throw new IOException("无法创建记忆文件");
            try(OutputStream out=context.getContentResolver().openOutputStream(written,"wt")){if(out==null)throw new IOException();out.write(encoded.getBytes(StandardCharsets.UTF_8));}
            String verify=ApiClient.read(context.getContentResolver().openInputStream(written),24*1024*1024);
            if(!encoded.equals(verify))throw new IOException("文件写入校验失败");
            store.putSetting("vault_status",(store.revision()==revision?"已保存 · ":"已保存较早快照，新修改待保存 · ")+new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date()));
            store.putSetting("vault_last_file",name);written=null;
            // Only our exact snapshot names are retained/removed; unrelated files are never touched.
            try{trim(tree,name);}catch(Exception ignored){} // A cleanup failure must not misreport a valid backup.
        }catch(Exception e){if(written!=null)try{DocumentsContract.deleteDocument(context.getContentResolver(),written);}catch(Exception ignored){}store.putSetting("vault_status","保存失败，旧文件保留；请检查文件夹权限后重试");throw e;}
        finally{Arrays.fill(password,'\0');}
    }
    private void trim(Uri tree,String latest)throws Exception{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));List<String[]> files=new ArrayList<>();
        try(Cursor c=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(c!=null)while(c.moveToNext()){String n=c.getString(1);if(n!=null&&n.matches("mozhi-vault-[0-9]{13}-[a-f0-9]{8}\\.json"))files.add(new String[]{c.getString(0),n});}
        }
        files.sort((a,b)->b[1].compareTo(a[1]));for(int n=3;n<files.size();n++)if(!files.get(n)[1].equals(latest))DocumentsContract.deleteDocument(context.getContentResolver(),DocumentsContract.buildDocumentUriUsingTree(tree,files.get(n)[0]));
    }
}
