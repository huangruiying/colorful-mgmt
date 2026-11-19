package org.huangry.colorful.geo.domain.service.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 模板变量处理：只扫模板一遍，填入的内容原样保留；取不到值时报错；按首次出现顺序列出变量名。 */
class PromptTemplateRendererTest {

    /** 同一变量出现多次都填上，变量以外的文字原样保留。 */
    @Test
    void shouldFillEveryOccurrenceAndKeepOtherText() {
        assertEquals("用英语书写，回答是外语时也翻成英语。",
                PromptTemplateRenderer.render("用{{reportLanguage}}书写，回答是外语时也翻成{{reportLanguage}}。", Map.of("reportLanguage", "英语")));
    }

    /** 填进去的回答里碰巧有 {{...}}、$ 和反斜杠时原样保留，不会被当成模板再替换，也不会被当成替换语法。 */
    @Test
    void shouldKeepFilledTextAsIs() {
        String answer = "价格 $1 起，路径 C:\\Users\\a，写法 {{reportLanguage}}";

        assertEquals("【回答】" + answer + "【语言】中文",
                PromptTemplateRenderer.render("【回答】{{answer}}【语言】{{reportLanguage}}", Map.of("answer", answer, "reportLanguage", "中文")));
    }

    /** 模板里的变量取不到值时报错，不把占位符原样发给模型。 */
    @Test
    void shouldRejectVariableWithoutValue() {
        var exception = assertThrows(IllegalStateException.class, () -> PromptTemplateRenderer.render("{{target}}{{issues}}", Map.of("target", "团油")));

        assertEquals("提示词变量没有取值：issues", exception.getMessage());
    }

    /** 列出模板里的变量名，按首次出现顺序去重；单层花括号和非英文名不算变量。 */
    @Test
    void shouldListVariableNamesInOrder() {
        assertEquals(List.of("target", "issues"),
                List.copyOf(PromptTemplateRenderer.listVariableNames("{{target}} {issues} {{issues}} {{target}} {{目标}} {\"a\":1}")));
    }
}
