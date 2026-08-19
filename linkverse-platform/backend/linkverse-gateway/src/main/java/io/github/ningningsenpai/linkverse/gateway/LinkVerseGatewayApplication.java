package io.github.ningningsenpai.linkverse.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LinkVerseGatewayApplication 启动 LinkVerse 统一入口与边缘安全服务。
 *
 * @author ning
 * @date 2026-08-19
 */
@SpringBootApplication
public class LinkVerseGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkVerseGatewayApplication.class, args);
    }
}
