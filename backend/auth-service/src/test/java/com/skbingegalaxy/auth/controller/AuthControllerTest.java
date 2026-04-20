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
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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

        @Test
        void googleLogin_localhostOrigin_omitsSecureCookie() throws Exception {
                when(authService.googleLogin(anyString())).thenReturn(successResponse);

                GoogleLoginRequest request = new GoogleLoginRequest("credential-token");

                MvcResult result = mockMvc.perform(post("/api/v1/auth/google")
                                                .header("Origin", "http://localhost:3000")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andReturn();

                List<String> cookies = result.getResponse().getHeaders("Set-Cookie");

                assertThat(cookies).hasSize(2);
                assertThat(cookies).allMatch(cookie -> !cookie.contains("; Secure"));
                assertThat(cookies).allMatch(cookie -> cookie.contains("; SameSite=Strict"));
        }

        @Test
        void googleLogin_forwardedHttps_keepsSecureCookie() throws Exception {
                when(authService.googleLogin(anyString())).thenReturn(successResponse);

                GoogleLoginRequest request = new GoogleLoginRequest("credential-token");

                MvcResult result = mockMvc.perform(post("/api/v1/auth/google")
                                                .header("Origin", "https://skbingegalaxy.com")
                                                .header("X-Forwarded-Proto", "https")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andReturn();

                List<String> cookies = result.getResponse().getHeaders("Set-Cookie");

                assertThat(cookies).hasSize(2);
                assertThat(cookies).allMatch(cookie -> cookie.contains("; Secure"));
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
        void getProfile_missingHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/profile"))
                                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateAccountPreferences_success() throws Exception {
        UserDto profile = UserDto.builder()
                .id(1L)
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .preferredExperience("Birthday celebration")
                .reminderLeadDays(14)
                .notificationChannel("EMAIL")
                .receivesOffers(true)
                .weekendAlerts(true)
                .conciergeSupport(true)
                .role(UserRole.CUSTOMER)
                .active(true)
                .build();

        when(authService.updateAccountPreferences(eq(1L), any(UpdateAccountPreferencesRequest.class))).thenReturn(profile);

        mockMvc.perform(put("/api/v1/auth/profile/preferences")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  \"preferredExperience\": \"Birthday celebration\",
                                  \"reminderLeadDays\": 14,
                                                                                                                                        \"notificationChannel\": \"EMAIL\",
                                  \"receivesOffers\": true,
                                  \"weekendAlerts\": true,
                                  \"conciergeSupport\": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preferredExperience").value("Birthday celebration"));
    }

    @Test
    void getSupportContact_success() throws Exception {
        SupportContactDto supportContact = SupportContactDto.builder()
                .email("support@example.com")
                .phoneDisplay("+91 98765 43210")
                .phoneRaw("+919876543210")
                .whatsappRaw("919876543210")
                .hours("10 AM to 8 PM IST")
                .build();

        when(authService.getSupportContact()).thenReturn(supportContact);

        mockMvc.perform(get("/api/v1/auth/support-contact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("support@example.com"))
                .andExpect(jsonPath("$.data.phoneDisplay").value("+91 98765 43210"));
    }

    // ── Weak password validation tests ────────────────────

    @Test
    void register_weakPassword_noUppercase_returns400() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .firstName("John").lastName("Doe")
                .email("john@example.com").phone("9876543210")
                .password("password@123").build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_weakPassword_noSpecialChar_returns400() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .firstName("John").lastName("Doe")
                .email("john@example.com").phone("9876543210")
                .password("Password123").build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_weakPassword_tooShort_returns400() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .firstName("John").lastName("Doe")
                .email("john@example.com").phone("9876543210")
                .password("P@1a").build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ── Account lockout via controller ─────────────────────

    @Test
    void login_lockedAccount_returns429() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException("Account is temporarily locked", HttpStatus.TOO_MANY_REQUESTS));

        LoginRequest request = LoginRequest.builder()
                .email("john@example.com").password("Password@123").build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests());
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

    // ── Bulk ban endpoint ────────────────────────────────

    @Test
    void bulkBan_success_returns200() throws Exception {
        when(authService.bulkSetActive(any(), eq(false))).thenReturn(2);

        mockMvc.perform(post("/api/v1/auth/admin/bulk-ban")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1, 2]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2))
                .andExpect(jsonPath("$.message").value("2 users banned"));
    }

    // ── Bulk unban endpoint ──────────────────────────────

    @Test
    void bulkUnban_success_returns200() throws Exception {
        when(authService.bulkSetActive(any(), eq(true))).thenReturn(3);

        mockMvc.perform(post("/api/v1/auth/admin/bulk-unban")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[3, 4, 5]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(3))
                .andExpect(jsonPath("$.message").value("3 users unbanned"));
    }

    // ── Bulk delete endpoint ─────────────────────────────

    @Test
    void bulkDelete_success_returns200() throws Exception {
        when(authService.bulkDeleteUsers(any())).thenReturn(1);

        mockMvc.perform(post("/api/v1/auth/admin/bulk-delete")
                        .header("X-User-Role", "SUPER_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[10]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1))
                .andExpect(jsonPath("$.message").value("1 users deleted"));
    }

    @Test
    void bulkDelete_adminRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/bulk-delete")
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[10]"))
                .andExpect(status().isForbidden());
    }

    @Test
    void bulkBan_emptyList_returns200WithZero() throws Exception {
        when(authService.bulkSetActive(any(), eq(false))).thenReturn(0);

        mockMvc.perform(post("/api/v1/auth/admin/bulk-ban")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
    }
}
