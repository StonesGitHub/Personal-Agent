package com.mozhi.assistant;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.*;

public final class Backup {
    private static final int ITERATIONS=210000;
    private static byte[] random(int n){byte[] b=new byte[n];new SecureRandom().nextBytes(b);return b;}
    private static SecretKeySpec key(char[] password,byte[] salt,int rounds) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(password,salt,rounds,256);
        try{return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),"AES");}
        finally{spec.clearPassword();}
    }
    public static String encrypt(JSONArray entries,char[] password) throws Exception {
        if(password.length<8)throw new Exception("备份密码至少 8 个字符。");
        byte[] salt=random(16),iv=random(12);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key(password,salt,ITERATIONS),new GCMParameterSpec(128,iv));
        byte[] bytes=Core.obj("version",4,"entries",entries).toString().getBytes(StandardCharsets.UTF_8);
        Base64.Encoder b=Base64.getEncoder();
        return Core.obj("format","mozhi-backup","version",4,"rounds",ITERATIONS,"salt",b.encodeToString(salt),"iv",b.encodeToString(iv),"ciphertext",b.encodeToString(cipher.doFinal(bytes))).toString();
    }
    public static JSONArray decrypt(String data,char[] password) throws Exception {
        JSONObject root=new JSONObject(data);
        if(!root.optString("format").equals("mozhi-backup") || root.optInt("version")<1 || root.optInt("version")>4 || root.optInt("rounds")!=ITERATIONS)throw new Exception("不支持的备份格式。");
        Base64.Decoder b=Base64.getDecoder();byte[] salt=b.decode(root.getString("salt")),iv=b.decode(root.getString("iv"));
        if(salt.length!=16||iv.length!=12)throw new Exception("备份格式损坏。");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(password,salt,ITERATIONS),new GCMParameterSpec(128,iv));
        JSONObject payload=new JSONObject(new String(cipher.doFinal(b.decode(root.getString("ciphertext"))),StandardCharsets.UTF_8));
        if(payload.optInt("version")<1||payload.optInt("version")>4)throw new Exception("备份版本不兼容。");
        JSONArray rows=payload.getJSONArray("entries");validate(rows);return rows;
    }
    public static List<Core.Item> validate(JSONArray rows) throws Exception {
        if(rows.length()>50000)throw new Exception("备份条目过多。");
        List<Core.Item> out=new ArrayList<>();Set<String> ids=new HashSet<>(),conversations=new HashSet<>();
        for(int n=0;n<rows.length();n++) {
            JSONObject row=rows.getJSONObject(n);String id=row.getString("id"),kind=row.getString("kind"),parent=row.getString("parent");
            if(id.isEmpty()||id.length()>100||!ids.add(id)||!java.util.Arrays.asList("conversation","message","memory","decision","skill","share","share_comment","share_like","reminder","health_record").contains(kind))throw new Exception("备份包含无效或重复条目。");
            JSONObject value=row.getJSONObject("data");if(value.toString().length()>100000)throw new Exception("备份条目过长。");
            if(kind.equals("message")&&!java.util.Arrays.asList("user","assistant").contains(value.optString("role")))throw new Exception("备份消息角色无效。");
            if(kind.equals("memory")&&(value.optString("text").length()>2000||value.optString("text").trim().isEmpty()))throw new Exception("备份记忆无效。");
            if(kind.equals("skill")&&(value.optString("name").trim().isEmpty()||value.optString("name").length()>60||value.optString("trigger").length()>200||value.optString("body").trim().isEmpty()||value.optString("body").length()>6000||value.optString("previous_body").length()>6000))throw new Exception("备份技能无效。");
            if(kind.equals("reminder")){
                ReminderSchedule.field(value,"title",80,false);ReminderSchedule.field(value,"body",1000,true);
                ReminderSchedule.next(value,ReminderSchedule.first(value)-1);
                if(!java.util.Arrays.asList("once","daily","weekly").contains(value.optString("repeat")))throw new Exception("提醒规则无效");
            }
            if(kind.equals("health_record")){
                ReminderSchedule.field(value,"subject",40,false);ReminderSchedule.field(value,"text",2000,false);
                if(!java.util.Arrays.asList("diet","sleep","symptom","activity","measurement").contains(value.optString("category")))throw new Exception("健康记录类别无效");
                java.time.OffsetDateTime.parse(value.getString("occurred_at"));
            }
            if(kind.equals("conversation"))conversations.add(id);
            if(!java.util.Arrays.asList("message","share_comment","share_like","reminder","health_record").contains(kind)&&!parent.isEmpty())throw new Exception("备份条目关系无效。");
            out.add(new Core.Item(id,kind,parent,row.getLong("created"),value));
        }
        for(Core.Item i:out)if(i.kind.equals("message")&&!conversations.contains(i.parent))throw new Exception("备份存在无所属对话的消息。");
        LocalCommunity.validateBackup(out);
        return out;
    }
}
