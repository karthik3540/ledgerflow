package com.karthik.ledgerflow.service;

import com.karthik.ledgerflow.dto.AccountResponse;
import com.karthik.ledgerflow.dto.CreateAccountRequest;
import com.karthik.ledgerflow.model.Account;
import com.karthik.ledgerflow.repository.AccountRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Service layer for account operations.
 * Keeps persistence logic out of the controller and provides a
 * transactional boundary for each operation.
 */
@Service
public class AccountService implements LedgerService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Creates a new account with the given owner name and starting balance.
     *
     * @param request validated creation request
     * @return the persisted account as a response DTO
     */
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        Account account = new Account();
        account.setOwnerName(request.ownerName());
        account.setBalance(request.initialBalance());
        Account saved = accountRepository.save(account);
        return AccountResponse.from(saved);
    }

    /**
     * Fetches an account by its UUID.
     *
     * @param id account UUID
     * @return an Optional containing the account response, or empty if not found
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "accounts", key = "#id")
    public Optional<AccountResponse> getAccount(UUID id) {
        return accountRepository.findById(id).map(AccountResponse::from);
    }
}
