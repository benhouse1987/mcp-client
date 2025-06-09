package com.example.mcpclient.service;

import com.example.mcpclient.dto.llm.LlmToolCallDto;
import com.example.mcpclient.dto.mcp.McpServerDetailsDto;
import com.example.mcpclient.dto.openai.OpenAiChatMessage;
import com.example.mcpclient.dto.openai.OpenAiChatRequest;
import com.example.mcpclient.dto.openai.OpenAiChatResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LargeModelService {

    private static final Logger logger = LoggerFactory.getLogger(LargeModelService.class);

    @Value("${large.model.url}")
    private String modelUrl;

    @Value("${large.model.name}")
    private String modelName;

    @Value("${large.model.key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final McpService mcpService;

    public LargeModelService(RestTemplate restTemplate, ObjectMapper objectMapper, McpService mcpService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.mcpService = mcpService;
    }

    public String getModelName() { // ENSURED GETTER IS PRESENT
        return modelName;
    }

    public Map<String, McpServerDetailsDto> getAvailableMcpTools() {
        if (mcpService == null) {
            logger.warn("McpService is null in LargeModelService.getAvailableMcpTools()");
            return Collections.emptyMap();
        }
        Map<String, McpServerDetailsDto> tools = mcpService.getMcpServerConfigurations();
        if (tools == null) {
             logger.warn("McpService.getMcpServerConfigurations() returned null. Defaulting to empty map of tools.");
             return Collections.emptyMap();
        }
        return tools;
    }

    // ENSURED processText IS CORRECT AND CALLS processOpenAiRequest
    public LlmToolCallDto processText(String inputText) {
        logger.info("Processing input with LLM ({}) for initial call: '{}'", modelName, inputText);
        // For the initial call, the command history is empty.
        String systemMessageContent = buildUnifiedSystemMessage(inputText, Collections.emptyList());
        List<OpenAiChatMessage> messages = new ArrayList<>();
        messages.add(new OpenAiChatMessage("system", systemMessageContent));
        messages.add(new OpenAiChatMessage("user", inputText));
        OpenAiChatRequest chatRequest = new OpenAiChatRequest(modelName, messages);
        return processOpenAiRequest(chatRequest);
    }

    // ENSURED processOpenAiRequest IS PRESENT AND CORRECT
    public LlmToolCallDto processOpenAiRequest(OpenAiChatRequest chatRequest) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        try {
            String requestBody = objectMapper.writeValueAsString(chatRequest);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            logger.info("OpenAI Request Body: {}", requestBody);

            ResponseEntity<OpenAiChatResponse> responseEntity = restTemplate.postForEntity(
                    modelUrl, entity, OpenAiChatResponse.class);

            OpenAiChatResponse chatResponse = responseEntity.getBody();

            if (chatResponse != null && chatResponse.getChoices() != null && !chatResponse.getChoices().isEmpty()) {
                String llmResponseContent = chatResponse.getChoices().get(0).getMessage().getContent();
                // Strip markdown fences if present
                if (llmResponseContent != null && llmResponseContent.startsWith("```json")) {
                    llmResponseContent = llmResponseContent.substring(7);
                }
                if (llmResponseContent != null && llmResponseContent.endsWith("```")) {
                    llmResponseContent = llmResponseContent.substring(0, llmResponseContent.length() - 3);
                }
                if (llmResponseContent != null) {
                    llmResponseContent = llmResponseContent.trim();
                }
                logger.info("OpenAI Request Raw response from LLM (after stripping): {}", llmResponseContent);

                try {
                    LlmToolCallDto toolCall = objectMapper.readValue(llmResponseContent, LlmToolCallDto.class);
                    if (toolCall.getToolToUse() != null || toolCall.getTextResponse() != null) {
                        return toolCall;
                    }
                    logger.warn("LLM response parsed as JSON but not a valid tool call or text_response structure. Content: {}", llmResponseContent);
                    LlmToolCallDto ambiguousJsonResponse = new LlmToolCallDto();
                    ambiguousJsonResponse.setTextResponse(llmResponseContent);
                    return ambiguousJsonResponse;
                } catch (JsonProcessingException e) {
                    logger.warn("Could not parse LLM response as JSON tool call: {}. Treating as plain text.", e.getMessage());
                    LlmToolCallDto textOnlyResponse = new LlmToolCallDto();
                    textOnlyResponse.setTextResponse(llmResponseContent);
                    return textOnlyResponse;
                }
            }
            logger.warn("No response choices received from LLM or response was empty.");
            LlmToolCallDto errorResponse = new LlmToolCallDto();
            errorResponse.setTextResponse("Error: No response from model or response was empty.");
            return errorResponse;

        } catch (HttpClientErrorException e) {
            logger.error("HttpClientErrorException calling OpenAI: {} - {}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            LlmToolCallDto errorResponse = new LlmToolCallDto();
            errorResponse.setTextResponse("Error from LLM API: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
            return errorResponse;
        } catch (JsonProcessingException e) {
            logger.error("Error serializing request for OpenAI: {}", e.getMessage(), e);
            LlmToolCallDto errorResponse = new LlmToolCallDto();
            errorResponse.setTextResponse("Error preparing request for LLM: " + e.getMessage());
            return errorResponse;
        } catch (Exception e) {
            logger.error("Error processing text with Large Model: {}", e.getMessage(), e);
            LlmToolCallDto errorResponse = new LlmToolCallDto();
            errorResponse.setTextResponse("Error: Could not connect to Large Model or process its response. " + e.getMessage());
            return errorResponse;
        }
    }

    // Placeholder for the List<String> formattedCommandHistory structure:
    // Each string is assumed to be a pre-formatted log like:
    // "先前执行的命令: cmd with params: {user_command=...}
命令结果:
<output>"

    /**
     * 构建统一的、给LLM的系统消息。此消息将用于所有与LLM的交互轮次。
     * 此消息主要使用中文，并始终包含：
     * 1. 核心AI行动指南（自主决策、不提问、主动信息获取、JSON响应格式、文件操作规则等）。
     * 2. 用户的原始请求。
     * 3. 到目前为止已执行的命令及其结果的列表（首次交互时此列表为空）。
     * 4. 直接指令：要求LLM基于以上所有信息，提供下一个要执行的命令或最终总结。
     * 5. 可用工具列表。
     * LLM被期望根据这些信息自主决定下一步。
     */
    public String buildUnifiedSystemMessage(String originalUserQuery, List<String> formattedCommandHistory) {
        Map<String, McpServerDetailsDto> tools = getAvailableMcpTools();
        StringBuilder sb = new StringBuilder();

        // 1. Core AI Guidelines
        sb.append("你是一个有能力的AI助手。你的核心任务是理解用户的请求并自主完成所有相关工作。\n");
        sb.append("你拥有以下工具（本地MCP服务器命令）的使用权限。请严格遵守以下行动指南：\n\n");

        sb.append("== 核心行动指南 ==\n");
        sb.append("1. 自主决策与执行：深入理解用户目标，独立判断并执行所有必要步骤。请勿向用户提出澄清性问题或寻求操作确认。你的目标是直接完成任务。\n");
        sb.append("2. 主动解决信息障碍：若执行任务所需信息不明确或缺失（例如，不确定的文件名、路径或参数），你必须首先尝试使用你拥有的工具（尤其是 'cmd' 命令）进行探查和信息获取。例如，使用 'cmd' 列出目录内容、读取文件内容等，以获取决策所需信息。\n");
        sb.append("3. 迭代式命令执行：对于复杂任务，通常需要多步骤的命令执行。你将按顺序迭代执行。首先，调用 'cmd' 工具执行第一个必要的系统命令。系统将返回该命令的输出。你需仔细分析此输出，并基于分析结果决定下一步行动：是执行更多'cmd'命令，还是任务已完成并可提供最终答复。如此循环，直至用户请求的所有操作均已完成。\n");
        sb.append("4. JSON响应格式（使用工具时）：当你判断需要使用工具时，你的回复必须严格遵循以下JSON格式，且仅包含此JSON对象：\n");
        sb.append("   {\"tool_to_use\": \"<工具名称>\", \"parameters\": {\"<参数名1>\": \"<参数值1>\", \"<参数名2>\": \"<参数值2>\", ...}, \"text_response\": \"<对此步操作或结果的可选中文总结>\"}\n");
        sb.append("   注意：'parameters' 对象中只应包含所选工具实际需要的参数。'text_response' 为可选，用于简要说明。\n");
        sb.append("5. JSON响应格式（直接回答时）：当你判断无需使用工具、没有合适工具，或所有指令已执行完毕，任务已圆满完成时，你的回复必须严格遵循以下JSON格式，且仅包含此JSON对象：\n");
        sb.append("   {\"text_response\": \"<给用户的最终中文总结性答复>\"}\n");
        sb.append("6. 响应纯净性：你的任何回复都必须是一个单独且结构完全正确的JSON对象。JSON对象前后不应包含任何说明、注释或其他非JSON字符。\n");
        sb.append("7. 文件操作特别指南：\n");
        sb.append("   a. 所有文件的创建、修改、读取等操作，都必须通过 'cmd' 工具调用相应的系统命令来完成。\n");
        sb.append("   b. 修改文件时，严谨的工作流程是：首先，使用命令读取目标文件的当前全部内容；然后，基于读取的内容和你需要做的修改，在内部生成全新的、完整的最终文件内容；最后，使用命令将此最终内容覆盖式写入原文件。\n");
        sb.append("   c. 写入文件内容时，必须确保文本中的换行符被正确处理为实际的换行效果，而不是写入 '\\n' 这样的转义字符。\n\n");


        // 2. Original User Request
        sb.append("== 用户的原始请求 ==\n");
        sb.append(originalUserQuery).append("\n\n");

        // 3. Executed Command History
        sb.append("== 已执行的命令历史 ==\n");
        if (formattedCommandHistory == null || formattedCommandHistory.isEmpty()) {
            sb.append("尚未执行任何命令。\n");
        } else {
            for (String historyItem : formattedCommandHistory) {
                // Indent multi-line history items (e.g., command outputs) for better readability in the prompt
                sb.append("- ").append(historyItem.replace("\n", "\n  ")).append("\n");
            }
        }
        sb.append("\n");

        // 4. Action Instruction
        sb.append("== 行动指示 ==\n");
        sb.append("基于以上核心行动指南、用户的原始请求以及已执行的命令历史（包括其成功或失败的结果）：\n");
        sb.append("1. 如果还需要执行更多命令来完成用户的原始请求，请提供下一个确切的系统命令。\n");
        sb.append("2. 如果用户的原始请求已完全达成，或者你判断无法通过命令进一步完成，请提供一个最终的中文总结性答复。\n");
        sb.append("请直接行动，不要提出问题。\n\n");

        // 5. Response Format Reminder (briefly)
        sb.append("请再次确认，你的回复必须严格按照上述JSON格式之一（使用工具或直接回答），且仅包含该JSON对象。\n\n");

        // Available Tools
        if (tools == null || tools.isEmpty()) {
            sb.append("当前无可用工具（这是系统配置问题）。\n");
        } else {
            sb.append("== 可用工具列表 ==\n");
            tools.forEach((name, config) -> {
                sb.append("工具名称: `").append(name).append("`\n");
                sb.append("  描述: ").append(config.getDescription()); // Assumes description is already in Chinese from mcp_servers.json
                sb.append("\n");
                if (config.getArgsTemplate() != null && !config.getArgsTemplate().isEmpty()) {
                    String params = config.getArgsTemplate().stream()
                        .map(arg -> arg.replaceAll("[\\{\\}]", ""))
                        .filter(arg -> !arg.contains(" ") && !arg.isEmpty())
                        .collect(Collectors.joining(", "));
                    if (!params.isEmpty()) {
                        sb.append("  需要提供的参数: `").append(params).append("`\n");
                    }
                }
                sb.append("\n");
            });
        }
        return sb.toString();
    }
}
