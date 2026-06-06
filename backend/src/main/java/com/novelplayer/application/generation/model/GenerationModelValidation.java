package com.novelplayer.application.generation.model;

import java.util.List;
import java.util.Objects;

/**
 * 生成中间模型的构造期校验工具。
 *
 * <p>这些模型会承载模型输出和后端组装逻辑之间的中间素材，因此在创建时就做轻量规范化，
 * 可以让后续阶段少处理空字符串、可变列表和空关键字段。</p>
 */
final class GenerationModelValidation {

    /**
     * 工具类不允许实例化。
     */
    private GenerationModelValidation() {
    }

    /**
     * 校验正整数。
     *
     * @param value 待校验数值。
     * @param fieldName 字段名称。
     * @return 原始数值。
     */
    static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    /**
     * 校验必填文本，并去除首尾空白。
     *
     * @param value 待校验文本。
     * @param fieldName 字段名称。
     * @return 规范化后的文本。
     */
    static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    /**
     * 规范化可选文本。
     *
     * @param value 待规范化文本。
     * @return 去除首尾空白后的文本；空白值返回 null。
     */
    static String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 复制可为空的对象列表，并确保其中没有 null 元素。
     *
     * @param values 待复制列表，可为空。
     * @param fieldName 字段名称。
     * @return 不可变列表；原列表为空时返回空列表。
     * @param <T> 列表元素类型。
     */
    static <T> List<T> copyList(List<T> values, String fieldName) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(values.stream()
                .map(value -> Objects.requireNonNull(value, fieldName + " must not contain null"))
                .toList());
    }

    /**
     * 复制非空对象列表，并确保其中没有 null 元素。
     *
     * @param values 待复制列表。
     * @param fieldName 字段名称。
     * @return 不可变列表。
     * @param <T> 列表元素类型。
     */
    static <T> List<T> requireList(List<T> values, String fieldName) {
        List<T> copied = copyList(values, fieldName);
        if (copied.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        return copied;
    }

    /**
     * 复制字符串列表，去除每个元素的首尾空白，并过滤空白元素。
     *
     * @param values 待复制列表，可为空。
     * @return 不可变字符串列表。
     */
    static List<String> copyTextList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    /**
     * 复制非空字符串列表，去除空白元素后必须至少保留一项。
     *
     * @param values 待复制列表。
     * @param fieldName 字段名称。
     * @return 不可变字符串列表。
     */
    static List<String> requireTextList(List<String> values, String fieldName) {
        List<String> copied = copyTextList(values);
        if (copied.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        return copied;
    }

    /**
     * 复制非空正整数列表，并确保其中没有 null 或非正数。
     *
     * @param values 待复制列表。
     * @param fieldName 字段名称。
     * @return 不可变正整数列表。
     */
    static List<Integer> requirePositiveIntegerList(List<Integer> values, String fieldName) {
        List<Integer> copied = requireList(values, fieldName);
        copied.forEach(value -> requirePositive(value, fieldName));
        return copied;
    }
}
