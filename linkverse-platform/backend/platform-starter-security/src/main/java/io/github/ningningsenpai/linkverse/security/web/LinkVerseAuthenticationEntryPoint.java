package io.github.ningningsenpai.linkverse.security.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * LinkVerseAuthenticationEntryPoint 输出不包含认证异常内部细节的中文 401 响应。
 *
 * @author ning
 * @date 2026-08-19
 */
public final class LinkVerseAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityProblemWriter problemWriter;

    public LinkVerseAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.problemWriter = new SecurityProblemWriter(objectMapper);
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException, ServletException {
        problemWriter.write(
                request,
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "SECURITY_UNAUTHORIZED",
                "身份认证失败",
                "请提供有效的访问令牌"
        );
    }
}
