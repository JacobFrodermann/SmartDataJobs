package de.smartdata.jobs.actions.networkactions;

/**
 * Provides pooled httpConnections
 * 
 * @author Florian Fehring
 */
import javax.net.ssl.*;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

public class HttpClientProvider {
    private static final HttpClient client;

    static {
        try {
            // TrustManager: akzeptiert alle Zertifikate
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }

                    @Override
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
            };

            // SSL-Kontext initialisieren
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            // HttpClient mit dem unsicheren SSL-Kontext
            client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .sslContext(sslContext)
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        } catch (Exception e) {
            throw new RuntimeException("Unable to initialize insecure HttpClient", e);
        }
    }

    public static HttpClient getClient() {
        return client;
    }
}


