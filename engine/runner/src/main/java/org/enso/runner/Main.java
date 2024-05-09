package org.enso.runner;

import buildinfo.Info;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.enso.common.HostEnsoUtils;
import org.enso.common.LanguageInfo;
import org.enso.distribution.DistributionManager;
import org.enso.distribution.Environment;
import org.enso.editions.DefaultEdition;
import org.enso.languageserver.boot.LanguageServerConfig;
import org.enso.languageserver.boot.ProfilingConfig;
import org.enso.languageserver.boot.StartupConfig;
import org.enso.libraryupload.LibraryUploader.UploadFailedError;
import org.enso.pkg.ComponentGroups;
import org.enso.pkg.Contact;
import org.enso.pkg.PackageManager;
import org.enso.pkg.PackageManager$;
import org.enso.pkg.Template;
import org.enso.polyglot.Module;
import org.enso.polyglot.PolyglotContext;
import org.enso.profiling.sampler.NoopSampler;
import org.enso.profiling.sampler.OutputStreamSampler;
import org.enso.version.VersionDescription;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.PolyglotException.StackFrame;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import scala.concurrent.ExecutionContext;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

/** The main CLI entry point class. */
public final class Main {
  private static final String RUN_OPTION = "run";
  private static final String INSPECT_OPTION = "inspect";
  private static final String DUMP_GRAPHS_OPTION = "dump-graphs";
  private static final String HELP_OPTION = "help";
  private static final String NEW_OPTION = "new";
  private static final String PROJECT_NAME_OPTION = "new-project-name";
  private static final String PROJECT_NORMALIZED_NAME_OPTION = "new-project-normalized-name";
  private static final String PROJECT_TEMPLATE_OPTION = "new-project-template";
  private static final String PROJECT_AUTHOR_NAME_OPTION = "new-project-author-name";
  private static final String PROJECT_AUTHOR_EMAIL_OPTION = "new-project-author-email";
  private static final String REPL_OPTION = "repl";
  private static final String DOCS_OPTION = "docs";
  private static final String PREINSTALL_OPTION = "preinstall-dependencies";
  private static final String PROFILING_PATH = "profiling-path";
  private static final String PROFILING_TIME = "profiling-time";
  private static final String LANGUAGE_SERVER_OPTION = "server";
  private static final String DAEMONIZE_OPTION = "daemon";
  private static final String INTERFACE_OPTION = "interface";
  private static final String RPC_PORT_OPTION = "rpc-port";
  private static final String DATA_PORT_OPTION = "data-port";
  private static final String SECURE_RPC_PORT_OPTION = "secure-rpc-port";
  private static final String SECURE_DATA_PORT_OPTION = "secure-data-port";
  private static final String ROOT_ID_OPTION = "root-id";
  private static final String ROOT_PATH_OPTION = "path";
  private static final String IN_PROJECT_OPTION = "in-project";
  private static final String VERSION_OPTION = "version";
  private static final String JSON_OPTION = "json";
  private static final String IR_CACHES_OPTION = "ir-caches";
  private static final String NO_IR_CACHES_OPTION = "no-ir-caches";
  private static final String NO_READ_IR_CACHES_OPTION = "no-read-ir-caches";
  private static final String DISABLE_PRIVATE_CHECK_OPTION = "disable-private-check";
  private static final String COMPILE_OPTION = "compile";
  private static final String NO_COMPILE_DEPENDENCIES_OPTION = "no-compile-dependencies";
  private static final String NO_GLOBAL_CACHE_OPTION = "no-global-cache";
  private static final String LOG_LEVEL = "log-level";
  private static final String LOGGER_CONNECT = "logger-connect";
  private static final String NO_LOG_MASKING = "no-log-masking";
  private static final String UPLOAD_OPTION = "upload";
  private static final String UPDATE_MANIFEST_OPTION = "update-manifest";
  private static final String HIDE_PROGRESS = "hide-progress";
  private static final String AUTH_TOKEN = "auth-token";
  private static final String AUTO_PARALLELISM_OPTION = "with-auto-parallelism";
  private static final String SKIP_GRAALVM_UPDATER = "skip-graalvm-updater";
  private static final String EXECUTION_ENVIRONMENT_OPTION = "execution-environment";
  private static final String WARNINGS_LIMIT = "warnings-limit";

  private static final org.slf4j.Logger logger = LoggerFactory.getLogger(Main.class);

  private static boolean isDevBuild() {
    return Info.ensoVersion().matches(".+-SNAPSHOT$");
  }

  private static Option.Builder cliOptionBuilder() {
    return Option.builder();
  }

  /**
   * Builds the [[Options]] object representing the CLI syntax.
   *
   * @return an [[Options]] object representing the CLI syntax
   */
  private static Options buildOptions() {
    var help =
        cliOptionBuilder().option("h").longOpt(HELP_OPTION).desc("Displays this message.").build();
    var repl = cliOptionBuilder().longOpt(REPL_OPTION).desc("Runs the Enso REPL.").build();
    var run =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("file")
            .longOpt(RUN_OPTION)
            .desc("Runs a specified Enso file.")
            .build();
    var inspect =
        cliOptionBuilder()
            .longOpt(INSPECT_OPTION)
            .desc("Start the Chrome inspector when --run is used.")
            .build();
    var dumpGraphs =
        cliOptionBuilder()
            .longOpt(DUMP_GRAPHS_OPTION)
            .desc("Dumps IGV graphs when --run is used.")
            .build();
    var docs =
        cliOptionBuilder()
            .longOpt(DOCS_OPTION)
            .desc("Runs the Enso documentation generator.")
            .build();
    var preinstall =
        cliOptionBuilder()
            .longOpt(PREINSTALL_OPTION)
            .desc("Installs dependencies of the project.")
            .build();
    var newOpt =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("path")
            .longOpt(NEW_OPTION)
            .desc("Creates a new Enso project.")
            .build();
    var newProjectNameOpt =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("name")
            .longOpt(PROJECT_NAME_OPTION)
            .desc("Specifies a project name when creating a project using --" + NEW_OPTION + ".")
            .build();
    var newProjectModuleNameOpt =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("name")
            .longOpt(PROJECT_NORMALIZED_NAME_OPTION)
            .desc(
                "Specifies a normalized (Upper_Snake_Case) name when creating a project using --"
                    + NEW_OPTION
                    + ".")
            .build();
    var newProjectTemplateOpt =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("name")
            .longOpt(PROJECT_TEMPLATE_OPTION)
            .desc(
                "Specifies a project template when creating a project using --" + NEW_OPTION + ".")
            .build();
    var newProjectAuthorNameOpt =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("name")
            .longOpt(PROJECT_AUTHOR_NAME_OPTION)
            .desc(
                "Specifies the name of the author and maintainer of the project "
                    + "created using --"
                    + NEW_OPTION
                    + ".")
            .build();
    var newProjectAuthorEmailOpt =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("email")
            .longOpt(PROJECT_AUTHOR_EMAIL_OPTION)
            .desc(
                "Specifies the email of the author and maintainer of the project "
                    + "created using --"
                    + NEW_OPTION
                    + ".")
            .build();
    var lsOption =
        cliOptionBuilder().longOpt(LANGUAGE_SERVER_OPTION).desc("Runs Language Server").build();
    var lsProfilingPathOption =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("file")
            .longOpt(PROFILING_PATH)
            .desc("The path to the Language Server profiling file.")
            .build();
    var lsProfilingTimeOption =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("seconds")
            .longOpt(PROFILING_TIME)
            .desc("The duration in seconds limiting the profiling time.")
            .build();
    var deamonizeOption =
        cliOptionBuilder().longOpt(DAEMONIZE_OPTION).desc("Daemonize Language Server").build();
    var interfaceOption =
        cliOptionBuilder()
            .longOpt(INTERFACE_OPTION)
            .hasArg(true)
            .numberOfArgs(1)
            .argName("interface")
            .desc("Interface for processing all incoming connections")
            .build();
    var rpcPortOption =
        cliOptionBuilder()
            .longOpt(RPC_PORT_OPTION)
            .hasArg(true)
            .numberOfArgs(1)
            .argName("rpc-port")
            .desc("RPC port for processing all incoming connections")
            .build();
    var secureRpcPortOption =
        cliOptionBuilder()
            .longOpt(SECURE_RPC_PORT_OPTION)
            .hasArg(true)
            .numberOfArgs(1)
            .argName("rpc-port")
            .desc("A secure RPC port for processing all incoming connections")
            .build();
    var dataPortOption =
        cliOptionBuilder()
            .longOpt(DATA_PORT_OPTION)
            .hasArg(true)
            .numberOfArgs(1)
            .argName("data-port")
            .desc("Data port for visualization protocol")
            .build();
    var secureDataPortOption =
        cliOptionBuilder()
            .longOpt(SECURE_DATA_PORT_OPTION)
            .hasArg(true)
            .numberOfArgs(1)
            .argName("data-port")
            .desc("A secure data port for visualization protocol")
            .build();
    var uuidOption =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("uuid")
            .longOpt(ROOT_ID_OPTION)
            .desc("Content root id.")
            .build();
    var pathOption =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("path")
            .longOpt(ROOT_PATH_OPTION)
            .desc("Path to the content root.")
            .build();
    var inProjectOption =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("project-path")
            .longOpt(IN_PROJECT_OPTION)
            .desc(
                "Setting this option when running the REPL or an Enso script, runs it"
                    + "in context of the specified project.")
            .build();
    var version =
        cliOptionBuilder()
            .longOpt(VERSION_OPTION)
            .desc("Checks the version of the Enso executable.")
            .build();
    var json =
        cliOptionBuilder()
            .longOpt(JSON_OPTION)
            .desc("Switches the --version option to JSON output.")
            .build();
    var logLevelOption =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("log-level")
            .longOpt(LOG_LEVEL)
            .desc(
                "Sets the runtime log level. Possible values are: OFF, ERROR, "
                    + "WARNING, INFO, DEBUG and TRACE. Default: INFO.")
            .build();
    var loggerConnectOption =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("uri")
            .longOpt(LOGGER_CONNECT)
            .desc("Connects to a logging service server and passes all logs to it.")
            .build();
    var noLogMaskingOption =
        cliOptionBuilder()
            .longOpt(NO_LOG_MASKING)
            .desc(
                "Disable masking of personally identifiable information in logs. "
                    + "Masking can be also disabled with the `NO_LOG_MASKING` environment "
                    + "variable.")
            .build();
    var uploadOption =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("url")
            .longOpt(UPLOAD_OPTION)
            .desc(
                "Uploads the library to a repository. "
                    + "The url defines the repository to upload to.")
            .build();
    var updateManifestOption =
        cliOptionBuilder()
            .longOpt(UPDATE_MANIFEST_OPTION)
            .desc("Updates the library manifest with the updated list of direct " + "dependencies.")
            .build();
    var hideProgressOption =
        cliOptionBuilder()
            .longOpt(HIDE_PROGRESS)
            .desc("If specified, progress bars will not be displayed.")
            .build();
    var authTokenOption =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("token")
            .longOpt(AUTH_TOKEN)
            .desc("Authentication token for the upload.")
            .build();
    var noReadIrCachesOption =
        cliOptionBuilder()
            .longOpt(NO_READ_IR_CACHES_OPTION)
            .desc("Disables the reading of IR caches in the runtime if IR caching is enabled.")
            .build();
    var compileOption =
        cliOptionBuilder()
            .hasArg(true)
            .numberOfArgs(1)
            .argName("package")
            .longOpt(COMPILE_OPTION)
            .desc("Compile the provided package without executing it.")
            .build();
    var noCompileDependenciesOption =
        cliOptionBuilder()
            .longOpt(NO_COMPILE_DEPENDENCIES_OPTION)
            .desc(
                "Tells the compiler to not compile dependencies when performing static"
                    + " compilation.")
            .build();
    var noGlobalCacheOption =
        cliOptionBuilder()
            .longOpt(NO_GLOBAL_CACHE_OPTION)
            .desc("Tells the compiler not to write compiled data to the global cache locations.")
            .build();

    var irCachesOption =
        cliOptionBuilder()
            .longOpt(IR_CACHES_OPTION)
            .desc(
                "Enables IR caches. These are on by default in production builds "
                    + "and off by default in developer builds. You may not specify this "
                    + "option with `--no-ir-caches`.")
            .build();
    var noIrCachesOption =
        cliOptionBuilder()
            .longOpt(NO_IR_CACHES_OPTION)
            .desc(
                "Disables IR caches. These are on by default in production builds "
                    + "and off by default in developer builds. You may not specify this "
                    + "option with `--ir-caches`.")
            .build();

    var cacheOptionsGroup = new OptionGroup();
    cacheOptionsGroup.addOption(irCachesOption);
    cacheOptionsGroup.addOption(noIrCachesOption);

    var autoParallelism =
        cliOptionBuilder()
            .longOpt(AUTO_PARALLELISM_OPTION)
            .desc("Enables auto parallelism in the Enso interpreter.")
            .build();

    var skipGraalVMUpdater =
        cliOptionBuilder()
            .longOpt(SKIP_GRAALVM_UPDATER)
            .desc("Skips GraalVM and its components setup during bootstrapping.")
            .build();

    var executionEnvironmentOption =
        cliOptionBuilder()
            .longOpt(EXECUTION_ENVIRONMENT_OPTION)
            .hasArg(true)
            .numberOfArgs(1)
            .argName("name")
            .desc(
                "Execution environment to use during execution (`live`/`design`). Defaults to"
                    + " `live`.")
            .build();

    var warningsLimitOption =
        cliOptionBuilder()
            .longOpt(WARNINGS_LIMIT)
            .hasArg(true)
            .numberOfArgs(1)
            .argName("limit")
            .desc("Specifies a maximal number of reported warnings. Defaults to `100`.")
            .build();

    var disablePrivateCheckOption =
        cliOptionBuilder()
            .longOpt(DISABLE_PRIVATE_CHECK_OPTION)
            .desc("Disables private module checking at runtime. Useful for tests.")
            .build();

    var options = new Options();
    options
        .addOption(help)
        .addOption(repl)
        .addOption(run)
        .addOption(inspect)
        .addOption(dumpGraphs)
        .addOption(docs)
        .addOption(preinstall)
        .addOption(newOpt)
        .addOption(newProjectNameOpt)
        .addOption(newProjectModuleNameOpt)
        .addOption(newProjectTemplateOpt)
        .addOption(newProjectAuthorNameOpt)
        .addOption(newProjectAuthorEmailOpt)
        .addOption(lsOption)
        .addOption(lsProfilingPathOption)
        .addOption(lsProfilingTimeOption)
        .addOption(deamonizeOption)
        .addOption(interfaceOption)
        .addOption(rpcPortOption)
        .addOption(dataPortOption)
        .addOption(secureRpcPortOption)
        .addOption(secureDataPortOption)
        .addOption(uuidOption)
        .addOption(pathOption)
        .addOption(inProjectOption)
        .addOption(version)
        .addOption(json)
        .addOption(logLevelOption)
        .addOption(loggerConnectOption)
        .addOption(noLogMaskingOption)
        .addOption(uploadOption)
        .addOption(updateManifestOption)
        .addOption(hideProgressOption)
        .addOption(authTokenOption)
        .addOption(noReadIrCachesOption)
        .addOption(compileOption)
        .addOption(noCompileDependenciesOption)
        .addOption(noGlobalCacheOption)
        .addOptionGroup(cacheOptionsGroup)
        .addOption(autoParallelism)
        .addOption(skipGraalVMUpdater)
        .addOption(executionEnvironmentOption)
        .addOption(warningsLimitOption)
        .addOption(disablePrivateCheckOption);

    return options;
  }

  /**
   * Prints the help message to the standard output.
   *
   * @param options object representing the CLI syntax
   */
  private static void printHelp(Options options) {
    new HelpFormatter().printHelp(LanguageInfo.ID, options);
  }

  /** Terminates the process with a failure exit code. */
  private RuntimeException exitFail() {
    return doExit(1);
  }

  /** Terminates the process with a success exit code. */
  private RuntimeException exitSuccess() {
    return doExit(0);
  }

  /** Shuts down the logging service and terminates the process. */
  private RuntimeException doExit(int exitCode) {
    RunnerLogging.tearDown();
    System.exit(exitCode);
    return null;
  }

  /**
   * Handles the `--new` CLI option.
   *
   * <p>Creates a project at the provided path. If the nameOption is provided it specifies the
   * project name, otherwise the name is generated automatically. The Enso version used in the
   * project is set to the version of this runner.
   *
   * @param path root path of the newly created project
   * @param nameOption specifies the name of the created project
   * @param normalizedNameOption specifies the normalized name of the created project
   * @param templateOption specifies the template of the created project
   * @param authorName if set, sets the name of the author and maintainer
   * @param authorEmail if set, sets the email of the author and maintainer
   */
  private void createNew(
      String path,
      scala.Option<String> nameOption,
      scala.Option<String> normalizedNameOption,
      scala.Option<String> templateOption,
      scala.Option<String> authorName,
      scala.Option<String> authorEmail) {
    final var root = new File(path);
    String name = nameOption.getOrElse(() -> PackageManager$.MODULE$.Default().generateName(root));
    scala.collection.immutable.List<Contact> authors =
        (authorName.isEmpty() && authorEmail.isEmpty())
            ? nil()
            : join(new Contact(authorName, authorEmail), nil());

    var edition = DefaultEdition.getDefaultEdition();
    if (logger.isTraceEnabled()) {
      var baseEdition = edition.parent().getOrElse(() -> "<no-base>");
      logger.trace("Creating a new project " + name + " based on edition [" + baseEdition + "].");
    }

    var template =
        templateOption.map(
            (n) -> {
              return Template.fromString(n)
                  .getOrElse(
                      () -> {
                        logger.error("Unknown project template name: '" + n + "'.");
                        throw exitFail();
                      });
            });

    PackageManager$.MODULE$
        .Default()
        .create(
            root,
            name,
            "local",
            normalizedNameOption,
            "0.0.1",
            template.getOrElse(() -> Template.Default$.MODULE$),
            scala.Option.apply(edition),
            authors,
            nil(),
            "",
            ComponentGroups.empty());
    throw exitSuccess();
  }

  /**
   * Handles the `--compile` CLI option.
   *
   * @param packagePath the path to the package being compiled
   * @param shouldCompileDependencies whether the dependencies of that package should also be
   *     compiled
   * @param shouldUseGlobalCache whether or not the compilation result should be written to the
   *     global cache
   * @param logLevel the logging level
   * @param logMasking whether or not log masking is enabled
   */
  private void compile(
      String packagePath,
      boolean shouldCompileDependencies,
      boolean shouldUseGlobalCache,
      Level logLevel,
      boolean logMasking) {
    var file = new File(packagePath);
    if (!file.exists() || !file.isDirectory()) {
      println("No package exists at " + file + ".");
      throw exitFail();
    }

    var context =
        ContextFactory.create()
            .projectRoot(packagePath)
            .in(System.in)
            .out(System.out)
            .repl(new Repl(makeTerminalForRepl()))
            .logLevel(logLevel)
            .logMasking(logMasking)
            .enableIrCaches(true)
            .strictErrors(true)
            .useGlobalIrCacheLocation(shouldUseGlobalCache)
            .build();

    var topScope = context.getTopScope();
    try {
      topScope.compile(shouldCompileDependencies);
      throw exitSuccess();
    } catch (Throwable t) {
      logger.error("Unexpected internal error", t);
      throw exitFail();
    } finally {
      context.context().close();
    }
  }

  /**
   * Handles the `--run` CLI option.
   *
   * <p>If `path` is a directory, so a project is run, a conflicting (pointing to another project)
   * `projectPath` should not be provided.
   *
   * @param path path of the project or file to execute
   * @param projectPath if specified, the script is run in context of a project located at that path
   * @param logLevel log level to set for the engine runtime
   * @param logMasking is the log masking enabled
   * @param enableIrCaches are IR caches enabled
   * @param disablePrivateCheck Is private modules check disabled. If yes, `private` keyword is
   *     ignored.
   * @param inspect shall inspect option be enabled
   * @param dump shall graphs be sent to the IGV
   * @param executionEnvironment name of the execution environment to use during execution or {@code
   *     null}
   */
  private void run(
      String path,
      java.util.List<String> additionalArgs,
      String projectPath,
      Level logLevel,
      boolean logMasking,
      boolean enableIrCaches,
      boolean disablePrivateCheck,
      boolean enableAutoParallelism,
      boolean inspect,
      boolean dump,
      String executionEnvironment,
      int warningsLimit)
      throws IOException {
    var fileAndProject = Utils.findFileAndProject(path, projectPath);
    if (fileAndProject == null) {
      throw exitFail();
    }
    var projectMode = fileAndProject._1();
    var file = fileAndProject._2();
    var projectRoot = fileAndProject._3();
    var options = new HashMap<String, String>();
    if (dump) {
      options.put("engine.TraceCompilation", "true");
      options.put("engine.MultiTier", "false");
    }
    if (inspect) {
      options.put("inspect", "");
    }
    var context =
        ContextFactory.create()
            .projectRoot(projectRoot)
            .in(System.in)
            .out(System.out)
            .repl(new Repl(makeTerminalForRepl()))
            .logLevel(logLevel)
            .logMasking(logMasking)
            .enableIrCaches(enableIrCaches)
            .disablePrivateCheck(disablePrivateCheck)
            .strictErrors(true)
            .enableAutoParallelism(enableAutoParallelism)
            .executionEnvironment(executionEnvironment != null ? executionEnvironment : "live")
            .warningsLimit(warningsLimit)
            .options(options)
            .build();

    if (projectMode) {
      var result = PackageManager$.MODULE$.Default().loadPackage(file);
      if (result.isSuccess()) {
        var s = (scala.util.Success) result;
        @SuppressWarnings("unchecked")
        var pkg = (org.enso.pkg.Package<java.io.File>) s.get();
        var main = pkg.mainFile();
        if (!main.exists()) {
          println("Main file does not exist.");
          context.context().close();
          throw exitFail();
        }
        var mainModuleName = pkg.moduleNameForFile(pkg.mainFile()).toString();
        runPackage(context, mainModuleName, file, additionalArgs);
      } else {
        println(((scala.util.Failure) result).exception().getMessage());
        throw exitFail();
      }
    } else {
      runSingleFile(context, file, additionalArgs);
    }
    context.context().close();
    throw exitSuccess();
  }

  /**
   * Handles the `--docs` CLI option.
   *
   * <p>Generates reference website from standard library.
   *
   * @param projectPath if specified, the docs is generated for a project at the given path
   * @param logLevel log level to set for the engine runtime
   * @param logMasking is the log masking enabled
   * @param enableIrCaches are the IR caches enabled
   */
  private void genDocs(
      String projectPath, Level logLevel, boolean logMasking, boolean enableIrCaches) {
    if (projectPath.isEmpty()) {
      println("Path hasn't been provided.");
      throw exitFail();
    }
    generateDocsFrom(projectPath, logLevel, logMasking, enableIrCaches);
    throw exitSuccess();
  }

  /**
   * Subroutine of `genDocs` function. Generates the documentation for given Enso project at given
   * path.
   */
  private void generateDocsFrom(
      String path, Level logLevel, boolean logMasking, boolean enableIrCaches) {
    var executionContext =
        ContextFactory.create()
            .projectRoot(path)
            .in(System.in)
            .out(System.out)
            .repl(new Repl(makeTerminalForRepl()))
            .logLevel(logLevel)
            .logMasking(logMasking)
            .enableIrCaches(enableIrCaches)
            .build();

    var file = new File(path);
    var pkg = PackageManager.Default().fromDirectory(file);
    var main = pkg.map(x -> x.mainFile());

    if (main.exists(x -> x.exists())) {
      var mainFile = main.get();
      var mainModuleName = pkg.get().moduleNameForFile(mainFile).toString();
      var topScope = executionContext.getTopScope();
      var mainModule = topScope.getModule(mainModuleName);
      var generated = mainModule.generateDocs();
      println(generated.toString());

      // TODO:
      // - go through executed code and get all HTML docs
      //   with their corresponding atoms/methods etc.
      // - Save those to files
    }
  }

  /**
   * Handles the `--preinstall-dependencies` CLI option.
   *
   * <p>Gathers imported dependencies and ensures that all of them are installed.
   *
   * @param projectPath path of the project
   * @param logLevel log level to set for the engine runtime
   */
  private void preinstallDependencies(String projectPath, Level logLevel) {
    if (projectPath == null) {
      println("Dependency installation is only available for projects.");
      throw exitFail();
    }
    try {
      DependencyPreinstaller.preinstallDependencies(new File(projectPath), logLevel);
      throw exitSuccess();
    } catch (RuntimeException error) {
      logger.error("Dependency installation failed: " + error.getMessage(), error);
      throw exitFail();
    }
  }

  private void runPackage(
      PolyglotContext context,
      String mainModuleName,
      File projectPath,
      java.util.List<String> additionalArgs) {
    var topScope = context.getTopScope();
    var mainModule = topScope.getModule(mainModuleName);
    runMain(mainModule, projectPath, additionalArgs, "main");
  }

  private void runSingleFile(
      PolyglotContext context, File file, java.util.List<String> additionalArgs) {
    var mainModule = context.evalModule(file);
    runMain(mainModule, file, additionalArgs, "main");
  }

  private void runMain(
      Module mainModule,
      File rootPkgPath,
      java.util.List<String> additionalArgs,
      String mainMethodName // = "main"
      ) {
    try {
      var mainType = mainModule.getAssociatedType();
      var mainFun = mainModule.getMethod(mainType, mainMethodName);
      if (mainFun.isEmpty()) {
        System.err.println(
            "The module "
                + mainModule.getName()
                + " does not contain a `main` "
                + "function. It could not be run.");
        throw exitFail();
      }
      var main = mainFun.get();
      if (!"main".equals(mainMethodName)) {
        main.execute(join(mainType, nil()));
      } else {
        // Opportunistically parse arguments and convert to ints.
        // This avoids conversions in main function.
        var parsedArgs =
            additionalArgs.stream()
                .map(
                    arg -> {
                      try {
                        return Integer.valueOf(arg);
                      } catch (NumberFormatException ex) {
                        return arg;
                      }
                    })
                .toList();
        var listOfArgs = nil();
        for (var e : parsedArgs) {
          listOfArgs = join(e, listOfArgs);
        }
        var res = main.execute(listOfArgs.reverse());
        if (!res.isNull()) {
          var textRes = res.isString() ? res.asString() : res.toString();
          println(textRes);
        }
      }
    } catch (PolyglotException e) {
      printPolyglotException(e, rootPkgPath);
      throw exitFail();
    }
  }

  /**
   * Handles the `--repl` CLI option
   *
   * @param projectPath if specified, the REPL is run in context of a project at the given path
   * @param logLevel log level to set for the engine runtime
   * @param logMasking is the log masking enabled
   * @param enableIrCaches are IR caches enabled
   */
  private void runRepl(
      String projectPath, Level logLevel, boolean logMasking, boolean enableIrCaches) {
    var mainMethodName = "internal_repl_entry_point___";
    var dummySourceToTriggerRepl =
        """
         from Standard.Base import all
         import Standard.Base.Runtime.Debug

         $mainMethodName = Debug.breakpoint
         """
            .replace("$mainMethodName", mainMethodName);
    var replModuleName = "Internal_Repl_Module___";
    var projectRoot = projectPath != null ? projectPath : "";
    var context =
        ContextFactory.create()
            .projectRoot(projectRoot)
            .in(System.in)
            .out(System.out)
            .repl(new Repl(makeTerminalForRepl()))
            .logLevel(logLevel)
            .logMasking(logMasking)
            .enableIrCaches(enableIrCaches)
            .build();
    var mainModule = context.evalModule(dummySourceToTriggerRepl, replModuleName);
    runMain(mainModule, null, Collections.emptyList(), mainMethodName);
    throw exitSuccess();
  }

  /**
   * Prints the version of the Enso executable.
   *
   * @param useJson whether the output should be JSON or human-readable.
   */
  private void displayVersion(boolean useJson) {
    var versionDescription =
        VersionDescription.make(
            "Enso Compiler and Runtime",
            true,
            VersionDescription.make$default$3(),
            VersionDescription.make$default$4(),
            scala.Option.apply(CurrentVersion.version().toString()));
    println(versionDescription.asString(useJson));
  }

  /** Parses the log level option. */
  private Level parseLogLevel(String levelOption) {
    var name = levelOption.toLowerCase();
    var found =
        Stream.of(Level.values()).filter(x -> name.equals(x.name().toLowerCase())).findFirst();
    if (found.isEmpty()) {
      var possible =
          Stream.of(Level.values())
              .map(x -> x.toString().toLowerCase())
              .collect(Collectors.joining(", "));
      System.err.println("Invalid log level. Possible values are " + possible + ".");
      throw exitFail();
    } else {
      return found.get();
    }
  }

  /** Parses an URI that specifies the logging service connection. */
  private URI parseUri(String string) {
    try {
      return new URI(string);
    } catch (URISyntaxException ex) {
      System.err.println("`" + string + "` is not a valid URI.");
      throw exitFail();
    }
  }

  /** Default log level to use if the LOG_LEVEL option is not provided. */
  private static final Level defaultLogLevel = Level.WARN;

  /**
   * Main entry point for the CLI program.
   *
   * @param args the command line arguments
   */
  public static void main(String[] args) throws IOException {
    new Main().launch(args);
  }

  /**
   * Main entry point for the CLI program.
   *
   * @param options the command line options
   * @param line the provided command line arguments
   * @param logLevel the provided log level
   * @param logMasking the flag indicating if the log masking is enabled
   */
  private void runMain(Options options, CommandLine line, Level logLevel, boolean logMasking)
      throws IOException {
    if (line.hasOption(HELP_OPTION)) {
      printHelp(options);
      throw exitSuccess();
    }
    if (line.hasOption(VERSION_OPTION)) {
      displayVersion(line.hasOption(JSON_OPTION));
      throw exitSuccess();
    }

    if (line.hasOption(NEW_OPTION)) {
      createNew(
          line.getOptionValue(NEW_OPTION),
          scala.Option.apply(line.getOptionValue(PROJECT_NAME_OPTION)),
          scala.Option.apply(line.getOptionValue(PROJECT_NORMALIZED_NAME_OPTION)),
          scala.Option.apply(line.getOptionValue(PROJECT_TEMPLATE_OPTION)),
          scala.Option.apply(line.getOptionValue(PROJECT_AUTHOR_NAME_OPTION)),
          scala.Option.apply(line.getOptionValue(PROJECT_AUTHOR_EMAIL_OPTION)));
    }

    if (line.hasOption(UPLOAD_OPTION)) {
      scala.Option<Path> projectRoot =
          scala.Option.apply(line.getOptionValue(IN_PROJECT_OPTION))
              .map(x -> Path.of(x))
              .getOrElse(
                  () -> {
                    logger.error(
                        "When uploading, the "
                            + IN_PROJECT_OPTION
                            + " is mandatory "
                            + "to specify which project to upload.");
                    throw exitFail();
                  });

      try {
        ProjectUploader.uploadProject(
            projectRoot.get(),
            line.getOptionValue(UPLOAD_OPTION),
            scala.Option.apply(line.getOptionValue(AUTH_TOKEN)),
            !line.hasOption(HIDE_PROGRESS),
            logLevel);
        throw exitSuccess();
      } catch (UploadFailedError ex) {
        // We catch this error to avoid printing an unnecessary stack trace.
        // The error itself is already logged.
        throw exitFail();
      }
    }

    if (line.hasOption(UPDATE_MANIFEST_OPTION)) {
      Path projectRoot =
          scala.Option.apply(line.getOptionValue(IN_PROJECT_OPTION))
              .map(x -> Path.of(x))
              .getOrElse(
                  () -> {
                    logger.error("The " + IN_PROJECT_OPTION + " is mandatory.");
                    throw exitFail();
                  });
      try {
        ProjectUploader.updateManifest(projectRoot, logLevel);
      } catch (Throwable err) {
        err.printStackTrace();
        throw exitFail();
      }
      throw exitSuccess();
    }

    if (line.hasOption(COMPILE_OPTION)) {
      var packagePaths = line.getOptionValue(COMPILE_OPTION);
      var shouldCompileDependencies = !line.hasOption(NO_COMPILE_DEPENDENCIES_OPTION);
      var shouldUseGlobalCache = !line.hasOption(NO_GLOBAL_CACHE_OPTION);

      compile(packagePaths, shouldCompileDependencies, shouldUseGlobalCache, logLevel, logMasking);
    }

    if (line.hasOption(RUN_OPTION)) {
      run(
          line.getOptionValue(RUN_OPTION),
          Arrays.asList(line.getArgs()),
          line.getOptionValue(IN_PROJECT_OPTION),
          logLevel,
          logMasking,
          shouldEnableIrCaches(line),
          line.hasOption(DISABLE_PRIVATE_CHECK_OPTION),
          line.hasOption(AUTO_PARALLELISM_OPTION),
          line.hasOption(INSPECT_OPTION),
          line.hasOption(DUMP_GRAPHS_OPTION),
          line.getOptionValue(EXECUTION_ENVIRONMENT_OPTION),
          scala.Option.apply(line.getOptionValue(WARNINGS_LIMIT))
              .map(Integer::parseInt)
              .getOrElse(() -> 100));
    }
    if (line.hasOption(REPL_OPTION)) {
      runRepl(
          line.getOptionValue(IN_PROJECT_OPTION), logLevel, logMasking, shouldEnableIrCaches(line));
    }
    if (line.hasOption(DOCS_OPTION)) {
      genDocs(
          line.getOptionValue(IN_PROJECT_OPTION), logLevel, logMasking, shouldEnableIrCaches(line));
    }
    if (line.hasOption(PREINSTALL_OPTION)) {
      preinstallDependencies(line.getOptionValue(IN_PROJECT_OPTION), logLevel);
    }
    if (line.getOptions().length == 0) {
      printHelp(options);
      throw exitFail();
    }
  }

  /**
   * Checks whether IR caching should be enabled.
   *
   * <p>The (mutually exclusive) flags can control it explicitly, otherwise it defaults to off in
   * development builds and on in production builds.
   *
   * @param line the command-line
   * @return `true` if caching should be enabled, `false`, otherwise
   */
  private static boolean shouldEnableIrCaches(CommandLine line) {
    if (line.hasOption(IR_CACHES_OPTION)) {
      return true;
    } else if (line.hasOption(NO_IR_CACHES_OPTION)) {
      return false;
    } else {
      return !isDevBuild();
    }
  }

  /** Constructs a terminal interface for the REPL, initializing its properties. */
  private static ReplIO makeTerminalForRepl() {
    var env = new Environment() {};
    var distributionManager = new DistributionManager(env);
    var historyFileName = "repl-history.txt";
    var historyFilePath =
        distributionManager.LocallyInstalledDirectories().cacheDirectory().resolve(historyFileName);
    return new TerminalIO(historyFilePath);
  }

  private static <A> A withProfiling(
      ProfilingConfig profilingConfig,
      ExecutionContextExecutor executor,
      java.util.concurrent.Callable<A> main)
      throws IOException {
    var path = profilingConfig.profilingPath();
    var sampler =
        path.isDefined() ? OutputStreamSampler.ofFile(path.get().toFile()) : new NoopSampler();
    sampler.start();
    profilingConfig
        .profilingTime()
        .foreach(timeout -> sampler.scheduleStop(timeout.length(), timeout.unit(), executor));
    scala.sys.package$.MODULE$.addShutdownHook(
        () -> {
          try {
            sampler.stop();
          } catch (IOException ex) {
            logger.info("Error stopping sampler", ex);
          }
          return BoxedUnit.UNIT;
        });

    try {
      return main.call();
    } catch (IOException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IOException(ex);
    } finally {
      sampler.stop();
    }
  }

  /**
   * Handles `--server` CLI option
   *
   * @param line a CLI line
   * @param logLevel log level to set for the engine runtime
   */
  private void runLanguageServer(CommandLine line, Level logLevel) {
    try {
      var config = parseServerOptions(line);
      LanguageServerApp.run(config, logLevel, line.hasOption(Main.DAEMONIZE_OPTION));
      throw exitSuccess();
    } catch (WrongOption e) {
      System.err.println(e.getMessage());
      throw exitFail();
    }
  }

  private static LanguageServerConfig parseServerOptions(CommandLine line) throws WrongOption {
    UUID rootId;
    try {
      var id = line.getOptionValue(ROOT_ID_OPTION);
      if (id == null) {
        throw new WrongOption("Root id must be provided");
      }
      rootId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new WrongOption("Root must be UUID");
    }
    var rootPath = line.getOptionValue(ROOT_PATH_OPTION);
    if (rootPath == null) {
      throw new WrongOption("Root path must be provided");
    }
    var interfac = line.getOptionValue(INTERFACE_OPTION, "127.0.0.1");
    int rpcPort;
    try {
      rpcPort = Integer.parseInt(line.getOptionValue(RPC_PORT_OPTION, "8080"));
    } catch (NumberFormatException e) {
      throw new WrongOption("Port must be integer");
    }
    int dataPort;
    try {
      dataPort = Integer.parseInt(line.getOptionValue(DATA_PORT_OPTION, "8081"));
    } catch (NumberFormatException e) {
      throw new WrongOption("Port must be integer");
    }
    Integer secureRpcPort;
    try {
      var port = line.getOptionValue(SECURE_RPC_PORT_OPTION);
      secureRpcPort = port == null ? null : Integer.valueOf(port);
    } catch (NumberFormatException e) {
      throw new WrongOption("Port must be integer");
    }
    Integer secureDataPort;
    try {
      var port = line.getOptionValue(SECURE_DATA_PORT_OPTION);
      secureDataPort = port == null ? null : Integer.valueOf(port);
    } catch (NumberFormatException e) {
      throw new WrongOption("Port must be integer");
    }
    var profilingConfig = parseProfilingConfig(line);
    var graalVMUpdater = line.hasOption(SKIP_GRAALVM_UPDATER);

    var config =
        new LanguageServerConfig(
            interfac,
            rpcPort,
            scala.Option.apply(secureRpcPort),
            dataPort,
            scala.Option.apply(secureDataPort),
            rootId,
            rootPath,
            profilingConfig,
            new StartupConfig(graalVMUpdater),
            "language-server",
            ExecutionContext.global());
    return config;
  }

  private static ProfilingConfig parseProfilingConfig(CommandLine line) throws WrongOption {
    Path profilingPath = null;
    try {
      var path = line.getOptionValue(PROFILING_PATH);
      if (path != null) {
        profilingPath = Paths.get(path);
      }
    } catch (InvalidPathException e) {
      throw new WrongOption("Profiling path is invalid");
    }
    FiniteDuration profilingTime = null;
    try {
      var time = line.getOptionValue(PROFILING_TIME);
      if (time != null) {
        profilingTime = FiniteDuration.apply(Integer.parseInt(time), TimeUnit.SECONDS);
      }
    } catch (NumberFormatException e) {
      throw new WrongOption("Profiling time should be an integer");
    }
    return new ProfilingConfig(
        scala.Option.apply(profilingPath), scala.Option.apply(profilingTime));
  }

  private void printFrame(StackFrame frame, File relativeTo) {
    var langId = frame.isHostFrame() ? "java" : frame.getLanguage().getId();

    String fmtFrame;
    if (LanguageInfo.ID.equals(langId)) {
      var fName = frame.getRootName();

      var src = "Internal";
      var sourceLoc = frame.getSourceLocation();
      if (sourceLoc != null) {
        var path = sourceLoc.getSource().getPath();
        var ident = sourceLoc.getSource().getName();
        if (path != null) {
          if (relativeTo != null) {
            var absRoot = relativeTo.getAbsoluteFile();
            if (path.startsWith(absRoot.getAbsolutePath())) {
              var rootDir = absRoot.isDirectory() ? absRoot : absRoot.getParentFile();
              ident = rootDir.toPath().relativize(new File(path).toPath()).toString();
            }
          }
        }

        var loc = sourceLoc.getStartLine() + "-" + sourceLoc.getEndLine();
        var line = sourceLoc.getStartLine();
        if (line == sourceLoc.getEndLine()) {
          var start = sourceLoc.getStartColumn();
          var end = sourceLoc.getEndColumn();
          loc = line + ":" + start + "-" + end;
        }
        src = ident + ":" + loc;
      }
      fmtFrame = fName + "(" + src + ")";
    } else {
      fmtFrame = frame.toString();
    }
    println("        at <" + langId + "> " + fmtFrame);
  }

  private void printPolyglotException(PolyglotException exception, File relativeTo) {
    var fullStackReversed = new LinkedList<StackFrame>();
    for (var e : exception.getPolyglotStackTrace()) {
      fullStackReversed.addFirst(e);
    }

    var dropInitJava =
        fullStackReversed.stream()
            .dropWhile(f -> !LanguageInfo.ID.equals(f.getLanguage().getId()))
            .toList();
    Collections.reverse(fullStackReversed);
    var msg = HostEnsoUtils.findExceptionMessage(exception);
    println("Execution finished with an error: " + msg);

    if (exception.isSyntaxError()) {
      // no stack
    } else if (dropInitJava.isEmpty()) {
      for (var f : exception.getPolyglotStackTrace()) {
        printFrame(f, relativeTo);
      }
    } else {
      for (var f : dropInitJava) {
        printFrame(f, relativeTo);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static final <T> scala.collection.immutable.List<T> nil() {
    return (scala.collection.immutable.List<T>) scala.collection.immutable.Nil$.MODULE$;
  }

  private static final <T> scala.collection.immutable.List<T> join(
      T head, scala.collection.immutable.List<T> tail) {
    return scala.collection.immutable.$colon$colon$.MODULE$.apply(head, tail);
  }

  private void println(String msg) {
    System.out.println(msg);
  }

  private void launch(String[] args) {
    var options = buildOptions();
    var line = preprocessArguments(options, args);
    launch(options, line);
  }

  protected CommandLine preprocessArguments(Options options, String[] args) {
    var parser = new DefaultParser();
    try {
      var line = parser.parse(options, args);
      return line;
    } catch (Exception e) {
      printHelp(options);
      throw exitFail();
    }
  }

  protected void launch(Options options, CommandLine line) {
    var logLevel =
        scala.Option.apply(line.getOptionValue(LOG_LEVEL))
            .map(this::parseLogLevel)
            .getOrElse(() -> defaultLogLevel);
    var connectionUri = scala.Option.apply(line.getOptionValue(LOGGER_CONNECT)).map(this::parseUri);
    var logMasking = !line.hasOption(NO_LOG_MASKING);
    RunnerLogging.setup(connectionUri, logLevel, logMasking);

    if (line.hasOption(LANGUAGE_SERVER_OPTION)) {
      runLanguageServer(line, logLevel);
    } else {
      try {
        var conf = parseProfilingConfig(line);
        try {
          withProfiling(
              conf,
              ExecutionContext.global(),
              () -> {
                runMain(options, line, logLevel, logMasking);
                return BoxedUnit.UNIT;
              });
        } catch (IOException ex) {
          System.err.println(ex.getMessage());
          exitFail();
        }
      } catch (WrongOption e) {
        System.err.println(e.getMessage());
        throw exitFail();
      }
    }
  }

  protected String getLanguageId() {
    return LanguageInfo.ID;
  }

  private static final class WrongOption extends Exception {
    WrongOption(String msg) {
      super(msg);
    }
  }
}
