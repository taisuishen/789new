package com.bingo789.risk;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.payment.api.PaymentClient;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.turnover.api.TurnoverClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients(clients = {PaymentClient.class, WalletClient.class, TurnoverClient.class})
public class RiskApplication {

    public static void main(String[] args) {
        BingoTime.applyJvmDefault();
        SpringApplication.run(RiskApplication.class, args);
    }
}
