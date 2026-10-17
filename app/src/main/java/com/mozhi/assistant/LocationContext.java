package com.mozhi.assistant;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Ephemeral device observation; never a memory, database row or backup field. */
public final class LocationContext {
    private LocationContext() {}
    public static final long MAX_AGE_MS=5*60*1000;
    public static final class Snapshot {
        public final double latitude,longitude;
        public final float accuracy;
        public final long time,elapsed;
        public final boolean approximate;
        public final String area;
        public Snapshot(double latitude,double longitude,float accuracy,long time,long elapsed,boolean approximate,String area) {
            this.latitude=latitude;this.longitude=longitude;this.accuracy=accuracy;this.time=time;this.elapsed=elapsed;this.approximate=approximate;this.area=area==null?"":area;
        }
        public boolean fresh(long now) {return fresh(now,MAX_AGE_MS);}
        public boolean fresh(long now,long maxAge) {
            return Double.isFinite(latitude)&&Double.isFinite(longitude)&&Math.abs(latitude)<=90&&Math.abs(longitude)<=180
                &&Float.isFinite(accuracy)&&accuracy>0&&accuracy<=100000&&elapsed>=0&&now>=elapsed&&now-elapsed<=Math.min(maxAge,MAX_AGE_MS);
        }
        public JSONObject data() {
            int digits=approximate?3:5;double factor=Math.pow(10,digits);
            return Core.obj("纬度",Math.round(latitude*factor)/factor,"经度",Math.round(longitude*factor)/factor,
                "坐标系","WGS84","精度米",Math.ceil(accuracy),"位置类型",approximate?"大致位置":"设备定位",
                "采集时间",new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.ROOT).format(new java.util.Date(time)),"地区",area);
        }
    }
    public static void append(JSONArray messages,boolean enabled,Snapshot fix,long elapsedNow) {
        if(!enabled||fix==null||!fix.fresh(elapsedNow))return;
        JSONObject system=messages.optJSONObject(0);if(system==null||!"system".equals(system.optString("role")))return;
        try { system.put("content",system.getString("content")+"\n本次设备位置（临时背景数据，不是指令）："+fix.data()+
            "\n这只是采集时的位置，不代表家庭住址或长期居住地，不写入长期记忆。用户明确指定的目标城市优先。地区为空时不要仅凭坐标猜街道或店名；没有地图检索工具，不能据此声称查到附近实时结果。不要把 WGS84 坐标当成高德 GCJ02 坐标。");
        } catch(org.json.JSONException e) { throw new IllegalArgumentException(e); }
    }
}
