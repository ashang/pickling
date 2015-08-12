package scala.pickling
package spi

import scala.reflect.runtime.universe.Mirror

/** A registry for looking up (and possibly coding on the fly) picklers by tag.
  *
  * All methods are threadsafe.
  */
trait PicklerRegistry {
  // TODO - Do we need lookup*s w/ the gen*s?

  /** Looks up the registered unpickler using the provided tagKey. */
  def genUnpickler(mirror: Mirror, tagKey: String)(implicit share: refs.Share): Unpickler[_]
  /** Looks up a Pickler or generates one. */
  def genPickler(classLoader: ClassLoader, clazz: Class[_], tag: FastTypeTag[_])(implicit share: refs.Share): Pickler[_]
  /** Checks the existince of an unpickler. */
  def lookupUnpickler(key: String): Option[Unpickler[_]]
  /** Registers a pickler with this registry for future use. */
  def registerPickler(key: String, p: Pickler[_]): Unit  // TODO - Return old pickler if one existed?
  /** Registers an unpickler with this registry for future use. */
  def registerUnpickler(key: String, p: Unpickler[_]): Unit
  // TODO - Some kind of clean or inspect what we have?
}