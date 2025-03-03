package org.enso.interpreter.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;
import org.enso.common.MethodNames;
import org.enso.test.utils.ContextUtils;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class BinaryOpFloatTest {
  private static final String[] OPERATIONS = {
    " +", " -", " ^", " *", " %", " <=", " <", " >=", " >", " /"
  };
  private static Value wrapReal;

  @Parameterized.Parameters(name = "({1}){0} ({2})")
  public static Object[][] parameters() {
    var r = new Random();
    var randomOps =
        Arrays.asList(OPERATIONS).stream()
            .map(
                (op) ->
                    new Object[] {
                      op,
                      r.nextDouble(),
                      switch (op) {
                        case " ^" -> r.nextDouble(10);
                        case " *" -> r.nextDouble(Integer.MAX_VALUE);
                        default -> r.nextDouble();
                      }
                    });
    var zeroOps =
        Arrays.asList(OPERATIONS).stream().map((op) -> new Object[] {op, r.nextDouble(), 0.0});
    var oneOps =
        Arrays.asList(OPERATIONS).stream().map((op) -> new Object[] {op, r.nextDouble(), 1.0});
    var extraOps = Stream.of(new Object[] {" %", 19.73, 12.10}, new Object[] {" ^", 10.12, 73.19});

    var s1 = Stream.concat(randomOps, zeroOps);
    var s2 = Stream.concat(s1, oneOps);
    var s3 = Stream.concat(s2, extraOps);
    return s3.toArray(Object[][]::new);
  }

  private static Context ctx;

  @BeforeClass
  public static void initContext() {
    ctx = ContextUtils.createDefaultContext();
    wrapReal =
        ctx.eval(
                "enso",
                """
                from Standard.Base import all

                type Wrap
                  Real v

                  + self that:Wrap = self.v+that.v
                  * self that:Wrap = self.v*that.v
                  / self that:Wrap = self.v/that.v
                  - self that:Wrap = self.v-that.v
                  ^ self that:Wrap = self.v^that.v
                  % self that:Wrap = self.v%that.v

                  < self that:Wrap = self.v<that.v
                  <= self that:Wrap = self.v<=that.v
                  > self that:Wrap = self.v>that.v
                  >= self that:Wrap = self.v>=that.v

                Wrap.from(that:Float) = Wrap.Real that

                wrap n:Float -> Wrap = n
                """)
            .invokeMember(MethodNames.Module.EVAL_EXPRESSION, "wrap");
  }

  @AfterClass
  public static void closeContext() {
    ctx.close();
  }

  private final String operation;
  private final double n1;
  private final double n2;

  public BinaryOpFloatTest(String operation, double n1, double n2) {
    this.operation = operation;
    this.n1 = n1;
    this.n2 = n2;
  }

  @Test
  public void verifyOperationOnForeignObject() {
    ContextUtils.executeInContext(
        ctx,
        () -> {
          var code = """
        fn a b = a{op} b
        """.replace("{op}", operation);
          var fn = ctx.eval("enso", code).invokeMember(MethodNames.Module.EVAL_EXPRESSION, "fn");

          var r1 = execute(fn, n1, n2);

          var wrap2 = ctx.asValue(new WrappedPrimitive(n2));
          var r2 = execute(fn, n1, wrap2);

          assertSameResult(r1, r2);
          return null;
        });
  }

  @Test
  public void verifyOperationWithConvertibleObject() {
    ContextUtils.executeInContext(
        ctx,
        () -> {
          var code = """
        fn a b = a{op} b
        """.replace("{op}", operation);
          var fn = ctx.eval("enso", code).invokeMember(MethodNames.Module.EVAL_EXPRESSION, "fn");

          var r1 = fn.execute(n1, n2);

          if (operation.contains("=") || operation.contains("<") || operation.contains(">")) {
            // avoid any >=< for now
            return null;
          }

          var wrap2 = wrapReal.execute(n2);
          var r2 = fn.execute(n1, wrap2);

          assertSameResult(r1, r2);
          return null;
        });
  }

  @Test
  public void verifyOperationOnConvertibleObject() {
    ContextUtils.executeInContext(
        ctx,
        () -> {
          var code = """
        fn a b = a{op} b
        """.replace("{op}", operation);
          var fn = ctx.eval("enso", code).invokeMember(MethodNames.Module.EVAL_EXPRESSION, "fn");

          var r1 = fn.execute(n1, n2);

          var wrap1 = wrapReal.execute(n1);
          var r2 = fn.execute(wrap1, n2);

          assertSameResult(r1, r2);
          return null;
        });
  }

  private Value execute(Value fn, Object... args) {
    try {
      return fn.execute(args);
    } catch (PolyglotException ex) {
      return ex.getGuestObject();
    }
  }

  private void assertSameResult(Value r1, Value r2) {
    assertEquals("r1: " + r1 + " r2: " + r2, r1.isException(), r2.isException());
    assertEquals("r1: " + r1 + " r2: " + r2, r1.isBoolean(), r2.isBoolean());
    assertEquals("r1: " + r1 + " r2: " + r2, r1.fitsInLong(), r2.fitsInLong());
    assertEquals("r1: " + r1 + " r2: " + r2, r1.fitsInDouble(), r2.fitsInDouble());
    assertEquals("r1: " + r1 + " r2: " + r2, r1.fitsInBigInteger(), r2.fitsInBigInteger());

    if (r1.fitsInLong()) {
      assertEquals("Results for " + n1 + operation + " " + n2, r1.asLong(), r2.asLong());
    } else if (r1.fitsInDouble()) {
      assertEquals("Results for " + n1 + operation + " " + n2, r1.asDouble(), r2.asDouble(), 0.1);
    } else if (r1.fitsInBigInteger()) {
      assertEquals(
          "Results for " + n1 + operation + " " + n2, r1.asBigInteger(), r2.asBigInteger());
    } else if (r1.isBoolean()) {
      assertEquals("Results for " + n1 + operation + " " + n2, r1.asBoolean(), r2.asBoolean());
    } else if (r1.isException()) {
      assertTrue("Both are exceptions for " + n1 + operation + " " + n2, r2.isException());
    } else {
      fail("Doesn't fit: " + r1);
    }
  }
}
