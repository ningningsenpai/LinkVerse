package io.github.ningningsenpai.linkverse.identity.api;

import io.github.ningningsenpai.linkverse.identity.application.IssuedAccessToken;
import io.github.ningningsenpai.linkverse.identity.application.LoginCommand;
import io.github.ningningsenpai.linkverse.identity.application.LoginService;
import io.github.ningningsenpai.linkverse.identity.application.RegisterUserCommand;
import io.github.ningningsenpai.linkverse.identity.application.RegisterUserResult;
import io.github.ningningsenpai.linkverse.identity.application.RegisterUserService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AuthController 暴露 MVP 用户注册与第一方登录协议入口。
 *
 * @author ning
 * @date 2026-08-19
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegisterUserService registerUserService;
    private final LoginService loginService;

    public AuthController(RegisterUserService registerUserService, LoginService loginService) {
        this.registerUserService = registerUserService;
        this.loginService = loginService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterUserResult result = registerUserService.register(
                new RegisterUserCommand(request.username(), request.password())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(
                result.userId(),
                result.username(),
                result.createdAt()
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        IssuedAccessToken token = loginService.login(new LoginCommand(request.username(), request.password()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new LoginResponse(token.tokenValue(), "Bearer", token.expiresInSeconds()));
    }
}
