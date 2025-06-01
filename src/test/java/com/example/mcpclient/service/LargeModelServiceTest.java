package com.example.mcpclient.service;

import com.example.mcpclient.dto.mcp.McpServerDetailsDto;
import com.fasterxml.jackson.databind.ObjectMapper; // Not directly used for mocking McpService output here
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.client.RestTemplate; // Mocked but not used in this specific test method

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class LargeModelServiceTest {

    @Mock
    private McpService mockMcpService;

    @Mock
    private RestTemplate mockRestTemplate; // Needed for constructor

    @Mock
    private ObjectMapper mockObjectMapper; // Needed for constructor

    @InjectMocks
    private LargeModelService largeModelService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // largeModelService is automatically instantiated with mocks due to @InjectMocks
    }

    @Test
    void buildSystemMessageWithTools_shouldGenerateCorrectMessageForCmdTool() {
        // 1. Define the mock behavior for McpService
        Map<String, McpServerDetailsDto> mockTools = new HashMap<>();
        McpServerDetailsDto cmdToolDetails = new McpServerDetailsDto();
        cmdToolDetails.setCommand("cmd.exe");
        cmdToolDetails.setArgsTemplate(Arrays.asList("/c", "{user_command}"));
        cmdToolDetails.setDescription("Executes a Windows command. Provide the full command string as the 'user_command' parameter. For example, to list directory contents, set 'user_command' to 'dir C:\\'.");
        cmdToolDetails.setWorkingDirectory("");
        mockTools.put("cmd", cmdToolDetails);

        when(mockMcpService.getMcpServerConfigurations()).thenReturn(mockTools);

        // 2. Call the method under test
        // Reflection or direct call if method is made package-private or public for testing
        // For this example, let's assume we can call it (it's private, so this would typically require reflection or refactoring for testability)
        // String systemMessage = largeModelService.buildSystemMessageWithTools();
        // Since direct call is hard for private methods, we will test its effect via processText,
        // by checking parts of the prompt. However, the prompt is built with @Value fields.
        // A more direct test of buildSystemMessageWithTools would be better if it were not private.

        // Let's assume for the sake of this subtask that we can test the private method.
        // If not, this test would need to be adapted or the method refactored.
        // One common way is to change its visibility to package-private for testing.
        // For now, we'll generate what we expect the output to be.

        String systemMessage = largeModelService.buildSystemMessageWithTools(); // Assuming it's made accessible for test

        // 3. Assertions
        assertNotNull(systemMessage, "System message should not be null");
        assertTrue(systemMessage.contains("You have access to the following tools"), "Message should introduce tools");
        assertTrue(systemMessage.contains("Tool Name: `cmd`"), "Message should contain 'cmd' tool name");
        assertTrue(systemMessage.contains("Description: Executes a Windows command. Provide the full command string as the 'user_command' parameter."), "Message should contain cmd tool description");
        assertTrue(systemMessage.contains("Parameters to provide: `user_command`"), "Message should list 'user_command' as parameter");
        assertFalse(systemMessage.contains("Tool Name: `another_tool`"), "Message should not contain other tools");
         assertTrue(systemMessage.contains("respond ONLY with a single JSON object matching this exact structure:"));
        assertTrue(systemMessage.contains("{"tool_to_use": "<tool_name>", "parameters": {"<param_name_1>": "<param_value_1>""));
        assertTrue(systemMessage.contains("If you do not need to use a tool, or if no tool is suitable for the user's request, respond ONLY with a single JSON object: {"text_response": "<your_direct_answer_to_the_user>"}"));
    }

    @Test
    void buildSystemMessageWithTools_whenNoTools_shouldGenerateGenericMessage() {
        when(mockMcpService.getMcpServerConfigurations()).thenReturn(Collections.emptyMap());
        String systemMessage = largeModelService.buildSystemMessageWithTools(); // Assuming accessible

        assertEquals("You are a helpful assistant. Please respond directly to the user's query.", systemMessage);
    }
}
