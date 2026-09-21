import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;

/**
 * Minimal Java HTTP process for Docker lifecycle demos (not production).
 */
public final class HelloServer {
  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));
    String name = System.getenv().getOrDefault("SERVICE_NAME", "java-demo");
    AtomicLong requestCount = new AtomicLong();

    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/hello", exchange -> {
      requestCount.incrementAndGet();
      byte[] body = ("Hello from " + name).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body);
      }
    });
    server.createContext("/metrics", exchange -> {
      requestCount.incrementAndGet();
      MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
      Runtime runtime = Runtime.getRuntime();
      long usedMemory = memory.getHeapMemoryUsage().getUsed();
      long maxMemory = memory.getHeapMemoryUsage().getMax();
      String body = String.format(
          "{\"service\":\"%s\",\"timestamp\":\"%s\",\"uptime_seconds\":%d,"
              + "\"requests_total\":%d,\"active_threads\":%d,\"heap_used_bytes\":%d,"
              + "\"heap_max_bytes\":%d,\"available_processors\":%d}%n",
          name,
          Instant.now(),
          ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
          requestCount.get(),
          Thread.activeCount(),
          usedMemory,
          maxMemory,
          runtime.availableProcessors());
      send(exchange, body, "application/json; charset=utf-8");
    });
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    System.out.println("====================================================");
    System.out.println("JAVA DEMO SERVICE STARTED SUCCESSFULLY");
    System.out.println("service-name=" + name + " port=" + port + " GET /hello ready");
    System.out.println("====================================================");
  }

  private static void send(com.sun.net.httpserver.HttpExchange exchange, String body,
      String contentType) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private HelloServer() {}
}
