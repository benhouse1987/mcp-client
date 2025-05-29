package com.example.mcpclient.service;

import com.example.mcpclient.dto.openai.OpenAiChatMessage;
import com.example.mcpclient.dto.openai.OpenAiChatRequest;
import com.example.mcpclient.dto.openai.OpenAiChatResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Service
public class LargeModelService {

    private static final Logger logger = LoggerFactory.getLogger(LargeModelService.class);

    @Value("${large.model.url}")
    private String modelUrl; // Should be https://api.openai.com/v1/chat/completions

    @Value("${large.model.name}")
    private String modelName; // Should be gpt-4o

    @Value("${large.model.key}")
    private String apiKey; // User's OpenAI API Key

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper; // For serializing request body

    public LargeModelService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String processText(String inputText) {
        logger.info("Sending text to Large Model ({}) at {}: '{}'", modelName, modelUrl, inputText);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        OpenAiChatMessage userMessage = new OpenAiChatMessage("user", inputText);
        List<OpenAiChatMessage> messages = Collections.singletonList(userMessage);
        OpenAiChatRequest chatRequest = new OpenAiChatRequest(modelName, messages);

        try {
            String requestBody = objectMapper.writeValueAsString(chatRequest);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            logger.debug("OpenAI Request Body: {}", requestBody);

            ResponseEntity<OpenAiChatResponse> responseEntity = restTemplate.postForEntity(
                    modelUrl,
                    entity,
                    OpenAiChatResponse.class
            );

            OpenAiChatResponse chatResponse = responseEntity.getBody();

            if (chatResponse != null && chatResponse.getChoices() != null && !chatResponse.getChoices().isEmpty()) {
                OpenAiChatMessage assistantMessage = chatResponse.getChoices().get(0).getMessage();
                logger.info("Received response from LLM: {}", assistantMessage.getContent());
                return assistantMessage.getContent();
            } else {
                logger.warn("No response choices received from LLM or response was empty.");
                return "No response from model or response was empty.";
            }

        } catch (HttpClientErrorException e) {
            logger.error("HttpClientErrorException while calling OpenAI: {} - {}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            return "Error from LLM API: " + e.getStatusCode() + " - " + e.getResponseBodyAsString();
        } catch (JsonProcessingException e) {
            logger.error("Error serializing request for OpenAI: {}", e.getMessage(), e);
            return "Error preparing request for LLM: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Error processing text with Large Model: {}", e.getMessage(), e);
            return "Error: Could not connect to Large Model or process its response. " + e.getMessage();
        }
    }
}
