package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.model.AlertSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
public class AlertSignalListener {

    private final AlertEvaluationService alertEvaluationService;

    public AlertSignalListener(AlertEvaluationService alertEvaluationService) {
        this.alertEvaluationService = alertEvaluationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSignal(AlertSignal signal) {
        try {
            alertEvaluationService.evaluateSignal(signal);
        } catch (Exception ex) {
            log.warn("Alert signal evaluation failed for {}", signal == null ? null : signal.getSourceEventKey(), ex);
        }
    }
}
