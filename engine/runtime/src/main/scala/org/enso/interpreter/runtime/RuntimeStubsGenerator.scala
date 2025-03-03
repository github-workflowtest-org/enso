package org.enso.interpreter.runtime

import org.enso.compiler.data.BindingsMap
import org.enso.compiler.core.{CompilerError, IR}
import org.enso.compiler.core.Implicits.AsMetadata
import org.enso.compiler.pass.analyse.BindingAnalysis
import org.enso.interpreter.runtime.builtin.Builtins
import org.enso.interpreter.runtime.data.atom.AtomConstructor
import org.enso.interpreter.runtime.data.Type
import org.enso.interpreter.runtime.scope.ModuleScope

/** Generates stubs of runtime representations of atom constructors, to allow
  * [[IrToTruffle the code generator]] to refer to constructors that are not
  * fully generated yet.
  */
class RuntimeStubsGenerator(builtins: Builtins) {

  /** Runs the stage on the given module.
    *
    * @param module the module to generate stubs in.
    */
  def run(ir: IR, scope: ModuleScope.Builder): Unit = {
    val localBindings = ir.unsafeGetMetadata(
      BindingAnalysis,
      "Non-parsed module used in stubs generator"
    )
    val types = localBindings.definedEntities.collect {
      case t: BindingsMap.Type => t
    }
    types.foreach { tp =>
      if (tp.builtinType) {
        val builtinType = builtins.getBuiltinType(tp.name)
        if (builtinType == null) {
          throw new CompilerError("Unknown @Builtin_Type " + tp.name)
        }
        if (
          Set(tp.members: _*).map(_.name) != Set(
            builtinType.getConstructors.toIndexedSeq: _*
          )
            .map(_.getName)
        ) {
          throw new CompilerError(
            s"Wrong constructors declared in the builtin ${tp.name}."
          )
        }
        scope.registerType(builtinType.getType)
        builtinType.getType.setShadowDefinitions(scope, true)
      } else {
        val isTypeProjectPrivate =
          tp.members.nonEmpty && tp.members.forall(_.isProjectPrivate)
        val createdType =
          if (tp.members.nonEmpty || tp.builtinType) {
            Type.create(
              tp.name,
              scope,
              builtins.any(),
              builtins.any(),
              false,
              isTypeProjectPrivate
            )
          } else {
            Type.createSingleton(
              tp.name,
              scope,
              builtins.any(),
              false,
              isTypeProjectPrivate
            )
          }
        val rtp = scope.registerType(createdType)
        tp.members.foreach { cons =>
          val constructor =
            new AtomConstructor(
              cons.name,
              scope.getModule(),
              rtp,
              false
            )
          rtp.registerConstructor(constructor)
        }
      }
    }
  }
}
