package org.enso.interpreter.runtime.system;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.io.TruffleProcessBuilder;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.apache.commons.lang3.SystemUtils;
import org.enso.interpreter.dsl.Builtin;
import org.enso.interpreter.node.expression.builtin.text.util.ExpectStringNode;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.data.atom.Atom;
import org.enso.interpreter.runtime.data.text.Text;
import org.enso.interpreter.runtime.data.vector.ArrayLikeCoerceToArrayNode;
import org.enso.interpreter.service.error.ExitException;

public class System {

  private static final Text LINUX = Text.create("linux");
  private static final Text MACOS = Text.create("macos");
  private static final Text WINDOWS = Text.create("windows");
  private static final Text UNKNOWN = Text.create("unknown");

  @Builtin.Method(description = "Get the type of operating system.", autoRegister = false)
  @CompilerDirectives.TruffleBoundary
  public static Text os() {
    if (SystemUtils.IS_OS_LINUX) return LINUX;
    if (SystemUtils.IS_OS_MAC_OSX) return MACOS;
    if (SystemUtils.IS_OS_WINDOWS) return WINDOWS;
    return UNKNOWN;
  }

  @Builtin.Method(description = "Check if the operating system is UNIX.", autoRegister = false)
  @CompilerDirectives.TruffleBoundary
  public static boolean is_unix() {
    return SystemUtils.IS_OS_UNIX;
  }

  @Builtin.Method(description = "Gets the nanosecond resolution system time.", autoRegister = false)
  @CompilerDirectives.TruffleBoundary
  public static long nanoTime() {
    return java.lang.System.nanoTime();
  }

  @Builtin.Method(
      description = "Exits the process, returning the provided code.",
      autoRegister = false)
  @CompilerDirectives.TruffleBoundary
  public static void exit(long code) {
    var ctx = EnsoContext.get(null);
    if (ctx.isInteractiveMode()) {
      // In interactive mode, the ExitException should be caught and handled by one of
      // the instruments that should take care of proper context disposal.
      throw new ExitException((int) code);
    } else {
      // While not in interactive mode, it is safe to directly call
      // TruffleCOntext.exitContext
      ctx.exit((int) code);
    }
  }

  @Builtin.Specialize
  @Builtin.Method(
      description = "Create a system process, returning the exit code.",
      autoRegister = false)
  @Builtin.WrapException(from = IOException.class)
  @Builtin.WrapException(from = InterruptedException.class)
  @CompilerDirectives.TruffleBoundary
  @ExplodeLoop
  public static Atom createProcess(
      EnsoContext ctx,
      Object command,
      Object arguments,
      Object input,
      boolean redirectIn,
      boolean redirectOut,
      boolean redirectErr,
      @Cached ArrayLikeCoerceToArrayNode coerce,
      @Cached ExpectStringNode expectStringNode)
      throws IOException, InterruptedException {
    Object[] arrArguments = coerce.execute(arguments);
    String[] cmd = new String[arrArguments.length + 1];
    cmd[0] = expectStringNode.execute(command);
    for (int i = 1; i <= arrArguments.length; i++) {
      cmd[i] = expectStringNode.execute(arrArguments[i - 1]);
    }
    TruffleProcessBuilder pb = ctx.newProcessBuilder(cmd);

    Process p = pb.start();
    ByteArrayInputStream in = new ByteArrayInputStream(expectStringNode.execute(input).getBytes());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    boolean startedWritingtoOut = false;
    try (OutputStream processIn = p.getOutputStream()) {
      InputStream stdin;
      if (redirectIn) {
        stdin = ctx.getIn();
      } else {
        stdin = in;
      }
      int nread;
      startedWritingtoOut = true;
      byte[] buf = new byte[8096];
      while (stdin.available() > 0 && (nread = stdin.read(buf)) != -1) {
        processIn.write(buf, 0, nread);
      }
    } catch (IOException e) {
      // Getting the output stream of a finished process results in an IOException.
      // We can ignore it at this point.
      // Unless this exception is from writing to buffer/reading from stdin.
      if (startedWritingtoOut) throw e;
    }

    // First read from stdout and stderr from the subprocess to prevent a deadlock.
    // In other words, call `p.waitFor()` after reading from the streams.
    // For more info, see https://stackoverflow.com/a/882795/4816269
    try (InputStream processOut = p.getInputStream()) {
      OutputStream stdout;
      if (redirectOut) {
        stdout = ctx.getOut();
      } else {
        stdout = out;
      }
      int nread;
      byte[] buf = new byte[8096];
      while ((nread = processOut.read(buf)) != -1) {
        stdout.write(buf, 0, nread);
      }
    }

    try (InputStream processErr = p.getErrorStream()) {
      OutputStream stderr;
      if (redirectErr) {
        stderr = ctx.getErr();
      } else {
        stderr = err;
      }
      int nread;
      byte[] buf = new byte[8096];
      while ((nread = processErr.read(buf)) != -1) {
        stderr.write(buf, 0, nread);
      }
    }

    p.waitFor();
    long exitCode = p.exitValue();
    Text returnOut = Text.create(out.toString());
    Text returnErr = Text.create(err.toString());

    return ctx.getBuiltins().system().makeSystemResult(exitCode, returnOut, returnErr);
  }
}
