package com.skbingegalaxy.auth.repository;

import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.common.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByPhone(String phone);
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
    Optional<User> findByEmailAndRole(String email, UserRole role);

    @Query("SELECT u FROM User u WHERE u.role = 'CUSTOMER' AND (" +
           "LOWER(u.firstName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(u.lastName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "u.phone LIKE CONCAT('%', :q, '%'))")
    List<User> searchCustomers(@Param("q") String query);

    @Query("SELECT u FROM User u WHERE u.role = 'CUSTOMER' ORDER BY u.firstName, u.lastName")
    List<User> findAllCustomers();
}
