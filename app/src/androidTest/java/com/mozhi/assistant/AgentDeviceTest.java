package com.mozhi.assistant;
import android.test.InstrumentationTestCase;import android.content.*;import android.text.Spanned;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import org.json.*;
public final class AgentDeviceTest extends InstrumentationTestCase {
    public void testMarkdownUsesNativeSpans()throws Exception{
        final Throwable[] error={null};getInstrumentation().runOnMainSync(()->{try{
            io.noties.markwon.Markwon m=io.noties.markwon.Markwon.builder(getInstrumentation().getTargetContext()).usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(getInstrumentation().getTargetContext())).build();
            Spanned result=m.toMarkdown("## 建议\n\n**优先安静**\n\n1. 先看距离\n2. 再看环境\n\n> 留些余量\n\n`代码`\n\n|选项|预算|\n|---|---|\n|A|50元|");
            assertFalse(result.toString().contains("**"));assertFalse(result.toString().contains("##"));assertTrue(result.getSpans(0,result.length(),Object.class).length>5);
        }catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);
    }
    public void testAtomicReplyPersistsMemoryAndSkill()throws Exception{
        LocalStore s=new LocalStore(getInstrumentation().getTargetContext());Core.Item c=s.add("conversation","",Core.obj("title","Agent设备测试"));Core.Item u=s.add("message",c.id,Core.obj("role","user","text","我喜欢安静","status","pending"));
        AgentSession session=new AgentSession(k->s.list(k,null),u,true,true);session.call("remember",Core.obj("slot","test.quiet","text","我喜欢安静","evidence","我喜欢安静","scope","always","days","0"));session.call("save_skill",Core.obj("name","设备测试技能","trigger","安静 咖啡","body","先核对环境，再比较预算。"));
        s.completeReply(u,"**收到**",session.changes(),session.trace);String cid=c.id;s.close();LocalStore reopened=new LocalStore(getInstrumentation().getTargetContext());assertEquals(2,reopened.list("message",cid).size());for(Core.Item item:session.changes()){assertTrue(reopened.list(item.kind,null).stream().anyMatch(x->x.id.equals(item.id)));reopened.remove(item);}reopened.remove(c);reopened.close();
    }
    public void testPreparePortableForReinstall()throws Exception{
        LocalStore s=new LocalStore(getInstrumentation().getTargetContext());Core.Item m=s.add("memory","",Core.obj("text","重装恢复测试：偏好安静","global",true,"enabled",true));Core.Item k=s.add("skill","",Core.obj("name","重装恢复测试技能","trigger","安静 咖啡馆","body","# 选择方法\n先比较安静程度，再核对预算。","source","agent","enabled",true,"version",2));
        String data=Backup.encrypt(s.dump(),"test-only-reinstall".toCharArray());try(OutputStream out=getInstrumentation().getTargetContext().openFileOutput("reinstall-output.json",Context.MODE_PRIVATE)){out.write(data.getBytes(StandardCharsets.UTF_8));}s.remove(m);s.remove(k);s.close();
    }
    public void testSeedVisualFixture()throws Exception{
        LocalStore s=new LocalStore(getInstrumentation().getTargetContext());Core.Item c=s.add("conversation","",Core.obj("title","界面验收示例"));s.add("message",c.id,Core.obj("role","user","text","帮我比较两个周末安排（测试示例）","status","done"));s.add("message",c.id,Core.obj("role","assistant","text","## 我的建议\n\n**优先选择轻松、安静的安排。** 留一点余量，不必把行程排满。\n\n1. 先确认可用时间。\n2. 比较路程和预算。\n3. 准备一个下雨备选。\n\n| 方案 | 时间 | 预算 |\n| --- | --- | --- |\n| 公园散步 | 1小时 | 较低 |\n| 室内活动 | 2小时 | 适中 |\n\n> 不确定的营业时间，需要出发前核实。\n\n**下一步：** 选出你更喜欢的方案。","status","done","agent_trace",new JSONArray().put("自动加载技能：家庭安排")));s.putSetting("current",c.id);s.close();
    }
}
