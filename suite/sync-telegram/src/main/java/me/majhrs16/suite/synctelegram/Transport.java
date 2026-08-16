package me.majhrs16.suite.synctelegram;

/**
 * HTTP transport seam so the Telegram backend is testable without network.
 * Production uses the JDK {@link java.net.http.HttpClient}.
 */
public interface Transport {

    String get(String url) throws java.io.IOException;

    String post(String url, String jsonBody) throws java.io.IOException;
}