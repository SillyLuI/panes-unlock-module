package com.example.panesunlock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 多应用 hook 配置。
 *
 * 后续要新增/修改目标应用，只需改 {@code assets/hooks.json}（或下方 DEFAULT_JSON）：
 *   - type=bundle_forge : 格窗专用（强制 LICENSE_* + 注入试用令牌）
 *   - type=return_true / return_false / return_null / return_int:<n> / return_string:<s>
 *     通用“改返回值”规则（simplehook 式），用于把 isVip()/isPro()/needPay() 之类强制成想要的值。
 *   - 其它类型可在 MultiAppHookModule 中按需扩展。
 */
public final class HookConf {

    public static final String DEFAULT_JSON = "{\n" +
            "  \"apps\": [\n" +
            "    {\n" +
            "      \"name\": \"格窗工作台\",\n" +
            "      \"pkg\": \"io.github.yishisanren.panes\",\n" +
            "      \"note\": \"强制 LICENSE_UNLOCKED / TRIAL + 注入试用令牌\",\n" +
            "      \"rules\": [ { \"type\": \"bundle_forge\" } ]\n" +
            "    },\n" +
            "    {\n" +
            "      \"name\": \"次元库\",\n" +
            "      \"pkg\": \"com.parse.qimiao\",\n" +
            "      \"note\": \"梆梆加固，静态无法定位 VIP 方法；真机动态定位后填 rules\",\n" +
            "      \"rules\": []\n" +
            "    },\n" +
            "    {\n" +
            "      \"name\": \"Moviebase\",\n" +
            "      \"pkg\": \"com.moviebase\",\n" +
            "      \"note\": \"RevenueCat 订阅；强制 SessionState.hasPremiumEntitlement(Lbre 字段 b) + RevenueCat EntitlementInfo.isActive()/getWillRenew()\",\n" +
            "      \"rules\": [\n" +
            "        { \"type\": \"field_true\", \"cls\": \"bre\", \"field\": \"b\" },\n" +
            "        { \"type\": \"return_true\", \"cls\": \"com.revenuecat.purchases.EntitlementInfo\", \"method\": \"isActive\", \"params\": [] },\n" +
            "        { \"type\": \"return_true\", \"cls\": \"com.revenuecat.purchases.EntitlementInfo\", \"method\": \"getWillRenew\", \"params\": [] }\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    public static class Rule {
        public String type;
        public String cls;
        public String method;
        public String value;
        public String field;
        public List<String> params = new ArrayList<>();
    }

    public static class AppEntry {
        public String name;
        public String pkg;
        public String note;
        public List<Rule> rules = new ArrayList<>();
    }

    public static List<AppEntry> parse(String json) {
        List<AppEntry> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray apps = root.getJSONArray("apps");
            for (int i = 0; i < apps.length(); i++) {
                JSONObject a = apps.getJSONObject(i);
                AppEntry e = new AppEntry();
                e.name = a.optString("name", a.optString("pkg", ""));
                e.pkg = a.optString("pkg", "");
                e.note = a.optString("note", "");
                JSONArray rs = a.optJSONArray("rules");
                if (rs != null) {
                    for (int j = 0; j < rs.length(); j++) {
                        JSONObject r = rs.getJSONObject(j);
                        Rule rule = new Rule();
                        rule.type = r.optString("type", "");
                        rule.cls = r.optString("cls", "");
                        rule.method = r.optString("method", "");
                        rule.value = r.optString("value", "");
                        JSONArray ps = r.optJSONArray("params");
                        if (ps != null) {
                            for (int k = 0; k < ps.length(); k++) {
                                rule.params.add(ps.getString(k));
                            }
                        }
                        e.rules.add(rule);
                    }
                }
                out.add(e);
            }
        } catch (Exception ignored) {
            // 解析失败时返回空列表，不影响模块加载
        }
        return out;
    }
}
