package org.enso.interpreter.runtime.data.atom;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.profiles.BranchProfile;
import java.util.HashSet;
import java.util.Set;
import org.enso.interpreter.EnsoLanguage;
import org.enso.interpreter.runtime.callable.UnresolvedSymbol;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.data.EnsoObject;
import org.enso.interpreter.runtime.data.Type;
import org.enso.interpreter.runtime.data.text.Text;
import org.enso.interpreter.runtime.data.vector.ArrayLikeHelpers;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.error.WarningsLibrary;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;
import org.enso.interpreter.runtime.type.TypesGen;

/** A runtime representation of an Atom in Enso. */
@ExportLibrary(InteropLibrary.class)
@ExportLibrary(TypesLibrary.class)
public abstract class Atom implements EnsoObject {
  final AtomConstructor constructor;
  private Integer hashCode;

  /**
   * Creates a new Atom for a given constructor.
   *
   * @param constructor the Atom's constructor
   */
  Atom(AtomConstructor constructor) {
    this.constructor = constructor;
  }

  /**
   * Gets the Atom's constructor.
   *
   * @return the constructor for this Atom
   */
  public final AtomConstructor getConstructor() {
    return constructor;
  }

  public void setHashCode(int hashCode) {
    assert this.hashCode == null : "setHashCode must be called at most once";
    this.hashCode = hashCode;
  }

  public Integer getHashCode() {
    return hashCode;
  }

  @CompilerDirectives.TruffleBoundary
  private void toString(StringBuilder builder, boolean shouldParen, int depth) {
    if (depth <= 0) {
      builder.append("...");
      return;
    }
    boolean parensNeeded = shouldParen && constructor.getArity() > 0;
    if (parensNeeded) {
      builder.append("(");
    }
    builder.append(getConstructor().getName());
    for (var i = 0; i < constructor.getArity(); i++) {
      var obj = StructsLibrary.getUncached().getField(this, i);
      builder.append(" ");
      if (obj instanceof Atom atom) {
        atom.toString(builder, true, depth - 1);
      } else {
        builder.append(obj);
      }
    }
    if (parensNeeded) {
      builder.append(")");
    }
  }

  /**
   * Creates a textual representation of this Atom, useful for debugging.
   *
   * @return a textual representation of this Atom.
   */
  @Override
  public String toString() {
    return toString(null, 10, null, null);
  }

  @CompilerDirectives.TruffleBoundary
  private String toString(String prefix, int depth, String suffix, Object obj) {
    StringBuilder sb = new StringBuilder();
    if (prefix != null) {
      sb.append(prefix);
    }
    toString(sb, false, depth);
    if (suffix != null) {
      sb.append(suffix);
    }
    if (obj != null) {
      var errorMessage =
          switch (obj) {
            case Function fn -> fn.toString(false);
            default -> InteropLibrary.getUncached().toDisplayString(obj);
          };
      if (errorMessage != null) {
        sb.append(errorMessage);
      } else {
        sb.append("Nothing");
      }
    }
    return sb.toString();
  }

  @CompilerDirectives.TruffleBoundary
  private String toString(int depth) {
    StringBuilder sb = new StringBuilder();
    toString(sb, false, depth);
    return sb.toString();
  }

  @ExportMessage
  boolean hasMembers() {
    return true;
  }

  /**
   * Returns a polyglot list of all the public members of Atom. A member is any method on the atom.
   * A member is public if it is not project-private.
   */
  @ExportMessage
  @CompilerDirectives.TruffleBoundary
  EnsoObject getMembers(boolean includeInternal) throws UnsupportedMessageException {
    Set<Function> members =
        constructor.getDefinitionScope().getMethodsForType(constructor.getType());
    Set<Function> allFuncMembers = new HashSet<>();
    if (members != null) {
      allFuncMembers.addAll(members);
    }
    members = constructor.getType().getDefinitionScope().getMethodsForType(constructor.getType());
    if (members != null) {
      allFuncMembers.addAll(members);
    }
    String[] publicMembers =
        allFuncMembers.stream()
            .filter(method -> !method.getSchema().isProjectPrivate())
            .map(Function::getName)
            .distinct()
            .toArray(String[]::new);
    return ArrayLikeHelpers.wrapStrings(publicMembers);
  }

  protected boolean isMethodProjectPrivate(Type type, String methodName) {
    Function method = constructor.getDefinitionScope().getMethodForType(type, methodName);
    if (method != null) {
      return method.getSchema().isProjectPrivate();
    }
    method = constructor.getType().getDefinitionScope().getMethodForType(type, methodName);
    return method != null && method.getSchema().isProjectPrivate();
  }

  /** A member is invocable if it is a method on the Atom and it is public. */
  @ExportMessage
  @CompilerDirectives.TruffleBoundary
  final boolean isMemberInvocable(String member) {
    var type = constructor.getType();
    Set<String> members = constructor.getDefinitionScope().getMethodNamesForType(type);
    if (members != null && members.contains(member)) {
      return !isMethodProjectPrivate(type, member);
    }
    members = type.getDefinitionScope().getMethodNamesForType(type);
    if (members != null && members.contains(member)) {
      return !isMethodProjectPrivate(type, member);
    }
    return false;
  }

  /** A member is readable if it is an atom constructor and if the atom constructor is public. */
  @ExportMessage
  @ExplodeLoop
  final boolean isMemberReadable(String member) {
    if (hasProjectPrivateConstructor()) {
      return false;
    }
    for (int i = 0; i < constructor.getArity(); i++) {
      if (member.equals(constructor.getFields()[i].getName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reads a field of the atom.
   *
   * @param member An identifier of a field.
   * @return Value of the member.
   * @throws UnknownIdentifierException If an unknown field is requested.
   * @throws UnsupportedMessageException If the atom constructor is project-private, and thus all
   *     the fields are project-private.
   */
  @ExportMessage
  @ExplodeLoop
  final Object readMember(String member, @CachedLibrary(limit = "3") StructsLibrary structs)
      throws UnknownIdentifierException, UnsupportedMessageException {
    if (hasProjectPrivateConstructor()) {
      throw UnsupportedMessageException.create();
    }
    for (int i = 0; i < constructor.getArity(); i++) {
      if (member.equals(constructor.getFields()[i].getName())) {
        return structs.getField(this, i);
      }
    }
    throw UnknownIdentifierException.create(member);
  }

  /** Only public (non project-private) methods can be invoked. */
  @ExportMessage
  static class InvokeMember {

    static UnresolvedSymbol buildSym(AtomConstructor cons, String name) {
      return UnresolvedSymbol.build(name, cons.getDefinitionScope());
    }

    @Specialization(
        guards = {
          "receiver.getConstructor() == cachedConstructor",
          "member.equals(cachedMember)",
          "!isProjectPrivate(cachedConstructor, cachedMember)"
        },
        limit = "3")
    static Object doCached(
        Atom receiver,
        String member,
        Object[] arguments,
        @Cached(value = "receiver.getConstructor()") AtomConstructor cachedConstructor,
        @Cached(value = "member") String cachedMember,
        @Cached(value = "buildSym(cachedConstructor, cachedMember)") UnresolvedSymbol cachedSym,
        @CachedLibrary("cachedSym") InteropLibrary symbols)
        throws UnsupportedMessageException,
            ArityException,
            UnsupportedTypeException,
            UnknownIdentifierException {
      assert !isProjectPrivate(cachedConstructor, cachedMember);
      Object[] args = new Object[arguments.length + 1];
      args[0] = receiver;
      System.arraycopy(arguments, 0, args, 1, arguments.length);
      try {
        return symbols.execute(cachedSym, args);
      } catch (PanicException ex) {
        if (ex.getCause() instanceof UnknownIdentifierException interopEx) {
          throw interopEx;
        } else {
          throw ex;
        }
      }
    }

    @Specialization(replaces = "doCached")
    static Object doUncached(
        Atom receiver,
        String member,
        Object[] arguments,
        @CachedLibrary(limit = "1") InteropLibrary symbols)
        throws UnsupportedMessageException,
            ArityException,
            UnsupportedTypeException,
            UnknownIdentifierException {
      if (isProjectPrivate(receiver.getConstructor(), member)) {
        throw UnsupportedMessageException.create();
      }
      UnresolvedSymbol symbol = buildSym(receiver.getConstructor(), member);
      return doCached(
          receiver, member, arguments, receiver.getConstructor(), member, symbol, symbols);
    }

    @Idempotent
    @TruffleBoundary
    protected static boolean isProjectPrivate(AtomConstructor cons, String member) {
      Function method = cons.getDefinitionScope().getMethodForType(cons.getType(), member);
      if (method != null) {
        return method.getSchema().isProjectPrivate();
      }
      method = cons.getType().getDefinitionScope().getMethodForType(cons.getType(), member);
      return method != null && method.getSchema().isProjectPrivate();
    }
  }

  @ExportMessage
  Text toDisplayString(
      boolean allowSideEffects,
      @CachedLibrary("this") InteropLibrary atoms,
      @CachedLibrary(limit = "3") WarningsLibrary warnings,
      @CachedLibrary(limit = "3") InteropLibrary interop,
      @Cached BranchProfile handleError) {
    Object result = null;
    String msg;
    try {
      result = atoms.invokeMember(this, "to_text");
      if (warnings.hasWarnings(result)) {
        result = warnings.removeWarnings(result);
      }
      if (TypesGen.isDataflowError(result)) {
        msg = this.toString("Error in method `to_text` of [", 10, "]: ", result);
      } else if (TypesGen.isText(result)) {
        return TypesGen.asText(result);
      } else if (interop.isString(result)) {
        return Text.create(interop.asString(result));
      } else {
        msg =
            this.toString(
                "Error in method `to_text` of [", 10, "]: Expected Text but got ", result);
      }
    } catch (AbstractTruffleException
        | UnsupportedMessageException
        | ArityException
        | UnknownIdentifierException
        | UnsupportedTypeException panic) {
      handleError.enter();
      msg = this.toString("Panic in method `to_text` of [", 10, "]: ", panic);
    }
    return Text.create(msg);
  }

  @ExportMessage
  Class<EnsoLanguage> getLanguage() {
    return EnsoLanguage.class;
  }

  @ExportMessage
  boolean hasLanguage() {
    return true;
  }

  @ExportMessage
  boolean hasType() {
    return true;
  }

  @ExportMessage
  Type getType() {
    return getConstructor().getType();
  }

  @ExportMessage
  Type getMetaObject() {
    return getType();
  }

  @ExportMessage
  boolean hasMetaObject() {
    return true;
  }

  private boolean hasProjectPrivateConstructor() {
    return constructor.getType().isProjectPrivate();
  }
}
