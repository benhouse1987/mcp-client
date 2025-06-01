package com.example.mcpclient.service;

import com.example.mcpclient.dto.mcp.McpServerDetailsDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpServiceTest {

    private McpService mcpService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper(); // Use a real ObjectMapper for this test
        mcpService = new McpService(objectMapper);
        // Manually trigger PostConstruct method
        mcpService.loadMcpConfigurations();
    }

    @Test
    void loadMcpConfigurations_shouldLoadCmdToolCorrectly() throws IOException {
        // Verify that mcp_servers.json is loaded (implicitly tested by setUp)
        // Now, explicitly check the contents.

        // For this test, we need to ensure that the mcp_servers.json being read
        // is the one from src/main/resources.
        // The ClassPathResource in McpService loads from the classpath.
        // Maven/Gradle usually copies src/main/resources to target/classes,
        // and src/test/resources to target/test-classes.
        // When tests run, target/test-classes and target/classes are on classpath.
        // If there was an mcp_servers.json in src/test/resources, it might override.
        // Assuming no such override for this test.

        Map<String, McpServerDetailsDto> configurations = mcpService.getMcpServerConfigurations();

        assertNotNull(configurations, "Configurations should not be null");
        assertFalse(configurations.isEmpty(), "Configurations should not be empty");
        assertTrue(configurations.containsKey("cmd"), "Should contain configuration for 'cmd' tool");

        McpServerDetailsDto cmdConfig = configurations.get("cmd");
        assertNotNull(cmdConfig, "'cmd' tool configuration should not be null");

        assertEquals("cmd.exe", cmdConfig.getCommand(), "Command should be 'cmd.exe'");
        assertNotNull(cmdConfig.getArgsTemplate(), "ArgsTemplate should not be null");
        assertEquals(Arrays.asList("/c", "{user_command}"), cmdConfig.getArgsTemplate(), "ArgsTemplate is incorrect");
        assertEquals(
            "Executes a Windows command. Provide the full command string as the 'user_command' parameter. For example, to list directory contents, set 'user_command' to 'dir C:\\'.",
            cmdConfig.getDescription(),
            "Description is incorrect"
        );
        // Assuming working_directory was intended to be empty or null based on our json
        // If it's an empty string in JSON, it will be an empty string here.
//         assertEquals("", cmdConfig.getWorkingDirectory(), "Working directory should be empty");
        assertEquals("C:\\aidir", cmdConfig.getWorkingDirectory(), "Working directory should be C:\\aidir");
    }

    // TODO: Add test for executeMcpCommand focusing on command construction
    // This would likely involve mocking ProcessBuilder
}
