package org.enso.shttp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public abstract class SimpleHttpHandler implements HttpHandler {
  private final boolean logRequests = false;

  @Override
  public final void handle(HttpExchange exchange) throws IOException {
    try {
      if (logRequests) {
        System.out.println(
            "Handling request: " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
      }

      doHandle(exchange);
    } catch (IOException e) {
      e.printStackTrace();
      exchange.close();
      throw e;
    } catch (Exception e) {
      e.printStackTrace();
      exchange.close();
    }
  }

  protected abstract void doHandle(HttpExchange exchange) throws IOException;

  protected final void sendResponse(int code, String message, HttpExchange exchange)
      throws IOException {
    sendResponse(code, message, exchange, "text/plain; charset=utf-8");
  }

  protected final void sendResponse(
      int code, String message, HttpExchange exchange, String contentType) throws IOException {
    byte[] response = message.getBytes(StandardCharsets.UTF_8);
    if (contentType != null) {
      exchange.getResponseHeaders().add("Content-Type", contentType);
    }
    exchange.sendResponseHeaders(code, response.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(response);
    }
    exchange.close();
  }

  protected final void sendEmptyResponse(int code, HttpExchange exchange) throws IOException {
    exchange.sendResponseHeaders(code, -1);
    exchange.close();
  }

  protected String decodeBodyAsText(HttpExchange exchange) throws IOException {
    return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
  }
}
