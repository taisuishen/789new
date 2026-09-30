package com.bingo789.lobby.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bingo789.lobby.api.enums.ProviderStatus;
import com.bingo789.lobby.entity.GameProvider;
import com.bingo789.lobby.mapper.GameProviderMapper;
import com.bingo789.lobby.service.CatalogCache;
import com.bingo789.lobby.service.GameSyncService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Catalogue sync. Job param: a provider code, or empty for all ACTIVE providers.
 * A failing provider does not stop the others; the job is marked failed at the end.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameSyncJob {

    private final GameProviderMapper providerMapper;
    private final GameSyncService syncService;
    private final CatalogCache catalogCache;

    @XxlJob("lobbyGameSyncJob")
    public void execute() {
        String param = XxlJobHelper.getJobParam();
        List<String> providerCodes;
        if (param == null || param.isBlank()) {
            providerCodes = providerMapper.selectList(Wrappers.<GameProvider>lambdaQuery()
                            .eq(GameProvider::getStatus, ProviderStatus.ACTIVE))
                    .stream().map(GameProvider::getCode).toList();
        } else {
            String code = param.trim();
            if (providerMapper.selectById(code) == null) {
                XxlJobHelper.handleFail("unknown provider " + code);
                return;
            }
            providerCodes = List.of(code);
        }

        List<String> failed = new ArrayList<>();
        for (String code : providerCodes) {
            try {
                GameSyncService.SyncResult result = syncService.syncProvider(code);
                XxlJobHelper.log("{}: inserted={}, updated={}, unchanged={}, missing={}",
                        code, result.inserted(), result.updated(), result.unchanged(), result.missing());
            } catch (Exception e) {
                failed.add(code);
                log.warn("catalogue sync failed for provider {}", code, e);
                XxlJobHelper.log("{}: failed: {}", code, e.toString());
            }
        }
        catalogCache.invalidate();
        if (!failed.isEmpty()) {
            XxlJobHelper.handleFail("catalogue sync failed for " + failed);
        }
    }
}
