package com.example.mcpclient.dto.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmToolCallDto {

    @JsonProperty("tool_to_use")
    private String toolToUse; // e.g., "list_directory_contents"

    @JsonProperty("parameters")
    private Map<String, String> parameters; // e.g., {"directory_path": "/some/path"}
    
    // This field can be used if the LLM also provides a direct textual response
    // in addition to or instead of a tool call.
    @JsonProperty("text_response")
    private String textResponse;


    // Default constructor
    public LlmToolCallDto() {
    }

    // Getters and Setters
    public String getToolToUse() {
        return toolToUse;
    }

    public void setToolToUse(String toolToUse) {
        this.toolToUse = toolToUse;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    public String getTextResponse() {
        return textResponse;
    }

    public void setTextResponse(String textResponse) {
        this.textResponse = textResponse;
    }
}
