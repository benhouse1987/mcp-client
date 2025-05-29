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
            return Map.of();
        }
        Map<String, McpServerDetailsDto> tools = mcpService.getMcpServerConfigurations();
        if (tools == null) {
             logger.warn("McpService.getMcpServerConfigurations() returned null. Defaulting to empty map of tools.");
             return Map.of();
        }
        return tools;
    }


    public LlmToolCallDto processText(String inputText) {
        logger.info("Processing input with LLM ({}) at {}: '{}'", modelName, modelUrl, inputText);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        // Construct the system message with tool descriptions
        String systemMessageContent = buildSystemMessageWithTools();
        
        List<OpenAiChatMessage> messages = new ArrayList<>();
        messages.add(new OpenAiChatMessage("system", systemMessageContent));
        messages.add(new OpenAiChatMessage("user", inputText));
        
        OpenAiChatRequest chatRequest = new OpenAiChatRequest(modelName, messages);
        // Potentially set response_format to { "type": "json_object" } if GPT-4o supports it well with your prompt
        // chatRequest.setResponseFormat(Map.of("type", "json_object")); // Requires OpenAiChatRequest to have this field

        try {
            String requestBody = objectMapper.writeValueAsString(chatRequest);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            logger.debug("OpenAI Request Body for tool call: {}", requestBody);

            ResponseEntity<OpenAiChatResponse> responseEntity = restTemplate.postForEntity(
                    modelUrl, entity, OpenAiChatResponse.class);

            OpenAiChatResponse chatResponse = responseEntity.getBody();

            if (chatResponse != null && chatResponse.getChoices() != null && !chatResponse.getChoices().isEmpty()) {
                String llmResponseContent = chatResponse.getChoices().get(0).getMessage().getContent();
                logger.info("Raw response from LLM: {}", llmResponseContent);
                
                // Attempt to parse the LLM's response as a LlmToolCallDto
                try {
                    LlmToolCallDto toolCall = objectMapper.readValue(llmResponseContent, LlmToolCallDto.class);
                    // Check if it's a valid tool call or just a text response formatted as JSON
                    if (toolCall.getToolToUse() != null) {
                        logger.info("LLM indicated tool to use: {} with parameters: {}", toolCall.getToolToUse(), toolCall.getParameters());
                        return toolCall;
                    } else if (toolCall.getTextResponse() != null) {
                         logger.info("LLM provided a text response (parsed from JSON): {}", toolCall.getTextResponse());
                         // Fallback: return as a text response within LlmToolCallDto
                         return toolCall; // McpController will need to handle this
                    }
                    // If it parsed but doesn't fit expected structure, treat as plain text.
                     logger.warn("LLM response parsed as JSON but not a valid tool call or text_response structure. Content: {}", llmResponseContent);
                     LlmToolCallDto ambiguousJsonResponse = new LlmToolCallDto();
                     ambiguousJsonResponse.setTextResponse(llmResponseContent); // Keep the raw content
                     return ambiguousJsonResponse;
                } catch (JsonProcessingException e) {
                    logger.warn("Could not parse LLM response as JSON tool call: {}. Treating as plain text.", e.getMessage());
                    // Fallback: LLM might not have returned JSON, or not the expected JSON.
                    // Return a LlmToolCallDto with only the text_response field populated.
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
        sb.append("Ensure your entire response is a single, valid JSON object and nothing else. Do not add any text before or after the JSON object.

");
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
