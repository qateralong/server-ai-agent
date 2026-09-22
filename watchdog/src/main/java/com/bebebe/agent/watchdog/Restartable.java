package com.bebebe.agent.watchdog;

import java.util.Optional;

public interface Restartable {

    Optional<String> restart(String reason);
}
