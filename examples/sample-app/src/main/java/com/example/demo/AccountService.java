package com.example.demo;

public interface AccountService {

    Account create(CreateAccountRequest request);

    Account find(Long id);

    void changeStatus(Long id, String status);

    void close(Long id);
}
