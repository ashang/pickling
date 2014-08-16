package scala.pickling
package ir

import scala.reflect.api.Universe

import HasCompat._

class IRs[U <: Universe with Singleton](val uni: U) {
  import uni._
  import compat._
  import definitions._

  // step 1: decide which strategy to use: primary ctor or allocateInstance (pure analysis of the ctor).
  //   - obtain primary ctor (see code)
  //   - go through its params and see if there's a param without a getter.

  // step 2: based on the strategy, we collect the FieldIRs.
  // we should have a method for each strategy.

 sealed abstract class PickleIR
  case class FieldIR(name: String, tpe: Type, param: Option[TermSymbol], accessor: Option[MethodSymbol]) {
    def field = accessor.map(_.accessed.asTerm)
    def getter = accessor.map(_.getter).flatMap(sym => if (sym != NoSymbol) Some(sym) else None)
    def setter = accessor.map(_.setter).flatMap(sym => if (sym != NoSymbol) Some(sym) else None)
    def isParam = param.map(_.owner.name == nme.CONSTRUCTOR).getOrElse(false)
    def isPublic = accessor.map(_.isPublic).getOrElse(false)

    // this part is interesting to picklers
    def hasGetter = getter.isDefined

    // this part is interesting to unpicklers
    def hasSetter = setter.isDefined
    def isErasedParam = isParam && accessor.isEmpty // TODO: this should somehow communicate with the constructors phase!
    def isNonParam = !isParam
  }

  case class ClassIR(tpe: Type, parent: ClassIR, fields: List[FieldIR]) extends PickleIR {
    var canCallCtor: Boolean = true
  }

  def nonParamFieldIRsOf(tpe: Type): Iterable[FieldIR] = {
    val (quantified, rawTpe) = tpe match { case ExistentialType(quantified, rtpe) => (quantified, rtpe); case rtpe => (Nil, rtpe) }

    val allAccessors = tpe.declarations.collect { case meth: MethodSymbol if meth.isAccessor || meth.isParamAccessor => meth }

    val (filteredAccessors, _) = allAccessors.partition(notMarkedTransient)

    val goodAccessorsNotParams = filteredAccessors.filterNot(_.isParamAccessor)

    val goodAccessorsNotParamsVars = goodAccessorsNotParams.filter(acc => acc.isSetter && acc.accessed != NoSymbol) // 2.10 compat: !acc.isAbstract

    goodAccessorsNotParamsVars.map { symSetter: MethodSymbol =>
      val sym = symSetter.getter.asMethod
      val rawSymTpe = sym.typeSignatureIn(rawTpe) match { case NullaryMethodType(ntpe) => ntpe; case ntpe => ntpe }
      val symTpe = existentialAbstraction(quantified, rawSymTpe)
      FieldIR(sym.name.toString, symTpe, None, Some(sym))
    }
  }

  def nonAbstractVars(tpe: Type, quantified: List[Symbol], rawTpeOfOwner: Type): List[FieldIR] = {
    // could collect all those that are setters
    (tpe.declarations.collect {
      //
      case sym: MethodSymbol if !sym.isParamAccessor && sym.isSetter && sym.accessed != NoSymbol =>
        val rawSymTpe =
          sym.getter.typeSignatureIn(rawTpeOfOwner) match { case NullaryMethodType(ntpe) => ntpe; case ntpe => ntpe }
        val symTpe =
          existentialAbstraction(quantified, rawSymTpe)
        FieldIR(sym.name.toString, symTpe, None, Some(sym.getter.asMethod))
    }).toList

    /* we need to compute the type of the field like we did with the ctor params:

        val rawSymTpe = getter.typeSignatureIn(rawTpeOfOwner) match { case NullaryMethodType(ntpe) => ntpe; case ntpe => ntpe }
        val symTpe = existentialAbstraction(quantified, rawSymTpe)

        this requires that the method also receives `rawTpe` which is the non-existential type of the enclosing class

        ok. so the type is the only thing missing.

     */
  }

  def newClassIR(tpe: Type): ClassIR = {
    // create new instance of ClassIR(tpe: Type, parent: ClassIR, fields: List[FieldIR])
    // (a) ignore parent (TODO: remove)
    // (b) keep track of canCallCtor (for unpickler generation)

    // idea:
    // param.nonEmpty     iff  field is param of primary ctor
    // accessor.nonEmpty  iff  field has getter and possibly a setter

    val primaryCtor = tpe.declaration(nme.CONSTRUCTOR) match {
      case overloaded: TermSymbol => overloaded.alternatives.head.asMethod // NOTE: primary ctor is always the first in the list
      case primaryCtor: MethodSymbol => primaryCtor
      case NoSymbol => NoSymbol
    }

    // we need all accessors to filter out transient ctor params
    // main diff: members instead of declarations
    val allAccessors = tpe.members.collect { case meth: MethodSymbol if meth.isAccessor || meth.isParamAccessor => meth }
    val (filteredAccessors, transientAccessors) = allAccessors.partition(notMarkedTransient)

    val primaryCtorParams = primaryCtor.asMethod.paramss.flatten

    val canCallCtor =
      primaryCtor != NoSymbol &&
      primaryCtorParams.forall(sym => !transientAccessors.exists(_.name == sym.name) && sym.asMethod.getter != NoSymbol)

    // ok. so, now we know which strategy to use.
    // now we have to then add logic for either doing what we already do, or doing this
    // allocate instance thing

    // hmm, it seems we at least need transientAccessors to check if a ctor param is transient, and that depends on
    // allAccessors. what about the use of transientAccessors above ^ ?

    val (quantified, rawTpe) = tpe match { case ExistentialType(quantified, rtpe) => (quantified, rtpe); case rtpe => (Nil, rtpe) }

    val baseClasses = tpe.typeSymbol.asClass.baseClasses

    def fieldIRsUsingCtor(): List[FieldIR] = {
      // collect:
      // (a) all ctor params
      val ctorFieldIRs = primaryCtorParams.map { sym =>
        val getter = sym.asTerm.getter.asMethod

        val rawSymTpe = getter.typeSignatureIn(rawTpe) match { case NullaryMethodType(ntpe) => ntpe; case ntpe => ntpe }
        val symTpe = existentialAbstraction(quantified, rawSymTpe)

        FieldIR(sym.name.toString, symTpe, Some(sym.asTerm), Some(getter))
      }

      // (b) non-abstract vars (also private ones)
      val allNonAbstractVars = baseClasses.flatMap { baseClass =>
        nonAbstractVars(tpe.baseType(baseClass), quantified, rawTpe)
      }

      ctorFieldIRs ++ allNonAbstractVars
    }

    def fieldIRsUsingAllocateInstance(): List[FieldIR] = {
      // collect:
      // (a) all vals or vars (even if abstract!!)
      baseClasses.flatMap { baseClass =>
        val stpe = tpe.baseType(baseClass)
        val allGetters = stpe.declarations.collect {
          case sym: MethodSymbol if sym.isGetter && !sym.isParamAccessor => sym
        }

        allGetters.map { getter =>
          val rawSymTpe = getter.typeSignatureIn(rawTpe) match { case NullaryMethodType(ntpe) => ntpe; case ntpe => ntpe }
          val symTpe = existentialAbstraction(quantified, rawSymTpe)

          FieldIR(getter.name.toString, symTpe, None, Some(getter))
        }
      }
    }

    val fieldIRs = if (canCallCtor) fieldIRsUsingCtor() else fieldIRsUsingAllocateInstance()

    // params of primary ctor that are not transient.
    // these have to be pickled if it turns out at runtime to be possible.
    val ctorParams = if (primaryCtor != NoSymbol) primaryCtor.asMethod.paramss.flatten.flatMap { sym =>
      if (transientAccessors.exists(_.name == sym.name) || sym.asTerm.getter == NoSymbol) List()
      else List(sym.asTerm)
    } else Nil

    // some of these ctorParams also have getters
    val allGetters = tpe.members.collect { case meth: MethodSymbol if meth.isGetter => meth }

    val (filteredGetters, transientGetters) = allGetters.partition(notMarkedTransient)

    // create FieldIRs for all non-transient ctor params
    val ctorParamFieldIRs = ctorParams.map { sym: TermSymbol =>
      val accessorOpt = filteredGetters.find(_.name == sym.name)

      val rawSymTpe = accessorOpt.getOrElse(sym).typeSignatureIn(rawTpe) match { case NullaryMethodType(ntpe) => ntpe; case ntpe => ntpe }
      val symTpe = existentialAbstraction(quantified, rawSymTpe)
      FieldIR(sym.name.toString, symTpe, Some(sym), accessorOpt)
    }

    // also collect FieldIRs of base classes (to support private vars)
    val nonParamFieldIRsOfBaseClasses = tpe.typeSymbol.asClass.baseClasses.flatMap { baseClass =>
      nonParamFieldIRsOf(baseClass.asClass.toType)
    }
/*
    println(s"Fields of ${tpe.toString}:")
    println(s"ctorParams: ${ctorParams.mkString(",")}")
    println(s"nonParamGetters: ${nonParamGetters.mkString(",")}")
*/
    ClassIR(tpe, null, ctorParamFieldIRs ++ nonParamFieldIRsOfBaseClasses)
  }


  private type Q = List[FieldIR]
  private type C = ClassIR

  // TODO: minimal versus verbose PickleFormat. i.e. someone might want all concrete inherited fields in their pickle

  def notMarkedTransient(sym: TermSymbol): Boolean = {
    val tr = scala.util.Try {
      (sym.accessed == NoSymbol) || // if there is no backing field, then it cannot be marked transient
      !sym.accessed.annotations.exists(_.tpe =:= typeOf[scala.transient])
    }
    tr.isFailure || tr.get
  }

  /** Creates FieldIRs for the given type, tp.
  */
  private def fields(tp: Type): Q = {
    val ctor = tp.declaration(nme.CONSTRUCTOR) match {
      case overloaded: TermSymbol => overloaded.alternatives.head.asMethod // NOTE: primary ctor is always the first in the list
      case primaryCtor: MethodSymbol => primaryCtor
      case NoSymbol => NoSymbol
    }

    val allAccessors = tp.declarations.collect { case meth: MethodSymbol if meth.isAccessor || meth.isParamAccessor => meth }

    val (filteredAccessors, transientAccessors) = allAccessors.partition(notMarkedTransient)

    val ctorParams = if (ctor != NoSymbol) ctor.asMethod.paramss.flatten.flatMap { sym =>
      if (transientAccessors.exists(acc => acc.name.toString == sym.name.toString)) List()
      else List(sym.asTerm)
    } else Nil

    val (paramAccessors, otherAccessors) = allAccessors.partition(_.isParamAccessor)

    def mkFieldIR(sym: TermSymbol, param: Option[TermSymbol], accessor: Option[MethodSymbol]) = {
      val (quantified, rawTp) = tp match { case ExistentialType(quantified, tpe) => (quantified, tpe); case tpe => (Nil, tpe) }
      val rawSymTp = accessor.getOrElse(sym).typeSignatureIn(rawTp) match { case NullaryMethodType(tpe) => tpe; case tpe => tpe }
      val symTp = existentialAbstraction(quantified, rawSymTp)
      FieldIR(sym.name.toString.trim, symTp, param, accessor)
    }

    val paramFields = ctorParams.map(sym => mkFieldIR(sym, Some(sym), paramAccessors.find(_.name == sym.name)))
    val varGetters = otherAccessors.collect{ case meth if meth.isGetter && meth.accessed != NoSymbol && meth.accessed.asTerm.isVar => meth }
    val varFields = varGetters.map(sym => mkFieldIR(sym, None, Some(sym)))

    paramFields ++ varFields
  }

  private def composition(f1: (Q, Q) => Q, f2: (C, C) => C, f3: C => List[C]) =
    (c: C) => f3(c).reverse.reduce[C](f2)

  private val f1 = (q1: Q, q2: Q) => q1 ++ q2

  private val f2 = (c1: C, c2: C) => ClassIR(c2.tpe, c1, c2.fields)

  private val f3 = (c: C) =>
    c.tpe.baseClasses
         .map(superSym => c.tpe.baseType(superSym))
         .map(tp => ClassIR(tp, null, fields(tp)))

  private val compose =
    composition(f1, f2, f3)

  private val flatten: C => C = (c: C) =>
    if (c.parent != null) ClassIR(c.tpe, c.parent, f1(c.fields, flatten(c.parent).fields))
    else c

  def flattenedClassIR(tpe: Type) = flatten(compose(ClassIR(tpe, null, Nil)))
}
