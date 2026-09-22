package com.bebebe.agent.config;

import com.electronwill.nightconfig.core.Config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class ConfigSection {

    private final String name;
    private final Config config;

    ConfigSection(String name, Config config) {
        this.name = name;
        this.config = config;
    }

    public String name() {
        return name;
    }

    public String fullKey(String key) {
        return name + "." + key;
    }

    public boolean has(String key) {
        return config.getOptional(key).isPresent();
    }

    public java.util.Set<String> keys() {
        return config.valueMap().keySet().stream()
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public Optional<String> string(String key) {
        return config.getOptional(key)
                .map(Object::toString)
                .map(String::trim)
                .filter(s -> !s.isEmpty());
    }

    public String string(String key, String fallback) {
        return string(key).orElse(fallback);
    }

    public String requiredString(String key) {
        return string(key).orElseThrow(() -> new ConfigException(
                "Required config parameter is missing: " + fullKey(key)));
    }

    public Optional<Integer> integer(String key) {
        return number(key).map(Number::intValue);
    }

    public int integer(String key, int fallback) {
        return integer(key).orElse(fallback);
    }

    public Optional<Long> longValue(String key) {
        return number(key).map(Number::longValue);
    }

    public Optional<Double> doubleValue(String key) {
        return number(key).map(Number::doubleValue);
    }

    private Optional<Number> number(String key) {
        return config.getOptional(key).map(value -> {
            if (value instanceof Number n) {
                return n;
            }
            throw new ConfigException(
                    "Parameter " + fullKey(key) + " must be a number, not " + describe(value));
        });
    }

    public Optional<Boolean> bool(String key) {
        return config.getOptional(key).map(value -> {
            if (value instanceof Boolean b) {
                return b;
            }
            throw new ConfigException(
                    "Parameter " + fullKey(key) + " must be true or false, not " + describe(value));
        });
    }

    public boolean bool(String key, boolean fallback) {
        return bool(key).orElse(fallback);
    }

    public Duration seconds(String key, Duration fallback) {
        return longValue(key).map(Duration::ofSeconds).orElse(fallback);
    }

    public List<String> stringList(String key) {
        return rawList(key).stream()
                .map(Object::toString)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public Set<String> lowercaseSet(String key) {
        return stringList(key).stream()
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<Long> longSet(String key) {
        return rawList(key).stream()
                .map(value -> {
                    if (value instanceof Number n) {
                        return n.longValue();
                    }
                    throw new ConfigException(
                            "Elements of list " + fullKey(key) + " must be numbers, "
                                    + "but found " + describe(value));
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private List<?> rawList(String key) {
        Optional<Object> value = config.getOptional(key);
        if (value.isEmpty()) {
            return List.of();
        }
        if (value.get() instanceof List<?> list) {
            return list;
        }
        throw new ConfigException(
                "Parameter " + fullKey(key) + " must be a list, not " + describe(value.get()));
    }

    private static String describe(Object value) {
        return value == null ? "null" : "'" + value + "' (" + value.getClass().getSimpleName() + ")";
    }
}
