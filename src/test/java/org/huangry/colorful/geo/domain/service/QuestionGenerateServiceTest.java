package org.huangry.colorful.geo.domain.service;

import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisPlanService;
import org.huangry.colorful.geo.domain.service.prompt.GeoPromptTemplateService;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.*;
import org.huangry.colorful.geo.infrastructure.common.enums.PromptTemplateKey;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanQuestionEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoProjectInfoEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoPromptTemplateEntity;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 检查制题与按地域改写的边界：候选失败必须显式暴露，来源、语言和地域由系统固定，生成与改写都不落库。 */
class QuestionGenerateServiceTest {
    private QuestionGenerateService service;
    private LLMClient client;
    private GeoDiagnosisPlanService plans;
    private GeoPromptTemplateService templates;
    private GeoProjectInfoEntity project;

    @BeforeEach
    void setUp() {
        service = new QuestionGenerateService();
        client = mock(LLMClient.class);
        plans = spy(new GeoDiagnosisPlanService());
        ReflectionTestUtils.setField(service, "llmClient", client);

        ReflectionTestUtils.setField(service, "planService", plans);
        templates = mock(GeoPromptTemplateService.class);
        ReflectionTestUtils.setField(service, "geoPromptTemplateService", templates);
        // 测试模板只保留各变量及其标签，检查代码填入的取值；正式模板的措辞在提示词管理维护
        when(templates.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_PLAN_QUESTION_GENERATE))
                .thenReturn(template("问题总数：{{count}}\n语言：{{language}}\n目标地域：{{regions}}\n项目资料：\n{{project}}"));
        when(templates.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_PLAN_QUESTION_LOCALIZE))
                .thenReturn(template("语言：{{languageRule}}。\n目标地域：{{region}}\n项目资料：\n{{project}}\n母题：\n{{questions}}"));
        project = new GeoProjectInfoEntity();
        project.setName("WeKnora");
        project.setAliases(List.of("微诺拉"));
        project.setIndustry("企业知识库");
        project.setTargetAudience("开发者");
        project.setTargetMarket("北美");
        project.setTargetLanguage("英语");
    }

    /** 母题带上本次地域（去空白、去重），项目资料不含目标市场、目标语言；制题不联网，结果不落库。 */
    @Test
    void shouldReturnReviewedCandidatesWithoutSaving() {
        when(client.query(any(LLMQueryRequest.class))).thenReturn(LLMResponse.withoutCitations(
                "```json\n[{\"text\":\"企业知识库有哪些？\",\"intent\":\"RECOMMENDATION\",\"brandScope\":\"NON_BRAND\"}]\n```"));
        var candidates = service.generate(project, "generator", 1, "中文", List.of("美国", " 德国 ", "美国"));
        assertEquals("GENERATED", candidates.get(0).getSource());
        assertEquals("中文", candidates.get(0).getLanguage());
        assertNull(candidates.get(0).getRegion());
        var request = ArgumentCaptor.forClass(LLMQueryRequest.class);
        verify(client).query(request.capture());
        assertEquals("generator", request.getValue().channelCode());
        assertFalse(request.getValue().enableWebSearch());
        String prompt = request.getValue().prompt();
        assertTrue(prompt.startsWith("问题总数：1\n语言：\"中文\"\n"));
        assertTrue(prompt.contains("开发者"));
        assertTrue(prompt.contains("目标地域：[\"美国\",\"德国\"]"));
        // 母题针对本次地域，项目档案里的目标市场、目标语言不发给模型
        assertFalse(prompt.contains("北美"));
        assertFalse(prompt.contains("targetLanguage"));
        verify(plans, never()).savePlan(any(), any(), any());
    }

    /** 改写结果逐题对应母题：意图、品牌范围取母题，地域取本次，语言未指定时取模型给出的语言。 */
    @Test
    void shouldLocalizeQuestionsKeepingIntentAndBrandScopeFromBase() {
        when(client.query(any(LLMQueryRequest.class))).thenReturn(LLMResponse.withoutCitations(
                "[{\"text\":\"Welche Wissensdatenbank eignet sich für deutsche Unternehmen?\",\"language\":\"德语\",\"intent\":\"PURCHASE\",\"brandScope\":\"BRAND\"},"
                        + "{\"text\":\"Ist WeKnora in Deutschland zuverlässig?\",\"language\":\"德语\"}]"));
        var localized = service.localizeQuestions(project, "generator", " 德国 ", null,
                List.of(base("企业知识库有哪些？", "RECOMMENDATION", "NON_BRAND"), base("WeKnora 可靠吗？", "AWARENESS", "BRAND")));
        assertEquals(2, localized.size());
        assertEquals("RECOMMENDATION", localized.get(0).getIntent());
        assertEquals("NON_BRAND", localized.get(0).getBrandScope());
        assertEquals("BRAND", localized.get(1).getBrandScope());
        assertEquals("德国", localized.get(0).getRegion());
        assertEquals("德语", localized.get(1).getLanguage());
        assertEquals("GENERATED", localized.get(0).getSource());
        var request = ArgumentCaptor.forClass(LLMQueryRequest.class);
        verify(client).query(request.capture());
        assertEquals("generator", request.getValue().channelCode());
        assertTrue(request.getValue().prompt().contains("目标地域：\"德国\""));
        assertTrue(request.getValue().prompt().contains("企业知识库有哪些？"));
        // 没指定语言时让模型按地域选常用语言
        assertTrue(request.getValue().prompt().startsWith("语言：使用该地域用户向 AI 提问时最常用的语言。"));
        // 地域和语言只看本次输入，项目档案里的目标市场、目标语言不发给模型；改写不联网
        assertFalse(request.getValue().prompt().contains("北美"));
        assertFalse(request.getValue().prompt().contains("targetMarket"));
        assertFalse(request.getValue().enableWebSearch());
        verify(plans, never()).savePlan(any(), any(), any());
    }

    /** 指定语言时，每道改写题都用指定语言，不采用模型给出的语言。 */
    @Test
    void shouldUseRequestedLanguageForEveryLocalizedQuestion() {
        when(client.query(any(LLMQueryRequest.class))).thenReturn(LLMResponse.withoutCitations(
                "[{\"text\":\"Which knowledge base suits companies in Singapore?\",\"language\":\"马来语\"}]"));
        var localized = service.localizeQuestions(project, null, "新加坡", "英语",
                List.of(base("企业知识库有哪些？", "RECOMMENDATION", "NON_BRAND")));
        assertEquals("英语", localized.get(0).getLanguage());
        assertEquals("新加坡", localized.get(0).getRegion());
        var request = ArgumentCaptor.forClass(LLMQueryRequest.class);
        verify(client).query(request.capture());
        assertTrue(request.getValue().prompt().startsWith("语言：全部使用指定语言 \"英语\"。"));
    }

    /** 数量不符、缺语言、非品牌题出现品牌名、改写后重复都整批失败，不返回部分结果。 */
    @Test
    void shouldRejectLocalizedOutputThatBreaksTheBase() {
        var bases = List.of(base("企业知识库有哪些？", "RECOMMENDATION", "NON_BRAND"), base("开源知识库怎么选？", "PURCHASE", "NON_BRAND"));
        for (String response : List.of("不是 JSON",
                "[{\"text\":\"Which knowledge base?\",\"language\":\"英语\"}]",
                "[{\"text\":\"Which knowledge base?\",\"language\":\"英语\"},{\"text\":\"How to choose an open source one?\"}]",
                "[{\"text\":\"Which knowledge base?\",\"language\":\"英语\"},{\"text\":\"Is WeKnora good in the US?\",\"language\":\"英语\"}]",
                "[{\"text\":\"Which knowledge base?\",\"language\":\"英语\"},{\"text\":\"which knowledge base?\",\"language\":\"英语\"}]")) {
            when(client.query(any(LLMQueryRequest.class))).thenReturn(LLMResponse.withoutCitations(response));
            assertThrows(LLMClientException.class, () -> service.localizeQuestions(project, null, "美国", null, bases));
        }
    }

    /** 地域为空或超长、语言超长、母题为空或超过 100 道、母题违反保存规则，都在调用模型前拒绝。 */
    @Test
    void shouldRejectInvalidLocalizeInputBeforeCallingModel() {
        var bases = List.of(base("企业知识库有哪些？", "RECOMMENDATION", "NON_BRAND"));
        assertThrows(IllegalArgumentException.class, () -> service.localizeQuestions(project, null, " ", null, bases));
        assertThrows(IllegalArgumentException.class, () -> service.localizeQuestions(project, null, "地".repeat(51), null, bases));
        assertThrows(IllegalArgumentException.class, () -> service.localizeQuestions(project, null, "美国", "语".repeat(101), bases));
        assertThrows(IllegalArgumentException.class, () -> service.localizeQuestions(project, null, "美国", null, List.of()));
        List<GeoDiagnosisPlanQuestionEntity> tooMany = new ArrayList<>();
        for (int index = 0; index < 101; index++) tooMany.add(base("企业知识库问题 " + index, "RECOMMENDATION", "NON_BRAND"));
        assertThrows(IllegalArgumentException.class, () -> service.localizeQuestions(project, null, "美国", null, tooMany));
        assertThrows(IllegalArgumentException.class, () -> service.localizeQuestions(project, null, "美国", null,
                List.of(base("WeKnora 好用吗？", "RECOMMENDATION", "NON_BRAND"))));
        verifyNoInteractions(client);
    }

    /** 两段提示词声明的每个变量代码都给了取值：模板写满全部变量也能填完，不会把占位符发给模型。 */
    @Test
    void shouldFillEveryDeclaredVariable() {
        when(templates.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_PLAN_QUESTION_GENERATE))
                .thenReturn(template(allVariables(PromptTemplateKey.DIAGNOSIS_PLAN_QUESTION_GENERATE)));
        when(templates.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_PLAN_QUESTION_LOCALIZE))
                .thenReturn(template(allVariables(PromptTemplateKey.DIAGNOSIS_PLAN_QUESTION_LOCALIZE)));
        when(client.query(any(LLMQueryRequest.class))).thenReturn(
                LLMResponse.withoutCitations("[{\"text\":\"企业知识库有哪些？\",\"intent\":\"RECOMMENDATION\",\"brandScope\":\"NON_BRAND\"}]"),
                LLMResponse.withoutCitations("[{\"text\":\"Which knowledge base?\",\"language\":\"英语\"}]"));

        service.generate(project, null, 1, "中文", List.of("美国"));
        service.localizeQuestions(project, null, "美国", null, List.of(base("企业知识库有哪些？", "RECOMMENDATION", "NON_BRAND")));

        var request = ArgumentCaptor.forClass(LLMQueryRequest.class);
        verify(client, times(2)).query(request.capture());
        assertTrue(request.getAllValues().stream().noneMatch(sent -> sent.prompt().contains("{{")));
    }

    /** 提示词未配置时明确报错，不调用模型，也不退回代码里的默认文字。 */
    @Test
    void shouldNotCallModelWhenPromptMissing() {
        when(templates.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_PLAN_QUESTION_GENERATE))
                .thenThrow(new NoSuchElementException("提示词未配置：诊断方案-生成母题提示词"));
        when(templates.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_PLAN_QUESTION_LOCALIZE))
                .thenThrow(new NoSuchElementException("提示词未配置：诊断方案-按地域改写问题提示词"));

        assertThrows(NoSuchElementException.class, () -> service.generate(project, null, 1, "中文", List.of("美国")));
        assertThrows(NoSuchElementException.class, () -> service.localizeQuestions(project, null, "美国", null,
                List.of(base("企业知识库有哪些？", "RECOMMENDATION", "NON_BRAND"))));
        verifyNoInteractions(client);
    }

    /**
     * @param content 模板内容
     * @return 提示词管理里的一版模板
     */
    private GeoPromptTemplateEntity template(String content) {
        var template = new GeoPromptTemplateEntity();
        template.setVersion(1);
        template.setContent(content);
        return template;
    }

    /**
     * @param key 提示词
     * @return 写满该提示词声明的全部变量的模板，用于确认代码每个都给了取值
     */
    private String allVariables(PromptTemplateKey key) {
        return key.getVariables().stream().map(variable -> "{{" + variable.name() + "}}").collect(Collectors.joining("|"));
    }

    /**
     * 构造一道模型生成的母题。
     * @param text 母题正文
     * @param intent 提问意图
     * @param brandScope 品牌范围
     * @return 未保存的母题
     */
    private GeoDiagnosisPlanQuestionEntity base(String text, String intent, String brandScope) {
        var question = new GeoDiagnosisPlanQuestionEntity();
        question.setQuestionText(text); question.setSource("GENERATED"); question.setIntent(intent);
        question.setBrandScope(brandScope); question.setLanguage("中文");
        return question;
    }

    @Test
    void shouldRejectInvalidOrMisleadingModelOutput() {
        for (String response : List.of("说明而不是 JSON", "[]", "null", "[null]", "[123]",
                "[{\"text\":{},\"intent\":\"RECOMMENDATION\",\"brandScope\":\"NON_BRAND\"}]",
                "[{\"text\":\"WeKnora 好用吗？\",\"intent\":\"RECOMMENDATION\",\"brandScope\":\"NON_BRAND\"}]",
                // 只问竞品、或只比较竞品的题不能标成品牌认知、竞品比较
                "[{\"text\":\"Dify 好用吗？\",\"intent\":\"AWARENESS\",\"brandScope\":\"BRAND\"}]",
                "[{\"text\":\"Dify 和 FastGPT 哪个好？\",\"intent\":\"COMPARISON\",\"brandScope\":\"COMPARISON\"}]",
                "[{\"text\":\"企业知识库推荐？\",\"intent\":\"INVALID\",\"brandScope\":\"NON_BRAND\"}]",
                "[{\"text\":\"企业知识库推荐？\",\"intent\":\"RECOMMENDATION\",\"brandScope\":\"NON_BRAND\"}] trailing",
                "[{\"text\":\"企业知识库推荐？\",\"intent\":\"RECOMMENDATION\",\"brandScope\":\"NON_BRAND\"}] {}")) {
            when(client.query(any(LLMQueryRequest.class))).thenReturn(LLMResponse.withoutCitations(response));
            assertThrows(LLMClientException.class, () -> service.generate(project, null, 1, "中文", List.of("美国")));
        }
    }

    /** 品牌认知母题没问到本项目时不调用模型；改写后丢了项目名称（如写成“这个品牌”）时整批失败，不返回部分结果。 */
    @Test
    void shouldRequireBrandQuestionToNameProjectBeforeAndAfterLocalizing() {
        assertThrows(IllegalArgumentException.class, () -> service.localizeQuestions(project, null, "德国", null,
                List.of(base("Dify 可靠吗？", "AWARENESS", "BRAND"))));
        verifyNoInteractions(client);

        when(client.query(any(LLMQueryRequest.class))).thenReturn(LLMResponse.withoutCitations(
                "[{\"text\":\"Ist diese Marke in Deutschland zuverlässig?\",\"language\":\"德语\"}]"));
        assertThrows(LLMClientException.class, () -> service.localizeQuestions(project, null, "德国", null,
                List.of(base("WeKnora 可靠吗？", "AWARENESS", "BRAND"))));
    }

    /** 数量越界、母题语言为空、没有地域（含全是空白）或地域超长，都在调用模型前拒绝。 */
    @Test
    void shouldRejectInvalidInputBeforeCallingModel() {
        assertThrows(IllegalArgumentException.class, () -> service.generate(project, null, 101, "中文", List.of("美国")));
        assertThrows(IllegalArgumentException.class, () -> service.generate(project, null, 1, " ", List.of("美国")));
        assertThrows(IllegalArgumentException.class, () -> service.generate(project, null, 1, "中文", null));
        assertThrows(IllegalArgumentException.class, () -> service.generate(project, null, 1, "中文", List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> service.generate(project, null, 1, "中文", List.of("地".repeat(51))));
        verifyNoInteractions(client);
    }
}
