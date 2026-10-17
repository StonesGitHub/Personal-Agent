package com.mozhi.assistant;
import android.test.InstrumentationTestCase;
/** Run only after adb uninstall -k followed by reinstall, with the existing recovery fixture. */
public final class RetainedDataDeviceTest extends InstrumentationTestCase {
    public void testRetainedDatabaseAndKey()throws Exception{
        LocalStore s=new LocalStore(getInstrumentation().getTargetContext());
        assertTrue(s.list("memory",null).stream().anyMatch(i->i.s("text").contains("重装恢复测试")));
        assertTrue(s.list("skill",null).stream().anyMatch(i->i.s("name").equals("重装恢复测试技能")));
        assertFalse(s.setting("current","").isEmpty());s.close();
    }
}
