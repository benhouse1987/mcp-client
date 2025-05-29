package com.example.mcpclient.dto.openai;

import java.util.List;

public class OpenAiChatRequest {
    private String model;
    private List<OpenAiChatMessage> messages;
    // Add other parameters like temperature, max_tokens if needed

    // Default constructor for Jackson
    public OpenAiChatRequest() {
    }

    public OpenAiChatRequest(String model, List<OpenAiChatMessage> messages) {
        this.model = model;
        this.messages = messages;
    }

    // Getters and setters
    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<OpenAiChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<OpenAiChatMessage> messages) {
        this.messages = messages;
    }
}
