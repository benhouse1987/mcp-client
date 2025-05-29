package com.example.mcpclient.service;

import com.example.mcpclient.dto.mcp.McpConfigRootDto;
import com.example.mcpclient.dto.mcp.McpServerDetailsDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// import org.springframework.beans.factory.annotation.Value; // Keep for future if config file path is from properties
import org.springframework.core.io.ClassPathResource; // To load from classpath
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class McpService {

    private static final Logger logger = LoggerFactory.getLogger(McpService.class);

    private final ObjectMapper objectMapper;
    private Map<String, McpServerDetailsDto> mcpServerConfigurations;

    // TODO: Make "mcp_servers.json" path configurable via application.properties if needed
    private final String mcpConfigPath = "mcp_servers.json";

    public McpService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadMcpConfigurations() {
        try {
            ClassPathResource resource = new ClassPathResource(mcpConfigPath);
            if (!resource.exists()) {
                logger.error("MCP configuration file not found at classpath: {}", mcpConfigPath);
                // Initialize with empty map to prevent NullPointerExceptions, or throw an exception
                mcpServerConfigurations = Map.of(); 
                return;
            }
            McpConfigRootDto configRoot = objectMapper.readValue(resource.getInputStream(), McpConfigRootDto.class);
            if (configRoot != null && configRoot.getMcpServers() != null) {
                mcpServerConfigurations = configRoot.getMcpServers();
                logger.info("Successfully loaded {} MCP server configurations.", mcpServerConfigurations.size());
                mcpServerConfigurations.forEach((name, config) -> logger.debug("Loaded MCP Server: {} -> Command: {}", name, config.getCommand()));
            } else {
                logger.warn("MCP configuration file {} is empty or malformed.", mcpConfigPath);
                mcpServerConfigurations = Map.of();
            }
        } catch (IOException e) {
            logger.error("Failed to load MCP server configurations from {}: {}", mcpConfigPath, e.getMessage(), e);
            mcpServerConfigurations = Map.of(); // Fallback to empty configurations
        }
    }

    public String executeMcpCommand(String serverName, Map<String, String> templateArguments) {
        if (mcpServerConfigurations == null || !mcpServerConfigurations.containsKey(serverName)) {
            logger.error("MCP server configuration not found for name: {}", serverName);
            return "Error: MCP server configuration '" + serverName + "' not found.";
        }

        McpServerDetailsDto config = mcpServerConfigurations.get(serverName);
        List<String> finalArgs;

        if (config.getArgsTemplate() != null && !config.getArgsTemplate().isEmpty()) {
            finalArgs = config.getArgsTemplate().stream()
                .map(arg -> replacePlaceholders(arg, templateArguments))
                .collect(Collectors.toList());
        } else if (config.getArgs() != null) {
            finalArgs = config.getArgs();
        } else {
            finalArgs = List.of();
        }

        List<String> commandAndArgs = new java.util.ArrayList<>();
        commandAndArgs.add(config.getCommand());
        commandAndArgs.addAll(finalArgs);

        logger.info("Executing MCP command '{}': {} {}", serverName, config.getCommand(), String.join(" ", finalArgs));

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(commandAndArgs);
            if (config.getWorkingDirectory() != null && !config.getWorkingDirectory().isBlank()) {
                File workingDir = new File(config.getWorkingDirectory());
                if (workingDir.exists() && workingDir.isDirectory()) {
                    processBuilder.directory(workingDir);
                    logger.info("Set working directory to: {}", workingDir.getAbsolutePath());
                } else {
                    logger.warn("Working directory '{}' specified for '{}' does not exist or is not a directory. Using default.", config.getWorkingDirectory(), serverName);
                }
            }
            
            Process process = processBuilder.start();

            // Capture output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }

            // Capture error
            StringBuilder errorOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    errorOutput.append(line).append(System.lineSeparator());
                }
            }

            boolean exited = process.waitFor(30, TimeUnit.SECONDS); // Timeout for process
            if (!exited) {
                process.destroyForcibly();
                logger.error("MCP command '{}' timed out.", serverName);
                return "Error: Command '" + serverName + "' timed out." + formatOutput(output, errorOutput);
            }

            int exitCode = process.exitValue();
            logger.info("MCP command '{}' finished with exit code: {}.", serverName, exitCode);

            if (exitCode == 0) {
                return "Output from '" + serverName + "':\n" + output.toString().trim();
            } else {
                return "Error executing '" + serverName + "' (exit code " + exitCode + "):\n" 
                       + output.toString().trim() 
                       + (errorOutput.length() > 0 ? "\nError Stream:\n" + errorOutput.toString().trim() : "");
            }

        } catch (IOException | InterruptedException e) {
            logger.error("Error executing MCP command '{}': {}", serverName, e.getMessage(), e);
            Thread.currentThread().interrupt(); // Restore interrupted status
            return "Error: Could not execute command '" + serverName + "'. " + e.getMessage();
        }
    }

    private String replacePlaceholders(String arg, Map<String, String> templateArguments) {
        if (templateArguments == null) return arg;
        String finalArg = arg;
        for (Map.Entry<String, String> entry : templateArguments.entrySet()) {
            finalArg = finalArg.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return finalArg;
    }
    
    private String formatOutput(StringBuilder output, StringBuilder errorOutput) {
        String result = "";
        if (output.length() > 0) {
            result += "\nOutput:\n" + output.toString().trim();
        }
        if (errorOutput.length() > 0) {
            result += "\nError Stream:\n" + errorOutput.toString().trim();
        }
        return result;
    }

    // Old sendCommand method and @Value mcpServerAddress are removed by overwriting.
    // Constructor now only requires ObjectMapper.
    
    // Getter for McpServerConfigurations, to be used by LargeModelService
    public Map<String, McpServerDetailsDto> getMcpServerConfigurations() {
        return this.mcpServerConfigurations;
    }
}
