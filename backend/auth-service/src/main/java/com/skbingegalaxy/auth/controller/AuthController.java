package com.skbingegalaxy.auth.controller;

import com.skbingegalaxy.auth.dto.*;
import com.skbingegalaxy.auth.service.AuthService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

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
                                                                  HttpServletResponse response) {
        AuthResponse authResponse = authService.googleLogin(request.getCredential());
        setAuthCookies(response, authResponse);
        return ResponseEntity.ok(ApiResponse.ok("Google login successful", authResponse));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request,
                                                               HttpServletResponse response) {
        AuthResponse authResponse = authService.register(request);
        setAuthCookies(response, authResponse);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Registration successful", authResponse));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
                                                            HttpServletResponse response) {
        AuthResponse authResponse = authService.login(request);
        setAuthCookies(response, authResponse);
        return ResponseEntity.ok(ApiResponse.ok("Login successful", authResponse));
    }

    @PostMapping("/admin/login")
    public ResponseEntity<ApiResponse<AuthResponse>> adminLogin(@Valid @RequestBody LoginRequest request,
                                                                 HttpServletResponse response) {
        AuthResponse authResponse = authService.adminLogin(request);
        setAuthCookies(response, authResponse);
        return ResponseEntity.ok(ApiResponse.ok("Admin login successful", authResponse));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refreshToken(
            @CookieValue(name = "refreshToken", required = false) String cookieRefreshToken,
            @RequestBody(required = false) RefreshTokenRequest request,
            HttpServletResponse response) {
        String rt = cookieRefreshToken != null ? cookieRefreshToken
                   : (request != null ? request.getRefreshToken() : null);
        if (rt == null || rt.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Missing refresh token"));
        }
        AuthResponse authResponse = authService.refreshToken(rt);
        setAuthCookies(response, authResponse);
        return ResponseEntity.ok(ApiResponse.ok("Token refreshed", authResponse));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletResponse response) {
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

    @PutMapping("/complete-profile")
    public ResponseEntity<ApiResponse<UserDto>> completeProfile(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CompleteProfileRequest request) {
        UserDto updated = authService.completeProfile(userId, request.getPhone());
        return ResponseEntity.ok(ApiResponse.ok("Profile updated", updated));
    }

    @GetMapping("/admin/search-customers")
    public ResponseEntity<ApiResponse<java.util.List<UserDto>>> searchCustomers(@RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.ok(authService.searchCustomers(q)));
    }

    @GetMapping("/admin/customers")
    public ResponseEntity<ApiResponse<java.util.List<UserDto>>> getAllCustomers() {
        return ResponseEntity.ok(ApiResponse.ok(authService.getAllCustomers()));
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
    public ResponseEntity<ApiResponse<UserDto>> adminCreateCustomer(@Valid @RequestBody RegisterRequest request) {
        UserDto user = authService.adminCreateCustomer(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Customer created", user));
    }

    @PostMapping("/admin/register")
    public ResponseEntity<ApiResponse<AuthResponse>> adminRegister(@Valid @RequestBody RegisterRequest request,
                                                                    HttpServletResponse response) {
        AuthResponse authResponse = authService.adminRegister(request);
        setAuthCookies(response, authResponse);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Admin registration successful", authResponse));
    }

    @GetMapping("/admin/admins")
    public ResponseEntity<ApiResponse<java.util.List<UserDto>>> getAllAdmins() {
        return ResponseEntity.ok(ApiResponse.ok(authService.getAllAdmins()));
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

    // ── Cookie helpers ───────────────────────────────────────
    private void setAuthCookies(HttpServletResponse response, AuthResponse authResponse) {
        Cookie accessCookie = new Cookie("token", authResponse.getToken());
        accessCookie.setHttpOnly(true);
        accessCookie.setSecure(cookieSecure);
        accessCookie.setPath("/");
        accessCookie.setMaxAge((int) (jwtExpirationMs / 1000));
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            accessCookie.setDomain(cookieDomain);
        }
        accessCookie.setAttribute("SameSite", "Strict");
        response.addCookie(accessCookie);

        Cookie refreshCookie = new Cookie("refreshToken", authResponse.getRefreshToken());
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(cookieSecure);
        refreshCookie.setPath("/api/v1/auth/refresh");
        refreshCookie.setMaxAge((int) (refreshExpirationMs / 1000));
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            refreshCookie.setDomain(cookieDomain);
        }
        refreshCookie.setAttribute("SameSite", "Strict");
        response.addCookie(refreshCookie);
    }

    private void clearAuthCookies(HttpServletResponse response) {
        Cookie accessCookie = new Cookie("token", "");
        accessCookie.setHttpOnly(true);
        accessCookie.setSecure(cookieSecure);
        accessCookie.setPath("/");
        accessCookie.setMaxAge(0);
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            accessCookie.setDomain(cookieDomain);
        }
        accessCookie.setAttribute("SameSite", "Strict");
        response.addCookie(accessCookie);

        Cookie refreshCookie = new Cookie("refreshToken", "");
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(cookieSecure);
        refreshCookie.setPath("/api/v1/auth/refresh");
        refreshCookie.setMaxAge(0);
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            refreshCookie.setDomain(cookieDomain);
        }
        refreshCookie.setAttribute("SameSite", "Strict");
        response.addCookie(refreshCookie);
    }
}
