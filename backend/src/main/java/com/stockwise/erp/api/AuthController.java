package com.stockwise.erp.api;

import com.stockwise.erp.api.ApiModels.LoginRequest;
import com.stockwise.erp.auth.ErpUser;
import com.stockwise.erp.auth.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtService jwt;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwords, JwtService jwt) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.jwt = jwt;
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        var users = jdbc.queryForList("""
                SELECT id,organization_id,full_name,email,password_hash,role
                FROM app_users WHERE lower(email)=lower(?) AND active=true
                """, request.email().trim());
        if (users.size() != 1 || !passwords.matches(request.password(), (String) users.getFirst().get("password_hash")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect");
        var row = users.getFirst();
        ErpUser user = new ErpUser(((Number) row.get("id")).longValue(), ((Number) row.get("organization_id")).longValue(),
                (String) row.get("full_name"), (String) row.get("email"), (String) row.get("role"));
        return Map.of("accessToken", jwt.issue(user), "tokenType", "Bearer", "user", profile(user));
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(@AuthenticationPrincipal ErpUser user) { return profile(user); }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "ok", "service", "stockwise-erp"); }

    private Map<String, Object> profile(ErpUser user) {
        return Map.of("id", user.id(), "organizationId", user.organizationId(), "name", user.fullName(), "email", user.email(), "role", user.role());
    }
}
