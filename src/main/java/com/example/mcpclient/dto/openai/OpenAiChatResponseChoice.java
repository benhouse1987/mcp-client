package com.example.mcpclient.dto.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAiChatResponseChoice {
    private int index;
    private OpenAiChatMessage message;
    // Add finish_reason if needed

    // Getters and setters
    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public OpenAiChatMessage getMessage() {
        return message;
    }

    public void setMessage(OpenAiChatMessage message) {
        this.message = message;
    }
}
