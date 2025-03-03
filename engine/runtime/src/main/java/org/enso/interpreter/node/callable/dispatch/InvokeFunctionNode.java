package org.enso.interpreter.node.callable.dispatch;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.NonIdempotent;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.source.SourceSection;
import java.util.UUID;
import org.enso.interpreter.Constants;
import org.enso.interpreter.node.BaseNode;
import org.enso.interpreter.node.EnsoRootNode;
import org.enso.interpreter.node.MethodRootNode;
import org.enso.interpreter.node.callable.CaptureCallerInfoNode;
import org.enso.interpreter.node.callable.FunctionCallInstrumentationNode;
import org.enso.interpreter.node.callable.InvokeCallableNode;
import org.enso.interpreter.node.callable.argument.ArgumentSorterNode;
import org.enso.interpreter.node.callable.argument.IndirectArgumentSorterNode;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.callable.CallerInfo;
import org.enso.interpreter.runtime.callable.argument.CallArgumentInfo;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.callable.function.FunctionSchema;
import org.enso.interpreter.runtime.data.atom.AtomConstructor;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.state.State;
import org.enso.pkg.Package;

/**
 * This class represents the protocol for remapping the arguments provided at a call site into the
 * positional order expected by the definition of the {@link Function}.
 */
@NodeInfo(shortName = "ArgumentSorter")
@ImportStatic({CallArgumentInfo.ArgumentMappingBuilder.class, Constants.CacheSizes.class})
public abstract class InvokeFunctionNode extends BaseNode {

  private final CallArgumentInfo[] schema;
  private final InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode;
  private final InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode;
  private @Child CaptureCallerInfoNode captureCallerInfoNode = CaptureCallerInfoNode.build();
  private @Child FunctionCallInstrumentationNode functionCallInstrumentationNode =
      FunctionCallInstrumentationNode.build();

  InvokeFunctionNode(
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode) {
    this.schema = schema;
    this.defaultsExecutionMode = defaultsExecutionMode;
    this.argumentsExecutionMode = argumentsExecutionMode;
  }

  /**
   * Creates an instance of this node.
   *
   * @param schema information about the call arguments in positional order
   * @param defaultsExecutionMode the defaults execution mode for this function invocation
   * @param argumentsExecutionMode the arguments execution mode for this function invocation
   * @return an instance of this node.
   */
  public static InvokeFunctionNode build(
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode) {
    return InvokeFunctionNodeGen.create(schema, defaultsExecutionMode, argumentsExecutionMode);
  }

  /**
   * Creates a simple node to invoke a function with provided arity.
   *
   * @param arity number of arguments to pass to the function
   * @return instance of this node to handle a {@code arity}-arity function invocation
   */
  @NeverDefault
  public static InvokeFunctionNode buildWithArity(int arity) {
    var schema = new CallArgumentInfo[arity];
    for (int idx = 0; idx < schema.length; idx++) {
      schema[idx] = new CallArgumentInfo();
    }
    return build(
        schema,
        InvokeCallableNode.DefaultsExecutionMode.EXECUTE,
        InvokeCallableNode.ArgumentsExecutionMode.EXECUTE);
  }

  @NonIdempotent
  EnsoContext getContext() {
    return EnsoContext.get(this);
  }

  @TruffleBoundary
  private PanicException makePrivateAccessPanic(Function targetFunction) {
    String thisProjName = null;
    if (getThisProject() != null) {
      thisProjName = getThisProject().libraryName().qualifiedName();
    }
    String targetProjName = null;
    if (getFunctionProject(targetFunction) != null) {
      targetProjName = getFunctionProject(targetFunction).libraryName().qualifiedName();
    }
    var funcName = targetFunction.getName();
    var err =
        EnsoContext.get(this)
            .getBuiltins()
            .error()
            .makePrivateAccessError(thisProjName, targetProjName, funcName);
    return new PanicException(err, this);
  }

  private void ensureFunctionIsAccessible(Function function, FunctionSchema functionSchema) {
    var isPrivateCheckDisabled = getContext().isPrivateCheckDisabled();
    CompilerAsserts.compilationConstant(isPrivateCheckDisabled);
    if (!isPrivateCheckDisabled
        && functionSchema.isProjectPrivate()
        && !isInSameProject(function)) {
      throw makePrivateAccessPanic(function);
    }
  }

  @Specialization(
      guards = {"!getContext().isInlineCachingDisabled()", "function.getSchema() == cachedSchema"},
      limit = Constants.CacheSizes.ARGUMENT_SORTER_NODE)
  Object invokeCached(
      Function function,
      VirtualFrame callerFrame,
      State state,
      Object[] arguments,
      @Cached("function.getSchema()") FunctionSchema cachedSchema,
      @Cached("generate(cachedSchema, getSchema())")
          CallArgumentInfo.ArgumentMapping argumentMapping,
      @Cached("build(cachedSchema, argumentMapping, getArgumentsExecutionMode())")
          ArgumentSorterNode mappingNode,
      @Cached(
              "build(argumentMapping, getDefaultsExecutionMode(), getArgumentsExecutionMode(),"
                  + " getTailStatus())")
          CurryNode curryNode) {
    ensureFunctionIsAccessible(function, cachedSchema);

    ArgumentSorterNode.MappedArguments mappedArguments =
        mappingNode.execute(callerFrame, function, state, arguments);
    CallerInfo callerInfo = null;
    if (cachedSchema.getCallerFrameAccess().shouldFrameBePassed()) {
      callerInfo = captureCallerInfoNode.execute(callerFrame.materialize());
    }
    var result =
        functionCallInstrumentationNode.execute(
            callerFrame, function, state, mappedArguments.getSortedArguments());
    if (result instanceof FunctionCallInstrumentationNode.FunctionCall) {
      return curryNode.execute(
          callerFrame,
          function,
          callerInfo,
          state,
          mappedArguments.getSortedArguments(),
          mappedArguments.getOversaturatedArguments());
    } else {
      return result;
    }
  }

  /**
   * Generates an argument mapping and executes a function with properly ordered arguments. Does not
   * perform any caching and is thus a slow-path operation.
   *
   * @param function the function to execute.
   * @param callerFrame the caller frame to pass to the function
   * @param state the state to pass to the function
   * @param arguments the arguments to reorder and supply to the {@code function}
   * @return the result of calling {@code function} with the supplied {@code arguments}.
   */
  @Specialization(replaces = "invokeCached")
  Object invokeUncached(
      Function function,
      VirtualFrame callerFrame,
      State state,
      Object[] arguments,
      @Cached IndirectArgumentSorterNode mappingNode,
      @Cached IndirectCurryNode curryNode) {
    ensureFunctionIsAccessible(function, function.getSchema());

    CallArgumentInfo.ArgumentMapping argumentMapping =
        CallArgumentInfo.ArgumentMappingBuilder.generate(function.getSchema(), getSchema());

    ArgumentSorterNode.MappedArguments mappedArguments =
        mappingNode.execute(
            callerFrame,
            function.getSchema(),
            argumentMapping,
            getArgumentsExecutionMode(),
            function,
            state,
            arguments);

    CallerInfo callerInfo = null;

    if (function.getSchema().getCallerFrameAccess().shouldFrameBePassed()) {
      callerInfo = captureCallerInfoNode.execute(callerFrame.materialize());
    }

    functionCallInstrumentationNode.execute(
        callerFrame, function, state, mappedArguments.getSortedArguments());

    return curryNode.execute(
        callerFrame == null ? null : callerFrame.materialize(),
        function,
        callerInfo,
        state,
        mappedArguments.getSortedArguments(),
        mappedArguments.getOversaturatedArguments(),
        argumentMapping.getPostApplicationSchema(),
        defaultsExecutionMode,
        argumentsExecutionMode,
        getTailStatus());
  }

  /**
   * Executes the {@link InvokeFunctionNode} to apply the function to given arguments.
   *
   * @param callable the function to call
   * @param callerFrame the caller frame to pass to the function
   * @param state the state to pass to the function
   * @param arguments the arguments being passed to {@code function}
   * @return the result of executing the {@code function} with reordered {@code arguments}
   */
  public abstract Object execute(
      Function callable, VirtualFrame callerFrame, State state, Object[] arguments);

  public CallArgumentInfo[] getSchema() {
    return schema;
  }

  public InvokeCallableNode.DefaultsExecutionMode getDefaultsExecutionMode() {
    return this.defaultsExecutionMode;
  }

  public InvokeCallableNode.ArgumentsExecutionMode getArgumentsExecutionMode() {
    return argumentsExecutionMode;
  }

  /**
   * @return the source section for this node.
   */
  @Override
  public SourceSection getSourceSection() {
    Node parent = getParent();
    return parent == null ? null : parent.getSourceSection();
  }

  /**
   * Sets the expression ID of this node.
   *
   * @param id the expression ID to assign this node.
   */
  public void setId(UUID id) {
    functionCallInstrumentationNode.setId(id);
  }

  /** Returns expression ID of this node. */
  public UUID getId() {
    return functionCallInstrumentationNode.getId();
  }

  /** Returns true if the given function is in the same project as this node. */
  private boolean isInSameProject(Function function) {
    var thisProj = getThisProject();
    var funcProj = getFunctionProject(function);
    return thisProj == funcProj;
  }

  private Package<TruffleFile> getThisProject() {
    if (getRootNode() instanceof EnsoRootNode thisRootNode) {
      return thisRootNode.getModuleScope().getModule().getPackage();
    }
    return null;
  }

  private Package<TruffleFile> getFunctionProject(Function function) {
    var cons = AtomConstructor.accessorFor(function);
    if (cons != null) {
      return cons.getDefinitionScope().getModule().getPackage();
    }
    cons = MethodRootNode.constructorFor(function);
    if (cons != null) {
      return cons.getDefinitionScope().getModule().getPackage();
    }
    if (function.getCallTarget().getRootNode() instanceof EnsoRootNode ensoRootNode) {
      return ensoRootNode.getModuleScope().getModule().getPackage();
    }
    return null;
  }
}
