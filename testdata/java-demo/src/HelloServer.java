import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Minimal Java HTTP process for Docker lifecycle demos (not production).
 */
public final class HelloServer {
  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));
    String name = System.getenv().getOrDefault("SERVICE_NAME", "java-demo");

    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/hello", exchange -> {
      byte[] body = ("Hello from " + name).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body);
      }
    });
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    System.out.println("====================================================");
    System.out.println("JAVA DEMO SERVICE STARTED SUCCESSFULLY");
    System.out.println("service-name=" + name + " port=" + port + " GET /hello ready");
    System.out.println("====================================================");
  }

  private HelloServer() {}
}
