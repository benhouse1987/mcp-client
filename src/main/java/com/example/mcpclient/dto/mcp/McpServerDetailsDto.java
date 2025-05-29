package com.example.mcpclient.dto.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerDetailsDto {

    private String description;
    private String command;
    private List<String> args; // For fixed arguments
    @JsonProperty("args_template") // Map JSON's "args_template" to this field
    private List<String> argsTemplate; // For templated arguments
    private String workingDirectory;

    // Default constructor for Jackson
    public McpServerDetailsDto() {
    }

    // Getters and Setters
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public List<String> getArgsTemplate() {
        return argsTemplate;
    }

    public void setArgsTemplate(List<String> argsTemplate) {
        this.argsTemplate = argsTemplate;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }
}
