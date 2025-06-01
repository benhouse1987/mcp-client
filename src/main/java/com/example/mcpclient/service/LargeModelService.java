package com.example.mcpclient.service;

import com.example.mcpclient.dto.llm.LlmToolCallDto;
import com.example.mcpclient.dto.mcp.McpServerDetailsDto; // Assuming McpService can provide this
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
import java.util.Collections; // Added for Java 8 compatibility
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LargeModelService {

    private static final Logger logger = LoggerFactory.getLogger(LargeModelService.class);

    @Value("${large.model.url}")
    private String modelUrl; // Should be https://api.openai.com/v1/chat/completions

    @Value("${large.model.name}")
    private String modelName; // Should be gpt-4o

    @Value("${large.model.key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final McpService mcpService; // Inject McpService

    public LargeModelService(RestTemplate restTemplate, ObjectMapper objectMapper, McpService mcpService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.mcpService = mcpService; // Store injected McpService
    }

    // Expose loaded configurations from McpService (or create a method in McpService to get them)
    // This is a simplified approach for now. A dedicated DTO or interface might be better.
    public Map<String, McpServerDetailsDto> getAvailableMcpTools() {
        // Ensure mcpService and its configurations are loaded.
        // If mcpService.loadMcpConfigurations() is @PostConstruct, it should be loaded.
        // Consider if mcpServerConfigurations could be null if loading failed.
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


    public LlmToolCallDto processText(String inputText) {
            logger.info("Processing input with LLM ({}) for initial call: '{}'", modelName, inputText);
            String systemMessageContent = buildSystemMessageWithTools();
            List<OpenAiChatMessage> messages = new ArrayList<>();
            messages.add(new OpenAiChatMessage("system", systemMessageContent));
            messages.add(new OpenAiChatMessage("user", inputText));
            OpenAiChatRequest chatRequest = new OpenAiChatRequest(modelName, messages);
            // Potentially set response_format if needed, e.g., chatRequest.setResponseFormat(Map.of("type", "json_object"));
            return processOpenAiRequest(chatRequest);
    }

    private String buildSystemMessageWithTools() {
        Map<String, McpServerDetailsDto> tools = getAvailableMcpTools();
        // Added null check for tools itself, as getMcpServerConfigurations might return null if loading failed critically
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
        sb.append("Available tools:\n");

        tools.forEach((name, config) -> {
            sb.append("- Tool Name: `").append(name).append("`\n");
            sb.append("  Description: ").append(config.getDescription()).append("\n");
            if (config.getArgsTemplate() != null && !config.getArgsTemplate().isEmpty()) {
                String params = config.getArgsTemplate().stream()
                    .map(arg -> arg.replaceAll("[{}]", "")) // Extract placeholder names
                    .filter(arg -> !arg.contains(" ") && !arg.isEmpty()) // Basic filter for valid param names
                    .collect(Collectors.joining(", "));
                if (!params.isEmpty()) {
                    sb.append("  Parameters to provide: `").append(params).append("`\n");
                }
            }
            sb.append("\n");
        });
        return sb.toString();
    }
}
