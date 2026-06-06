package com.novelplayer.application.chapter;

import com.novelplayer.domain.project.NovelChapter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在进入模型前，先把章节压缩成更聚焦的结构化上下文。
 * 更稳定地产出 characters / locations / scenes 等结构字段。
 */
@Component
public class ChapterContextExtractor {

    private static final int MAX_SUMMARY_SENTENCES = 3;
    private static final int MAX_HOOK_LENGTH = 48;
    private static final int MAX_EXCERPT_LENGTH = 420;
    private static final int MAX_GLOBAL_ITEMS = 12;
    private static final int MAX_LOCAL_ITEMS = 6;

    private static final Pattern CHARACTER_CANDIDATE = Pattern.compile(
            "(?<![A-Za-z0-9])[\\p{IsHan}]{2,4}(?=(?:说|问|答|道|想|看|听|走|来到|转身|抬头|低声|冷笑|沉默|发现|推开))"
    );

    private static final Pattern LOCATION_CANDIDATE = Pattern.compile(
            "[\\p{IsHan}]{2,10}(?:家|府|宫|殿|楼|阁|院|堂|厅|房|室|门|街|巷|桥|店|馆|寺|庙|山|谷|村|城|镇|湖|河|岛|站|车站|医院|学校|教室|书店|酒店|客栈)"
    );

    private static final List<String> CONFLICT_KEYWORDS = List.of(
            "争执", "冲突", "威胁", "怀疑", "误会", "背叛", "质问", "追杀", "失踪", "骗局",
            "秘密", "真相", "危险", "崩溃", "反击", "阻拦", "决裂", "交易", "勒索", "陷阱",
            "血迹", "命令", "拒绝", "逃跑", "追问", "暴露", "试探", "报复", "复仇", "隐瞒"
    );

    public ChapterContextBundle extract(List<NovelChapter> chapters) {
        List<ChapterContext> contexts = new ArrayList<>();
        LinkedHashSet<String> globalCharacters = new LinkedHashSet<>();
        LinkedHashSet<String> globalLocations = new LinkedHashSet<>();
        LinkedHashSet<String> globalConflicts = new LinkedHashSet<>();

        for (NovelChapter chapter : chapters) {
            ChapterContext context = extract(chapter);
            contexts.add(context);
            addAllLimited(globalCharacters, context.characterCandidates(), MAX_GLOBAL_ITEMS);
            addAllLimited(globalLocations, context.locationCandidates(), MAX_GLOBAL_ITEMS);
            addAllLimited(globalConflicts, context.conflictSignals(), MAX_GLOBAL_ITEMS);
        }

        return new ChapterContextBundle(
                List.copyOf(contexts),
                List.copyOf(globalCharacters),
                List.copyOf(globalLocations),
                List.copyOf(globalConflicts)
        );
    }

    public ChapterContext extract(NovelChapter chapter) {
        String cleaned = cleanText(chapter.getContent());
        List<String> sentences = splitSentences(cleaned);
        String summary = buildSummary(sentences);
        String openingHook = clip(firstNonBlank(sentences), MAX_HOOK_LENGTH);
        String endingHook = clip(lastNonBlank(sentences), MAX_HOOK_LENGTH);
        List<String> characters = findCandidates(cleaned, CHARACTER_CANDIDATE, MAX_LOCAL_ITEMS, this::isValidCharacterCandidate);
        List<String> locations = findCandidates(cleaned, LOCATION_CANDIDATE, MAX_LOCAL_ITEMS, this::isValidLocationCandidate);
        List<String> conflicts = findConflictSignals(cleaned);
        String excerpt = clip(cleaned, MAX_EXCERPT_LENGTH);

        return new ChapterContext(
                chapter.getChapterIndex(),
                safeText(chapter.getTitle()),
                summary,
                openingHook,
                endingHook,
                characters,
                locations,
                conflicts,
                excerpt
        );
    }

    private String cleanText(String text) {
        return safeText(text)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private List<String> splitSentences(String text) {
        if (text.isBlank()) {
            return List.of();
        }

        return text.split("(?<=[。！？!?；;]|\\n)")
                .length == 0
                ? List.of(text)
                : java.util.Arrays.stream(text.split("(?<=[。！？!?；;]|\\n)"))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .toList();
    }

    private String buildSummary(List<String> sentences) {
        if (sentences.isEmpty()) {
            return "";
        }

        List<String> picked = new ArrayList<>();
        for (int i = 0; i < sentences.size() && picked.size() < MAX_SUMMARY_SENTENCES; i++) {
            picked.add(sentences.get(i));
        }

        if (sentences.size() > MAX_SUMMARY_SENTENCES + 1) {
            String tail = sentences.get(sentences.size() - 1);
            if (!picked.contains(tail)) {
                picked.add(tail);
            }
        }

        return clip(String.join(" ", picked), 180);
    }

    private List<String> findCandidates(String text, Pattern pattern, int limit, java.util.function.Predicate<String> predicate) {
        LinkedHashSet<String> results = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && results.size() < limit) {
            String candidate = matcher.group().trim();
            if (predicate.test(candidate)) {
                results.add(candidate);
            }
        }
        return List.copyOf(results);
    }

    private List<String> findConflictSignals(String text) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String keyword : CONFLICT_KEYWORDS) {
            if (lowered.contains(keyword.toLowerCase(Locale.ROOT))) {
                signals.add(keyword);
            }
            if (signals.size() >= MAX_LOCAL_ITEMS) {
                break;
            }
        }
        return List.copyOf(signals);
    }

    private boolean isValidCharacterCandidate(String text) {
        if (text.length() < 2 || text.length() > 4) {
            return false;
        }
        if (text.contains("自己") || text.contains("时候") || text.contains("地方") || text.contains("声音")) {
            return false;
        }
        return !text.endsWith("说道") && !text.endsWith("问道");
    }

    private boolean isValidLocationCandidate(String text) {
        return text.length() >= 2 && text.length() <= 10 && !text.contains("他们");
    }

    private void addAllLimited(Set<String> target, List<String> values, int limit) {
        for (String value : values) {
            if (target.size() >= limit) {
                return;
            }
            target.add(value);
        }
    }

    private String firstNonBlank(List<String> sentences) {
        return sentences.isEmpty() ? "" : sentences.get(0);
    }

    private String lastNonBlank(List<String> sentences) {
        return sentences.isEmpty() ? "" : sentences.get(sentences.size() - 1);
    }

    private String clip(String text, int maxLength) {
        String normalized = safeText(text);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
