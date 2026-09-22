package com.bebebe.agent.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.FileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private final Path path;
    private final Config root;

    private AppConfig(Path path, Config root) {
        this.path = path;
        this.root = root;
    }

    public static AppConfig load() {
        return load(ConfigPaths.defaultPath());
    }

    public static AppConfig load(Path tomlFile) {
        if (!Files.isReadable(tomlFile)) {
            throw new ConfigException(
                    "Config not found: " + tomlFile.toAbsolutePath()
                            + ". Copy config/agent.example.toml and fill in the values.");
        }
        try (FileConfig file = FileConfig.builder(tomlFile).build()) {
            file.load();

            Config snapshot = Config.inMemory();
            snapshot.putAll(file);
            log.info("Config read: {}", tomlFile.toAbsolutePath());
            return new AppConfig(tomlFile, snapshot);
        } catch (ConfigException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ConfigException("Failed to parse config " + tomlFile.toAbsolutePath(), e);
        }
    }

    public static AppConfig fromToml(String toml) {
        Config parsed = com.electronwill.nightconfig.toml.TomlFormat.instance()
                .createParser()
                .parse(toml);
        return new AppConfig(Path.of("<memory>"), parsed);
    }

    public Path path() {
        return path;
    }

    public ConfigSection section(String name) {
        return new ConfigSection(name, root.<Config>getOptional(name).orElseGet(Config::inMemory));
    }

    public java.util.Set<String> sectionNames() {
        return root.valueMap().keySet();
    }

    public boolean hasSection(String name) {
        return root.getOptional(name).isPresent();
    }

    public Optional<ConfigSection> optionalSection(String name) {
        return hasSection(name) ? Optional.of(section(name)) : Optional.empty();
    }

    @Override
    public String toString() {
        return "AppConfig[" + path + "]";
    }
}
