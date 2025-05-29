package com.example.mcpclient.dto.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class McpConfigRootDto {

    private Map<String, McpServerDetailsDto> mcpServers;

    // Default constructor for Jackson
    public McpConfigRootDto() {
    }

    // Getters and Setters
    public Map<String, McpServerDetailsDto> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(Map<String, McpServerDetailsDto> mcpServers) {
        this.mcpServers = mcpServers;
    }
}
