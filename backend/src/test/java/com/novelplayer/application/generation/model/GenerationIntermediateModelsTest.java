package com.novelplayer.application.generation.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖生成中间模型的构造期规范化和基础校验。
 */
class GenerationIntermediateModelsTest {

    @Test
    void chapterDigestNormalizesTextAndCopiesLists() {
        List<String> events = new ArrayList<>(List.of("  发现信件  ", " ", "产生冲突"));

        ChapterDigest digest = new ChapterDigest(
                1,
                "  雨夜  ",
                "  林安进入旧书店。  ",
                events,
                List.of(new CharacterMention(" 林安 ", List.of(" 她 ", " "), " protagonist ", " 找到父亲 ")),
                List.of(new LocationMention(" 旧书店 ", " interior ", " 昏暗狭窄 ")),
                List.of("  店主回避问题  "),
                List.of("  父亲为何失踪  "),
                List.of("  把信件作为场景高潮  ")
        );
        events.add("后续修改不应影响模型");

        assertThat(digest.title()).isEqualTo("雨夜");
        assertThat(digest.summary()).isEqualTo("林安进入旧书店。");
        assertThat(digest.majorEvents()).containsExactly("发现信件", "产生冲突");
        assertThat(digest.characters()).singleElement()
                .satisfies(character -> {
                    assertThat(character.name()).isEqualTo("林安");
                    assertThat(character.aliases()).containsExactly("她");
                    assertThat(character.roleHint()).isEqualTo("protagonist");
                    assertThat(character.goalHint()).isEqualTo("找到父亲");
                });
        assertThat(digest.locations()).singleElement()
                .satisfies(location -> {
                    assertThat(location.name()).isEqualTo("旧书店");
                    assertThat(location.type()).isEqualTo("interior");
                    assertThat(location.description()).isEqualTo("昏暗狭窄");
                });
        assertThat(digest.majorEvents()).doesNotContain("后续修改不应影响模型");
        assertThatThrownBy(() -> digest.majorEvents().add("不可变"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void storyBibleRequiresCharactersLocationsAndMainPlot() {
        assertThatThrownBy(() -> new StoryBible(List.of(), List.of(sampleLocation()), "主线", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("characters");
        assertThatThrownBy(() -> new StoryBible(List.of(sampleCharacter()), List.of(), "主线", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locations");
        assertThatThrownBy(() -> new StoryBible(List.of(sampleCharacter()), List.of(sampleLocation()), " ", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mainPlot");

        StoryBible bible = new StoryBible(
                List.of(sampleCharacter()),
                List.of(sampleLocation()),
                "  林安寻找父亲失踪真相。  ",
                List.of(" 真相 ", " "),
                List.of(" 第七章前不能揭露父亲身份 ")
        );

        assertThat(bible.mainPlot()).isEqualTo("林安寻找父亲失踪真相。");
        assertThat(bible.themes()).containsExactly("真相");
        assertThat(bible.continuityRules()).containsExactly("第七章前不能揭露父亲身份");
    }

    @Test
    void scenePlanRequiresAtLeastOnePlannedScene() {
        assertThatThrownBy(() -> new ScenePlan(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scenes");

        ScenePlan plan = new ScenePlan(List.of(samplePlannedScene()));

        assertThat(plan.scenes()).singleElement()
                .satisfies(scene -> {
                    assertThat(scene.id()).isEqualTo("scene_001");
                    assertThat(scene.sourceChapters()).containsExactly(1);
                    assertThat(scene.characters()).containsExactly("char_001");
                });
        assertThatThrownBy(() -> plan.scenes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void plannedSceneAndSceneDraftValidateRequiredReferences() {
        assertThatThrownBy(() -> new PlannedScene(
                "scene_001",
                "雨夜",
                List.of(0),
                "loc_001",
                "night",
                List.of("char_001"),
                "建立悬念",
                "发现信件",
                List.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceChapters");

        assertThatThrownBy(() -> new SceneDraft(
                "scene_001",
                "雨夜",
                List.of(1),
                "loc_001",
                "night",
                List.of("char_001"),
                "建立悬念",
                "发现信件",
                List.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocks");

        SceneDraft draft = new SceneDraft(
                " scene_001 ",
                " 雨夜 ",
                List.of(1),
                " loc_001 ",
                " night ",
                List.of(" char_001 ", " "),
                " 建立悬念 ",
                " 林安发现信件。 ",
                List.of(new DraftSceneBlock(" dialogue ", " char_001 ", " 是谁留下的？ "))
        );

        assertThat(draft.id()).isEqualTo("scene_001");
        assertThat(draft.characters()).containsExactly("char_001");
        assertThat(draft.blocks()).singleElement()
                .satisfies(block -> {
                    assertThat(block.type()).isEqualTo("dialogue");
                    assertThat(block.speakerId()).isEqualTo("char_001");
                    assertThat(block.text()).isEqualTo("是谁留下的？");
                });
    }

    private static BibleCharacter sampleCharacter() {
        return new BibleCharacter(
                " char_001 ",
                " 林安 ",
                List.of(" 她 "),
                " protagonist ",
                " 寻找真相 ",
                List.of(" 克制 "),
                " 短句为主 "
        );
    }

    private static BibleLocation sampleLocation() {
        return new BibleLocation(" loc_001 ", " 旧书店 ", " interior ", " 昏暗 ");
    }

    private static PlannedScene samplePlannedScene() {
        return new PlannedScene(
                " scene_001 ",
                " 雨夜来信 ",
                List.of(1),
                " loc_001 ",
                " night ",
                List.of(" char_001 ", " "),
                " 建立悬念 ",
                " 林安发现信件。 ",
                List.of(" 推门进入旧书店 ")
        );
    }
}
