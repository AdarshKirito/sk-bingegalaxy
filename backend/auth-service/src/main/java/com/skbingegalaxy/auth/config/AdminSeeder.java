package com.skbingegalaxy.auth.config;

import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.auth.repository.UserRepository;
import com.skbingegalaxy.common.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email}")
    private String adminEmail;

    @Value("${app.admin.password}")
    private String adminPassword;

    @Value("${app.admin.name}")
    private String adminName;

    @Value("${app.admin.phone}")
    private String adminPhone;

    @Override
    public void run(String... args) {
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.error("Admin seed skipped — app.admin.email and app.admin.password must be configured");
            return;
        }        // Enforce minimum strength on the seeded super-admin password so a
        // weak ADMIN_PASSWORD env value can't quietly create a trivially
        // brute-forceable god account. Same policy applied to user signups.
        if (!isStrongPassword(adminPassword)) {
            log.error("Admin seed aborted \u2014 ADMIN_PASSWORD must be \u226512 chars and include upper, lower, digit, and special character");
            return;
        }        var existing = userRepository.findByEmail(adminEmail);
        if (existing.isPresent()) {
            User admin = existing.get();
            if (admin.getRole() != UserRole.SUPER_ADMIN) {
                admin.setRole(UserRole.SUPER_ADMIN);
                userRepository.save(admin);
                log.info("Admin user upgraded to SUPER_ADMIN: {}", adminEmail);
            } else {
                log.info("Super admin already exists, skipping seed: {}", adminEmail);
            }
        } else {
            String[] names = adminName.split(" ", 2);
            User admin = User.builder()
                .firstName(names[0])
                .lastName(names.length > 1 ? names[1] : "")
                .email(adminEmail)
                .phone(adminPhone)
                .password(passwordEncoder.encode(adminPassword))
                .role(UserRole.SUPER_ADMIN)
                .active(true)
                .build();
            userRepository.save(admin);
            log.info("Super admin seeded: {}", adminEmail);
        }
    }

    private static boolean isStrongPassword(String pw) {
        if (pw == null || pw.length() < 12) return false;
        boolean upper = false, lower = false, digit = false, special = false;
        for (int i = 0; i < pw.length(); i++) {
            char c = pw.charAt(i);
            if (Character.isUpperCase(c)) upper = true;
            else if (Character.isLowerCase(c)) lower = true;
            else if (Character.isDigit(c)) digit = true;
            else if (!Character.isWhitespace(c)) special = true;
        }
        return upper && lower && digit && special;
    }
}
