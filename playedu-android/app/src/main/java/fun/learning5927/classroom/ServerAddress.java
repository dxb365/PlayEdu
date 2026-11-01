package fun.learning5927.classroom;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class ServerAddress {
    private ServerAddress() {}

    static String normalize(String value) {
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))) {
                throw new IllegalArgumentException("HTTPS origin required");
            }
            return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), "/", null, null).toASCIIString();
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IllegalArgumentException("Invalid classroom address", exception);
        }
    }

    static boolean isInternal(String server, String url) {
        try {
            URI base = new URI(server), target = new URI(url);
            return "https".equalsIgnoreCase(target.getScheme()) && target.getRawUserInfo() == null
                    && base.getHost().equalsIgnoreCase(target.getHost())
                    && effectivePort(base) == effectivePort(target);
        } catch (URISyntaxException | NullPointerException exception) {
            return false;
        }
    }

    static boolean isExternalWebLink(String url) {
        try {
            URI uri = new URI(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (URISyntaxException | NullPointerException exception) {
            return false;
        }
    }

    private static int effectivePort(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }
}
