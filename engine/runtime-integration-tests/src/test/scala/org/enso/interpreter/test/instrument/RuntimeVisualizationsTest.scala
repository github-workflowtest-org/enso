package org.enso.interpreter.test.instrument

import org.enso.interpreter.runtime.`type`.ConstantsGen
import org.enso.interpreter.test.Metadata
import org.enso.pkg.QualifiedName
import org.enso.polyglot._
import org.enso.polyglot.runtime.Runtime.Api
import org.enso.text.editing.model
import org.enso.text.editing.model.TextEdit
import org.graalvm.polyglot.Context
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayOutputStream, File}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.UUID
import java.util.logging.Level

@scala.annotation.nowarn("msg=multiarg infix syntax")
class RuntimeVisualizationsTest extends AnyFlatSpec with Matchers {

  // === Test Utilities =======================================================

  class TestContext(packageName: String, sequentialExecution: Boolean)
      extends InstrumentTestContext(packageName) {

    val out: ByteArrayOutputStream = new ByteArrayOutputStream()
    val context =
      Context
        .newBuilder()
        .allowExperimentalOptions(true)
        .allowAllAccess(true)
        .option(RuntimeOptions.PROJECT_ROOT, pkg.root.getAbsolutePath)
        .option(RuntimeOptions.LOG_LEVEL, Level.WARNING.getName())
        .option(
          RuntimeOptions.INTERPRETER_SEQUENTIAL_COMMAND_EXECUTION,
          sequentialExecution.toString
        )
        .option(
          RuntimeOptions.INTERPRETER_RANDOM_DELAYED_COMMAND_EXECUTION,
          (!sequentialExecution).toString
        )
        .option(RuntimeOptions.ENABLE_PROJECT_SUGGESTIONS, "false")
        .option(RuntimeOptions.ENABLE_GLOBAL_SUGGESTIONS, "false")
        .option(RuntimeOptions.ENABLE_EXECUTION_TIMER, "false")
        .option(RuntimeServerInfo.ENABLE_OPTION, "true")
        .option(RuntimeOptions.INTERACTIVE_MODE, "true")
        .option(
          RuntimeOptions.DISABLE_IR_CACHES,
          InstrumentTestContext.DISABLE_IR_CACHE
        )
        .option("engine.WarnInterpreterOnly", "false")
        .option(
          RuntimeOptions.LANGUAGE_HOME_OVERRIDE,
          Paths.get("../../distribution/component").toFile.getAbsolutePath
        )
        .logHandler(System.err)
        .out(out)
        .serverTransport(runtimeServerEmulator.makeServerTransport)
        .build()

    def writeMain(contents: String): File =
      Files.write(pkg.mainFile.toPath, contents.getBytes).toFile

    def writeFile(file: File, contents: String): File =
      Files.write(file.toPath, contents.getBytes).toFile

    def writeInSrcDir(moduleName: String, contents: String): File = {
      val file = new File(pkg.sourceDir, s"$moduleName.enso")
      Files.write(file.toPath, contents.getBytes).toFile
    }

    def send(msg: Api.Request): Unit = runtimeServerEmulator.sendToRuntime(msg)

    def consumeOut: List[String] = {
      val result = out.toString
      out.reset()
      result.linesIterator.toList
    }

    def executionComplete(contextId: UUID): Api.Response =
      Api.Response(Api.ExecutionComplete(contextId))

    // === The Tests ==========================================================

    object Main { context =>

      val metadata = new Metadata

      val idMainX = metadata.addItem(63, 1, "aa")
      val idMainY = metadata.addItem(73, 7, "ab")
      val idMainZ = metadata.addItem(89, 5, "ac")
      val idFooY  = metadata.addItem(133, 8, "ad")
      val idFooZ  = metadata.addItem(150, 5, "ae")

      def code =
        metadata.appendToCode(
          """
            |from Standard.Base.Data.Numbers import Number
            |
            |main =
            |    x = 6
            |    y = x.foo 5
            |    z = y + 5
            |    z
            |
            |Number.foo self = x ->
            |    y = self + 3
            |    z = y * x
            |    z
            |""".stripMargin.linesIterator.mkString("\n")
        )

      object Update {

        def mainX(
          contextId: UUID,
          fromCache: Boolean   = false,
          typeChanged: Boolean = true
        ): Api.Response =
          Api.Response(
            Api.ExpressionUpdates(
              contextId,
              Set(
                Api.ExpressionUpdate(
                  Main.idMainX,
                  Some(ConstantsGen.INTEGER),
                  None,
                  Vector(Api.ProfilingInfo.ExecutionTime(0)),
                  fromCache,
                  typeChanged,
                  Api.ExpressionUpdate.Payload.Value()
                )
              )
            )
          )

        def mainY(
          contextId: UUID,
          fromCache: Boolean   = false,
          typeChanged: Boolean = true
        ): Api.Response =
          Api.Response(
            Api.ExpressionUpdates(
              contextId,
              Set(
                Api.ExpressionUpdate(
                  Main.idMainY,
                  Some(ConstantsGen.INTEGER),
                  Some(
                    Api.MethodCall(
                      Api.MethodPointer(
                        "Enso_Test.Test.Main",
                        ConstantsGen.NUMBER,
                        "foo"
                      )
                    )
                  ),
                  Vector(Api.ProfilingInfo.ExecutionTime(0)),
                  fromCache,
                  typeChanged,
                  Api.ExpressionUpdate.Payload.Value()
                )
              )
            )
          )

        def mainZ(
          contextId: UUID,
          fromCache: Boolean   = false,
          typeChanged: Boolean = true
        ): Api.Response =
          Api.Response(
            Api.ExpressionUpdates(
              contextId,
              Set(
                Api.ExpressionUpdate(
                  Main.idMainZ,
                  Some(ConstantsGen.INTEGER),
                  Some(
                    Api.MethodCall(
                      Api.MethodPointer(
                        "Standard.Base.Data.Numbers",
                        "Standard.Base.Data.Numbers.Integer",
                        "+"
                      )
                    )
                  ),
                  Vector(Api.ProfilingInfo.ExecutionTime(0)),
                  fromCache,
                  typeChanged,
                  Api.ExpressionUpdate.Payload.Value()
                )
              )
            )
          )

        def fooY(
          contextId: UUID,
          fromCache: Boolean   = false,
          typeChanged: Boolean = true
        ): Api.Response =
          Api.Response(
            Api.ExpressionUpdates(
              contextId,
              Set(
                Api.ExpressionUpdate(
                  Main.idFooY,
                  Some(ConstantsGen.INTEGER),
                  Some(
                    Api.MethodCall(
                      Api.MethodPointer(
                        "Standard.Base.Data.Numbers",
                        "Standard.Base.Data.Numbers.Integer",
                        "+"
                      )
                    )
                  ),
                  Vector(Api.ProfilingInfo.ExecutionTime(0)),
                  fromCache,
                  typeChanged,
                  Api.ExpressionUpdate.Payload.Value()
                )
              )
            )
          )

        def fooZ(
          contextId: UUID,
          fromCache: Boolean   = false,
          typeChanged: Boolean = true
        ): Api.Response =
          Api.Response(
            Api.ExpressionUpdates(
              contextId,
              Set(
                Api.ExpressionUpdate(
                  Main.idFooZ,
                  Some(ConstantsGen.INTEGER),
                  Some(
                    Api.MethodCall(
                      Api.MethodPointer(
                        "Standard.Base.Data.Numbers",
                        "Standard.Base.Data.Numbers.Integer",
                        "*"
                      )
                    )
                  ),
                  Vector(Api.ProfilingInfo.ExecutionTime(0)),
                  fromCache,
                  typeChanged,
                  Api.ExpressionUpdate.Payload.Value()
                )
              )
            )
          )
      }
    }

    object Visualization {

      val metadata = new Metadata

      val code =
        metadata.appendToCode(
          """
            |encode x = x.to_text
            |
            |incAndEncode x =
            |    y = x + 1
            |    encode y
            |
            |""".stripMargin.linesIterator.mkString("\n")
        )

    }

    object AnnotatedVisualization {

      val metadata    = new Metadata
      val idIncY      = metadata.addItem(111, 7)
      val idIncRes    = metadata.addItem(129, 8)
      val idIncMethod = metadata.addItem(102, 43)

      val code =
        metadata.appendToCode(
          """import Standard.Base.IO
            |
            |encode x =
            |   IO.println "encoding..."
            |   x.to_text
            |
            |incAndEncode x a=1 b=1 =
            |    y = a*x + b
            |    res = encode y
            |    res
            |""".stripMargin.linesIterator.mkString("\n")
        )

    }

  }

  def withContext(
    sequentialExecution: Boolean = true
  )(f: TestContext => Unit): Unit = {
    val context = new TestContext("Test", sequentialExecution)
    try {
      context.init()
      val Some(Api.Response(_, Api.InitializedNotification())) = context.receive
      f(context)
    } finally {
      if (context != null) {
        context.close()
        context.out.reset()
      }
    }
  }

  it should "emit visualization update when expression is computed" in withContext() {
    context =>
      val idMainRes  = context.Main.metadata.addItem(99, 1)
      val contents   = context.Main.code
      val mainFile   = context.writeMain(context.Main.code)
      val moduleName = "Enso_Test.Test.Main"
      val visualizationFile =
        context.writeInSrcDir("Visualization", context.Visualization.code)

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      context.send(
        Api.Request(
          requestId,
          Api.OpenFileRequest(
            visualizationFile,
            context.Visualization.code
          )
        )
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        6
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        TestMessages.update(contextId, idMainRes, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMainRes,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Visualization",
                "x -> encode x",
                Vector()
              ),
              "Enso_Test.Test.Visualization"
            )
          )
        )
      )
      val attachVisualizationResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      attachVisualizationResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMainRes`
                ),
                data
              )
            ) =>
          data
      }
      data.sameElements("50".getBytes) shouldBe true

      // recompute
      context.send(
        Api.Request(
          requestId,
          Api.RecomputeContextRequest(contextId, None, None)
        )
      )

      val recomputeResponses = context.receiveNIgnoreExpressionUpdates(3)
      recomputeResponses should contain allOf (
        Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
        context.executionComplete(contextId)
      )
      val Some(data2) = recomputeResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMainRes`
                ),
                data
              )
            ) =>
          data
      }
      data2.sameElements("50".getBytes) shouldBe true
  }

  it should "emit visualization update when expression is cached" in withContext() {
    context =>
      val contents   = context.Main.code
      val mainFile   = context.writeMain(context.Main.code)
      val moduleName = "Enso_Test.Test.Main"
      val visualizationFile =
        context.writeInSrcDir("Visualization", context.Visualization.code)

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      context.send(
        Api.Request(
          requestId,
          Api.OpenFileRequest(
            visualizationFile,
            context.Visualization.code
          )
        )
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        5
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            context.Main.idMainX,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Visualization",
                "x -> encode x",
                Vector()
              ),
              "Enso_Test.Test.Visualization"
            )
          )
        )
      )
      val attachVisualizationResponses = context.receiveN(2)
      attachVisualizationResponses should contain(
        Api.Response(requestId, Api.VisualizationAttached())
      )
      val expectedExpressionId = context.Main.idMainX
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          data
      }
      data.sameElements("6".getBytes) shouldBe true

      // recompute
      context.send(
        Api.Request(
          requestId,
          Api.RecomputeContextRequest(contextId, None, None)
        )
      )
      context.receiveNIgnoreExpressionUpdates(2) should contain allOf (
        Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
        context.executionComplete(contextId)
      )

      // recompute invalidating x
      context.send(
        Api.Request(
          requestId,
          Api.RecomputeContextRequest(
            contextId,
            Some(
              Api.InvalidatedExpressions.Expressions(
                Vector(context.Main.idMainX)
              )
            ),
            None
          )
        )
      )
      val recomputeResponses2 = context.receiveNIgnoreExpressionUpdates(3)
      recomputeResponses2 should contain allOf (
        Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
        context.executionComplete(contextId)
      )
      val Some(data2) = recomputeResponses2.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          data
      }
      data2.sameElements("6".getBytes) shouldBe true
  }

  it should "emit visualization update when expression is modified" in withContext() {
    context =>
      val contents   = context.Main.code
      val moduleName = "Enso_Test.Test.Main"
      val mainFile   = context.writeMain(contents)
      val visualizationFile =
        context.writeInSrcDir("Visualization", context.Visualization.code)

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      // open files
      context.send(
        Api.Request(
          requestId,
          Api.OpenFileRequest(
            visualizationFile,
            context.Visualization.code
          )
        )
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )

      context.receiveNIgnorePendingExpressionUpdates(
        5
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            context.Main.idMainX,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Visualization",
                "x -> encode x",
                Vector()
              ),
              "Enso_Test.Test.Visualization"
            )
          )
        )
      )
      val attachVisualizationResponses = context.receiveN(2)
      attachVisualizationResponses should contain(
        Api.Response(requestId, Api.VisualizationAttached())
      )
      val expectedExpressionId = context.Main.idMainX
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          data
      }
      data.sameElements("6".getBytes) shouldBe true

      // Modify the file
      context.send(
        Api.Request(
          Api.EditFileNotification(
            mainFile,
            Seq(
              TextEdit(
                model.Range(model.Position(4, 8), model.Position(4, 9)),
                "5"
              )
            ),
            execute = true,
            idMap   = None
          )
        )
      )

      val editFileResponse = context.receiveNIgnoreExpressionUpdates(2)
      editFileResponse should contain(
        context.executionComplete(contextId)
      )
      val Some(data1) = editFileResponse.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          data
      }
      data1.sameElements("5".getBytes) shouldBe true
  }

  it should "emit visualization update when transitive expression is modified" in withContext() {
    context =>
      val contents   = context.Main.code
      val moduleName = "Enso_Test.Test.Main"
      val mainFile   = context.writeMain(contents)
      val visualizationFile =
        context.writeInSrcDir("Visualization", context.Visualization.code)

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      // open files
      context.send(
        Api.Request(
          requestId,
          Api.OpenFileRequest(
            visualizationFile,
            context.Visualization.code
          )
        )
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )

      context.receiveNIgnorePendingExpressionUpdates(
        5
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            context.Main.idMainZ,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Visualization",
                "encode",
                Vector()
              ),
              "Enso_Test.Test.Visualization"
            )
          )
        )
      )
      val attachVisualizationResponses = context.receiveN(2)
      attachVisualizationResponses should contain(
        Api.Response(requestId, Api.VisualizationAttached())
      )
      val expectedExpressionId = context.Main.idMainZ
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          data
      }
      data.sameElements("50".getBytes) shouldBe true

      // Modify the file
      context.send(
        Api.Request(
          Api.EditFileNotification(
            mainFile,
            Seq(
              TextEdit(
                model.Range(model.Position(4, 8), model.Position(4, 9)),
                "5"
              )
            ),
            execute = true,
            idMap   = None
          )
        )
      )

      val editFileResponse = context.receiveNIgnoreExpressionUpdates(2)
      editFileResponse should contain(
        context.executionComplete(contextId)
      )
      val Some(data1) = editFileResponse.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          data
      }
      data1.sameElements("45".getBytes) shouldBe true
  }

  it should "emit visualization update when frame popped" in withContext() {
    context =>
      val contents   = context.Main.code
      val moduleName = "Enso_Test.Test.Main"
      val mainFile   = context.writeMain(contents)
      val visualizationFile =
        context.writeInSrcDir("Visualization", context.Visualization.code)

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      // open files
      context.send(
        Api.Request(
          requestId,
          Api.OpenFileRequest(
            visualizationFile,
            context.Visualization.code
          )
        )
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )

      context.receiveNIgnorePendingExpressionUpdates(
        5
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            context.Main.idMainZ,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Visualization",
                "encode",
                Vector()
              ),
              "Enso_Test.Test.Visualization"
            )
          )
        )
      )
      val attachVisualizationResponses = context.receiveN(2)
      attachVisualizationResponses should contain(
        Api.Response(requestId, Api.VisualizationAttached())
      )
      val expectedExpressionId = context.Main.idMainZ
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          data
      }
      new String(data) shouldEqual "50"

      // push foo call
      val item2 = Api.StackItem.LocalCall(context.Main.idMainY)
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item2))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        4
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.fooY(contextId),
        context.Main.Update.fooZ(contextId),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            context.Main.idFooZ,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Visualization",
                "encode",
                Vector()
              ),
              "Enso_Test.Test.Visualization"
            )
          )
        )
      )
      val attachVisualizationResponses2 = context.receiveN(2)
      attachVisualizationResponses2 should contain(
        Api.Response(requestId, Api.VisualizationAttached())
      )
      val expectedExpressionId2 = context.Main.idFooZ
      val Some(data2) = attachVisualizationResponses2.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `expectedExpressionId2`
                ),
                data
              )
            ) =>
          data
      }
      new String(data2) shouldEqual "45"

      // Modify the file
      context.send(
        Api.Request(
          Api.EditFileNotification(
            mainFile,
            Seq(
              TextEdit(
                model.Range(model.Position(10, 15), model.Position(10, 16)),
                "5"
              )
            ),
            execute = true,
            idMap   = None
          )
        )
      )

      val editFileResponse = context.receiveNIgnorePendingExpressionUpdates(4)
      editFileResponse should contain allOf (
        TestMessages.update(
          contextId,
          context.Main.idFooY,
          ConstantsGen.INTEGER,
          methodCall = Some(
            Api.MethodCall(
              Api.MethodPointer(
                "Standard.Base.Data.Numbers",
                "Standard.Base.Data.Numbers.Integer",
                "+"
              )
            )
          ),
          typeChanged = false
        ),
        TestMessages.update(
          contextId,
          context.Main.idFooZ,
          ConstantsGen.INTEGER,
          methodCall = Some(
            Api.MethodCall(
              Api.MethodPointer(
                "Standard.Base.Data.Numbers",
                "Standard.Base.Data.Numbers.Integer",
                "*"
              )
            )
          ),
          typeChanged = false
        ),
        context.executionComplete(contextId)
      )
      val Some(data3) = editFileResponse.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `expectedExpressionId2`
                ),
                data
              )
            ) =>
          data
      }
      new String(data3) shouldEqual "55"

      // pop foo call
      context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
      val popContextResponses = context.receiveNIgnorePendingExpressionUpdates(
        5
      )
      popContextResponses should contain allOf (
        Api.Response(requestId, Api.PopContextResponse(contextId)),
        context.Main.Update.mainY(contextId, typeChanged = false),
        context.Main.Update.mainZ(contextId, typeChanged = false),
        context.executionComplete(contextId)
      )

      val Some(data4) = popContextResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          data
      }
      new String(data4) shouldEqual "60"
  }

  it should "be able to modify visualizations" in withContext() { context =>
    val contents = context.Main.code
    val mainFile = context.writeMain(contents)
    val visualizationFile =
      context.writeInSrcDir("Visualization", context.Visualization.code)

    val contextId       = UUID.randomUUID()
    val requestId       = UUID.randomUUID()
    val visualizationId = UUID.randomUUID()

    // open files
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )

    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )
    context.send(
      Api.Request(
        requestId,
        Api.OpenFileRequest(
          visualizationFile,
          context.Visualization.code
        )
      )
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receiveNIgnorePendingExpressionUpdates(
      5
    ) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )

    // attach visualization
    context.send(
      Api.Request(
        requestId,
        Api.AttachVisualization(
          visualizationId,
          context.Main.idMainX,
          Api.VisualizationConfiguration(
            contextId,
            Api.VisualizationExpression.Text(
              "Enso_Test.Test.Visualization",
              "x -> encode x",
              Vector()
            ),
            "Enso_Test.Test.Visualization"
          )
        )
      )
    )

    val attachVisualizationResponses = context.receiveN(2)
    attachVisualizationResponses should contain(
      Api.Response(requestId, Api.VisualizationAttached())
    )
    val expectedExpressionId = context.Main.idMainX
    val Some(data) = attachVisualizationResponses.collectFirst {
      case Api.Response(
            None,
            Api.VisualizationUpdate(
              Api.VisualizationContext(
                `visualizationId`,
                `contextId`,
                `expectedExpressionId`
              ),
              data
            )
          ) =>
        data
    }
    data.sameElements("6".getBytes) shouldBe true

    // modify visualization
    context.send(
      Api.Request(
        requestId,
        Api.ModifyVisualization(
          visualizationId,
          Api.VisualizationConfiguration(
            contextId,
            Api.VisualizationExpression.Text(
              "Enso_Test.Test.Visualization",
              "x -> incAndEncode x",
              Vector()
            ),
            "Enso_Test.Test.Visualization"
          )
        )
      )
    )
    val modifyVisualizationResponses = context.receiveN(2)
    modifyVisualizationResponses should contain(
      Api.Response(requestId, Api.VisualizationModified())
    )
    val Some(dataAfterModification) =
      modifyVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          data
      }
    dataAfterModification.sameElements("7".getBytes) shouldBe true
  }

  it should "be able to modify visualizations for pending visualizations" in withContext(
    sequentialExecution = false
  ) { context =>
    val contents = context.Main.code
    val mainFile = context.writeMain(contents)
    val visualizationFile =
      context.writeInSrcDir("Visualization", context.Visualization.code)

    val contextId        = UUID.randomUUID()
    val requestId        = UUID.randomUUID()
    val visualizationId  = UUID.randomUUID()
    val visualizationId2 = UUID.randomUUID()

    // open files
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )

    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )
    context.send(
      Api.Request(
        requestId,
        Api.OpenFileRequest(
          visualizationFile,
          context.Visualization.code
        )
      )
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receiveNIgnorePendingExpressionUpdates(
      5
    ) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )

    // attach visualizations
    context.send(
      Api.Request(
        requestId,
        Api.AttachVisualization(
          visualizationId,
          context.Main.idMainX,
          Api.VisualizationConfiguration(
            contextId,
            Api.VisualizationExpression.Text(
              "Enso_Test.Test.Visualization",
              "x -> encode x",
              Vector()
            ),
            "Enso_Test.Test.Visualization"
          )
        )
      )
    )

    context.send(
      Api.Request(
        requestId,
        Api.AttachVisualization(
          visualizationId2,
          context.Main.idMainX,
          Api.VisualizationConfiguration(
            contextId,
            Api.VisualizationExpression.Text(
              "Enso_Test.Test.Visualization",
              "x -> encode x",
              Vector()
            ),
            "Enso_Test.Test.Visualization"
          )
        )
      )
    )

    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(4, 8), model.Position(4, 9)),
              "7"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )

    val responses =
      context.receiveNIgnoreExpressionUpdates(7)

    responses should contain allOf (
      Api.Response(requestId, Api.VisualizationAttached()),
      context.executionComplete(contextId)
    )

    responses.filter(
      _.payload.isInstanceOf[Api.VisualizationAttached]
    ) shouldEqual List(
      Api.Response(requestId, Api.VisualizationAttached()),
      Api.Response(requestId, Api.VisualizationAttached())
    )

    val visualizationUpdatesResponses =
      responses.filter(_.payload.isInstanceOf[Api.VisualizationUpdate])
    val expectedExpressionId = context.Main.idMainX
    val visualizationUpdates = visualizationUpdatesResponses.map(
      _.payload.asInstanceOf[Api.VisualizationUpdate]
    )
    val visContexts = visualizationUpdates.map(_.visualizationContext)
    visContexts should contain allOf (
      Api.VisualizationContext(
        `visualizationId`,
        `contextId`,
        `expectedExpressionId`
      ),
      Api.VisualizationContext(
        `visualizationId2`,
        `contextId`,
        `expectedExpressionId`
      ),
    )

    visualizationUpdates.map(update =>
      new String(update.data)
    ) should contain allOf ("6", "7")

    // modify visualization
    context.send(
      Api.Request(
        requestId,
        Api.ModifyVisualization(
          visualizationId,
          Api.VisualizationConfiguration(
            contextId,
            Api.VisualizationExpression.Text(
              "Enso_Test.Test.Visualization",
              "x -> incAndEncode x",
              Vector()
            ),
            "Enso_Test.Test.Visualization"
          )
        )
      )
    )
    val modifyVisualizationResponses =
      context.receiveNIgnoreExpressionUpdates(4)

    modifyVisualizationResponses should contain allOf (
      Api.Response(requestId, Api.VisualizationModified()),
      context.executionComplete(contextId)
    )
    val visualizationUpdates2 =
      modifyVisualizationResponses.collect {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  modifiedId,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          (data, modifiedId)
      }

    visualizationUpdates2.map(_._2) should contain(visualizationId)

    visualizationUpdates2.map(p => new String(p._1)) should contain("8")
  }

  it should "not emit visualization update when visualization is detached" in withContext() {
    context =>
      val contents = context.Main.code
      val mainFile = context.writeMain(contents)
      val visualizationFile =
        context.writeInSrcDir("Visualization", context.Visualization.code)

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      // open files
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )
      context.send(
        Api.Request(
          requestId,
          Api.OpenFileRequest(
            visualizationFile,
            context.Visualization.code
          )
        )
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            context.Main.idMainX,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Visualization",
                "x -> encode x",
                Vector()
              ),
              "Enso_Test.Test.Visualization"
            )
          )
        )
      )
      context.receiveN(2) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.VisualizationAttached()),
        Api.Response(
          Api.ExecutionFailed(
            contextId,
            Api.ExecutionResult.Failure("Execution stack is empty.", None)
          )
        )
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      val pushResponses = context.receiveNIgnorePendingExpressionUpdates(6)
      pushResponses should contain allOf (
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        context.executionComplete(contextId)
      )
      val expectedExpressionId = context.Main.idMainX
      val Some(data) =
        pushResponses.collectFirst {
          case Api.Response(
                None,
                Api.VisualizationUpdate(
                  Api.VisualizationContext(
                    `visualizationId`,
                    `contextId`,
                    `expectedExpressionId`
                  ),
                  data
                )
              ) =>
            data
        }
      data.sameElements("6".getBytes) shouldBe true

      // detach visualization
      context.send(
        Api.Request(
          requestId,
          Api.DetachVisualization(
            contextId,
            visualizationId,
            context.Main.idMainX
          )
        )
      )
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.VisualizationDetached())
      )

      // recompute
      context.send(
        Api.Request(
          requestId,
          Api.RecomputeContextRequest(contextId, None, None)
        )
      )
      context.receiveNIgnoreExpressionUpdates(
        2
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
        context.executionComplete(contextId)
      )

      // recompute invalidating x
      context.send(
        Api.Request(
          requestId,
          Api.RecomputeContextRequest(
            contextId,
            Some(
              Api.InvalidatedExpressions.Expressions(
                Vector(context.Main.idMainX)
              )
            ),
            None
          )
        )
      )
      context.receiveNIgnoreExpressionUpdates(
        2
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
        context.executionComplete(contextId)
      )
  }

  it should "not emit visualization update when expression is not affected by the change" in withContext() {
    context =>
      val contents   = context.Main.code
      val moduleName = "Enso_Test.Test.Main"
      val mainFile   = context.writeMain(contents)
      val visualizationFile =
        context.writeInSrcDir("Visualization", context.Visualization.code)

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      // open files
      context.send(
        Api.Request(
          requestId,
          Api.OpenFileRequest(
            visualizationFile,
            context.Visualization.code
          )
        )
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )

      context.receiveNIgnorePendingExpressionUpdates(
        5
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            context.Main.idMainX,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Visualization",
                "encode",
                Vector()
              ),
              "Enso_Test.Test.Visualization"
            )
          )
        )
      )
      val attachVisualizationResponses = context.receiveN(2)
      attachVisualizationResponses should contain(
        Api.Response(requestId, Api.VisualizationAttached())
      )
      val expectedExpressionId = context.Main.idMainX
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          data
      }
      data.sameElements("6".getBytes) shouldBe true

      // Modify the file
      context.send(
        Api.Request(
          Api.EditFileNotification(
            mainFile,
            Seq(
              TextEdit(
                model.Range(model.Position(6, 12), model.Position(6, 13)),
                "6"
              )
            ),
            execute = true,
            idMap   = None
          )
        )
      )

      context.receiveNIgnoreExpressionUpdates(
        1
      ) should contain theSameElementsAs Seq(
        context.executionComplete(contextId)
      )
  }

  it should "not reorder visualization commands" in withContext() { context =>
    val contents = context.Main.code
    val mainFile = context.writeMain(contents)
    val visualizationFile =
      context.writeInSrcDir("Visualization", context.Visualization.code)

    val contextId       = UUID.randomUUID()
    val requestId       = UUID.randomUUID()
    val visualizationId = UUID.randomUUID()

    // open files
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )

    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )
    context.send(
      Api.Request(
        requestId,
        Api.OpenFileRequest(
          visualizationFile,
          context.Visualization.code
        )
      )
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receiveNIgnorePendingExpressionUpdates(
      5
    ) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )

    // attach visualization
    context.send(
      Api.Request(
        requestId,
        Api.AttachVisualization(
          visualizationId,
          context.Main.idMainX,
          Api.VisualizationConfiguration(
            contextId,
            Api.VisualizationExpression.Text(
              "Enso_Test.Test.Visualization",
              "x -> encode x",
              Vector()
            ),
            "Enso_Test.Test.Visualization"
          )
        )
      )
    )

    val attachVisualizationResponses = context.receiveN(2)
    attachVisualizationResponses should contain(
      Api.Response(requestId, Api.VisualizationAttached())
    )
    val expectedExpressionId = context.Main.idMainX
    val Some(data) = attachVisualizationResponses.collectFirst {
      case Api.Response(
            None,
            Api.VisualizationUpdate(
              Api.VisualizationContext(
                `visualizationId`,
                `contextId`,
                `expectedExpressionId`
              ),
              data
            )
          ) =>
        data
    }
    data.sameElements("6".getBytes) shouldBe true

    // modify visualization
    context.send(
      Api.Request(
        requestId,
        Api.ModifyVisualization(
          visualizationId,
          Api.VisualizationConfiguration(
            contextId,
            Api.VisualizationExpression.Text(
              "Enso_Test.Test.Visualization",
              "x -> incAndEncode x",
              Vector()
            ),
            "Enso_Test.Test.Visualization"
          )
        )
      )
    )
    // detach visualization
    context.send(
      Api.Request(
        requestId,
        Api.DetachVisualization(
          contextId,
          visualizationId,
          context.Main.idMainX
        )
      )
    )
    val modifyVisualizationResponses = context.receiveN(3)
    modifyVisualizationResponses should contain allOf (
      Api.Response(requestId, Api.VisualizationModified()),
      Api.Response(requestId, Api.VisualizationDetached())
    )
    val Some(dataAfterModification) =
      modifyVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `expectedExpressionId`
                ),
                data
              )
            ) =>
          data
      }
    dataAfterModification.sameElements("7".getBytes) shouldBe true
  }

  it should "return ModuleNotFound error when attaching visualization" in withContext() {
    context =>
      val idMain     = context.Main.metadata.addItem(99, 1)
      val contents   = context.Main.code
      val mainFile   = context.writeMain(context.Main.code)
      val moduleName = "Enso_Test.Test.Main"

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        6
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        TestMessages.update(contextId, idMain, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMain,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Test.Undefined",
                "x -> x",
                Vector()
              ),
              "Test.Undefined"
            )
          )
        )
      )
      context.receiveN(2) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.VisualizationAttached()),
        Api.Response(Api.ModuleNotFound("Test.Undefined"))
      )
  }

  it should "be able to use external libraries if they are needed by the visualization" in withContext() {
    context =>
      val idMain     = context.Main.metadata.addItem(99, 1)
      val contents   = context.Main.code
      val mainFile   = context.writeMain(context.Main.code)
      val moduleName = "Enso_Test.Test.Main"

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        6
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        TestMessages.update(contextId, idMain, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMain,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Standard.Visualization.Main",
                "x -> x.default_visualization.to_text",
                Vector()
              ),
              "Standard.Visualization.Main"
            )
          )
        )
      )

      val attachVisualizationResponses = context.receiveN(7)
      attachVisualizationResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )

      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMain`
                ),
                data
              )
            ) =>
          data
      }

      new String(data) shouldEqual "(Builtin 'Table')"

      val loadedLibraries = attachVisualizationResponses
        .collect {
          case Api.Response(None, Api.LibraryLoaded(namespace, name, _, _)) =>
            Some((namespace, name))
          case _ => None
        }
        .filter(_.isDefined)
        .flatten

      loadedLibraries should contain(("Standard", "Visualization"))
  }

  it should "return VisualizationExpressionFailed error when attaching visualization" in withContext() {
    context =>
      val idMain     = context.Main.metadata.addItem(99, 1)
      val contents   = context.Main.code
      val mainFile   = context.writeMain(context.Main.code)
      val moduleName = "Enso_Test.Test.Main"

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        6
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        TestMessages.update(contextId, idMain, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMain,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Main",
                "Main.does_not_exist",
                Vector()
              ),
              "Enso_Test.Test.Main"
            )
          )
        )
      )
      context.receiveN(2) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.VisualizationAttached()),
        Api.Response(
          Api.VisualizationExpressionFailed(
            Api.VisualizationContext(visualizationId, contextId, idMain),
            "Method `does_not_exist` of type Main could not be found.",
            Some(
              Api.ExecutionResult.Diagnostic.error(
                message =
                  "Method `does_not_exist` of type Main could not be found.",
                stack = Vector(
// empty stack for now
//                Api.StackTraceElement("<eval>", None, None, None),
//                Api.StackTraceElement("Debug.eval", None, None, None)
                )
              )
            )
          )
        )
      )
  }

  it should "return visualization evaluation errors with diagnostic info" in withContext() {
    context =>
      val idMain     = context.Main.metadata.addItem(99, 1)
      val contents   = context.Main.code
      val mainFile   = context.writeMain(context.Main.code)
      val moduleName = "Enso_Test.Test.Main"

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        6
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        TestMessages.update(contextId, idMain, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMain,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                moduleName,
                "x -> x.visualise_me",
                Vector()
              ),
              moduleName
            )
          )
        )
      )
      context.receiveNIgnoreExpressionUpdates(
        3
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.VisualizationAttached()),
        Api.Response(
          Api.VisualizationEvaluationFailed(
            Api.VisualizationContext(
              visualizationId,
              contextId,
              idMain
            ),
            "Method `visualise_me` of type Integer could not be found.",
            Some(
              Api.ExecutionResult.Diagnostic.error(
                "Method `visualise_me` of type Integer could not be found.",
                None,
                Some(model.Range(model.Position(0, 5), model.Position(0, 19))),
                None,
                Vector(
                  Api.StackTraceElement(
                    "<inline_source>.Enso_Test.Test.Main",
                    None,
                    Some(
                      model.Range(model.Position(0, 5), model.Position(0, 19))
                    ),
                    None
                  )
                )
              )
            )
          )
        ),
        context.executionComplete(contextId)
      )
  }

  it should "return visualization error with a stack trace" in withContext() {
    context =>
      val idMain     = context.Main.metadata.addItem(99, 1)
      val contents   = context.Main.code
      val mainFile   = context.writeMain(context.Main.code)
      val moduleName = "Enso_Test.Test.Main"
      val visualizationCode =
        """
          |encode x = x.visualise_me
          |
          |inc_and_encode x = encode x+1
          |""".stripMargin.linesIterator.mkString("\n")

      val visualizationFile =
        context.writeInSrcDir("Visualization", visualizationCode)

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      context.send(
        Api.Request(
          requestId,
          Api.OpenFileRequest(
            visualizationFile,
            visualizationCode
          )
        )
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        6
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        TestMessages.update(contextId, idMain, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMain,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Visualization",
                "inc_and_encode",
                Vector()
              ),
              "Enso_Test.Test.Visualization"
            )
          )
        )
      )
      context.receiveNIgnoreExpressionUpdates(
        3
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.VisualizationAttached()),
        Api.Response(
          Api.VisualizationEvaluationFailed(
            Api.VisualizationContext(
              visualizationId,
              contextId,
              idMain
            ),
            "Method `visualise_me` of type Integer could not be found.",
            Some(
              Api.ExecutionResult.Diagnostic.error(
                "Method `visualise_me` of type Integer could not be found.",
                Some(visualizationFile),
                Some(model.Range(model.Position(1, 11), model.Position(1, 25))),
                None,
                Vector(
                  Api.StackTraceElement(
                    "Visualization.encode",
                    Some(visualizationFile),
                    Some(
                      model.Range(model.Position(1, 11), model.Position(1, 25))
                    ),
                    None
                  ),
                  Api.StackTraceElement(
                    "Visualization.inc_and_encode",
                    Some(visualizationFile),
                    Some(
                      model.Range(model.Position(3, 19), model.Position(3, 29))
                    ),
                    None
                  )
                )
              )
            )
          )
        ),
        context.executionComplete(contextId)
      )
  }

  it should "run visualization expression catching error" in withContext() {
    context =>
      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()
      val moduleName      = "Enso_Test.Test.Main"
      val metadata        = new Metadata

      val idMain = metadata.addItem(42, 14)

      val code =
        """from Standard.Base import all
          |
          |main =
          |    Error.throw 42
          |""".stripMargin.linesIterator.mkString("\n")
      val contents = metadata.appendToCode(code)
      val mainFile = context.writeMain(contents)

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnoreStdLib(3) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        TestMessages.error(
          contextId,
          idMain,
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.Error",
              "Standard.Base.Error.Error",
              "throw"
            )
          ),
          Api.ExpressionUpdate.Payload.DataflowError(Seq(idMain))
        ),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMain,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                moduleName,
                "x -> x.catch_primitive _.to_text",
                Vector()
              ),
              moduleName
            )
          )
        )
      )
      val attachVisualizationResponses =
        context.receiveN(4, timeoutSeconds = 60)
      attachVisualizationResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMain`
                ),
                data
              )
            ) =>
          data
      }
      data.sameElements("42".getBytes) shouldBe true
  }

  it should "run visualization expression propagating panic" in withContext() {
    context =>
      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()
      val moduleName      = "Enso_Test.Test.Main"
      val metadata        = new Metadata

      val idMain = metadata.addItem(42, 14)

      val code =
        """from Standard.Base import all
          |
          |main =
          |    Panic.throw 42
          |""".stripMargin.linesIterator.mkString("\n")
      val contents = metadata.appendToCode(code)
      val mainFile = context.writeMain(contents)

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        3
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        TestMessages.panic(
          contextId,
          idMain,
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.Panic",
              "Standard.Base.Panic.Panic",
              "throw"
            )
          ),
          Api.ExpressionUpdate.Payload.Panic("Integer", Seq(idMain)),
          Some("Standard.Base.Panic.Panic")
        ),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMain,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                moduleName,
                "x -> Panic.catch_primitive x caught_panic-> caught_panic.payload.to_text",
                Vector()
              ),
              moduleName
            )
          )
        )
      )
      context.receiveNIgnorePendingExpressionUpdates(
        4
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.VisualizationAttached()),
        TestMessages.panic(
          contextId,
          idMain,
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.Panic",
              "Standard.Base.Panic.Panic",
              "throw"
            )
          ),
          Api.ExpressionUpdate.Payload.Panic("Integer", Seq(idMain)),
          builtin = false
        ),
        Api.Response(
          Api.VisualizationEvaluationFailed(
            Api.VisualizationContext(
              visualizationId,
              contextId,
              idMain
            ),
            "42",
            Some(
              Api.ExecutionResult.Diagnostic.error(
                message = "42",
                file    = Some(mainFile),
                location = Some(
                  model.Range(model.Position(3, 4), model.Position(3, 18))
                ),
                expressionId = Some(idMain),
                stack = Vector(
                  Api.StackTraceElement(
                    "Main.main",
                    Some(mainFile),
                    Some(
                      model.Range(model.Position(3, 4), model.Position(3, 18))
                    ),
                    Some(idMain)
                  )
                )
              )
            )
          )
        ),
        context.executionComplete(contextId)
      )
  }

  it should "run visualization error preprocessor" in withContext() { context =>
    val contextId       = UUID.randomUUID()
    val requestId       = UUID.randomUUID()
    val visualizationId = UUID.randomUUID()
    val moduleName      = "Enso_Test.Test.Main"
    val metadata        = new Metadata

    val idMain = metadata.addItem(158, 36)

    val code =
      """import Standard.Base.Data.List
        |import Standard.Visualization
        |import Standard.Base.Error.Error
        |import Standard.Base.Errors.Empty_Error.Empty_Error
        |
        |main =
        |    Error.throw (Empty_Error.Error List)
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // NOTE: below values need to be kept in sync with what is used internally by Rust IDE code
    val visualizationModule   = "Standard.Visualization.Preprocessor"
    val visualizationFunction = "error_preprocessor"

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Open the new file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receiveNIgnoreStdLib(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.error(
        contextId,
        idMain,
        Api.MethodCall(
          Api.MethodPointer(
            "Standard.Base.Error",
            "Standard.Base.Error.Error",
            "throw"
          )
        ),
        Api.ExpressionUpdate.Payload.DataflowError(Seq(idMain))
      ),
      context.executionComplete(contextId)
    )

    // attach visualization
    context.send(
      Api.Request(
        requestId,
        Api.AttachVisualization(
          visualizationId,
          idMain,
          Api.VisualizationConfiguration(
            contextId,
            Api.VisualizationExpression.ModuleMethod(
              Api.MethodPointer(
                visualizationModule,
                visualizationModule,
                visualizationFunction
              ),
              Vector()
            ),
            visualizationModule
          )
        )
      )
    )
    val attachVisualizationResponses =
      context.receiveNIgnoreExpressionUpdates(3)
    attachVisualizationResponses should contain allOf (
      Api.Response(requestId, Api.VisualizationAttached()),
      context.executionComplete(contextId)
    )
    val Some(data) = attachVisualizationResponses.collectFirst {
      case Api.Response(
            None,
            Api.VisualizationUpdate(
              Api.VisualizationContext(
                `visualizationId`,
                `contextId`,
                `idMain`
              ),
              data
            )
          ) =>
        data
    }
    val stringified = new String(data)
    stringified shouldEqual """{"kind":"Dataflow","message":"The List is empty. (at <enso> Main.main(Enso_Test.Test.Main:7:5-40))"}"""
  }

  it should "run visualization default preprocessor" in withContext() {
    context =>
      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()
      val moduleName      = "Enso_Test.Test.Main"
      val metadata        = new Metadata

      val idMain = metadata.addItem(47, 6)

      val code =
        """import Standard.Visualization
          |
          |main =
          |    fn = x -> x
          |    fn
          |""".stripMargin.linesIterator.mkString("\n")
      val contents = metadata.appendToCode(code)
      val mainFile = context.writeMain(contents)

      val visualizationModule   = "Standard.Visualization.Preprocessor"
      val visualizationFunction = "default_preprocessor"

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, moduleName, "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        3
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        TestMessages.update(
          contextId,
          idMain,
          ConstantsGen.FUNCTION
        ),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMain,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.ModuleMethod(
                Api.MethodPointer(
                  visualizationModule,
                  visualizationModule,
                  visualizationFunction
                ),
                Vector()
              ),
              visualizationModule
            )
          )
        )
      )
      val attachVisualizationResponses =
        context.receiveNIgnoreExpressionUpdates(2)
      attachVisualizationResponses should contain(
        Api.Response(requestId, Api.VisualizationAttached())
      )
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMain`
                ),
                data
              )
            ) =>
          data
      }
      val stringified = new String(data)
      stringified shouldEqual "\"Function\""
  }

  it should "attach text visualization with arguments" in withContext() {
    context =>
      val idMainRes  = context.Main.metadata.addItem(99, 1)
      val contents   = context.Main.code
      val mainFile   = context.writeMain(context.Main.code)
      val moduleName = "Enso_Test.Test.Main"

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        6
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        TestMessages.update(contextId, idMainRes, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )
      context.consumeOut shouldEqual List()

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMainRes,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Main",
                "x -> suffix -> x.to_text + suffix",
                Vector("'_bar'")
              ),
              "Enso_Test.Test.Main"
            )
          )
        )
      )
      val attachVisualizationResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      attachVisualizationResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMainRes`
                ),
                data
              )
            ) =>
          data
      }
      new String(data) shouldEqual "50_bar"

      // recompute
      context.send(
        Api.Request(
          requestId,
          Api.RecomputeContextRequest(contextId, None, None)
        )
      )

      val recomputeResponses = context.receiveNIgnoreExpressionUpdates(3)
      recomputeResponses should contain allOf (
        Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
        context.executionComplete(contextId)
      )
      val Some(data2) = recomputeResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMainRes`
                ),
                data
              )
            ) =>
          data
      }
      new String(data2) shouldEqual "50_bar"
      context.consumeOut shouldEqual List()

      // modify visualization
      context.send(
        Api.Request(
          requestId,
          Api.ModifyVisualization(
            visualizationId,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Main",
                "x -> suffix -> x.to_text + suffix",
                Vector("'_quux'")
              ),
              "Enso_Test.Test.Main"
            )
          )
        )
      )
      val modifyVisualizationResponses =
        context.receiveNIgnoreExpressionUpdates(2)
      modifyVisualizationResponses should contain(
        Api.Response(requestId, Api.VisualizationModified())
      )
      val Some(data3) =
        modifyVisualizationResponses.collectFirst {
          case Api.Response(
                None,
                Api.VisualizationUpdate(
                  Api.VisualizationContext(
                    `visualizationId`,
                    `contextId`,
                    `idMainRes`
                  ),
                  data
                )
              ) =>
            data
        }
      new String(data3) shouldEqual "50_quux"
  }

  it should "attach method pointer visualization without arguments" in withContext() {
    context =>
      val idMainRes = context.Main.metadata.addItem(99, 1)
      val contents  = context.Main.code
      val mainFile  = context.writeMain(context.Main.code)
      val visualizationFile =
        context.writeInSrcDir("Visualization", context.Visualization.code)

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      context.send(
        Api.Request(
          requestId,
          Api.OpenFileRequest(
            visualizationFile,
            context.Visualization.code
          )
        )
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        6
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        TestMessages.update(contextId, idMainRes, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMainRes,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.ModuleMethod(
                Api.MethodPointer(
                  "Enso_Test.Test.Visualization",
                  "Enso_Test.Test.Visualization",
                  "incAndEncode"
                ),
                Vector()
              ),
              "Enso_Test.Test.Visualization"
            )
          )
        )
      )
      val attachVisualizationResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      attachVisualizationResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMainRes`
                ),
                data
              )
            ) =>
          data
      }
      data.sameElements("51".getBytes) shouldBe true

      // recompute
      context.send(
        Api.Request(
          requestId,
          Api.RecomputeContextRequest(contextId, None, None)
        )
      )

      val recomputeResponses = context.receiveNIgnoreExpressionUpdates(3)
      recomputeResponses should contain allOf (
        Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
        context.executionComplete(contextId)
      )
      val Some(data2) = recomputeResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMainRes`
                ),
                data
              )
            ) =>
          data
      }
      data2.sameElements("51".getBytes) shouldBe true
  }

  it should "attach method pointer visualization with arguments" in withContext() {
    context =>
      val idMainRes  = context.Main.metadata.addItem(99, 1)
      val contents   = context.Main.code
      val mainFile   = context.writeMain(context.Main.code)
      val moduleName = "Enso_Test.Test.Main"
      val visualizationFile =
        context.writeInSrcDir(
          "Visualization",
          context.AnnotatedVisualization.code
        )

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      context.send(
        Api.Request(
          requestId,
          Api.OpenFileRequest(
            visualizationFile,
            context.AnnotatedVisualization.code
          )
        )
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        6
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        TestMessages.update(contextId, idMainRes, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )
      context.consumeOut shouldEqual List()

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMainRes,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.ModuleMethod(
                Api.MethodPointer(
                  "Enso_Test.Test.Visualization",
                  "Enso_Test.Test.Visualization",
                  "incAndEncode"
                ),
                Vector("2", "3")
              ),
              "Enso_Test.Test.Visualization"
            )
          )
        )
      )
      val attachVisualizationResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      attachVisualizationResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMainRes`
                ),
                data
              )
            ) =>
          data
      }
      data.sameElements("103".getBytes) shouldBe true
      context.consumeOut shouldEqual List("encoding...")

      // recompute
      context.send(
        Api.Request(
          requestId,
          Api.RecomputeContextRequest(contextId, None, None)
        )
      )

      val recomputeResponses = context.receiveNIgnoreExpressionUpdates(3)
      recomputeResponses should contain allOf (
        Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
        context.executionComplete(contextId)
      )
      val Some(data2) = recomputeResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMainRes`
                ),
                data
              )
            ) =>
          data
      }
      data2.sameElements("103".getBytes) shouldBe true
      context.consumeOut shouldEqual List()

      // modify visualization
      context.send(
        Api.Request(
          requestId,
          Api.ModifyVisualization(
            visualizationId,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.ModuleMethod(
                Api.MethodPointer(
                  "Enso_Test.Test.Visualization",
                  "Enso_Test.Test.Visualization",
                  "incAndEncode"
                ),
                Vector("2", "4")
              ),
              "Enso_Test.Test.Visualization"
            )
          )
        )
      )
      val modifyVisualizationResponses =
        context.receiveNIgnoreExpressionUpdates(2)
      modifyVisualizationResponses should contain(
        Api.Response(requestId, Api.VisualizationModified())
      )
      val Some(data3) =
        modifyVisualizationResponses.collectFirst {
          case Api.Response(
                None,
                Api.VisualizationUpdate(
                  Api.VisualizationContext(
                    `visualizationId`,
                    `contextId`,
                    `idMainRes`
                  ),
                  data
                )
              ) =>
            data
        }
      data3.sameElements("104".getBytes) shouldBe true
      context.consumeOut shouldEqual List("encoding...")
  }

  it should "cache intermediate visualization expressions" in withContext() {
    context =>
      val idMainRes  = context.Main.metadata.addItem(99, 1)
      val contents   = context.Main.code
      val mainFile   = context.writeMain(context.Main.code)
      val moduleName = "Enso_Test.Test.Main"
      val visualizationFile =
        context.writeInSrcDir(
          "Visualization",
          context.AnnotatedVisualization.code
        )

      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()

      context.send(
        Api.Request(
          requestId,
          Api.OpenFileRequest(
            visualizationFile,
            context.AnnotatedVisualization.code
          )
        )
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        6
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        context.Main.Update.mainX(contextId),
        context.Main.Update.mainY(contextId),
        context.Main.Update.mainZ(contextId),
        TestMessages.update(contextId, idMainRes, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )
      context.consumeOut shouldEqual List()

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMainRes,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.ModuleMethod(
                Api.MethodPointer(
                  "Enso_Test.Test.Visualization",
                  "Enso_Test.Test.Visualization",
                  "incAndEncode"
                ),
                Vector()
              ),
              "Enso_Test.Test.Visualization"
            )
          )
        )
      )
      val attachVisualizationResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      attachVisualizationResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMainRes`
                ),
                data
              )
            ) =>
          data
      }
      data.sameElements("51".getBytes) shouldBe true
      context.consumeOut shouldEqual List("encoding...")

      // recompute
      context.send(
        Api.Request(
          requestId,
          Api.RecomputeContextRequest(contextId, None, None)
        )
      )

      val recomputeResponses = context.receiveNIgnoreExpressionUpdates(3)
      recomputeResponses should contain allOf (
        Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
        context.executionComplete(contextId)
      )
      val Some(data2) = recomputeResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMainRes`
                ),
                data
              )
            ) =>
          data
      }
      data2.sameElements("51".getBytes) shouldBe true
      context.consumeOut shouldEqual List()

      // Modify the visualization file
      context.send(
        Api.Request(
          Api.EditFileNotification(
            visualizationFile,
            Seq(
              TextEdit(
                model.Range(model.Position(6, 21), model.Position(6, 22)),
                "2"
              )
            ),
            execute = true,
            idMap   = None
          )
        )
      )

      val editFileResponse = context.receiveNIgnoreExpressionUpdates(2)
      editFileResponse should contain(
        context.executionComplete(contextId)
      )
      val Some(data3) = editFileResponse.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMainRes`
                ),
                data
              )
            ) =>
          data
      }
      data3.sameElements("52".getBytes) shouldBe true
      context.consumeOut shouldEqual List("encoding...")
  }

  it should "emit visualization update for values annotated with warnings" in withContext() {
    context =>
      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()
      val moduleName      = "Enso_Test.Test.Main"
      val metadata        = new Metadata

      val idMain = metadata.addItem(37, 26)

      val code =
        """from Standard.Base import all
          |
          |main =
          |    Warning.attach "y" 42
          |""".stripMargin.linesIterator.mkString("\n")
      val contents = metadata.appendToCode(code)
      val mainFile = context.writeMain(contents)

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        3
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        TestMessages.update(
          contextId,
          idMain,
          ConstantsGen.INTEGER,
          payload = Api.ExpressionUpdate.Payload.Value(
            Some(
              Api.ExpressionUpdate.Payload.Value.Warnings(1, Some("y"), false)
            )
          )
        ),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMain,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Main",
                "x -> x.to_text",
                Vector()
              ),
              "Enso_Test.Test.Main"
            )
          )
        )
      )
      val attachVisualizationResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      attachVisualizationResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMain`
                ),
                data
              )
            ) =>
          data
      }
      new String(data, StandardCharsets.UTF_8) shouldEqual "42"
  }

  it should "emit visualization update for values in array annotated with warnings" in withContext() {
    context =>
      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()
      val moduleName      = "Enso_Test.Test.Main"
      val metadata        = new Metadata

      val idMain = metadata.addItem(37, 28)

      val code =
        """from Standard.Base import all
          |
          |main =
          |    [Warning.attach "y" 42]
          |""".stripMargin.linesIterator.mkString("\n")

      metadata.assertInCode(idMain, code, "\n    [Warning.attach \"y\" 42]")

      val contents = metadata.appendToCode(code)
      val mainFile = context.writeMain(contents)

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        3
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        TestMessages.update(
          contextId,
          idMain,
          ConstantsGen.VECTOR,
          payload = Api.ExpressionUpdate.Payload.Value(
            Some(
              Api.ExpressionUpdate.Payload.Value.Warnings(1, Some("y"), false)
            )
          )
        ),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idMain,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Main",
                "x -> x.to_text",
                Vector()
              ),
              "Enso_Test.Test.Main"
            )
          )
        )
      )
      val attachVisualizationResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      attachVisualizationResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMain`
                ),
                data
              )
            ) =>
          data
      }
      new String(data, StandardCharsets.UTF_8) shouldEqual "[42]"
  }

  it should "emit visualization update for values in atom annotated with warnings" in withContext() {
    context =>
      val contextId         = UUID.randomUUID()
      val requestId         = UUID.randomUUID()
      val visualizationId   = UUID.randomUUID()
      val moduleName        = "Enso_Test.Test.Main"
      val warningTypeName   = QualifiedName.fromString(ConstantsGen.WARNING)
      val warningModuleName = warningTypeName.getParent.get
      val metadata          = new Metadata

      val idX   = metadata.addItem(81, 21)
      val idRes = metadata.addItem(107, 20)

      val code =
        """from Standard.Base import all
          |
          |type Newtype
          |    Mk_Newtype value
          |
          |main =
          |    x = Warning.attach "x" 42
          |    Newtype.Mk_Newtype x
          |""".stripMargin.linesIterator.mkString("\n")
      val contents = metadata.appendToCode(code)
      val mainFile = context.writeMain(contents)

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        4
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        TestMessages.update(
          contextId,
          idX,
          ConstantsGen.INTEGER,
          methodCall = Some(
            Api.MethodCall(
              Api.MethodPointer(
                warningModuleName.toString,
                warningTypeName.toString,
                "attach"
              )
            )
          ),
          payload = Api.ExpressionUpdate.Payload.Value(
            Some(
              Api.ExpressionUpdate.Payload.Value.Warnings(1, Some("x"), false)
            )
          )
        ),
        TestMessages.update(
          contextId,
          idRes,
          s"$moduleName.Newtype",
          methodCall = Some(
            Api.MethodCall(
              Api
                .MethodPointer(moduleName, s"$moduleName.Newtype", "Mk_Newtype")
            )
          ),
          payload = Api.ExpressionUpdate.Payload.Value(
            Some(
              Api.ExpressionUpdate.Payload.Value.Warnings(1, Some("x"), false)
            )
          )
        ),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idRes,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                "Enso_Test.Test.Main",
                "x -> x.to_text",
                Vector()
              ),
              "Enso_Test.Test.Main"
            )
          )
        )
      )
      val attachVisualizationResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      attachVisualizationResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idRes`
                ),
                data
              )
            ) =>
          data
      }
      new String(data, StandardCharsets.UTF_8) shouldEqual "(Mk_Newtype 42)"
  }

  it should "visualize normal atomconstructor" in withContext() { context =>
    val contextId       = UUID.randomUUID()
    val requestId       = UUID.randomUUID()
    val visualizationId = UUID.randomUUID()
    val moduleName      = "Enso_Test.Test.Main"
    val metadata        = new Metadata

    val idX    = metadata.addItem(103, 35, "aaaa")
    val idCons = metadata.addItem(116, 21, "cccc")
    val idRes  = metadata.addItem(143, 1, "eeee")

    val code =
      """from Standard.Base import all
        |
        |type Newtype
        |    Mk_Newtype value
        |
        |    fix t:Newtype = t
        |main =
        |    x = Newtype.fix (Newtype.Mk_Newtype 42)
        |    x
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    metadata.assertInCode(idX, code, "Newtype.fix (Newtype.Mk_Newtype 42)")
    metadata.assertInCode(idCons, code, "Newtype.Mk_Newtype 42")
    metadata.assertInCode(idRes, code, "x")

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Open the new file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receiveNIgnorePendingExpressionUpdates(
      5
    ) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idCons,
        s"$moduleName.Newtype",
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              s"$moduleName",
              s"$moduleName.Newtype",
              "Mk_Newtype"
            )
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(None)
      ),
      TestMessages.update(
        contextId,
        idX,
        s"$moduleName.Newtype",
        methodCall = Some(
          Api.MethodCall(
            Api
              .MethodPointer(moduleName, s"$moduleName.Newtype", "fix")
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(None)
      ),
      TestMessages.update(
        contextId,
        idRes,
        s"$moduleName.Newtype",
        methodCall = None,
        payload    = Api.ExpressionUpdate.Payload.Value(None)
      ),
      context.executionComplete(contextId)
    )

    // attach visualization
    context.send(
      Api.Request(
        requestId,
        Api.AttachVisualization(
          visualizationId,
          idRes,
          Api.VisualizationConfiguration(
            contextId,
            Api.VisualizationExpression.Text(
              "Enso_Test.Test.Main",
              "x -> x.to_text",
              Vector()
            ),
            "Enso_Test.Test.Main"
          )
        )
      )
    )
    val attachVisualizationResponses =
      context.receiveNIgnoreExpressionUpdates(3)
    attachVisualizationResponses should contain allOf (
      Api.Response(requestId, Api.VisualizationAttached()),
      context.executionComplete(contextId)
    )
    val Some(data) = attachVisualizationResponses.collectFirst {
      case Api.Response(
            None,
            Api.VisualizationUpdate(
              Api.VisualizationContext(
                `visualizationId`,
                `contextId`,
                `idRes`
              ),
              data
            )
          ) =>
        data
    }
    new String(data, StandardCharsets.UTF_8) shouldEqual "(Mk_Newtype 42)"
  }

  it should "visualize autoscoped atomconstructor" in withContext() { context =>
    val contextId       = UUID.randomUUID()
    val requestId       = UUID.randomUUID()
    val visualizationId = UUID.randomUUID()
    val moduleName      = "Enso_Test.Test.Main"
    val metadata        = new Metadata

    val idX    = metadata.addItem(103, 29, "aaaa")
    val idCons = metadata.addItem(116, 15, "cccc")
    val idRes  = metadata.addItem(137, 1, "eeee")

    val code =
      """from Standard.Base import all
        |
        |type Newtype
        |    Mk_Newtype value
        |
        |    fix t:Newtype = t
        |main =
        |    x = Newtype.fix (..Mk_Newtype 42)
        |    x
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    metadata.assertInCode(idX, code, "Newtype.fix (..Mk_Newtype 42)")
    metadata.assertInCode(idCons, code, "..Mk_Newtype 42")
    metadata.assertInCode(idRes, code, "x")

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Open the new file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receiveNIgnorePendingExpressionUpdates(
      5
    ) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idCons,
        s"$moduleName.Newtype",
        methodCall = Some(
          Api.MethodCall(
            Api
              .MethodPointer(moduleName, s"$moduleName.Newtype", "Mk_Newtype")
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(None)
      ),
      TestMessages.update(
        contextId,
        idX,
        s"$moduleName.Newtype",
        methodCall = Some(
          Api.MethodCall(
            Api
              .MethodPointer(moduleName, s"$moduleName.Newtype", "fix")
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(None)
      ),
      TestMessages.update(
        contextId,
        idRes,
        s"$moduleName.Newtype",
        methodCall = None,
        payload    = Api.ExpressionUpdate.Payload.Value(None)
      ),
      context.executionComplete(contextId)
    )

    // attach visualization
    context.send(
      Api.Request(
        requestId,
        Api.AttachVisualization(
          visualizationId,
          idRes,
          Api.VisualizationConfiguration(
            contextId,
            Api.VisualizationExpression.Text(
              "Enso_Test.Test.Main",
              "x -> x.to_text",
              Vector()
            ),
            "Enso_Test.Test.Main"
          )
        )
      )
    )
    val attachVisualizationResponses =
      context.receiveNIgnoreExpressionUpdates(3)
    attachVisualizationResponses should contain allOf (
      Api.Response(requestId, Api.VisualizationAttached()),
      context.executionComplete(contextId)
    )
    val Some(data) = attachVisualizationResponses.collectFirst {
      case Api.Response(
            None,
            Api.VisualizationUpdate(
              Api.VisualizationContext(
                `visualizationId`,
                `contextId`,
                `idRes`
              ),
              data
            )
          ) =>
        data
    }
    new String(data, StandardCharsets.UTF_8) shouldEqual "(Mk_Newtype 42)"
  }

  it should "emit visualization update for the target of a method call" in withContext() {
    context =>
      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()
      val moduleName      = "Enso_Test.Test.Main"
      val metadata        = new Metadata("import Standard.Base.Data.Numbers\n\n")

      val idX      = metadata.addItem(65, 1, "aa")
      val idY      = metadata.addItem(65, 7, "ab")
      val idS      = metadata.addItem(81, 1)
      val idZ      = metadata.addItem(91, 5, "ac")
      val idZexprS = metadata.addItem(93, 1)
      val idZexpr1 = metadata.addItem(95, 1)

      val code =
        """type T
          |    C
          |
          |    inc self x = x + 1
          |
          |main =
          |    x = T.C
          |    y = x.inc 7
          |    s = 1
          |    z = p y s
          |    z
          |
          |p x y = x + y
          |""".stripMargin.linesIterator.mkString("\n")
      val contents = metadata.appendToCode(code)
      val mainFile = context.writeMain(contents)

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        8
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        TestMessages.update(contextId, idX, s"$moduleName.T"),
        TestMessages.update(
          contextId,
          idY,
          ConstantsGen.INTEGER,
          Api.MethodCall(Api.MethodPointer(moduleName, s"$moduleName.T", "inc"))
        ),
        TestMessages.update(contextId, idS, ConstantsGen.INTEGER),
        TestMessages.update(
          contextId,
          idZ,
          ConstantsGen.INTEGER,
          Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "p"))
        ),
        TestMessages.update(contextId, idZexprS, ConstantsGen.INTEGER),
        TestMessages.update(contextId, idZexpr1, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )

      // attach visualization
      context.send(
        Api.Request(
          requestId,
          Api.AttachVisualization(
            visualizationId,
            idX,
            Api.VisualizationConfiguration(
              contextId,
              Api.VisualizationExpression.Text(
                moduleName,
                "x -> x.to_text",
                Vector()
              ),
              moduleName
            )
          )
        )
      )
      val attachVisualizationResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      attachVisualizationResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = attachVisualizationResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idX`
                ),
                data
              )
            ) =>
          data
      }
      new String(data, StandardCharsets.UTF_8) shouldEqual "C"

      // Modify the file
      context.send(
        Api.Request(
          Api.EditFileNotification(
            mainFile,
            Seq(
              TextEdit(
                model.Range(model.Position(9, 8), model.Position(9, 9)),
                "x"
              )
            ),
            execute = true,
            idMap   = None
          )
        )
      )

      val editFileResponse = context.receiveNIgnoreExpressionUpdates(2)
      editFileResponse should contain(
        context.executionComplete(contextId)
      )
      val Some(data1) = editFileResponse.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idX`
                ),
                data
              )
            ) =>
          data
      }
      new String(data1, StandardCharsets.UTF_8) shouldEqual "C"
  }

  it should "execute expression in the scope of local expression cached" in withContext() {
    context =>
      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()
      val moduleName      = "Enso_Test.Test.Main"
      val metadata        = new Metadata("import Standard.Base.Data.Numbers\n\n")

      val idOp1 = metadata.addItem(23, 2)
      val idOp2 = metadata.addItem(42, 13)

      val code =
        """main =
          |    operator1 = 42
          |    operator2 = operator1 + 1
          |    operator2
          |
          |fun1 x = x.to_text
          |""".stripMargin.linesIterator.mkString("\n")
      val contents = metadata.appendToCode(code)
      val mainFile = context.writeMain(contents)

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, moduleName, "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        4
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        TestMessages.update(contextId, idOp1, ConstantsGen.INTEGER),
        TestMessages.update(
          contextId,
          idOp2,
          ConstantsGen.INTEGER,
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.Data.Numbers",
              ConstantsGen.INTEGER,
              "+"
            )
          )
        ),
        context.executionComplete(contextId)
      )

      // execute expression
      context.send(
        Api.Request(
          requestId,
          Api.ExecuteExpression(
            contextId,
            visualizationId,
            idOp2,
            "fun1 operator1"
          )
        )
      )
      val executeExpressionResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      executeExpressionResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = executeExpressionResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idOp2`
                ),
                data
              )
            ) =>
          data
      }
      new String(data) shouldEqual "42"
  }

  it should "execute expression in the scope of local expression not cached" in withContext() {
    context =>
      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()
      val moduleName      = "Enso_Test.Test.Main"
      val metadata        = new Metadata("import Standard.Base.Data.Numbers\n\n")

      val idOp1 = metadata.addItem(23, 2)
      val idOp2 = metadata.addItem(42, 13)
      val idRes = metadata.addItem(60, 9)

      val code =
        """main =
          |    operator1 = 42
          |    operator2 = operator1 + 1
          |    operator2
          |
          |fun1 x = x.to_text
          |""".stripMargin.linesIterator.mkString("\n")
      val contents = metadata.appendToCode(code)
      val mainFile = context.writeMain(contents)

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, moduleName, "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        5
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        TestMessages.update(contextId, idOp1, ConstantsGen.INTEGER),
        TestMessages.update(
          contextId,
          idOp2,
          ConstantsGen.INTEGER,
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.Data.Numbers",
              ConstantsGen.INTEGER,
              "+"
            )
          )
        ),
        TestMessages.update(contextId, idRes, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )

      // execute expression
      context.send(
        Api.Request(
          requestId,
          Api.ExecuteExpression(
            contextId,
            visualizationId,
            idRes,
            "fun1 operator1"
          )
        )
      )
      val executeExpressionResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      executeExpressionResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = executeExpressionResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idRes`
                ),
                data
              )
            ) =>
          data
      }
      new String(data) shouldEqual "42"
  }

  it should "execute expression in the scope of local binding" in withContext() {
    context =>
      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()
      val moduleName      = "Enso_Test.Test.Main"
      val metadata        = new Metadata("import Standard.Base.Data.Numbers\n\n")

      val idOp1        = metadata.addItem(23, 2)
      val idOp2        = metadata.addItem(42, 13)
      val idOp2Binding = metadata.addItem(30, 25)
      val idRes        = metadata.addItem(60, 9)

      val code =
        """main =
          |    operator1 = 42
          |    operator2 = operator1 + 1
          |    operator2
          |
          |fun1 x = x.to_text
          |""".stripMargin.linesIterator.mkString("\n")
      val contents = metadata.appendToCode(code)
      val mainFile = context.writeMain(contents)

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, moduleName, "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        6
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        TestMessages.update(contextId, idOp1, ConstantsGen.INTEGER),
        TestMessages.update(
          contextId,
          idOp2,
          ConstantsGen.INTEGER,
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.Data.Numbers",
              ConstantsGen.INTEGER,
              "+"
            )
          )
        ),
        TestMessages
          .update(contextId, idOp2Binding, ConstantsGen.NOTHING),
        TestMessages.update(contextId, idRes, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )

      // execute expression
      context.send(
        Api.Request(
          requestId,
          Api.ExecuteExpression(
            contextId,
            visualizationId,
            idOp2Binding,
            "fun1 operator1+operator2"
          )
        )
      )
      val executeExpressionResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      executeExpressionResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = executeExpressionResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idOp2Binding`
                ),
                data
              )
            ) =>
          data
      }
      new String(data) shouldEqual "85"
  }

  it should "execute expression in the scope of main method" in withContext() {
    context =>
      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()
      val moduleName      = "Enso_Test.Test.Main"
      val metadata        = new Metadata("import Standard.Base.Data.Numbers\n\n")

      val idOp1  = metadata.addItem(23, 2)
      val idOp2  = metadata.addItem(42, 13)
      val idMain = metadata.addItem(6, 63)

      val code =
        """main =
          |    operator1 = 42
          |    operator2 = operator1 + 1
          |    operator2
          |
          |fun1 x = x.to_text
          |""".stripMargin.linesIterator.mkString("\n")
      val contents = metadata.appendToCode(code)
      val mainFile = context.writeMain(contents)

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, moduleName, "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        5
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        TestMessages.update(contextId, idOp1, ConstantsGen.INTEGER),
        TestMessages.update(
          contextId,
          idOp2,
          ConstantsGen.INTEGER,
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.Data.Numbers",
              ConstantsGen.INTEGER,
              "+"
            )
          )
        ),
        TestMessages.update(contextId, idMain, ConstantsGen.INTEGER),
        context.executionComplete(contextId)
      )

      // execute expression
      context.send(
        Api.Request(
          requestId,
          Api.ExecuteExpression(
            contextId,
            visualizationId,
            idMain,
            "fun1 operator1+operator2"
          )
        )
      )
      val executeExpressionResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      executeExpressionResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = executeExpressionResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMain`
                ),
                data
              )
            ) =>
          data
      }
      new String(data) shouldEqual "85"
  }

  it should "execute default visualization preprocessor" in withContext() {
    context =>
      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()
      val moduleName      = "Enso_Test.Test.Main"
      val metadata        = new Metadata

      val idMain = metadata.addItem(60, 6)

      val code =
        """import Standard.Visualization.Preprocessor
          |
          |main =
          |    fn = x -> x
          |    fn
          |""".stripMargin.linesIterator.mkString("\n")
      val contents = metadata.appendToCode(code)
      val mainFile = context.writeMain(contents)

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, moduleName, "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        3
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        TestMessages.update(
          contextId,
          idMain,
          ConstantsGen.FUNCTION
        ),
        context.executionComplete(contextId)
      )

      // execute expression
      context.send(
        Api.Request(
          requestId,
          Api.ExecuteExpression(
            contextId,
            visualizationId,
            idMain,
            "Preprocessor.default_preprocessor 85"
          )
        )
      )
      val executeExpressionResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      executeExpressionResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = executeExpressionResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMain`
                ),
                data
              )
            ) =>
          data
      }
      new String(data) shouldEqual "85"
  }

  it should "execute default visualization preprocessor with a FQN" in withContext() {
    context =>
      val contextId       = UUID.randomUUID()
      val requestId       = UUID.randomUUID()
      val visualizationId = UUID.randomUUID()
      val moduleName      = "Enso_Test.Test.Main"
      val metadata        = new Metadata

      val idMain = metadata.addItem(90, 6)

      val code =
        """import Standard.Visualization
          |import Standard.Visualization.Preprocessor
          |
          |main =
          |    fn = x -> x
          |    fn
          |""".stripMargin.linesIterator.mkString("\n")
      val contents = metadata.appendToCode(code)
      val mainFile = context.writeMain(contents)

      // create context
      context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
      context.receive shouldEqual Some(
        Api.Response(requestId, Api.CreateContextResponse(contextId))
      )

      // Open the new file
      context.send(
        Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
      )
      context.receive shouldEqual Some(
        Api.Response(Some(requestId), Api.OpenFileResponse)
      )

      // push main
      val item1 = Api.StackItem.ExplicitCall(
        Api.MethodPointer(moduleName, moduleName, "main"),
        None,
        Vector()
      )
      context.send(
        Api.Request(requestId, Api.PushContextRequest(contextId, item1))
      )
      context.receiveNIgnorePendingExpressionUpdates(
        3
      ) should contain theSameElementsAs Seq(
        Api.Response(requestId, Api.PushContextResponse(contextId)),
        TestMessages.update(
          contextId,
          idMain,
          ConstantsGen.FUNCTION
        ),
        context.executionComplete(contextId)
      )

      // execute expression
      context.send(
        Api.Request(
          requestId,
          Api.ExecuteExpression(
            contextId,
            visualizationId,
            idMain,
            "Standard.Visualization.Preprocessor.default_preprocessor 85"
          )
        )
      )
      val executeExpressionResponses =
        context.receiveNIgnoreExpressionUpdates(3)
      executeExpressionResponses should contain allOf (
        Api.Response(requestId, Api.VisualizationAttached()),
        context.executionComplete(contextId)
      )
      val Some(data) = executeExpressionResponses.collectFirst {
        case Api.Response(
              None,
              Api.VisualizationUpdate(
                Api.VisualizationContext(
                  `visualizationId`,
                  `contextId`,
                  `idMain`
                ),
                data
              )
            ) =>
          data
      }
      new String(data) shouldEqual "85"
  }

}
