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
        String systemMessageContent = buildSystemMessageWithTools(); // Call the correctly named method
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

    /**
     * 构建给LLM的初始系统消息。
     * 此消息主要使用中文，指导LLM如何构建其响应（工具调用或文本响应的JSON格式），
     * 列出可用工具，并提供多步骤任务的处理指南。
     * 强调迭代操作：LLM应持续（通过'cmd'工具）调用命令，直到用户的完整请求得到解决。
     * 同时包含文件操作（例如，先获取全部内容，然后覆盖写入）的具体说明。
     * 新增指导原则：LLM被指示要自主行动，不向用户提问，并尝试使用命令解决信息缺失的问题。
     */
    public String buildSystemMessageWithTools() {
        Map<String, McpServerDetailsDto> tools = getAvailableMcpTools();
        if (tools == null || tools.isEmpty()) {
            return "你是一个有帮助的助手。请直接用中文回答用户的问题。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个有能力的AI助手。你的核心任务是理解用户的请求并自主完成所有相关工作。\n");
        sb.append("你拥有以下工具（本地MCP服务器命令）的使用权限。请严格遵守以下行动指南：\n\n");

        sb.append("行动指南：\n");
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

        sb.append("可用工具列表：\n");
        tools.forEach((name, config) -> {
            sb.append("工具名称: `").append(name).append("`\n");
            // Tool description will be handled in a later step (Plan Step 3) to ensure it's also in Chinese.
            // For now, we use the existing description but clearly label it.
            sb.append("  描述: ").append(config.getDescription()); // Existing description
            if (name.equals("cmd") && config.getWorkingDirectory() != null && !config.getWorkingDirectory().isEmpty()) {
                sb.append(" (注意: 此命令将在工作目录 '")
                  .append(config.getWorkingDirectory())
                  .append("' 中执行。对其他位置请使用绝对路径，或基于此目录使用相对路径。)");
            }
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
        return sb.toString();
    }

    /**
     * 构建在工具执行后给LLM的后续系统消息。
     * 此消息主要使用中文，为LLM提供原始用户查询、刚执行的命令及其参数和输出的上下文。
     * 它指导LLM：
     * 1. 分析输出：如果命令失败，研判错误信息，并自主决定是修正命令、尝试不同命令，还是判断任务无法完成。无需向用户征询。
     * 2. 决定后续步骤：如果任务仍在进行中，应继续自主调用所需工具（通常是'cmd'）。
     * 3. 任务完成：如果基于当前输出判断任务已完全解决，则提供最终的中文文本总结。
     * 此消息重申了可用工具、JSON响应格式，以及文件操作和自主行动的指导方针。
     * 强调LLM不应提问，而是应自行使用命令解决信息缺失问题。
     */
    public String buildFollowUpSystemMessage(String originalUserQuery, String executedCommandName, Map<String, String> executedCommandParams, String commandOutput) {
        Map<String, McpServerDetailsDto> tools = getAvailableMcpTools();
        StringBuilder sb = new StringBuilder();

        sb.append("你是一个有能力的AI助手。你先前基于用户的原始请求执行了一个命令。现在你需要分析结果并决定下一步行动。\n");
        sb.append("记住核心行动指南：自主决策，不提问，主动使用命令解决信息障碍。\n\n");

        sb.append("上下文回顾：\n");
        sb.append("  - 原始用户请求: '").append(originalUserQuery).append("'\n");
        sb.append("  - 已执行的命令: '").append(executedCommandName).append("' 使用参数: ").append(executedCommandParams.toString()).append("'\n");
        sb.append("  - 命令输出:\n").append(commandOutput).append("\n\n");

        boolean commandFailed = false;
        if (commandOutput != null &&
            (commandOutput.startsWith("Error executing") || // Keeping English keywords for error detection from McpService
             commandOutput.contains("(exit code ") ||
             commandOutput.toLowerCase().contains("is not recognized as an internal or external command") ||
             commandOutput.toLowerCase().contains("cannot find the path specified") ||
             commandOutput.toLowerCase().contains("access is denied"))) {
            commandFailed = true;
            logger.info("Command execution detected as failed. Modifying LLM prompt for autonomous error handling.");
        }

        sb.append("下一步行动指示：\n");
        if (commandFailed) {
            sb.append("1. 分析失败：上述命令执行失败或产生了错误（详情见“命令输出”）。请仔细分析错误信息。\n");
            sb.append("2. 自主修正：基于你的分析，你必须自主决定如何修正。选项包括：\n");
            sb.append("   a. 修正当前命令：如果你认为可以修正参数或命令本身来解决问题，请使用 'cmd' 工具调用修正后的命令。\n");
            sb.append("   b. 尝试替代命令：如果原命令无法修复，但有其他命令或方法能达成用户目标，请使用 'cmd' 工具调用新的命令。\n");
            sb.append("   c. 无法解决：如果你判断该错误无法通过你拥有的工具和知识解决，或者用户目标无法达成，则提供一个说明情况的最终文本答复。\n");
            sb.append("   请勿就如何修复或下一步操作询问用户。\n");
        } else {
            sb.append("1. 分析成功输出：上述命令已成功执行。请分析其输出。\n");
            sb.append("2. 决定后续：基于命令输出和原始用户请求，自主判断任务是否已完全解决。\n");
            sb.append("   a. 若需更多步骤：如果认为还需执行其他命令才能完整实现用户目标（例如，文件操作的后续步骤，或一个复杂任务的下一个环节），请继续使用 'cmd' 工具调用下一个必要的命令。若缺少信息，尝试用 'cmd' 命令获取。\n");
            sb.append("   b. 若任务完成：如果命令输出表明用户请求已圆满完成，或者你已获取足够信息来总结成果，则提供一个最终的中文文本答复。\n");
            sb.append("   请勿询问用户是否需要更多步骤或任务是否完成。\n");
        }

        sb.append("\n响应格式提醒：\n");
        sb.append("- 若需使用'cmd'工具：你的回复必须是此JSON格式: {\"tool_to_use\": \"cmd\", \"parameters\": {\"user_command\": \"<完整系统命令>\"}, \"text_response\": \"<可选的中文总结>\"}\n");
        sb.append("- 若提供最终答案：你的回复必须是此JSON格式: {\"text_response\": \"<给用户的最终中文总结性答复>\"}\n");
        sb.append("- 确保回复是单一、结构正确的JSON对象，不含任何额外字符。\n");

        sb.append("\n文件操作特别指南（重申）：\n");
        sb.append("修改文件请遵循“先读、再改、后写”的原则，所有操作通过'cmd'命令完成，确保换行符正确处理。\n");
        sb.append("自动完成所有需要做的任务，如果需要修改，创建文件，直接使用系统命令操作，不要打开任何软件让我自己粘贴。如果需要修改文件，先获取目标文件全文，然后生成调整后的最终内容，最后覆盖式写入目标文件。\n");


        if (tools == null || tools.isEmpty()) {
            sb.append("\n当前无可用工具。\n");
        } else {
            sb.append("\n可用工具列表（主要使用 'cmd'）：\n");
            tools.forEach((name, config) -> {
                sb.append("工具名称: `").append(name).append("`\n");
                sb.append("  描述: ").append(config.getDescription());
                 if (name.equals("cmd") && config.getWorkingDirectory() != null && !config.getWorkingDirectory().isEmpty()) {
                    sb.append(" (注意: 此命令将在工作目录 '")
                      .append(config.getWorkingDirectory())
                      .append("' 中执行。对其他位置请使用绝对路径，或基于此目录使用相对路径。)");
                }
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
