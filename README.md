# MCP 客户端 (Spring Boot)

这是一个使用 Spring Boot 构建的 MCP (模型控制程序) 客户端。它允许用户通过网页界面用自然语言输入指令。这些指令会由大型语言模型 (LLM) 解析，该模型可以决定是直接回复用户，还是指示应用程序执行一个在本地配置好的命令 (MCP 工具) 并返回结果。

## 项目结构简介

- `src/main/java`: Java 源代码
    - `com.example.mcpclient.config`: Spring 配置类 (例如 `AppConfig` 用于 `RestTemplate`, `ObjectMapper`)
    - `com.example.mcpclient.controller`: Spring MVC 控制器 (例如 `McpController`)
    - `com.example.mcpclient.service`: 服务类 (例如 `McpService` 用于执行本地命令, `LargeModelService` 用于与LLM交互)
    - `com.example.mcpclient.dto`: 数据传输对象 (用于 LLM 交互和 MCP 配置)
    - `McpClientApplication.java`: Spring Boot 主应用程序类
- `src/main/resources`: 资源文件
    - `application.properties`: 应用程序配置文件 (服务器端口, LLM API 地址等)
    - `mcp_servers.json`: MCP 本地命令配置文件
    - `templates/index.html`: 前端 Thymeleaf 模板页面
- `pom.xml`: Maven 项目配置文件

## 先决条件

- Java Development Kit (JDK) 1.8 或更高版本
- Apache Maven 3.2 或更高版本

## 如何启动应用程序

1.  **克隆或下载项目**
    如果您是从 git 仓库获取项目, 请先克隆它。

2.  **配置**
    打开 `src/main/resources/application.properties` 文件。
    您必须配置以下项才能与大语言模型交互：
    - `large.model.url`: 大型语言模型的 API 地址 (例如: `https://api.openai.com/v1/chat/completions`)
    - `large.model.name`: 使用的大型模型的名称/标识 (例如: `gpt-4o`)
    - `large.model.key`: 访问大型语言模型 API 所需的密钥 (例如: `YOUR_OPENAI_API_KEY_HERE`)
    
    您可以根据需要修改以下可选配置：
    - `server.port`: 应用程序运行的端口 (默认为 `8888`)

    *注意: 原先用于配置远程 MCP 服务器地址的 `mcp.server.address` 属性现已不再使用，因为此版本通过 `mcp_servers.json` 文件执行本地命令。*

3.  **配置 MCP 本地命令 (`mcp_servers.json`)**
    此版本的 MCP 客户端通过执行本地配置的命令来工作。这些本地命令在 `src/main/resources/mcp_servers.json` 文件中定义。
    默认配置包含一些示例命令。

    **`mcp_servers.json` 文件结构示例:**
    ```json
    {
      "mcpServers": {
        "get_current_date_os": {
          "description": "获取当前系统日期（使用操作系统命令）。",
          "command": "date",
          "args": [],
          "workingDirectory": null
        },
        "list_directory_contents": {
          "description": "列出指定目录中的文件和文件夹。",
          "command": "ls",
          "args_template": ["-la", "{directory_path}"],
          "workingDirectory": null
        }
        // ...更多命令...
      }
    }
    ```

    **字段说明:**
    -   `description`: 命令功能的描述，会提供给大语言模型以帮助其决策。
    -   `command`: 要执行的程序名或命令 (例如: `python`, `ls`, `date`, `mkdir`)。
    -   `args`: 固定的参数列表 (可选)。
    -   `args_template`: 参数模板列表，其中可包含占位符 (例如 `{directory_path}`)。大语言模型将提供这些占位符的值。
    -   `workingDirectory`: 命令执行的工作目录 (可选, `null` 表示使用项目默认工作目录)。

    **添加新命令:**
    若要添加新的本地命令，请按照上述格式编辑 `src/main/resources/mcp_servers.json` 文件，在 `mcpServers` 对象中添加新的条目。确保提供清晰的 `description` 以便大语言模型能够理解和使用您的命令。例如，要添加一个执行 Python 脚本的命令:
    ```json
    "get_current_time_python": {
      "description": "获取当前系统时间 (使用 Python 脚本)。需要 'python' 在系统 PATH 中，并且项目根目录有名为 'mcp_server_time_script.py' 的脚本。",
      "command": "python",
      "args": ["mcp_server_time_script.py"],
      "workingDirectory": null 
    }
    ```
    *(确保对应的脚本 `mcp_server_time_script.py` 存在于项目根目录并能正确执行。)*


4.  **使用 Maven 运行**
    在项目的根目录下，打开命令行或终端，然后执行以下 Maven 命令：
    ```bash
    mvn spring-boot:run
    ```
    或者，如果您想先构建 JAR 包再运行：
    ```bash
    mvn clean package
    java -jar target/mcp-client-0.0.1-SNAPSHOT.jar 
    ```
    *(请将 `mcp-client-0.0.1-SNAPSHOT.jar` 替换为 `target` 目录下实际生成的 JAR 文件名)*

5.  **访问应用程序**
    启动成功后，在您的浏览器中打开以下地址：
    [http://localhost:8888](http://localhost:8888) 
    (如果您在 `application.properties` 中修改了 `server.port`，请使用相应的端口号)

## 使用方法

1.  **启动应用程序** (方法同上)。
2.  **访问应用程序** (方法同上)。
3.  **输入指令:**
    在页面输入框中输入您的自然语言指令 (例如：“现在几点了？” 或 “列出 /tmp 目录的内容”)。
4.  **执行与响应:**
    -   您输入的指令会首先发送给配置的大语言模型 (例如 OpenAI GPT-4o)。
    -   大语言模型会分析您的请求。
        -   如果模型认为需要执行一个本地配置的命令（在 `mcp_servers.json` 中定义）来满足您的请求，它会指示应用程序执行该命令，并可能提供必要的参数（例如目录路径）。应用程序随后会执行此本地命令并显示其输出。
        -   如果模型可以直接回答您的问题，或者没有合适的本地命令，它会直接生成文本回复。
    -   页面将显示大语言模型的文本回复以及（如果执行了）本地命令的名称和输出。
```
