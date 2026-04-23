package com.skbingegalaxy.auth.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    private String token;
    private String refreshToken;
    private UserDto user;

    /**
     * When true, the password was correct but the account has MFA enabled and the caller
     * must submit a TOTP code. {@code token} and {@code refreshToken} will be null in this
     * case. The client should re-submit the credentials with an {@code mfaCode} populated.
     */
    private boolean mfaRequired;

    /** Short-lived opaque challenge id returned alongside {@link #mfaRequired}. */
    private String mfaChallengeId;

    /** Hint to the client when additional verification is needed (e.g. EMAIL_NOT_VERIFIED). */
    private String challengeType;
}
