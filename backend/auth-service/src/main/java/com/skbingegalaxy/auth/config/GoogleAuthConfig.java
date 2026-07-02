package com.skbingegalaxy.auth.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

@Configuration
@Slf4j
public class GoogleAuthConfig {

    @Value("${app.google.client-id:}")
    private String googleClientId;

    /**
     * Fail loud on misconfiguration: if {@code app.google.client-id} is blank the
     * verifier's audience is empty, so EVERY Google sign-in will be rejected with
     * 401. Surface that at startup so ops sees it immediately instead of debugging
     * "Google login failed" reports.
     */
    @PostConstruct
    void warnIfUnconfigured() {
        if (googleClientId == null || googleClientId.isBlank()) {
            log.warn("Google sign-in is NOT configured — 'app.google.client-id' (env GOOGLE_CLIENT_ID) is empty. "
                + "All Google logins will be rejected. Set it to the SAME OAuth Web client id the frontend uses "
                + "(VITE_GOOGLE_CLIENT_ID). Email/password sign-in is unaffected.");
        } else {
            log.info("Google sign-in configured (client-id ...{}).",
                googleClientId.length() > 8 ? googleClientId.substring(googleClientId.length() - 8) : "set");
        }
    }

    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier() {
        return new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance())
            .setAudience(Collections.singletonList(googleClientId))
            .build();
    }
}
