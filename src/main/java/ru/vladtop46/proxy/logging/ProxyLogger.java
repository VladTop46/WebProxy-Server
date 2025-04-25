package ru.vladtop46.proxy.logging;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ProxyLogger {
    private static final DateTimeFormatter LOG_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final BufferedWriter logWriter;

    public ProxyLogger(String logDir) {
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(logDir));
            String logFile = String.format("%s/proxy-%s.log",
                    logDir, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            this.logWriter = new BufferedWriter(new FileWriter(logFile, true));
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize logger", e);
        }
    }

    public synchronized void log(String message) {
        try {
            String timestamp = LocalDateTime.now().format(LOG_TIME_FORMATTER);
            String logEntry = String.format("[%s] %s", timestamp, message);
            logWriter.write(logEntry);
            logWriter.newLine();
            logWriter.flush();
            System.out.println(logEntry);
        } catch (IOException e) {
            System.err.println("Error writing to log: " + e.getMessage());
        }
    }
}