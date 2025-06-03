package com.example.mcpclient.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.example.mcpclient.mapper")
public class MyBatisPlusConfig {
}
