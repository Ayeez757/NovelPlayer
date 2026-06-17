package com.novelplayer.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 底层 JSON 调用客户端。
 *
 * 负责承接 AI 层最底层的通用能力：根据传入的阶段名称、系统提示词和用户提示词，调用 DeepSeek 模型获取响应，并尽可能稳定地返回标准 JSON 字符串。
 *
 * 只处理与模型调用相关的通用技术细节，包括：发起对话请求并获取模型原始响应；从响应文本中提取 JSON 对象；当首次返回不是合法 JSON 时，自动进行一次重试；记录阶段名、响应长度、耗时等日志，便于排查问题。
 *
 * <p>该类不负责具体业务阶段的 Prompt 文案编排，也不负责 JSON 到业务实体的映射、
 * 阶段结果归一化或业务校验。这些职责应由上层各阶段 Service 自行处理。</p>
 *
 */
@Component
@ConditionalOnProperty(prefix = "novel-player.generation", name = "mock-ai", havingValue = "false")
public class DeepSeekLlmJsonClient implements LlmJsonClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmJsonClient.class);

    private static final int RESPONSE_PREVIEW_LIMIT = 700;

    private static final String RETRY_SYSTEM_PROMPT = """
        你正在修正一次生成失败的JSON返回结果。
        仅输出一个严格合规的JSON对象。
        所有对象键名、字符串值全部使用双引号包裹。
        禁止输出Markdown代码块、说明文字、注释、末尾逗号以及多余描述文本。
        """;

    /** Spring AI 对话客户端实例，用于调用 DeepSeek 模型 */
    private final ChatClient chatClient;

    /** Jackson JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /** DeepSeek 专用 JSON 提取解析器，负责从混合文本中提取纯 JSON */
    private final DeepSeekJsonExtractor jsonExtractor;

    /** DeepSeek 对话配置参数工厂，用于设置模型参数（温度、topP 等） */
    private final DeepSeekChatOptionsFactory chatOptionsFactory;

    /**
     * 构造 DeepSeek JSON 调用客户端（正式环境使用）
     *
     * @param chatClientBuilder   Spring AI ChatClient 构建器
     * @param objectMapper        Jackson JSON 序列化工具
     * @param chatOptionsFactory  DeepSeek 对话参数配置工厂
     */
    @Autowired
    public DeepSeekLlmJsonClient(ChatClient.Builder chatClientBuilder,
                                 ObjectMapper objectMapper,
                                 DeepSeekChatOptionsFactory chatOptionsFactory) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.jsonExtractor = new DeepSeekJsonExtractor(objectMapper);
        this.chatOptionsFactory = chatOptionsFactory;
    }

    /**
     * 仅用于单元测试的构造方法（chatClient 为 null）
     *
     * @param objectMapper Jackson JSON 序列化工具
     */
    DeepSeekLlmJsonClient(ObjectMapper objectMapper) {
        this.chatClient = null;
        this.objectMapper = objectMapper;
        this.jsonExtractor = new DeepSeekJsonExtractor(objectMapper);
        this.chatOptionsFactory = null;
    }

    /**
     * 请求 AI 返回指定阶段的 JSON 字符串（接口实现）
     *
     * 该方法是 LlmJsonClient 接口的核心实现：调用模型获取响应后，
     * 将提取到的 JSON 节点序列化为字符串返回给上层。
     *
     * @param stageName    阶段名称（用于日志标识和错误提示）
     * @param systemPrompt 系统提示词，定义 AI 的角色和行为规范
     * @param userPrompt   用户提示词，包含具体任务描述和输入数据
     * @return 模型返回的合法 JSON 字符串
     * @throws IllegalStateException 当 JSON 提取失败或序列化失败时抛出
     */
    @Override
    public String requestJson(String stageName, String systemPrompt, String userPrompt) {
        ObjectNode root = requestStageJson(stageName, systemPrompt, userPrompt);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize JSON for stage: " + stageName, exception);
        }
    }

    /**
     * 请求 AI 返回指定阶段的 JSON 节点（核心私有方法）
     *
     * @param stageName    阶段名称（用于日志标识和错误提示）
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 提取出的 JSON 对象节点
     * @throws IllegalStateException 当首次和重试都失败时抛出，会携带两次失败的原因
     */
    private ObjectNode requestStageJson(String stageName, String systemPrompt, String userPrompt) {
        // 首次请求：获取原始响应内容
        String content = requestStageContent(stageName, "initial", systemPrompt, userPrompt);

        try {
            // 尝试从响应中提取 JSON 对象
            return jsonExtractor.extractObject(content, stageName);
        } catch (Exception firstException) {
            // 首次提取失败，记录警告日志并进入重试流程
            log.warn("DeepSeek response is not parseable.该阶段返回的响应内容无法解析。 stage={} attempt=initial preview={}",
                    stageName, preview(content), firstException);
            return retryStageJson(stageName, userPrompt, firstException);
        }
    }

    /**
     * 当首次 JSON 解析失败时，发起一次重试请求
     *
     * @param stageName       阶段名称
     * @param originalPrompt  原始的用户提示词（会被嵌入到重试提示中）
     * @param firstException  首次失败时捕获的异常（会被作为 suppressed 附加到最终异常）
     * @return 重试成功后提取的 JSON 对象节点
     * @throws IllegalStateException 重试也失败时抛出，包含两次失败的详细原因
     */
    private ObjectNode retryStageJson(String stageName, String originalPrompt,
                                      Exception firstException) {
        // 构建重试提示词：告知模型上一次失败的原因，并要求重新执行任务
        String retryPrompt = """
                阶段「%s」上一次返回的结果不是合法JSON。
                重新执行原有任务，仅输出严格标准的单个JSON对象。
                要求：
                - 所有对象键名、字符串值统一使用双引号包裹
                - 禁止添加Markdown代码块标记、注释、说明文字、末尾逗号
                - JSON前后不能附带任何多余文字内容
                原始任务指令：
                %s
                """.formatted(stageName, originalPrompt);

        // 使用专用的重试系统提示词发起第二次请求
        String content = requestStageContent(stageName, "retry", RETRY_SYSTEM_PROMPT, retryPrompt);

        try {
            // 尝试从重试响应中提取 JSON 对象
            return jsonExtractor.extractObject(content, stageName);
        } catch (Exception secondException) {
            // 重试也失败，记录错误日志并抛出包含两次失败原因的异常
            log.warn("DeepSeek分段响应无法解析。stage={} attempt=retry preview={}",
                    stageName, preview(content), secondException);

            IllegalStateException failure = new IllegalStateException(
                    stageName + "阶段的DeepSeek分段响应并非合法JSON", secondException);
            failure.addSuppressed(firstException);  // 附加首次失败的原因
            throw failure;
        }
    }

    /**
     * 执行实际的模型调用，获取原始响应内容字符串
     *
     * @param stageName    阶段名称（用于日志标识）
     * @param attempt      尝试次数标识（"initial" 或 "retry"）
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型返回的原始响应字符串（可能包含 Markdown 代码块、多余说明文字等）
     */
    String requestStageContent(String stageName, String attempt, String systemPrompt,
                               String userPrompt) {
        long startedAt = System.nanoTime();  // 记录开始时间（纳秒精度）

        // 通过 ChatOptionsFactory 配置模型参数后发起调用
        String content = chatOptionsFactory.apply(chatClient.prompt()
                        .system(systemPrompt)
                        .user(userPrompt))
                .call()
                .content();

        // 计算耗时（毫秒）
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        // 记录成功接收响应的日志，包含阶段名、尝试次数、响应长度、耗时
        log.info("已接收到 DeepSeek 的分段流式响应。stage={} attempt={} responseLength={} elapsedMs={}",
                stageName, attempt, content == null ? 0 : content.length(), elapsedMs);

        return content;
    }

    /**
     * 截取响应内容预览（用于日志输出）
     *
     * @param content 原始响应内容（可能为 null）
     * @return 经过格式化和截取后的预览字符串；若 content 为 null 则返回 "<null>"
     */
    private String preview(String content) {
        if (content == null) {
            return "<null>";
        }
        // 将各种换行/空白字符归一化为空格，便于单行日志输出
        String normalized = content
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .strip();

        if (normalized.length() <= RESPONSE_PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, RESPONSE_PREVIEW_LIMIT) + "...";
    }
}