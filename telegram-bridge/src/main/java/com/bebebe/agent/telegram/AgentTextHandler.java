package com.bebebe.agent.telegram;

import com.bebebe.agent.core.AgentReply;
import com.bebebe.agent.core.UserMessage;

@FunctionalInterface
public interface AgentTextHandler {

    AgentReply reply(UserMessage message);
}
