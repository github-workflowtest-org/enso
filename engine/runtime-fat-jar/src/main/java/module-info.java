open module org.enso.runtime {
  requires java.base;
  requires java.net.http;
  // Because of akka.util.Unsafe
  requires jdk.unsupported;
  requires org.graalvm.polyglot;
  requires org.graalvm.truffle;
  requires static org.slf4j;

  uses org.slf4j.spi.SLF4JServiceProvider;
  uses org.enso.interpreter.instrument.HandlerFactory;

  provides com.oracle.truffle.api.provider.TruffleLanguageProvider with
      org.enso.interpreter.EnsoLanguageProvider,
      org.enso.interpreter.epb.EpbLanguageProvider;

  provides com.oracle.truffle.api.instrumentation.provider.TruffleInstrumentProvider with
    org.enso.interpreter.instrument.ReplDebuggerInstrumentProvider,
    org.enso.interpreter.instrument.RuntimeServerInstrumentProvider,
    org.enso.interpreter.instrument.IdExecutionInstrumentProvider;


  // java.beans.Transient needed by Jackson jackson.databind.ext.Java7SupportImpl
  requires java.desktop;
  // also needed by Jackson to avoid
  // com.fasterxml.jackson.databind.exc.InvalidTypeIdException:
  //   Could not resolve type id 'org.enso.polyglot.data.Tree$Node' as a subtype of
  //   `org.enso.polyglot.data.Tree$Node<org.enso.polyglot.runtime.Runtime$Api$SuggestionUpdate>`: Not a subtype
  requires java.se;
}
