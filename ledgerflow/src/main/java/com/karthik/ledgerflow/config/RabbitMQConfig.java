package com.karthik.ledgerflow.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * RabbitMQ configuration – declares exchanges, queues, and bindings.
 * Add actual queue/exchange names here as business logic is introduced.
 */
@Configuration
@Profile("!test")
public class RabbitMQConfig {

    /* ── Example constants (replace with real names) ─────────────────── */
    public static final String LEDGER_EXCHANGE  = "ledger.exchange";
    public static final String LEDGER_QUEUE     = "ledger.queue";
    public static final String LEDGER_ROUTING   = "ledger.routing.key";

    @Bean
    public TopicExchange ledgerExchange() {
        return new TopicExchange(LEDGER_EXCHANGE);
    }

    @Bean
    public Queue ledgerQueue() {
        return QueueBuilder.durable(LEDGER_QUEUE).build();
    }

    @Bean
    public Binding ledgerBinding(Queue ledgerQueue, TopicExchange ledgerExchange) {
        return BindingBuilder.bind(ledgerQueue).to(ledgerExchange).with(LEDGER_ROUTING);
    }

    @Bean
    public MessageConverter jsonMessageConverter(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
