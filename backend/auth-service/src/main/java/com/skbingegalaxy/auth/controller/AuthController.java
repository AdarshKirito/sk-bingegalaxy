package com.skbingegalaxy.auth.controller;

import com.skbingegalaxy.auth.dto.*;
import com.skbingegalaxy.auth.service.AuthService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Registration successful", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.ok("Login successful", response));
    }

    @PostMapping("/admin/login")
    public ResponseEntity<ApiResponse<AuthResponse>> adminLogin(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.adminLogin(request);
        return ResponseEntity.ok(ApiResponse.ok("Admin login successful", response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.ok("Token refreshed", response));
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

    @GetMapping("/admin/search-customers")
    public ResponseEntity<ApiResponse<java.util.List<UserDto>>> searchCustomers(@RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.ok(authService.searchCustomers(q)));
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
}
