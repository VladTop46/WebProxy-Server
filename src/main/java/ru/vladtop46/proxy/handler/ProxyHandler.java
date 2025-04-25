package ru.vladtop46.proxy.handler;

import ru.vladtop46.proxy.config.ProxyConfig;
import ru.vladtop46.proxy.model.ConnectionInfo;
import ru.vladtop46.proxy.security.AccessControl;
import ru.vladtop46.proxy.logging.ProxyLogger;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class ProxyHandler implements Runnable {
    private static final int BUFFER_SIZE = 8192;
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Socket clientSocket;
    private final ProxyConfig config;
    private final AccessControl accessControl;
    private final ConnectionInfo connInfo;
    private final ProxyLogger logger;
    private boolean isWebSocket = false;

    public ProxyHandler(Socket clientSocket, ProxyConfig config, AccessControl accessControl) {
        this.clientSocket = clientSocket;
        this.config = config;
        this.accessControl = accessControl;
        this.connInfo = new ConnectionInfo(clientSocket);
        this.logger = new ProxyLogger(config.getServer().getLogsDirectory());
    }

    @Override
    public void run() {
        try {
            BufferedReader clientReader = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));
            BufferedWriter clientWriter = new BufferedWriter(
                    new OutputStreamWriter(clientSocket.getOutputStream()));

            String requestLine = clientReader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                logStatus("EMPTY_REQUEST");
                return;
            }

            String[] requestParts = requestLine.split(" ");
            if (requestParts.length != 3) {
                logStatus("INVALID_REQUEST_FORMAT");
                return;
            }

            String method = requestParts[0];
            String url = requestParts[1];
            logStatus("REQUEST_RECEIVED: " + method + " " + url);

            Map<String, String> headers = readHeaders(clientReader);
            String host = headers.get("host");
            if (host != null) {
                String[] hostParts = host.split(":");
                connInfo.setTargetHost(hostParts[0]);
                connInfo.setTargetPort(hostParts.length > 1 ?
                        Integer.parseInt(hostParts[1]) : ("CONNECT".equals(method) ? 443 : 80));

                // Проверка доступа к домену
                if (!accessControl.isDomainAllowed(connInfo.getTargetHost())) {
                    logStatus("DOMAIN_BLOCKED: " + connInfo.getTargetHost());
                    clientWriter.write(accessControl.getErrorPage());
                    clientWriter.flush();
                    return;
                }
            }

            // Обработка соединения в зависимости от типа
            if (isWebSocketUpgrade(headers)) {
                if (!config.getWebsocket().isEnabled()) {
                    logStatus("WEBSOCKET_DISABLED");
                    return;
                }
                connInfo.setConnectionType("WEBSOCKET");
                logStatus("WEBSOCKET_UPGRADE_REQUESTED");
                handleWebSocket(headers, clientSocket);
            } else if ("CONNECT".equalsIgnoreCase(method)) {
                connInfo.setConnectionType("HTTPS");
                logStatus("HTTPS_TUNNEL_REQUESTED");
                handleConnectMethod(url, clientWriter, clientReader);
            } else {
                connInfo.setConnectionType("HTTP");
                logStatus("HTTP_REQUEST_STARTED");
                handleHttpMethod(requestLine, headers, clientReader, clientWriter);
            }
        } catch (IOException e) {
            logStatus("ERROR: " + e.getMessage());
        } finally {
            try {
                if (!isWebSocket) {
                    clientSocket.close();
                    logStatus("CONNECTION_CLOSED");
                }
            } catch (IOException e) {
                logStatus("ERROR_CLOSING: " + e.getMessage());
            }
        }
    }

    private Map<String, String> readHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim().toLowerCase();
                String value = line.substring(colonIndex + 1).trim();
                headers.put(key, value);
            }
        }
        return headers;
    }

    private void handleWebSocket(Map<String, String> headers, Socket clientSocket) throws IOException {
        if (!config.getWebsocket().isEnabled()) {
            logStatus("WEBSOCKET_DISABLED");
            return;
        }

        boolean isWebRTC = isWebRTCConnection(headers);
        if (isWebRTC && !config.getWebsocket().isWebRtcEnabled()) {
            logStatus("WEBRTC_DISABLED");
            return;
        }

        try {
            Socket serverSocket = new Socket(connInfo.getTargetHost(), connInfo.getTargetPort());
            logStatus("WEBSOCKET_SERVER_CONNECTED");

            // Отправляем заголовки WebSocket серверу
            sendWebSocketHeaders(headers, serverSocket);

            // Запускаем обработку WebSocket фреймов
            startWebSocketThreads(serverSocket, clientSocket, isWebRTC);

        } catch (Exception e) {
            logStatus("WEBSOCKET_SETUP_ERROR: " + e.getMessage());
            throw e;
        }
    }

    private void sendWebSocketHeaders(Map<String, String> headers, Socket serverSocket) throws IOException {
        OutputStream serverOut = serverSocket.getOutputStream();
        StringBuilder request = new StringBuilder();
        request.append("GET / HTTP/1.1\r\n");

        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!header.getKey().toLowerCase().startsWith("proxy-")) {
                request.append(header.getKey())
                        .append(": ")
                        .append(header.getValue())
                        .append("\r\n");
            }
        }
        request.append("\r\n");

        serverOut.write(request.toString().getBytes());
        serverOut.flush();
        logStatus("WEBSOCKET_HANDSHAKE_SENT");
    }

    private void startWebSocketThreads(Socket serverSocket, Socket clientSocket, boolean isWebRTC) {
        Thread serverToClient = new Thread(() -> {
            try {
                handleWebSocketFrames(serverSocket.getInputStream(),
                        clientSocket.getOutputStream(),
                        "SERVER->CLIENT",
                        isWebRTC);
            } catch (IOException e) {
                logStatus("WEBSOCKET_S2C_ERROR: " + e.getMessage());
            }
        });

        Thread clientToServer = new Thread(() -> {
            try {
                handleWebSocketFrames(clientSocket.getInputStream(),
                        serverSocket.getOutputStream(),
                        "CLIENT->SERVER",
                        isWebRTC);
            } catch (IOException e) {
                logStatus("WEBSOCKET_C2S_ERROR: " + e.getMessage());
            }
        });

        isWebSocket = true;
        logStatus(isWebRTC ? "WEBRTC_STREAMS_ESTABLISHED" : "WEBSOCKET_STREAMS_ESTABLISHED");
        serverToClient.start();
        clientToServer.start();
    }

    // Остальные методы из оригинального кода остаются теми же,
    // но используют config, logger и connInfo

    private void logStatus(String status) {
        connInfo.setStatus(status);
        logger.log(String.format("%s [%s] %s",
                connInfo.getLogPrefix(),
                connInfo.getConnectionType(),
                status));
    }

    private void handleWebSocketFrames(InputStream in, OutputStream out,
                                       String direction, boolean isWebRTC) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        long totalBytes = 0;

        while ((bytesRead = in.read(buffer)) != -1) {
            totalBytes += bytesRead;

            if (bytesRead >= 2) {
                byte frameInfo = buffer[0];
                byte maskAndLength = buffer[1];

                int opcode = frameInfo & 0x0F;
                boolean isFinal = (frameInfo & 0x80) != 0;
                boolean isMasked = (maskAndLength & 0x80) != 0;

                if (isWebRTC) {
                    handleWebRTCFrame(buffer, bytesRead, opcode, isMasked, direction);
                }

                String frameType = getWebSocketFrameType(opcode);
                String connectionType = isWebRTC ? "WEBRTC" : "WEBSOCKET";
                logStatus(String.format("%s_FRAME [%s]: type=%s, final=%b, masked=%b, length=%d",
                        connectionType, direction, frameType, isFinal, isMasked, bytesRead));
            }

            out.write(buffer, 0, bytesRead);
            out.flush();

            if (totalBytes % (BUFFER_SIZE * 100) == 0) {
                logStatus(String.format("%s_TRANSFER [%s]: %d bytes transferred",
                        isWebRTC ? "WEBRTC" : "WEBSOCKET", direction, totalBytes));
            }
        }
    }

    private String getWebSocketFrameType(int opcode) {
        return switch (opcode) {
            case 0x0 -> "CONTINUATION";
            case 0x1 -> "TEXT";
            case 0x2 -> "BINARY";
            case 0x8 -> "CLOSE";
            case 0x9 -> "PING";
            case 0xA -> "PONG";
            default -> "UNKNOWN";
        };
    }

    private boolean isWebRTCConnection(Map<String, String> headers) {
        if (!config.getWebsocket().isWebRtcEnabled()) {
            return false;
        }

        String path = headers.getOrDefault("path", "");
        String host = headers.getOrDefault("host", "");
        String origin = headers.getOrDefault("origin", "");

        return (host.contains("discord.media") ||
                origin.contains("discord.com") ||
                path.contains("/voice") ||
                path.contains("/rtc"));
    }

    private void handleHttpMethod(String requestLine, Map<String, String> headers,
                                  BufferedReader clientReader, BufferedWriter clientWriter) throws IOException {
        try (Socket serverSocket = new Socket()) {
            serverSocket.connect(new InetSocketAddress(connInfo.getTargetHost(),
                    connInfo.getTargetPort()));
            logStatus("HTTP_SERVER_CONNECTED");

            BufferedWriter serverWriter = new BufferedWriter(
                    new OutputStreamWriter(serverSocket.getOutputStream()));
            BufferedReader serverReader = new BufferedReader(
                    new InputStreamReader(serverSocket.getInputStream()));

            // Send request headers
            serverWriter.write(requestLine + "\r\n");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (!header.getKey().toLowerCase().startsWith("proxy-")) {
                    serverWriter.write(header.getKey() + ": " + header.getValue() + "\r\n");
                }
            }
            serverWriter.write("\r\n");
            serverWriter.flush();
            logStatus("HTTP_HEADERS_SENT");

            // Transfer request body if exists
            if (headers.containsKey("content-length")) {
                int contentLength = Integer.parseInt(headers.get("content-length"));
                logStatus("HTTP_SENDING_BODY: " + contentLength + " bytes");
                transferRequestBody(clientReader, serverWriter, contentLength);
            }

            // Read and transfer response
            logStatus("HTTP_READING_RESPONSE");
            String statusLine = serverReader.readLine();
            if (statusLine != null) {
                logStatus("HTTP_RESPONSE: " + statusLine);
                transferHttpResponse(serverReader, clientWriter, statusLine);
            }
        }
    }

    private void handleConnectMethod(String url, BufferedWriter clientWriter,
                                     BufferedReader clientReader) throws IOException {
        String[] urlParts = url.split(":");
        connInfo.setTargetHost(urlParts[0]);
        connInfo.setTargetPort(urlParts.length > 1 ? Integer.parseInt(urlParts[1]) : 443);

        try (Socket serverSocket = new Socket(connInfo.getTargetHost(), connInfo.getTargetPort())) {
            logStatus("HTTPS_TUNNEL_ESTABLISHED");

            // Send connection established response
            clientWriter.write("HTTP/1.1 200 Connection Established\r\n");
            clientWriter.write("Proxy-Agent: ProxyServer\r\n");
            clientWriter.write("\r\n");
            clientWriter.flush();

            // Create bidirectional streams
            Thread clientToServer = new Thread(() -> {
                try {
                    transferData(clientSocket.getInputStream(),
                            serverSocket.getOutputStream(),
                            "CLIENT->SERVER");
                } catch (IOException e) {
                    logStatus("HTTPS_C2S_ERROR: " + e.getMessage());
                }
            });

            Thread serverToClient = new Thread(() -> {
                try {
                    transferData(serverSocket.getInputStream(),
                            clientSocket.getOutputStream(),
                            "SERVER->CLIENT");
                } catch (IOException e) {
                    logStatus("HTTPS_S2C_ERROR: " + e.getMessage());
                }
            });

            clientToServer.start();
            serverToClient.start();

            try {
                clientToServer.join();
                serverToClient.join();
                logStatus("HTTPS_TUNNEL_CLOSED");
            } catch (InterruptedException e) {
                logStatus("HTTPS_TUNNEL_INTERRUPTED");
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean isWebSocketUpgrade(Map<String, String> headers) {
        if (!config.getWebsocket().isEnabled()) {
            return false;
        }

        return "websocket".equalsIgnoreCase(headers.getOrDefault("upgrade", "")) &&
                "upgrade".equalsIgnoreCase(headers.getOrDefault("connection", "")) &&
                headers.containsKey("sec-websocket-key");
    }

    private void transferRequestBody(BufferedReader clientReader, BufferedWriter serverWriter,
                                     int contentLength) throws IOException {
        char[] buffer = new char[BUFFER_SIZE];
        int remaining = contentLength;
        int totalTransferred = 0;

        while (remaining > 0) {
            int read = clientReader.read(buffer, 0, Math.min(BUFFER_SIZE, remaining));
            if (read == -1) break;

            serverWriter.write(buffer, 0, read);
            remaining -= read;
            totalTransferred += read;

            if (totalTransferred % (BUFFER_SIZE * 10) == 0) {
                logStatus(String.format("HTTP_BODY_PROGRESS: %d/%d bytes",
                        totalTransferred, contentLength));
            }
        }

        serverWriter.flush();
        logStatus("HTTP_BODY_SENT: " + totalTransferred + " bytes");
    }

    private void transferHttpResponse(BufferedReader serverReader, BufferedWriter clientWriter,
                                      String statusLine) throws IOException {
        clientWriter.write(statusLine + "\r\n");

        // Read and transfer headers
        Map<String, String> responseHeaders = new HashMap<>();
        String line;
        while ((line = serverReader.readLine()) != null && !line.isEmpty()) {
            clientWriter.write(line + "\r\n");

            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim().toLowerCase();
                String value = line.substring(colonIndex + 1).trim();
                responseHeaders.put(key, value);
            }
        }
        clientWriter.write("\r\n");
        clientWriter.flush();

        // Log important response headers
        String contentType = responseHeaders.getOrDefault("content-type", "unknown");
        String contentLength = responseHeaders.get("content-length");
        logStatus(String.format("HTTP_RESPONSE_HEADERS: type=%s, length=%s",
                contentType, contentLength != null ? contentLength : "chunked"));

        // Transfer response body based on transfer type
        if (contentLength != null) {
            transferResponseBody(serverReader, clientWriter, Integer.parseInt(contentLength));
        } else if ("chunked".equalsIgnoreCase(responseHeaders.getOrDefault("transfer-encoding", ""))) {
            transferChunkedResponse(serverReader, clientWriter);
        } else {
            transferResponseUntilClosed(serverReader, clientWriter);
        }
    }

    private void transferResponseBody(BufferedReader serverReader, BufferedWriter clientWriter,
                                      int contentLength) throws IOException {
        char[] buffer = new char[BUFFER_SIZE];
        int remaining = contentLength;
        int totalTransferred = 0;

        while (remaining > 0) {
            int read = serverReader.read(buffer, 0, Math.min(BUFFER_SIZE, remaining));
            if (read == -1) break;

            clientWriter.write(buffer, 0, read);
            clientWriter.flush();
            remaining -= read;
            totalTransferred += read;

            if (totalTransferred % (BUFFER_SIZE * 10) == 0) {
                logStatus(String.format("HTTP_RESPONSE_PROGRESS: %d/%d bytes",
                        totalTransferred, contentLength));
            }
        }
        logStatus("HTTP_RESPONSE_COMPLETE: " + totalTransferred + " bytes");
    }

    private void transferChunkedResponse(BufferedReader serverReader, BufferedWriter clientWriter)
            throws IOException {
        String chunkSizeLine;
        int totalTransferred = 0;

        while ((chunkSizeLine = serverReader.readLine()) != null) {
            clientWriter.write(chunkSizeLine + "\r\n");

            int chunkSize = Integer.parseInt(chunkSizeLine.split(";")[0].trim(), 16);
            if (chunkSize == 0) {
                // Last chunk
                clientWriter.write("\r\n");
                clientWriter.flush();
                logStatus("HTTP_CHUNKED_RESPONSE_COMPLETE: " + totalTransferred + " bytes");
                break;
            }

            char[] buffer = new char[chunkSize];
            int read = serverReader.read(buffer, 0, chunkSize);
            clientWriter.write(buffer, 0, read);
            clientWriter.write("\r\n");
            clientWriter.flush();

            totalTransferred += read;
            if (totalTransferred % (BUFFER_SIZE * 10) == 0) {
                logStatus("HTTP_CHUNKED_RESPONSE_PROGRESS: " + totalTransferred + " bytes");
            }

            // Skip empty line after chunk
            serverReader.readLine();
        }
    }

    private void transferResponseUntilClosed(BufferedReader serverReader, BufferedWriter clientWriter)
            throws IOException {
        char[] buffer = new char[BUFFER_SIZE];
        int totalTransferred = 0;
        int read;

        while ((read = serverReader.read(buffer)) != -1) {
            clientWriter.write(buffer, 0, read);
            clientWriter.flush();
            totalTransferred += read;

            if (totalTransferred % (BUFFER_SIZE * 10) == 0) {
                logStatus("HTTP_STREAMING_RESPONSE_PROGRESS: " + totalTransferred + " bytes");
            }
        }
        logStatus("HTTP_STREAMING_RESPONSE_COMPLETE: " + totalTransferred + " bytes");
    }

    private void transferData(InputStream input, OutputStream output, String direction)
            throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        long totalTransferred = 0;

        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
            output.flush();
            totalTransferred += bytesRead;

            if (totalTransferred % (BUFFER_SIZE * 128) == 0) {
                logStatus(String.format("DATA_TRANSFER [%s]: %d bytes",
                        direction, totalTransferred));
            }
        }

        logStatus(String.format("DATA_TRANSFER_COMPLETE [%s]: %d bytes",
                direction, totalTransferred));
    }

    private void handleWebRTCFrame(byte[] buffer, int length, int opcode, boolean isMasked,
                                   String direction) {
        try {
            // Skip WebSocket header
            int dataOffset = 2;
            if (isMasked) {
                dataOffset += 4; // Skip mask
            }

            // Process text frames that might contain ICE candidates or SDP
            if (opcode == 0x1 && length > dataOffset) {
                byte[] payload = new byte[length - dataOffset];
                System.arraycopy(buffer, dataOffset, payload, 0, payload.length);

                if (isMasked) {
                    // Unmask data
                    byte[] mask = new byte[4];
                    System.arraycopy(buffer, dataOffset - 4, mask, 0, 4);
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] = (byte)(payload[i] ^ mask[i % 4]);
                    }
                }

                // Convert to string and check for WebRTC signaling data
                String data = new String(payload);
                if (data.contains("candidate") || data.contains("sdp")) {
                    logStatus(String.format("WEBRTC_SIGNALING [%s]: %s",
                            direction, data.substring(0, Math.min(100, data.length()))));
                }
            }
        } catch (Exception e) {
            logStatus("WEBRTC_FRAME_PARSE_ERROR: " + e.getMessage());
        }
    }
}