package ru.vladtop46.proxy.config;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class ProxyConfig {
    private ServerSettings server;
    private SecuritySettings security;
    private WebSocketSettings websocket;
    private List<String> blockedDomains;
    private ErrorPageSettings errorPage;

    // Геттеры и сеттеры для основных полей
    public ServerSettings getServer() {
        return server;
    }

    public void setServer(ServerSettings server) {
        this.server = server;
    }

    public SecuritySettings getSecurity() {
        return security;
    }

    public void setSecurity(SecuritySettings security) {
        this.security = security;
    }

    public WebSocketSettings getWebsocket() {
        return websocket;
    }

    public void setWebsocket(WebSocketSettings websocket) {
        this.websocket = websocket;
    }

    public List<String> getBlockedDomains() {
        return blockedDomains;
    }

    public void setBlockedDomains(List<String> blockedDomains) {
        this.blockedDomains = blockedDomains;
    }

    public ErrorPageSettings getErrorPage() {
        return errorPage;
    }

    public void setErrorPage(ErrorPageSettings errorPage) {
        this.errorPage = errorPage;
    }

    public static class ServerSettings {
        private int port = 8023;
        private String logsDirectory = "logs";

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getLogsDirectory() {
            return logsDirectory;
        }

        public void setLogsDirectory(String logsDirectory) {
            this.logsDirectory = logsDirectory;
        }
    }

    public static class SecuritySettings {
        private boolean whitelistEnabled = false;
        private List<String> whitelistedIps;

        public boolean isWhitelistEnabled() {
            return whitelistEnabled;
        }

        public void setWhitelistEnabled(boolean whitelistEnabled) {
            this.whitelistEnabled = whitelistEnabled;
        }

        public List<String> getWhitelistedIps() {
            return whitelistedIps;
        }

        public void setWhitelistedIps(List<String> whitelistedIps) {
            this.whitelistedIps = whitelistedIps;
        }
    }

    public static class WebSocketSettings {
        private boolean enabled = true;
        private boolean webRtcEnabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isWebRtcEnabled() {
            return webRtcEnabled;
        }

        public void setWebRtcEnabled(boolean webRtcEnabled) {
            this.webRtcEnabled = webRtcEnabled;
        }
    }

    public static class ErrorPageSettings {
        private String title = "Access Denied";
        private String message = "This domain is blocked by proxy settings.";

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static ProxyConfig loadConfig(String path) {
        try (InputStream input = Files.newInputStream(Paths.get(path))) {
            Yaml yaml = new Yaml();
            return yaml.loadAs(input, ProxyConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config: " + e.getMessage(), e);
        }
    }
}