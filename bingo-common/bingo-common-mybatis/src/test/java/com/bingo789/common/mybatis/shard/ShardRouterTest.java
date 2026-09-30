package com.bingo789.common.mybatis.shard;

import com.bingo789.common.core.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShardRouterTest {

    @Test
    void snowflakeIdsSpreadEvenlyOverLogicalShards() {
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(7);
        int shards = 1024;
        int users = 1_024_000;
        int[] counts = new int[shards];
        for (int i = 0; i < users; i++) {
            counts[LogicalShards.of(ids.nextId(), shards)]++;
        }
        int expected = users / shards;
        for (int count : counts) {
            // +-20% of the mean; a plain id % 1024 on snowflakes would leave most shards empty
            assertThat(count).isBetween((int) (expected * 0.8), (int) (expected * 1.2));
        }
    }

    @Test
    void routesMustCoverEveryShardExactlyOnce() {
        assertThatThrownBy(() -> ShardRouter.of(properties(List.of(route("0-500", "ds0"), route("502-1023", "ds1")), List.of())))
                .hasMessageContaining("501");
        assertThatThrownBy(() -> ShardRouter.of(properties(List.of(route("0-600", "ds0"), route("500-1023", "ds1")), List.of())))
                .hasMessageContaining("routed twice");
        assertThatThrownBy(() -> ShardRouter.of(properties(List.of(route("0-1023", "ds9")), List.of())))
                .hasMessageContaining("unknown datasource");
    }

    @Test
    void reloadMovesShardsAndFlagsMigration() {
        ShardRouter router = ShardRouter.of(properties(List.of(route("0-511", "ds0"), route("512-1023", "ds1")), List.of()));
        long user = findUserInShard(router, 100);
        assertThat(router.dataSourceOf(user)).isEqualTo("ds0");
        assertThat(router.isMigrating(user)).isFalse();

        router.reload(properties(List.of(route("0-511", "ds0"), route("512-1023", "ds1")), List.of("64-127")));
        assertThat(router.isMigrating(user)).isTrue();

        router.reload(properties(List.of(route("0-63", "ds0"), route("64-127", "ds1"), route("128-511", "ds0"),
                route("512-1023", "ds1")), List.of()));
        assertThat(router.dataSourceOf(user)).isEqualTo("ds1");
        assertThat(router.isMigrating(user)).isFalse();
    }

    @Test
    void jobsRunOnlyOnDatasourcesThatOwnShards() {
        // ds1 is configured (e.g. a new instance still being filled by DRS) but owns no shard yet
        ShardRouter router = ShardRouter.of(properties(List.of(route("0-1023", "ds0")), List.of()));
        assertThat(router.dataSources()).containsExactly("ds0", "ds1");
        assertThat(router.activeDataSources()).containsExactly("ds0");

        router.reload(properties(List.of(route("0-511", "ds0"), route("512-1023", "ds1")), List.of()));
        assertThat(router.activeDataSources()).containsExactly("ds0", "ds1");

        // everything moved to ds1: ds0 stays active because it holds the global tables
        router.reload(properties(List.of(route("0-1023", "ds1")), List.of()));
        assertThat(router.activeDataSources()).containsExactly("ds0", "ds1");
    }

    @Test
    void invalidReloadKeepsThePreviousTable() {
        ShardRouter router = ShardRouter.of(properties(List.of(route("0-1023", "ds0")), List.of()));
        assertThatThrownBy(() -> router.reload(properties(List.of(route("0-100", "ds0")), List.of())));
        assertThat(router.dataSourceOf(12345L)).isEqualTo("ds0");
    }

    @Test
    void templateRefusesWritesToMigratingShards() {
        ShardRouter router = ShardRouter.of(properties(List.of(route("0-1023", "ds0")), List.of("0-1023")));
        ShardTemplate template = new ShardTemplate(router, true);

        assertThatThrownBy(() -> template.forUserWrite(1L, () -> "x")).isInstanceOf(ShardMigratingException.class);
        assertThat(template.forUser(1L, ShardContext::current)).isEqualTo("ds0");
        assertThat(ShardContext.current()).isNull();
    }

    private static long findUserInShard(ShardRouter router, int shard) {
        for (long id = 1; ; id++) {
            if (router.logicalShard(id) == shard) {
                return id;
            }
        }
    }

    private static ShardProperties.Route route(String shards, String ds) {
        return new ShardProperties.Route(shards, ds);
    }

    private static ShardProperties properties(List<ShardProperties.Route> routes, List<String> migrating) {
        Map<String, ShardProperties.DataSourceSpec> datasources = new LinkedHashMap<>();
        datasources.put("ds0", new ShardProperties.DataSourceSpec("jdbc:mysql://a/x", "u", "p", null));
        datasources.put("ds1", new ShardProperties.DataSourceSpec("jdbc:mysql://b/x", "u", "p", null));
        return new ShardProperties(true, 1024, datasources, routes, null, migrating, null);
    }
}
