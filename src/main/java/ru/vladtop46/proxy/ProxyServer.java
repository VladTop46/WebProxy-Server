package ru.vladtop46.proxy;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Locale;

public class ProxyServer {
    private static final int PROXY_PORT = 8023; // Замените на нужный порт
    private static BufferedWriter logWriter;
    private static String currentLogDate;
    private static int logFileIndex = 1;

    public static void main(String[] args) {
        try {
            initializeLogFile();
            ServerSocket serverSocket = new ServerSocket(PROXY_PORT);
            logMessage(String.format("Proxy server is running on port: %d", PROXY_PORT));
            System.out.println(String.format("Proxy server is running on port: %d", PROXY_PORT));

            while (true) {
                Socket clientSocket = serverSocket.accept();
                InetAddress clientAddress = clientSocket.getInetAddress();
                LocalDateTime currentTime = LocalDateTime.now();
                logMessage(String.format("Client connected - IP: %s, Time: %s, Message: Connected to Proxy",
                        clientAddress.getHostAddress(), currentTime));
                System.out.println(String.format("Client connected - IP: %s, Time: %s, Message: Connected to Proxy",
                        clientAddress.getHostAddress(), currentTime));

                new Thread(new ProxyHandler(clientSocket)).start();
            }
        } catch (IOException e) {
            logMessage("Error: " + e.getMessage());
        }
    }

    private static void initializeLogFile() throws IOException {
        String logDir = "logs";
        java.nio.file.Files.createDirectories(java.nio.file.Paths.get(logDir));

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        currentLogDate = dateFormat.format(new Date());

        logFileIndex = getNextLogFileIndex(logDir, currentLogDate);

        String logFileName = String.format("%s/webproxy-%s-%d.log", logDir, currentLogDate, logFileIndex);
        logWriter = new BufferedWriter(new FileWriter(logFileName, true));

        logMessage(String.format("Log file initialized: %s", logFileName));
    }

    private static int getNextLogFileIndex(String logDir, String currentDate) {
        String prefix = String.format("webproxy-%s-", currentDate);
        int maxIndex = 0;

        try (java.util.stream.Stream<String> files = java.nio.file.Files.list(java.nio.file.Paths.get(logDir)).map(path -> path.getFileName().toString())) {
            maxIndex = files.filter(file -> file.startsWith(prefix))
                    .map(file -> file.replace(prefix, "").replace(".log", ""))
                    .mapToInt(num -> {
                        try {
                            return Integer.parseInt(num);
                        } catch (NumberFormatException e) {
                            return 0;
                        }
                    })
                    .max()
                    .orElse(0);
        } catch (IOException e) {
            System.err.println("Error reading log files: " + e.getMessage());
        }

        return maxIndex + 1;
    }

    private static synchronized void logMessage(String message) {
        try {
            logWriter.write(message);
            logWriter.newLine();
            logWriter.flush();
        } catch (IOException e) {
            System.err.println("Error writing to log file: " + e.getMessage());
        }
    }

    private static class ProxyHandler implements Runnable {
        private static final int BUFFER_SIZE = 8192; // Или любое другое значение
        private final Socket clientSocket;

        public ProxyHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try {
                BufferedReader clientReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                BufferedWriter clientWriter = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));

                // Чтение первой строки (заголовок запроса)
                String requestLine = clientReader.readLine();

                if (requestLine == null || requestLine.isEmpty()) {
                    return;
                }

                String[] requestParts = requestLine.split(" ");
                if (requestParts.length != 3) {
                    return;
                }

                String method = requestParts[0];
                String url = requestParts[1];

                // Обработка метода CONNECT для HTTPS
                if ("CONNECT".equalsIgnoreCase(method)) {
                    handleConnectMethod(url, clientWriter, clientReader);
                } else {
                    handleHttpMethod(requestLine, clientReader, clientWriter);
                }
            } catch (IOException e) {
                logMessage("Error in ProxyHandler: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    logMessage("Error closing client socket: " + e.getMessage());
                }
            }
        }

        private void handleConnectMethod(String url, BufferedWriter clientWriter, BufferedReader clientReader) {
            String[] urlParts = url.split(":");
            String host = urlParts[0];
            int port = (urlParts.length > 1) ? Integer.parseInt(urlParts[1]) : 443;

            try (Socket serverSocket = new Socket(host, port)) {
                clientWriter.write("HTTP/1.1 200 Connection Established\r\n");
                clientWriter.write("Proxy-Agent: ProxyServer\r\n");
                clientWriter.write("\r\n");
                clientWriter.flush();

                InputStream clientInput = clientSocket.getInputStream();
                OutputStream clientOutput = clientSocket.getOutputStream();
                InputStream serverInput = serverSocket.getInputStream();
                OutputStream serverOutput = serverSocket.getOutputStream();

                Thread clientToServer = new Thread(() -> transferData(clientInput, serverOutput));
                Thread serverToClient = new Thread(() -> transferData(serverInput, clientOutput));

                clientToServer.start();
                serverToClient.start();

                clientToServer.join();
                serverToClient.join();
            } catch (IOException | InterruptedException e) {
                logMessage("Error in handleConnectMethod: " + e.getMessage());
            }
        }

        private void handleHttpMethod(String requestLine, BufferedReader clientReader, BufferedWriter clientWriter) {
            try (Socket serverSocket = new Socket()) {
                BufferedWriter serverWriter = new BufferedWriter(new OutputStreamWriter(serverSocket.getOutputStream()));
                BufferedReader serverReader = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));

                // Извлечение хоста и порта из заголовков
                String host = null;
                int port = 80;
                String line;
                while ((line = clientReader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("host:")) {
                        String hostPart = line.split(" ")[1];
                        String[] hostParts = hostPart.split(":");
                        host = hostParts[0];
                        if (hostParts.length > 1) {
                            try {
                                port = Integer.parseInt(hostParts[1]);
                            } catch (NumberFormatException e) {
                                port = 80; // Используем порт по умолчанию
                            }
                        }
                    }
                }

                // Если хост не найден в заголовках, извлечем его из URL
                if (host == null) {
                    try {
                        URL parsedUrl = new URL(requestLine.split(" ")[1]);
                        host = parsedUrl.getHost();
                        if (parsedUrl.getPort() != -1) {
                            port = parsedUrl.getPort();
                        }
                    } catch (Exception e) {
                        logMessage("Invalid URL: " + requestLine.split(" ")[1]);
                        return;
                    }
                }

                // Соединяемся с удаленным сервером
                serverSocket.connect(new InetSocketAddress(host, port));

                // Переписываем первый запрос клиента на сервер
                serverWriter.write(requestLine);
                serverWriter.newLine();

                // Переписываем остальные заголовки
                while ((line = clientReader.readLine()) != null && !line.isEmpty()) {
                    if (!line.toLowerCase(Locale.ROOT).startsWith("proxy-connection")) {
                        serverWriter.write(line);
                        serverWriter.newLine();
                    }
                }
                serverWriter.newLine();
                serverWriter.flush();

                // Переписываем ответ сервера клиенту
                char[] buffer = new char[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = serverReader.read(buffer)) != -1) {
                    clientWriter.write(buffer, 0, bytesRead);
                    clientWriter.flush();
                }
            } catch (IOException e) {
                logMessage("Error in handleHttpMethod: " + e.getMessage());
            }
        }

        private void transferData(InputStream input, OutputStream output) {
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    output.flush();
                }
            } catch (IOException e) {
                logMessage("Error in transferData: " + e.getMessage());
            }
        }
    }
}
