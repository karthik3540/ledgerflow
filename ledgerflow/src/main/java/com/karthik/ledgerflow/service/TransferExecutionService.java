package com.karthik.ledgerflow.service;

import com.karthik.ledgerflow.dto.TransferRequest;
import com.karthik.ledgerflow.dto.TransferResult;
import com.karthik.ledgerflow.exception.InsufficientFundsException;
import com.karthik.ledgerflow.model.Account;
import com.karthik.ledgerflow.model.EntryType;
import com.karthik.ledgerflow.model.LedgerEntry;
import com.karthik.ledgerflow.repository.AccountRepository;
import com.karthik.ledgerflow.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Service responsible for executing the locked transfer within a single database transaction.
 */
@Service
public class TransferExecutionService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * Optional test hook invoked immediately after row locks are acquired
     * and before balances are verified or updated.
     */
    private volatile Runnable postLockHook;

    public TransferExecutionService(AccountRepository accountRepository,
                                    LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository     = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * Sets or clears a test hook executed immediately after acquiring locks.
     *
     * @param postLockHook the runnable to execute, or null to clear
     */
    public void setPostLockHook(Runnable postLockHook) {
        this.postLockHook = postLockHook;
    }

    /**
     * Executes the financial transfer with row-level locks acquired in ascending UUID order.
     *
     * @param request validated transfer request
     * @return completed transfer result
     */
    @Transactional
    public TransferResult executeLockedTransfer(TransferRequest request) {
        // ── Acquire pessimistic write locks in ascending UUID order ───────
        boolean lockFromFirst =
                request.fromAccountId().compareTo(request.toAccountId()) < 0;

        UUID firstLockId  = lockFromFirst ? request.fromAccountId() : request.toAccountId();
        UUID secondLockId = lockFromFirst ? request.toAccountId()   : request.fromAccountId();

        // Acquire locks in the determined order (each call issues SELECT ... FOR UPDATE)
        Account firstLocked = accountRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Account not found: " + firstLockId));

        Account secondLocked = accountRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Account not found: " + secondLockId));

        // Re-bind to semantic roles after lock acquisition
        Account fromAccount = lockFromFirst ? firstLocked  : secondLocked;
        Account toAccount   = lockFromFirst ? secondLocked : firstLocked;

        // Execute test hook if configured (e.g. artificial delay to exercise concurrent read races)
        if (postLockHook != null) {
            postLockHook.run();
        }

        // ── Verify sufficient balance ─────────────────────────────────────
        if (fromAccount.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException(
                    fromAccount.getId(),
                    fromAccount.getBalance(),
                    request.amount());
        }

        // ── Generate transactionId and insert ledger entries ──────────────
        UUID transactionId = UUID.randomUUID();

        LedgerEntry debit = new LedgerEntry(
                fromAccount.getId(), transactionId, request.amount(), EntryType.DEBIT);

        LedgerEntry credit = new LedgerEntry(
                toAccount.getId(), transactionId, request.amount(), EntryType.CREDIT);

        ledgerEntryRepository.save(debit);
        ledgerEntryRepository.save(credit);

        // ── Update cached balances ────────────────────────────────────────
        fromAccount.setBalance(fromAccount.getBalance().subtract(request.amount()));
        toAccount.setBalance(toAccount.getBalance().add(request.amount()));

        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        return new TransferResult(
                transactionId,
                fromAccount.getId(), fromAccount.getBalance(),
                toAccount.getId(),   toAccount.getBalance(),
                "COMPLETED"
        );
    }
}
