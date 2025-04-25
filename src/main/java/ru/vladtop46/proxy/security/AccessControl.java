package ru.vladtop46.proxy.security;

import ru.vladtop46.proxy.config.ProxyConfig;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class AccessControl {
    private final ProxyConfig config;
    private final List<IpRange> ipRanges = new ArrayList<>();

    public AccessControl(ProxyConfig config) {
        this.config = config;
        initIpRanges();
    }

    private void initIpRanges() {
        ipRanges.clear();
        List<String> whitelistedIps = config.getSecurity().getWhitelistedIps();
        if (whitelistedIps != null) {
            for (String ipEntry : whitelistedIps) {
                try {
                    ipRanges.add(new IpRange(ipEntry));
                } catch (Exception e) {
                    System.err.println("Invalid IP or CIDR notation: " + ipEntry + " - " + e.getMessage());
                }
            }
        }
    }

    public boolean isIpAllowed(InetAddress address) {
        if (!config.getSecurity().isWhitelistEnabled()) {
            return true;
        }

        String ip = address.getHostAddress();

        // Проверяем каждый разрешенный IP или диапазон
        for (IpRange range : ipRanges) {
            if (range.contains(ip)) {
                return true;
            }
        }

        return false;
    }

    public boolean isDomainAllowed(String domain) {
        return !config.getBlockedDomains().contains(domain.toLowerCase());
    }

    public String getErrorPage() {
        return String.format("""
                HTTP/1.1 403 Forbidden
                Content-Type: text/html; charset=UTF-8
                Connection: close
                
                <!DOCTYPE html>
                <html>
                <head><title>%s</title></head>
                <body>
                    <h1>%s</h1>
                    <p>%s</p>
                </body>
                </html>
                """,
                config.getErrorPage().getTitle(),
                config.getErrorPage().getTitle(),
                config.getErrorPage().getMessage());
    }

    /**
     * Класс для представления одиночного IP или диапазона IP в CIDR-нотации
     */
    private static class IpRange {
        private final long networkAddress;
        private final long networkMask;
        private final boolean isSingleIp;

        /**
         * Создает представление IP-адреса или диапазона
         * @param cidrNotation IP адрес (например, "192.168.1.1") или CIDR (например, "192.168.1.0/24")
         */
        public IpRange(String cidrNotation) {
            if (cidrNotation.contains("/")) {
                // CIDR нотация
                String[] parts = cidrNotation.split("/");
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Invalid CIDR notation: " + cidrNotation);
                }

                int prefixLength = Integer.parseInt(parts[1]);
                if (prefixLength < 0 || prefixLength > 32) {
                    throw new IllegalArgumentException("Invalid prefix length: " + prefixLength);
                }

                this.networkAddress = ipToLong(parts[0]);
                this.networkMask = prefixLength == 0 ? 0 : (~0L << (32 - prefixLength));
                this.isSingleIp = (prefixLength == 32);
            } else {
                // Одиночный IP
                this.networkAddress = ipToLong(cidrNotation);
                this.networkMask = ~0L; // 32 bits, all 1's
                this.isSingleIp = true;
            }
        }

        /**
         * Проверяет, содержится ли указанный IP в этом диапазоне
         * @param ipAddress IP адрес для проверки
         * @return true если IP содержится в диапазоне, иначе false
         */
        public boolean contains(String ipAddress) {
            long ip = ipToLong(ipAddress);

            if (isSingleIp) {
                return ip == networkAddress;
            } else {
                return (ip & networkMask) == (networkAddress & networkMask);
            }
        }

        /**
         * Преобразует строковое представление IP в числовое (long)
         * @param ipAddress IP адрес в формате "x.x.x.x"
         * @return числовое представление IP
         */
        private static long ipToLong(String ipAddress) {
            String[] octets = ipAddress.split("\\.");
            if (octets.length != 4) {
                throw new IllegalArgumentException("Invalid IP address format: " + ipAddress);
            }

            long result = 0;
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(octets[i]);
                if (octet < 0 || octet > 255) {
                    throw new IllegalArgumentException("Invalid octet value: " + octet);
                }
                result = (result << 8) | octet;
            }
            return result;
        }
    }
}