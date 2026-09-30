package com.bingo789.common.mybatis.shard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Applies {@code bingo.shard.routes} / {@code bingo.shard.migrating-shards} changes pushed from Nacos.
 * A rejected change (overlap, gap, unknown datasource) is logged and the previous table stays in force.
 */
@Slf4j
public class ShardRoutesRefresher implements ApplicationListener<EnvironmentChangeEvent> {

    private final ShardRouter router;
    private final Environment environment;

    public ShardRoutesRefresher(ShardRouter router, Environment environment) {
        this.router = router;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {
        if (event.getKeys().stream().noneMatch(key -> key.startsWith("bingo.shard."))) {
            return;
        }
        try {
            ShardProperties fresh = Binder.get(environment).bind("bingo.shard", Bindable.of(ShardProperties.class)).get();
            router.reload(fresh);
        } catch (Exception e) {
            log.error("rejected shard route change, keeping the previous routes", e);
        }
    }
}
