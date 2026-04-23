package com.skbingegalaxy.auth.controller;

import com.skbingegalaxy.auth.dto.*;
import com.skbingegalaxy.auth.service.AuthService;
import com.skbingegalaxy.auth.service.TokenRevocationService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;
    private final TokenRevocationService tokenRevocationService;

    @Value("${app.jwt.expiration-ms:3600000}")
    private long jwtExpirationMs;

    @Value("${app.jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    @Value("${app.cookie.secure:true}")
    private boolean cookieSecure;

    @Value("${app.cookie.domain:}")
    private String cookieDomain;

    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AuthResponse>> googleLogin(@Valid @RequestBody GoogleLoginRequest request,
                                                                  HttpServletRequest httpRequest,
                                                                  HttpServletResponse response) {
        AuthResponse authResponse = authService.googleLogin(request.getCredential());
        setAuthCookies(httpRequest, response, authResponse);
        return ResponseEntity.ok(ApiResponse.ok("Google login successful", authResponse));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request,
                                                               HttpServletRequest httpRequest,
                                                               HttpServletResponse response) {
        AuthResponse authResponse = authService.register(request);
        setAuthCookies(httpRequest, response, authResponse);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Registration successful", authResponse));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
                                                            HttpServletRequest httpRequest,
                                                            HttpServletResponse response) {
        AuthResponse authResponse = authService.login(request);
        setAuthCookies(httpRequest, response, authResponse);
        return ResponseEntity.ok(ApiResponse.ok("Login successful", authResponse));
    }

    @PostMapping("/admin/login")
    public ResponseEntity<ApiResponse<AuthResponse>> adminLogin(@Valid @RequestBody LoginRequest request,
                                                                 HttpServletRequest httpRequest,
                                                                 HttpServletResponse response) {
        AuthResponse authResponse = authService.adminLogin(request);
        setAuthCookies(httpRequest, response, authResponse);
        return ResponseEntity.ok(ApiResponse.ok("Admin login successful", authResponse));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refreshToken(
            @CookieValue(name = "refreshToken", required = false) String cookieRefreshToken,
            @RequestBody(required = false) RefreshTokenRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        String rt = cookieRefreshToken != null ? cookieRefreshToken
                   : (request != null ? request.getRefreshToken() : null);
        if (rt == null || rt.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Missing refresh token"));
        }
        AuthResponse authResponse = authService.refreshToken(rt);
        setAuthCookies(httpRequest, response, authResponse);
        return ResponseEntity.ok(ApiResponse.ok("Token refreshed", authResponse));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = "refreshToken", required = false) String cookieRefreshToken,
            @RequestHeader(name = "Authorization", required = false) String authHeader,
            HttpServletResponse response) {
        // Revoke the refresh token so it can't be replayed against /auth/refresh.
        if (cookieRefreshToken != null && !cookieRefreshToken.isBlank()) {
            tokenRevocationService.revoke(cookieRefreshToken);
        }
        // Access tokens usually live for ~15 minutes and are picked up by a
        // gateway-side denylist in a follow-up; revoking on logout here
        // costs one row and prepares for that rollout.
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            tokenRevocationService.revoke(authHeader.substring(7));
        }
        clearAuthCookies(response);
        return ResponseEntity.ok(ApiResponse.ok("Logged out", null));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(ApiResponse.ok("Password reset instructions sent to your email", null));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.ok("Password reset successful", null));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<Void>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        authService.verifyOtpAndResetPassword(request);
        return ResponseEntity.ok(ApiResponse.ok("Password reset via OTP successful", null));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserDto>> getProfile(@RequestHeader("X-User-Id") Long userId) {
        UserDto profile = authService.getUserProfile(userId);
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    @PutMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(userId, request);
        return ResponseEntity.ok(ApiResponse.ok("Password changed successfully", null));
    }

    @PutMapping("/profile/preferences")
    public ResponseEntity<ApiResponse<UserDto>> updateAccountPreferences(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody UpdateAccountPreferencesRequest request) {
        UserDto updated = authService.updateAccountPreferences(userId, request);
        return ResponseEntity.ok(ApiResponse.ok("Account preferences updated", updated));
    }

    @GetMapping("/support-contact")
    public ResponseEntity<ApiResponse<SupportContactDto>> getSupportContact() {
        return ResponseEntity.ok(ApiResponse.ok(authService.getSupportContact()));
    }

    @PutMapping("/complete-profile")
    public ResponseEntity<ApiResponse<AuthResponse>> completeProfile(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CompleteProfileRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        AuthResponse authResponse = authService.completeProfile(userId, request.getPhone());
        setAuthCookies(httpRequest, response, authResponse);
        return ResponseEntity.ok(ApiResponse.ok("Profile updated", authResponse));
    }

    @GetMapping("/admin/search-customers")
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<UserDto>>> searchCustomers(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, Math.min(size, 200));
        return ResponseEntity.ok(ApiResponse.ok(authService.searchCustomers(q, pageable)));
    }

    @GetMapping("/admin/customers")
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<UserDto>>> getAllCustomers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, Math.min(size, 200));
        return ResponseEntity.ok(ApiResponse.ok(authService.getAllCustomers(pageable)));
    }

    @GetMapping("/admin/customer/{id}")
    public ResponseEntity<ApiResponse<UserDto>> getCustomerById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(authService.getCustomerById(id)));
    }

    @PutMapping("/admin/customer/{id}")
    public ResponseEntity<ApiResponse<UserDto>> updateCustomer(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCustomerRequest request) {
        UserDto user = authService.adminUpdateCustomer(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Customer updated", user));
    }

    @PostMapping("/admin/create-customer")
    public ResponseEntity<ApiResponse<UserDto>> adminCreateCustomer(@Valid @RequestBody AdminCreateCustomerRequest request) {
        UserDto user = authService.adminCreateCustomer(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Customer created", user));
    }

    @PostMapping("/admin/register")
    public ResponseEntity<ApiResponse<AuthResponse>> adminRegister(@Valid @RequestBody RegisterRequest request,
                                                                    HttpServletRequest httpRequest,
                                                                    HttpServletResponse response) {
        AuthResponse authResponse = authService.adminRegister(request);
        // Do NOT call setAuthCookies — that would overwrite the super-admin session with the new admin's tokens
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Admin registration successful", authResponse));
    }

    @GetMapping("/admin/admins")
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<UserDto>>> getAllAdmins(
            @org.springframework.data.web.PageableDefault(size = 20) org.springframework.data.domain.Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(authService.getAllAdmins(pageable)));
    }

    @PutMapping("/admin/admins/{id}")
    public ResponseEntity<ApiResponse<UserDto>> updateAdmin(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCustomerRequest request) {
        UserDto user = authService.updateAdmin(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Admin updated", user));
    }

    @DeleteMapping("/admin/user/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long id) {
        authService.deleteUser(id);
        return ResponseEntity.ok(ApiResponse.ok("User deleted", null));
    }

    @PostMapping("/admin/bulk-ban")
    public ResponseEntity<ApiResponse<Integer>> bulkBan(@RequestBody java.util.List<Long> userIds) {
        int count = authService.bulkSetActive(userIds, false);
        return ResponseEntity.ok(ApiResponse.ok(count + " users banned", count));
    }

    @PostMapping("/admin/bulk-unban")
    public ResponseEntity<ApiResponse<Integer>> bulkUnban(@RequestBody java.util.List<Long> userIds) {
        int count = authService.bulkSetActive(userIds, true);
        return ResponseEntity.ok(ApiResponse.ok(count + " users unbanned", count));
    }

    @PostMapping("/admin/bulk-delete")
    public ResponseEntity<ApiResponse<Integer>> bulkDelete(
            @RequestBody java.util.List<Long> userIds,
            @RequestHeader("X-User-Role") String role) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only super admins can bulk delete users", HttpStatus.FORBIDDEN);
        }
        int count = authService.bulkDeleteUsers(userIds);
        return ResponseEntity.ok(ApiResponse.ok(count + " users deleted", count));
    }

    // ── Cookie helpers ───────────────────────────────────────
    // Use Set-Cookie header directly to properly set SameSite attribute
    // (Cookie.setAttribute("SameSite",...) is not honoured by all containers)
    private void setAuthCookies(HttpServletRequest request, HttpServletResponse response, AuthResponse authResponse) {
        boolean useSecureCookies = shouldUseSecureCookies(request);
        response.addHeader("Set-Cookie", buildCookieHeader(
            "token", authResponse.getToken(), "/", (int) (jwtExpirationMs / 1000), useSecureCookies));
        response.addHeader("Set-Cookie", buildCookieHeader(
            "refreshToken", authResponse.getRefreshToken(), "/api/v1/auth/refresh", (int) (refreshExpirationMs / 1000), useSecureCookies));
    }

    private void clearAuthCookies(HttpServletResponse response) {
        response.addHeader("Set-Cookie", buildCookieHeader("token", "", "/", 0, false));
        response.addHeader("Set-Cookie", buildCookieHeader("refreshToken", "", "/api/v1/auth/refresh", 0, false));
    }

    private String buildCookieHeader(String name, String value, String path, int maxAge, boolean secureCookie) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('=').append(value);
        sb.append("; Path=").append(path);
        sb.append("; Max-Age=").append(maxAge);
        sb.append("; HttpOnly");
        sb.append("; SameSite=Strict");
        if (secureCookie) {
            sb.append("; Secure");
        }
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            if (!cookieDomain.matches("^[a-zA-Z0-9.-]+$")) {
                throw new IllegalArgumentException("Invalid cookie domain configuration");
            }
            sb.append("; Domain=").append(cookieDomain);
        }
        return sb.toString();
    }

    private boolean shouldUseSecureCookies(HttpServletRequest request) {
        if (!cookieSecure) {
            return false;
        }
        if (isHttpsRequest(request)) {
            return true;
        }
        return !isLocalDevelopmentRequest(request);
    }

    private boolean isHttpsRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }

        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && "https".equalsIgnoreCase(forwardedProto)) {
            return true;
        }

        String forwardedSsl = request.getHeader("X-Forwarded-Ssl");
        return forwardedSsl != null && "on".equalsIgnoreCase(forwardedSsl);
    }

    private boolean isLocalDevelopmentRequest(HttpServletRequest request) {
        return isLocalHost(request.getServerName())
            || isLocalHost(request.getHeader("Host"))
            || isLocalHost(request.getHeader("X-Forwarded-Host"))
            || isLocalHost(extractHost(request.getHeader("Origin")))
            || isLocalHost(extractHost(request.getHeader("Referer")));
    }

    private boolean isLocalHost(String hostValue) {
        if (hostValue == null || hostValue.isBlank()) {
            return false;
        }

        String normalized = hostValue.trim();
        int schemeSeparator = normalized.indexOf("://");
        if (schemeSeparator >= 0) {
            normalized = normalized.substring(schemeSeparator + 3);
        }
        int slashIndex = normalized.indexOf('/');
        if (slashIndex >= 0) {
            normalized = normalized.substring(0, slashIndex);
        }
        if (normalized.startsWith("[") && normalized.contains("]")) {
            normalized = normalized.substring(1, normalized.indexOf(']'));
        } else {
            int portSeparator = normalized.indexOf(':');
            if (portSeparator >= 0) {
                normalized = normalized.substring(0, portSeparator);
            }
        }

        return "localhost".equalsIgnoreCase(normalized)
            || normalized.endsWith(".localhost")
            || "127.0.0.1".equals(normalized)
            || "0.0.0.0".equals(normalized)
            || "::1".equals(normalized);
    }

    private String extractHost(String urlValue) {
        if (urlValue == null || urlValue.isBlank()) {
            return null;
        }

        String normalized = urlValue.trim();
        int schemeSeparator = normalized.indexOf("://");
        if (schemeSeparator >= 0) {
            normalized = normalized.substring(schemeSeparator + 3);
        }
        int slashIndex = normalized.indexOf('/');
        return slashIndex >= 0 ? normalized.substring(0, slashIndex) : normalized;
    }
}
