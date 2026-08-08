package com.example.demo;

import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AuditService auditService;

    public AccountService(AccountRepository accountRepository, AuditService auditService) {
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }

    public Account create(CreateAccountRequest request) {
        if (accountRepository.existsByEmail(request.getEmail())) {
            throw new IllegalStateException("email already registered");
        }

        Account account = new Account();
        account.setEmail(request.getEmail());
        account.setName(request.getName());

        if (request.getName() != null && request.getName().length() > 2) {
            account.setStatus("ACTIVE");
        } else {
            account.setStatus("PENDING");
        }

        Account saved = accountRepository.save(account);
        auditService.record("account.created", saved.getId());
        return saved;
    }
}
