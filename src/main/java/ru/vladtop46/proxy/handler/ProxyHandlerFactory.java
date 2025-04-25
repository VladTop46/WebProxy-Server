package ru.vladtop46.proxy.handler;

import ru.vladtop46.proxy.config.ProxyConfig;
import ru.vladtop46.proxy.security.AccessControl;

import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

public class ProxyHandlerFactory {
    private final AtomicReference<ProxyConfig> configRef;
    private final AtomicReference<AccessControl> accessControlRef;

    /**
     * Создает фабрику обработчиков с атомарной ссылкой на конфигурацию
     * @param configRef атомарная ссылка на конфигурацию прокси
     */
    public ProxyHandlerFactory(AtomicReference<ProxyConfig> configRef) {
        this.configRef = configRef;
        this.accessControlRef = new AtomicReference<>(new AccessControl(configRef.get()));
    }

    /**
     * Конструктор для обратной совместимости
     * @param config конфигурация прокси
     */
    public ProxyHandlerFactory(ProxyConfig config) {
        this.configRef = new AtomicReference<>(config);
        this.accessControlRef = new AtomicReference<>(new AccessControl(config));
    }

    /**
     * Обновляет AccessControl при изменении конфигурации
     */
    public void updateAccessControl() {
        accessControlRef.set(new AccessControl(configRef.get()));
    }

    /**
     * Создает обработчик соединения с актуальной конфигурацией
     * @param clientSocket клиентский сокет
     * @return обработчик соединения
     */
    public Runnable createHandler(Socket clientSocket) {
        // Получаем актуальную конфигурацию и контроль доступа
        ProxyConfig currentConfig = configRef.get();
        AccessControl currentAccessControl = accessControlRef.get();

        return new ProxyHandler(clientSocket, currentConfig, currentAccessControl);
    }
}