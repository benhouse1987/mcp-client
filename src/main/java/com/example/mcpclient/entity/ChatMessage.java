package com.example.mcpclient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.type.JdbcType;

import java.time.LocalDateTime;

@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("task_id")
    private String taskId;

    @TableField(value = "user_input", jdbcType = JdbcType.LONGVARCHAR)
    private String userInput;

    @TableField(value = "llm_response", jdbcType = JdbcType.LONGVARCHAR)
    private String llmResponse;

    @TableField("mcp_command")
    private String mcpCommand;

    @TableField(value = "mcp_command_output", jdbcType = JdbcType.LONGVARCHAR)
    private String mcpCommandOutput;

    @TableField("timestamp")
    private LocalDateTime timestamp;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getUserInput() {
        return userInput;
    }

    public void setUserInput(String userInput) {
        this.userInput = userInput;
    }

    public String getLlmResponse() {
        return llmResponse;
    }

    public void setLlmResponse(String llmResponse) {
        this.llmResponse = llmResponse;
    }

    public String getMcpCommand() {
        return mcpCommand;
    }

    public void setMcpCommand(String mcpCommand) {
        this.mcpCommand = mcpCommand;
    }

    public String getMcpCommandOutput() {
        return mcpCommandOutput;
    }

    public void setMcpCommandOutput(String mcpCommandOutput) {
        this.mcpCommandOutput = mcpCommandOutput;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
