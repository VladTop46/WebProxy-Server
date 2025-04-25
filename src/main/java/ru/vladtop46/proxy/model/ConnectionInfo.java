package ru.vladtop46.proxy.model;

import java.net.Socket;
import java.util.UUID;

public class ConnectionInfo {
    private final String id;
    private final String clientIp;
    private final int clientPort;
    private String targetHost;
    private int targetPort;
    private String connectionType;
    private String status;

    public ConnectionInfo(Socket clientSocket) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.clientIp = clientSocket.getInetAddress().getHostAddress();
        this.clientPort = clientSocket.getPort();
        this.status = "INITIALIZED";
    }

    // Геттеры
    public String getId() {
        return id;
    }

    public String getClientIp() {
        return clientIp;
    }

    public int getClientPort() {
        return clientPort;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public String getConnectionType() {
        return connectionType;
    }

    public String getStatus() {
        return status;
    }

    // Сеттеры
    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost;
    }

    public void setTargetPort(int targetPort) {
        this.targetPort = targetPort;
    }

    public void setConnectionType(String connectionType) {
        this.connectionType = connectionType;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLogPrefix() {
        return String.format("[%s][%s:%d]", id, clientIp, clientPort);
    }
}