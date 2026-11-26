package org.huangry.colorful.geo.domain.service.diagnosis;

import org.huangry.colorful.geo.infrastructure.repository.*;
import org.huangry.colorful.geo.infrastructure.repository.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 问题及关联规则测试；事务原子性另用本地 MySQL 验证。 */
class GeoDiagnosisPlanServiceTest {
    private final GeoDiagnosisPlanService service = new GeoDiagnosisPlanService();

    @Test
    void shouldRejectBrandNamesAndAliasesInNonBrandQuestions() {
        for (String text : List.of("ＷｅＫｎｏｒａ怎么部署", "w e k n o r a好用吗", "微诺拉推荐吗")) {
            assertThrows(IllegalArgumentException.class, () -> service.validateQuestions(project(), List.of(question(text))));
        }
        var branded = question("WeKnora怎么部署");
        branded.setBrandScope("BRAND");
        assertDoesNotThrow(() -> service.validateQuestions(project(), List.of(branded)));
    }

    @Test
    void shouldRejectDuplicatesAndNormalizeOrder() {
        assertThrows(IllegalArgumentException.class, () -> service.validateQuestions(project(),
                List.of(question("RAG 有哪些？"), question("rag有哪些？"))));
        var first = question(" 企业知识库如何选型？ ");
        var second = question("开源知识库有哪些？");
        service.validateQuestions(project(), List.of(first, second));
        assertEquals("企业知识库如何选型？", first.getQuestionText());
        assertEquals(0, first.getSortOrder());
        assertEquals(1, second.getSortOrder());
    }

    @Test
    void shouldAcceptFiveHundredQuestionsAndRejectMore() {
        List<GeoDiagnosisPlanQuestionEntity> fiveHundred = new java.util.ArrayList<>();
        for (int index = 0; index < 500; index++) fiveHundred.add(question("企业知识库选型问题 " + index));
        assertDoesNotThrow(() -> service.validateQuestions(project(), fiveHundred));
        fiveHundred.add(question("企业知识库选型问题 500"));
        assertThrows(IllegalArgumentException.class, () -> service.validateQuestions(project(), fiveHundred));
    }

    /** 地域可空：去除首尾空白，空白和未填写都视为不分地域。 */
    @Test
    void shouldTrimRegionAndTreatBlankAsUnassigned() {
        var trimmed = question("美国用户常买哪款手电？"); trimmed.setRegion(" 美国 ");
        var blank = question("德国用户常买哪款手电？"); blank.setRegion("   ");
        var missing = question("通用的手电推荐有哪些？");
        service.validateQuestions(project(), List.of(trimmed, blank, missing));
        assertEquals("美国", trimmed.getRegion());
        assertNull(blank.getRegion());
        assertNull(missing.getRegion());
    }

    /** 地域与数据库列长一致：50 字以内保存，超过拒绝。 */
    @Test
    void shouldRejectRegionLongerThanFiftyCharacters() {
        var atLimit = question("五十字地域的问题？"); atLimit.setRegion("地".repeat(50));
        assertDoesNotThrow(() -> service.validateQuestions(project(), List.of(atLimit)));
        var tooLong = question("五十一字地域的问题？"); tooLong.setRegion("地".repeat(51));
        assertThrows(IllegalArgumentException.class, () -> service.validateQuestions(project(), List.of(tooLong)));
    }

    @Test
    void shouldRejectDisabledChannelsBeforeDatabaseWrite() {
        var projectDao = mock(GeoProjectInfoDao.class);
        var channelDao = mock(GeoLlmChannelDao.class);
        var planDao = mock(GeoDiagnosisPlanDao.class);
        ReflectionTestUtils.setField(service, "projectDao", projectDao);
        ReflectionTestUtils.setField(service, "llmChannelDao", channelDao);
        ReflectionTestUtils.setField(service, "baseMapper", planDao);
        when(projectDao.selectById(1L)).thenReturn(project());
        var disabled = new GeoLlmChannelEntity(); disabled.setEnabled(false);
        when(channelDao.selectById(2L)).thenReturn(disabled);
        var plan = new GeoDiagnosisPlanEntity();
        plan.setName("基线"); plan.setProjectId(1L); plan.setRepeatCount(3);
        var channel = new GeoDiagnosisPlanChannelEntity(); channel.setChannelId(2L);
        assertThrows(IllegalArgumentException.class, () -> service.savePlan(plan,
                List.of(question("企业知识库有哪些？")), List.of(channel)));
        verifyNoInteractions(planDao);
    }

    private GeoProjectInfoEntity project() {
        var project = new GeoProjectInfoEntity();
        project.setName("WeKnora"); project.setAliases(List.of("微诺拉")); return project;
    }

    private GeoDiagnosisPlanQuestionEntity question(String text) {
        var question = new GeoDiagnosisPlanQuestionEntity();
        question.setQuestionText(text); question.setSource("MANUAL"); question.setIntent("RECOMMENDATION");
        question.setBrandScope("NON_BRAND"); question.setLanguage("中文"); return question;
    }
}
