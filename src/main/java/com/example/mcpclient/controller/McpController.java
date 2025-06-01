package com.example.mcpclient.controller;
import com.example.mcpclient.dto.openai.OpenAiChatMessage;


import com.example.mcpclient.dto.openai.OpenAiChatRequest;

import com.example.mcpclient.dto.llm.LlmToolCallDto;
import com.example.mcpclient.dto.mcp.McpServerDetailsDto; // Import this
import com.example.mcpclient.service.LargeModelService;
import com.example.mcpclient.service.McpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList; // Import this
import java.util.List;      // Import this
import java.util.Map;       // Import this
import java.util.stream.Collectors; // Import this

@Controller
public class McpController {

    private static final Logger logger = LoggerFactory.getLogger(McpController.class);

    private final McpService mcpService;
    private final LargeModelService largeModelService;

    public McpController(McpService mcpService, LargeModelService largeModelService) {
        this.mcpService = mcpService;
        this.largeModelService = largeModelService;
    }

    // Helper DTO for simpler view model, could be an inner class or separate file
    public static class McpCommandView {
        private String name;
        private String description;

        public McpCommandView(String name, String description) {
            this.name = name;
            this.description = description;
        }
        public String getName() { return name; }
        public String getDescription() { return description; }
    }

    @GetMapping("/")
    public String index(Model model) {
        if (!model.containsAttribute("userInput")) {
            model.addAttribute("userInput", "");
        }
        if (!model.containsAttribute("llmTextResponse")) {
             model.addAttribute("llmTextResponse", "Awaiting your command...");
        }
        if (!model.containsAttribute("mcpCommandName")) {
            model.addAttribute("mcpCommandName", "");
        }
        if (!model.containsAttribute("mcpCommandOutput")) {
            model.addAttribute("mcpCommandOutput", "");
        }

        // Get available MCP commands from McpService
        Map<String, McpServerDetailsDto> mcpConfigs = mcpService.getMcpServerConfigurations();
        if (mcpConfigs != null) {
            List<McpCommandView> availableCommands = mcpConfigs.entrySet().stream()
                .map(entry -> new McpCommandView(entry.getKey(), entry.getValue().getDescription()))
                .collect(Collectors.toList());
            model.addAttribute("availableMcpCommands", availableCommands);
            logger.debug("Added available MCP commands to model: {}", availableCommands.size());
        } else {
            model.addAttribute("availableMcpCommands", new ArrayList<McpCommandView>()); // Empty list
            logger.warn("No MCP configurations loaded or McpService returned null for configurations.");
        }
        
        return "index";
    }

    private static final int MAX_LLM_ITERATIONS = 5; // Prevent infinite loops

    @PostMapping("/execute")
    public String executeCommand(@RequestParam("command") String originalUserInput, Model model) {
        logger.info("Received original user input: {}", originalUserInput);
        model.addAttribute("userInput", originalUserInput);

        List<String> conversationHistoryForDisplay = new ArrayList<>();
        LlmToolCallDto llmResponse = null;

        String openAiModelName = largeModelService.getModelName();
        if (openAiModelName == null || openAiModelName.trim().isEmpty()) {
            logger.error("OpenAI Model Name is not configured in LargeModelService.");
            model.addAttribute("llmTextResponse", "Error: Application configuration issue (model name missing).");
            model.addAttribute("conversationHistory", conversationHistoryForDisplay);
            addDefaultModelAttributes(model);
            return "index";
        }

        for (int i = 0; i < MAX_LLM_ITERATIONS; i++) {
            logger.info("LLM Iteration {}/{}...", i + 1, MAX_LLM_ITERATIONS);
            OpenAiChatRequest chatRequest;
            List<OpenAiChatMessage> messages = new ArrayList<>();

            if (i == 0) {
                String systemMessage = largeModelService.buildSystemMessageWithTools();
                messages.add(new OpenAiChatMessage("system", systemMessage));
                messages.add(new OpenAiChatMessage("user", originalUserInput));
                chatRequest = new OpenAiChatRequest(openAiModelName, messages);
                llmResponse = largeModelService.processOpenAiRequest(chatRequest);
            } else {
                String systemMessage = largeModelService.buildFollowUpSystemMessage(
                    originalUserInput,
                    (String) model.getAttribute("lastExecutedToolName"),
                    (Map<String, String>) model.getAttribute("lastExecutedToolParams"),
                    (String) model.getAttribute("lastToolOutput")
                );
                messages.add(new OpenAiChatMessage("system", systemMessage));
                messages.add(new OpenAiChatMessage("user", "Based on the previous command's output, what is the next step or the final answer?"));
                chatRequest = new OpenAiChatRequest(openAiModelName, messages);
                llmResponse = largeModelService.processOpenAiRequest(chatRequest);
            }

            if (llmResponse.getTextResponse() != null && !llmResponse.getTextResponse().isEmpty()) {
                String llmText = llmResponse.getTextResponse();
                conversationHistoryForDisplay.add("LLM: " + llmText);
                model.addAttribute("llmTextResponse", llmText);
            }

            if (llmResponse.getToolToUse() != null && !llmResponse.getToolToUse().trim().isEmpty()) {
                String toolName = llmResponse.getToolToUse();
                Map<String, String> toolParameters = llmResponse.getParameters();
                conversationHistoryForDisplay.add("LLM wants to use tool: " + toolName + " with parameters: " + toolParameters);

                if (!"cmd".equals(toolName) || !mcpService.getMcpServerConfigurations().containsKey(toolName)) {
                    String errorMsg = "LLM attempted to use an invalid or unavailable tool: " + toolName + ". Aborting interaction.";
                    logger.warn(errorMsg);
                    conversationHistoryForDisplay.add(errorMsg);
                    model.addAttribute("mcpCommandOutput", errorMsg);
                    break;
                }


                if ("cmd".equals(toolName) && toolParameters.containsKey("user_command")) {
                    conversationHistoryForDisplay.add("Executing command: `" + toolParameters.get("user_command") + "`");
                }
                String mcpOutput = mcpService.executeMcpCommand(toolName, toolParameters);
                conversationHistoryForDisplay.add("Tool '" + toolName + "' output:\n" + mcpOutput);
                model.addAttribute("mcpCommandOutput", mcpOutput);

                model.addAttribute("lastExecutedToolName", toolName);
                model.addAttribute("lastExecutedToolParams", toolParameters);
                model.addAttribute("lastToolOutput", mcpOutput);

                if (i == MAX_LLM_ITERATIONS - 1) {
                    conversationHistoryForDisplay.add("Max iterations reached. Ending conversation.");
                }
            } else {
                logger.info("LLM provided a final response or no tool was called. Ending interaction loop.");
                model.addAttribute("conversationHistory", conversationHistoryForDisplay);
                break;
            }
        }

        model.addAttribute("conversationHistory", conversationHistoryForDisplay);
        addDefaultModelAttributes(model);
        return "index";
    }

    private void addDefaultModelAttributes(Model model) {
        if (!model.containsAttribute("userInput")) {
            model.addAttribute("userInput", "");
        }
        Map<String, McpServerDetailsDto> mcpConfigs = mcpService.getMcpServerConfigurations();
        if (mcpConfigs != null) {
            List<McpController.McpCommandView> availableCommands = mcpConfigs.entrySet().stream()
                .map(entry -> new McpController.McpCommandView(entry.getKey(), entry.getValue().getDescription()))
                .collect(Collectors.toList());
            model.addAttribute("availableMcpCommands", availableCommands);
        } else {
            model.addAttribute("availableMcpCommands", new ArrayList<McpController.McpCommandView>());
        }
        if (!model.containsAttribute("llmTextResponse")) {
             model.addAttribute("llmTextResponse", "Awaiting your command...");
        }
        if (!model.containsAttribute("mcpCommandOutput")) {
            model.addAttribute("mcpCommandOutput", "");
        }
    }
}
