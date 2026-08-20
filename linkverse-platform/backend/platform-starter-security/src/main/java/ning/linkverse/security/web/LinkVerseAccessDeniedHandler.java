package ning.linkverse.security.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * LinkVerseAccessDeniedHandler 输出不暴露授权规则内部细节的中文 403 响应。
 *
 * @author ning
 * @date 2026-08-19
 */
public final class LinkVerseAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityProblemWriter problemWriter;

    public LinkVerseAccessDeniedHandler(ObjectMapper objectMapper) {
        this.problemWriter = new SecurityProblemWriter(objectMapper);
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        problemWriter.write(
                request,
                response,
                HttpServletResponse.SC_FORBIDDEN,
                "SECURITY_FORBIDDEN",
                "访问被拒绝",
                "当前身份无权访问该资源"
        );
    }
}
