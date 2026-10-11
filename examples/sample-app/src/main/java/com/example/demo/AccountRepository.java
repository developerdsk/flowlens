package com.example.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    boolean existsByEmail(String email);

    Account findByEmail(String email);

    @Modifying
    @Query("update Account a set a.status = :status where a.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    void deleteByStatus(String status);
}
