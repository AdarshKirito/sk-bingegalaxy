package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.dto.*;
import com.skbingegalaxy.auth.entity.PasswordResetToken;
import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.auth.repository.PasswordResetTokenRepository;
import com.skbingegalaxy.auth.repository.UserRepository;
import com.skbingegalaxy.auth.security.JwtProvider;
import com.skbingegalaxy.common.enums.UserRole;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.DuplicateResourceException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository resetTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtProvider jwtProvider;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks private AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "otpExpirationMinutes", 10);
        ReflectionTestUtils.setField(authService, "otpLength", 6);
                ReflectionTestUtils.setField(authService, "supportEmail", "support@example.com");
                ReflectionTestUtils.setField(authService, "supportPhone", "9876543210");
                ReflectionTestUtils.setField(authService, "supportHours", "10 AM to 8 PM IST");

        testUser = User.builder()
                .id(1L)
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .phone("9876543210")
                .password("encodedPassword")
                .role(UserRole.CUSTOMER)
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ── Register tests ────────────────────────────────────

    @Test
    void register_success() {
        RegisterRequest request = RegisterRequest.builder()
                .firstName("John").lastName("Doe")
                .email("john@example.com").phone("9876543210")
                .password("Password@123").build();

        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByPhone(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        when(jwtProvider.generateToken(any(User.class))).thenReturn("jwt-token");
        when(jwtProvider.generateRefreshToken(any(User.class))).thenReturn("refresh-token");

        AuthResponse response = authService.register(request);

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(response.getUser().getEmail()).isEqualTo("john@example.com");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_duplicateEmail_throwsException() {
        RegisterRequest request = RegisterRequest.builder()
                .email("john@example.com").phone("9876543210").build();

        when(userRepository.existsByEmail("john@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void register_duplicatePhone_throwsException() {
        RegisterRequest request = RegisterRequest.builder()
                .email("john@example.com").phone("9876543210").build();

        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByPhone("9876543210")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // ── Login tests ───────────────────────────────────────

    @Test
    void login_success() {
        LoginRequest request = LoginRequest.builder()
                .email("john@example.com").password("Password@123").build();

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("Password@123", "encodedPassword")).thenReturn(true);
        when(jwtProvider.generateToken(testUser)).thenReturn("jwt-token");
        when(jwtProvider.generateRefreshToken(testUser)).thenReturn("refresh-token");

        AuthResponse response = authService.login(request);

        assertThat(response.getToken()).isEqualTo("jwt-token");
    }

    @Test
    void login_invalidEmail_throwsException() {
        LoginRequest request = LoginRequest.builder()
                .email("wrong@example.com").password("Password@123").build();

        when(userRepository.findByEmail("wrong@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void login_wrongPassword_throwsException() {
        LoginRequest request = LoginRequest.builder()
                .email("john@example.com").password("WrongPass").build();

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("WrongPass", "encodedPassword")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void login_deactivatedAccount_throwsException() {
        testUser.setActive(false);
        LoginRequest request = LoginRequest.builder()
                .email("john@example.com").password("Password@123").build();

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(testUser));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("deactivated");
    }

    // ── Admin login ───────────────────────────────────────

    @Test
    void adminLogin_success() {
        testUser.setRole(UserRole.ADMIN);
        LoginRequest request = LoginRequest.builder()
                .email("admin@example.com").password("AdminPass").build();

        when(userRepository.findByEmail("admin@example.com"))
                .thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("AdminPass", "encodedPassword")).thenReturn(true);
        when(jwtProvider.generateToken(testUser)).thenReturn("admin-token");
        when(jwtProvider.generateRefreshToken(testUser)).thenReturn("admin-refresh");

        AuthResponse response = authService.adminLogin(request);
        assertThat(response.getToken()).isEqualTo("admin-token");
    }

    @Test
    void adminLogin_notAdmin_throwsException() {
        testUser.setRole(UserRole.CUSTOMER);
        LoginRequest request = LoginRequest.builder()
                .email("user@example.com").password("pass").build();

        when(userRepository.findByEmail("user@example.com"))
                .thenReturn(Optional.of(testUser));

        assertThatThrownBy(() -> authService.adminLogin(request))
                .isInstanceOf(BusinessException.class);
    }

    // ── Forgot / Reset password ──────────────────────────

    @Test
    void forgotPassword_success() {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        ReflectionTestUtils.setField(request, "email", "john@example.com");

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(testUser));
        when(resetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(i -> i.getArgument(0));

        authService.forgotPassword(request);

        verify(resetTokenRepository).save(any(PasswordResetToken.class));
    }

    @Test
    void forgotPassword_unknownEmail_returnsSilently() {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        ReflectionTestUtils.setField(request, "email", "unknown@example.com");

        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        // Should not throw — prevents email enumeration
        authService.forgotPassword(request);

        verify(userRepository).findByEmail("unknown@example.com");
        verifyNoMoreInteractions(resetTokenRepository);
    }

    // ── Get profile ──────────────────────────────────────

    @Test
    void getUserProfile_success() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        UserDto dto = authService.getUserProfile(1L);

        assertThat(dto.getEmail()).isEqualTo("john@example.com");
        assertThat(dto.getFirstName()).isEqualTo("John");
    }

        @Test
        void getUserProfile_legacyPreferenceNulls_defaultsApplied() {
                testUser.setReminderLeadDays(null);
                testUser.setNotificationChannel(null);
                testUser.setReceivesOffers(null);
                testUser.setWeekendAlerts(null);
                testUser.setConciergeSupport(null);
                when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

                UserDto dto = authService.getUserProfile(1L);

                assertThat(dto.getReminderLeadDays()).isEqualTo(14);
                assertThat(dto.getNotificationChannel()).isEqualTo("EMAIL");
                assertThat(dto.isReceivesOffers()).isTrue();
                assertThat(dto.isWeekendAlerts()).isTrue();
                assertThat(dto.isConciergeSupport()).isTrue();
        }

    @Test
    void getUserProfile_notFound_throwsException() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.getUserProfile(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

        @Test
        void updateAccountPreferences_missingDayForMonth_throwsException() {
                UpdateAccountPreferencesRequest request = UpdateAccountPreferencesRequest.builder()
                                .birthdayMonth("July")
                                .build();

                when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

                assertThatThrownBy(() -> authService.updateAccountPreferences(1L, request))
                                .isInstanceOf(BusinessException.class)
                                .hasMessageContaining("Birthday reminders need both month and day");
        }

        @Test
        void getSupportContact_returnsConfiguredSupportDetails() {
                SupportContactDto supportContact = authService.getSupportContact();

                assertThat(supportContact.getEmail()).isEqualTo("support@example.com");
                assertThat(supportContact.getPhoneDisplay()).isEqualTo("+91 98765 43210");
                assertThat(supportContact.getPhoneRaw()).isEqualTo("+919876543210");
                assertThat(supportContact.getWhatsappRaw()).isEqualTo("919876543210");
                assertThat(supportContact.getHours()).isEqualTo("10 AM to 8 PM IST");
        }

    // ── Account lockout tests ────────────────────────────

    @Test
    void login_lockedAccount_throwsTooManyRequests() {
        testUser.lockAccount(15);
        LoginRequest request = LoginRequest.builder()
                .email("john@example.com").password("Password@123").build();

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(testUser));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("temporarily locked");
    }

    @Test
    void login_wrongPassword_incrementsFailedAttempts() {
        LoginRequest request = LoginRequest.builder()
                .email("john@example.com").password("WrongPass").build();

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("WrongPass", "encodedPassword")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class);
        assertThat(testUser.getFailedLoginAttempts()).isEqualTo(1);
        verify(userRepository).save(testUser);
    }

    @Test
    void login_fiveWrongPasswords_locksAccount() {
        testUser.setFailedLoginAttempts(4); // already 4 failed attempts
        LoginRequest request = LoginRequest.builder()
                .email("john@example.com").password("WrongPass").build();

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("WrongPass", "encodedPassword")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class);
        assertThat(testUser.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(testUser.getLockedUntil()).isNotNull();
        assertThat(testUser.isAccountLocked()).isTrue();
    }

    @Test
    void login_success_resetsFailedAttempts() {
        testUser.setFailedLoginAttempts(3);
        LoginRequest request = LoginRequest.builder()
                .email("john@example.com").password("Password@123").build();

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("Password@123", "encodedPassword")).thenReturn(true);
        when(jwtProvider.generateToken(testUser)).thenReturn("jwt-token");
        when(jwtProvider.generateRefreshToken(testUser)).thenReturn("refresh-token");

        authService.login(request);

        assertThat(testUser.getFailedLoginAttempts()).isEqualTo(0);
        assertThat(testUser.getLockedUntil()).isNull();
    }

    @Test
    void login_expiredLockout_allowsLogin() {
        testUser.setLockedUntil(LocalDateTime.now().minusMinutes(1)); // lockout expired
        LoginRequest request = LoginRequest.builder()
                .email("john@example.com").password("Password@123").build();

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("Password@123", "encodedPassword")).thenReturn(true);
        when(jwtProvider.generateToken(testUser)).thenReturn("jwt-token");
        when(jwtProvider.generateRefreshToken(testUser)).thenReturn("refresh-token");

        AuthResponse response = authService.login(request);

        assertThat(response.getToken()).isEqualTo("jwt-token");
    }

    @Test
    void adminLogin_lockedAccount_throwsTooManyRequests() {
        testUser.setRole(UserRole.ADMIN);
        testUser.lockAccount(15);
        LoginRequest request = LoginRequest.builder()
                .email("admin@example.com").password("AdminPass").build();

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(testUser));

        assertThatThrownBy(() -> authService.adminLogin(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("temporarily locked");
    }

    @Test
    void adminLogin_wrongPassword_incrementsAndLocks() {
        testUser.setRole(UserRole.ADMIN);
        testUser.setFailedLoginAttempts(4);
        LoginRequest request = LoginRequest.builder()
                .email("admin@example.com").password("WrongPass").build();

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("WrongPass", "encodedPassword")).thenReturn(false);

        assertThatThrownBy(() -> authService.adminLogin(request))
                .isInstanceOf(BusinessException.class);
        assertThat(testUser.isAccountLocked()).isTrue();
    }

    // ── OTP brute-force protection tests ────────────────────

    @Test
    void verifyOtp_success() {
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token("valid-token")
                .otp("123456")
                .user(testUser)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        VerifyOtpRequest request = new VerifyOtpRequest();
        ReflectionTestUtils.setField(request, "token", "valid-token");
        ReflectionTestUtils.setField(request, "otp", "123456");
        ReflectionTestUtils.setField(request, "newPassword", "NewPass@123");

        when(resetTokenRepository.findByTokenAndUsedFalse("valid-token")).thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("NewPass@123")).thenReturn("encodedNew");

        authService.verifyOtpAndResetPassword(request);

        verify(userRepository).save(testUser);
        verify(resetTokenRepository).markAllUnusedAsUsedForUser(testUser.getId());
    }

    @Test
    void verifyOtp_wrongOtp_incrementsAttempts() {
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token("valid-token")
                .otp("123456")
                .user(testUser)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        VerifyOtpRequest request = new VerifyOtpRequest();
        ReflectionTestUtils.setField(request, "token", "valid-token");
        ReflectionTestUtils.setField(request, "otp", "999999");
        ReflectionTestUtils.setField(request, "newPassword", "NewPass@123");

        when(resetTokenRepository.findByTokenAndUsedFalse("valid-token")).thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> authService.verifyOtpAndResetPassword(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid OTP");
        assertThat(resetToken.getOtpAttempts()).isEqualTo(1);
        verify(resetTokenRepository).save(resetToken);
    }

    @Test
    void verifyOtp_exhaustedAttempts_invalidatesToken() {
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token("valid-token")
                .otp("123456")
                .user(testUser)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        // Set to max attempts
        for (int i = 0; i < 5; i++) resetToken.incrementOtpAttempts();

        VerifyOtpRequest request = new VerifyOtpRequest();
        ReflectionTestUtils.setField(request, "token", "valid-token");
        ReflectionTestUtils.setField(request, "otp", "000000");
        ReflectionTestUtils.setField(request, "newPassword", "NewPass@123");

        when(resetTokenRepository.findByTokenAndUsedFalse("valid-token")).thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> authService.verifyOtpAndResetPassword(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many incorrect OTP attempts");
        assertThat(resetToken.isUsed()).isTrue();
    }

    @Test
    void verifyOtp_missingToken_throwsException() {
        VerifyOtpRequest request = new VerifyOtpRequest();
        ReflectionTestUtils.setField(request, "token", "");
        ReflectionTestUtils.setField(request, "otp", "123456");
        ReflectionTestUtils.setField(request, "newPassword", "NewPass@123");

        assertThatThrownBy(() -> authService.verifyOtpAndResetPassword(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Reset token is required");
    }

    @Test
    void verifyOtp_expiredToken_throwsException() {
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token("expired-token")
                .otp("123456")
                .user(testUser)
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();

        VerifyOtpRequest request = new VerifyOtpRequest();
        ReflectionTestUtils.setField(request, "token", "expired-token");
        ReflectionTestUtils.setField(request, "otp", "123456");
        ReflectionTestUtils.setField(request, "newPassword", "NewPass@123");

        when(resetTokenRepository.findByTokenAndUsedFalse("expired-token")).thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> authService.verifyOtpAndResetPassword(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
    }

        @Test
        void updateAccountPreferences_success() {
                UpdateAccountPreferencesRequest request = UpdateAccountPreferencesRequest.builder()
                                .preferredExperience("Anniversary")
                                .vibePreference("Quiet and romantic")
                                .reminderLeadDays(21)
                                .birthdayMonth("March")
                                .birthdayDay(19)
                                .anniversaryMonth("December")
                                .anniversaryDay(9)
                                .notificationChannel("EMAIL")
                                .receivesOffers(false)
                                .weekendAlerts(true)
                                .conciergeSupport(false)
                                .build();

                when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
                when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

                UserDto dto = authService.updateAccountPreferences(1L, request);

                assertThat(dto.getPreferredExperience()).isEqualTo("Anniversary");
                assertThat(dto.getVibePreference()).isEqualTo("Quiet and romantic");
                assertThat(dto.getReminderLeadDays()).isEqualTo(21);
                assertThat(dto.getBirthdayMonth()).isEqualTo("March");
                assertThat(dto.getBirthdayDay()).isEqualTo(19);
                assertThat(dto.getAnniversaryMonth()).isEqualTo("December");
                assertThat(dto.getAnniversaryDay()).isEqualTo(9);
                assertThat(dto.getNotificationChannel()).isEqualTo("EMAIL");
                assertThat(dto.isReceivesOffers()).isFalse();
                assertThat(dto.isWeekendAlerts()).isTrue();
                assertThat(dto.isConciergeSupport()).isFalse();
        }
}
