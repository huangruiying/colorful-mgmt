package org.huangry.colorful.geo.domain.service.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.common.enums.PromptTemplateKey;
import org.huangry.colorful.geo.infrastructure.common.enums.PromptTemplateKey.PromptVariable;
import org.huangry.colorful.geo.infrastructure.repository.GeoPromptTemplateDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoPromptTemplateEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 提示词模板的领域服务：按提示词读取当前版本或指定版本，保存时校验变量并新增一版，旧版本保留不覆盖。
 * <p>库里是提示词的唯一来源，缺了就明确报错，不退回代码里的默认文字。使用方按 {@link PromptTemplateKey} 取模板后自己填变量；
 * 本服务只管模板的读写与校验，不填变量、不调用模型。</p>
 */
@Service
@Slf4j
public class GeoPromptTemplateService {
    /** 模板长度上限，防止误粘贴超大内容；现有最长的判定提示词约 3200 字。 */
    private static final int CONTENT_MAX_LENGTH = 20000;

    @Resource
    private GeoPromptTemplateDao promptTemplateDao;

    /**
     * 读取一个提示词的当前版本（版本号最大的一版）。
     * @param key 提示词
     * @return 当前版本
     * @throws NoSuchElementException 库里还没有这个提示词
     */
    public GeoPromptTemplateEntity getCurrentTemplate(PromptTemplateKey key) {
        GeoPromptTemplateEntity template = findCurrentTemplate(key);
        if (template == null) {
            throw new NoSuchElementException("提示词未配置：" + key.getTitle());
        }
        return template;
    }

    /**
     * 查找一个提示词的当前版本；提示词管理页面要显示“未配置”，因此允许不存在。
     * @param key 提示词
     * @return 当前版本，库里还没有时为 null
     */
    public GeoPromptTemplateEntity findCurrentTemplate(PromptTemplateKey key) {
        return promptTemplateDao.selectOne(new LambdaQueryWrapper<GeoPromptTemplateEntity>()
                .eq(GeoPromptTemplateEntity::getPromptKey, key.name())
                .orderByDesc(GeoPromptTemplateEntity::getVersion).last("LIMIT 1"));
    }

    /**
     * 读取一个提示词的指定版本，用于续跑沿用报告冻结的版本，或在页面上载入旧版本。
     * @param key 提示词
     * @param version 版本号
     * @return 该版本
     * @throws NoSuchElementException 没有这一版
     */
    public GeoPromptTemplateEntity getTemplate(PromptTemplateKey key, int version) {
        GeoPromptTemplateEntity template = promptTemplateDao.selectOne(new LambdaQueryWrapper<GeoPromptTemplateEntity>()
                .eq(GeoPromptTemplateEntity::getPromptKey, key.name())
                .eq(GeoPromptTemplateEntity::getVersion, version));
        if (template == null) {
            throw new NoSuchElementException(key.getTitle() + "第 " + version + " 版不存在");
        }
        return template;
    }

    /**
     * 列出一个提示词的全部版本，新的在前；只取版本号与保存时间，不读内容。
     * @param key 提示词
     * @return 各版本，没有时为空列表
     */
    public List<GeoPromptTemplateEntity> listTemplateVersions(PromptTemplateKey key) {
        return promptTemplateDao.selectList(new LambdaQueryWrapper<GeoPromptTemplateEntity>()
                .select(GeoPromptTemplateEntity::getId, GeoPromptTemplateEntity::getVersion, GeoPromptTemplateEntity::getCreatedAt)
                .eq(GeoPromptTemplateEntity::getPromptKey, key.name())
                .orderByDesc(GeoPromptTemplateEntity::getVersion));
    }

    /**
     * 把编辑后的内容保存为这个提示词的新版本：版本号在当前版本上加 1，还没有任何版本时为第 1 版；旧版本保留。
     * <p>写入 geo_prompt_template 1 条。已有报告不受影响：正在判定的报告沿用开始时冻结的版本，之后新执行的步骤用这一版。</p>
     * @param key 提示词
     * @param content 编辑后的模板
     * @return 回读后的新版本，带数据库生成的保存时间
     * @throws IllegalArgumentException 内容为空或超长、用了未声明的变量、缺少必填变量、与当前版本相同，或同时有另一次保存抢先
     */
    @Transactional
    public GeoPromptTemplateEntity createTemplateVersion(PromptTemplateKey key, String content) {
        // 1. 校验内容与变量，不合格的模板不进库
        validateTemplateContent(key, content);
        // 2. 与当前版本相同时不新增版本，避免版本列表里堆出内容一样的版本
        GeoPromptTemplateEntity current = findCurrentTemplate(key);
        if (current != null && current.getContent().equals(content)) {
            throw new IllegalArgumentException("内容与当前版本相同，无需保存");
        }
        // 3. 新增一版；同一版本号已被另一次保存占用时由唯一键拒绝，提示刷新后再保存
        GeoPromptTemplateEntity template = new GeoPromptTemplateEntity();
        template.setPromptKey(key.name());
        template.setVersion(current == null ? 1 : current.getVersion() + 1);
        template.setContent(content);
        try {
            promptTemplateDao.insert(template);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("提示词刚被保存过新版本，请刷新后再保存", exception);
        }
        log.info("提示词已保存新版本 promptKey={}, version={}, length={}", key, template.getVersion(), content.length());
        // 4. 回读数据库生成的保存时间
        return promptTemplateDao.selectById(template.getId());
    }

    /**
     * 校验模板：不能为空或超长；只能使用这个提示词声明的变量，必填变量都要出现。
     * @param key 提示词
     * @param content 模板
     * @throws IllegalArgumentException 不满足任一条
     */
    private void validateTemplateContent(PromptTemplateKey key, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("提示词内容不能为空");
        }
        if (content.length() > CONTENT_MAX_LENGTH) {
            throw new IllegalArgumentException("提示词最多 " + CONTENT_MAX_LENGTH + " 字");
        }
        // 未声明的变量多半是拼错，执行时取不到值，保存时就拒绝
        Set<String> declared = key.getVariables().stream().map(PromptVariable::name).collect(Collectors.toSet());
        Set<String> used = PromptTemplateRenderer.listVariableNames(content);
        List<String> unknown = used.stream().filter(name -> !declared.contains(name)).map(name -> "{{" + name + "}}").toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("提示词里有未定义的变量：" + String.join("、", unknown) + "，可用变量见页面右侧");
        }
        // 必填变量是这段提示词要处理的数据本身，缺了提示词就失去作用
        List<String> missing = key.getVariables().stream().filter(PromptVariable::required).filter(variable -> !used.contains(variable.name()))
                .map(variable -> "{{" + variable.name() + "}}（" + variable.description() + "）").toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("提示词缺少必填变量：" + String.join("、", missing));
        }
    }
}
