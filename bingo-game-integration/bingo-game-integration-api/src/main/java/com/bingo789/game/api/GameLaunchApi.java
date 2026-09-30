package com.bingo789.game.api;

import com.bingo789.game.api.dto.LaunchCommand;
import com.bingo789.game.api.dto.LaunchView;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

public interface GameLaunchApi {

    @PostMapping("/internal/game/launch")
    LaunchView launch(@RequestBody LaunchCommand command);
}
