package com.bingo789.common.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.nio.file.Path;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(GlobalExceptionHandler.class)
public class BingoWebAutoConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public RequestContextFilter bingoRequestContextFilter() {
        return new RequestContextFilter();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ServiceRegistry.class)
    static class DrainConfiguration {

        /** Marker created by the pod's preStop hook (k8s manifests). */
        @Bean
        RegistryDrain registryDrain(@Value("${bingo.drain.marker-file:/tmp/draining}") String markerFile,
                                    ObjectProvider<ServiceRegistry<Registration>> registry,
                                    ObjectProvider<Registration> registration) {
            return new RegistryDrain(Path.of(markerFile), registry, registration);
        }
    }
}
