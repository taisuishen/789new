package com.bingo789.game.callback;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CallbackControllerTest {

    @Test
    void actionIsTheRestOfThePathAfterTheProvider() {
        assertThat(CallbackController.actionOf("/callback/PP/bet.html", "PP")).isEqualTo("bet.html");
        assertThat(CallbackController.actionOf("/callback/CQ9/transaction/balance/b7891001", "CQ9"))
                .isEqualTo("transaction/balance/b7891001");
        assertThat(CallbackController.actionOf("/callback/JDB", "JDB")).isEmpty();
        assertThat(CallbackController.actionOf("/callback/JDB/", "JDB")).isEmpty();
    }
}
