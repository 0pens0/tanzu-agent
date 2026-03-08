package org.tanzu.goosechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.tanzu.goose.cf.GooseConfiguration;
import org.tanzu.goose.cf.GooseExecutor;
import org.tanzu.goose.cf.McpServerInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Direct OAuth credential injection for goose-agent-chat (Phase 5 removal target).
 * <p>
 * Broker credential injection is now handled by the java-wrapper's
 * {@link org.tanzu.goose.cf.broker.BrokerCredentialInjector}. This class retains
 * only the direct OAuth fallback path for servers not yet migrated to the broker.
 * </p>
 */
@Component
public class GooseConfigInjector {

    private static final Logger logger = LoggerFactory.getLogger(GooseConfigInjector.class);

    private final GooseExecutor executor;
    private final McpOAuthController oauthController;

    public GooseConfigInjector(GooseExecutor executor, McpOAuthController oauthController) {
        this.executor = executor;
        this.oauthController = oauthController;
    }

    /**
     * Inject OAuth tokens for authenticated MCP servers into the Goose config.
     * This is the direct OAuth fallback — used when the broker is not configured.
     *
     * @param sessionId the user's session ID
     */
    public void injectOAuthTokens(String sessionId) {
        logger.debug("injectOAuthTokens called for sessionId={}", sessionId);
        try {
            GooseConfiguration config = executor.getConfiguration();
            Path configPath = findConfigPath();

            if (configPath == null || !Files.exists(configPath)) {
                logger.warn("Config file not found, cannot inject OAuth tokens");
                return;
            }

            String configContent = Files.readString(configPath);
            boolean modified = false;

            for (McpServerInfo server : config.mcpServers()) {
                if (!server.requiresAuth()) {
                    continue;
                }

                Optional<String> accessToken = oauthController.getAccessToken(server.name(), sessionId);
                if (accessToken.isEmpty()) {
                    continue;
                }

                String token = accessToken.get();
                logger.info("Injecting OAuth token for server {} in session {}", server.name(), sessionId);
                configContent = injectAuthorizationHeader(configContent, server.name(), token);
                modified = true;
            }

            if (modified) {
                Files.writeString(configPath, configContent);
                logger.info("Updated config.yaml with OAuth tokens for session {}", sessionId);
            }

        } catch (IOException e) {
            logger.error("Failed to inject OAuth tokens into config", e);
        }
    }

    private String injectAuthorizationHeader(String configContent, String serverName, String token) {
        String[] lines = configContent.split("\n");
        StringBuilder result = new StringBuilder();

        boolean inTargetServer = false;
        boolean foundHeaders = false;
        boolean injectedAuth = false;
        int serverIndent = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            int currentIndent = line.length() - line.stripLeading().length();

            if (trimmed.equals(serverName + ":") && currentIndent == 2) {
                inTargetServer = true;
                serverIndent = currentIndent;
                result.append(line).append("\n");
                continue;
            }

            if (inTargetServer && currentIndent == serverIndent && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
                if (!injectedAuth) {
                    result.append("    headers:\n");
                    result.append("      Authorization: \"Bearer ").append(token).append("\"\n");
                    injectedAuth = true;
                }
                inTargetServer = false;
            }

            if (inTargetServer) {
                if (trimmed.equals("headers:") && currentIndent == 4) {
                    foundHeaders = true;
                    result.append(line).append("\n");

                    boolean authExists = false;
                    for (int j = i + 1; j < lines.length; j++) {
                        String nextLine = lines[j].trim();
                        int nextIndent = lines[j].length() - lines[j].stripLeading().length();
                        if (nextIndent <= 4 && !nextLine.isEmpty()) break;
                        if (nextLine.startsWith("Authorization:")) {
                            authExists = true;
                            break;
                        }
                    }

                    if (!authExists) {
                        result.append("      Authorization: \"Bearer ").append(token).append("\"\n");
                        injectedAuth = true;
                    }
                    continue;
                }

                if (foundHeaders && trimmed.startsWith("Authorization:") && currentIndent == 6) {
                    result.append("      Authorization: \"Bearer ").append(token).append("\"\n");
                    injectedAuth = true;
                    continue;
                }
            }

            result.append(line).append("\n");
        }

        if (inTargetServer && !injectedAuth) {
            result.append("    headers:\n");
            result.append("      Authorization: \"Bearer ").append(token).append("\"\n");
        }

        return result.toString();
    }

    private Path findConfigPath() {
        String home = System.getenv("HOME");
        if (home != null) {
            Path configPath = Paths.get(home, ".config", "goose", "config.yaml");
            if (Files.exists(configPath)) {
                return configPath;
            }
        }

        Path cfPath = Paths.get("/home/vcap/app/.config/goose/config.yaml");
        if (Files.exists(cfPath)) {
            return cfPath;
        }

        return null;
    }
}
