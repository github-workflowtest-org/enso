package org.enso.interpreter.instrument.job

import java.util.UUID
import org.enso.interpreter.instrument.InstrumentFrame
import org.enso.interpreter.instrument.execution.{Executable, RuntimeContext}
import org.enso.interpreter.runtime.state.ExecutionEnvironment
import org.enso.polyglot.runtime.Runtime.Api

/** A job responsible for executing a call stack for the provided context.
  *
  * @param contextId an identifier of a context to execute
  * @param stack a call stack to execute
  * @param executionEnvironment the execution environment to use
  */
class ExecuteJob(
  contextId: UUID,
  stack: List[InstrumentFrame],
  val executionEnvironment: Option[Api.ExecutionEnvironment],
  val visualizationTriggered: Boolean = false
) extends Job[Unit](
      List(contextId),
      isCancellable = true,
      // Interruptions may turn out to be problematic in enterprise edition of GraalVM
      // until https://github.com/oracle/graal/issues/3590 is resolved
      mayInterruptIfRunning = true
    ) {

  /** @inheritdoc */
  override def run(implicit ctx: RuntimeContext): Unit = {
    ctx.locking.withContextLock(
      ctx.locking.getOrCreateContextLock(contextId),
      this.getClass,
      () =>
        ctx.locking.withReadCompilationLock(
          this.getClass,
          () => {
            try {
              val context = ctx.executionService.getContext
              val originalExecutionEnvironment =
                executionEnvironment.map(_ => context.getExecutionEnvironment)
              executionEnvironment.foreach(env =>
                context.setExecutionEnvironment(
                  ExecutionEnvironment.forName(env.name)
                )
              )
              val outcome =
                try ProgramExecutionSupport.runProgram(contextId, stack)
                finally {
                  originalExecutionEnvironment.foreach(
                    context.setExecutionEnvironment
                  )
                }
              outcome match {
                case Some(diagnostic: Api.ExecutionResult.Diagnostic) =>
                  if (diagnostic.isError) {
                    ctx.endpoint.sendToClient(
                      Api.Response(Api.ExecutionFailed(contextId, diagnostic))
                    )
                  } else {
                    ctx.endpoint.sendToClient(
                      Api.Response(
                        Api.ExecutionUpdate(contextId, Seq(diagnostic))
                      )
                    )
                    ctx.endpoint.sendToClient(
                      Api.Response(Api.ExecutionComplete(contextId))
                    )
                  }
                case Some(failure: Api.ExecutionResult.Failure) =>
                  ctx.endpoint.sendToClient(
                    Api.Response(Api.ExecutionFailed(contextId, failure))
                  )
                case None =>
                  ctx.endpoint.sendToClient(
                    Api.Response(Api.ExecutionComplete(contextId))
                  )
              }
            } catch {
              case t: Throwable =>
                ctx.endpoint.sendToClient(
                  Api.Response(
                    Api.ExecutionFailed(
                      contextId,
                      Api.ExecutionResult.Failure(t.getMessage, None)
                    )
                  )
                )
            }
          }
        )
    )
  }

  override def toString(): String = {
    s"ExecuteJob(contextId=$contextId)"
  }

}

object ExecuteJob {

  /** Create execute job from the executable.
    *
    * @param executable the executable to run
    * @param visualizationTriggered true if execution is triggered by a visualization request, false otherwise
    * @return the new execute job
    */
  def apply(
    executable: Executable,
    visualizationTriggered: Boolean = false
  ): ExecuteJob =
    new ExecuteJob(
      executable.contextId,
      executable.stack.toList,
      None,
      visualizationTriggered
    )

  /** Create execute job from the context and stack.
    *
    * @param contextId the contextId to execute
    * @param stack the stack to execute
    * @return new execute job
    */
  def apply(contextId: UUID, stack: List[InstrumentFrame]): ExecuteJob =
    new ExecuteJob(contextId, stack, None)
}
