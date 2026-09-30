package com.bingo789.turnover;

import com.bingo789.common.core.time.BingoTime;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TurnoverApplication {

    public static void main(String[] args) {
        BingoTime.applyJvmDefault();
        SpringApplication.run(TurnoverApplication.class, args);
    }
}
