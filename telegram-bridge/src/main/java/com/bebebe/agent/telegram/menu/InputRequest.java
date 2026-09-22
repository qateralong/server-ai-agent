package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.telegram.input.InputHandler;

public record InputRequest(String fieldKey, InputHandler handler) {
}
