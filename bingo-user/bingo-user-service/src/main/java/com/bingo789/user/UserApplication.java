package com.bingo789.user;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.wallet.api.WalletClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients(clients = WalletClient.class)
public class UserApplication {

    public static void main(String[] args) {
        BingoTime.applyJvmDefault();
        SpringApplication.run(UserApplication.class, args);
    }
}
