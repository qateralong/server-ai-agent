package com.bebebe.agent.ui;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaConfig;

public final class UiContext {

    private static volatile AgentSwitch agentSwitch;
    private static volatile AppSettings settings;
    private static volatile com.bebebe.agent.llm.LlmProvider llm;
    private static volatile com.bebebe.agent.memory.PersonaStore personas;
    private static volatile UiServices services;
    private static volatile Runnable showWindow = () -> { };
    private static volatile com.bebebe.agent.notes.NotesStore notes;

    private UiContext() {
    }

    public static void install(AgentSwitch switchInstance, AppSettings settingsInstance,
                               com.bebebe.agent.llm.LlmProvider provider) {
        agentSwitch = switchInstance;
        settings = settingsInstance;
        llm = provider;
    }

    public static synchronized AgentSwitch agentSwitch() {
        if (agentSwitch == null) {
            agentSwitch = new AgentSwitch(false);
        }
        return agentSwitch;
    }

    public static synchronized AppSettings settings() {
        if (settings == null) {
            settings = AppSettings.from(AppConfig.fromToml(""));
        }
        return settings;
    }

    public static void installPersonas(com.bebebe.agent.memory.PersonaStore store) {
        personas = store;
    }

    public static com.bebebe.agent.memory.PersonaStore personas() {
        return personas;
    }

    public static void onShowWindow(Runnable action) {
        showWindow = action;
    }

    public static void showWindow() {
        showWindow.run();
    }

    public static void installNotes(com.bebebe.agent.notes.NotesStore store) {
        notes = store;
    }

    public static com.bebebe.agent.notes.NotesStore notes() {
        return notes;
    }

    public static void installServices(UiServices value) {
        services = value;
    }

    public static UiServices services() {
        return services;
    }

    public static synchronized com.bebebe.agent.llm.LlmProvider llm() {
        if (llm == null) {
            llm = new com.bebebe.agent.llm.OllamaProvider(new OllamaClient(OllamaConfig.localDefault()));
        }
        return llm;
    }
}
