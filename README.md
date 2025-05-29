# MCP 客户端 (Spring Boot)

这是一个使用 Spring Boot 构建的 MCP (模型控制程序) 客户端。它允许用户通过网页界面输入命令，与配置的 MCP 服务器和大型语言模型进行交互。

## 项目结构简介

- `src/main/java`: Java 源代码
    - `com.example.mcpclient.config`: Spring 配置类 (例如 `AppConfig` 用于 `RestTemplate`)
    - `com.example.mcpclient.controller`: Spring MVC 控制器 (例如 `McpController`)
    - `com.example.mcpclient.service`: 服务类 (例如 `McpService`, `LargeModelService`)
    - `McpClientApplication.java`: Spring Boot 主应用程序类
- `src/main/resources`: 资源文件
    - `application.properties`: 应用程序配置文件 (服务器端口，MCP 和 LLM 地址等)
    - `templates/index.html`: 前端 Thymeleaf 模板页面
- `pom.xml`: Maven 项目配置文件

## 先决条件

- Java Development Kit (JDK) 1.8 或更高版本
- Apache Maven 3.2 或更高版本

## 如何启动应用程序

1.  **克隆或下载项目**
    如果您是从 git 仓库获取项目, 请先克隆它。

2.  **配置 (可选)**
    打开 `src/main/resources/application.properties` 文件。
    您可以根据需要修改以下配置：
    - `server.port`: 应用程序运行的端口 (默认为 `8888`)
    - `mcp.server.address`: MCP 服务器的地址
    - `large.model.url`: 大型语言模型的 API 地址
    - `large.model.name`: 使用的大型模型的名称/标识
    - `large.model.key`: 访问大型语言模型 API 所需的密钥

    *注意: 当前 `McpService` 和 `LargeModelService` 中的 API 调用是占位符实现。您需要根据实际的 MCP 服务器和 LLM API 详细信息来完成这些服务的实现。*

3.  **使用 Maven 运行**
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

4.  **访问应用程序**
    启动成功后，在您的浏览器中打开以下地址：
    [http://localhost:8888](http://localhost:8888) 
    (如果您在 `application.properties` 中修改了 `server.port`，请使用相应的端口号)

## 使用方法

- 在页面上的输入框中输入您的命令。
- 点击 "Execute" (执行) 按钮。
- 页面将会显示来自大型语言模型和 MCP 服务器的响应（当前为占位符响应）。
