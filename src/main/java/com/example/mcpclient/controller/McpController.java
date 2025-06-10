package com.example.mcpclient.controller;
import com.example.mcpclient.dto.openai.OpenAiChatMessage;


import com.example.mcpclient.dto.openai.OpenAiChatRequest;

import com.example.mcpclient.dto.llm.LlmToolCallDto;
import com.example.mcpclient.dto.mcp.McpServerDetailsDto;
import com.example.mcpclient.entity.ChatMessage;
import com.example.mcpclient.mapper.ChatMessageMapper;
import com.example.mcpclient.service.LargeModelService;
import com.example.mcpclient.service.McpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

@Controller
public class McpController {

    private static final Logger logger = LoggerFactory.getLogger(McpController.class);
    // Phrase the LLM must include in its final text_response to explicitly signal task completion.
    private static final String COMPLETION_PHRASE = "任务完成";

    private final McpService mcpService;
    private final LargeModelService largeModelService;
    private final ChatMessageMapper chatMessageMapper;

    public McpController(McpService mcpService, LargeModelService largeModelService, ChatMessageMapper chatMessageMapper) {
        this.mcpService = mcpService;
        this.largeModelService = largeModelService;
        this.chatMessageMapper = chatMessageMapper;
    }

    // Helper DTO for simpler view model, could be an inner class or separate file
    public static class McpCommandView {
        private String name;
        private String description;

        public McpCommandView(String name, String description) {
            this.name = name;
            this.description = description;
        }
        public String getName() { return name; }
        public String getDescription() { return description; }
    }

    @GetMapping("/")
    public String index(Model model, @RequestParam(required = false) String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            taskId = UUID.randomUUID().toString();
            logger.info("New session or missing taskId, generated taskId: {}", taskId);
        }
        model.addAttribute("taskId", taskId);

        if (!model.containsAttribute("userInput")) {
            model.addAttribute("userInput", "");
        }
        if (!model.containsAttribute("llmTextResponse")) {
             model.addAttribute("llmTextResponse", "Awaiting your command...");
        }
        if (!model.containsAttribute("mcpCommandName")) {
            model.addAttribute("mcpCommandName", "");
        }
        if (!model.containsAttribute("mcpCommandOutput")) {
            model.addAttribute("mcpCommandOutput", "");
        }

        // Get available MCP commands from McpService
        Map<String, McpServerDetailsDto> mcpConfigs = mcpService.getMcpServerConfigurations();
        if (mcpConfigs != null) {
            List<McpCommandView> availableCommands = mcpConfigs.entrySet().stream()
                .map(entry -> new McpCommandView(entry.getKey(), entry.getValue().getDescription()))
                .collect(Collectors.toList());
            model.addAttribute("availableMcpCommands", availableCommands);
            logger.debug("Added available MCP commands to model: {}", availableCommands.size());
        } else {
            model.addAttribute("availableMcpCommands", new ArrayList<McpCommandView>()); // Empty list
            logger.warn("No MCP configurations loaded or McpService returned null for configurations.");
        }
        
        return "index";
    }

    private static final int MAX_LLM_ITERATIONS = 100; // Prevent infinite loops

    @PostMapping("/execute")
    public String executeCommand(@RequestParam("command") String originalUserInput,
                                 @RequestParam("taskId") String taskId,
                                 Model model) {
        model.addAttribute("taskId", taskId); // Ensure taskId is in the model for this request
        logger.info("Task ID {} - Received original user input: {}", taskId, originalUserInput);
        model.addAttribute("userInput", originalUserInput);

        // Save initial user input
        ChatMessage userMessage = new ChatMessage();
        userMessage.setTaskId(taskId);
        userMessage.setUserInput(originalUserInput);
        userMessage.setTimestamp(LocalDateTime.now());
        chatMessageMapper.insert(userMessage);
        logger.info("Task ID {} - Saved user input to DB.", taskId);

        // Retrieve full task history
        List<ChatMessage> taskHistory = chatMessageMapper.selectList(
            new QueryWrapper<ChatMessage>().eq("task_id", taskId).orderByAsc("timestamp")
        );
        logger.info("Task ID {} - Retrieved {} historical messages from DB.", taskId, taskHistory.size());

        List<String> conversationHistoryForDisplay = new ArrayList<>();
        LlmToolCallDto llmResponse = null;

        String openAiModelName = largeModelService.getModelName();
        if (openAiModelName == null || openAiModelName.trim().isEmpty()) {
            logger.error("OpenAI Model Name is not configured in LargeModelService.");
            model.addAttribute("llmTextResponse", "Error: Application configuration issue (model name missing).");
            model.addAttribute("conversationHistory", conversationHistoryForDisplay);
            addDefaultModelAttributes(model);
            return "index";
        }

        // This loop enables iterative interaction with the LLM. The LLM is guided by system prompts now primarily in Chinese, emphasizing autonomous action.
        // It's capped by MAX_LLM_ITERATIONS to prevent endless cycles.
        // In each iteration, the LLM might call a tool or provide a text response.
        for (int i = 0; i < MAX_LLM_ITERATIONS; i++) {
            logger.info("Task ID {} - LLM Iteration {}/{}...", taskId, i + 1, MAX_LLM_ITERATIONS);
            List<OpenAiChatMessage> messages = new ArrayList<>();

            // Prepare a formatted list of executed commands and their results for the system prompt.
            // This provides the LLM with a comprehensive history of actions taken so far in the current task.
            // This list will be empty for the first iteration (i=0) if taskHistory only contains the initial user message,
            // or if no commands have been executed yet.
            List<String> formattedCommandHistoryItems = new ArrayList<>();
            // Iterate through the taskHistory (which includes the latest user input and any prior interactions)
            // to build the command execution history.
            // The taskHistory is updated after each LLM response or tool execution within this loop.
            for (ChatMessage historicalMessage : taskHistory) {
                if (historicalMessage.getMcpCommand() != null && !historicalMessage.getMcpCommand().isEmpty()) {
                    String commandDetail = historicalMessage.getMcpCommand();
                    String outputDetail = (historicalMessage.getMcpCommandOutput() != null && !historicalMessage.getMcpCommandOutput().isEmpty())
                                          ? historicalMessage.getMcpCommandOutput()
                                          : "(无输出或输出未记录)";
                    // Format each command execution and its output as a single string entry.
                    String historyEntry = String.format("先前执行的命令: %s\n命令结果:\n%s", commandDetail, outputDetail);
                    formattedCommandHistoryItems.add(historyEntry);
                }
            }

            // Always use the unified system message, providing the original user input and the full command execution history.
            // This consistent prompt structure is used for both initial and subsequent LLM interactions.
            String systemMessageText = largeModelService.buildUnifiedSystemMessage(originalUserInput, formattedCommandHistoryItems);

            // Prepare the message list for the OpenAI API call, adhering to a lean structure.
            // The list will contain:
            // 1. A single, comprehensive system message (generated by buildUnifiedSystemMessage)
            //    that includes the original user query and the full history of executed commands and their results.
            // 2. If it's the first interaction turn (i == 0), the current originalUserInput is added as a user message.
            // For subsequent turns (i > 0), only the system message is sent, as it contains all necessary context.
            // The old logic of adding multiple historical user/assistant/tool messages directly to this list is removed.
            messages.clear(); // Ensure messages list is fresh for this iteration's API call
            messages.add(new OpenAiChatMessage("system", systemMessageText));

            if (i == 0) {
                messages.add(new OpenAiChatMessage("user", originalUserInput));
            }

            logger.debug("Task ID {} - Iteration {} - Messages prepared for LLM (roles): {}", taskId, i + 1, messages.stream().map(OpenAiChatMessage::getRole).collect(Collectors.joining(", ")));

            // Validate the constructed messages list.
            // It should always have at least one system message.
            // If the system message content is empty, it's a critical error.
            if (messages.isEmpty() || (messages.get(0).getRole().equals("system") && messages.get(0).getContent().trim().isEmpty())) {
                 logger.error("Task ID {} - Iteration {} - System message is empty or messages list is empty. Critical error.", taskId, i + 1);
                 model.addAttribute("llmTextResponse", "Error: System prompt generation failed.");
                 break;
            }
            // Note: For i > 0, messages.size() will be 1 (system prompt only), which is valid.
            // For i == 0, messages.size() will be 2 (system + user), also valid.

            // Send the lean message list to the LLM.
            OpenAiChatRequest chatRequest = new OpenAiChatRequest(openAiModelName, messages);
            llmResponse = largeModelService.processOpenAiRequest(chatRequest);

            if (llmResponse.getTextResponse() != null && !llmResponse.getTextResponse().isEmpty()) {
                String llmText = llmResponse.getTextResponse();
                conversationHistoryForDisplay.add("LLM: " + llmText);
                model.addAttribute("llmTextResponse", llmText);

                // Save LLM text response
                ChatMessage llmTextMessage = new ChatMessage();
                llmTextMessage.setTaskId(taskId);
                llmTextMessage.setLlmResponse(llmText);
                llmTextMessage.setTimestamp(LocalDateTime.now());
                chatMessageMapper.insert(llmTextMessage);
                taskHistory.add(llmTextMessage); // Add to in-memory history for next iteration's context
                logger.info("Task ID {} - Saved LLM text response to DB. History size: {}", taskId, taskHistory.size());
            }

            if (llmResponse.getToolToUse() != null && !llmResponse.getToolToUse().trim().isEmpty()) {
                String toolName = llmResponse.getToolToUse();
                Map<String, String> toolParameters = llmResponse.getParameters();
                String mcpCommandString = toolName + " with params: " + toolParameters.toString();
                conversationHistoryForDisplay.add("LLM wants to use tool: " + mcpCommandString);

                ChatMessage toolCallMessage = new ChatMessage();
                toolCallMessage.setTaskId(taskId);
                toolCallMessage.setMcpCommand(mcpCommandString);
                // Capture LLM's textual response that might have accompanied the tool call decision
                if (llmResponse.getTextResponse() != null && !llmResponse.getTextResponse().isEmpty()) {
                    toolCallMessage.setLlmResponse(llmResponse.getTextResponse());
                } else {
                    toolCallMessage.setLlmResponse("LLM requesting tool: " + toolName); // Fallback text
                }
                toolCallMessage.setTimestamp(LocalDateTime.now());
                chatMessageMapper.insert(toolCallMessage);
                taskHistory.add(toolCallMessage);
                logger.info("Task ID {} - Saved LLM tool call to DB: {}. History size: {}", taskId, mcpCommandString, taskHistory.size());

                if (!"cmd".equals(toolName) || !mcpService.getMcpServerConfigurations().containsKey(toolName)) {
                    String errorMsg = "LLM attempted to use an invalid or unavailable tool: " + toolName + ". Aborting interaction.";
                    logger.warn(errorMsg);
                    conversationHistoryForDisplay.add(errorMsg);
                    model.addAttribute("mcpCommandOutput", errorMsg);

                    ChatMessage errorToolOutputMessage = new ChatMessage();
                    errorToolOutputMessage.setTaskId(taskId);
                    errorToolOutputMessage.setMcpCommand(mcpCommandString); // Log which command failed
                    errorToolOutputMessage.setMcpCommandOutput(errorMsg);
                    errorToolOutputMessage.setTimestamp(LocalDateTime.now());
                    chatMessageMapper.insert(errorToolOutputMessage);
                    taskHistory.add(errorToolOutputMessage);
                    logger.info("Task ID {} - Saved tool error output to DB. History size: {}", taskId, taskHistory.size());
                    break;
                }

                if ("cmd".equals(toolName) && toolParameters.containsKey("user_command")) {
                    conversationHistoryForDisplay.add("Executing command: `" + toolParameters.get("user_command") + "`");
                }
                String mcpOutput = mcpService.executeMcpCommand(toolName, toolParameters);
                conversationHistoryForDisplay.add("Tool '" + toolName + "' output:\n" + mcpOutput);
                model.addAttribute("mcpCommandOutput", mcpOutput);

                ChatMessage mcpOutputMessage = new ChatMessage();
                mcpOutputMessage.setTaskId(taskId);
                // Associate output with the command that was run.
                // If llmResponse.getTextResponse() was present, it's already logged with toolCallMessage.
                // Here we log the mcpCommandString again to link it directly to its output.
                mcpOutputMessage.setMcpCommand(mcpCommandString);
                mcpOutputMessage.setMcpCommandOutput(mcpOutput);
                mcpOutputMessage.setTimestamp(LocalDateTime.now());
                chatMessageMapper.insert(mcpOutputMessage);
                taskHistory.add(mcpOutputMessage);
                logger.info("Task ID {} - Saved MCP command output to DB. History size: {}", taskId, taskHistory.size());

                // These attributes are used in the next iteration to build the follow-up system message,
                // providing context to the LLM about the last action taken.
                model.addAttribute("lastExecutedToolName", toolName);
                model.addAttribute("lastExecutedToolParams", toolParameters);
                model.addAttribute("lastToolOutput", mcpOutput);

                if (i == MAX_LLM_ITERATIONS - 1) {
                    conversationHistoryForDisplay.add("Max iterations reached. Ending conversation.");
                }
            } else {
                // LLM provided no tool call. Check if it's a final response with explicit completion.
                if (llmResponse.getTextResponse() != null && !llmResponse.getTextResponse().isEmpty()) {
                    if (llmResponse.getTextResponse().contains(COMPLETION_PHRASE)) {
                        logger.info("LLM provided a final response with completion phrase ('{}'). Ending interaction loop.", COMPLETION_PHRASE);
                        model.addAttribute("conversationHistory", conversationHistoryForDisplay);
                        break; // Break only if completion phrase is present
                    } else {
                        logger.info("LLM provided a text response without the completion phrase. Task considered ongoing. Text: {}", llmResponse.getTextResponse());
                        // Loop continues, LLM will be prompted again with updated history.
                        // This covers cases where LLM might be explaining something or giving partial info.
                    }
                } else {
                    // LLM provided no tool and no text response, or an empty text response.
                    // This is unusual. Log it and continue, letting max iterations or next LLM decision handle it.
                    logger.warn("LLM provided no tool and no (or empty) text response. Task considered ongoing by default.");
                }
            }
        }

        if (llmResponse != null && (llmResponse.getToolToUse() != null || (llmResponse.getTextResponse() != null && !llmResponse.getTextResponse().contains(COMPLETION_PHRASE)))) {
            // This condition means the loop finished by exhausting iterations, and the LLM didn't signal completion.
            logger.warn("Task ID {} - Max iterations ({}) reached and LLM did not signal task completion with '{}'. Ending interaction.", taskId, MAX_LLM_ITERATIONS, COMPLETION_PHRASE);
            conversationHistoryForDisplay.add("Max iterations reached. LLM did not explicitly confirm task completion.");
        }

        model.addAttribute("conversationHistory", conversationHistoryForDisplay);
        addDefaultModelAttributes(model);
        return "index";
    }

    @GetMapping("/new_task")
    public String newTask() {
        String newTaskId = UUID.randomUUID().toString();
        logger.info("New task started by user. New taskId: {}", newTaskId);
        return "redirect:/?taskId=" + newTaskId;
    }

    // Helper methods to extract tool name and arguments from the stored command string
    // These are basic implementations and might need to be more robust
    private String extractToolName(String mcpCommand) {
        if (mcpCommand == null) return "";
        // Example: "cmd with params: {user_command=list files}"
        // Example: "some_other_tool with params: {param1=value1}"
        int paramsIndex = mcpCommand.indexOf(" with params: ");
        if (paramsIndex != -1) {
            return mcpCommand.substring(0, paramsIndex);
        }
        // Fallback if " with params: " is not found (e.g. older format or simple command name)
        int spaceIndex = mcpCommand.indexOf(" ");
        if (spaceIndex > 0) {
            return mcpCommand.substring(0, spaceIndex);
        }
        return mcpCommand;
    }

    private String extractToolArguments(String mcpCommand) {
        if (mcpCommand == null) return "";
        int paramsIndex = mcpCommand.indexOf(" with params: ");
        if (paramsIndex != -1 && mcpCommand.length() > paramsIndex + " with params: ".length()) {
            String args = mcpCommand.substring(paramsIndex + " with params: ".length());
            // Basic check if it's already a JSON-like structure
            if (args.startsWith("{") && args.endsWith("}")) {
                return args;
            }
            // If not, it might be a simple string argument; wrap it to look like JSON.
            // This is a simple heuristic. Real parsing might be needed for complex cases.
            // e.g. if command is "tool_name with params: list files" -> "{ \"command\": \"list files\" }"
            // For "cmd with params: {user_command=list files}", it should extract "{user_command=list files}"
            // The current mcpCommandString is "toolName + " with params: " + toolParameters.toString()"
            // toolParameters.toString() for a Map often looks like {key=value, key2=value2}
            return args; // Return as is, assuming it's Map.toString() output or similar
        }
        return "{}"; // Default to empty JSON object
    }

    private void addDefaultModelAttributes(Model model) {
        if (!model.containsAttribute("userInput")) {
            model.addAttribute("userInput", "");
        }
        Map<String, McpServerDetailsDto> mcpConfigs = mcpService.getMcpServerConfigurations();
        if (mcpConfigs != null) {
            List<McpController.McpCommandView> availableCommands = mcpConfigs.entrySet().stream()
                .map(entry -> new McpController.McpCommandView(entry.getKey(), entry.getValue().getDescription()))
                .collect(Collectors.toList());
            model.addAttribute("availableMcpCommands", availableCommands);
        } else {
            model.addAttribute("availableMcpCommands", new ArrayList<McpController.McpCommandView>());
        }
        if (!model.containsAttribute("llmTextResponse")) {
             model.addAttribute("llmTextResponse", "Awaiting your command...");
        }
        if (!model.containsAttribute("mcpCommandOutput")) {
            model.addAttribute("mcpCommandOutput", "");
        }
    }
}
