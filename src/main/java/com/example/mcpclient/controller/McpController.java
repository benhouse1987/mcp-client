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
import org.springframework.web.bind.annotation.SessionAttributes;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

@Controller
@SessionAttributes("taskId")
public class McpController {

    private static final Logger logger = LoggerFactory.getLogger(McpController.class);

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
    public String index(Model model, HttpSession session) {
        String taskId = (String) session.getAttribute("taskId");
        if (taskId == null) {
            taskId = UUID.randomUUID().toString();
            session.setAttribute("taskId", taskId);
            logger.info("New session, generated taskId: {}", taskId);
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

    private static final int MAX_LLM_ITERATIONS = 5; // Prevent infinite loops

    @PostMapping("/execute")
    public String executeCommand(@RequestParam("command") String originalUserInput, Model model, HttpSession session) {
        String taskId = (String) session.getAttribute("taskId");
        if (taskId == null) {
            taskId = UUID.randomUUID().toString();
            session.setAttribute("taskId", taskId);
            logger.warn("taskId not found in session during executeCommand, generated new one: {}. This should ideally be set by index().", taskId);
        }
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

        for (int i = 0; i < MAX_LLM_ITERATIONS; i++) {
            logger.info("Task ID {} - LLM Iteration {}/{}...", taskId, i + 1, MAX_LLM_ITERATIONS);
            List<OpenAiChatMessage> messages = new ArrayList<>();

            // Add System Message
            String systemMessageText;
            if (i == 0) {
                systemMessageText = largeModelService.buildSystemMessageWithTools();
            } else {
                systemMessageText = largeModelService.buildFollowUpSystemMessage(
                    originalUserInput,
                    (String) model.getAttribute("lastExecutedToolName"),
                    (Map<String, String>) model.getAttribute("lastExecutedToolParams"),
                    (String) model.getAttribute("lastToolOutput")
                );
            }
            messages.add(new OpenAiChatMessage("system", systemMessageText));

            // Populate with Historical Messages from DB
            // The 'userMessage' is the ChatMessage object for the current 'originalUserInput'.
            // 'taskHistory' includes 'userMessage' if it was just saved.
            for (ChatMessage historicalMessage : taskHistory) {
                // If this is the first iteration (i=0) AND the historical message is the userMessage
                // (i.e., the current input just saved), skip it here. It will be added explicitly later.
                if (i == 0 && historicalMessage.getId().equals(userMessage.getId())) {
                    continue;
                }

                if (historicalMessage.getUserInput() != null && !historicalMessage.getUserInput().isEmpty()) {
                    messages.add(new OpenAiChatMessage("user", historicalMessage.getUserInput()));
                }
                if (historicalMessage.getLlmResponse() != null && !historicalMessage.getLlmResponse().isEmpty()) {
                    messages.add(new OpenAiChatMessage("assistant", historicalMessage.getLlmResponse()));
                }
                if (historicalMessage.getMcpCommand() != null && !historicalMessage.getMcpCommand().isEmpty()) {
                    String toolCallJson = String.format("{ \"tool_calls\": [{\"type\": \"function\", \"function\": {\"name\": \"%s\", \"arguments\": \"%s\"}}]}",
                                                        extractToolName(historicalMessage.getMcpCommand()),
                                                        extractToolArguments(historicalMessage.getMcpCommand()).replace("\"", "\\\""));
                    messages.add(new OpenAiChatMessage("assistant", toolCallJson ));
                }
                if (historicalMessage.getMcpCommandOutput() != null && !historicalMessage.getMcpCommandOutput().isEmpty()) {
                    String toolNameForOutput = (historicalMessage.getMcpCommand() != null && !historicalMessage.getMcpCommand().isEmpty())
                                               ? extractToolName(historicalMessage.getMcpCommand()) : "unknown_tool";
                    // Using "system" role for tool output as a simpler alternative to "tool" role without full tool_call_id handling.
                    // OpenAI generally expects "tool" role for tool outputs, along with a "tool_call_id".
                    // String toolOutputJson = String.format("{\"tool_call_id\": \"not_available\", \"role\": \"tool\", \"name\": \"%s\", \"content\": \"%s\"}",
                    //                                     toolNameForOutput,
                    //                                     historicalMessage.getMcpCommandOutput().replace("\"", "\\\""));
                    // messages.add(new OpenAiChatMessage("tool", toolOutputJson)); // Correct role if using tool_call_id
                     messages.add(new OpenAiChatMessage("system", "Tool output for " + toolNameForOutput + ": " + historicalMessage.getMcpCommandOutput().replace("\"", "\\\"")));
                }
            }

            // Add Current User Input for the first iteration of this /execute call
            if (i == 0) {
                messages.add(new OpenAiChatMessage("user", originalUserInput));
            }
            // For i > 0, the "user" query is effectively the context from history + the follow-up system message.
            // If the API strictly needs a user message in every turn, a placeholder can be added:
            // else if (messages.get(messages.size()-1).getRole().equals("assistant")) {
            //    messages.add(new OpenAiChatMessage("user", "OK."));
            // }

            logger.debug("Task ID {} - Iteration {} - Messages prepared for LLM (roles): {}", taskId, i + 1, messages.stream().map(OpenAiChatMessage::getRole).collect(Collectors.joining(", ")));
            if (messages.isEmpty() || (messages.size()==1 && messages.get(0).getRole().equals("system") && i > 0) ) {
                 logger.warn("Task ID {} - Iteration {} - No meaningful messages (beyond system) to send to LLM. Breaking loop.", taskId, i + 1);
                 // This might happen if history is empty and it's not the first iteration.
                 // Or if the last message was an assistant action and no explicit user follow-up is constructed.
                 // Adding a generic user prompt to allow assistant to continue if it has more to do.
                 if (i > 0 && (llmResponse != null && llmResponse.getToolToUse() != null)) { // If LLM wanted to use a tool
                    messages.add(new OpenAiChatMessage("user", "Continue based on the tool's output."));
                 } else if (i > 0) {
                    // If LLM just gave a text response, and we are in a follow-up iteration without new user input
                    // messages.add(new OpenAiChatMessage("user", "What is the next step?")); // or break
                 } else if (messages.isEmpty() && i == 0) { // Should not happen if originalUserInput is always added for i=0
                    logger.error("Task ID {} - Iteration 0 - No messages including initial user input. Critical error.", taskId);
                    break;
                 }
            }
            if (messages.size() == 1 && messages.get(0).getRole().equals("system")) {
                // Avoid sending only a system message if there's no other content, unless it's the very first message.
                // This check is a bit tricky; depends on how LLM handles system-only messages after initial context.
                if (i > 0) { // only system message and it's not the first iteration.
                     logger.warn("Task ID {} - Iteration {} - Only a system message. Adding a generic user prompt.", taskId, i+1);
                     messages.add(new OpenAiChatMessage("user", "Please proceed."));
                } else if (taskHistory.isEmpty() && originalUserInput.trim().isEmpty()){ // First iteration, but no user input
                    logger.warn("Task ID {} - Iteration 0 - Only system message and no user input. Aborting.", taskId);
                    model.addAttribute("llmTextResponse", "Error: No input provided.");
                    break;
                }
            }


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

                model.addAttribute("lastExecutedToolName", toolName);
                model.addAttribute("lastExecutedToolParams", toolParameters);
                model.addAttribute("lastToolOutput", mcpOutput);

                if (i == MAX_LLM_ITERATIONS - 1) {
                    conversationHistoryForDisplay.add("Max iterations reached. Ending conversation.");
                }
            } else {
                logger.info("LLM provided a final response or no tool was called. Ending interaction loop.");
                model.addAttribute("conversationHistory", conversationHistoryForDisplay);
                break;
            }
        }

        model.addAttribute("conversationHistory", conversationHistoryForDisplay);
        addDefaultModelAttributes(model);
        return "index";
    }

    @GetMapping("/new_task")
    public String newTask(HttpSession session) {
        String newTaskId = UUID.randomUUID().toString();
        session.setAttribute("taskId", newTaskId);
        logger.info("New task started by user. New taskId: {}", newTaskId);
        return "redirect:/";
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
