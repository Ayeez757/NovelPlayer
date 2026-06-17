package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 阶段化生成器的 JSON 处理工具类。
 *
 * 提供统一的 JSON 读取、验证、规范化以及格式化输出能力，
 * 用于在各阶段生成器中预处理大语言模型的输入输出数据。
 *
 * <p>该类为纯工具类，所有方法均为静态方法，不允许实例化。</p>
 */
final class StageJsonSupport {

    private StageJsonSupport() {
    }

    /**
     * 将 JSON 字符串解析并验证为 ObjectNode。
     *
     * <p>该方法确保输入 JSON 是一个有效的 JSON 对象（而非数组或其他类型），
     * 并返回可操作的 ObjectNode 实例。</p>
     *
     * @param objectMapper JSON 序列化/反序列化工具。
     * @param stageName 阶段名称，用于错误信息定位。
     * @param json 待解析的 JSON 字符串。
     * @return 解析后的 ObjectNode。
     * @throws IllegalStateException 当 JSON 格式无效或根节点不是对象时抛出。
     */
    static ObjectNode readObject(ObjectMapper objectMapper, String stageName, String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node instanceof ObjectNode objectNode) {
                return objectNode;
            }
            throw new IllegalStateException("JSON 根节点必须是对象：阶段名称 " + stageName);
        } catch (Exception exception) {
            throw new IllegalStateException("阶段 " + stageName + " 的 JSON 格式无效", exception);
        }
    }

    /**
     * 将 ObjectNode 转换为指定的目标类型。
     *
     * @param objectMapper JSON 序列化/反序列化工具。
     * @param stageName 阶段名称，用于错误信息定位。
     * @param root 已规范化的 ObjectNode。
     * @param type 目标类型。
     * @return 转换后的目标类型实例。
     * @param <T> 目标类型泛型。
     * @throws IllegalStateException 当类型转换失败时抛出。
     */
    static <T> T treeToValue(ObjectMapper objectMapper, String stageName, ObjectNode root, Class<T> type) {
        try {
            return objectMapper.treeToValue(root, type);
        } catch (Exception exception) {
            throw new IllegalStateException("阶段 " + stageName + " 的规范化 JSON 转换失败", exception);
        }
    }

    /**
     * 将任意对象格式化为带缩进的 JSON 字符串。
     *
     * <p>主要用于生成可读性更好的提示词输入内容，便于调试和日志记录。</p>
     *
     * @param objectMapper JSON 序列化/反序列化工具。
     * @param value 待序列化的对象。
     * @return 带缩进格式的 JSON 字符串。
     * @throws IllegalStateException 当序列化失败时抛出。
     */
    static String toPrettyJson(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("阶段化提示词输入序列化失败", exception);
        }
    }

    /**
     * 确保对象节点中存在指定名称的数组字段。
     *
     * <p>如果字段不存在或不是数组类型，则创建新的空数组替换原字段。</p>
     *
     * @param objectMapper JSON 序列化/反序列化工具。
     * @param node 目标对象节点。
     * @param fieldName 字段名称。
     * @return 保证存在的 ArrayNode。
     */
    static ArrayNode ensureArray(ObjectMapper objectMapper, ObjectNode node, String fieldName) {
        JsonNode current = node.get(fieldName);
        if (current instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        ArrayNode replacement = objectMapper.createArrayNode();
        node.set(fieldName, replacement);
        return replacement;
    }

    /**
     * 如果指定字段为空或空白，则设置默认值。
     *
     * @param node 目标对象节点。
     * @param fieldName 字段名称。
     * @param fallback 默认值。
     */
    static void putIfBlank(ObjectNode node, String fieldName, String fallback) {
        JsonNode current = node.get(fieldName);
        if (current == null || current.isNull() || isBlank(current.asText())) {
            node.put(fieldName, fallback);
        }
    }

    /**
     * 将数组节点中的所有元素规范化为字符串。
     *
     * 该方法会遍历数组中的每个元素：
     * 空值或 null 替换为默认占位文本
     *  对象类型尝试提取 text/summary/description/content/name/label 字段作为字符串
     *   其他类型直接调用 asText() 转换
     *
     *
     * @param objectMapper JSON 序列化/反序列化工具。
     * @param arrayNode 待规范化的数组节点。
     * @param fallbackLabel 备用标签，用于生成占位文本。
     */
    static void normalizeStringArray(ObjectMapper objectMapper, ArrayNode arrayNode, String fallbackLabel) {
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode item = arrayNode.get(i);
            if (item == null || item.isNull()) {
                arrayNode.set(i, objectMapper.getNodeFactory().textNode("保留一条" + fallbackLabel + "。"));
                continue;
            }
            if (item.isTextual()) {
                continue;
            }
            if (item.isObject()) {
                String extracted = firstNonBlank(
                        item.path("text").asText(null),
                        item.path("summary").asText(null),
                        item.path("description").asText(null),
                        item.path("content").asText(null),
                        item.path("name").asText(null),
                        item.path("label").asText(null)
                );
                arrayNode.set(i, objectMapper.getNodeFactory().textNode(
                        extracted != null ? extracted : "保留一条" + fallbackLabel + "。"
                ));
                continue;
            }
            arrayNode.set(i, objectMapper.getNodeFactory().textNode(item.asText()));
        }
    }

    /**
     * 将文本内容压缩到指定长度，超出部分用省略号替代。
     *
     * @param value 原始文本。
     * @param maxLength 最大允许长度。
     * @return 压缩后的摘要文本。
     */
    static String summarize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "保留当前章节最强的一处戏剧冲突。";
        }
        String normalized = value.strip();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    /**
     * 判断字符串是否为 null 或仅包含空白字符。
     *
     * @param value 待检查的字符串。
     * @return 如果字符串为 null 或空白则返回 true，否则返回 false。
     */
    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 返回多个候选值中第一个非空且非空白字符串。
     *
     * @param values 候选字符串数组。
     * @return 第一个有效的字符串，如果所有候选都无效则返回 null。
     */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }
}