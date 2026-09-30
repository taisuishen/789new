package com.bingo789.payment.web;

import com.bingo789.common.core.Result;
import com.bingo789.common.web.CurrentUser;
import com.bingo789.payment.domain.ChannelDirection;
import com.bingo789.payment.service.ChannelConfigService;
import com.bingo789.payment.web.dto.ChannelView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/payment/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelConfigService channelConfigService;

    /** @param direction DEPOSIT or PAYOUT */
    @GetMapping
    public Result<List<ChannelView>> list(@RequestParam(value = "direction", defaultValue = "DEPOSIT") String direction,
                                          @RequestParam(value = "currency", required = false) String currency) {
        CurrentUser.requireUserId();
        // valueOf throws IllegalArgumentException, mapped to 400
        ChannelDirection required = ChannelDirection.valueOf(direction.toUpperCase(Locale.ROOT));
        return Result.ok(channelConfigService.listUsable(required, currency));
    }
}
