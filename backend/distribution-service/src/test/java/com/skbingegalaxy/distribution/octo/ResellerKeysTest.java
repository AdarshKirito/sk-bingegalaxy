package com.skbingegalaxy.distribution.octo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key a reseller presents to reach a venue.
 *
 * <p>It is the only thing standing between a third-party system and a venue's inventory,
 * and it is issued by us rather than agreed with anyone — so the properties below are the
 * whole of its security.
 */
@DisplayName("Reseller key issuance")
class ResellerKeysTest {

    @Test
    @DisplayName("issues a prefixed, high-entropy key with a matching digest")
    void issuesAUsableKey() {
        ResellerKeys.IssuedKey issued = ResellerKeys.issue();

        assertThat(issued.plaintext()).startsWith(ResellerKeys.PREFIX);
        // 32 random bytes, base64url without padding, plus the prefix.
        assertThat(issued.plaintext().length())
            .isGreaterThanOrEqualTo(ResellerKeys.PREFIX.length() + 43);
        assertThat(issued.hash()).isEqualTo(ResellerKeys.sha256Hex(issued.plaintext()));
        assertThat(issued.hash()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the stored digest cannot reproduce the key")
    void digestIsNotReversible() {
        ResellerKeys.IssuedKey issued = ResellerKeys.issue();

        // Stated as a test because it is the entire argument for storing a hash rather
        // than a reference: a dump of distribution_db must yield nothing presentable.
        assertThat(issued.hash()).doesNotContain(issued.plaintext());
        assertThat(issued.plaintext()).doesNotContain(issued.hash());
    }

    @Test
    @DisplayName("the hint identifies a key without narrowing it")
    void hintRevealsOnlyTheTail() {
        ResellerKeys.IssuedKey issued = ResellerKeys.issue();

        assertThat(issued.hint()).hasSize(8).startsWith("••••");
        assertThat(issued.plaintext()).endsWith(issued.hint().substring(4));
    }

    @Test
    @DisplayName("two issued keys never collide")
    void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(ResellerKeys.issue().plaintext());
        }
        // A collision would make one reseller's key resolve to another venue's
        // connection — the unique index would reject the write, but only after the
        // operator had been shown a key that cannot be saved.
        assertThat(seen).hasSize(500);
    }

    @Test
    @DisplayName("verification accepts the right key and refuses everything else")
    void verification() {
        ResellerKeys.IssuedKey issued = ResellerKeys.issue();

        assertThat(ResellerKeys.matches(issued.plaintext(), issued.hash())).isTrue();
        assertThat(ResellerKeys.matches(issued.plaintext() + "x", issued.hash())).isFalse();
        assertThat(ResellerKeys.matches("", issued.hash())).isFalse();
        // Null must read as "refused", never as "nothing to check".
        assertThat(ResellerKeys.matches(null, issued.hash())).isFalse();
        assertThat(ResellerKeys.matches(issued.plaintext(), null)).isFalse();
    }
}
