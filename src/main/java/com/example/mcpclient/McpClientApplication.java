package com.example.mcpclient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class McpClientApplication {

    public static void main(String[] args) {
        // Set HTTPS proxy properties
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", "7890");

        // It's also common to set HTTP proxy settings if needed, for example:
        // System.setProperty("http.proxyHost", "127.0.0.1");
        // System.setProperty("http.proxyPort", "7890");
        // And non-proxy hosts:
        // System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1");
        // System.setProperty("https.nonProxyHosts", "localhost|127.0.0.1");

        SpringApplication.run(McpClientApplication.class, args);
    }

}
