package com.novelplayer.application.script;

import com.novelplayer.domain.script.ScriptDocument;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 执行基础字段校验以及注解无法表达的跨引用校验，
 * 例如场景人物编号、地点编号、对白 speaker_id 是否真实存在。
 */
@Component
public class ScriptSchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(ScriptSchemaValidator.class);

    private final Validator validator;

    /**
     * 注入 Bean Validation 校验器。
     *
     * @param validator Jakarta Validation 校验器。
     */
    public ScriptSchemaValidator(Validator validator) {
        this.validator = validator;
    }

    /**
     * 校验剧本文档的字段约束和跨引用约束。
     *
     * @param document 待校验的剧本文档。
     */
    public void validate(ScriptDocument document) {
        log.debug("Validating script document schemaVersion={} characterCount={} locationCount={} sceneCount={}",
                document.schemaVersion(), safeSize(document.characters()), safeSize(document.locations()), safeSize(document.scenes()));
        // Bean Validation 负责字段级约束，例如必填、列表长度和字符串格式。
        List<String> errors = validator.validate(document).stream()
                .map(this::formatViolation)
                .sorted()
                .toList();

        // record 注解不适合表达跨集合引用，所以先构建编号集合，再校验场景引用。
        Set<String> characterIds = new HashSet<>();
        document.characters().forEach(character -> characterIds.add(character.id()));
        Set<String> locationIds = new HashSet<>();
        document.locations().forEach(location -> locationIds.add(location.id()));

        List<String> referenceErrors = document.scenes().stream()
                .flatMap(scene -> {
                    List<String> sceneErrors = new java.util.ArrayList<>();
                    if (!locationIds.contains(scene.locationId())) {
                        sceneErrors.add(scene.id() + " 引用了不存在的地点：" + scene.locationId());
                    }
                    scene.characters().stream()
                            .filter(characterId -> !characterIds.contains(characterId))
                            .forEach(characterId -> sceneErrors.add(scene.id() + " 引用了不存在的人物：" + characterId));
                    // 对白块必须有合法 speakerId；动作和转场块允许没有说话人。
                    scene.blocks().stream()
                            .filter(block -> "dialogue".equals(block.type()))
                            .filter(block -> block.speakerId() == null || !characterIds.contains(block.speakerId()))
                            .forEach(block -> sceneErrors.add(scene.id() + " 存在未声明说话人的对白：" + block.speakerId()));
                    return sceneErrors.stream();
                })
                .toList();

        if (!errors.isEmpty() || !referenceErrors.isEmpty()) {
            List<String> allErrors = new java.util.ArrayList<>(errors);
            allErrors.addAll(referenceErrors);
            log.warn("Script document validation failed errorCount={} beanValidationErrors={} referenceErrors={}",
                    allErrors.size(), errors.size(), referenceErrors.size());
            throw new ScriptValidationException(allErrors);
        }
        log.debug("Script document validation passed schemaVersion={} sceneCount={}",
                document.schemaVersion(), safeSize(document.scenes()));
    }

    /**
     * 将 Bean Validation 的 violation 转为前端可读的错误文本。
     *
     * @param violation 单条字段校验错误。
     * @return 可展示错误文本。
     */
    private String formatViolation(ConstraintViolation<ScriptDocument> violation) {
        return violation.getPropertyPath() + " " + violation.getMessage();
    }

    /**
     * 安全读取列表大小，避免校验日志因为空列表引用抛出异常。
     *
     * @param values 待统计列表。
     * @return 列表大小；列表为空引用时返回 -1。
     */
    private static int safeSize(List<?> values) {
        return values == null ? -1 : values.size();
    }
}
