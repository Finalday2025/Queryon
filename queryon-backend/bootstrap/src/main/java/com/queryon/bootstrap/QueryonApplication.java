package com.queryon.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口。组件扫描覆盖 com.queryon 下各模块。
 */
@SpringBootApplication(scanBasePackages = "com.queryon")
public class QueryonApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueryonApplication.class, args);
    }
}
