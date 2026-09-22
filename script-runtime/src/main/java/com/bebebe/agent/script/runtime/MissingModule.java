package com.bebebe.agent.script.runtime;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record MissingModule(String moduleName, String packageName) {

    private static final Pattern MODULE_NOT_FOUND = Pattern.compile(
            "ModuleNotFoundError: No module named ['\"]([A-Za-z0-9_.]+)['\"]");

    private static final Pattern IMPORT_ERROR = Pattern.compile(
            "ImportError: No module named ['\"]?([A-Za-z0-9_.]+)['\"]?");

    private static final Map<String, String> PACKAGE_ALIASES = Map.ofEntries(
            Map.entry("cv2", "opencv-python"),
            Map.entry("PIL", "Pillow"),
            Map.entry("yaml", "PyYAML"),
            Map.entry("bs4", "beautifulsoup4"),
            Map.entry("sklearn", "scikit-learn"),
            Map.entry("skimage", "scikit-image"),
            Map.entry("dateutil", "python-dateutil"),
            Map.entry("serial", "pyserial"),
            Map.entry("Crypto", "pycryptodome"),
            Map.entry("docx", "python-docx"),
            Map.entry("pptx", "python-pptx"),
            Map.entry("fitz", "PyMuPDF"),
            Map.entry("git", "GitPython"),
            Map.entry("attr", "attrs"),
            Map.entry("OpenSSL", "pyOpenSSL"),
            Map.entry("zmq", "pyzmq"),
            Map.entry("psycopg2", "psycopg2-binary"),
            Map.entry("MySQLdb", "mysqlclient"),
            Map.entry("magic", "python-magic"),
            Map.entry("dotenv", "python-dotenv"),
            Map.entry("jwt", "PyJWT"),
            Map.entry("usb", "pyusb"),
            Map.entry("gi", "PyGObject"),
            Map.entry("lxml", "lxml"),
            Map.entry("google", "google-api-python-client"));

    private static final Pattern SAFE_PACKAGE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public static Optional<MissingModule> detect(String stderr, Set<String> stdlibModules) {
        if (stderr == null || stderr.isBlank()) {
            return Optional.empty();
        }

        String module = firstMatch(MODULE_NOT_FOUND, stderr)
                .or(() -> firstMatch(IMPORT_ERROR, stderr))
                .orElse(null);
        if (module == null) {
            return Optional.empty();
        }

        String topLevel = module.contains(".") ? module.substring(0, module.indexOf('.')) : module;

        if (stdlibModules.contains(topLevel)) {
            return Optional.empty();
        }

        String packageName = PACKAGE_ALIASES.getOrDefault(topLevel, topLevel);
        if (!SAFE_PACKAGE.matcher(packageName).matches()) {
            return Optional.empty();
        }
        return Optional.of(new MissingModule(topLevel, packageName));
    }

    private static Optional<String> firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    @Override
    public String toString() {
        return moduleName.equals(packageName)
                ? moduleName
                : moduleName + " (package " + packageName + ")";
    }
}
