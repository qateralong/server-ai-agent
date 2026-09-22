rootProject.name = "server-ai-agent"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(

    "supervisor-app",
    "server-app",
    "client-app",

    "assembly",
    "ui",

    "agent-core",
    "ollama-client",

    "telegram-bridge",
    "stt-bridge",
    "capture",
    "tts-bridge",
    "clipboard-bridge",

    "tools",
    "script-runtime",
    "script-library",

    "memory-store",
    "notes-store",

    "config-store",
    "scheduler",
    "logging",
    "watchdog",

    "transport",
)
