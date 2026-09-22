package com.bebebe.agent.watchdog;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public record BuildInfo(String commit, String shortCommit, String commitTime, String branch,
                        boolean dirty, String remote, String buildTime) {

    public static final String RESOURCE = "/build-info.properties";

    public static BuildInfo load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return unknown();
            }
            Properties p = new Properties();
            p.load(in);
            return new BuildInfo(
                    p.getProperty("commit", ""), p.getProperty("commit.short", ""),
                    p.getProperty("commit.time", ""), p.getProperty("branch", ""),
                    Boolean.parseBoolean(p.getProperty("dirty", "false")),
                    p.getProperty("remote", ""), p.getProperty("build.time", ""));
        } catch (IOException e) {
            return unknown();
        }
    }

    public static BuildInfo unknown() {
        return new BuildInfo("", "", "", "", false, "", "");
    }

    public boolean isKnown() {
        return !commit.isEmpty();
    }

    public String repoSlug() {
        String r = remote.strip();
        if (r.isEmpty()) {
            return "";
        }
        r = r.replaceFirst("\\.git$", "");
        int colon = r.lastIndexOf(':');
        int slash = r.lastIndexOf('/');
        int owner = r.lastIndexOf('/', slash - 1);
        if (r.startsWith("git@") && colon > 0) {
            return r.substring(colon + 1);
        }
        return owner > 0 ? r.substring(owner + 1) : "";
    }

    public String describe() {
        if (!isKnown()) {
            return "unknown (built without Gradle)";
        }
        return shortCommit + " (" + branch + (dirty ? ", uncommitted changes" : "") + ")";
    }
}
