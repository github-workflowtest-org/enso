//! Implementation of Syntax Tree, known as well as Abstract Syntax Tree, or AST.

use crate::prelude::*;
use crate::source::*;
use crate::syntax::*;

use crate::span_builder;

#[cfg(feature = "debug")]
use enso_parser_syntax_tree_visitor::Visitor;


// ==============
// === Export ===
// ==============

pub mod block;



// ============
// === Tree ===
// ============

/// The Abstract Syntax Tree of the language.
#[derive(Clone, Debug, Deref, DerefMut, Eq, PartialEq, Serialize, Reflect, Deserialize)]
#[allow(missing_docs)]
pub struct Tree<'s> {
    #[reflect(flatten, hide)]
    pub span:    Span<'s>,
    #[deref]
    #[deref_mut]
    #[reflect(subtype)]
    pub variant: Box<Variant<'s>>,
}

/// Constructor.
#[allow(non_snake_case)]
pub fn Tree<'s>(span: Span<'s>, variant: impl Into<Variant<'s>>) -> Tree<'s> {
    let variant = Box::new(variant.into());
    Tree { variant, span }
}

impl<'s> AsRef<Span<'s>> for Tree<'s> {
    fn as_ref(&self) -> &Span<'s> {
        &self.span
    }
}

impl<'s> Default for Tree<'s> {
    fn default() -> Self {
        Self {
            variant: Box::new(Variant::Ident(Ident { token: Default::default() })),
            span:    Span::empty_without_offset(),
        }
    }
}

/// Macro providing [`Tree`] type definition. It is used to both define the ast [`Variant`], and to
/// define impls for every token type in other modules.
#[macro_export]
macro_rules! with_ast_definition { ($f:ident ($($args:tt)*)) => { $f! { $($args)*
    /// [`Tree`] variants definition. See its docs to learn more.
    #[tagged_enum]
    #[cfg_attr(feature = "debug", derive(Visitor))]
    #[derive(Clone, Eq, PartialEq, Serialize, Reflect, Deserialize)]
    #[allow(clippy::large_enum_variant)] // Inefficient. Will be fixed in #182878443.
    #[tagged_enum(apply_attributes_to = "variants")]
    #[reflect(inline)]
    pub enum Variant<'s> {
        /// Invalid [`Tree`] fragment with an attached [`Error`].
        Invalid {
            pub error: Error,
            pub ast: Tree<'s>,
        },
        /// A sequence of lines introduced by a line ending in an operator.
        BodyBlock {
            /// The lines of the block.
            pub statements: Vec<block::Line<'s>>,
        },
        /// A sequence of lines comprising the arguments of a function call.
        ArgumentBlockApplication {
            /// The expression for the value to which the arguments are to be applied.
            pub lhs: Option<Tree<'s>>,
            /// The lines of the block.
            pub arguments: Vec<block::Line<'s>>,
        },
        /// A sequence of lines comprising a tree of operator expressions.
        OperatorBlockApplication {
            /// The expression preceding the block; this will be the leftmost-leaf of the binary
            /// tree.
            pub lhs: Option<Tree<'s>>,
            /// The lines of the block.
            pub expressions: Vec<block::OperatorLine<'s>>,
            /// Lines that appear lexically within the block, but are not syntactically consistent
            /// with an operator block.
            pub excess: Vec<block::Line<'s>>,
        },
        /// A simple identifier, like `foo` or `bar`.
        Ident {
            pub token: token::Ident<'s>,
        },
        /// A `private` keyword, marking associated expressions as project-private.
        Private {
            pub keyword: token::Private<'s>,
            pub body: Option<Tree<'s>>,
        },
        /// A numeric literal, like `10`.
        Number {
            pub base:              Option<token::NumberBase<'s>>,
            pub integer:           Option<token::Digits<'s>>,
            pub fractional_digits: Option<FractionalDigits<'s>>,
        },
        /// The wildcard marker, `_`.
        Wildcard {
            pub token: token::Wildcard<'s>,
            #[serde(serialize_with = "crate::serialization::serialize_optional_int")]
            #[serde(deserialize_with = "crate::serialization::deserialize_optional_int")]
            #[reflect(as = "i32")]
            pub de_bruijn_index: Option<u32>,
        },
        /// The suspended-default-arguments marker, `...`.
        SuspendedDefaultArguments {
            pub token: token::SuspendedDefaultArguments<'s>,
        },
        TextLiteral {
            pub open:     Option<token::TextStart<'s>>,
            /// If there is no text on the first line of a multi-line literal, the initial newline
            /// is non-semantic and included here. If there is text on the line with the opening
            /// quote, this will be empty and the first newline, if any, will be in a text section.
            pub newline:  Option<token::Newline<'s>>,
            pub elements: Vec<TextElement<'s>>,
            pub close:    Option<token::TextEnd<'s>>,
            #[serde(skip)]
            #[reflect(skip)]
            pub closed:   bool,
        },
        /// A simple application, like `print "hello"`.
        App {
            pub func: Tree<'s>,
            pub arg: Tree<'s>,
        },
        /// An application using an argument name, like `summarize_transaction (price = 100)`.
        NamedApp {
            pub func:   Tree<'s>,
            pub open:   Option<token::OpenSymbol<'s>>,
            pub name:   token::Ident<'s>,
            pub equals: token::Operator<'s>,
            pub arg:    Tree<'s>,
            pub close:  Option<token::CloseSymbol<'s>>,
        },
        /// Application of an operator, like `a + b`. The left or right operands might be missing,
        /// thus creating an operator section like `a +`, `+ b`, or simply `+`. See the
        /// [`OprSectionBoundary`] variant to learn more about operator section scope.
        OprApp {
            pub lhs: Option<Tree<'s>>,
            pub opr: OperatorOrError<'s>,
            pub rhs: Option<Tree<'s>>,
        },
        /// Application of a unary operator, like `-a` or `~handler`. It is a syntax error for `rhs`
        /// to be `None`.
        UnaryOprApp {
            pub opr: token::Operator<'s>,
            pub rhs: Option<Tree<'s>>,
        },
        /// Application of the autoscope operator to an identifier, e.g. `..True`.
        AutoscopedIdentifier {
            pub opr: token::Operator<'s>,
            pub ident: token::Ident<'s>,
        },
        /// Defines the point where operator sections should be expanded to lambdas. Let's consider
        /// the expression `map (.sum 1)`. It should be desugared to `map (x -> x.sum 1)`, not to
        /// `map ((x -> x.sum) 1)`. The expression `.sum` will be parsed as operator section
        /// ([`OprApp`] with left operand missing), and the [`OprSectionBoundary`] will be placed
        /// around the whole `.sum 1` expression.
        OprSectionBoundary {
            pub arguments: u32,
            pub ast:       Tree<'s>,
        },
        TemplateFunction {
            pub arguments: u32,
            pub ast:       Tree<'s>,
        },
        /// An application of a multi-segment function, such as `if ... then ... else ...`. Each
        /// segment starts with a token and contains an expression. Some multi-segment functions can
        /// have a prefix, an expression that is argument of the function, but is placed before the
        /// first token. Lambda is a good example for that. In an expression
        /// `Vector x y z -> x + y + z`, the `->` token is the beginning of the section, the
        /// `x + y + z` is the section body, and `Vector x y z` is the prefix of this function
        /// application.
        MultiSegmentApp {
            pub segments: NonEmptyVec<MultiSegmentAppSegment<'s>>,
        },
        /// A type definition; introduced by a line consisting of the keyword `type`, an identifier
        /// to be used as the name of the type, and zero or more specifications of type parameters.
        /// The following indented block contains two types of lines:
        /// - Type constructors definitions.
        /// - Bindings, defining either methods or type methods.
        TypeDef {
            pub keyword: token::Ident<'s>,
            pub name:    token::Ident<'s>,
            pub params:  Vec<ArgumentDefinition<'s>>,
            pub body:    Vec<block::Line<'s>>,
        },
        /// A variable assignment, like `foo = bar 23`.
        Assignment {
            /// The pattern which should be unified with the expression.
            pub pattern: Tree<'s>,
            /// The `=` token.
            pub equals: token::Operator<'s>,
            /// The expression initializing the value(s) in the pattern.
            pub expr: Tree<'s>,
        },
        /// A function definition, like `add x y = x + y`.
        Function {
            /// The (qualified) name to which the function should be bound.
            pub name: Tree<'s>,
            /// The argument patterns.
            pub args: Vec<ArgumentDefinition<'s>>,
            /// An optional specification of return type, like `-> Integer`.
            pub returns: Option<ReturnSpecification<'s>>,
            /// The `=` token.
            pub equals: token::Operator<'s>,
            /// The body, which will typically be an inline expression or a `BodyBlock` expression.
            /// It is an error for this to be empty.
            pub body: Option<Tree<'s>>,
        },
        /// A foreign function definition.
        ForeignFunction {
            /// The `foreign` keyword.
            pub foreign:  token::Ident<'s>,
            /// The function's language.
            pub language: token::Ident<'s>,
            /// The name to which the function should be bound.
            pub name:     token::Ident<'s>,
            /// The argument patterns.
            pub args:     Vec<ArgumentDefinition<'s>>,
            /// The `=` token.
            pub equals:   token::Operator<'s>,
            /// The body, which is source code for the specified language.
            pub body:     Tree<'s>,
        },
        /// An import statement.
        Import {
            pub polyglot: Option<MultiSegmentAppSegment<'s>>,
            pub from:     Option<MultiSegmentAppSegment<'s>>,
            pub import:   MultiSegmentAppSegment<'s>,
            pub all:      Option<token::Ident<'s>>,
            #[reflect(rename = "as")]
            pub as_:      Option<MultiSegmentAppSegment<'s>>,
            pub hiding:   Option<MultiSegmentAppSegment<'s>>,
        },
        /// An export statement.
        Export {
            pub from:   Option<MultiSegmentAppSegment<'s>>,
            pub export: MultiSegmentAppSegment<'s>,
            pub all:    Option<token::Ident<'s>>,
            #[reflect(rename = "as")]
            pub as_:    Option<MultiSegmentAppSegment<'s>>,
            pub hiding: Option<MultiSegmentAppSegment<'s>>,
        },
        /// An expression grouped by matched parentheses.
        Group {
            pub open:  Option<token::OpenSymbol<'s>>,
            pub body:  Option<Tree<'s>>,
            pub close: Option<token::CloseSymbol<'s>>,
        },
        /// Statement declaring the type of a variable.
        TypeSignature {
            /// (Qualified) name of the item whose type is being declared.
            pub variable: Tree<'s>,
            /// The `:` token.
            pub operator: token::Operator<'s>,
            /// The variable's type.
            #[reflect(rename = "type")]
            pub type_:    Tree<'s>,
        },
        /// An expression with explicit type information attached.
        TypeAnnotated {
            /// The expression whose type is being annotated.
            pub expression: Tree<'s>,
            /// The `:` token.
            pub operator: token::Operator<'s>,
            /// The expression's type.
            #[reflect(rename = "type")]
            pub type_: Tree<'s>,
        },
        /// A `case _ of` pattern-matching expression.
        CaseOf {
            pub case:       token::Ident<'s>,
            pub expression: Option<Tree<'s>>,
            pub of:         token::Ident<'s>,
            pub cases:      Vec<CaseLine<'s>>,
        },
        /// A lambda expression.
        Lambda {
            pub operator: token::Operator<'s>,
            pub arrow: Option<Tree<'s>>,
        },
        /// An array literal.
        Array {
            pub left:  token::OpenSymbol<'s>,
            pub first: Option<Tree<'s>>,
            pub rest:  Vec<OperatorDelimitedTree<'s>>,
            pub right: token::CloseSymbol<'s>,
        },
        /// A tuple literal.
        Tuple {
            pub left:  token::OpenSymbol<'s>,
            pub first: Option<Tree<'s>>,
            pub rest:  Vec<OperatorDelimitedTree<'s>>,
            pub right: token::CloseSymbol<'s>,
        },
        /// An expression preceded by an annotation. For example:
        /// ```enso
        /// @on_problems Problem_Behavior.get_widget_attribute
        /// Table.select_columns : Vector Text | Column_Selector -> Boolean -> Problem_Behavior -> Table
        /// ```
        Annotated {
            pub token:      token::Operator<'s>,
            pub annotation: token::Ident<'s>,
            pub argument:   Option<Tree<'s>>,
            pub newlines:   Vec<token::Newline<'s>>,
            pub expression: Option<Tree<'s>>,
        },
        /// An expression preceded by a special built-in annotation, e.g. `@Tail_Call foo 4`.
        AnnotatedBuiltin {
            pub token:      token::Operator<'s>,
            pub annotation: token::Ident<'s>,
            pub newlines:   Vec<token::Newline<'s>>,
            pub expression: Option<Tree<'s>>,
        },
        /// An expression preceded by a doc comment.
        Documented {
            /// The documentation.
            pub documentation: DocComment<'s>,
            /// The item being documented.
            pub expression: Option<Tree<'s>>,
        },
        /// Defines a type constructor.
        ConstructorDefinition {
            /// The identifier naming the type constructor.
            pub constructor:   token::Ident<'s>,
            /// The arguments the type constructor accepts, specified inline.
            pub arguments:     Vec<ArgumentDefinition<'s>>,
            /// The arguments the type constructor accepts, specified on their own lines.
            pub block:         Vec<ArgumentDefinitionLine<'s>>,
        },
    }
}};}


macro_rules! generate_variant_constructors {
    (
        $(#$enum_meta:tt)*
        pub enum $enum:ident<'s> {
            $(
                $(#$variant_meta:tt)*
                $variant:ident $({$($(#$field_meta:tt)* pub $field:ident : $field_ty:ty),* $(,)? })?
            ),* $(,)?
        }
    ) => { paste! {
        impl<'s> Tree<'s> {
            $(
                /// Constructor.
                pub fn [<$variant:snake:lower>]($($(mut $field : $field_ty),*)?) -> Self {
                    let span = span_builder![$($($field),*)?];
                    Tree(span, $variant($($($field),*)?))
                }
            )*
        }
    }};
}

macro_rules! generate_ast_definition {
    ($($ts:tt)*) => {
        $($ts)*
        generate_variant_constructors!{$($ts)*}
    };
}

with_ast_definition!(generate_ast_definition());


// === Invalid ===

/// Error of parsing attached to an [`Tree`] node.
#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Reflect, Deserialize)]
#[allow(missing_docs)]
#[reflect(transparent)]
#[serde(from = "crate::serialization::Error")]
pub struct Error {
    #[serde(skip_deserializing)]
    pub message: Cow<'static, str>,
}

impl Error {
    /// Constructor.
    pub fn new(message: impl Into<Cow<'static, str>>) -> Self {
        let message = message.into();
        Self { message }
    }
}

impl<'s> Tree<'s> {
    /// Constructor.
    pub fn with_error(self, message: impl Into<Cow<'static, str>>) -> Self {
        Tree::invalid(Error::new(message), self)
    }
}

impl<'s> span::Builder<'s> for Error {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        span
    }
}


// === Argument blocks ===

/// An argument specification on its own line.
#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Reflect, Deserialize)]
pub struct ArgumentDefinitionLine<'s> {
    /// The token beginning the line.
    pub newline:  token::Newline<'s>,
    /// The argument definition, unless this is an empty line.
    pub argument: Option<ArgumentDefinition<'s>>,
}

impl<'s> span::Builder<'s> for ArgumentDefinitionLine<'s> {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        span.add(&mut self.newline).add(&mut self.argument)
    }
}


// === Text literals ===

/// A component of a text literal, within the quotation marks.
#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Reflect, Deserialize)]
pub enum TextElement<'s> {
    /// The text content of the literal. If it is multiline, the offset information may contain
    /// part of the content, after trimming appropriately.
    Section {
        /// The text content.
        text: token::TextSection<'s>,
    },
    /// An escaped character.
    Escape {
        /// The escape sequence.
        token: token::TextEscape<'s>,
    },
    /// A logical newline.
    Newline {
        /// The newline token. The semantics of a logical newline are independent of the specific
        /// characters in the input, which are generally platform-dependent.
        newline: token::Newline<'s>,
    },
    /// An interpolated section within a text literal.
    Splice {
        /// The opening ` character.
        open:       token::OpenSymbol<'s>,
        /// The interpolated expression.
        expression: Option<Tree<'s>>,
        /// The closing ` character.
        close:      token::CloseSymbol<'s>,
    },
}

impl<'s> span::Builder<'s> for TextElement<'s> {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        match self {
            TextElement::Section { text } => text.add_to_span(span),
            TextElement::Escape { token } => span.add(token),
            TextElement::Splice { open, expression, close } =>
                span.add(open).add(expression).add(close),
            TextElement::Newline { newline } => span.add(newline),
        }
    }
}


// === Documentation ===

/// A documentation comment.
#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Debug, Clone, Eq, PartialEq, Serialize, Reflect, Deserialize)]
pub struct DocComment<'s> {
    /// The comment-initiating token.
    pub open:     token::TextStart<'s>,
    /// The documentation text.
    pub elements: Vec<TextElement<'s>>,
    /// Empty lines between the comment and the item.
    pub newlines: Vec<token::Newline<'s>>,
}

impl<'s> span::Builder<'s> for DocComment<'s> {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        span.add(&mut self.open).add(&mut self.elements).add(&mut self.newlines)
    }
}


// === Number literals ===

#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Reflect, Deserialize)]
#[allow(missing_docs)]
pub struct FractionalDigits<'s> {
    /// The dot operator.
    pub dot:    token::Operator<'s>,
    /// The decimal digits after the dot.
    pub digits: token::Digits<'s>,
}

impl<'s> span::Builder<'s> for FractionalDigits<'s> {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        span.add(&mut self.dot).add(&mut self.digits)
    }
}


// === Functions ===

/// A function argument definition.
#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Reflect, Deserialize)]
pub struct ArgumentDefinition<'s> {
    /// Opening parenthesis (outer).
    pub open:       Option<token::OpenSymbol<'s>>,
    /// Opening parenthesis (inner).
    pub open2:      Option<token::OpenSymbol<'s>>,
    /// An optional execution-suspension unary operator (~).
    pub suspension: Option<token::Operator<'s>>,
    /// The pattern being bound to an argument.
    pub pattern:    Tree<'s>,
    /// An optional type ascribed to an argument.
    #[reflect(rename = "type")]
    pub type_:      Option<ArgumentType<'s>>,
    /// Closing parenthesis (inner).
    pub close2:     Option<token::CloseSymbol<'s>>,
    /// An optional default value for an argument.
    pub default:    Option<ArgumentDefault<'s>>,
    /// Closing parenthesis (outer).
    pub close:      Option<token::CloseSymbol<'s>>,
}

impl<'s> span::Builder<'s> for ArgumentDefinition<'s> {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        span.add(&mut self.open)
            .add(&mut self.open2)
            .add(&mut self.suspension)
            .add(&mut self.pattern)
            .add(&mut self.type_)
            .add(&mut self.close2)
            .add(&mut self.default)
            .add(&mut self.close)
    }
}

/// A default value specification in a function argument definition.
#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Reflect, Deserialize)]
pub struct ArgumentDefault<'s> {
    /// The `=` token.
    pub equals:     token::Operator<'s>,
    /// The default value.
    pub expression: Tree<'s>,
}

impl<'s> span::Builder<'s> for ArgumentDefault<'s> {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        span.add(&mut self.equals).add(&mut self.expression)
    }
}

/// A type ascribed to an argument definition.
#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Reflect, Deserialize)]
pub struct ArgumentType<'s> {
    /// The `:` token.
    pub operator: token::Operator<'s>,
    /// The type.
    #[reflect(rename = "type")]
    pub type_:    Tree<'s>,
}

impl<'s> span::Builder<'s> for ArgumentType<'s> {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        span.add(&mut self.operator).add(&mut self.type_)
    }
}

/// A function return type specification.
#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Reflect, Deserialize)]
pub struct ReturnSpecification<'s> {
    /// The `->` operator.
    pub arrow:  token::Operator<'s>,
    /// The function's return type.
    #[reflect(rename = "type")]
    pub r#type: Tree<'s>,
}

impl<'s> span::Builder<'s> for ReturnSpecification<'s> {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        span.add(&mut self.arrow).add(&mut self.r#type)
    }
}


// === CaseOf ===

/// A line that may contain a case-expression in a case-of expression.
#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize, Reflect, Deserialize)]
pub struct CaseLine<'s> {
    /// The token beginning the line. This will always be present, unless the first case-expression
    /// is on the same line as the initial case-of.
    pub newline: Option<token::Newline<'s>>,
    /// The case-expression, if present.
    pub case:    Option<Case<'s>>,
}

impl<'s> CaseLine<'s> {
    /// Return a mutable reference to the `left_offset` of this object (which will actually belong
    /// to one of the object's children, if it has any).
    pub fn left_offset_mut(&mut self) -> Option<&mut Offset<'s>> {
        self.newline
            .as_mut()
            .map(|t| &mut t.left_offset)
            .or_else(|| self.case.as_mut().and_then(Case::left_offset_mut))
    }
}

impl<'s> span::Builder<'s> for CaseLine<'s> {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        span.add(&mut self.newline).add(&mut self.case)
    }
}

/// A case-expression in a case-of expression.
#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize, Reflect, Deserialize)]
pub struct Case<'s> {
    /// Documentation, if present.
    pub documentation: Option<DocComment<'s>>,
    /// The pattern being matched. It is an error for this to be absent.
    pub pattern:       Option<Tree<'s>>,
    /// Token.
    pub arrow:         Option<token::Operator<'s>>,
    /// The expression associated with the pattern. It is an error for this to be empty.
    pub expression:    Option<Tree<'s>>,
}

impl<'s> Case<'s> {
    /// Return a mutable reference to the `left_offset` of this object (which will actually belong
    /// to one of the object's children, if it has any).
    pub fn left_offset_mut(&mut self) -> Option<&mut Offset<'s>> {
        None.or_else(|| self.documentation.as_mut().map(|t| &mut t.open.left_offset))
            .or_else(|| self.pattern.as_mut().map(|t| &mut t.span.left_offset))
            .or_else(|| self.arrow.as_mut().map(|t| &mut t.left_offset))
            .or_else(|| self.expression.as_mut().map(|e| &mut e.span.left_offset))
    }
}

impl<'s> span::Builder<'s> for Case<'s> {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        span.add(&mut self.documentation)
            .add(&mut self.pattern)
            .add(&mut self.arrow)
            .add(&mut self.expression)
    }
}


// === OprApp ===

/// Operator or [`MultipleOperatorError`].
pub type OperatorOrError<'s> = Result<token::Operator<'s>, MultipleOperatorError<'s>>;

/// Error indicating multiple operators found next to each other, like `a + * b`.
#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Reflect, Deserialize)]
#[allow(missing_docs)]
pub struct MultipleOperatorError<'s> {
    pub operators: NonEmptyVec<token::Operator<'s>>,
}

impl<'s> span::Builder<'s> for MultipleOperatorError<'s> {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        self.operators.add_to_span(span)
    }
}

/// A sequence of one or more operators.
pub trait NonEmptyOperatorSequence<'s> {
    /// Return a reference to the first operator.
    fn first_operator(&self) -> &token::Operator<'s>;
    /// Return a mutable reference to the first operator.
    fn first_operator_mut(&mut self) -> &mut token::Operator<'s>;
}

impl<'s> NonEmptyOperatorSequence<'s> for OperatorOrError<'s> {
    fn first_operator(&self) -> &token::Operator<'s> {
        match self {
            Ok(opr) => opr,
            Err(oprs) => oprs.operators.first(),
        }
    }
    fn first_operator_mut(&mut self) -> &mut token::Operator<'s> {
        match self {
            Ok(opr) => opr,
            Err(oprs) => oprs.operators.first_mut(),
        }
    }
}


// === MultiSegmentApp ===

/// A segment of [`MultiSegmentApp`], like `if cond` in the `if cond then ok else fail` expression.
#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Reflect, Deserialize)]
#[allow(missing_docs)]
pub struct MultiSegmentAppSegment<'s> {
    pub header: Token<'s>,
    pub body:   Option<Tree<'s>>,
}

impl<'s> span::Builder<'s> for MultiSegmentAppSegment<'s> {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        span.add(&mut self.header).add(&mut self.body)
    }
}


// === Array and Tuple ===

/// A node following an operator.
#[cfg_attr(feature = "debug", derive(Visitor))]
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Reflect, Deserialize)]
pub struct OperatorDelimitedTree<'s> {
    /// The delimiting operator.
    pub operator: token::Operator<'s>,
    /// The expression. It is an error for this to be absent.
    pub body:     Option<Tree<'s>>,
}

impl<'s> span::Builder<'s> for OperatorDelimitedTree<'s> {
    fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
        span.add(&mut self.operator).add(&mut self.body)
    }
}



// ====================================
// === Tree-construction operations ===
// ====================================

/// Join two nodes with a new node appropriate for their types.
///
/// For most input types, this simply constructs an `App`; however, for some operand types
/// application has special semantics.
pub fn apply<'s>(mut func: Tree<'s>, mut arg: Tree<'s>) -> Tree<'s> {
    match (&mut *func.variant, &mut *arg.variant) {
        (Variant::Annotated(func_ @ Annotated { argument: None, .. }), _) => {
            func.span.code_length += arg.span.length_including_whitespace();
            func_.argument = maybe_apply(mem::take(&mut func_.argument), arg).into();
            func
        }
        (Variant::AnnotatedBuiltin(func_), _) => {
            func.span.code_length += arg.span.length_including_whitespace();
            func_.expression = maybe_apply(mem::take(&mut func_.expression), arg).into();
            func
        }
        (
            Variant::OprApp(OprApp { lhs: Some(_), opr: Ok(_), rhs: rhs @ None }),
            Variant::ArgumentBlockApplication(ArgumentBlockApplication { lhs: None, arguments }),
        ) => {
            func.span.code_length += arg.span.length_including_whitespace();
            *rhs = block::body_from_lines(mem::take(arguments)).into();
            func
        }
        (_, Variant::ArgumentBlockApplication(block)) if block.lhs.is_none() => {
            let code =
                func.span.code_length + arg.span.left_offset.code.length() + arg.span.code_length;
            arg.span.code_length = code;
            let func_left_offset = func.span.left_offset.take_as_prefix();
            let arg_left_offset = mem::replace(&mut arg.span.left_offset, func_left_offset);
            if let Some(first) = block.arguments.first_mut() {
                first.newline.left_offset += arg_left_offset;
            }
            block.lhs = Some(func);
            arg
        }
        (_, Variant::OperatorBlockApplication(block)) if block.lhs.is_none() => {
            let code =
                func.span.code_length + arg.span.left_offset.code.length() + arg.span.code_length;
            arg.span.code_length = code;
            let func_left_offset = func.span.left_offset.take_as_prefix();
            let arg_left_offset = mem::replace(&mut arg.span.left_offset, func_left_offset);
            if let Some(first) = block.expressions.first_mut() {
                first.newline.left_offset += arg_left_offset;
            }
            block.lhs = Some(func);
            arg
        }
        (_, Variant::OprApp(OprApp { lhs: Some(lhs), opr: Ok(opr), rhs: Some(rhs) }))
            if opr.properties.is_assignment()
                && let Variant::Ident(lhs) = &*lhs.variant =>
        {
            let mut lhs = lhs.token.clone();
            lhs.left_offset += arg.span.left_offset;
            Tree::named_app(func, None, lhs, opr.clone(), rhs.clone(), None)
        }
        (_, Variant::Group(Group { open: Some(open), body: Some(body), close: Some(close) }))
            if let box Variant::OprApp(OprApp { lhs: Some(lhs), opr: Ok(opr), rhs: Some(rhs) }) =
                &body.variant
                && opr.properties.is_assignment()
                && let Variant::Ident(lhs) = &*lhs.variant =>
        {
            let mut open = open.clone();
            open.left_offset += arg.span.left_offset;
            let open = Some(open);
            let close = Some(close.clone());
            Tree::named_app(func, open, lhs.token.clone(), opr.clone(), rhs.clone(), close)
        }
        _ => Tree::app(func, arg),
    }
}

fn maybe_apply<'s>(f: Option<Tree<'s>>, x: Tree<'s>) -> Tree<'s> {
    match f {
        Some(f) => apply(f, x),
        None => x,
    }
}

/// Join two text literals, merging contents as appropriate to each field.
pub fn join_text_literals<'s>(
    lhs: &mut TextLiteral<'s>,
    rhs: &mut TextLiteral<'s>,
    lhs_span: &mut Span<'s>,
    rhs_span: Span<'s>,
) {
    lhs_span.code_length += rhs_span.length_including_whitespace();
    match rhs.elements.first_mut() {
        Some(TextElement::Section { text }) => text.left_offset += rhs_span.left_offset,
        Some(TextElement::Escape { token }) => token.left_offset += rhs_span.left_offset,
        Some(TextElement::Splice { open, .. }) => open.left_offset += rhs_span.left_offset,
        Some(TextElement::Newline { newline }) => newline.left_offset += rhs_span.left_offset,
        None => (),
    }
    if let Some(newline) = rhs.newline.take() {
        lhs.newline = newline.into();
    }
    lhs.elements.append(&mut rhs.elements);
    lhs.close = rhs.close.take();
    lhs.closed = rhs.closed;
}

/// Join two nodes with an operator, in a way appropriate for their types.
///
/// For most operands this will simply construct an `OprApp`; however, a non-operator block (i.e. an
/// `ArgumentBlock`) is reinterpreted as a `BodyBlock` when it appears in the RHS of an operator
/// expression.
pub fn apply_operator<'s>(
    mut lhs: Option<Tree<'s>>,
    opr: Vec<token::Operator<'s>>,
    mut rhs: Option<Tree<'s>>,
) -> Tree<'s> {
    let opr = match opr.len() {
        0 => return apply(lhs.unwrap(), rhs.unwrap()),
        1 => Ok(opr.into_iter().next().unwrap()),
        _ => Err(MultipleOperatorError { operators: NonEmptyVec::try_from(opr).unwrap() }),
    };
    if let Ok(opr_) = &opr
        && opr_.properties.is_token_joiner()
        && let Some(lhs_) = lhs.as_mut()
        && let Some(rhs_) = rhs.as_mut()
    {
        return match (&mut *lhs_.variant, &mut *rhs_.variant) {
            (
                Variant::Number(func_ @ Number { base: _, integer: None, fractional_digits: None }),
                Variant::Number(Number { base: None, integer, fractional_digits }),
            ) => {
                func_.integer = mem::take(integer);
                func_.fractional_digits = mem::take(fractional_digits);
                lhs_.span.code_length += rhs_.span.code_length;
                lhs.take().unwrap()
            }
            _ => {
                debug_assert!(false, "Unexpected use of token-joiner operator!");
                apply(lhs.take().unwrap(), rhs.take().unwrap())
            }
        };
    }
    if let Ok(opr_) = &opr
        && opr_.properties.is_special()
    {
        let tree = Tree::opr_app(lhs, opr, rhs);
        return tree.with_error("Invalid use of special operator.");
    }
    if let Ok(opr_) = &opr
        && opr_.properties.is_type_annotation()
    {
        return match (lhs, rhs) {
            (Some(lhs), Some(rhs)) => Tree::type_annotated(lhs, opr.unwrap(), rhs),
            (lhs, rhs) => {
                let invalid = Tree::opr_app(lhs, opr, rhs);
                invalid.with_error("`:` operator must be applied to two operands.")
            }
        };
    }
    if let Ok(opr_) = &opr
        && !opr_.properties.can_form_section()
        && lhs.is_none()
        && rhs.is_none()
    {
        let error = format!("Operator `{opr:?}` must be applied to two operands.");
        let invalid = Tree::opr_app(lhs, opr, rhs);
        return invalid.with_error(error);
    }
    if let Ok(opr) = &opr
        && opr.properties.is_decimal()
        && let Some(lhs) = lhs.as_mut()
        && let box Variant::Number(lhs_) = &mut lhs.variant
        && lhs_.fractional_digits.is_none()
        && let Some(rhs) = rhs.as_mut()
        && let box Variant::Number(Number {
            base: None,
            integer: Some(digits),
            fractional_digits: None,
        }) = &mut rhs.variant
    {
        let dot = opr.clone();
        let digits = digits.clone();
        lhs.span.code_length += dot.code.length() + rhs.span.code_length;
        lhs_.fractional_digits = Some(FractionalDigits { dot, digits });
        return lhs.clone();
    }
    if let Some(rhs_) = rhs.as_mut() {
        if let Variant::ArgumentBlockApplication(block) = &mut *rhs_.variant {
            if block.lhs.is_none() {
                if let Some(first) = block.arguments.first_mut() {
                    first.newline.left_offset += rhs_.span.left_offset.take_as_prefix();
                }
                let ArgumentBlockApplication { lhs: _, arguments } = block;
                let arguments = mem::take(arguments);
                *rhs_ = block::body_from_lines(arguments);
            }
        }
    }
    Tree::opr_app(lhs, opr, rhs)
}

/// Apply a unary operator to an operand.
///
/// For most inputs this will simply construct a `UnaryOprApp`; however, some operators are special.
pub fn apply_unary_operator<'s>(opr: token::Operator<'s>, rhs: Option<Tree<'s>>) -> Tree<'s> {
    if opr.properties.is_annotation()
        && let Some(Tree { variant: box Variant::Ident(Ident { token }), .. }) = rhs
    {
        return match token.is_type {
            true => Tree::annotated_builtin(opr, token, vec![], None),
            false => Tree::annotated(opr, token, None, vec![], None),
        };
    }
    if opr.properties.is_autoscope()
        && let Some(rhs) = rhs
    {
        return if let box Variant::Ident(Ident { mut token }) = rhs.variant {
            let applied_to_type = token.variant.is_type;
            token.left_offset = rhs.span.left_offset;
            let autoscope_application = Tree::autoscoped_identifier(opr, token);
            return if applied_to_type {
                autoscope_application
            } else {
                autoscope_application.with_error(
                    "The auto-scope operator may only be applied to a capitalized identifier.",
                )
            };
        } else {
            Tree::unary_opr_app(opr, Some(rhs))
                .with_error("The auto-scope operator (..) may only be applied to an identifier.")
        };
    }
    if !opr.properties.can_form_section() && rhs.is_none() {
        let error = format!("Operator `{opr:?}` must be applied to an operand.");
        let invalid = Tree::unary_opr_app(opr, rhs);
        return invalid.with_error(error);
    }
    Tree::unary_opr_app(opr, rhs)
}

/// Create an AST node for a token.
pub fn to_ast(token: Token) -> Tree {
    match token.variant {
        token::Variant::Ident(ident) => token.with_variant(ident).into(),
        token::Variant::Digits(number) =>
            Tree::number(None, Some(token.with_variant(number)), None),
        token::Variant::NumberBase(base) =>
            Tree::number(Some(token.with_variant(base)), None, None),
        token::Variant::TextStart(open) =>
            Tree::text_literal(Some(token.with_variant(open)), default(), default(), default(), default()),
        token::Variant::TextSection(section) => {
            let section = TextElement::Section { text: token.with_variant(section) };
            Tree::text_literal(default(), default(), vec![section], default(), default())
        }
        token::Variant::TextEscape(escape) => {
            let token = token.with_variant(escape);
            let section = TextElement::Escape { token };
            Tree::text_literal(default(), default(), vec![section], default(), default())
        }
        token::Variant::TextEnd(_) if token.code.is_empty() =>
            Tree::text_literal(default(), default(), default(), default(), true),
        token::Variant::TextEnd(close) =>
            Tree::text_literal(default(), default(), default(), Some(token.with_variant(close)), true),
        token::Variant::TextInitialNewline(_) =>
            Tree::text_literal(default(), Some(token::newline(token.left_offset, token.code)), default(), default(), default()),
        token::Variant::TextNewline(_) => {
            let newline = token::newline(token.left_offset, token.code);
            let newline = TextElement::Newline { newline };
            Tree::text_literal(default(), default(), vec![newline], default(), default())
        }
        token::Variant::Wildcard(wildcard) => Tree::wildcard(token.with_variant(wildcard), default()),
        token::Variant::SuspendedDefaultArguments(t) => Tree::suspended_default_arguments(token.with_variant(t)),
        token::Variant::OpenSymbol(s) =>
            Tree::group(Some(token.with_variant(s)), default(), default()).with_error("Unmatched delimiter"),
        token::Variant::CloseSymbol(s) =>
            Tree::group(default(), default(), Some(token.with_variant(s))).with_error("Unmatched delimiter"),
        // These should be unreachable: They are handled when assembling items into blocks,
        // before parsing proper.
        token::Variant::Newline(_)
        | token::Variant::BlockStart(_)
        | token::Variant::BlockEnd(_)
        // This should be unreachable: `Precedence::resolve` doesn't calls `to_ast` for operators.
        | token::Variant::Operator(_)
        | token::Variant::Private(_)
        // Map an error case in the lexer to an error in the AST.
        | token::Variant::Invalid(_) => {
            let message = format!("Unexpected token: {token:?}");
            let ident = token::variant::Ident(false, 0, false, false, false);
            let value = Tree::ident(token.with_variant(ident));
            Tree::with_error(value, message)
        }
    }
}

impl<'s> From<token::Ident<'s>> for Tree<'s> {
    fn from(token: token::Ident<'s>) -> Self {
        Tree::ident(token)
    }
}



// ================
// === Visitors ===
// ================

/// The visitor pattern for [`AST`].
///
/// # Visitor traits
/// There are several visitor traits defined allowing for traversal of specific AST elements, such
/// as AST nodes ([`TreeVisitor`]), span information ([`SpanVisitor`]), and AST nodes or tokens
/// altogether ([`ItemVisitor`]). A visitor is a struct that is modified when traversing the target
/// elements. Visitors are also capable of tracking when they entered or exited a nested
/// [`Tree`] structure, and they can control how deep the traversal should be performed.
///
/// # Visitable traits
/// This macro also defines visitable traits, such as [`TreeVisitable`] or [`SpanVisitable`], which
/// provide [`Tree`] elements with such functions as [`visit`] or [`visit_span`]. These functions
/// let you run visitors. However, as defining a visitor is relatively complex, a traversal function
/// [`map`] is provided.
///
/// # Generalization of the implementation
/// The current implementation bases on a few non-generic traits. One might define a way better
/// implementation (causing way less boilerplate), such as:
/// ```text
/// pub trait Visitor<T> {
///     fn visit(&mut self, elem: &T);
/// }
/// ```
/// Such definition could be implemented for every [`Tree`] node (the [`T`] parameter).
/// Unfortunately, due to Rust compiler errors, Rust is not able to compile such a definition. We
/// could move to it as soon as this error gets resolved:
/// https://github.com/rust-lang/rust/issues/96634.
#[allow(missing_docs)]
#[cfg(feature = "debug")]
pub trait Visitor {}

/// The visitor trait allowing for [`Item`] traversal.
#[allow(missing_docs)]
#[cfg(feature = "debug")]
pub trait ItemVisitor<'s, 'a>: Visitor {
    fn visit_item(&mut self, ast: item::Ref<'s, 'a>) -> bool;
}

macro_rules! define_visitor {
    (
        $name:ident,
        $visit:ident,
        $visitor:ident,
        $visitable:ident
    ) => {
        /// The visitable trait. See documentation of [`define_visitor`] to learn more.
        #[cfg(feature = "debug")]
        #[allow(missing_docs)]
        pub trait $visitable<'s, 'a> {
            fn $visit<V: $visitor<'s, 'a>>(&'a self, _visitor: &mut V) {}
        }

        #[cfg(feature = "debug")]
        impl<'s, 'a, T: $visitable<'s, 'a>> $visitable<'s, 'a> for Option<T> {
            fn $visit<V: $visitor<'s, 'a>>(&'a self, visitor: &mut V) {
                if let Some(elem) = self {
                    $visitable::$visit(elem, visitor)
                }
            }
        }

        #[cfg(feature = "debug")]
        impl<'s, 'a, T: $visitable<'s, 'a>, E: $visitable<'s, 'a>> $visitable<'s, 'a>
            for Result<T, E>
        {
            fn $visit<V: $visitor<'s, 'a>>(&'a self, visitor: &mut V) {
                match self {
                    Ok(elem) => $visitable::$visit(elem, visitor),
                    Err(elem) => $visitable::$visit(elem, visitor),
                }
            }
        }

        #[cfg(feature = "debug")]
        impl<'s, 'a, T: $visitable<'s, 'a>> $visitable<'s, 'a> for Vec<T> {
            fn $visit<V: $visitor<'s, 'a>>(&'a self, visitor: &mut V) {
                self.iter().map(|t| $visitable::$visit(t, visitor)).for_each(drop);
            }
        }

        #[cfg(feature = "debug")]
        impl<'s, 'a, T: $visitable<'s, 'a>> $visitable<'s, 'a> for NonEmptyVec<T> {
            fn $visit<V: $visitor<'s, 'a>>(&'a self, visitor: &mut V) {
                self.iter().map(|t| $visitable::$visit(t, visitor)).for_each(drop);
            }
        }
    };
}

define_visitor!(Item, visit_item, ItemVisitor, ItemVisitable);


// === Trait Implementations for Simple Leaf Types ===

macro_rules! spanless_leaf_impls {
    ($ty:ty) => {
        #[cfg(feature = "debug")]
        impl<'a, 's> ItemVisitable<'s, 'a> for $ty {}
        impl<'s> span::Builder<'s> for $ty {
            fn add_to_span(&mut self, span: Span<'s>) -> Span<'s> {
                span
            }
        }
    };
}

spanless_leaf_impls!(u32);
spanless_leaf_impls!(bool);
spanless_leaf_impls!(VisibleOffset);
spanless_leaf_impls!(Cow<'static, str>);



// === ItemVisitable special cases ===

#[cfg(feature = "debug")]
impl<'s, 'a> ItemVisitable<'s, 'a> for Tree<'s> {
    fn visit_item<V: ItemVisitor<'s, 'a>>(&'a self, visitor: &mut V) {
        if visitor.visit_item(item::Ref::Tree(self)) {
            self.variant.visit_item(visitor)
        }
    }
}

#[cfg(feature = "debug")]
impl<'s: 'a, 'a, T: 'a> ItemVisitable<'s, 'a> for Token<'s, T>
where &'a Token<'s, T>: Into<token::Ref<'s, 'a>>
{
    fn visit_item<V: ItemVisitor<'s, 'a>>(&'a self, visitor: &mut V) {
        visitor.visit_item(item::Ref::Token(self.into()));
    }
}



// ==========================
// === CodePrinterVisitor ===
// ==========================

/// A visitor collecting code representation of AST nodes.
#[cfg(feature = "debug")]
#[derive(Debug, Default)]
#[allow(missing_docs)]
struct CodePrinterVisitor {
    pub code: String,
}

#[cfg(feature = "debug")]
impl Visitor for CodePrinterVisitor {}
#[cfg(feature = "debug")]
impl<'s, 'a> ItemVisitor<'s, 'a> for CodePrinterVisitor {
    fn visit_item(&mut self, item: item::Ref<'s, 'a>) -> bool {
        match item {
            item::Ref::Tree(tree) => self.code.push_str(&tree.span.left_offset.code),
            item::Ref::Token(token) => {
                self.code.push_str(&token.left_offset.code);
                self.code.push_str(token.code);
            }
        }
        true
    }
}

#[cfg(feature = "debug")]
impl<'s> Tree<'s> {
    /// Code generator of this AST.
    pub fn code(&self) -> String {
        let mut visitor = CodePrinterVisitor::default();
        self.visit_item(&mut visitor);
        visitor.code
    }

    /// Return source code of this AST, excluding initial whitespace.
    pub fn trimmed_code(&self) -> String {
        let mut visitor = CodePrinterVisitor::default();
        self.variant.visit_item(&mut visitor);
        visitor.code
    }
}



// =====================
// === ItemFnVisitor ===
// =====================

#[cfg(feature = "debug")]
impl<'s> Tree<'s> {
    /// Apply the provided function to each [`Token`] or [`Tree`] that is a child of the node.
    pub fn visit_items<F>(&self, f: F)
    where F: for<'a> FnMut(item::Ref<'s, 'a>) {
        struct ItemFnVisitor<F> {
            f: F,
        }
        impl<F> Visitor for ItemFnVisitor<F> {}
        impl<'a, 's: 'a, F> ItemVisitor<'s, 'a> for ItemFnVisitor<F>
        where F: FnMut(item::Ref<'s, 'a>)
        {
            fn visit_item(&mut self, item: item::Ref<'s, 'a>) -> bool {
                (self.f)(item);
                false
            }
        }
        self.variant.visit_item(&mut ItemFnVisitor { f });
    }

    /// Apply the provided function recursively to each [`Tree`] that is a descendant of the node.
    pub fn visit_trees<F>(&self, f: F)
    where F: for<'a> FnMut(&'a Tree<'s>) {
        struct ItemFnVisitor<F> {
            f: F,
        }
        impl<F> Visitor for ItemFnVisitor<F> {}
        impl<'a, 's: 'a, F> ItemVisitor<'s, 'a> for ItemFnVisitor<F>
        where F: FnMut(&'a Tree<'s>)
        {
            fn visit_item(&mut self, item: item::Ref<'s, 'a>) -> bool {
                if let item::Ref::Tree(tree) = item {
                    (self.f)(tree);
                }
                true
            }
        }
        self.variant.visit_item(&mut ItemFnVisitor { f });
    }
}
