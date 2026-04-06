package com.skbingegalaxy.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.auth.dto.*;
import com.skbingegalaxy.auth.service.AuthService;
import com.skbingegalaxy.common.enums.UserRole;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.DuplicateResourceException;
import com.skbingegalaxy.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private AuthService authService;

    private AuthResponse successResponse;

    @BeforeEach
    void setUp() {
        successResponse = AuthResponse.builder()
                .token("jwt-token")
                .refreshToken("refresh-token")
                .user(UserDto.builder()
                        .id(1L).firstName("John").lastName("Doe")
                        .email("john@example.com").phone("9876543210")
                        .role(UserRole.CUSTOMER).active(true)
                        .createdAt(LocalDateTime.now()).build())
                .build();
    }

    // ── Register endpoint ────────────────────────────────

    @Test
    void register_success_returns201() throws Exception {
        when(authService.register(any(RegisterRequest.class))).thenReturn(successResponse);

        RegisterRequest request = RegisterRequest.builder()
                .firstName("John").lastName("Doe")
                .email("john@example.com").phone("9876543210")
                .password("Password@123").build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.token").value("jwt-token"))
                .andExpect(jsonPath("$.data.user.email").value("john@example.com"));
    }

    @Test
    void register_invalidEmail_returns400() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .firstName("John").lastName("Doe")
                .email("invalid-email").phone("9876543210")
                .password("Password@123").build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_missingFields_returns400() throws Exception {
        RegisterRequest request = RegisterRequest.builder().build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_duplicateEmail_throwsConflict() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new DuplicateResourceException("User", "email", "john@example.com"));

        RegisterRequest request = RegisterRequest.builder()
                .firstName("John").lastName("Doe")
                .email("john@example.com").phone("9876543210")
                .password("Password@123").build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    // ── Login endpoint ───────────────────────────────────

    @Test
    void login_success_returns200() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(successResponse);

        LoginRequest request = LoginRequest.builder()
                .email("john@example.com").password("Password@123").build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("jwt-token"));
    }

    @Test
    void login_invalidCredentials_returns401() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException("Invalid email or password", HttpStatus.UNAUTHORIZED));

        LoginRequest request = LoginRequest.builder()
                .email("john@example.com").password("WrongPass1").build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_deactivatedAccount_returns403() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException("Account is deactivated. Contact support.", HttpStatus.FORBIDDEN));

        LoginRequest request = LoginRequest.builder()
                .email("john@example.com").password("Password@123").build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // ── Admin login endpoint ─────────────────────────────

    @Test
    void adminLogin_success_returns200() throws Exception {
        successResponse.getUser().setRole(UserRole.ADMIN);
        when(authService.adminLogin(any(LoginRequest.class))).thenReturn(successResponse);

        LoginRequest request = LoginRequest.builder()
                .email("admin@example.com").password("AdminPass1").build();

        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("jwt-token"));
    }

    // ── Refresh token endpoint ───────────────────────────

    @Test
    void refreshToken_success() throws Exception {
        when(authService.refreshToken("valid-refresh-token")).thenReturn(successResponse);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"valid-refresh-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("jwt-token"));
    }

    @Test
        void refreshToken_missing_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                                .andExpect(status().isUnauthorized());
    }

    // ── Forgot password endpoint ─────────────────────────

    @Test
    void forgotPassword_success() throws Exception {
        doNothing().when(authService).forgotPassword(any(ForgotPasswordRequest.class));

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"john@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password reset instructions sent to your email"));
    }

    // ── Get profile endpoint ─────────────────────────────

    @Test
    void getProfile_success() throws Exception {
        UserDto profile = UserDto.builder()
                .id(1L).firstName("John").lastName("Doe")
                .email("john@example.com").role(UserRole.CUSTOMER).active(true).build();

        when(authService.getUserProfile(1L)).thenReturn(profile);

        mockMvc.perform(get("/api/v1/auth/profile")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("john@example.com"));
    }

    @Test
    void getProfile_missingHeader_returns500() throws Exception {
        mockMvc.perform(get("/api/v1/auth/profile"))
                .andExpect(status().isInternalServerError());
    }

    // ── Reset password endpoint ──────────────────────────

    @Test
    void resetPassword_success() throws Exception {
        doNothing().when(authService).resetPassword(any(ResetPasswordRequest.class));

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"reset-token\",\"newPassword\":\"NewPass@123\"}"))
                .andExpect(status().isOk());
    }
}
