package com.karthik.ledgerflow.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karthik.ledgerflow.config.RabbitMQConfig;
import com.karthik.ledgerflow.event.TransactionCompletedEvent;
import com.karthik.ledgerflow.model.AuditLog;
import com.karthik.ledgerflow.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ consumer that listens to {@code ledger.queue} for completed transaction
 * events and persists corresponding audit log entries to PostgreSQL.
 */
@Component
public class AuditLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditLogConsumer.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditLogConsumer(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitMQConfig.LEDGER_QUEUE)
    public void handleTransactionCompleted(TransactionCompletedEvent event) {
        log.info("Received TransactionCompletedEvent: transactionId={}", event.transactionId());
        try {
            String payload = objectMapper.writeValueAsString(event);
            AuditLog auditLog = new AuditLog(
                    event.transactionId(),
                    "TransactionCompletedEvent",
                    payload
            );
            auditLogRepository.save(auditLog);
            log.info("Saved audit_log entry for transactionId={}", event.transactionId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize TransactionCompletedEvent payload for transactionId={}",
                    event.transactionId(), e);
            throw new RuntimeException("Failed to serialize audit log payload", e);
        }
    }
}
