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
    void forgotPassword_unknownEmail_throwsException() {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        ReflectionTestUtils.setField(request, "email", "unknown@example.com");

        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.forgotPassword(request))
                .isInstanceOf(ResourceNotFoundException.class);
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
                assertThat(dto.getNotificationChannel()).isEqualTo("WHATSAPP");
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
        void getSupportContact_returnsConfiguredSupportDetails() {
                SupportContactDto supportContact = authService.getSupportContact();

                assertThat(supportContact.getEmail()).isEqualTo("support@example.com");
                assertThat(supportContact.getPhoneDisplay()).isEqualTo("+91 98765 43210");
                assertThat(supportContact.getPhoneRaw()).isEqualTo("+919876543210");
                assertThat(supportContact.getWhatsappRaw()).isEqualTo("919876543210");
                assertThat(supportContact.getHours()).isEqualTo("10 AM to 8 PM IST");
        }

        @Test
        void updateAccountPreferences_success() {
                UpdateAccountPreferencesRequest request = UpdateAccountPreferencesRequest.builder()
                                .preferredExperience("Anniversary")
                                .vibePreference("Quiet and romantic")
                                .reminderLeadDays(21)
                                .birthdayMonth("March")
                                .anniversaryMonth("December")
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
                assertThat(dto.getAnniversaryMonth()).isEqualTo("December");
                assertThat(dto.getNotificationChannel()).isEqualTo("EMAIL");
                assertThat(dto.isReceivesOffers()).isFalse();
                assertThat(dto.isWeekendAlerts()).isTrue();
                assertThat(dto.isConciergeSupport()).isFalse();
        }
}
