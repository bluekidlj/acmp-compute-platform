package com.acmp.compute;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI Compute Platform 启动类。
 * 显卡资源管理与任务调度平台。
 */
@SpringBootApplication
@MapperScan("com.acmp.compute.mapper")
public class AcmpComputeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AcmpComputeApplication.class, args);
    }
}
