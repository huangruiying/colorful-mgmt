package org.huangry.colorful.geo.domain.service.diagnosis.report.content.strategy;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 地域提升策略输出校验：打法必须依据已知机会并有操作路径，品牌方向没有依据时只能是推测，防止模型编造结论。 */
class RegionStrategyPromptTest {
    private final RegionStrategyPrompt regionStrategyPrompt = new RegionStrategyPrompt();
    private final JSONArray opportunities = JSON.parseArray("[{\"id\":\"R1-O1\",\"type\":\"COMPETITOR_OCCUPIED\",\"title\":\"竞品占位\"},"
            + "{\"id\":\"R1-O4\",\"type\":\"NOT_FIRST_CHOICE\",\"title\":\"推荐未首推\"}]");

    /** 未知编号剔除；没有依据或没有步骤的打法丢弃；声称原文却没有依据的品牌方向丢弃；步骤去空白并最多保留 5 步。 */
    @Test
    void shouldKeepOnlyItemsBackedByKnownOpportunities() {
        String output = """
                说明文字```json
                {"brandDirections":[{"title":"突出磁吸充电","content":"结合当地户外场景","evidenceStatus":"INFERRED","opportunityIds":[]},
                                    {"title":"无依据却称原文","content":"x","evidenceStatus":"ANSWER_STATED","opportunityIds":["R9-O1"]}],
                 "mentionPlays":[{"title":"进入推荐清单","steps":[" 在 Reddit 发测评 ","","a","b","c","d","e"],"evidenceStatus":"INFERRED","opportunityIds":["R1-O1","R9-O9"]},
                                 {"title":"没有步骤","steps":[],"evidenceStatus":"INFERRED","opportunityIds":["R1-O1"]},
                                 {"title":"编号不存在","steps":["x"],"evidenceStatus":"INFERRED","opportunityIds":["R9-O9"]}],
                 "rankPlays":[{"title":"证据状态非法","steps":["对比评测"],"evidenceStatus":"OTHER","opportunityIds":["R1-O4"]}]}
                ```""";

        JSONObject strategy = regionStrategyPrompt.parseStrategy(output, opportunities);

        assertEquals(List.of("突出磁吸充电"), titles(strategy.getJSONArray("brandDirections")));
        assertEquals(List.of("进入推荐清单"), titles(strategy.getJSONArray("mentionPlays")));
        JSONObject play = strategy.getJSONArray("mentionPlays").getJSONObject(0);
        assertEquals(List.of("R1-O1"), play.getJSONArray("opportunityIds"));
        assertEquals(List.of("在 Reddit 发测评", "a", "b", "c", "d"), play.getJSONArray("steps"));
        assertEquals(0, strategy.getJSONArray("rankPlays").size());
    }

    /** 输出不是 JSON，或三类策略都没有有效条目时明确失败，由调用方记为该地域策略失败。 */
    @Test
    void shouldRejectOutputWithoutUsableItems() {
        assertThrows(IllegalArgumentException.class, () -> regionStrategyPrompt.parseStrategy("无法回答", opportunities));
        assertThrows(IllegalArgumentException.class, () -> regionStrategyPrompt.parseStrategy("""
                {"brandDirections":[],"rankPlays":[],
                 "mentionPlays":[{"title":"x","steps":["a"],"evidenceStatus":"INFERRED","opportunityIds":["R9-O1"]}]}""", opportunities));
    }

    /** 模板里的地域名称、竞品、样本、宣传状态、可见度、机会清单与报告语言都按变量名填上，机会清单是完整 JSON，模型才能引用编号。 */
    @Test
    void shouldFillRegionVariables() {
        JSONObject region = new JSONObject().fluentPut("name", "德国").fluentPut("sample", new JSONObject().fluentPut("questions", 3))
                .fluentPut("portrayal", new JSONObject().fluentPut("status", "NEUTRAL")).fluentPut("visibility", new JSONObject()).fluentPut("opportunities", opportunities);
        JSONObject project = new JSONObject().fluentPut("name", "Olight").fluentPut("competitors", JSON.parseArray("[\"Fenix\"]"));

        String prompt = regionStrategyPrompt.buildPrompt("{{target}}|{{sellingPoints}}|{{competitors}}|【地域】{{region}}|{{sample}}|{{visibility}}|{{opportunities}}|用{{reportLanguage}}书写",
                project, region, "英语");

        assertEquals("Olight||[\"Fenix\"]|【地域】德国|{\"questions\":3}|{}|" + JSON.toJSONString(opportunities) + "|用英语书写", prompt);
        String allVariables = org.huangry.colorful.geo.infrastructure.common.enums.PromptTemplateKey.DIAGNOSIS_REPORT_REGION_STRATEGY.getVariables().stream()
                .map(variable -> "{{" + variable.name() + "}}").collect(java.util.stream.Collectors.joining("|"));
        assertFalse(regionStrategyPrompt.buildPrompt(allVariables, project, region, "英语").contains("{{"));
    }

    /** @return 每条策略的标题 */
    private List<String> titles(JSONArray items) {
        return items.stream().map(item -> ((JSONObject) item).getString("title")).toList();
    }
}
