package com.agenttravel.backend.auth;

import java.util.UUID;

import com.agenttravel.backend.common.ApiException;
import com.agenttravel.backend.common.RequestContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Lazy;

@RestController
@Lazy
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/register")
    public AuthModels.TokenResponse register(@Valid @RequestBody AuthModels.RegisterRequest request) {
        return service.register(request);
    }

    @PostMapping("/login")
    public AuthModels.TokenResponse login(@Valid @RequestBody AuthModels.LoginRequest request) {
        return service.login(request);
    }

    @PostMapping("/refresh")
    public AuthModels.TokenResponse refresh(@Valid @RequestBody AuthModels.RefreshRequest request) {
        return service.refresh(request);
    }

    @PostMapping("/logout")
    public void logout(@Valid @RequestBody AuthModels.RefreshRequest request) {
        service.logout(requireUser(), request.refreshToken());
    }

    @GetMapping("/me")
    public AuthModels.CurrentUser me() {
        return service.currentUser(requireUser());
    }

    private UUID requireUser() {
        RequestContext.Context context = RequestContext.get();
        if (context == null || context.userId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        }
        return context.userId();
    }
}
