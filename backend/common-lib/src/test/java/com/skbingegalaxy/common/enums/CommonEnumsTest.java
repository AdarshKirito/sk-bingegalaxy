package com.skbingegalaxy.common.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommonEnumsTest {

    @Test
    void userRoles_areStable() {
        assertArrayEquals(new UserRole[]{
                UserRole.CUSTOMER,
                UserRole.ADMIN,
                UserRole.SUPER_ADMIN
        }, UserRole.values());
    }

    @Test
    void paymentMethods_areStable() {
        assertArrayEquals(new PaymentMethod[]{
                PaymentMethod.UPI,
                PaymentMethod.CARD,
                PaymentMethod.BANK_TRANSFER,
                PaymentMethod.WALLET,
                PaymentMethod.CASH
        }, PaymentMethod.values());
    }

    @Test
    void bookingStatuses_areStable() {
        assertArrayEquals(new BookingStatus[]{
                BookingStatus.PENDING,
                BookingStatus.CONFIRMED,
                BookingStatus.CHECKED_IN,
                BookingStatus.COMPLETED,
                BookingStatus.CANCELLED,
                BookingStatus.NO_SHOW
        }, BookingStatus.values());
    }

    @Test
    void paymentStatuses_areStable() {
        assertArrayEquals(new PaymentStatus[]{
                PaymentStatus.PENDING,
                PaymentStatus.INITIATED,
                PaymentStatus.SUCCESS,
                PaymentStatus.FAILED,
                PaymentStatus.REFUNDED,
                PaymentStatus.PARTIALLY_REFUNDED,
                PaymentStatus.PARTIALLY_PAID,
                PaymentStatus.DISPUTED
        }, PaymentStatus.values());
    }

    @Test
    void notificationChannels_areStable() {
        assertArrayEquals(new NotificationChannel[]{
                NotificationChannel.EMAIL,
                NotificationChannel.SMS,
                NotificationChannel.WHATSAPP,
                NotificationChannel.PUSH
        }, NotificationChannel.values());
    }

    @Test
    void authorityScopes_parseCaseAndWhitespace() {
        assertArrayEquals(new AuthorityScope[]{
                AuthorityScope.CURRENCIES,
                AuthorityScope.NOTIFICATIONS,
                AuthorityScope.LOYALTY,
                AuthorityScope.OPS,
                AuthorityScope.ALL_USERS,
                AuthorityScope.CUSTOMER_EDIT,
                AuthorityScope.ADMIN_REGISTER,
                AuthorityScope.HOME_CMS,
                AuthorityScope.ACCOUNT_CMS,
                AuthorityScope.SUPER_DASHBOARD
        }, AuthorityScope.values());

        assertEquals(AuthorityScope.ALL_USERS, AuthorityScope.fromString(" all_users "));
        assertNull(AuthorityScope.fromString(null));
        assertNull(AuthorityScope.fromString("not-a-scope"));
    }
}
