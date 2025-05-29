package com.example.mcpclient.controller;

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
        // Add any initial attributes to the model if needed for the page
        model.addAttribute("command", ""); // Initialize command field
        model.addAttribute("llmResponse", "");
        model.addAttribute("mcpResponse", "");
        model.addAttribute("finalOutcome", "Enter a command and click 'Execute'.");
        return "index"; // Name of the Thymeleaf HTML template
    }

    @PostMapping("/execute")
    public String executeCommand(@RequestParam("command") String command, Model model) {
        logger.info("Received command from page: {}", command);
        model.addAttribute("command", command);

        String llmProcessedCommand = command; // Default to original command
        String llmResponse = "LLM processing skipped (placeholder).";
        String mcpResponse = "MCP execution skipped (placeholder).";
        String finalOutcome;

        try {
            // Step 1: (Optional) Process command with Large Model
            // For now, we'll just use the placeholder.
            // In a real scenario, you might send `command` to `largeModelService.processText()`
            // and use its output as `llmProcessedCommand`.
            llmResponse = largeModelService.processText(command);
            logger.info("LLM Service Response: {}", llmResponse);
            // Potentially parse llmResponse to extract a command for MCP or use it directly
            // For this placeholder, let's assume the LLM response itself could be the command or part of it.
            // llmProcessedCommand = llmResponse; // Or some parsed part of it.

            // Step 2: Send command to MCP Server
            // For now, we send the original command, or what came from LLM
            mcpResponse = mcpService.sendCommand(llmProcessedCommand);
            logger.info("MCP Service Response: {}", mcpResponse);
            
            finalOutcome = "Command processed. See LLM and MCP responses.";

        } catch (Exception e) {
            logger.error("Error during command execution: {}", e.getMessage(), e);
            finalOutcome = "Error: " + e.getMessage();
            llmResponse = "Error occurred, LLM not called or failed.";
            mcpResponse = "Error occurred, MCP not called or failed.";
        }

        model.addAttribute("llmResponse", llmResponse);
        model.addAttribute("mcpResponse", mcpResponse);
        model.addAttribute("finalOutcome", finalOutcome);

        return "index"; // Return to the same page to display results
    }
}
