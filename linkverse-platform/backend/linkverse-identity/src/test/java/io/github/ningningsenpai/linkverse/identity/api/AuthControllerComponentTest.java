package io.github.ningningsenpai.linkverse.identity.api;

import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.ningningsenpai.linkverse.core.error.PlatformException;
import io.github.ningningsenpai.linkverse.identity.application.IssuedAccessToken;
import io.github.ningningsenpai.linkverse.identity.application.LoginCommand;
import io.github.ningningsenpai.linkverse.identity.application.LoginService;
import io.github.ningningsenpai.linkverse.identity.application.RegisterUserCommand;
import io.github.ningningsenpai.linkverse.identity.application.RegisterUserResult;
import io.github.ningningsenpai.linkverse.identity.application.RegisterUserService;
import io.github.ningningsenpai.linkverse.identity.domain.IdentityErrorCode;
import io.github.ningningsenpai.linkverse.web.error.PlatformProblemExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthControllerComponentTest 验证注册、登录和统一 ProblemDetail 的 HTTP 契约。
 *
 * @author ning
 * @date 2026-08-19
 */
class AuthControllerComponentTest {

    private RegisterUserService registerUserService;
    private LoginService loginService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registerUserService = mock(RegisterUserService.class);
        loginService = mock(LoginService.class);
        MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .build()
        );
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(registerUserService, loginService))
                .setControllerAdvice(new PlatformProblemExceptionHandler())
                .setMessageConverters(jsonConverter)
                .build();
    }

    @Test
    void shouldRegisterUserWithoutReturningCredentialData() throws Exception {
        when(registerUserService.register(new RegisterUserCommand("Alice", "a-secure-password")))
                .thenReturn(new RegisterUserResult(
                        42L,
                        "Alice",
                        Instant.parse("2026-08-19T10:15:30Z")
                ));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"Alice","password":"a-secure-password"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.user_id").value(42))
                .andExpect(jsonPath("$.username").value("Alice"))
                .andExpect(jsonPath("$.created_at").value("2026-08-19T10:15:30Z"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.password_hash").doesNotExist());
    }

    @Test
    void shouldReturnNoStoreHeadersForLoginToken() throws Exception {
        when(loginService.login(new LoginCommand("alice", "a-secure-password")))
                .thenReturn(new IssuedAccessToken("signed-user-token", 900));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"a-secure-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.access_token").value("signed-user-token"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(900));
    }

    @Test
    void shouldReturnSafeProblemDetailForInvalidCredentials() throws Exception {
        when(loginService.login(new LoginCommand("alice", "wrong-password")))
                .thenThrow(new PlatformException(IdentityErrorCode.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("用户名或密码错误"));
    }

    @Test
    void shouldRejectMalformedRegisterRequestBeforeApplicationService() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("请求参数校验失败"));
    }
}
