package com.agenttravel.backend.points;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenttravel.backend.common.AuditRepository;
import org.junit.jupiter.api.Test;

class WalletReconciliationUnitTests {

    private final WalletRepository wallet = mock(WalletRepository.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final WalletReconciliation reconciliation = new WalletReconciliation(wallet, audit);

    @Test
    void writesNoAlertWhenEveryAccountIsBalanced() {
        when(wallet.countUnbalancedAccounts()).thenReturn(0L);

        reconciliation.check();

        verifyNoInteractions(audit);
    }

    @Test
    void writesOneAggregatedAlertForAnyNumberOfDriftedAccounts() {
        when(wallet.countUnbalancedAccounts()).thenReturn(7L);

        reconciliation.check();

        verify(audit).write(null, "POINTS_RECONCILIATION", "POINTS_ACCOUNT", null, "FAILURE",
                "POINTS_RECONCILIATION_MISMATCH");
    }
}
