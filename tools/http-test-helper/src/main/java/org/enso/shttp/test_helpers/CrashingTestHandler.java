package org.enso.shttp.test_helpers;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import org.enso.shttp.SimpleHttpHandler;

public class CrashingTestHandler extends SimpleHttpHandler {
  @Override
  protected void doHandle(HttpExchange exchange) throws IOException {
    // This exception will be logged by SimpleHttpHandler, but that's OK - let's know that this
    // crash is happening.
    throw new RuntimeException("This handler crashes on purpose.");
  }
}
