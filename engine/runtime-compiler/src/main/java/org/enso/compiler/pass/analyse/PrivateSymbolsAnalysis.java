package org.enso.compiler.pass.analyse;

import java.util.List;
import java.util.UUID;
import org.enso.compiler.context.InlineContext;
import org.enso.compiler.context.ModuleContext;
import org.enso.compiler.core.IR;
import org.enso.compiler.core.Implicits;
import org.enso.compiler.core.ir.Expression;
import org.enso.compiler.core.ir.Module;
import org.enso.compiler.core.ir.Name;
import org.enso.compiler.core.ir.Pattern;
import org.enso.compiler.core.ir.expression.Case;
import org.enso.compiler.core.ir.expression.Case.Branch;
import org.enso.compiler.data.BindingsMap;
import org.enso.compiler.data.BindingsMap.ResolvedConstructor;
import org.enso.compiler.data.BindingsMap.ResolvedModule;
import org.enso.compiler.data.BindingsMap.ResolvedName;
import org.enso.compiler.pass.IRPass;
import org.enso.compiler.pass.resolve.Patterns$;
import org.enso.pkg.QualifiedName;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

/**
 * Iterates all the symbols in the IR and checks for their {@link
 * org.enso.compiler.data.BindingsMap.Resolution} metadata. If they are present, it checks if the
 * symbol is private and if it is used outside of its defining project. If so, a private-access
 * error IR is generated.
 */
public class PrivateSymbolsAnalysis implements IRPass {
  public static final PrivateSymbolsAnalysis INSTANCE = new PrivateSymbolsAnalysis();
  private UUID uuid;

  private PrivateSymbolsAnalysis() {}

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
    List<IRPass> passes =
        List.of(
            PrivateModuleAnalysis.INSTANCE, PrivateConstructorAnalysis.INSTANCE, Patterns$.MODULE$);
    return CollectionConverters.asScala(passes).toList();
  }

  @Override
  public Seq<IRPass> invalidatedPasses() {
    return nil();
  }

  @Override
  public <T extends IR> T updateMetadataInDuplicate(T sourceIr, T copyOfIr) {
    return IRPass.super.updateMetadataInDuplicate(sourceIr, copyOfIr);
  }

  @Override
  public Module runModule(Module ir, ModuleContext moduleContext) {
    var bindingsMap =
        (BindingsMap)
            Implicits.AsMetadata(ir)
                .unsafeGetMetadata(BindingAnalysis$.MODULE$, () -> "BindingsMap should be present");
    var newBindings =
        ir.bindings()
            .map(binding -> binding.mapExpressions(expr -> processExpression(expr, bindingsMap)));
    return ir.copy(
        ir.imports(),
        ir.exports(),
        newBindings,
        ir.location(),
        ir.passData(),
        ir.diagnostics(),
        ir.id());
  }

  /** Not supported for expressions. */
  @Override
  public Expression runExpression(Expression ir, InlineContext inlineContext) {
    return ir;
  }

  @SuppressWarnings("unchecked")
  private Expression processExpression(Expression expr, BindingsMap bindingsMap) {
    return switch (expr) {
      case Case.Expr caseExpr -> {
        var newBranches =
            caseExpr.branches().map(branch -> processCaseBranch(branch, bindingsMap)).toSeq();
        var newScrutinee = processExpression(caseExpr.scrutinee(), bindingsMap);
        yield caseExpr.copy(
            newScrutinee,
            newBranches,
            caseExpr.isNested(),
            caseExpr.location(),
            caseExpr.passData(),
            caseExpr.diagnostics(),
            caseExpr.id());
      }
      case Name name -> processName(name, bindingsMap);
      default -> expr.mapExpressions(e -> processExpression(e, bindingsMap));
    };
  }

  private Branch processCaseBranch(Branch branch, BindingsMap bindingsMap) {
    var pat = branch.pattern();
    var newPat = processCasePattern(pat, bindingsMap);
    var newExpr = processExpression(branch.expression(), bindingsMap);
    return branch.copy(
        newPat,
        newExpr,
        branch.terminalBranch(),
        branch.location(),
        branch.passData(),
        branch.diagnostics(),
        branch.id());
  }

  private Pattern processCasePattern(Pattern pattern, BindingsMap bindingsMap) {
    if (pattern instanceof Pattern.Constructor cons) {
      var consName = cons.constructor();
      var resolvedCons = tryResolveName(consName, bindingsMap);
      if (resolvedCons != null && isProjectPrivate(resolvedCons)) {
        var curProjName = getProjName(bindingsMap.currentModule().getName());
        var resolvedProjName = getProjName(resolvedCons.module().getName());
        if (!curProjName.equals(resolvedProjName)) {
          var reason =
              new org.enso.compiler.core.ir.expression.errors.Pattern.PrivateConstructor(
                  consName.name(), curProjName, resolvedProjName);
          return new org.enso.compiler.core.ir.expression.errors.Pattern(
              cons, reason, cons.passData(), cons.diagnostics());
        }
      }
    }
    return pattern.mapExpressions(e -> processExpression(e, bindingsMap));
  }

  private Expression processName(Name name, BindingsMap bindingsMap) {
    var resolvedName = tryResolveName(name, bindingsMap);
    if (resolvedName != null && isProjectPrivate(resolvedName)) {
      var curProjName = getProjName(bindingsMap.currentModule().getName());
      var resolvedProjName = getProjName(resolvedName.module().getName());
      if (!curProjName.equals(resolvedProjName)) {
        var reason =
            new org.enso.compiler.core.ir.expression.errors.Resolution.PrivateEntity(
                curProjName, resolvedProjName);
        return new org.enso.compiler.core.ir.expression.errors.Resolution(
            name, reason, name.passData(), name.diagnostics());
      }
    }
    return name.mapExpressions(e -> processExpression(e, bindingsMap));
  }

  private static boolean isProjectPrivate(ResolvedName resolvedName) {
    return switch (resolvedName) {
      case ResolvedConstructor resolvedCons -> resolvedCons.cons().isProjectPrivate();
      case ResolvedModule resolvedMod -> {
        if (resolvedMod.module()
            instanceof org.enso.compiler.data.BindingsMap$ModuleReference$Concrete concreteMod) {
          yield concreteMod.module().isPrivate();
        } else {
          yield false;
        }
      }
      default -> false;
    };
  }

  private static String getProjName(QualifiedName qualName) {
    if (qualName.pathAsJava().size() < 2) {
      return "unknown";
    } else {
      return qualName.pathAsJava().get(0) + "." + qualName.pathAsJava().get(1);
    }
  }

  private ResolvedName tryResolveName(Name name, BindingsMap bindingsMap) {
    return switch (name) {
      case Name.Literal lit -> {
        var resolved = bindingsMap.resolveName(lit.name());
        if (resolved.isRight()) {
          yield (ResolvedName) resolved.getOrElse(() -> null);
        } else {
          yield null;
        }
      }
      case Name.Qualified qual -> {
        var nameParts = qual.parts().map(Name::name);
        var resolved = bindingsMap.resolveQualifiedName(nameParts);
        if (resolved.isRight()) {
          yield (ResolvedName) resolved.getOrElse(() -> null);
        } else {
          yield null;
        }
      }
      default -> null;
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> scala.collection.immutable.List<T> nil() {
    return (scala.collection.immutable.List<T>) scala.collection.immutable.Nil$.MODULE$;
  }
}
