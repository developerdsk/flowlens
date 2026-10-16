package com.example.demo;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DormantAccountCleanup {

    private final AccountRepository accountRepository;

    public DormantAccountCleanup(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /** Every night at 03:00: purge accounts that were closed long ago. */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeClosedAccounts() {
        accountRepository.deleteByStatus("CLOSED");
    }
}
