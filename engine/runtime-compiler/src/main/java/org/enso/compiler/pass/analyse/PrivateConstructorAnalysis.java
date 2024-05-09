package org.enso.compiler.pass.analyse;

import java.util.List;
import java.util.UUID;
import org.enso.compiler.context.InlineContext;
import org.enso.compiler.context.ModuleContext;
import org.enso.compiler.core.IR;
import org.enso.compiler.core.ir.Expression;
import org.enso.compiler.core.ir.Module;
import org.enso.compiler.core.ir.expression.errors.Syntax;
import org.enso.compiler.core.ir.expression.errors.Syntax.InconsistentConstructorVisibility$;
import org.enso.compiler.core.ir.module.scope.Definition;
import org.enso.compiler.pass.IRPass;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

/**
 * Ensures that all type definitions have either all constructors public, or all constructors
 * private.
 */
public final class PrivateConstructorAnalysis implements IRPass {
  public static final PrivateConstructorAnalysis INSTANCE = new PrivateConstructorAnalysis();

  private UUID uuid;

  private PrivateConstructorAnalysis() {}

  @Override
  public void org$enso$compiler$pass$IRPass$_setter_$key_$eq(UUID v) {
    this.uuid = v;
  }

  @Override
  public UUID key() {
    return uuid;
  }

  @Override
  public Seq<IRPass> precursorPasses() {
    List<IRPass> passes = List.of(PrivateModuleAnalysis.INSTANCE);
    return CollectionConverters.asScala(passes).toList();
  }

  @Override
  @SuppressWarnings("unchecked")
  public Seq<IRPass> invalidatedPasses() {
    Object obj = scala.collection.immutable.Nil$.MODULE$;
    return (scala.collection.immutable.List<IRPass>) obj;
  }

  @Override
  public Module runModule(Module ir, ModuleContext moduleContext) {
    var newBindings =
        ir.bindings()
            .map(
                binding -> {
                  if (binding instanceof Definition.Type type) {
                    var privateCtorsCnt = type.members().filter(ctor -> ctor.isPrivate()).size();
                    var publicCtorsCnt = type.members().filter(ctor -> !ctor.isPrivate()).size();
                    var ctorsCnt = type.members().size();
                    if (!(privateCtorsCnt == ctorsCnt || publicCtorsCnt == ctorsCnt)) {
                      assert type.location().isDefined();
                      return Syntax.apply(
                          type.location().get(),
                          InconsistentConstructorVisibility$.MODULE$,
                          type.passData(),
                          type.diagnostics());
                    }
                  }
                  return binding;
                });
    return ir.copy(
        ir.imports(),
        ir.exports(),
        newBindings,
        ir.location(),
        ir.passData(),
        ir.diagnostics(),
        ir.id());
  }

  /** Not supported on a single expression. */
  @Override
  public Expression runExpression(Expression ir, InlineContext inlineContext) {
    return ir;
  }

  @Override
  public <T extends IR> T updateMetadataInDuplicate(T sourceIr, T copyOfIr) {
    return IRPass.super.updateMetadataInDuplicate(sourceIr, copyOfIr);
  }
}
