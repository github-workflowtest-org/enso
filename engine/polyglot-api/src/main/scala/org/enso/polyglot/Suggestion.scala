package org.enso.polyglot

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonSubTypes, JsonTypeInfo}
import org.enso.logger.masking.ToLogString

import java.util.UUID

import scala.collection.immutable.ListSet

/** A search suggestion. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
  Array(
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Module],
      name  = "suggestionModule"
    ),
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Type],
      name  = "suggestionType"
    ),
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Constructor],
      name  = "suggestionConstructor"
    ),
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Getter],
      name  = "suggestionGetter"
    ),
    new JsonSubTypes.Type(
      value = classOf[Suggestion.DefinedMethod],
      name  = "suggestionDefinedMethod"
    ),
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Conversion],
      name  = "suggestionConversion"
    ),
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Function],
      name  = "suggestionFunction"
    ),
    new JsonSubTypes.Type(
      value = classOf[Suggestion.Local],
      name  = "suggestionLocal"
    )
  )
)
@SerialVersionUID(9650L)
sealed trait Suggestion extends ToLogString {

  def externalId:    Option[Suggestion.ExternalID]
  def module:        String
  def name:          String
  def returnType:    String
  def documentation: Option[String]

  def withReexports(reexports: Set[String]): Suggestion

  def withReturnType(returnType: String): Suggestion

  /** Creates a copy of this suggestion with the optional fields changed, if applicable.
    *
    * @param optExternalId externalID to modify, if non-empty
    * @param optReturnType return type to modify, if non-empty and applicable
    * @param optDocumentation documentation to modify, if non-empty
    * @param optScope scope to modify, if non-empty and applicable
    * @return a copy of this suggestion with modified fields, if applicable, unchanged suggestion otherwise
    */
  def update(
    optExternalId: Option[Option[Suggestion.ExternalID]],
    optReturnType: Option[String],
    optDocumentation: Option[Option[String]],
    optScope: Option[Suggestion.Scope]
  ): Suggestion
}

object Suggestion {

  type ExternalID = UUID

  /** @return `true` if the suggestion id defined in the global (module) scope.
    */
  def isGlobal(suggestion: Suggestion): Boolean =
    suggestion match {
      case _: Suggestion.Module      => true
      case _: Suggestion.Type        => true
      case _: Suggestion.Constructor => true
      case _: Suggestion.Method      => true
      case _: Suggestion.Function    => false
      case _: Suggestion.Local       => false
    }

  /** The type of a suggestion. */
  sealed trait Kind
  object Kind {

    def apply(suggestion: Suggestion): Kind =
      suggestion match {
        case _: Module        => Module
        case _: Type          => Type
        case _: Constructor   => Constructor
        case _: Conversion    => Conversion
        case _: Getter        => Getter
        case _: DefinedMethod => Method
        case _: Function      => Function
        case _: Local         => Local
      }

    /** The module suggestion. */
    case object Module extends Kind

    /** The type suggestion. */
    case object Type extends Kind

    /** The constructor suggestion. */
    case object Constructor extends Kind

    /** The constructor field accessor suggestion. */
    case object Getter extends Kind

    /** The method suggestion. */
    case object Method extends Kind

    /** The conversion suggestion. */
    case object Conversion extends Kind {
      val From = "from"
    }

    /** The function suggestion. */
    case object Function extends Kind

    /** The suggestion of a local value. */
    case object Local extends Kind
  }

  /** Arguments extractor. */
  object Arguments {

    def apply(suggestion: Suggestion): Seq[Argument] =
      suggestion match {
        case _: Module                => Seq()
        case tpe: Type                => tpe.params
        case constructor: Constructor => constructor.arguments
        case method: Method           => method.arguments
        case function: Function       => function.arguments
        case _: Local                 => Seq()
      }
  }

  /** Self type extractor. */
  object SelfType {

    def apply(suggestion: Suggestion): Option[String] =
      suggestion match {
        case _: Module                => None
        case _: Type                  => None
        case constructor: Constructor => Some(constructor.returnType)
        case method: Method           => Some(method.selfType)
        case _: Function              => None
        case _: Local                 => None
      }
  }

  /** Annotations extractor. */
  object Annotations {

    def apply(suggestion: Suggestion): Seq[String] =
      suggestion match {
        case _: Module                => Seq()
        case _: Type                  => Seq()
        case constructor: Constructor => constructor.annotations
        case method: Method           => method.annotations
        case _: Function              => Seq()
        case _: Local                 => Seq()
      }
  }

  /** An argument of an atom or a function.
    *
    * @param name the argument name
    * @param reprType the type of the argument
    * @param isSuspended is the argument lazy
    * @param hasDefault does the argument have a default
    * @param defaultValue optional default value
    * @param tagValues optional list of possible values
    */
  case class Argument(
    name: String,
    reprType: String,
    isSuspended: Boolean,
    hasDefault: Boolean,
    defaultValue: Option[String],
    tagValues: Option[Seq[String]] = None
  ) extends ToLogString {

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      "Argument(" +
      s"name=$name," +
      s"reprType=$reprType," +
      s"isSuspended=$isSuspended," +
      s"hasDefault=$hasDefault,defaultValue=" +
      (if (shouldMask) defaultValue.map(_ => STUB) else defaultValue) +
      s",tagValues=" +
      (if (shouldMask) tagValues.map(_ => STUB) else tagValues) +
      ")"
  }

  /** Position in the text.
    *
    * @param line a line position in a document (zero-based).
    * @param character a character offset
    */
  case class Position(line: Int, character: Int)

  /** The definition scope.
    *
    * @param start the start of the definition scope
    * @param end the end of the definition scope
    */
  case class Scope(start: Position, end: Position)

  object Scope {

    /** Creates a scope from the provided suggestion.
      *
      * @param suggestion the provided suggestion
      * @return the scope of the suggestion
      */
    def apply(suggestion: Suggestion): Option[Scope] =
      suggestion match {
        case _: Module          => None
        case _: Type            => None
        case _: Constructor     => None
        case _: Method          => None
        case function: Function => Some(function.scope)
        case local: Local       => Some(local.scope)
      }
  }

  /** A module.
    *
    * @param module the fully qualified module name
    * @param documentation the documentation string
    * @param reexports modules re-exporting this module
    */
  case class Module(
    module: String,
    documentation: Option[String],
    reexports: Set[String] = ListSet()
  ) extends Suggestion
      with ToLogString {

    override def name: String =
      module

    override def externalId: Option[ExternalID] =
      None

    override def returnType: String =
      module

    override def withReexports(reexports: Set[String]): Suggestion =
      copy(reexports = reexports)

    override def withReturnType(returnType: String): Suggestion =
      copy(module = returnType)

    override def update(
      optExternalId: Option[Option[ExternalID]],
      optReturnType: Option[String],
      optDocumentation: Option[Option[String]],
      optScope: Option[Scope]
    ): Suggestion = {
      optDocumentation
        .map(documentation => this.copy(documentation = documentation))
        .getOrElse(this)
    }

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      s"Module(module=$module,name=$name,documentation=" +
      (if (shouldMask) documentation.map(_ => STUB) else documentation) +
      s",reexports=$reexports)"

  }

  /** A type definition.
    *
    * @param externalId the external id
    * @param module the module name
    * @param name the atom name
    * @param params the list of parameters
    * @param returnType the type of an atom
    * @param parentType qualified name of the parent type
    * @param documentation the documentation string
    * @param reexports modules re-exporting this atom
    */
  case class Type(
    externalId: Option[ExternalID],
    module: String,
    name: String,
    params: Seq[Argument],
    returnType: String,
    parentType: Option[String],
    documentation: Option[String],
    reexports: Set[String] = ListSet()
  ) extends Suggestion
      with ToLogString {

    override def withReexports(reexports: Set[String]): Suggestion =
      copy(reexports = reexports)

    override def withReturnType(returnType: String): Suggestion =
      copy(returnType = returnType)

    override def update(
      optExternalId: Option[Option[ExternalID]],
      optReturnType: Option[String],
      optDocumentation: Option[Option[String]],
      optScope: Option[Scope]
    ): Suggestion = {
      val v1 = optExternalId
        .map(externalID => this.copy(externalId = externalID))
        .getOrElse(this)
      val v2 = optReturnType
        .map(returnType => v1.copy(returnType = returnType))
        .getOrElse(v1)
      optDocumentation
        .map(documentation => v2.copy(documentation = documentation))
        .getOrElse(v2)
    }

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      "Type(" +
      s"externalId=$externalId," +
      s"module=$module," +
      s"name=$name," +
      s"params=${params.map(_.toLogString(shouldMask))}," +
      s"returnType=$returnType" +
      s"parentType=$parentType" +
      s",documentation=" + (if (shouldMask) documentation.map(_ => STUB)
                            else documentation) +
      s",reexports=$reexports)"
  }

  /** A value constructor.
    *
    * @param externalId the external id
    * @param module the module name
    * @param name the constructor name
    * @param arguments the list of arguments
    * @param returnType the type of an atom
    * @param documentation the documentation string
    * @param annotations the list of annotations
    * @param reexports modules re-exporting this atom
    */
  case class Constructor(
    externalId: Option[ExternalID],
    module: String,
    name: String,
    arguments: Seq[Argument],
    returnType: String,
    documentation: Option[String],
    annotations: Seq[String],
    reexports: Set[String] = ListSet()
  ) extends Suggestion
      with ToLogString {

    override def withReexports(reexports: Set[String]): Suggestion =
      copy(reexports = reexports)

    override def withReturnType(returnType: String): Suggestion =
      copy(returnType = returnType)

    override def update(
      optExternalId: Option[Option[ExternalID]],
      optReturnType: Option[String],
      optDocumentation: Option[Option[String]],
      optScope: Option[Scope]
    ): Suggestion = {
      val v1 = optExternalId
        .map(externalID => this.copy(externalId = externalID))
        .getOrElse(this)
      val v2 = optReturnType
        .map(returnType => v1.copy(returnType = returnType))
        .getOrElse(v1)
      optDocumentation
        .map(documentation => v2.copy(documentation = documentation))
        .getOrElse(v2)
    }

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      "Constructor(" +
      s"externalId=$externalId," +
      s"module=$module," +
      s"name=$name," +
      s"arguments=${arguments.map(_.toLogString(shouldMask))}," +
      s"returnType=$returnType" +
      s",documentation=" + (if (shouldMask) documentation.map(_ => STUB)
                            else documentation) +
      s",reexports=$reexports)"
  }

  /** Base trait for method suggestions. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(
    Array(
      new JsonSubTypes.Type(
        value = classOf[Suggestion.Getter],
        name  = "suggestionMethodGetter"
      ),
      new JsonSubTypes.Type(
        value = classOf[Suggestion.DefinedMethod],
        name  = "suggestionMethodDefinedMethod"
      ),
      new JsonSubTypes.Type(
        value = classOf[Suggestion.Conversion],
        name  = "suggestionMethodConversion"
      )
    )
  )
  sealed trait Method extends Suggestion {
    def arguments:   Seq[Argument]
    def selfType:    String
    def isStatic:    Boolean
    def annotations: Seq[String]
    def reexports:   Set[String]
  }

  /** A method generated to access constructor field.
    *
    * @param externalId the external id
    * @param module the module name
    * @param name the method name
    * @param arguments the list of arguments
    * @param selfType the self type of a method
    * @param returnType the return type of a method
    * @param documentation the documentation string
    * @param annotations the list of annotations
    * @param reexports modules re-exporting this method
    */
  case class Getter(
    externalId: Option[ExternalID],
    module: String,
    name: String,
    arguments: Seq[Argument],
    selfType: String,
    returnType: String,
    documentation: Option[String],
    annotations: Seq[String],
    reexports: Set[String] = ListSet()
  ) extends Method
      with ToLogString {

    /** @inheritdoc */
    @JsonIgnore
    override def isStatic: Boolean = false

    override def withReexports(reexports: Set[String]): Suggestion =
      copy(reexports = reexports)

    override def withReturnType(returnType: String): Suggestion =
      copy(returnType = returnType)

    override def update(
      optExternalId: Option[Option[ExternalID]],
      optReturnType: Option[String],
      optDocumentation: Option[Option[String]],
      optScope: Option[Scope]
    ): Suggestion = {
      val v1 = optExternalId
        .map(externalID => this.copy(externalId = externalID))
        .getOrElse(this)
      val v2 = optReturnType
        .map(returnType => v1.copy(returnType = returnType))
        .getOrElse(v1)
      optDocumentation
        .map(documentation => v2.copy(documentation = documentation))
        .getOrElse(v2)
    }

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      "Getter(" +
      s"module=$module," +
      s"name=$name," +
      s"arguments=${arguments.map(_.toLogString(shouldMask))}," +
      s"selfType=$selfType," +
      s"returnType=$returnType," +
      s"documentation=" + (if (shouldMask) documentation.map(_ => STUB)
                           else documentation) +
      s",reexports=$reexports)"
  }

  /** A function defined on a type or a module.
    *
    * @param externalId the external id
    * @param module the module name
    * @param name the method name
    * @param arguments the list of arguments
    * @param selfType the self type of a method
    * @param returnType the return type of a method
    * @param isStatic the flag indicating whether a method is static or instance
    * @param documentation the documentation string
    * @param annotations the list of annotations
    * @param reexports modules re-exporting this method
    */
  case class DefinedMethod(
    externalId: Option[ExternalID],
    module: String,
    name: String,
    arguments: Seq[Argument],
    selfType: String,
    returnType: String,
    isStatic: Boolean,
    documentation: Option[String],
    annotations: Seq[String],
    reexports: Set[String] = ListSet()
  ) extends Method
      with ToLogString {

    override def withReexports(reexports: Set[String]): Suggestion =
      copy(reexports = reexports)

    override def withReturnType(returnType: String): Suggestion =
      copy(returnType = returnType)

    override def update(
      optExternalId: Option[Option[ExternalID]],
      optReturnType: Option[String],
      optDocumentation: Option[Option[String]],
      optScope: Option[Scope]
    ): Suggestion = {
      val v1 = optExternalId
        .map(externalID => this.copy(externalId = externalID))
        .getOrElse(this)
      val v2 = optReturnType
        .map(returnType => v1.copy(returnType = returnType))
        .getOrElse(v1)
      optDocumentation
        .map(documentation => v2.copy(documentation = documentation))
        .getOrElse(v2)
    }

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      "Method(" +
      s"module=$module," +
      s"name=$name," +
      s"arguments=${arguments.map(_.toLogString(shouldMask))}," +
      s"selfType=$selfType," +
      s"returnType=$returnType," +
      s"isStatic=$isStatic," +
      s"documentation=" + (if (shouldMask) documentation.map(_ => STUB)
                           else documentation) +
      s",reexports=$reexports)"
  }

  /** A conversion function.
    *
    * @param externalId the external id
    * @param module the module name
    * @param arguments the list of arguments
    * @param selfType the source type of a conversion
    * @param returnType the return type of a conversion
    * @param documentation the documentation string
    * @param reexports modules re-exporting this conversion
    */
  case class Conversion(
    externalId: Option[ExternalID],
    module: String,
    arguments: Seq[Argument],
    selfType: String,
    returnType: String,
    documentation: Option[String],
    reexports: Set[String] = ListSet()
  ) extends Method {

    /** @inheritdoc */
    @JsonIgnore
    override def isStatic: Boolean = false

    /** @inheritdoc */
    @JsonIgnore
    override def annotations: Seq[String] = Seq()

    /** @inheritdoc */
    @JsonIgnore
    override def name: String =
      Kind.Conversion.From

    override def withReexports(reexports: Set[String]): Suggestion =
      copy(reexports = reexports)

    override def withReturnType(returnType: String): Suggestion =
      copy(returnType = returnType)

    override def update(
      optExternalId: Option[Option[ExternalID]],
      optReturnType: Option[String],
      optDocumentation: Option[Option[String]],
      optScope: Option[Scope]
    ): Suggestion = {
      val v1 = optExternalId
        .map(externalID => this.copy(externalId = externalID))
        .getOrElse(this)
      val v2 = optReturnType
        .map(returnType => v1.copy(returnType = returnType))
        .getOrElse(v1)
      optDocumentation
        .map(documentation => v2.copy(documentation = documentation))
        .getOrElse(v2)
    }

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      "Conversion(" +
      s"module=$module," +
      s"arguments=${arguments.map(_.toLogString(shouldMask))}," +
      s"selfType=$selfType," +
      s"returnType=$returnType," +
      s"documentation=" + (if (shouldMask) documentation.map(_ => STUB)
                           else documentation) +
      s",reexports=$reexports)"
  }

  /** A local function definition.
    *
    * @param externalId the external id
    * @param module the module name
    * @param name the function name
    * @param arguments the function arguments
    * @param returnType the return type of a function
    * @param scope the scope where the function is defined
    * @param documentation the documentation string
    */
  case class Function(
    externalId: Option[ExternalID],
    module: String,
    name: String,
    arguments: Seq[Argument],
    returnType: String,
    scope: Scope,
    documentation: Option[String]
  ) extends Suggestion
      with ToLogString {

    override def withReexports(reexports: Set[String]): Suggestion =
      this

    override def withReturnType(returnType: String): Suggestion =
      copy(returnType = returnType)

    override def update(
      optExternalId: Option[Option[ExternalID]],
      optReturnType: Option[String],
      optDocumentation: Option[Option[String]],
      optScope: Option[Scope]
    ): Suggestion = {
      val v1 = optExternalId
        .map(externalID => this.copy(externalId = externalID))
        .getOrElse(this)
      val v2 = optReturnType
        .map(returnType => v1.copy(returnType = returnType))
        .getOrElse(v1)
      val v3 = optDocumentation
        .map(documentation => v2.copy(documentation = documentation))
        .getOrElse(v2)
      optScope.map(scope => v3.copy(scope = scope)).getOrElse(v3)
    }

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      "Function(" +
      s"externalId=$externalId," +
      s"module=$module," +
      s"name=$name," +
      s"arguments=${arguments.map(_.toLogString(shouldMask))}," +
      s"returnType=$returnType," +
      s"scope=$scope," +
      s"documentation=$documentation" +
      ")"
  }

  /** A local value.
    *
    * @param externalId the external id
    * @param module the module name
    * @param name the name of a value
    * @param returnType the type of a local value
    * @param scope the scope where the value is defined
    * @param documentation the documentation string
    */
  case class Local(
    externalId: Option[ExternalID],
    module: String,
    name: String,
    returnType: String,
    scope: Scope,
    documentation: Option[String]
  ) extends Suggestion {

    override def withReexports(reexports: Set[String]): Suggestion =
      this

    override def withReturnType(returnType: String): Suggestion =
      copy(returnType = returnType)

    override def update(
      optExternalId: Option[Option[ExternalID]],
      optReturnType: Option[String],
      optDocumentation: Option[Option[String]],
      optScope: Option[Scope]
    ): Suggestion = {
      val v1 = optExternalId
        .map(externalID => this.copy(externalId = externalID))
        .getOrElse(this)
      val v2 = optReturnType
        .map(returnType => v1.copy(returnType = returnType))
        .getOrElse(v1)
      val v3 = optDocumentation
        .map(documentation => v2.copy(documentation = documentation))
        .getOrElse(v2)
      optScope.map(scope => v3.copy(scope = scope)).getOrElse(v3)
    }

    /** @inheritdoc */
    override def toLogString(shouldMask: Boolean): String =
      s"Local(" +
      s"externalId=$externalId," +
      s"module=$module," +
      s"name=$name," +
      s"returnType=$returnType," +
      s"scope=$scope," +
      s"documentation=$documentation" +
      s")"
  }
}
