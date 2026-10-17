package com.mozhi.assistant;
public final class SkillSeeds {
    public static void install(LocalStore store){if(store.setting("skill_seeds_v1","").equals("yes"))return;
        add(store,"购物取舍","购物 商品 预算 购买 值得 性价比","# 购物取舍\n1. 明确用途、预算和已有替代品。\n2. 结合用户已经明确的偏好，只比较真正会改变选择的因素。\n3. 使用表格比较购买、延后和不买。没有实时价格时请用户提供，不编造。\n4. 给出倾向、代价和一个下一步。");
        add(store,"工作选择","工作 职业 跳槽 岗位 offer 收入","# 工作选择\n1. 先看用户目标与时间、家庭、收入底线。\n2. 比较成长空间、现金收入、稳定性与机会成本，区别事实和猜测。\n3. 列出最可能改变结论的两项未知信息。\n4. 给出有条件的建议，不替用户决定。");
        add(store,"家庭安排","家庭 孩子 周末 行程 安排","# 家庭安排\n1. 确认参与者、可用时间、预算和照护约束。\n2. 安排留有余量，提供一个轻量备选方案。\n3. 涉及营业时间、天气和交通时提醒需要当前信息，不编造查询结果。\n4. 用简短清单呈现可执行步骤。");store.putSetting("skill_seeds_v1","yes");
    }
    public static void installBuiltin(LocalStore store,org.json.JSONArray skills)throws org.json.JSONException {
        for(int n=0;n<skills.length();n++){
            org.json.JSONObject skill=skills.getJSONObject(n);String id=skill.getString("id"),key="builtin_installed_"+id;
            boolean exists=false;for(Core.Item i:store.list("skill"))if(i.s("builtin_id").equals(id)){exists=true;break;}
            if(!exists)store.add("skill","",Core.obj("builtin_id",id,"name",skill.getString("name"),"trigger",skill.getString("trigger"),"body",skill.getString("body"),"description",skill.getString("description"),"starter",skill.getString("starter"),"source","builtin","enabled",true,"version",1));
            store.putSetting(key,"yes");
        }
    }
    private static void add(LocalStore s,String name,String trigger,String body){s.add("skill","",Core.obj("name",name,"trigger",trigger,"body",body,"source","builtin","enabled",true,"version",1));}
}
