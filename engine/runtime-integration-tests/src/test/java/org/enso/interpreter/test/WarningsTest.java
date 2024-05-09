package org.enso.interpreter.test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.enso.common.LanguageInfo;
import org.enso.common.MethodNames;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.error.Warning;
import org.enso.interpreter.runtime.error.WarningsLibrary;
import org.enso.interpreter.runtime.error.WithWarnings;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.hamcrest.core.AllOf;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class WarningsTest extends TestBase {

  private static Context ctx;
  private static ValuesGenerator generator;
  private static Value wrap;
  private static EnsoContext ensoContext;

  @BeforeClass
  public static void initEnsoContext() {
    ctx = createDefaultContext();
    generator = ValuesGenerator.create(ctx, ValuesGenerator.Language.ENSO);
    ensoContext =
        (EnsoContext)
            ctx.getBindings(LanguageInfo.ID)
                .invokeMember(MethodNames.TopScope.LEAK_CONTEXT)
                .asHostObject();
    var module =
        ctx.eval(
            "enso",
            """
    from Standard.Base import Warning

    wrap msg value = Warning.attach msg value
    """);
    wrap = module.invokeMember(MethodNames.Module.EVAL_EXPRESSION, "wrap");
  }

  @AfterClass
  public static void disposeContext() {
    ensoContext = null;
    ctx.close();
  }

  @Test
  public void doubleWithWarningsWrap() {
    var warn1 = Warning.create(ensoContext, "w1", this);
    var warn2 = Warning.create(ensoContext, "w2", this);
    var value = 42L;

    var with1 = WithWarnings.wrap(ensoContext, value, warn1);
    var with2 = WithWarnings.wrap(ensoContext, with1, warn2);

    assertEquals(value, with1.getValue());
    assertEquals(value, with2.getValue());
    Assert.assertArrayEquals(
        new Object[] {warn1}, with1.getWarningsArray(WarningsLibrary.getUncached(), false));
    Assert.assertArrayEquals(
        new Object[] {warn1, warn2}, with2.getWarningsArray(WarningsLibrary.getUncached(), false));
  }

  @Test
  public void wrapAndUnwrap() {
    var value = 42;
    WithWarnings without;
    try {
      without = WithWarnings.wrap(ensoContext, 42, new Warning[0]);
    } catch (AssertionError e) {
      // OK
      return;
    }
    fail("One shall not be created WithWarnings without any warnings " + without);
  }

  @Test
  public void warningIsAnException() {
    var warning42 = wrap.execute("warn:1", 42);
    var warningHi = wrap.execute("warn:2", "Hi");

    assertTrue("value is a number", warning42.isNumber());
    assertTrue("value is Int", warning42.fitsInInt());
    assertEquals(42, warning42.asInt());

    assertTrue("value2 is a text", warningHi.isString());
    assertTrue("value2 not a number", warning42.isNumber());
    assertEquals("Hi", warningHi.asString());

    assertTrue("value1 with warning is also an exception", warning42.isException());
    assertTrue("value2 with warning is also an exception", warningHi.isException());

    try {
      warning42.throwException();
      fail("Shouldn't reach here");
    } catch (PolyglotException ex) {
      assertEquals("warn:1", ex.getMessage());
    }

    var warningMulti = wrap.execute("warn:3", warning42);
    assertTrue("multi value is a number", warningMulti.isNumber());
    assertTrue("multi value is Int", warningMulti.fitsInInt());
    assertEquals(42, warningMulti.asInt());

    assertTrue("multi vlaue with warning is also an exception", warningMulti.isException());

    try {
      warningMulti.throwException();
      fail("Shouldn't reach here");
    } catch (PolyglotException ex) {
      assertThat(ex.getMessage(), AllOf.allOf(containsString("warn:1"), containsString("warn:3")));
    }
  }

  @Test
  public void allWarningsAreExceptions() throws Exception {
    for (var v : generator.allValues()) {
      if (v.isNull() || v.isBoolean()) {
        continue;
      }
      assertWarningsForAType(v);
    }
  }

  private void assertWarningsForAType(Value v) {
    var type = v.getMetaObject();

    var warning1 = wrap.execute("warn:once", v);
    var warning2 = wrap.execute("warn:twice", warning1);

    var warningType = warning2.getMetaObject();
    assertEquals("Types without and with warnings are the same", type, warningType);
    assertTrue("It is an exception. Type: " + type, warning2.isException());
    try {
      warning2.throwException();
    } catch (PolyglotException ex) {
      if (ex.getMessage() == null) {
        assertEquals(generator.typeError(), type);
        assertEquals(generator.typeError(), warningType);
      } else {
        assertThat(
            "Warning found for " + type,
            ex.getMessage(),
            AllOf.allOf(containsString("warn:once"), containsString("warn:twice")));
      }
    }
  }
}
