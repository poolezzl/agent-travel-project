package com.agenttravel.backend.points;

import com.agenttravel.backend.common.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class WalletReconciliation {

    private static final Logger LOG = LoggerFactory.getLogger(WalletReconciliation.class);
    private final WalletRepository wallet;
    private final AuditRepository audit;

    public WalletReconciliation(WalletRepository wallet, AuditRepository audit) {
        this.wallet = wallet;
        this.audit = audit;
    }

    @Scheduled(cron = "${app.wallet.reconciliation-cron:0 0 * * * *}", zone = "UTC")
    public void check() {
        long accounts = wallet.countUnbalancedAccounts();
        if (accounts > 0) {
            LOG.error("POINTS_RECONCILIATION_MISMATCH 异常积分账户数={}", accounts);
            audit.write(null, "POINTS_RECONCILIATION", "POINTS_ACCOUNT", null,
                    "FAILURE", "POINTS_RECONCILIATION_MISMATCH");
        }
    }
}
