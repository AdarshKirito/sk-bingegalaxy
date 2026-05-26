package com.skbingegalaxy.booking.entity;

/**
 * Lifecycle for venue rooms. SUPER_ADMIN-created rooms are auto-APPROVED;
 * rooms created by a regular ADMIN start as PENDING_APPROVAL and only
 * become bookable once a SUPER_ADMIN approves them. REJECTED rooms are
 * kept (for audit) but blocked at booking time.
 *
 * Mirrors the BingeApprovalStatus pattern established in V33.
 */
public enum RoomApprovalStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED
}
