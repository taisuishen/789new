package com.bingo789.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(GlobalExceptionHandler.class)
public class BingoWebAutoConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public RequestContextFilter bingoRequestContextFilter() {
        return new RequestContextFilter();
    }
}
