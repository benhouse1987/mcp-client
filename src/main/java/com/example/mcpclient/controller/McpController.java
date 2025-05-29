package com.example.mcpclient.controller;

import com.example.mcpclient.dto.llm.LlmToolCallDto;
import com.example.mcpclient.service.LargeModelService;
import com.example.mcpclient.service.McpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class McpController {

    private static final Logger logger = LoggerFactory.getLogger(McpController.class);

    private final McpService mcpService;
    private final LargeModelService largeModelService;

    public McpController(McpService mcpService, LargeModelService largeModelService) {
        this.mcpService = mcpService;
        this.largeModelService = largeModelService;
    }

    @GetMapping("/")
    public String index(Model model) {
        // Preserve existing model attributes for initial page load if any, or add new ones
        if (!model.containsAttribute("userInput")) { // Prevent overwriting on POST redirect
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
        return "index"; // Name of the Thymeleaf HTML template
    }

    @PostMapping("/execute")
    public String executeCommand(@RequestParam("command") String userInputCommand, Model model) {
        logger.info("Received user input: {}", userInputCommand);
        model.addAttribute("userInput", userInputCommand);
        model.addAttribute("llmTextResponse", "Processing with LLM..."); // Initial status
        model.addAttribute("mcpCommandName", "");
        model.addAttribute("mcpCommandOutput", "");

        try {
            LlmToolCallDto llmResponse = largeModelService.processText(userInputCommand);

            String llmText = llmResponse.getTextResponse();
            if (llmText == null || llmText.isBlank()) {
                // If LLM calls a tool, it might not have a separate text_response.
                // If it doesn't call a tool, and text_response is empty, then it's truly an empty response.
                llmText = (llmResponse.getToolToUse() != null && !llmResponse.getToolToUse().isBlank()) 
                          ? "LLM is attempting to use a tool." 
                          : "LLM did not provide a direct text response.";
            }
            model.addAttribute("llmTextResponse", llmText);

            if (llmResponse.getToolToUse() != null && !llmResponse.getToolToUse().isBlank()) {
                String toolName = llmResponse.getToolToUse();
                model.addAttribute("mcpCommandName", "LLM requested tool: " + toolName);
                logger.info("LLM requested to use tool: {} with parameters: {}", toolName, llmResponse.getParameters());

                // Execute the MCP command
                String mcpOutput = mcpService.executeMcpCommand(toolName, llmResponse.getParameters());
                model.addAttribute("mcpCommandOutput", mcpOutput);
                logger.info("MCP command '{}' output: {}", toolName, mcpOutput);
                
                // Optionally, update llmTextResponse if the tool execution implies a summary.
                // For now, the initial llmText (if any) and mcpCommandOutput are distinct.

            } else {
                logger.info("LLM did not request a tool. Displaying its text response.");
                model.addAttribute("mcpCommandName", "No MCP command executed by LLM.");
                // llmTextResponse is already set with the direct text from LLM (or default if blank)
            }

        } catch (Exception e) {
            logger.error("Error during command execution orchestration: {}", e.getMessage(), e);
            model.addAttribute("llmTextResponse", "An error occurred in the controller: " + e.getMessage());
            model.addAttribute("mcpCommandOutput", "Execution failed due to controller error.");
        }

        return "index"; // Return to the same page to display results
    }
}
