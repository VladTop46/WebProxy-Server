package ru.vladtop46.proxy;

import ru.vladtop46.proxy.config.ProxyConfig;
import ru.vladtop46.proxy.handler.ProxyHandlerFactory;
import ru.vladtop46.proxy.logging.ProxyLogger;
import ru.vladtop46.proxy.security.AccessControl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ProxyServer {
    private final String configPath;
    private final AtomicReference<ProxyConfig> configRef;
    private final ProxyLogger logger;
    private final ProxyHandlerFactory handlerFactory;
    private AccessControl accessControl;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private long lastConfigModTime = 0;

    // Интервал проверки обновлений конфига (в миллисекундах)
    private static final long CONFIG_CHECK_INTERVAL = 10000; // 10 секунд

    // Флаг для настройки автоматического обновления конфига
    private boolean autoReloadConfig = false;

    public ProxyServer(String configPath) {
        this.configPath = configPath;
        ProxyConfig initialConfig = ProxyConfig.loadConfig(configPath);
        this.configRef = new AtomicReference<>(initialConfig);
        this.logger = new ProxyLogger(initialConfig.getServer().getLogsDirectory());
        this.handlerFactory = new ProxyHandlerFactory(configRef);
        this.accessControl = new AccessControl(initialConfig);

        try {
            Path path = Paths.get(configPath);
            if (Files.exists(path)) {
                this.lastConfigModTime = Files.getLastModifiedTime(path).toMillis();
            }
        } catch (Exception e) {
            logger.log("Error getting config file modification time: " + e.getMessage());
        }
    }

    public void start() {
        try {
            // Запускаем поток для прослушивания команд консоли
            startCommandListener();

            // Запускаем поток для проверки изменений конфига
            if (autoReloadConfig) {
                startConfigWatcher();
            }

            ProxyConfig config = configRef.get();
            ServerSocket serverSocket = new ServerSocket(config.getServer().getPort());
            logger.log(String.format("Proxy server is running on port: %d", config.getServer().getPort()));
            logger.log("Type 'reload' to reload configuration or 'exit' to stop the server");

            while (running.get()) {
                try {
                    // Установка таймаута для возможности проверки флага running
                    serverSocket.setSoTimeout(1000);
                    Socket clientSocket = serverSocket.accept();

                    // Проверка доступа по IP
                    if (!accessControl.isIpAllowed(clientSocket.getInetAddress())) {
                        logger.log(String.format("Access denied for IP: %s",
                                clientSocket.getInetAddress().getHostAddress()));
                        clientSocket.close();
                    } else {
                        // Создаем новый поток обработки только для разрешенных соединений
                        new Thread(handlerFactory.createHandler(clientSocket)).start();
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // Игнорируем таймаут - это нормально, позволяет проверить флаг running
                } catch (Exception e) {
                    if (running.get()) {
                        logger.log("Connection error: " + e.getMessage());
                    }
                }
            }

            serverSocket.close();
            logger.log("Server stopped");

        } catch (Exception e) {
            logger.log("Server error: " + e.getMessage());
        }
    }

    /**
     * Метод для перезагрузки конфигурации
     */
    public void reloadConfig() {
        try {
            ProxyConfig newConfig = ProxyConfig.loadConfig(configPath);
            configRef.set(newConfig);

            // Обновляем AccessControl с новой конфигурацией
            this.accessControl = new AccessControl(newConfig);

            // Уведомляем фабрику обработчиков об обновлении конфигурации
            handlerFactory.updateAccessControl();

            // Обновление последнего времени модификации
            Path path = Paths.get(configPath);
            if (Files.exists(path)) {
                this.lastConfigModTime = Files.getLastModifiedTime(path).toMillis();
            }

            logger.log("Configuration reloaded successfully");
        } catch (Exception e) {
            logger.log("Error reloading configuration: " + e.getMessage());
        }
    }

    /**
     * Запускает отдельный поток для прослушивания команд из консоли
     */
    private void startCommandListener() {
        Thread commandThread = new Thread(() -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            try {
                while (running.get()) {
                    if (reader.ready()) {
                        String command = reader.readLine().trim().toLowerCase();
                        processCommand(command);
                    }
                    Thread.sleep(100); // Небольшая задержка чтобы не загружать CPU
                }
            } catch (Exception e) {
                logger.log("Command listener error: " + e.getMessage());
            }
        });
        commandThread.setDaemon(true);
        commandThread.start();
    }

    /**
     * Обрабатывает команды консоли
     */
    private void processCommand(String command) {
        switch (command) {
            case "reload":
                logger.log("Reloading configuration...");
                reloadConfig();
                break;
            case "exit":
                logger.log("Stopping server...");
                running.set(false);
                break;
            case "status":
                logger.log("Server is running. Current config: " + configPath);
                break;
            case "help":
                logger.log("Available commands: reload, exit, status, help");
                break;
            default:
                logger.log("Unknown command. Type 'help' for available commands");
                break;
        }
    }

    /**
     * Запускает поток для проверки изменений конфигурационного файла
     */
    private void startConfigWatcher() {
        Thread watcherThread = new Thread(() -> {
            try {
                while (running.get()) {
                    try {
                        Path path = Paths.get(configPath);
                        if (Files.exists(path)) {
                            long currentModTime = Files.getLastModifiedTime(path).toMillis();
                            if (currentModTime > lastConfigModTime) {
                                logger.log("Configuration file was modified, reloading...");
                                reloadConfig();
                            }
                        }
                    } catch (Exception e) {
                        logger.log("Error checking config file: " + e.getMessage());
                    }

                    Thread.sleep(CONFIG_CHECK_INTERVAL);
                }
            } catch (InterruptedException e) {
                // Thread interrupted, exit
            }
        });
        watcherThread.setDaemon(true);
        watcherThread.start();
        logger.log("Config watcher started (interval: " + (CONFIG_CHECK_INTERVAL / 1000) + " seconds)");
    }

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "config.yml";
        new ProxyServer(configPath).start();
    }
}