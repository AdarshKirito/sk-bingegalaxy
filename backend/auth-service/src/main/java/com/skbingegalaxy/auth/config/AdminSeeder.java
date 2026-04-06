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
        var existing = userRepository.findByEmail(adminEmail);
        if (existing.isPresent()) {
            User admin = existing.get();
            boolean changed = false;
            if (admin.getRole() != UserRole.SUPER_ADMIN) {
                admin.setRole(UserRole.SUPER_ADMIN);
                changed = true;
                log.info("Admin user upgraded to SUPER_ADMIN: {}", adminEmail);
            }
            // Always sync password from config
            admin.setPassword(passwordEncoder.encode(adminPassword));
            changed = true;
            if (changed) {
                userRepository.save(admin);
            }
            log.info("Super admin synced: {}", adminEmail);
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
}
