package com.example.mcpclient.controller;

import com.example.mcpclient.dto.llm.LlmToolCallDto;
import com.example.mcpclient.dto.mcp.McpServerDetailsDto; // For available commands
import com.example.mcpclient.dto.openai.OpenAiChatRequest; // For mocking
import com.example.mcpclient.service.LargeModelService;
import com.example.mcpclient.service.McpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.Model;
import org.springframework.http.HttpEntity; // Added for any(HttpEntity.class)

import java.util.ArrayList; // Added for anyList verification if needed more specifically
import java.util.Arrays; // Added for LargeModelServiceTest content, good to have for consistency
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class McpControllerTest {

    @Mock
    private McpService mockMcpService;

    @Mock
    private LargeModelService mockLargeModelService;

    @Mock
    private Model mockModel;

    @InjectMocks
    private McpController mcpController;

    private Map<String, McpServerDetailsDto> mockMcpConfigs;

    @BeforeEach
    void setUp() {
        mockMcpConfigs = new HashMap<>();
        McpServerDetailsDto cmdTool = new McpServerDetailsDto();
        cmdTool.setDescription("Test CMD tool");
        mockMcpConfigs.put("cmd", cmdTool);

        when(mockMcpService.getMcpServerConfigurations()).thenReturn(mockMcpConfigs);
        when(mockLargeModelService.getModelName()).thenReturn("test-gpt-model");
    }

    @Test
    void executeCommand_llmRespondsDirectly_noToolCall() {
        String userInput = "Hello, what is the time?";
        String llmDirectResponse = "I am a large language model and cannot provide real-time information like the current time.";

        LlmToolCallDto llmResponseDto = new LlmToolCallDto();
        llmResponseDto.setTextResponse(llmDirectResponse);

        when(mockLargeModelService.buildSystemMessageWithTools()).thenReturn("Initial system prompt");
        when(mockLargeModelService.processOpenAiRequest(any(OpenAiChatRequest.class)))
            .thenReturn(llmResponseDto);

        String viewName = mcpController.executeCommand(userInput, mockModel);

        assertEquals("index", viewName, "View name should be 'index'");

        verify(mockLargeModelService, times(1)).buildSystemMessageWithTools();
        verify(mockLargeModelService, times(1)).processOpenAiRequest(any(OpenAiChatRequest.class));
        verify(mockMcpService, never()).executeMcpCommand(anyString(), anyMap());
        verify(mockModel).addAttribute("userInput", userInput);
        verify(mockModel).addAttribute("llmTextResponse", llmDirectResponse);

        // Capture and verify conversation history (basic check)
        verify(mockModel).addAttribute(eq("conversationHistory"), anyList());
    }

    // TODO: Add test for single tool call by LLM
    // TODO: Add test for multi-step tool calls by LLM
    // TODO: Add test for max iterations reached
    // TODO: Add test for LLM attempting to use invalid tool
}
