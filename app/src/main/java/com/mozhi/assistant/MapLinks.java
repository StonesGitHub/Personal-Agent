package com.mozhi.assistant;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local destination data only. Never geocodes, invents coordinates or launches an app. */
public final class MapLinks {
    private MapLinks() {}
    public static final String INSTRUCTIONS = "\n地点回答规则：推荐可前往的具体地点（店铺、景点、公园、医院、车站等）时，" +
        "调用 map_links 为这些地点生成链接，一批最多10个，在最终回答中使用返回的 markdown，让用户点击地名打开本机地图。" +
        "带上已知城市、分店名称和可靠地址；城市会影响匹配且不明确时先询问，未知地址留空，不得编造门牌、坐标或POI ID。" +
        "map_links 只是本地格式化，不是搜索，不证明地点存在、营业或符合推荐条件。不要将‘附近咖啡馆’等泛称冒充具体店名。" +
        "没有剩余工具轮次时可按 [地点名](mozhi://map?name=地点名&city=已知城市&address=已知地址) 输出，参数需要URL编码。" +
        "用户点击后才会打开地图，只有目的地文字会交给地图；同名地点由地图确认，不声称已经开始导航。";

    public static final class Destination {
        public final String name, city, address;
        public Destination(String name, String city, String address) {
            this.name = clean(name, 120, true);
            this.city = clean(city, 80, false);
            this.address = clean(address, 240, false);
        }
        public String query() {
            String location = address;
            if (!city.isEmpty() && !location.contains(city)) location = city + (location.isEmpty() ? "" : " " + location);
            if (!location.contains(name)) location += (location.isEmpty() ? "" : " ") + name;
            return location;
        }
        public String url() { return "mozhi://map?name=" + encode(name) + "&city=" + encode(city) + "&address=" + encode(address); }
        public String markdown() { return "[" + escape(name) + " ↗](" + url() + ")"; }
        public String geoUri() { return "geo:0,0?q=" + encode(query()); }
        public String amapUri() { return "androidamap://keywordNavi?sourceApplication=" + encode("墨知") + "&keyword=" + encode(query()) + "&style=0"; }
        public String amapSearchUri() { return "androidamap://poi?sourceApplication="+encode("墨知")+"&keywords="+encode(query().replace('|','｜'))+"&dev=0"; }
        public String baiduUri() {
            // Baidu also parses ':' and '|' as a miniature destination language; send plain names.
            return "baidumap://map/direction?origin=" + encode("我的位置") + "&destination=" +
                encode(query().replace('|', '｜').replace(':', '：')) + "&destination_region=" + encode(city) +
                "&mode=driving&coord_type=bd09ll&src=andr.com.mozhi.assistant";
        }
        public String webUri() { return "https://uri.amap.com/search?keyword=" + encode(query()) + "&city=" + encode(city) + "&src=mozhi&callnative=0"; }
    }

    private static String clean(String value, int max, boolean required) {
        if (value == null) throw new IllegalArgumentException("地点字段必须为文字");
        for (int n=0; n<value.length(); n++) if (Character.isISOControl(value.charAt(n))) throw new IllegalArgumentException("地点不能包含控制字符");
        String text = value.trim();
        if ((required && text.isEmpty()) || text.length() > max) throw new IllegalArgumentException("地点名称为空或字段过长");
        return text;
    }
    public static String encode(String text) {
        try { return URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("*", "%2A"); }
        catch (java.io.UnsupportedEncodingException e) { throw new AssertionError(e); }
    }
    private static String decode(String text) throws Exception { return URLDecoder.decode(text, "UTF-8"); }
    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) { if ("\\`*_{}[]()#+-.!|<>~".indexOf(c)>=0) out.append('\\'); out.append(c); }
        return out.toString();
    }

    /** Untrusted model links are decoded once, validated, then rebuilt for the selected map. */
    public static Destination parse(String link) {
        if (link == null || link.length() > 6000) return null;
        try {
            URI u = new URI(link);
            if (u.getRawFragment()!=null) return null;
            if ("mozhi".equalsIgnoreCase(u.getScheme()) && "map".equalsIgnoreCase(u.getHost()) &&
                u.getUserInfo()==null && u.getPort()==-1 && (u.getPath()==null || u.getPath().isEmpty())) {
                Map<String,String> p = params(u.getRawQuery());
                for (String key : p.keySet()) if (!key.equals("name") && !key.equals("city") && !key.equals("address")) return null;
                return new Destination(p.get("name"), p.getOrDefault("city", ""), p.getOrDefault("address", ""));
            }
            if ("geo".equalsIgnoreCase(u.getScheme())) {
                String raw=u.getRawSchemeSpecificPart();
                if (!raw.startsWith("0,0?")) return null;
                Map<String,String> p=params(raw.substring(4));
                if (p.size()!=1 || !p.containsKey("q")) return null;
                // Legacy standard geo links with an address/query work through the same picker.
                return new Destination(p.get("q"), "", "");
            }
            // Older/model-authored AMap links also use the same local handoff. Rebuild only
            // destination text; never pass arbitrary intent flags, packages or model coordinates.
            if(u.getUserInfo()==null&&u.getPort()==-1&&
                (("https".equalsIgnoreCase(u.getScheme())&&"uri.amap.com".equalsIgnoreCase(u.getHost())&&"/search".equals(u.getPath()))||
                ("androidamap".equalsIgnoreCase(u.getScheme())&&"keywordNavi".equals(u.getHost())&&(u.getPath()==null||u.getPath().isEmpty())))){
                Map<String,String> p=params(u.getRawQuery());return new Destination(p.get("keyword"),p.getOrDefault("city",""),"");
            }
        } catch (Exception invalid) { return null; }
        return null;
    }
    private static Map<String,String> params(String raw) throws Exception {
        if (raw==null || raw.isEmpty()) throw new IllegalArgumentException("缺少地点");
        Map<String,String> result=new LinkedHashMap<>();
        for (String part:raw.split("&",-1)) {
            int eq=part.indexOf('=');
            if (eq<=0) throw new IllegalArgumentException("地点参数无效");
            String key=decode(part.substring(0,eq));
            if (result.containsKey(key)) throw new IllegalArgumentException("地点参数重复");
            result.put(key,decode(part.substring(eq+1)));
        }
        return result;
    }

    public static JSONObject schema() {
        JSONObject fields=Core.obj("name",Core.obj("type","string","description","具体地点或分店名称，最多120字"),
            "city",Core.obj("type","string","description","已知城市或地区，未知留空，最多80字"),
            "address",Core.obj("type","string","description","有可靠依据的街道/地址，未知留空，最多240字"));
        JSONObject item=Core.obj("type","object","properties",fields,"required",new JSONArray().put("name").put("city").put("address"),"additionalProperties",false);
        return Core.obj("type","function","function",Core.obj("name","map_links","description","为推荐的具体地点批量生成可点击地图链接。仅本地格式化，不联网、不定位、不验证地点，也不打开地图。", "parameters",
            Core.obj("type","object","properties",Core.obj("places",Core.obj("type","array","items",item,"minItems",1,"maxItems",10)),"required",new JSONArray().put("places"),"additionalProperties",false)));
    }
    public static JSONObject create(JSONObject args) throws Exception {
        JSONArray input=args.getJSONArray("places");
        if (input.length()<1 || input.length()>10) throw new IllegalArgumentException("每批地点数量为1至10个");
        JSONArray links=new JSONArray();
        for (int n=0;n<input.length();n++) {
            JSONObject row=input.getJSONObject(n);
            for(String key:new String[]{"name","city","address"})
                if(!(row.opt(key) instanceof String))throw new IllegalArgumentException("地点字段必须为文字："+key);
            Destination d=new Destination(row.getString("name"),row.getString("city"),row.getString("address"));
            links.put(Core.obj("name",d.name,"destination",d.query(),"url",d.url(),"markdown",d.markdown()));
        }
        return Core.obj("places",links,"note","仅生成链接，未核实地点。请在最终推荐里原样使用 markdown；由用户点击后选择地图与具体目的地。");
    }
}
