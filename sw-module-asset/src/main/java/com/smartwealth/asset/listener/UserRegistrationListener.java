package com.smartwealth.asset.listener;

import com.smartwealth.asset.service.IAssetWalletService;
import com.smartwealth.user.event.UserRegisteredEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class UserRegistrationListener {
    @Autowired
    private IAssetWalletService walletService;

    // TransactionPhase.AFTER_COMMIT 确保用户注册事务成功后，才执行创建钱包
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRegistered(UserRegisteredEvent event) {
        walletService.initWallet(event.getUserId());
    }
}