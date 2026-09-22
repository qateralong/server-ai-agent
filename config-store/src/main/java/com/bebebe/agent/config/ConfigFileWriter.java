package com.bebebe.agent.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.NewlineStyle;
import com.electronwill.nightconfig.toml.TomlWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public final class ConfigFileWriter {

    private static final Logger log = LoggerFactory.getLogger(ConfigFileWriter.class);

    private ConfigFileWriter() {
    }

    public static void update(Path file, Map<String, Object> values) {
        if (!Files.isReadable(file)) {
            throw new ConfigException("Nothing to update: config not found at " + file.toAbsolutePath());
        }

        String rendered;
        try (CommentedFileConfig config = CommentedFileConfig.builder(file)
                .preserveInsertionOrder()
                .sync()
                .build()) {
            config.load();
            values.forEach(config::set);

            TomlWriter writer = new TomlWriter();
            writer.setIndent("");
            writer.setNewline(NewlineStyle.UNIX);

            StringWriter out = new StringWriter();
            writer.write(config, out);
            rendered = out.toString();
        } catch (RuntimeException e) {
            throw new ConfigException("Failed to prepare config for writing: " + file.toAbsolutePath(), e);
        }

        writeAtomically(file, rendered);
        log.info("Config updated ({} keys): {}", values.size(), file.toAbsolutePath());
    }

    private static void writeAtomically(Path file, String content) {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            copyPermissions(file, temp);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {

                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ConfigException("Failed to write config: " + file.toAbsolutePath(), e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                log.debug("Temporary file not deleted: {}", temp, e);
            }
        }
    }

    private static void copyPermissions(Path from, Path to) {
        try {
            var permissions = Files.getPosixFilePermissions(from);
            Files.setPosixFilePermissions(to, permissions);
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("File permissions not carried over to the temporary copy", e);
        }
    }
}
