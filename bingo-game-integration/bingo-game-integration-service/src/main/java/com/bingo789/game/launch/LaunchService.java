package com.bingo789.game.launch;

import com.bingo789.common.core.line.UserLine;
import com.bingo789.game.api.dto.LaunchCommand;
import com.bingo789.game.api.dto.LaunchView;
import com.bingo789.game.config.WalletMode;
import com.bingo789.game.provider.ProviderRegistry;
import com.bingo789.game.provider.ProviderRuntime;
import com.bingo789.game.transfer.TransferWalletService;
import com.bingo789.game.wallet.PlayerIds;
import com.bingo789.wallet.api.WalletClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class LaunchService {

    private final ProviderRegistry registry;
    private final TransferWalletService transferService;
    private final WalletClient walletClient;

    /** Player eligibility (RG, KYC, maintenance) is checked by the lobby before it calls us. */
    public LaunchView launch(LaunchCommand command) {
        ProviderRuntime provider = registry.require(command.providerCode());
        String playerId = PlayerIds.encode(command.userId());
        if (provider.config().walletMode() == WalletMode.TRANSFER && provider.config().autoTransferOnLaunch() && !command.demo()) {
            autoTransferIn(provider, command);
        }
        LaunchView view = provider.client().execute(() -> provider.adapter().launch(command, playerId, provider.client()));
        return new LaunchView(view.url(), provider.config().walletMode().name());
    }

    /**
     * Transfer mode: move the whole available balance to the provider before play. A failed or unknown transfer
     * does not block the launch; the recovery job settles it and the player simply sees the provider balance.
     * TODO: transfer back on game exit / session end (provider "logout" callback or lobby event), plus a sweep job.
     */
    private void autoTransferIn(ProviderRuntime provider, LaunchCommand command) {
        try {
            BigDecimal balance = walletClient.balance(command.userId(), command.currency()).balance();
            if (balance.signum() > 0) {
                transferService.transferIn(provider, command.userId(), UserLine.orDefault(command.userLine()), command.currency(), balance);
            }
        } catch (Exception e) {
            log.warn("auto transfer-in failed for user {} provider {}", command.userId(), provider.code(), e);
        }
    }
}
