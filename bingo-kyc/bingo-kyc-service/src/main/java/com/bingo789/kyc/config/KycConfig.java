package com.bingo789.kyc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
public class KycConfig {

    /** RunPod API and the facecmp pod; request timeouts are set per call from the config table. */
    @Bean(destroyMethod = "close")
    public HttpClient runPodHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }
}
