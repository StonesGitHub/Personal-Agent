package com.mozhi.assistant;
import android.test.InstrumentationTestCase;import java.io.*;
public final class ReinstallDeviceTest extends InstrumentationTestCase {
    // Invoked separately AFTER a real uninstall/reinstall by the device harness.
    public void testRestoreAfterReinstall()throws Exception{
        File input=getInstrumentation().getTargetContext().getFileStreamPath("reinstall-input.json");assertTrue("Restore file must exist after reinstall",input.exists());
        LocalStore s=new LocalStore(getInstrumentation().getTargetContext());String data=ApiClient.read(new FileInputStream(input),24*1024*1024);s.restore(Backup.decrypt(data,"test-only-reinstall".toCharArray()));assertTrue(s.list("memory",null).stream().anyMatch(i->i.s("text").contains("重装恢复测试")));assertTrue(s.list("skill",null).stream().anyMatch(i->i.s("name").equals("重装恢复测试技能")));s.close();
    }
}
