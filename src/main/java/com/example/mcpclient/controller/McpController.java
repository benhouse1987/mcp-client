package com.example.mcpclient.controller;

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

    @PostMapping("/execute")
    public String executeCommand(@RequestParam("command") String userInputCommand, Model model) {
        logger.info("Received user input: {}", userInputCommand);
        model.addAttribute("userInput", userInputCommand);
        model.addAttribute("llmTextResponse", "Processing with LLM..."); 
        model.addAttribute("mcpCommandName", "");
        model.addAttribute("mcpCommandOutput", "");

        try {
            LlmToolCallDto llmResponse = largeModelService.processText(userInputCommand);

            String llmText = llmResponse.getTextResponse();
            if (llmText == null || llmText.trim().isEmpty()) {
                // If LLM calls a tool, it might not have a separate text_response.
                // If it doesn't call a tool, and text_response is empty, then it's truly an empty response.
                if (llmResponse.getToolToUse() != null && !llmResponse.getToolToUse().trim().isEmpty()) {
                    llmText = "LLM is attempting to use tool: " + llmResponse.getToolToUse() + ". See tool output below.";
                } else {
                    llmText = "LLM did not provide a direct text response.";
                }
            }
            model.addAttribute("llmTextResponse", llmText);

            if (llmResponse.getToolToUse() != null && !llmResponse.getToolToUse().trim().isEmpty()) {
                String toolName = llmResponse.getToolToUse();
                model.addAttribute("mcpCommandName", "LLM decided to use tool: " + toolName);
                logger.info("LLM requested to use tool: {} with parameters: {}", toolName, llmResponse.getParameters());

                String mcpOutput = mcpService.executeMcpCommand(toolName, llmResponse.getParameters());
                model.addAttribute("mcpCommandOutput", mcpOutput);
                logger.info("MCP command '{}' output: {}", toolName, mcpOutput);

            } else {
                logger.info("LLM did not request a tool. Displaying its text response.");
                model.addAttribute("mcpCommandName", "No MCP command executed by LLM.");
            }

        } catch (Exception e) {
            logger.error("Error during command execution orchestration: {}", e.getMessage(), e);
            model.addAttribute("llmTextResponse", "An error occurred: " + e.getMessage());
            model.addAttribute("mcpCommandOutput", "Execution failed due to controller error.");
        }
        
        // Ensure availableMcpCommands is re-added on POST if not using redirect-after-post
        // This is necessary because we are returning "index" view directly.
        Map<String, McpServerDetailsDto> mcpConfigs = mcpService.getMcpServerConfigurations();
        if (mcpConfigs != null) {
            List<McpCommandView> availableCommands = mcpConfigs.entrySet().stream()
                .map(entry -> new McpCommandView(entry.getKey(), entry.getValue().getDescription()))
                .collect(Collectors.toList());
            model.addAttribute("availableMcpCommands", availableCommands);
        } else {
            model.addAttribute("availableMcpCommands", new ArrayList<McpCommandView>());
        }

        return "index"; 
    }
}
