package com.mozhi.assistant;
import org.json.JSONArray;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class LocationContextTest {
    private LocationContext.Snapshot fix(long elapsed){return new LocationContext.Snapshot(30.67,104.07,25,1700000000000L,elapsed,false,"四川省 成都市");}
    @Test public void staleAndFutureObservationsAreNeverCurrent(){
        assertTrue(fix(100).fresh(100));assertTrue(fix(100).fresh(100+LocationContext.MAX_AGE_MS));
        assertFalse(fix(100).fresh(101+LocationContext.MAX_AGE_MS));assertFalse(fix(100).fresh(99));
    }
    @Test public void automaticRefreshDoesNotReuseOldButStillSendableCache(){
        assertTrue(fix(100).fresh(30_100,LocationController.REFRESH_MAX_AGE_MS));
        assertFalse(fix(100).fresh(30_101,LocationController.REFRESH_MAX_AGE_MS));
        assertTrue(fix(100).fresh(30_101));
        assertFalse(fix(100).fresh(99,LocationController.REFRESH_MAX_AGE_MS));
    }
    @Test public void customFreshnessNeverExtendsTheContextExpiry(){
        assertFalse(fix(100).fresh(101+LocationContext.MAX_AGE_MS,Long.MAX_VALUE));
        assertFalse(fix(100).fresh(100,-1));
    }
    @Test public void invalidMeasurementsCannotBecomeModelContext(){
        for(double latitude:new double[]{Double.NaN,Double.POSITIVE_INFINITY,91,-91})assertFalse(new LocationContext.Snapshot(latitude,104,20,1,100,false,"").fresh(100));
        assertFalse(new LocationContext.Snapshot(30,181,20,1,100,false,"").fresh(100));
        for(float accuracy:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY})assertFalse(new LocationContext.Snapshot(30,104,accuracy,1,100,false,"").fresh(100));
    }
    @Test public void disabledMissingAndStaleLocationsLeaveRequestUntouched(){
        for(LocationContext.Snapshot location:new LocationContext.Snapshot[]{null,fix(0)}){JSONArray request=Core.messages(Collections.emptyList(),Collections.emptyList());String before=request.toString();LocationContext.append(request,true,location,LocationContext.MAX_AGE_MS+1);assertEquals(before,request.toString());}
        JSONArray request=Core.messages(Collections.emptyList(),Collections.emptyList());String before=request.toString();LocationContext.append(request,false,fix(100),100);assertEquals(before,request.toString());
    }
    @Test public void observationIsEphemeralAndExplicitAboutAccuracyAndCoordinateSystem(){
        Core.Item user=new Core.Item("id","message","chat",1,Core.obj("role","user","text","附近走走","status","pending"));String original=user.data.toString();
        JSONArray request=Core.messages(Collections.singletonList(user),Collections.emptyList());LocationContext.append(request,true,fix(100),200);
        String system=request.optJSONObject(0).optString("content");assertTrue(system.contains("四川省 成都市"));assertTrue(system.contains("WGS84"));assertTrue(system.contains("精度米"));assertTrue(system.contains("不写入长期记忆"));assertEquals(original,user.data.toString());assertEquals(2,request.length());
        JSONArray next=Core.messages(Collections.singletonList(user),Collections.emptyList());assertFalse(next.toString().contains("本次设备位置"));
    }
    @Test public void approximatePermissionKeepsTheObservationLabelAndRounding(){
        LocationContext.Snapshot coarse=new LocationContext.Snapshot(30.123456,104.123456,2500,1,100,true,"");
        assertEquals(30.123,coarse.data().optDouble("纬度"),.0000001);assertEquals("大致位置",coarse.data().optString("位置类型"));assertEquals("",coarse.data().optString("地区"));
    }
}
