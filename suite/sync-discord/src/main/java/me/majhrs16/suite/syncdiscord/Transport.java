package me.majhrs16.suite.syncdiscord;

import java.io.IOException;
import java.util.Map;

/**
 * HTTP transport seam so the Discord REST backend is testable without network.
 * Production uses the JDK {@link java.net.http.HttpClient}.
 */
public interface Transport {

    String post(String url, Map<String, String> headers, String jsonBody) throws IOException;
}