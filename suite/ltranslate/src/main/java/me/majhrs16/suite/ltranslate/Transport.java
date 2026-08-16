package me.majhrs16.suite.ltranslate;

/**
 * Minimal HTTP transport seam so the backend is testable without network.
 * The production implementation uses the JDK {@link java.net.http.HttpClient}.
 */
public interface Transport {

    String get(String url) throws java.io.IOException;

    String post(String url, String jsonBody) throws java.io.IOException;
}