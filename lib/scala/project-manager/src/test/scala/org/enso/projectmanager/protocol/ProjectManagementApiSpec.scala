package org.enso.projectmanager.protocol

import akka.testkit.TestDuration
import io.circe.literal._
import org.enso.semver.SemVer
import org.apache.commons.io.FileUtils
import org.enso.logger.ReportLogsOnFailure
import org.enso.pkg.validation.NameValidation
import org.enso.projectmanager.boot.configuration.TimeoutConfig
import org.enso.projectmanager.{BaseServerSpec, ProjectManagementOps}
import org.enso.runtimeversionmanager.CurrentVersion
import org.enso.runtimeversionmanager.test.OverrideTestVersionSuite
import org.enso.testkit.FlakySpec
import org.scalactic.source.Position

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.UUID
import scala.concurrent.duration._
import scala.io.Source

class ProjectManagementApiSpec
    extends BaseServerSpec
    with FlakySpec
    with OverrideTestVersionSuite
    with ProjectManagementOps
    with ReportLogsOnFailure {

  override val testVersion: SemVer = SemVer.of(0, 0, 1)

  override def beforeEach(): Unit = {
    super.beforeEach()
    gen.reset()
  }

  override val engineToInstall = Some(SemVer.of(0, 0, 1))

  override lazy val timeoutConfig: TimeoutConfig = {
    config.timeout.copy(delayedShutdownTimeout = 1.nanosecond)
  }

  "project/create" must {

    "check if project name is not empty" taggedAs Flaky in {
      val client = new WsTestClient(address)
      client.send(json"""
          { "jsonrpc": "2.0",
            "method": "project/create",
            "id": 1,
            "params": {
              "name": "",
              "missingComponentAction": "Install"
            }
          }
          """)
      client.expectJson(json"""
          { "jsonrpc": "2.0",
            "id": 1,
            "error": { "code": 4001, "message": "Project name cannot be empty." }
          }
          """)
    }

    "validate project name should allow arbitrary characters" in {
      implicit val client: WsTestClient = new WsTestClient(address)
      val projectName                   = "Enso-test-roject4/#$$%^@!"
      val normalizedName                = NameValidation.normalizeName(projectName)

      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/create",
              "id": 1,
              "params": {
                "name": $projectName
              }
            }
          """)
      val projectId = getGeneratedUUID
      client.expectJson(
        json"""
          {"jsonrpc":"2.0",
          "id":1,
          "result":{
            "projectId":$projectId,
            "projectName":$projectName,
            "projectNormalizedName":$normalizedName
            }
          }
          """,
        timeout = 10.seconds.dilated
      )

      //teardown
      deleteProject(projectId)
    }

    "validate project name should not be empty" in {
      val client = new WsTestClient(address)
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/create",
              "id": 1,
              "params": {
                "name": "   "
              }
            }
          """)
      client.expectJson(json"""
          {"jsonrpc":"2.0",
          "id":1,
          "error":{
            "code":4001,
            "message":"Project name cannot be empty."
            }
          }
          """)
    }

    "create project structure" in {
      val projectName = "Foo"

      implicit val client: WsTestClient = new WsTestClient(address)

      val projectId = createProject(projectName)

      val projectDir  = new File(userProjectDir, projectName)
      val packageFile = new File(projectDir, "package.yaml")
      val mainEnso    = Paths.get(projectDir.toString, "src", "Main.enso").toFile
      val meta        = Paths.get(projectDir.toString, ".enso", "project.json").toFile

      packageFile shouldBe Symbol("file")
      mainEnso shouldBe Symbol("file")
      meta shouldBe Symbol("file")

      //teardown
      deleteProject(projectId)
    }

    "create project from default template" in {
      val projectName = "Foo"

      implicit val client: WsTestClient = new WsTestClient(address)

      val projectId =
        createProject(projectName, projectTemplate = Some("default"))

      val projectDir  = new File(userProjectDir, projectName)
      val packageFile = new File(projectDir, "package.yaml")
      val mainEnso    = Paths.get(projectDir.toString, "src", "Main.enso").toFile
      val meta        = Paths.get(projectDir.toString, ".enso", "project.json").toFile

      packageFile shouldBe Symbol("file")
      mainEnso shouldBe Symbol("file")
      meta shouldBe Symbol("file")

      //teardown
      deleteProject(projectId)
    }

    "create project from orders template" in {
      val projectName = "Foo"

      implicit val client: WsTestClient = new WsTestClient(address)

      val projectId =
        createProject(projectName, projectTemplate = Some("orders"))

      val projectDir  = new File(userProjectDir, projectName)
      val packageFile = new File(projectDir, "package.yaml")
      val mainEnso    = Paths.get(projectDir.toString, "src", "Main.enso").toFile
      val storeDataXlsx =
        Paths.get(projectDir.toString, "data", "store_data.xlsx").toFile
      val meta = Paths.get(projectDir.toString, ".enso", "project.json").toFile

      packageFile shouldBe Symbol("file")
      mainEnso shouldBe Symbol("file")
      storeDataXlsx shouldBe Symbol("file")
      meta shouldBe Symbol("file")

      //teardown
      deleteProject(projectId)
    }

    "create project from restaurants template" in {
      val projectName = "Foo"

      implicit val client: WsTestClient = new WsTestClient(address)

      val projectId =
        createProject(projectName, projectTemplate = Some("restaurants"))

      val projectDir  = new File(userProjectDir, projectName)
      val packageFile = new File(projectDir, "package.yaml")
      val mainEnso    = Paths.get(projectDir.toString, "src", "Main.enso").toFile
      val laDistrictsCsv =
        Paths.get(projectDir.toString, "data", "la_districts.csv").toFile
      val restaurantsCsv =
        Paths.get(projectDir.toString, "data", "restaurants.csv").toFile
      val meta = Paths.get(projectDir.toString, ".enso", "project.json").toFile

      packageFile shouldBe Symbol("file")
      mainEnso shouldBe Symbol("file")
      laDistrictsCsv shouldBe Symbol("file")
      restaurantsCsv shouldBe Symbol("file")
      meta shouldBe Symbol("file")

      //teardown
      deleteProject(projectId)
    }

    "create project from stargazers template" in {
      val projectName = "Foo"

      implicit val client: WsTestClient = new WsTestClient(address)

      val projectId =
        createProject(projectName, projectTemplate = Some("stargazers"))

      val projectDir  = new File(userProjectDir, projectName)
      val packageFile = new File(projectDir, "package.yaml")
      val mainEnso    = Paths.get(projectDir.toString, "src", "Main.enso").toFile
      val meta        = Paths.get(projectDir.toString, ".enso", "project.json").toFile

      packageFile shouldBe Symbol("file")
      mainEnso shouldBe Symbol("file")
      meta shouldBe Symbol("file")

      //teardown
      deleteProject(projectId)
    }

    "find a name when project with the same name exists" in {
      val projectName = "Foo"

      implicit val client: WsTestClient = new WsTestClient(address)

      val projectId1 = createProject(projectName)
      val projectId2 = createProject(
        projectName,
        nameSuffix = Some(1)
      )

      val projectDir  = new File(userProjectDir, "Foo_1")
      val packageFile = new File(projectDir, "package.yaml")

      Files.readAllLines(packageFile.toPath) contains "name: Foo_1"

      //teardown
      deleteProject(projectId1)
      deleteProject(projectId2)
    }

    "find a name when project is created from template" in {
      val projectName = "Foo"

      implicit val client: WsTestClient = new WsTestClient(address)

      val projectId1 =
        createProject(projectName, projectTemplate = Some("default"))
      val projectId2 = createProject(
        projectName,
        projectTemplate = Some("default"),
        nameSuffix      = Some(1)
      )

      val projectDir  = new File(userProjectDir, "Foo_1")
      val packageFile = new File(projectDir, "package.yaml")

      Files.readAllLines(packageFile.toPath) contains "name: Foo_1"

      //teardown
      deleteProject(projectId1)
      deleteProject(projectId2)
    }

    "create project with specific version" in {
      implicit val client: WsTestClient = new WsTestClient(address)
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/create",
              "id": 1,
              "params": {
                "name": "Foo",
                "version": ${CurrentVersion.version.toString}
              }
            }
          """)
      val projectId = getGeneratedUUID
      client.expectJson(json"""
          {
            "jsonrpc" : "2.0",
            "id" : 1,
            "result" : {
              "projectId" : $projectId,
              "projectName" : "Foo",
              "projectNormalizedName": "Foo"
            }
          }
          """)

      //teardown
      deleteProject(projectId)
    }

    "create a project dir with a suffix if a directory is taken" in {
      val projectName           = "Foo"
      val projectDir            = new File(userProjectDir, projectName)
      val projectDirWithSuffix1 = new File(userProjectDir, projectName + "_1")
      val projectDirWithSuffix2 = new File(userProjectDir, projectName + "_2")
      val packageFile           = new File(projectDirWithSuffix2, "package.yaml")
      projectDir.mkdirs()
      projectDirWithSuffix1.mkdirs()
      implicit val client: WsTestClient = new WsTestClient(address)

      val projectId = createProject(projectName)
      projectDirWithSuffix2.isDirectory shouldBe true
      packageFile.isFile shouldBe true

      //teardown
      deleteProject(projectId)
      FileUtils.deleteQuietly(projectDir)
      FileUtils.deleteQuietly(projectDirWithSuffix1)
      FileUtils.deleteQuietly(projectDirWithSuffix2)
    }

    "create project in a custom directory" in {
      val projectName       = "Foo"
      val customProjectsDir = Files.createTempDirectory("enso-projects-custom")

      implicit val client: WsTestClient = new WsTestClient(address)

      val projectId = createProject(
        projectName,
        projectsDirectory = Some(customProjectsDir.toFile)
      )

      val projectDir  = new File(customProjectsDir.toFile, projectName)
      val packageFile = new File(projectDir, "package.yaml")
      val mainEnso    = Paths.get(projectDir.toString, "src", "Main.enso").toFile
      val meta        = Paths.get(projectDir.toString, ".enso", "project.json").toFile

      packageFile shouldBe Symbol("file")
      mainEnso shouldBe Symbol("file")
      meta shouldBe Symbol("file")

      //teardown
      deleteProject(
        projectId,
        projectsDirectory = Some(customProjectsDir.toFile)
      )
    }

  }

  "project/delete" must {

    "fail when project doesn't exist" in {
      val client = new WsTestClient(address)
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/delete",
              "id": 1,
              "params": {
                "projectId": ${UUID.randomUUID()}
              }
            }
          """)
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":1,
            "error":{
              "code":4004,
              "message":"Project with the provided id does not exist"
            }
          }
          """)

    }

    "fail when project is running" taggedAs Flaky in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val projectId = createProject("Foo")
      openProject(projectId)
      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/delete",
              "id": 2,
              "params": {
                "projectId": $projectId
              }
            }
          """)
      //then
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":2,
            "error":{
              "code":4008,
              "message":"Cannot remove open project"
            }
          }
          """)

      //teardown
      closeProject(projectId)
      deleteProject(projectId)
    }

    "remove project structure" in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val projectName = "To_Remove"
      val projectId   = createProject(projectName)
      val projectDir  = new File(userProjectDir, projectName)
      projectDir shouldBe Symbol("directory")
      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/delete",
              "id": 1,
              "params": {
                "projectId": $projectId
              }
            }
          """)
      //then
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":1,
            "result": null
          }
          """)

      projectDir.exists() shouldBe false
    }

    "remove project structure in custom directory" in {
      val customProjectDir              = Files.createTempDirectory("enso-projects-custom")
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val projectName = "To_Remove"
      val projectId = createProject(
        projectName,
        projectsDirectory = Some(customProjectDir.toFile)
      )
      val projectDir = new File(customProjectDir.toFile, projectName)
      projectDir shouldBe Symbol("directory")
      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/delete",
              "id": 1,
              "params": {
                "projectId": $projectId,
                "projectsDirectory": ${customProjectDir.toString}
              }
            }
          """)
      //then
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":1,
            "result": null
          }
          """)

      projectDir.exists() shouldBe false
    }

  }

  "project/open" must {

    "open a project" taggedAs Flaky in {
      implicit val client: WsTestClient = new WsTestClient(address)

      val projectName = "Test_Project"
      val projectId   = createProject(projectName)
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/open",
              "id": 0,
              "params": {
                "projectId": $projectId
              }
            }
          """)
      val result = openProjectData
      result.projectName shouldEqual projectName
      result.engineVersion shouldEqual CurrentVersion.version

      // teardown
      closeProject(projectId)
      deleteProject(projectId)
    }

    "open a project in a custom directory" taggedAs Flaky in {
      implicit val client: WsTestClient = new WsTestClient(address)

      val customProjectDir = Files.createTempDirectory("enso-projects-custom")
      val projectName      = "Test_Project"
      val projectId = createProject(
        projectName,
        projectsDirectory = Some(customProjectDir.toFile)
      )
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/open",
              "id": 0,
              "params": {
                "projectId": $projectId,
                "projectsDirectory": ${customProjectDir.toString}
              }
            }
          """)
      val result = openProjectData
      result.projectName shouldEqual projectName
      result.engineVersion shouldEqual CurrentVersion.version

      // teardown
      closeProject(projectId)
      deleteProject(
        projectId,
        projectsDirectory = Some(customProjectDir.toFile)
      )
    }

    "fail when project doesn't exist" in {
      val client = new WsTestClient(address)
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/open",
              "id": 0,
              "params": {
                "projectId": ${UUID.randomUUID()}
              }
            }
          """)
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":0,
            "error":{
              "code":4004,
              "message":"Project with the provided id does not exist"
            }
          }
          """)
    }

    "fail when project's edition could not be resolved" in {
      pending
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val projectId = createProject("Foo")
      setProjectParentEdition(
        "Foo",
        "some_weird_edition_name_that-surely-does-not-exist"
      )
      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/open",
              "id": 0,
              "params": {
                "projectId": $projectId
              }
            }
          """)
      //then
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":0,
            "error":{
              "code" : 4011,
              "message" : "Could not resolve project engine version: Cannot load the edition: Could not find edition `some_weird_edition_name_that-surely-does-not-exist`."
            }
          }
          """)
      //teardown
      deleteProject(projectId)
    }

    "start the Language Server if not running" taggedAs Flaky in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val projectName = "To_Remove"
      val projectId   = createProject(projectName)
      //when
      val socket = openProject(projectId)
      val languageServerClient =
        new WsTestClient(s"ws://${socket.host}:${socket.port}")
      languageServerClient.send(json"""
          {
            "jsonrpc": "2.0",
            "method": "file/read",
            "id": 1,
            "params": {
              "path": {
                "rootId": ${UUID.randomUUID()},
                "segments": ["src", "Main.enso"]
              }
            }
          }
            """)
      //then
      languageServerClient.expectJson(json"""
          {
            "jsonrpc":"2.0",
             "id":1,
             "error":{"code":6001,"message":"Session not initialised"}}
            """)
      //teardown
      closeProject(projectId)
      deleteProject(projectId)
    }

    "not start new Language Server if one is running" taggedAs Flaky in {
      val client1   = new WsTestClient(address)
      val projectId = createProject("Foo")(client1, implicitly[Position])
      //when
      val socket1 = openProject(projectId)(client1, implicitly[Position])
      val client2 = new WsTestClient(address)
      val socket2 = openProject(projectId)(client2, implicitly[Position])
      //then
      socket2 shouldBe socket1
      //teardown
      client1.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/close",
              "id": 2,
              "params": {
                "projectId": $projectId
              }
            }
          """)
      client1.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":2,
            "error" : {
              "code" : 4007,
              "message" : "Cannot close project because it is open by other peers"
             }
          }
          """)
      closeProject(projectId)(client2, implicitly[Position])
      deleteProject(projectId)(client1, implicitly[Position])
    }

    "start the Language Server after moving the directory" taggedAs Flaky in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val projectName = "Foo"
      val projectId   = createProject(projectName)

      val newName       = "bar"
      val newProjectDir = new File(userProjectDir, newName)
      FileUtils.moveDirectory(
        new File(userProjectDir, projectName),
        newProjectDir
      )
      val packageFile = new File(newProjectDir, "package.yaml")
      val mainEnso =
        Paths.get(newProjectDir.toString, "src", "Main.enso").toFile
      val meta =
        Paths.get(newProjectDir.toString, ".enso", "project.json").toFile

      packageFile shouldBe Symbol("file")
      mainEnso shouldBe Symbol("file")
      meta shouldBe Symbol("file")

      //when
      val socket = openProject(projectId)
      val languageServerClient =
        new WsTestClient(s"ws://${socket.host}:${socket.port}")
      languageServerClient.send(json"""
          {
            "jsonrpc": "2.0",
            "method": "file/read",
            "id": 1,
            "params": {
              "path": {
                "rootId": ${UUID.randomUUID()},
                "segments": ["src", "Main.enso"]
              }
            }
          }
            """)
      //then
      // 'not initialized' response indicates that language server is running
      languageServerClient.expectJson(json"""
          {
            "jsonrpc":"2.0",
             "id":1,
             "error":{"code":6001,"message":"Session not initialised"}}
            """)
      //teardown
      closeProject(projectId)
      deleteProject(projectId)
    }

    "deduplicate project ids" taggedAs Flaky in {
      implicit val client: WsTestClient = new WsTestClient(address)

      // given
      val projectName1        = "Foo"
      val projectCreationTime = testClock.currentTime
      val projectId1          = createProject(projectName1)
      val projectDir1         = new File(userProjectDir, projectName1)
      val projectDir2         = new File(userProjectDir, "Test")
      FileUtils.copyDirectory(projectDir1, projectDir2)

      // when
      testClock.moveTimeForward()
      openProject(projectId1)
      val projectOpenTime = testClock.currentTime
      //then
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/list",
              "id": 0,
              "params": { }
            }
          """)
      val projectId2 = getGeneratedUUID
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":0,
            "result": {
              "projects": [
                {
                  "name": "Foo",
                  "namespace": "local",
                  "id": $projectId1,
                  "created": $projectCreationTime,
                  "lastOpened": $projectOpenTime
                },
                {
                  "name": "Foo",
                  "namespace": "local",
                  "id": $projectId2,
                  "created": $projectCreationTime,
                  "lastOpened": null
                }
              ]
            }
          }
          """)

      // teardown
      closeProject(projectId1)
      deleteProject(projectId1)
      deleteProject(projectId2)
    }

  }

  "project/close" must {

    "fail when project is not open" taggedAs Flaky in {
      val client = new WsTestClient(address)
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/close",
              "id": 0,
              "params": {
                "projectId": ${UUID.randomUUID()}
              }
            }
          """)
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":0,
            "error":{
              "code":4006,
              "message":"Cannot close project that is not open"
            }
          }
          """)

    }

    "close project when the requester is the only client" taggedAs Flaky in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val projectId = createProject("Foo")
      val socket    = openProject(projectId)
      val languageServerClient =
        new WsTestClient(s"ws://${socket.host}:${socket.port}")
      languageServerClient.send("test")
      languageServerClient.expectJson(json"""
          {
            "jsonrpc" : "2.0",
            "id" : null,
            "error" : {
              "code" : -32700,
              "message" : "Parse error"
            }
          }
            """)

      //when
      closeProject(projectId)
      languageServerClient.send("test")
      //then
      languageServerClient.expectNoMessage()
      //teardown
      deleteProject(projectId)
    }

    "close project opened from a custom projects directory" taggedAs Flaky in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val customProjectDir = Files.createTempDirectory("enso-projects-custom")
      val projectId =
        createProject("Foo", projectsDirectory = Some(customProjectDir.toFile))
      val socket = openProject(
        projectId,
        projectsDirectory = Some(customProjectDir.toFile)
      )
      val languageServerClient =
        new WsTestClient(s"ws://${socket.host}:${socket.port}")
      languageServerClient.send("test")
      languageServerClient.expectJson(json"""
          {
            "jsonrpc" : "2.0",
            "id" : null,
            "error" : {
              "code" : -32700,
              "message" : "Parse error"
            }
          }
            """)

      //when
      closeProject(projectId)
      languageServerClient.send("test")
      //then
      languageServerClient.expectNoMessage()
      //teardown
      deleteProject(
        projectId,
        projectsDirectory = Some(customProjectDir.toFile)
      )
    }
  }

  "project/list" must {

    "return a list sorted by creation time if none of projects was opened" in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val projectFooCreationTime = testClock.currentTime
      val fooId                  = createProject("Foo")
      testClock.moveTimeForward()
      val projectBarCreationTime = testClock.currentTime
      val barId                  = createProject("Bar")
      testClock.moveTimeForward()
      val projectBazCreationTime = testClock.currentTime
      val bazId                  = createProject("Baz")
      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/list",
              "id": 0,
              "params": { }
            }
          """)
      //then
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":0,
            "result": {
              "projects": [
                {
                  "name": "Baz",
                  "namespace": "local",
                  "id": $bazId,
                  "created": $projectBazCreationTime,
                  "lastOpened": null
                },
                {
                  "name": "Bar",
                  "namespace": "local",
                  "id": $barId,
                  "created": $projectBarCreationTime,
                  "lastOpened": null
                },
                {
                  "name": "Foo",
                  "namespace": "local",
                  "id": $fooId,
                  "created": $projectFooCreationTime,
                  "lastOpened": null
                }
              ]
            }
          }
          """)
      deleteProject(fooId)
      deleteProject(barId)
      deleteProject(bazId)
    }

    "return a list of projects even if editions of some of them cannot be resolved" taggedAs Flaky in {
      pending // flaky
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val projectFooCreationTime = testClock.currentTime
      val fooId                  = createProject("Foo")
      testClock.moveTimeForward()
      val projectBarCreationTime = testClock.currentTime
      val barId                  = createProject("Bar")
      setProjectParentEdition(
        "Bar",
        "some_weird_edition_name_that-surely-does-not-exist"
      )

      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/list",
              "id": 0,
              "params": { }
            }
          """)
      //then
      client.expectJson(
        json"""
          {
            "jsonrpc":"2.0",
            "id":0,
            "result": {
              "projects": [
                {
                  "name": "Bar",
                  "namespace": "local",
                  "id": $barId,
                  "created": $projectBarCreationTime,
                  "lastOpened": null
                },
                {
                  "name": "Foo",
                  "namespace": "local",
                  "id": $fooId,
                  "created": $projectFooCreationTime,
                  "lastOpened": null
                }
              ]
            }
          }
          """,
        timeout = 10.seconds.dilated
      )
      deleteProject(fooId)
      deleteProject(barId)
    }

    "returned sorted list of recently opened projects" in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val creationTime = testClock.currentTime
      val fooId        = createProject("Foo")
      val barId        = createProject("Bar")
      val bazId        = createProject("Baz")
      testClock.moveTimeForward()
      openProject(barId)
      val barOpenTime = testClock.currentTime
      testClock.moveTimeForward()
      openProject(bazId)
      val bazOpenTime = testClock.currentTime
      testClock.moveTimeForward()
      val projectQuuxCreationTime = testClock.currentTime
      val quuxId                  = createProject("Quux")
      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/list",
              "id": 0,
              "params": {
                "numberOfProjects": 4
              }
            }
          """)
      //then
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":0,
            "result": {
              "projects": [
                {
                  "name": "Quux",
                  "namespace": "local",
                  "id": $quuxId,
                  "created": $projectQuuxCreationTime,
                  "lastOpened": null
                },
                {
                  "name": "Baz",
                  "namespace": "local",
                  "id": $bazId,
                  "created": $creationTime,
                  "lastOpened": $bazOpenTime
                },
                {
                  "name": "Bar",
                  "namespace": "local",
                  "id": $barId,
                  "created": $creationTime,
                  "lastOpened": $barOpenTime
                },
                {
                  "name": "Foo",
                  "namespace": "local",
                  "id": $fooId,
                  "created": $creationTime,
                  "lastOpened": null
                }
              ]
            }
          }
          """)
      //teardown
      closeProject(barId)
      closeProject(bazId)
      deleteProject(fooId)
      deleteProject(barId)
      deleteProject(bazId)
      deleteProject(quuxId)
    }

    "resolve clashing ids" taggedAs Flaky in {
      implicit val client: WsTestClient = new WsTestClient(address)

      // given
      val projectName1 = "Foo"
      val creationTime = testClock.currentTime
      val projectId1   = createProject(projectName1)
      val projectDir1  = new File(userProjectDir, projectName1)
      val projectDir2  = new File(userProjectDir, "Test")
      FileUtils.copyDirectory(projectDir1, projectDir2)

      // when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/list",
              "id": 0,
              "params": { }
            }
          """)

      //then
      val projectId2 = getGeneratedUUID
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":0,
            "result": {
              "projects": [
                {
                  "name": "Foo",
                  "namespace": "local",
                  "id": $projectId1,
                  "created": $creationTime,
                  "lastOpened": null
                },
                {
                  "name": "Foo",
                  "namespace": "local",
                  "id": $projectId2,
                  "created": $creationTime,
                  "lastOpened": null
                }
              ]
            }
          }
          """)

      // teardown
      deleteProject(projectId1)
      deleteProject(projectId2)
    }

  }

  "project/rename" must {

    "rename a project and move project dir" in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val newProjectName = "Bar"
      val projectId      = createProject("Foo")
      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/rename",
              "id": 0,
              "params": {
                "projectId": $projectId,
                "name": $newProjectName
              }
            }
          """)
      //then
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":0,
            "result": null
          }
          """)
      val projectDir  = new File(userProjectDir, newProjectName)
      val packageFile = new File(projectDir, "package.yaml")
      val buffer      = Source.fromFile(packageFile)
      val lines       = buffer.getLines()
      lines.contains("name: Bar") shouldBe true
      buffer.close()
      //teardown
      deleteProject(projectId)
    }

    "rename a project in a custom projects directory" in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val customProjectDir = Files.createTempDirectory("enso-projects-custom")
      val newProjectName   = "Bar"
      val projectId =
        createProject("Foo", projectsDirectory = Some(customProjectDir.toFile))
      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/rename",
              "id": 0,
              "params": {
                "projectId": $projectId,
                "name": $newProjectName,
                "projectsDirectory": ${customProjectDir.toString}
              }
            }
          """)
      //then
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":0,
            "result": null
          }
          """)
      val projectDir  = new File(customProjectDir.toFile, newProjectName)
      val packageFile = new File(projectDir, "package.yaml")
      val buffer      = Source.fromFile(packageFile)
      val lines       = buffer.getLines()
      lines.contains("name: Bar") shouldBe true
      buffer.close()
      //teardown
      deleteProject(
        projectId,
        projectsDirectory = Some(customProjectDir.toFile)
      )
    }

    "create a project dir with a suffix if a directory is taken" taggedAs Flaky in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val oldProjectName    = "Foobar"
      val newProjectName    = "Foo"
      val projectId         = createProject(oldProjectName)
      val primaryProjectDir = new File(userProjectDir, newProjectName)
      primaryProjectDir.mkdirs()
      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/rename",
              "id": 0,
              "params": {
                "projectId": $projectId,
                "name": $newProjectName
              }
            }
          """)
      //then
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":0,
            "result": null
          }
          """)
      val projectDir  = new File(userProjectDir, s"${newProjectName}_1")
      val packageFile = new File(projectDir, "package.yaml")
      val buffer      = Source.fromFile(packageFile)
      val lines       = buffer.getLines()
      lines.contains("name: Foo") shouldBe true
      buffer.close()
      //teardown
      deleteProject(projectId)
    }

    "reply with an error when the project with the same name exists" in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val oldProjectName    = "Foo"
      val newProjectName    = "Bar"
      val projectId         = createProject(oldProjectName)
      val existingProjectId = createProject(newProjectName)
      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/rename",
              "id": 0,
              "params": {
                "projectId": $projectId,
                "name": $newProjectName
              }
            }
          """)
      //then
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":0,
            "error":{
              "code":4003,
              "message":"Project with the provided name exists"
            }
          }
          """)
      //teardown
      deleteProject(projectId)
      deleteProject(existingProjectId)
    }

    "reply with an error when the project doesn't exist" in {
      //given
      implicit val client: WsTestClient = new WsTestClient(address)
      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/rename",
              "id": 0,
              "params": {
                "projectId": ${UUID.randomUUID()},
                "name": "Bar"
              }
            }
          """)
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":0,
            "error":{
              "code":4004,
              "message":"Project with the provided id does not exist"
            }
          }
          """)
    }

    "validate project name allow arbitrary characters" in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val projectId = createProject("Foo")
      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/rename",
              "id": 0,
              "params": {
                "projectId": $projectId,
                "name": "Enso-test-project4/#$$%^@!"
              }
            }
          """)
      //then
      client.expectJson(json"""
          {"jsonrpc":"2.0",
          "id":0,
          "result":null
          }
          """)
      //teardown
      deleteProject(projectId)
    }

    "validate project name should not be empty" in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val projectId = createProject("Foo")
      //when
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/rename",
              "id": 0,
              "params": {
                "projectId": $projectId,
                "name": "   "
              }
            }
          """)
      //then
      client.expectJson(json"""
          {"jsonrpc":"2.0",
          "id":0,
          "error":{
            "code":4001,
            "message":"Project name cannot be empty."
            }
          }
          """)
      //teardown
      deleteProject(projectId)
    }
  }

  "project/duplicate" must {

    "duplicate a project" in {
      implicit val client: WsTestClient = new WsTestClient(address)
      //given
      val projectName = "Project To Copy"
      val projectId   = createProject(projectName)
      //when
      client.send(json"""
        { "jsonrpc": "2.0",
          "method": "project/duplicate",
          "id": 0,
          "params": {
            "projectId": $projectId
          }
        }
        """)
      //then
      val newProjectName = "Project To Copy (copy)"
      val duplicateReply = client.fuzzyExpectJson(json"""
        {
          "jsonrpc": "2.0",
          "id": 0,
          "result": {
            "projectId": "*",
            "projectName": $newProjectName,
            "projectNormalizedName": "ProjectToCopycopy"
          }
        }
        """)

      val Some(duplicatedProjectId) = for {
        reply         <- duplicateReply.asObject
        resultJson    <- reply("result")
        result        <- resultJson.asObject
        projectIdJson <- result("projectId")
        projectId     <- projectIdJson.asString
      } yield UUID.fromString(projectId)

      {
        val projectDir  = new File(userProjectDir, "ProjectToCopycopy")
        val packageFile = new File(projectDir, "package.yaml")
        val buffer      = Source.fromFile(packageFile)
        try {
          val lines = buffer.getLines()
          lines.contains(s"name: $newProjectName") shouldBe true
        } finally {
          buffer.close()
        }
      }

      //when
      client.send(json"""
        { "jsonrpc": "2.0",
          "method": "project/duplicate",
          "id": 0,
          "params": {
            "projectId": $projectId
          }
        }
        """)
      //then
      val newProjectName1 = "Project To Copy (copy)_1"
      val duplicateReply1 = client.fuzzyExpectJson(json"""
        {
          "jsonrpc": "2.0",
          "id": 0,
          "result": {
            "projectId": "*",
            "projectName": $newProjectName1,
            "projectNormalizedName": "ProjectToCopycopy_1"
          }
        }
        """)

      val Some(duplicatedProjectId1) = for {
        reply         <- duplicateReply1.asObject
        resultJson    <- reply("result")
        result        <- resultJson.asObject
        projectIdJson <- result("projectId")
        projectId     <- projectIdJson.asString
      } yield UUID.fromString(projectId)

      {
        val projectDir  = new File(userProjectDir, "ProjectToCopycopy_1")
        val packageFile = new File(projectDir, "package.yaml")
        val buffer      = Source.fromFile(packageFile)
        try {
          val lines = buffer.getLines()
          lines.contains(s"name: $newProjectName1") shouldBe true
        } finally {
          buffer.close()
        }
      }

      //teardown
      deleteProject(duplicatedProjectId)
      deleteProject(duplicatedProjectId1)
      deleteProject(projectId)
    }

    "fail when project doesn't exist" in {
      val client = new WsTestClient(address)
      client.send(json"""
            { "jsonrpc": "2.0",
              "method": "project/duplicate",
              "id": 1,
              "params": {
                "projectId": ${UUID.randomUUID()}
              }
            }
          """)
      client.expectJson(json"""
          {
            "jsonrpc":"2.0",
            "id":1,
            "error":{
              "code":4004,
              "message":"Project with the provided id does not exist"
            }
          }
          """)
    }

  }
}
