package org.huangry.colorful.geo.domain.service;

import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisPlanService;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.*;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanQuestionEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoProjectInfoEntity;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 检查制题与按地域改写的边界：候选失败必须显式暴露，来源、语言和地域由系统固定，生成与改写都不落库。 */
class QuestionGenerateServiceTest {
    private QuestionGenerateService service;
    private LLMClient client;
    private GeoDiagnosisPlanService plans;
    private GeoProjectInfoEntity project;

    @BeforeEach
    void setUp() {
        service = new QuestionGenerateService();
        client = mock(LLMClient.class);
        plans = spy(new GeoDiagnosisPlanService());
        ReflectionTestUtils.setField(service, "llmClient", client);

        ReflectionTestUtils.setField(service, "planService", plans);
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
        assertTrue(request.getValue().prompt().contains("\"德国\""));
        assertTrue(request.getValue().prompt().contains("企业知识库有哪些？"));
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
                "[{\"text\":\"企业知识库推荐？\",\"intent\":\"INVALID\",\"brandScope\":\"NON_BRAND\"}]",
                "[{\"text\":\"企业知识库推荐？\",\"intent\":\"RECOMMENDATION\",\"brandScope\":\"NON_BRAND\"}] trailing",
                "[{\"text\":\"企业知识库推荐？\",\"intent\":\"RECOMMENDATION\",\"brandScope\":\"NON_BRAND\"}] {}")) {
            when(client.query(any(LLMQueryRequest.class))).thenReturn(LLMResponse.withoutCitations(response));
            assertThrows(LLMClientException.class, () -> service.generate(project, null, 1, "中文", List.of("美国")));
        }
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
