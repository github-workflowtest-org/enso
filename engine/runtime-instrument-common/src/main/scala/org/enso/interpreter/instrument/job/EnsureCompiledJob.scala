package org.enso.interpreter.instrument.job

import cats.implicits._
import com.oracle.truffle.api.TruffleLogger
import org.enso.compiler.CompilerResult
import org.enso.compiler.context._
import org.enso.compiler.core.Implicits.AsMetadata
import org.enso.compiler.core.{ExternalID, IR}
import org.enso.compiler.core.ir.{
  expression,
  Diagnostic,
  IdentifiedLocation,
  Warning
}
import org.enso.compiler.core.ir.expression.Error
import org.enso.compiler.data.BindingsMap
import org.enso.compiler.pass.analyse.{
  CachePreferenceAnalysis,
  DataflowAnalysis,
  GatherDiagnostics
}
import org.enso.interpreter.instrument.execution.{
  LocationResolver,
  RuntimeContext
}
import org.enso.interpreter.instrument.{
  CacheInvalidation,
  InstrumentFrame,
  Visualization
}
import org.enso.interpreter.runtime.Module
import org.enso.interpreter.service.error.ModuleNotFoundForFileException
import org.enso.pkg.QualifiedName
import org.enso.polyglot.CompilationStage
import org.enso.polyglot.runtime.Runtime.Api
import org.enso.polyglot.runtime.Runtime.Api.StackItem
import org.enso.text.buffer.Rope

import java.io.File
import java.util.UUID
import java.util.logging.Level

import scala.jdk.OptionConverters._

/** A job that ensures that specified files are compiled.
  *
  * @param files a files to compile
  * @param isCancellable a flag indicating if the job is cancellable
  */
final class EnsureCompiledJob(
  protected val files: Iterable[File],
  isCancellable: Boolean = true
) extends Job[EnsureCompiledJob.CompilationStatus](
      List.empty,
      isCancellable,
      false
    ) {

  import EnsureCompiledJob.CompilationStatus

  /** @inheritdoc */
  override def run(implicit ctx: RuntimeContext): CompilationStatus = {
    val writeLockTimestamp             = ctx.locking.acquireWriteCompilationLock()
    implicit val logger: TruffleLogger = ctx.executionService.getLogger

    try {
      val compilationResult = ensureCompiledFiles(files)
      setCacheWeights()
      compilationResult
    } finally {
      ctx.locking.releaseWriteCompilationLock()
      logger.log(
        Level.FINEST,
        s"Kept write compilation lock [EnsureCompiledJob] for ${System.currentTimeMillis() - writeLockTimestamp} milliseconds"
      )
    }
  }

  /** Run the scheduled compilation and invalidation logic, and send the
    * suggestion updates.
    *
    * @param files the list of files to compile.
    * @param ctx the runtime context
    */
  private def ensureCompiledFiles(
    files: Iterable[File]
  )(implicit ctx: RuntimeContext, logger: TruffleLogger): CompilationStatus = {
    val modules = files.flatMap { file =>
      ctx.executionService.getContext.getModuleForFile(file).toScala
    }
    val moduleCompilationStatus = modules.map(ensureCompiledModule)
    val modulesInScope =
      getProjectModulesInScope.filterNot(m => modules.exists(_ == m))
    val scopeCompilationStatus = ensureCompiledScope(modulesInScope)
    (moduleCompilationStatus.flatten ++ scopeCompilationStatus).maxOption
      .getOrElse(CompilationStatus.Success)
  }

  /** Run the scheduled compilation and invalidation logic, and send the
    * suggestion updates.
    *
    * @param module the module to compile.
    * @param ctx the runtime context
    */
  private def ensureCompiledModule(
    module: Module
  )(implicit
    ctx: RuntimeContext,
    logger: TruffleLogger
  ): Option[CompilationStatus] = {
    val result = compile(module)
    result match {
      case Left(ex) =>
        logger.log(
          Level.WARNING,
          s"Error while ensureCompiledModule ${module.getName}",
          ex
        )
        Some(CompilationStatus.Failure)
      case _ =>
        applyEdits(new File(module.getPath)).map { changeset =>
          compile(module)
            .map { _ =>
              // Side-effect: ensures that module's source is correctly initialized.
              module.getSource()
              invalidateCaches(module, changeset)
              val state =
                ctx.state.suggestions.getOrCreateFresh(module, module.getIr)
              if (state.isIndexed) {
                ctx.jobProcessor.runBackground(
                  AnalyzeModuleJob(module, state, module.getIr(), changeset)
                )
              } else {
                AnalyzeModuleJob.analyzeModule(
                  module,
                  state,
                  module.getIr(),
                  changeset
                )
              }
              runCompilationDiagnostics(module)
            }
            .getOrElse(CompilationStatus.Failure)
        }
    }
  }

  /** Compile all modules in the scope and send the extracted suggestions.
    *
    * @param ctx the runtime context
    */
  private def ensureCompiledScope(modulesInScope: Iterable[Module])(implicit
    ctx: RuntimeContext
  ): Iterable[CompilationStatus] = {
    val notIndexedModulesInScope =
      modulesInScope.filter(m => {
        val state = ctx.state.suggestions.find(m)
        state == null || !state.isIndexed
      })
    val (modulesToAnalyzeBuilder, compilationStatusesBuilder) =
      notIndexedModulesInScope.foldLeft(
        (Set.newBuilder[Module], Vector.newBuilder[CompilationStatus])
      ) { case ((modules, statuses), module) =>
        compile(module) match {
          case Left(err) =>
            ctx.executionService.getLogger
              .log(Level.SEVERE, s"Compilation error in ${module.getName}", err)
            sendFailureUpdate(
              Api.ExecutionResult.Failure(
                err.getMessage,
                Option(module.getPath).map(new File(_))
              )
            )
            (modules, statuses += CompilationStatus.Failure)
          case Right(compilerResult) =>
            val status = runCompilationDiagnostics(module)
            (
              modules
                .addAll(
                  compilerResult.compiledModules
                    .map(Module.fromCompilerModule(_))
                )
                .addOne(module),
              statuses += status
            )
        }
      }
    val modulesToAnalyze = modulesToAnalyzeBuilder.result()
    if (modulesToAnalyze.nonEmpty) {
      ctx.jobProcessor.runBackground(
        AnalyzeModuleInScopeJob(
          modulesToAnalyze.map(m =>
            (
              m,
              ctx.state.suggestions.getOrCreateFresh(m, m.getIr),
              m.getSource() != null
            )
          )
        )
      )
    }
    compilationStatusesBuilder.result()
  }

  /** Extract compilation diagnostics from the module and send the diagnostic
    * updates.
    *
    * @param module the module to analyze
    * @param ctx the runtime context
    * @return the compilation outcome
    */
  private def runCompilationDiagnostics(module: Module)(implicit
    ctx: RuntimeContext
  ): CompilationStatus = {
    val pass = GatherDiagnostics
      .runModule(
        module.getIr,
        ModuleContext(
          module.asCompilerModule(),
          compilerConfig = ctx.executionService.getContext.getCompilerConfig
        )
      )
      .unsafeGetMetadata(
        GatherDiagnostics,
        "No diagnostics metadata right after the gathering pass."
      )
      .diagnostics
    val diagnostics = pass.collect {
      case warn: Warning =>
        createDiagnostic(Api.DiagnosticType.Warning, module, warn)
      case error: Error =>
        createDiagnostic(Api.DiagnosticType.Error, module, error)
    }
    sendDiagnosticUpdates(diagnostics)
    getCompilationStatus(diagnostics)
  }

  /** Create Api diagnostic message from the `IR` node.
    *
    * @param kind the diagnostic type
    * @param module the module to analyze
    * @param diagnostic the diagnostic `IR` node
    * @return the diagnostic message
    */
  private def createDiagnostic(
    kind: Api.DiagnosticType,
    module: Module,
    diagnostic: Diagnostic
  ): Api.ExecutionResult.Diagnostic = {
    val source = module.getSource

    def fileLocationFromSection(loc: IdentifiedLocation) = {
      val section =
        source.createSection(loc.location().start(), loc.location().length());
      val locStr = "" + section.getStartLine() + ":" + section
        .getStartColumn() + "-" + section.getEndLine() + ":" + section
        .getEndColumn()
      source.getName() + "[" + locStr + "]";
    }
    Api.ExecutionResult.Diagnostic(
      kind,
      Option(diagnostic.formattedMessage(fileLocationFromSection)),
      Option(module.getPath).map(new File(_)),
      diagnostic.location
        .map(loc =>
          LocationResolver
            .locationToRange(loc.location, source.getCharacters)
        ),
      diagnostic.location
        .flatMap(LocationResolver.getExpressionId(module.getIr, _))
        .map(_.externalId),
      Vector()
    )
  }

  /** Compile the module.
    *
    * @param module the module to compile.
    * @param ctx the runtime context
    * @return the compiled module
    */
  private def compile(
    module: Module
  )(implicit ctx: RuntimeContext): Either[Throwable, CompilerResult] =
    Either.catchNonFatal {
      val compilationStage = module.getCompilationStage
      if (!compilationStage.isAtLeast(CompilationStage.AFTER_CODEGEN)) {
        ctx.executionService.getLogger
          .log(Level.FINEST, s"Compiling ${module.getName}.")
        val result = ctx.executionService.getContext.getCompiler
          .run(module.asCompilerModule())
        result.copy(compiledModules =
          result.compiledModules.filter(_.getName != module.getName)
        )
      } else {
        CompilerResult.empty
      }
    }

  /** Apply pending edits to the file.
    *
    * @param file the file to apply edits to
    * @param ctx the runtime context
    * @return the [[Changeset]] after applying the edits to the source
    */
  private def applyEdits(
    file: File
  )(implicit
    ctx: RuntimeContext,
    logger: TruffleLogger
  ): Option[Changeset[Rope]] = {
    val fileLockTimestamp = ctx.locking.acquireFileLock(file)
    try {
      val pendingEditsLockTimestamp = ctx.locking.acquirePendingEditsLock()
      try {
        val pendingEdits = ctx.state.pendingEdits.dequeue(file)
        val edits        = pendingEdits.map(_.edit)
        val shouldExecute =
          pendingEdits.isEmpty || pendingEdits.exists(_.execute)
        val module = ctx.executionService.getContext
          .getModuleForFile(file)
          .orElseThrow(() => new ModuleNotFoundForFileException(file))
        val changesetBuilder = new ChangesetBuilder(
          module.getLiteralSource,
          module.getIr
        )
        val changeset = changesetBuilder.build(pendingEdits)
        ctx.executionService.modifyModuleSources(
          module,
          edits,
          changeset.simpleUpdate.orNull,
          logger
        )
        Option.when(shouldExecute)(changeset)
      } finally {
        ctx.locking.releasePendingEditsLock()
        logger.log(
          Level.FINEST,
          "Kept pending edits lock [EnsureCompiledJob] for {} milliseconds",
          System.currentTimeMillis() - pendingEditsLockTimestamp
        )
      }
    } finally {
      ctx.locking.releaseFileLock(file)
      logger.log(
        Level.FINEST,
        "Kept file lock [EnsureCompiledJob] for {} milliseconds",
        System.currentTimeMillis() - fileLockTimestamp
      )
    }
  }

  /** Create cache invalidation commands after applying the edits.
    *
    * @param changeset the [[Changeset]] object capturing the previous
    * version of IR
    * @param ir the IR of compiled module
    * @return the list of cache invalidation commands
    */
  private def buildCacheInvalidationCommands(
    changeset: Changeset[_],
    ir: IR
  ): Seq[CacheInvalidation] = {
    val resolutionErrors = findNodesWithResolutionErrors(ir)
    val invalidateExpressionsCommand =
      CacheInvalidation.Command.InvalidateKeys(
        changeset.invalidated ++ resolutionErrors
      )
    val moduleIds = ir.preorder().flatMap(_.location()).flatMap(_.id()).toSet
    val invalidateStaleCommand =
      CacheInvalidation.Command.InvalidateStale(moduleIds)
    Seq(
      CacheInvalidation(
        CacheInvalidation.StackSelector.All,
        invalidateExpressionsCommand,
        Set(CacheInvalidation.IndexSelector.Weights)
      ),
      CacheInvalidation(
        CacheInvalidation.StackSelector.All,
        invalidateStaleCommand,
        Set(CacheInvalidation.IndexSelector.All)
      )
    )
  }

  /** Looks for the nodes with the resolution error and their dependents.
    *
    * @param ir the module IR
    * @return the set of node ids affected by a resolution error in the module
    */
  private def findNodesWithResolutionErrors(ir: IR): Set[UUID @ExternalID] = {
    val metadata = ir
      .unsafeGetMetadata(
        DataflowAnalysis,
        "Empty dataflow analysis metadata during the interactive compilation."
      )

    val resolutionNotFoundKeys =
      ir.preorder()
        .collect {
          case err @ expression.errors.Resolution(
                _,
                expression.errors.Resolution
                  .ResolverError(BindingsMap.ResolutionNotFound),
                _,
                _
              ) =>
            DataflowAnalysis.DependencyInfo.Type.Static(
              err.getId(),
              err.getExternalId
            )
        }
        .toSet

    resolutionNotFoundKeys.flatMap(
      metadata.dependents.getExternal(_).getOrElse(Set())
    )
  }

  /** Run the invalidation commands.
    *
    * @param module the compiled module
    * @param changeset the changeset containing the list of invalidated expressions
    * @param ctx the runtime context
    */
  private def invalidateCaches(
    module: Module,
    changeset: Changeset[_]
  )(implicit ctx: RuntimeContext): Unit = {
    val invalidationCommands =
      buildCacheInvalidationCommands(changeset, module.getIr)
    ctx.contextManager.getAllContexts.values
      .foreach { stack =>
        if (stack.nonEmpty && isStackInModule(module.getName, stack)) {
          CacheInvalidation.runAll(stack, invalidationCommands)
        }
      }
    CacheInvalidation.runAllVisualizations(
      ctx.contextManager.getVisualizations(module.getName),
      invalidationCommands
    )

    val invalidatedVisualizations =
      ctx.contextManager.getInvalidatedVisualizations(
        module.getName,
        changeset.invalidated
      )
    invalidatedVisualizations.foreach { visualization =>
      UpsertVisualizationJob.upsertVisualization(visualization)
    }
    if (invalidatedVisualizations.nonEmpty) {
      ctx.executionService.getLogger.log(
        Level.FINEST,
        "Invalidated visualizations [{}]",
        invalidatedVisualizations.map(_.id)
      )
    }

    // pending updates
    val updates = changeset.invalidated.map { key =>
      Api.ExpressionUpdate(
        key,
        None,
        None,
        Vector.empty,
        true,
        false,
        Api.ExpressionUpdate.Payload.Pending(None, None)
      )
    }
    if (updates.nonEmpty) {
      ctx.contextManager.getAllContexts.keys.foreach { contextId =>
        val response = Api.Response(Api.ExpressionUpdates(contextId, updates))
        ctx.endpoint.sendToClient(response)
      }
    }
  }

  /** Send notification about the compilation status.
    *
    * @param diagnostics the list of diagnostic messages returned by the
    * compiler
    * @param ctx the runtime context
    */
  private def sendDiagnosticUpdates(
    diagnostics: Seq[Api.ExecutionResult.Diagnostic]
  )(implicit ctx: RuntimeContext): Unit =
    if (diagnostics.nonEmpty) {
      ctx.contextManager.getAllContexts.keys.foreach { contextId =>
        ctx.endpoint.sendToClient(
          Api.Response(Api.ExecutionUpdate(contextId, diagnostics))
        )
      }
    }

  /** Send notification about the compilation status.
    *
    * @param failure the execution failure
    * @param ctx the runtime context
    */
  private def sendFailureUpdate(
    failure: Api.ExecutionResult.Failure
  )(implicit ctx: RuntimeContext): Unit =
    ctx.contextManager.getAllContexts.keys.foreach { contextId =>
      ctx.endpoint.sendToClient(
        Api.Response(Api.ExecutionFailed(contextId, failure))
      )
    }

  private def getCompilationStatus(
    diagnostics: Iterable[Api.ExecutionResult.Diagnostic]
  ): CompilationStatus =
    if (diagnostics.exists(_.isError))
      CompilationStatus.Error
    else
      CompilationStatus.Success

  private def setCacheWeights()(implicit ctx: RuntimeContext): Unit = {
    ctx.contextManager.getAllContexts.values.foreach { stack =>
      getCacheMetadata(stack).foreach { metadata =>
        CacheInvalidation.run(
          stack,
          CacheInvalidation(
            CacheInvalidation.StackSelector.Top,
            CacheInvalidation.Command.SetMetadata(metadata)
          )
        )
      }
    }
    val visualizations = ctx.contextManager.getAllVisualizations
    visualizations.flatMap(getCacheMetadata).foreach { metadata =>
      CacheInvalidation.runVisualizations(
        visualizations,
        CacheInvalidation.Command.SetMetadata(metadata)
      )
    }
  }

  private def getCacheMetadata(
    stack: Iterable[InstrumentFrame]
  )(implicit ctx: RuntimeContext): Option[CachePreferenceAnalysis.Metadata] =
    stack.lastOption.flatMap {
      case InstrumentFrame(Api.StackItem.ExplicitCall(ptr, _, _), _, _) =>
        ctx.executionService.getContext.findModule(ptr.module).toScala.map {
          module =>
            module.getIr
              .unsafeGetMetadata(
                CachePreferenceAnalysis,
                s"Empty cache preference metadata ${module.getName}"
              )
        }
      case _ => None
    }

  private def getCacheMetadata(
    visualization: Visualization
  ): Option[CachePreferenceAnalysis.Metadata] = {
    val module = visualization.module
    module.getIr.getMetadata(CachePreferenceAnalysis)
  }

  /** Get all project modules in the current compiler scope. */
  private def getProjectModulesInScope(implicit
    ctx: RuntimeContext
  ): Iterable[Module] = {
    val packageRepository =
      ctx.executionService.getContext.getCompiler.packageRepository
    packageRepository.getMainProjectPackage
      .map(pkg =>
        packageRepository
          .getModulesForLibrary(pkg.libraryName)
          .map(Module.fromCompilerModule(_))
      )
      .getOrElse(Seq())
  }

  /** Check if stack belongs to the provided module.
    *
    * @param module the qualified module name
    * @param stack the execution stack
    */
  private def isStackInModule(
    module: QualifiedName,
    stack: Iterable[InstrumentFrame]
  ): Boolean =
    stack.lastOption match {
      case Some(
            InstrumentFrame(StackItem.ExplicitCall(methodPointer, _, _), _, _)
          ) =>
        methodPointer.module == module.toString
      case _ =>
        false
    }

}

object EnsureCompiledJob {

  /** The outcome of a compilation. */
  sealed trait CompilationStatus
  private case object CompilationStatus {

    /** Compilation completed. */
    case object Success extends CompilationStatus

    /** Compilation completed with errors. */
    case object Error extends CompilationStatus

    /** Compiler crashed. */
    case object Failure extends CompilationStatus

    implicit val ordering: Ordering[CompilationStatus] =
      Ordering.by {
        case Success => 0
        case Error   => 1
        case Failure => 2
      }
  }

  /** Create [[EnsureCompiledJob]] for a single file.
    *
    * @param file the file to compile
    * @return new instance of [[EnsureCompiledJob]]
    */
  def apply(file: File): EnsureCompiledJob =
    new EnsureCompiledJob(Seq(file))

  /** Create [[EnsureCompiledJob]] for a stack.
    *
    * @param stack the call stack to compile
    * @return new instance of [[EnsureCompiledJob]]
    */
  def apply(stack: Iterable[InstrumentFrame])(implicit
    ctx: RuntimeContext
  ): EnsureCompiledJob =
    new EnsureCompiledJob(extractFiles(stack))

  /** Extract files to compile from a call stack.
    *
    * @param stack a call stack
    * @return a list of files to compile
    */
  private def extractFiles(stack: Iterable[InstrumentFrame])(implicit
    ctx: RuntimeContext
  ): Iterable[File] =
    stack
      .map(_.item)
      .flatMap {
        case Api.StackItem.ExplicitCall(methodPointer, _, _) =>
          ctx.executionService.getContext
            .findModule(methodPointer.module)
            .flatMap { module =>
              val path = java.util.Optional.ofNullable(module.getPath)
              if (path.isEmpty) {
                ctx.executionService.getLogger
                  .severe(s"${module.getName} module path is empty")
              }
              path
            }
            .map(path => new File(path))
            .toScala
        case _ =>
          None
      }

}
