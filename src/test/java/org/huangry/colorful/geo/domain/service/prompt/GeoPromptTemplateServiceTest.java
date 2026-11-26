package org.huangry.colorful.geo.domain.service.prompt;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.huangry.colorful.geo.infrastructure.common.enums.PromptTemplateKey;
import org.huangry.colorful.geo.infrastructure.repository.GeoPromptTemplateDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoPromptTemplateEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 提示词模板的读取与保存：缺失明确报错，保存时校验变量、在当前版本上加 1，内容相同或版本号被抢先时拒绝；建表脚本里的初始模板都合规。 */
class GeoPromptTemplateServiceTest {
    private final GeoPromptTemplateService service = new GeoPromptTemplateService();
    private final GeoPromptTemplateDao templates = mock(GeoPromptTemplateDao.class);
    private static final PromptTemplateKey ADVICE = PromptTemplateKey.DIAGNOSIS_REPORT_ADVICE;
    private static final String VALID_ADVICE = "【目标】{{target}}\n【问题】{{issues}}\n用{{reportLanguage}}书写";

    /** 库里没有这个提示词或这一版时明确报错，异常信息写明是哪一个，执行器据此暂停报告。 */
    @Test
    void shouldRejectMissingTemplate() {
        assertEquals("提示词未配置：诊断报告-优化建议提示词", assertThrows(NoSuchElementException.class, () -> service.getCurrentTemplate(ADVICE)).getMessage());
        assertEquals("诊断报告-优化建议提示词第 3 版不存在", assertThrows(NoSuchElementException.class, () -> service.getTemplate(ADVICE, 3)).getMessage());
    }

    /** 保存在当前版本上加 1，还没有任何版本时为第 1 版；返回回读的记录。 */
    @Test
    void shouldCreateNextVersion() {
        when(templates.selectOne(any())).thenReturn(template(4, "旧内容 {{issues}}"));
        var saved = template(5, VALID_ADVICE);
        when(templates.selectById(any())).thenReturn(saved);

        assertSame(saved, service.createTemplateVersion(ADVICE, VALID_ADVICE));
        var inserted = ArgumentCaptor.forClass(GeoPromptTemplateEntity.class);
        verify(templates).insert(inserted.capture());
        assertEquals("DIAGNOSIS_REPORT_ADVICE", inserted.getValue().getPromptKey());
        assertEquals(5, inserted.getValue().getVersion());

        reset(templates);
        when(templates.selectOne(any())).thenReturn(null);
        service.createTemplateVersion(ADVICE, VALID_ADVICE);
        verify(templates).insert(argThatVersion(1));
    }

    /** 内容为空、超长、用了未声明的变量（多半是拼错）或缺少必填变量时拒绝，不写库。 */
    @Test
    void shouldRejectInvalidContent() {
        assertEquals("提示词内容不能为空", assertThrows(IllegalArgumentException.class, () -> service.createTemplateVersion(ADVICE, "  ")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.createTemplateVersion(ADVICE, "{{issues}}" + "长".repeat(20000)));
        assertEquals("提示词里有未定义的变量：{{isues}}，可用变量见页面右侧",
                assertThrows(IllegalArgumentException.class, () -> service.createTemplateVersion(ADVICE, "{{target}}{{isues}}")).getMessage());
        assertEquals("提示词缺少必填变量：{{issues}}（规则发现的问题列表（JSON 数组，每项有编号、数字与回答原文证据））",
                assertThrows(IllegalArgumentException.class, () -> service.createTemplateVersion(ADVICE, "{{target}} 用{{reportLanguage}}书写")).getMessage());
        verify(templates, never()).insert(any(GeoPromptTemplateEntity.class));
    }

    /** 与当前版本相同时不新增；同一版本号被另一次保存抢先时提示刷新，不写出重复版本。 */
    @Test
    void shouldRejectUnchangedContentAndConcurrentSave() {
        when(templates.selectOne(any())).thenReturn(template(2, VALID_ADVICE));
        assertEquals("内容与当前版本相同，无需保存", assertThrows(IllegalArgumentException.class, () -> service.createTemplateVersion(ADVICE, VALID_ADVICE)).getMessage());
        verify(templates, never()).insert(any(GeoPromptTemplateEntity.class));

        when(templates.insert(any(GeoPromptTemplateEntity.class))).thenThrow(new DuplicateKeyException("uk_prompt_template_version"));
        assertEquals("提示词刚被保存过新版本，请刷新后再保存",
                assertThrows(IllegalArgumentException.class, () -> service.createTemplateVersion(ADVICE, VALID_ADVICE + "。")).getMessage());
    }

    /** 建表脚本为每个提示词写了初始模板：判定从第 2 版开始（沿用 v2 编号）、其余从第 1 版开始，且都只用声明的变量、必填变量齐全。 */
    @Test
    void shouldSeedEveryPromptWithValidTemplate() throws Exception {
        String ddl = new String(new ClassPathResource("db/mysql/001_init.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Matcher seed = Pattern.compile("SELECT '(\\w+)', (\\d+), '((?:[^']|'')*)'\\s+FROM DUAL", Pattern.DOTALL).matcher(ddl);
        Map<PromptTemplateKey, Integer> versions = new EnumMap<>(PromptTemplateKey.class);
        while (seed.find()) {
            PromptTemplateKey key = PromptTemplateKey.valueOf(seed.group(1));
            assertNull(versions.put(key, Integer.parseInt(seed.group(2))), "重复的初始模板：" + key);
            ReflectionTestUtils.invokeMethod(service, "validateTemplateContent", key, seed.group(3).replace("''", "'"));
        }

        assertEquals(PromptTemplateKey.values().length, versions.size());
        assertEquals(2, versions.get(PromptTemplateKey.DIAGNOSIS_REPORT_RESULT_JUDGE));
        assertEquals(1, versions.get(PromptTemplateKey.DIAGNOSIS_REPORT_ADVICE));
    }

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "prompt-template-test"), GeoPromptTemplateEntity.class);
        ReflectionTestUtils.setField(service, "promptTemplateDao", templates);
    }

    /**
     * @param version 版本号
     * @param content 模板内容
     * @return 一版优化建议提示词
     */
    private GeoPromptTemplateEntity template(int version, String content) {
        var template = new GeoPromptTemplateEntity();
        template.setId((long) version);
        template.setPromptKey(ADVICE.name());
        template.setVersion(version);
        template.setContent(content);
        return template;
    }

    /**
     * @param version 期望写入的版本号
     * @return 匹配该版本号的写入记录
     */
    private GeoPromptTemplateEntity argThatVersion(int version) {
        return org.mockito.ArgumentMatchers.argThat(template -> template.getVersion() == version);
    }
}
