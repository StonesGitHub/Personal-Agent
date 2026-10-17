package com.mozhi.assistant;

import java.io.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Local parsing only. Imports are quoted reference material, never executable instructions or memories. */
public final class ChatImport {
    public static final int MAX_BYTES=5*1024*1024,MAX_CHARS=1000000,MAX_MESSAGES=2000,MAX_THREADS=50;
    public static final class Turn {
        public final String role,text;
        Turn(String role,String text){this.role=role;this.text=text;}
    }
    public static final class Transcript {
        public final String title,source;public final List<Turn> messages;public final boolean referenceOnly;public final int omitted;
        Transcript(String title,String source,List<Turn> messages,boolean referenceOnly,int omitted)throws IOException {
            this.title=title==null||title.trim().isEmpty()?"外部聊天":title.trim().substring(0,Math.min(100,title.trim().length()));
            this.source=source==null?"外部记录":source.substring(0,Math.min(4096,source.length()));
            this.messages=Collections.unmodifiableList(new ArrayList<>(messages));this.referenceOnly=referenceOnly;this.omitted=omitted;
            if(messages.isEmpty())throw new IOException("没有找到可导入的用户或助手正文。");
            if(messages.size()>MAX_MESSAGES)throw new IOException("聊天消息过多，请分批导入。");
            int total=0;for(Turn t:messages){total+=t.text.length();if(t.text.length()>40000||Core.obj("text",t.text).toString().length()>90000)throw new IOException("单条记录过长，请拆分后导入。");}
            if(total>MAX_CHARS)throw new IOException("聊天文本过长，请分批导入。");
        }
        public String fingerprint() {
            try {MessageDigest d=MessageDigest.getInstance("SHA-256");for(Turn t:messages){d.update(t.role.getBytes(StandardCharsets.UTF_8));d.update((byte)0);d.update(t.text.getBytes(StandardCharsets.UTF_8));d.update((byte)0);}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
            catch(Exception e){throw new IllegalStateException(e);}
        }
        public String text(){StringBuilder s=new StringBuilder();for(Turn m:messages)s.append(m.role.equals("user")?"用户：":"助手：").append(m.text).append("\n\n");return s.toString().trim();}
    }
    public static final class Plan {
        public final List<Core.Item> rows=new ArrayList<>();public final List<String> conversations=new ArrayList<>();public int added,duplicates,messages;
    }
    public static boolean requested(String message){return Pattern.compile("导入|迁移.*(?:聊天|记录)|\\bimport\\b",Pattern.CASE_INSENSITIVE).matcher(message).find();}
    public static String read(InputStream in)throws IOException {
        if(in==null)throw new IOException("无法打开文件。");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
        try(InputStream stream=in){while((n=stream.read(buf))!=-1){if(out.size()+n>MAX_BYTES)throw new IOException("文件超过 5 MB，请分批导入。");out.write(buf,0,n);}}
        byte[] b=out.toByteArray();Charset c=StandardCharsets.UTF_8;int skip=0;
        if(b.length>=2&&b[0]==(byte)0xff&&b[1]==(byte)0xfe){c=StandardCharsets.UTF_16LE;skip=2;}
        else if(b.length>=2&&b[0]==(byte)0xfe&&b[1]==(byte)0xff){c=StandardCharsets.UTF_16BE;skip=2;}
        try{return c.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b,skip,b.length-skip)).toString();}
        catch(CharacterCodingException e){throw new IOException("请使用 UTF-8 或 UTF-16 编码的 JSON、TXT、Markdown 文件。");}
    }
    public static List<Transcript> parse(String input,String source)throws Exception {
        String raw=input.replaceFirst("^\\uFEFF","").trim();if(raw.isEmpty()||raw.length()>MAX_CHARS)throw new IOException("文本为空或超过 100 万字，请分批导入。");
        if(raw.indexOf('\0')>=0)throw new IOException("这不是可读取的聊天文本文件。");
        List<Transcript> result=new ArrayList<>();
        if(raw.startsWith("{")||raw.startsWith("[")){
            try{
                JSONTokener parser=new JSONTokener(raw);Object data=parser.nextValue();if(parser.nextClean()!=0)throw new IOException("JSON 文件包含多余内容，请重新导出。");
                if(data instanceof JSONArray)parseArray((JSONArray)data,source,result);
                else if(data instanceof JSONObject){JSONObject obj=(JSONObject)data;
                    if(obj.optJSONArray("conversations")!=null)parseArray(obj.getJSONArray("conversations"),source,result);
                    else if(obj.optJSONObject("data")!=null&&obj.optJSONObject("data").has("biz_data"))result.add(deepSeek(obj,source));
                    else result.add(object(obj,source));
                }
            }catch(JSONException e){throw new IOException("JSON 聊天文件格式不完整，请重新导出。");}
            if(result.isEmpty())throw new IOException("未识别到聊天记录，请使用 messages 或 ChatGPT conversations JSON 文件。");
        } else result.add(text(raw,source));
        int count=0,total=0;for(Transcript t:result){count+=t.messages.size();for(Turn m:t.messages)total+=m.text.length();}
        if(result.size()>MAX_THREADS||count>MAX_MESSAGES||total>MAX_CHARS)throw new IOException("最多一次导入 50 个对话、2000 条消息，请分批导入。");
        return result;
    }
    private static void parseArray(JSONArray a,String source,List<Transcript> out)throws Exception {
        if(a.length()==0)return;JSONObject first=a.optJSONObject(0);if(first==null)throw new IOException("聊天 JSON 必须包含消息对象。");
        if(first.has("role")||first.has("author"))out.add(linear(a,"外部聊天",source));
        else {if(a.length()>MAX_THREADS)throw new IOException("对话过多，请分批导入。");for(int n=0;n<a.length();n++)out.add(object(a.getJSONObject(n),source));}
    }
    private static Transcript object(JSONObject obj,String source)throws Exception {
        if(obj.optJSONObject("mapping")!=null)return mapping(obj,source);
        JSONArray messages=obj.optJSONArray("messages");if(messages==null)throw new IOException("未识别到 messages 或 mapping 聊天结构。");
        return linear(messages,obj.optString("title","外部聊天"),source);
    }
    public static Transcript deepSeek(JSONObject root,String source)throws Exception {
        JSONObject data=root.optJSONObject("data");
        if(root.optInt("code",-1)!=0||data==null||data.optInt("biz_code",-1)!=0)throw new IOException("DeepSeek 分享链接不可用、已失效或需要登录。");
        JSONObject content=data.optJSONObject("biz_data");if(content==null)throw new IOException("分享链接没有聊天内容。");
        return object(content,source);
    }
    private static Transcript mapping(JSONObject obj,String source)throws Exception {
        JSONObject nodes=obj.getJSONObject("mapping");if(nodes.length()>MAX_MESSAGES*4)throw new IOException("导出的分支过多，请拆分对话。");
        String current=obj.optString("current_node","");
        if(current.isEmpty()){
            List<String> leaves=new ArrayList<>();for(Iterator<String> it=nodes.keys();it.hasNext();){String id=it.next();JSONObject node=nodes.optJSONObject(id);JSONArray children=node==null?null:node.optJSONArray("children");if(node!=null&&node.optJSONObject("message")!=null&&(children==null||children.length()==0))leaves.add(id);}
            if(leaves.size()!=1)throw new IOException("这个对话有多个分支且未标明当前分支，请导出选定分支或粘贴文本。");current=leaves.get(0);
        }
        List<JSONObject> chain=new ArrayList<>();Set<String> seen=new HashSet<>();
        while(!current.isEmpty()){
            if(!seen.add(current))throw new IOException("聊天分支存在循环，无法导入。");
            JSONObject node=nodes.optJSONObject(current);if(node==null)throw new IOException("聊天分支不完整，请重新导出。");
            JSONObject message=node.optJSONObject("message");if(message!=null)chain.add(message);
            current=node.isNull("parent")?"":node.optString("parent","");
        }
        Collections.reverse(chain);return linear(new JSONArray(chain),obj.optString("title","ChatGPT 聊天"),source);
    }
    private static Transcript linear(JSONArray rows,String title,String source)throws Exception {
        if(rows.length()>MAX_MESSAGES*4)throw new IOException("聊天消息过多，请分批导入。");
        List<Turn> turns=new ArrayList<>();int omitted=0;
        for(int n=0;n<rows.length();n++){
            JSONObject m=rows.getJSONObject(n);String role=m.optString("role","");JSONObject author=m.optJSONObject("author");if(author!=null)role=author.optString("role",role);
            role=role.toLowerCase(Locale.ROOT);if(role.equals("human"))role="user";if(role.equals("bot")||role.equals("ai"))role="assistant";
            if((!role.equals("user")&&!role.equals("assistant"))||m.optString("channel").equals("analysis")||m.optString("channel").equals("commentary")){omitted++;continue;}
            StringBuilder body=new StringBuilder();JSONArray fragments=m.optJSONArray("fragments");
            if(fragments!=null){for(int j=0;j<fragments.length();j++){JSONObject f=fragments.optJSONObject(j);if(f==null)continue;String type=f.optString("type");if(type.equals("REQUEST")||type.equals("RESPONSE"))append(body,f.opt("content"));else omitted++;}}
            else {Object content=m.opt("content");if(content instanceof String)append(body,content);else if(content instanceof JSONObject){JSONArray parts=((JSONObject)content).optJSONArray("parts");if(parts!=null)for(int j=0;j<parts.length();j++){if(parts.opt(j) instanceof String)append(body,parts.opt(j));else omitted++;}}else if(content instanceof JSONArray){JSONArray parts=(JSONArray)content;for(int j=0;j<parts.length();j++){Object p=parts.opt(j);if(p instanceof String)append(body,p);else if(p instanceof JSONObject&&((JSONObject)p).optString("type").equals("text"))append(body,((JSONObject)p).opt("text"));else omitted++;}}}
            String value=body.toString().trim();if(!value.isEmpty())turns.add(new Turn(role,value));else omitted++;
        }
        return new Transcript(title,source,turns,false,omitted);
    }
    private static void append(StringBuilder b,Object text){if(text instanceof String&&!((String)text).trim().isEmpty()){if(b.length()>0)b.append('\n');b.append(text);}}
    private static Transcript text(String raw,String source)throws IOException {
        Pattern marker=Pattern.compile("^(?:#{1,6}\\s*)?(用户|我|User|Human|助手|Assistant|AI|DeepSeek|ChatGPT|豆包|Kimi)\\s*[：:]\\s*(.*)$",Pattern.CASE_INSENSITIVE);
        List<Turn> turns=new ArrayList<>();String role=null;StringBuilder body=new StringBuilder();String fence="";StringBuilder preamble=new StringBuilder();
        for(String line:raw.split("\\r?\\n",-1)){
            String cleaned=line.trim().replaceFirst("^\\*\\*([^*]+?[：:])\\*\\*", "$1").replaceFirst("^\\*\\*([^*]+?)\\*\\*([：:])", "$1$2");
            Matcher m=marker.matcher(cleaned);String content=line.trim();
            if(fence.isEmpty()&&m.matches()){
                if(role!=null&&!body.toString().trim().isEmpty())turns.add(new Turn(role,body.toString().trim()));
                role=Arrays.asList("用户","我","user","human").contains(m.group(1).toLowerCase(Locale.ROOT))?"user":"assistant";body=new StringBuilder(m.group(2));content=m.group(2).trim();
            }else if(role==null)preamble.append(line).append('\n');else body.append('\n').append(line);
            if(fence.isEmpty()){if(content.startsWith("```"))fence="```";else if(content.startsWith("~~~"))fence="~~~";}
            else if(content.startsWith(fence))fence="";
        }
        if(role!=null&&!body.toString().trim().isEmpty())turns.add(new Turn(role,body.toString().trim()));
        boolean reference=turns.isEmpty();
        if(reference)turns.add(new Turn("user",raw));else if(!preamble.toString().trim().isEmpty())turns.add(0,new Turn("user","【原文说明】\n"+preamble.toString().trim()));
        return new Transcript(reference?"导入资料":"导入聊天",source,turns,reference,0);
    }
    public static Plan plan(List<Transcript> transcripts,List<Core.Item> existing,long now)throws IOException {
        if(transcripts.isEmpty()||transcripts.size()>MAX_THREADS)throw new IOException("请选择要导入的对话。");
        int count=0,total=0;for(Transcript t:transcripts){count+=t.messages.size();for(Turn m:t.messages)total+=m.text.length();}if(count>MAX_MESSAGES||total>MAX_CHARS)throw new IOException("记录过多，请分批导入。");
        Plan p=new Plan();Map<String,String> known=new HashMap<>();for(Core.Item c:existing)if(!c.s("import_fingerprint").isEmpty())known.put(c.s("import_fingerprint"),c.id);
        for(Transcript t:transcripts){String fp=t.fingerprint(),id=known.get(fp);if(id!=null){p.duplicates++;p.conversations.add(id);continue;}
            id=UUID.randomUUID().toString();known.put(fp,id);p.conversations.add(id);p.added++;
            p.rows.add(new Core.Item(id,"conversation","",now,Core.obj("title",t.title,"imported",true,"import_source",t.source,"import_fingerprint",fp)));
            for(Turn m:t.messages){p.rows.add(new Core.Item(UUID.randomUUID().toString(),"message",id,now,Core.obj("role",m.role,"text",m.text,"status","done","imported",true,"import_source",t.source,"reference_only",t.referenceOnly)));p.messages++;}
        }
        return p;
    }
    private ChatImport(){}
}
