package org.huangry.colorful.geo.domain.service.prompt;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板的变量处理：找出模板里写的 {{变量名}}，并按变量名填入数据。
 * <p>只扫模板一遍，填进去的内容不会再被当成模板替换，回答原文里碰巧出现的 {{...}}、$ 和反斜杠都原样保留；
 * 取不到值的变量直接报错，不会把占位符原样发给模型。保存模板时的变量校验也用这里找出的变量名。</p>
 */
public final class PromptTemplateRenderer {
    /** 模板里的变量写法：两层花括号包住的英文变量名。 */
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\w+)}}");

    private PromptTemplateRenderer() {
    }

    /**
     * 按变量名把数据填进模板。
     * @param template 提示词模板
     * @param variables 变量名到取值的映射；该处要留空时传空字符串
     * @return 填好数据的提示词
     * @throws IllegalStateException 模板里有变量取不到值（代码漏传，或模板用了未声明的变量）
     */
    public static String render(String template, Map<String, String> variables) {
        return VARIABLE.matcher(template).replaceAll(match -> {
            String value = variables.get(match.group(1));
            if (value == null) {
                throw new IllegalStateException("提示词变量没有取值：" + match.group(1));
            }
            return Matcher.quoteReplacement(value);
        });
    }

    /**
     * 找出模板里用到的变量名。
     * @param template 提示词模板
     * @return 变量名，按首次出现的顺序去重
     */
    public static Set<String> listVariableNames(String template) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = VARIABLE.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
