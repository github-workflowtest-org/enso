package org.enso.interpreter.runtime.builtin;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleOptions;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.enso.compiler.Passes;
import org.enso.compiler.context.CompilerContext;
import org.enso.compiler.context.FreshNameSupply;
import org.enso.compiler.core.CompilerError;
import org.enso.compiler.phase.BuiltinsIrBuilder;
import org.enso.interpreter.EnsoLanguage;
import org.enso.interpreter.node.expression.builtin.Any;
import org.enso.interpreter.node.expression.builtin.Boolean;
import org.enso.interpreter.node.expression.builtin.Builtin;
import org.enso.interpreter.node.expression.builtin.BuiltinRootNode;
import org.enso.interpreter.node.expression.builtin.Nothing;
import org.enso.interpreter.node.expression.builtin.Polyglot;
import org.enso.interpreter.node.expression.builtin.debug.Debug;
import org.enso.interpreter.node.expression.builtin.error.CaughtPanic;
import org.enso.interpreter.node.expression.builtin.error.Warning;
import org.enso.interpreter.node.expression.builtin.immutable.Vector;
import org.enso.interpreter.node.expression.builtin.io.File;
import org.enso.interpreter.node.expression.builtin.meta.ProjectDescription;
import org.enso.interpreter.node.expression.builtin.mutable.Array;
import org.enso.interpreter.node.expression.builtin.mutable.Ref;
import org.enso.interpreter.node.expression.builtin.ordering.Comparable;
import org.enso.interpreter.node.expression.builtin.ordering.DefaultComparator;
import org.enso.interpreter.node.expression.builtin.ordering.Ordering;
import org.enso.interpreter.node.expression.builtin.resource.ManagedResource;
import org.enso.interpreter.node.expression.builtin.runtime.Context;
import org.enso.interpreter.node.expression.builtin.text.Text;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.Module;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.data.Type;
import org.enso.interpreter.runtime.scope.ModuleScope;
import org.enso.interpreter.runtime.util.CachingSupplier;
import org.enso.pkg.QualifiedName;

/** Container class for static predefined atoms, methods, and their containing scope. */
public final class Builtins {

  private static List<Constructor<? extends Builtin>> loadedBuiltinConstructors;
  private static Map<String, LoadedBuiltinMetaMethod> loadedBuiltinMethodsMeta;
  private static Map<String, LoadedBuiltinMethod> loadedBuiltinMethods;

  static {
    loadedBuiltinConstructors = readBuiltinTypes();
    loadedBuiltinMethodsMeta = readBuiltinMethodsMeta();
    if (TruffleOptions.AOT) {
      loadedBuiltinMethods = loadBuiltinMethodClassesEarly(loadedBuiltinMethodsMeta);
    }
  }

  public static final String PACKAGE_NAME = "Builtins";
  public static final String NAMESPACE = "Standard";
  public static final String MODULE_NAME = NAMESPACE + "." + PACKAGE_NAME + ".Main";

  /** Container for method names needed outside this class. */
  public static class MethodNames {
    public static class Debug {
      public static final String EVAL = "eval";
    }
  }

  private final Map<Class<? extends Builtin>, Builtin> builtins;
  private final Map<String, Map<String, Supplier<LoadedBuiltinMethod>>> builtinMethodNodes;
  private final Map<String, Builtin> builtinsByName;

  private final Error error;
  private final Module module;
  private final ModuleScope scope;
  private final Number number;
  private final Boolean bool;

  private final Context contexts;
  private final Ordering ordering;
  private final Comparable comparable;
  private final DefaultComparator defaultComparator;
  private final System system;
  private final Special special;

  // Builtin types
  private final Builtin any;
  private final Builtin nothing;
  private final Builtin function;
  private final Builtin polyglot;
  private final Builtin text;
  private final Builtin array;
  private final Builtin vector;
  private final Builtin dictionary;
  private final Builtin dataflowError;
  private final Builtin ref;
  private final Builtin managedResource;
  private final Builtin debug;
  private final ProjectDescription projectDescription;
  private final Builtin file;
  private final Builtin date;
  private final Builtin dateTime;
  private final Builtin duration;
  private final Builtin timeOfDay;
  private final Builtin timeZone;
  private final Builtin warning;

  /**
   * Creates an instance with builtin methods installed.
   *
   * @param context the current {@link EnsoContext} instance
   */
  public Builtins(EnsoContext context) {
    EnsoLanguage language = context.getLanguage();
    module = Module.empty(QualifiedName.fromString(MODULE_NAME), null);
    module.compileScope(context); // Dummy compilation for an empty module
    ModuleScope.Builder scopeBuilder = module.newScopeBuilder(false);

    builtins = initializeBuiltinTypes(loadedBuiltinConstructors, language, scopeBuilder);
    builtinsByName =
        builtins.values().stream()
            .collect(
                Collectors.toMap(
                    v -> v.getType().getName(), java.util.function.Function.identity()));
    if (TruffleOptions.AOT) {
      builtinMethodNodes = readBuiltinMethodsMetadata(loadedBuiltinMethods, scopeBuilder);
      registerBuiltinMethods(scopeBuilder, language);
    } else {
      builtinMethodNodes = registerBuiltinMethodsLazily(scopeBuilder, language);
    }

    ordering = getBuiltinType(Ordering.class);
    comparable = getBuiltinType(Comparable.class);
    defaultComparator = getBuiltinType(DefaultComparator.class);
    bool = this.getBuiltinType(Boolean.class);
    contexts = this.getBuiltinType(Context.class);

    any = builtins.get(Any.class);
    nothing = builtins.get(Nothing.class);
    function = builtins.get(org.enso.interpreter.node.expression.builtin.function.Function.class);
    polyglot = builtins.get(Polyglot.class);
    text = builtins.get(Text.class);
    array = builtins.get(Array.class);
    vector = builtins.get(Vector.class);
    dictionary = builtins.get(org.enso.interpreter.node.expression.builtin.Dictionary.class);
    dataflowError = builtins.get(org.enso.interpreter.node.expression.builtin.Error.class);
    ref = builtins.get(Ref.class);
    managedResource = builtins.get(ManagedResource.class);
    debug = builtins.get(Debug.class);
    projectDescription = getBuiltinType(ProjectDescription.class);
    file = builtins.get(File.class);
    date = builtins.get(org.enso.interpreter.node.expression.builtin.date.Date.class);
    dateTime = builtins.get(org.enso.interpreter.node.expression.builtin.date.DateTime.class);
    duration = builtins.get(org.enso.interpreter.node.expression.builtin.date.Duration.class);
    timeOfDay = builtins.get(org.enso.interpreter.node.expression.builtin.date.TimeOfDay.class);
    timeZone = builtins.get(org.enso.interpreter.node.expression.builtin.date.TimeZone.class);
    warning = builtins.get(Warning.class);

    error = new Error(this, context);
    system = new System(this);
    number = new Number(this);
    special = new Special(language);
    scope = scopeBuilder.build();
  }

  private static Map<String, LoadedBuiltinMethod> loadBuiltinMethodClassesEarly(
      Map<String, LoadedBuiltinMetaMethod> map) {
    Map<String, LoadedBuiltinMethod> methods = new HashMap<>();
    map.forEach(
        (key, value) -> {
          methods.put(key, value.toMethod());
        });
    return methods;
  }

  /**
   * Registers builtin methods with their corresponding Atom Constructor's owners. That way
   * "special" builtin types have builtin methods in the scope without requiring everyone to always
   * import full stdlib.
   *
   * @param scope Builtins scope
   * @param language The language the resulting function nodes should be associated with
   */
  private void registerBuiltinMethods(ModuleScope.Builder scope, EnsoLanguage language) {
    for (Builtin builtin : builtins.values()) {
      var type = builtin.getType();
      Map<String, Supplier<LoadedBuiltinMethod>> methods = builtinMethodNodes.get(type.getName());
      if (methods != null) {
        // Register a builtin method iff it is marked as auto-register.
        // Methods can only register under a type or, if we deal with a static method, it's
        // eigen-type.
        // Such builtins are available on certain types without importing the whole stdlib, e.g. Any
        // or Number.
        methods.forEach(
            (key, value) -> {
              LoadedBuiltinMethod meth = value.get();
              Type tpe =
                  meth.isAutoRegister ? (!meth.isStatic() ? type : type.getEigentype()) : null;
              if (tpe != null) {
                Optional<BuiltinFunction> fun = meth.toFunction(language, false);
                fun.ifPresent(f -> scope.registerMethod(tpe, key, f.getFunction()));
              }
            });
      }
    }
  }

  /**
   * Register builtin methods and initialize them lazily in the provided scope. This method differs
   * from `registerBuiltinMethods` where all methods are initialized by the time they are
   * registered..
   *
   * @param scope Builtins scope
   * @param language The language the resulting function nodes should be associated with
   * @return map from types to builtin methods
   */
  private Map<String, Map<String, Supplier<LoadedBuiltinMethod>>> registerBuiltinMethodsLazily(
      ModuleScope.Builder scope, EnsoLanguage language) {
    Map<String, Map<String, Supplier<LoadedBuiltinMethod>>> builtinMethodNodes = new HashMap<>();
    Map<String, Map<String, LoadedBuiltinMetaMethod>> builtinMetaMethods = new HashMap<>();
    loadedBuiltinMethodsMeta.forEach(
        (fullName, meta) -> {
          String[] builtinName = fullName.split("\\.");
          if (builtinName.length != 2) {
            throw new CompilerError("Invalid builtin metadata for " + fullName);
          }
          String builtinMethodOwner = builtinName[0];
          String builtinMethodName = builtinName[1];
          Optional.ofNullable(scope.asModuleScope().getType(builtinMethodOwner, true))
              .ifPresentOrElse(
                  constr -> {
                    Map<String, Supplier<LoadedBuiltinMethod>> atomNodes =
                        getOrUpdate(builtinMethodNodes, constr.getName());
                    atomNodes.put(builtinMethodName, new CachingSupplier<>(() -> meta.toMethod()));

                    Map<String, LoadedBuiltinMetaMethod> atomNodesMeta =
                        getOrUpdate(builtinMetaMethods, constr.getName());
                    atomNodesMeta.put(builtinMethodName, meta);
                  },
                  () -> {
                    Map<String, Supplier<LoadedBuiltinMethod>> atomNodes =
                        getOrUpdate(builtinMethodNodes, builtinMethodOwner);
                    atomNodes.put(builtinMethodName, new CachingSupplier<>(() -> meta.toMethod()));

                    Map<String, LoadedBuiltinMetaMethod> atomNodesMeta =
                        getOrUpdate(builtinMetaMethods, builtinMethodOwner);
                    atomNodesMeta.put(builtinMethodName, meta);
                  });
        });

    for (Builtin builtin : builtins.values()) {
      var type = builtin.getType();
      Map<String, LoadedBuiltinMetaMethod> methods = builtinMetaMethods.get(type.getName());
      if (methods != null) {
        // Register a builtin method iff it is marked as auto-register.
        // Methods can only register under a type or, if we deal with a static method, it's
        // eigen-type.
        // Such builtins are available on certain types without importing the whole stdlib, e.g. Any
        // or Number.
        methods.forEach(
            (key, value) -> {
              Type tpe =
                  value.isAutoRegister() ? (!value.isStatic() ? type : type.getEigentype()) : null;
              if (tpe != null) {
                Supplier<Function> supplier =
                    () -> value.toMethod().toFunction(language, false).get().getFunction();
                scope.registerMethod(tpe, key, supplier);
              }
            });
      }
    }
    return builtinMethodNodes;
  }

  private <T> Map<String, T> getOrUpdate(Map<String, Map<String, T>> map, String key) {
    Map<String, T> entry = map.get(key);
    if (entry == null) {
      entry = new HashMap<>();
      map.put(key, entry);
    }
    return entry;
  }

  /**
   * @return {@code true} if the IR has been initialized, otherwise {@code false}
   */
  public boolean isIrInitialized() {
    return this.module.getIr() != null;
  }

  /** Initialize the source file for the builtins module. */
  @CompilerDirectives.TruffleBoundary
  public void initializeBuiltinsSource() {
    module.setLiteralSource("");
  }

  /**
   * Initialize the IR for the builtins module from the builtins source file.
   *
   * @param freshNameSupply the compiler's fresh name supply
   * @param passes the passes manager for the compiler
   */
  @CompilerDirectives.TruffleBoundary
  public void initializeBuiltinsIr(
      CompilerContext context, FreshNameSupply freshNameSupply, Passes passes) {
    try {
      if (module.getSource() == null) {
        initializeBuiltinsSource();
      }
      BuiltinsIrBuilder.build(context, module.asCompilerModule(), freshNameSupply, passes);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Returns a list of supported builtins.
   *
   * <p>Builtin types are marked via @BuiltinType annotation. The metadata file represents a single
   * builtin type per row. The format of the row is as follows: <Enso name of the builtin
   * type>:<Name of the class representing it>:[<field1>,<field2>,...] where the last column gives a
   * list of optional type's fields.
   */
  private static List<Constructor<? extends Builtin>> readBuiltinTypes() {
    ClassLoader classLoader = Builtins.class.getClassLoader();
    List<String> lines;
    final String NODE_PKG = "org.enso.interpreter.node.expression.builtin";
    final String META_PATH =
        "META-INF" + "/" + NODE_PKG.replace('.', '/') + "/BuiltinTypes.metadata";
    try (InputStream resource = classLoader.getResourceAsStream(META_PATH)) {
      lines =
          new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))
              .lines()
              .collect(Collectors.toList());
    } catch (Exception ioe) {
      lines = new ArrayList<>();
      ioe.printStackTrace();
    }

    return lines.stream()
        .map(
            line -> {
              String[] builtinMeta = line.split(":");
              if (builtinMeta.length < 2 || builtinMeta.length > 4) {
                java.lang.System.out.println(Arrays.toString(builtinMeta));
                throw new CompilerError("Invalid builtin metadata in: " + line);
              }
              try {
                @SuppressWarnings("unchecked")
                Class<? extends Builtin> clazz =
                    (Class<? extends Builtin>) Class.forName(builtinMeta[1]);

                // Note: Don't create a new instance of the builtin at this point
                // because that will be too much for the inliner and won't get
                // constant folded.
                return clazz.getConstructor();
              } catch (ClassNotFoundException | NoSuchMethodException e) {
                e.printStackTrace();
                throw new CompilerError("Invalid builtin type entry: " + builtinMeta[1]);
              }
            })
        .collect(Collectors.toList());
  }

  /** Initialize builting types in the context of the given language and module scope */
  private Map<Class<? extends Builtin>, Builtin> initializeBuiltinTypes(
      List<Constructor<? extends Builtin>> constrs,
      EnsoLanguage language,
      ModuleScope.Builder scope) {
    Map<Class<? extends Builtin>, Builtin> builtins = new HashMap<>();

    for (var constr : constrs) {
      try {
        Builtin builtin = constr.newInstance();
        builtins.put(builtin.getClass(), builtin);
      } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
        throw new CompilerError("Invalid builtin type entry: " + constr, e);
      }
    }
    for (var b : builtins.values()) {
      b.initialize(language, scope, builtins);
    }
    return builtins;
  }

  /**
   * Returns a map of Builtin methods associated with their owner.
   *
   * @param classes a map of (already loaded) builtin methods
   * @param scope Builtins scope
   * @return A map of builtin method nodes per builtin type name
   */
  private Map<String, Map<String, Supplier<LoadedBuiltinMethod>>> readBuiltinMethodsMetadata(
      Map<String, LoadedBuiltinMethod> classes, ModuleScope.Builder scope) {

    Map<String, Map<String, Supplier<LoadedBuiltinMethod>>> methodNodes = new HashMap<>();
    classes.forEach(
        (fullBuiltinName, builtin) -> {
          String[] builtinName = fullBuiltinName.split("\\.");
          if (builtinName.length != 2) {
            throw new CompilerError("Invalid builtin metadata for " + fullBuiltinName);
          }
          String builtinMethodOwner = builtinName[0];
          String builtinMethodName = builtinName[1];
          Optional.ofNullable(scope.asModuleScope().getType(builtinMethodOwner, true))
              .ifPresentOrElse(
                  constr -> {
                    Map<String, Supplier<LoadedBuiltinMethod>> atomNodes =
                        getOrUpdate(methodNodes, constr.getName());
                    atomNodes.put(builtinMethodName, new CachingSupplier<>(builtin));
                  },
                  () -> {
                    Map<String, Supplier<LoadedBuiltinMethod>> atomNodes =
                        getOrUpdate(methodNodes, builtinMethodOwner);
                    atomNodes.put(builtinMethodName, new CachingSupplier<>(builtin));
                  });
        });
    return methodNodes;
  }

  /**
   * Loads a Map of builtin methods.
   *
   * <p>Builtin methods are marked via @BuiltinMethod annotation. THe metadata file represents a
   * single builtin method per row. The format of the row is as follows: <Fully qualified name of
   * the builtin method>:<Class name of the builtin method representing it>
   *
   * @return A map of builtin method nodes per builtin type name
   */
  private static Map<String, LoadedBuiltinMetaMethod> readBuiltinMethodsMeta() {
    ClassLoader classLoader = Builtins.class.getClassLoader();
    List<String> lines;

    final String NODE_PKG = "org.enso.interpreter.node.expression.builtin";
    final String META_PATH =
        "META-INF" + "/" + NODE_PKG.replace('.', '/') + "/BuiltinMethods.metadata";
    try (InputStream resource = classLoader.getResourceAsStream(META_PATH)) {
      lines =
          new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))
              .lines()
              .collect(Collectors.toList());
    } catch (Exception ioe) {
      lines = new ArrayList<>();
      ioe.printStackTrace();
    }

    return lines.stream()
        .map(
            line -> {
              String[] builtinMeta = line.split(":");
              if (builtinMeta.length != 4) {
                throw new CompilerError("Invalid builtin metadata in: " + line);
              }
              String[] builtinName = builtinMeta[0].split("\\.");
              if (builtinName.length != 2) {
                throw new CompilerError("Invalid builtin metadata in : " + line);
              }
              boolean isStatic = java.lang.Boolean.valueOf(builtinMeta[2]);
              boolean isAutoRegister = java.lang.Boolean.valueOf(builtinMeta[3]);

              return new AbstractMap.SimpleEntry<>(
                  builtinMeta[0],
                  new LoadedBuiltinMetaMethod(builtinMeta[1], isStatic, isAutoRegister));
            })
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /**
   * Returns a builtin method for the provided Atom Constructor and the name, if it exists.
   *
   * @param type Atom Constructor owner of the function
   * @param methodName Name of the method
   * @param language The language the resulting function nodes should be associated with
   * @return A non-empty function under the given name, if it exists. An empty value if no such
   *     builtin method was ever registerd
   */
  public Optional<BuiltinFunction> getBuiltinFunction(
      String type, String methodName, EnsoLanguage language, boolean isStaticInstance) {
    // TODO: move away from String mapping once Builtins is gone
    Map<String, Supplier<LoadedBuiltinMethod>> atomNodes = builtinMethodNodes.get(type);
    if (atomNodes == null) {
      return Optional.empty();
    }
    var supply = atomNodes.get(methodName);
    if (supply == null) {
      return Optional.empty();
    }
    LoadedBuiltinMethod builtin = supply.get();
    if (builtin == null) {
      return Optional.empty();
    }
    return builtin.toFunction(language, isStaticInstance);
  }

  public Optional<BuiltinFunction> getBuiltinFunction(
      Type type, String methodName, EnsoLanguage language) {
    return getBuiltinFunction(type.getName(), methodName, language, false);
  }

  public <T extends Builtin> T getBuiltinType(Class<T> clazz) {
    @SuppressWarnings("unchecked")
    T t = (T) builtins.get(clazz);
    return t;
  }

  public Builtin getBuiltinType(String name) {
    return builtinsByName.get(name);
  }

  /**
   * Returns the {@code Nothing} atom constructor.
   *
   * @return the {@code Nothing} atom constructor
   */
  public Type nothing() {
    return nothing.getType();
  }

  /**
   * Returns the {@code Text} part of builtins.
   *
   * @return the {@code Text} part of builtins.
   */
  public Type text() {
    return text.getType();
  }

  /**
   * Returns the {@code Function} atom constructor.
   *
   * @return the {@code Function} atom constructor
   */
  public Type function() {
    return function.getType();
  }

  /**
   * Returns the number-related entities.
   *
   * @return the number-related part of builtins.
   */
  public Number number() {
    return number;
  }

  /**
   * @return the builtin Context type
   */
  public Context context() {
    return contexts;
  }

  /**
   * @return the container for boolean constructors.
   */
  public Boolean bool() {
    return bool;
  }

  /**
   * @return the ManagedResource constructor.
   */
  public Type managedResource() {
    return managedResource.getType();
  }

  /**
   * @return the builtin Error types container.
   */
  public Error error() {
    return error;
  }

  /**
   * Returns the {@code Any} atom constructor.
   *
   * @return the {@code Any} atom constructor
   */
  public Type any() {
    return any.getType();
  }

  /**
   * Returns the {@code Warning} atom constructor.
   *
   * @return the {@code Warning} atom constructor
   */
  public Type warning() {
    return warning.getType();
  }

  /**
   * Returns the {@code File} atom constructor.
   *
   * @return the {@code File} atom constructor
   */
  public Type file() {
    return file.getType();
  }

  /**
   * Returns the {@code Date} atom constructor.
   *
   * @return the {@code Date} atom constructor
   */
  public Type date() {
    return date.getType();
  }

  /**
   * Returns the {@code DateTime} atom constructor.
   *
   * @return the {@code DateTime} atom constructor
   */
  public Type dateTime() {
    return dateTime.getType();
  }

  /**
   * Returns the {@code TimeOfDay} atom constructor.
   *
   * @return the {@code TimeOfDay} atom constructor
   */
  public Type timeOfDay() {
    return timeOfDay.getType();
  }

  /**
   * Returns the {@code Duration} atom constructor.
   *
   * @return the {@code Duration} atom constructor.
   */
  public Type duration() {
    return duration.getType();
  }

  /**
   * Returns the {@code TimeZone} atom constructor.
   *
   * @return the {@code TimeZone} atom constructor
   */
  public Type timeZone() {
    return timeZone.getType();
  }

  /**
   * Returns the {@code Debug} atom constructor. TODO: this is redundant, figure out a way to avoid
   * createing spurious Debug builtin type
   *
   * @return the {@code Debug} atom constructor
   */
  public Type debug() {
    return debug.getType();
  }

  /**
   * @return the {@code Project_Description} atom constructor
   */
  public ProjectDescription getProjectDescription() {
    return projectDescription;
  }

  /**
   * @return the {@code System} atom constructor.
   */
  public System system() {
    return system;
  }

  /**
   * @return the Array constructor.
   */
  public Type array() {
    return array.getType();
  }

  public Type vector() {
    return vector.getType();
  }

  public Type dictionary() {
    return dictionary.getType();
  }

  /**
   * @return the Ref constructor.
   */
  public Type ref() {
    return ref.getType();
  }

  /**
   * @return the container for polyglot-related builtins.
   */
  public Type polyglot() {
    return polyglot.getType();
  }

  /**
   * @return the {@code Caught_Panic} atom constructor
   */
  public CaughtPanic caughtPanic() {
    return this.error.caughtPanic();
  }

  /**
   * @return the {@code Panic} atom constructor
   */
  public Type panic() {
    return this.error.panic();
  }

  /**
   * @return the container for ordering-related builtins
   */
  public Ordering ordering() {
    return ordering;
  }

  public Comparable comparable() {
    return comparable;
  }

  public DefaultComparator defaultComparator() {
    return defaultComparator;
  }

  /**
   * @return the container for the dataflow error-related builtins
   */
  public Type dataflowError() {
    return dataflowError.getType();
  }

  public Special special() {
    return special;
  }

  /**
   * Returns the builtin module scope.
   *
   * @return the builtin module scope
   */
  public ModuleScope getScope() {
    return scope;
  }

  public Module getModule() {
    return module;
  }

  private static class LoadedBuiltinMetaMethod {

    private LoadedBuiltinMethod method;
    private final String className;
    private final boolean staticMethod;
    private final boolean autoRegister;

    private LoadedBuiltinMetaMethod(String className, boolean staticMethod, boolean autoRegister) {
      this.className = className;
      this.staticMethod = staticMethod;
      this.autoRegister = autoRegister;
      this.method = null;
    }

    boolean isStatic() {
      return staticMethod;
    }

    boolean isAutoRegister() {
      return autoRegister;
    }

    LoadedBuiltinMethod toMethod() {
      if (method == null) {
        try {
          @SuppressWarnings("unchecked")
          Class<BuiltinRootNode> clazz = (Class<BuiltinRootNode>) Class.forName(className);
          Method meth = clazz.getMethod("makeFunction", EnsoLanguage.class, boolean.class);
          method = new LoadedBuiltinMethod(meth, staticMethod, autoRegister);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
          throw new CompilerError("Invalid builtin method " + className, e);
        }
      }
      return method;
    }
  }

  private record LoadedBuiltinMethod(Method meth, boolean isStatic, boolean isAutoRegister) {
    Optional<BuiltinFunction> toFunction(EnsoLanguage language, boolean isStaticInstance) {
      try {
        return Optional.ofNullable((Function) meth.invoke(null, language, isStaticInstance))
            .map(f -> new BuiltinFunction(f, isAutoRegister));
      } catch (Exception e) {
        e.printStackTrace();
        return Optional.empty();
      }
    }
  }
}
