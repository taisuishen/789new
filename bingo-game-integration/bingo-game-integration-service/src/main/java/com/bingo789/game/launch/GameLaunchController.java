package com.bingo789.game.launch;

import com.bingo789.game.api.GameLaunchApi;
import com.bingo789.game.api.dto.LaunchCommand;
import com.bingo789.game.api.dto.LaunchView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GameLaunchController implements GameLaunchApi {

    private final LaunchService launchService;

    @Override
    public LaunchView launch(@Valid @RequestBody LaunchCommand command) {
        return launchService.launch(command);
    }
}
