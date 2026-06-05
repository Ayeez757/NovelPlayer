package com.novelplayer.application.script;

import com.novelplayer.domain.script.ScriptDocument;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
/**
 * 执行基础字段校验以及注解无法表达的跨引用校验，
 * 例如场景人物编号、地点编号、对白 speaker_id 是否真实存在。
 */
public class ScriptSchemaValidator {

    private final Validator validator;

    public ScriptSchemaValidator(Validator validator) {
        this.validator = validator;
    }

    public void validate(ScriptDocument document) {
        List<String> errors = validator.validate(document).stream()
                .map(this::formatViolation)
                .sorted()
                .toList();

        // 先构建编号集合，后续场景级引用校验会更简单也更快。
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
            throw new ScriptValidationException(allErrors);
        }
    }

    private String formatViolation(ConstraintViolation<ScriptDocument> violation) {
        return violation.getPropertyPath() + " " + violation.getMessage();
    }
}
