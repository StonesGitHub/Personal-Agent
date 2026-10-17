package com.mozhi.assistant;

import java.time.*;
import java.time.temporal.ChronoUnit;
import org.json.*;

/** Pure scheduling rules shared by local tool execution and Android alarm recovery. */
public final class ReminderSchedule {
    private ReminderSchedule() {}
    static String field(JSONObject a,String key,int max,boolean empty) {
        Object value=a.opt(key);
        if(!(value instanceof String))throw new IllegalArgumentException(key+" 必须为文字");
        String s=((String)value).trim();
        if((!empty&&s.isEmpty())||s.length()>max)throw new IllegalArgumentException(key+" 为空或过长");
        return s;
    }
    public static JSONObject validate(JSONObject args,long now)throws JSONException {
        JSONObject data=Core.obj("title",field(args,"title",80,false),"body",field(args,"body",1000,true),
            "start_at",field(args,"start_at",40,false),"timezone",field(args,"timezone",80,false),"repeat",field(args,"repeat",10,false));
        if(!java.util.Arrays.asList("once","daily","weekly").contains(data.getString("repeat")))throw new IllegalArgumentException("仅支持一次、每天、每周");
        long first=first(data);next(data,first-1);
        if(first<now+1000||first>now+3660L*86400000)throw new IllegalArgumentException("首次提醒需在未来，且不超过十年");
        data.put("next_fire",first);data.put("state","active");return data;
    }
    static OffsetDateTime start(JSONObject value) {
        try{return OffsetDateTime.parse(value.optString("start_at"));}
        catch(DateTimeException e){throw new IllegalArgumentException("时间需包含日期和 UTC 偏移，例如 2026-10-09T08:00:00+08:00");}
    }
    public static long first(JSONObject value){return start(value).toInstant().toEpochMilli();}
    public static long next(JSONObject value,long after) {
        try{
            ZoneId zone=ZoneId.of(value.getString("timezone"));OffsetDateTime start=start(value);
            ZonedDateTime local=start.toInstant().atZone(zone);
            if(!local.toLocalDateTime().equals(start.toLocalDateTime())||!local.getOffset().equals(start.getOffset()))throw new IllegalArgumentException("首次时间与指定时区不一致，或本地时间不存在");
            long first=start.toInstant().toEpochMilli();if(first>after)return first;
            String repeat=value.getString("repeat");if(repeat.equals("once"))return 0;
            if(!repeat.equals("daily")&&!repeat.equals("weekly"))throw new IllegalArgumentException("重复规则无效");
            int step=repeat.equals("daily")?1:7;
            LocalDate today=Instant.ofEpochMilli(after).atZone(zone).toLocalDate();
            long days=Math.max(0,ChronoUnit.DAYS.between(local.toLocalDate(),today)/step*step);
            for(int n=0;n<3;n++){
                // atZone shifts a spring gap forward and chooses the earlier autumn offset.
                long at=LocalDateTime.of(local.toLocalDate().plusDays(days+(long)n*step),local.toLocalTime()).atZone(zone).toInstant().toEpochMilli();
                if(at>after)return at;
            }
            throw new IllegalArgumentException("无法计算下次提醒");
        }catch(DateTimeException|JSONException e){throw new IllegalArgumentException("提醒时间或时区无效");}
    }
    public static String describe(JSONObject value){
        String repeat=value.optString("repeat");String rule=repeat.equals("daily")?"每天":repeat.equals("weekly")?"每周":"一次";
        try{
            long at=value.optLong("next_fire");if(at==0)at=first(value);
            return Instant.ofEpochMilli(at).atZone(ZoneId.of(value.optString("timezone"))).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))+" · "+value.optString("timezone")+" · "+rule;
        }catch(Exception e){return "时间无效，请重新设置";}
    }
}
