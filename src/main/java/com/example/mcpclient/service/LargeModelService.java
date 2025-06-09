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

    // ENSURED buildSystemMessageWithTools IS PRESENT AND HAS MULTI-STEP GUIDANCE
    public String buildSystemMessageWithTools() {
        Map<String, McpServerDetailsDto> tools = getAvailableMcpTools();
        if (tools == null || tools.isEmpty()) {
            return "You are a helpful assistant. Please respond directly to the user's query.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful assistant. You have access to the following tools (local MCP server commands). ");
        sb.append("If you determine that using one of these tools is the best way to respond to the user's request, please respond ONLY with a single JSON object matching this exact structure: ");
        sb.append("{\"tool_to_use\": \"<tool_name>\", \"parameters\": {\"<param_name_1>\": \"<param_value_1>\", \"<param_name_2>\": \"<param_value_2>\", ...}, \"text_response\": \"<optional_text_for_user_summarizing_action_or_result>\"}. ");
        sb.append("The \"parameters\" object should only contain parameters relevant to the chosen tool. ");
        sb.append("If you do not need to use a tool, or if no tool is suitable for the user's request, respond ONLY with a single JSON object: {\"text_response\": \"<your_direct_answer_to_the_user>\"}. ");
        sb.append("Ensure your entire response is a single, valid JSON object and nothing else. Do not add any text before or after the JSON object. ");
        sb.append("If the task requires multiple steps or commands, call the 'cmd' tool for the first command. You will receive its output and can then decide on subsequent actions or provide a final answer. \\n"); // Multi-step guidance
        sb.append("如果需要修改，创建文件，直接使用系统命令操作，不要打开任何软件让我自己粘贴。如果需要修改文件，先获取目标文件全文，然后生成调整后的最终内容，最后覆盖式写入目标文件 \\n"); // Multi-step guidance
        sb.append("Available tools:\\n");

        tools.forEach((name, config) -> {
            sb.append("- Tool Name: `").append(name).append("`\\n");
            sb.append("  Description: ").append(config.getDescription()).append("\\n");
            if (config.getArgsTemplate() != null && !config.getArgsTemplate().isEmpty()) {
                String params = config.getArgsTemplate().stream()
                    .map(arg -> arg.replaceAll("[\\{\\}]", "")) // Corrected regex for { and }
                    .filter(arg -> !arg.contains(" ") && !arg.isEmpty())
                    .collect(Collectors.joining(", "));
                if (!params.isEmpty()) {
                    sb.append("  Parameters to provide: `").append(params).append("`\\n");
                }
            }
            sb.append("\\n");
        });
        return sb.toString();
    }

    // ENSURED buildFollowUpSystemMessage IS PRESENT AND CORRECT
    public String buildFollowUpSystemMessage(String originalUserQuery, String executedCommandName, Map<String, String> executedCommandParams, String commandOutput) {
        Map<String, McpServerDetailsDto> tools = getAvailableMcpTools();
        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful assistant. You previously executed a command based on the user's original query.\\n");
        sb.append("Original User Query: '").append(originalUserQuery).append("'\\n");
        sb.append("Executed Command: '").append(executedCommandName).append("' with parameters: ").append(executedCommandParams.toString()).append("'\\n");
        sb.append("Command Output:\\n").append(commandOutput).append("'\\n\\n");
        boolean commandFailed = false;
        if (commandOutput != null &&
            (commandOutput.startsWith("Error executing") ||
             commandOutput.contains("(exit code ") ||
             commandOutput.toLowerCase().contains("is not recognized as an internal or external command") ||
             commandOutput.toLowerCase().contains("cannot find the path specified") ||
             commandOutput.toLowerCase().contains("access is denied"))) {
            commandFailed = true;
            logger.info("Command execution detected as failed. Modifying LLM prompt for suggestions.");
        }

        if (commandFailed) {
            sb.append("The previous command failed or produced an error (see 'Command Output' above). ");
            sb.append("Please analyze the error message and suggest how to fix the command, or propose an alternative command to achieve the user's original goal. ");
            sb.append("You can then request a corrected 'cmd' tool call with the fixed command (using 'user_command' parameter), or if you have a different suggestion, provide it as a textual response using the 'text_response' field only.\\n");
        } else {
            sb.append("Based on this output and the original query, you can either request another command using the 'cmd' tool or provide a final answer to the user.\\n");
        }
        sb.append("If you need to use the 'cmd' tool again, respond ONLY with a single JSON object matching this exact structure: \\n");
        sb.append("{\"tool_to_use\": \"cmd\", \"parameters\": {\"user_command\": \"<full_windows_command>\"}, \"text_response\": \"<optional_summary>\"}. \\n");
        sb.append("If you want to provide a final answer, respond ONLY with a single JSON object: {\"text_response\": \"<your_direct_answer_to_the_user>\"}. \\n");
        sb.append("Ensure your entire response is a single, valid JSON object and nothing else.\\n");
        sb.append("自动完成所有需要做的任务，如果需要修改，创建文件，直接使用系统命令操作，不要打开任何软件让我自己粘贴。如果需要修改文件，先获取目标文件全文，然后生成调整后的最终内容，最后覆盖式写入目标文件 \\n"); // Multi-step guidance

        if (tools == null || tools.isEmpty()) {
            sb.append("No tools are currently available.\\n");
        } else {
            sb.append("Available tools (primarily 'cmd'):\\n");
            tools.forEach((name, config) -> {
                sb.append("- Tool Name: `").append(name).append("`\\n");
                sb.append("  Description: ").append(config.getDescription()).append("\\n");
                if (config.getArgsTemplate() != null && !config.getArgsTemplate().isEmpty()) {
                    String params = config.getArgsTemplate().stream()
                        .map(arg -> arg.replaceAll("[\\{\\}]", "")) // Corrected regex
                        .filter(arg -> !arg.contains(" ") && !arg.isEmpty())
                        .collect(Collectors.joining(", "));
                    if (!params.isEmpty()) {
                        sb.append("  Parameters to provide: `").append(params).append("`\\n");
                    }
                }
                sb.append("\\n");
            });
        }
        return sb.toString();
    }
}
