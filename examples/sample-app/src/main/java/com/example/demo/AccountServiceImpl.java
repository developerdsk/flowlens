package com.example.demo;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;

    public AccountServiceImpl(AccountRepository accountRepository, AuditService auditService,
                              ApplicationEventPublisher events) {
        this.accountRepository = accountRepository;
        this.auditService = auditService;
        this.events = events;
    }

    @Override
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
        events.publishEvent(new AccountCreatedEvent(saved.getId(), saved.getEmail()));
        return saved;
    }

    @Override
    public Account find(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("no account " + id));
    }

    @Override
    public void changeStatus(Long id, String status) {
        accountRepository.updateStatus(id, status);
        auditService.record("account.status", id);
    }

    @Override
    public void close(Long id) {
        Account account = find(id);
        if ("CLOSED".equals(account.getStatus())) {
            return;
        }
        account.setStatus("CLOSED");
        accountRepository.save(account);
        auditService.record("account.closed", id);
    }
}
