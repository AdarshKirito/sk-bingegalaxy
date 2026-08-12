package com.skbingegalaxy.distribution.dto;

import lombok.Builder;
import lombok.Data;

/**
 * A marketplace a connection can be pointed at.
 *
 * <p><b>Why this read had to exist.</b> Attaching a destination is the step between
 * creating a connection and having a channel that can sell, and the endpoint for it took
 * a {@code destinationCode} — a value the console had no way to learn. There was no
 * catalogue read anywhere, so the only way to complete a connection was to already know
 * the codes and post them by hand.
 *
 * <p>Only what the console needs to offer a real choice. The commercial terms are the
 * operator's decision and are supplied on the way in, not read from here.
 */
@Data
@Builder
public class DestinationDto {

    private String code;
    private String displayName;

    /**
     * The provider that operates this destination. A connection may only reach
     * destinations its own provider operates — a Bókun connection reaching Viator is
     * legitimate and handled by capability rows; a Viator connection reaching
     * GetYourGuide is not — so the console filters on this rather than offering choices
     * the server will refuse.
     */
    private String operatedByProviderCode;

    /**
     * FALSE for a feed-only destination such as Google Things to Do: a traveller lands on
     * SK Binge and checks out here, so no reservation is ever delivered back. Surfaced so
     * the console can say so at the moment of choosing, rather than leaving an operator
     * watching an inbox that will never receive anything.
     */
    private boolean deliversReservations;
}
