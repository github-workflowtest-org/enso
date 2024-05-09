package org.enso.interpreter.util;

import java.util.List;
import java.util.Optional;
import scala.Option;
import scala.collection.Seq;
import scala.jdk.javaapi.CollectionConverters;
import scala.jdk.javaapi.OptionConverters;

/** Utility class for converting between Scala and Java basic classes. */
public class ScalaConversions {

  private ScalaConversions() {}

  /**
   * Converts a Scala {@link Option} to a Java {@link Optional}.
   *
   * @param option the scala option to convert
   * @return the corresponding java optional
   */
  public static <T> Optional<T> asJava(Option<T> option) {
    return OptionConverters.toJava(option);
  }

  /**
   * Converts a Scala {@link Seq} to a Java {@link List}.
   *
   * @param list the scala list to convert
   * @return the corresponding java list
   */
  public static <T> List<T> asJava(Seq<T> list) {
    return CollectionConverters.asJava(list);
  }

  @SuppressWarnings("unchecked")
  public static <T> scala.collection.immutable.List<T> nil() {
    return (scala.collection.immutable.List<T>) scala.collection.immutable.Nil$.MODULE$;
  }

  public static <T> scala.collection.immutable.List<T> cons(
      T head, scala.collection.immutable.List<T> tail) {
    return scala.collection.immutable.$colon$colon$.MODULE$.apply(head, tail);
  }

  public static <T> scala.collection.immutable.Seq<T> seq(List<T> list) {
    return CollectionConverters.asScala(list).toSeq();
  }

  /**
   * Create a Scala set from the provided elements.
   *
   * @param elems the set elements.
   * @return the immutable Scala set.
   */
  @SafeVarargs
  public static <T> scala.collection.immutable.Set<T> set(T... elems) {
    var s = new scala.collection.mutable.LinkedHashSet<T>();
    for (T elem : elems) s.add(elem);

    return s.toSet();
  }
}
