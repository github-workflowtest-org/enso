package org.enso.interpreter.test.semantic

import org.enso.interpreter.test.{InterpreterException, PackageTest}
import org.enso.polyglot.RuntimeOptions

class ImportsTest extends PackageTest {
  implicit def messagingNatureOInterpreterException
    : org.scalatest.enablers.Messaging[InterpreterException] =
    new org.scalatest.enablers.Messaging[InterpreterException] {
      def messageOf(exception: InterpreterException): String =
        exception.getLocalizedMessage
    }

  private def isDiagnosticLine(line: String): Boolean = {
    line.contains(" | ")
  }

  "Atoms and methods" should "be available for import" in {
    evalTestProject("Test_Simple_Imports") shouldEqual 20
  }

  "Methods defined together with atoms" should "be visible even if not imported" in {
    evalTestProject("Test_Non_Imported_Own_Methods") shouldEqual 10
  }

  "Overloaded methods" should "not be visible when not imported" in {
    the[InterpreterException] thrownBy evalTestProject(
      "Test_Non_Imported_Overloads"
    ) should have message "Method `method` of type X could not be found."
  }

  "Import statements" should "report errors when they cannot be resolved" in {
    val ex = the[InterpreterException] thrownBy evalTestProject(
      "Test_Bad_Imports"
    )
    ex.getMessage should include("The package could not be resolved")
    val outLines = consumeOut.filterNot(isDiagnosticLine)
    outLines should have size 2
    outLines(0) should include(
      "Package containing the module Surely_This.Does_Not_Exist.My_Module " +
      "could not be loaded: The package could not be resolved: The library " +
      "`Surely_This.Does_Not_Exist` is not defined within the edition."
    )
    outLines(1) should include(
      "The module Enso_Test.Test_Bad_Imports.Oopsie does not exist."
    )
  }

  "Symbols from imported modules" should "not be visible when imported qualified" in {
    val ex = the[InterpreterException] thrownBy evalTestProject(
      "Test_Qualified_Error"
    )
    ex.getMessage should include("The name `Mk_X` could not be found.")
    val outLines = consumeOut
    outLines
      .filterNot(isDiagnosticLine)
      .head should include("The name `Mk_X` could not be found.")
  }

  "Symbols from imported modules" should "not be visible when hidden" in {
    val ex = the[InterpreterException] thrownBy evalTestProject(
      "Test_Hiding_Error"
    )
    ex.getMessage should include("The name `X` could not be found.")
    consumeOut
      .filterNot(isDiagnosticLine)
      .head should include("The name `X` could not be found.")
  }

  "Symbols from imported modules" should "be visible even when others are hidden" in {
    evalTestProject("Test_Hiding_Success") shouldEqual 20
  }

  "Imported modules" should "be renamed with renaming imports" in {
    evalTestProject("Test_Rename") shouldEqual 20
  }

  "Imported modules" should "not be visible under original name when renamed" in {
    val ex = the[InterpreterException] thrownBy evalTestProject(
      "Test_Rename_Error"
    )
    ex.getMessage should include("The name `Atom` could not be found.")
    consumeOut
      .filterNot(isDiagnosticLine)
      .head should include("The name `Atom` could not be found.")
  }

  "Importing everything from the module" should "should not bring module into the scope when resolving names" in {
    evalTestProject("Test_Import_Case") shouldEqual 0
  }

  "Exports system" should "detect cycles" in {
    the[InterpreterException] thrownBy (evalTestProject(
      "Cycle_Test"
    )) should have message "Compilation aborted due to errors."
    consumeOut should contain("Export statements form a cycle:")
  }

  "Exports system" should "honor logical export" in {
    val compilationResult = evalTestProject(
      "Logical_Import_Violated_Test"
    )
    compilationResult shouldEqual "Element with Internal"
    consumeOut shouldEqual List()
  }

  "Import statements" should "allow for importing submodules" in {
    evalTestProject("Test_Submodules") shouldEqual 42
    val outLines = consumeOut
    outLines(0) shouldEqual "(Foo 10)"
    outLines(1) shouldEqual "(Mk_C 52)"
    outLines(2) shouldEqual "20"
    outLines(3) shouldEqual "(Mk_C 10)"
  }

  "Importing module" should "bring extension methods into the scope " in {
    evalTestProject("Test_Extension_Methods_Success_1") shouldEqual 42
  }

  "The unqualified import of a module" should "bring extension methods into the scope " in {
    evalTestProject("Test_Extension_Methods_Success_2") shouldEqual 42
  }

  "Importing module's types" should "not bring extension methods into the scope " in {
    the[InterpreterException] thrownBy evalTestProject(
      "Test_Extension_Methods_Failure"
    ) should have message "Method `foo` of type Integer could not be found."
  }

  "Compiler" should "detect name conflicts preventing users from importing submodules" in {
    try {
      evalTestProject("Test_Submodules_Name_Conflict")
      fail("Should throw CompilerError")
    } catch {
      case e: InterpreterException =>
        e.getMessage.contains("Conflicting resolutions") shouldBe true
    }
  }

  "Compiler" should "accept exports of the same module" in {
    evalTestProject("Test_Multiple_Exports") shouldEqual 0
    val outLines = consumeOut
    outLines(0) shouldEqual "z"
    outLines(1) shouldEqual "42"
  }

  "Polyglot symbols" should "not be exported" in {
    val ex = the[InterpreterException] thrownBy evalTestProject(
      "Test_Polyglot_Exports"
    )
    ex.getMessage should include(
      "Main.enso:5:16: error: The name `Long` could not be found."
    )
    val outLines = consumeOut.filterNot(isDiagnosticLine)
    outLines should have length 1
    outLines.head should include(
      "Main.enso:5:16: error: The name `Long` could not be found."
    )
  }

  "Constructors" should "be importable" in {
    evalTestProject("Test_Type_Imports").toString shouldEqual "(Some 10)"
  }

  "Constructors" should "be exportable" in {
    evalTestProject("Test_Type_Exports").toString shouldEqual "(Some 10)"
  }

  "Fully qualified names" should "not be resolved when lacking imports" in {
    val ex = the[InterpreterException] thrownBy evalTestProject(
      "Test_Fully_Qualified_Name_Failure"
    )
    ex.getMessage should include(
      "Main.enso:2:14: error: Fully qualified name references a library Standard.Base but an import statement for it is missing."
    )
    val outLines = consumeOut.filterNot(isDiagnosticLine)
    outLines should have length 1
    outLines.head should include(
      "Main.enso:2:14: error: Fully qualified name references a library Standard.Base but an import statement for it is missing."
    )
  }

  "Fully qualified names" should "be resolved when library has already been loaded" in {
    evalTestProject(
      "Test_Fully_Qualified_Name_Success"
    ).toString shouldEqual "0"
    val outLines = consumeOut
    outLines should have length 1
    outLines(0) shouldEqual "Hello world!"
  }

  "Fully qualified names" should "detect conflicts with the exported types sharing the namespace" in {
    evalTestProject(
      "Test_Fully_Qualified_Name_Conflict"
    ).toString shouldEqual "Foo"
    val outLines = consumeOut.filterNot(isDiagnosticLine)
    outLines.head should include(
      "Main.enso:2:1: warning: The exported type `Atom` in `local.Test_Fully_Qualified_Name_Conflict.Atom` module will cause name conflict when attempting to use a fully qualified name of the `local.Test_Fully_Qualified_Name_Conflict.Atom.Foo` module."
    )
  }

  "Fully qualified names" should "resolve symbols via physical FQN from other project" in {
    val res = evalTestProject(
      "Test_Fully_Qualified_Name_2"
    )
    res.isBoolean shouldBe true
    res.asBoolean() shouldBe true
  }

  "Deeply nested modules" should "infer correct synthetic modules" in {
    evalTestProject(
      "Test_Deeply_Nested_Modules"
    ).toString shouldEqual "0"
    val outLines = consumeOut
    outLines should have length 3
    outLines(0) shouldEqual "(A_Mod.Value 1)"
    outLines(1) shouldEqual "(C_Mod.Value 1)"
    outLines(2) shouldEqual "(D_Mod.Value 1)"
  }

  "Private modules" should "be able to import and use private modules within the same project" in {
    evalTestProject(
      "Test_Private_Modules_1"
    ).toString shouldEqual "42"
  }

  "Private modules" should "be able to import non-private stuff from different project" in {
    evalTestProject(
      "Test_Private_Modules_2"
    ).toString shouldEqual "(Pub_Mod_Type.Value 42)"
  }

  "Private modules" should "not be able to import private modules from different project" in {
    val ex = the[InterpreterException] thrownBy evalTestProject(
      "Test_Private_Modules_3"
    )
    ex.getMessage should include("error: Cannot import private module")
    val outLines = consumeOut.filterNot(isDiagnosticLine)
    outLines should have length 1
    outLines.head should include(
      "Main.enso:2:1: error: Cannot import private module"
    )
  }

  "Private modules" should "be able to mix private and public submodules" in {
    evalTestProject(
      "Test_Private_Modules_4"
    ) shouldEqual 42
  }

  "Private module" should "be able to have only private submodules" in {
    evalTestProject(
      "Test_Private_Modules_5"
    ) shouldEqual 42
  }

  "Private modules" should "be able to import private modules from different project when private checks are disabled" in {
    evalTestProject(
      "Test_Private_Modules_3",
      Map(RuntimeOptions.DISABLE_PRIVATE_CHECK -> "true")
    ) shouldEqual "Success"
  }

  "Private modules" should "be able to have a private submodule under a public synthetic module" in {
    evalTestProject(
      "Test_Private_Modules_6"
    ) shouldEqual 42
  }

  "Private modules" should "be able to have a public submodule under a private module" ignore {
    evalTestProject(
      "Test_Private_Modules_7"
    ) shouldEqual 42
  }
}
