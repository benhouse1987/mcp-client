package com.example.mcpclient.dto.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAiChatResponse {
    private String id;
    private String object;
    private long created;
    private String model;
    private List<OpenAiChatResponseChoice> choices;
    // Add usage statistics if needed

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getObject() { return object; }
    public void setObject(String object) { this.object = object; }
    public long getCreated() { return created; }
    public void setCreated(long created) { this.created = created; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public List<OpenAiChatResponseChoice> getChoices() { return choices; }
    public void setChoices(List<OpenAiChatResponseChoice> choices) { this.choices = choices; }
}
