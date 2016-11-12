/* Copyright 2009-2016 EPFL, Lausanne */

package leon
package genc
package phases

import ir.IRs.{ RIR }

import ir.PrimitiveTypes._
import ir.Literals._
import ir.Operators._

import genc.{ CAST => C }
import RIR._

import collection.mutable.{ Map => MutableMap, Set => MutableSet }

private[genc] object IR2CPhase extends TimedLeonPhase[RIR.ProgDef, CAST.Prog] {
  val name = "CASTer"
  val description = "Translate the IR tree into the final C AST"

  def getTimer(ctx: LeonContext) = ctx.timers.genc.get("RIR -> CAST")

  def apply(ctx: LeonContext, ir: RIR.ProgDef): CAST.Prog = new IR2CImpl(ctx)(ir)
}

// This implementation is basically a Transformer that produce something which isn't an IR tree.
// So roughtly the same visiting scheme is applied.
//
// Function conversion is pretty straighforward at this point of the pipeline. Expression conversion
// require little care. But class conversion is harder; see detailed procedure below.
private class IR2CImpl(val ctx: LeonContext) extends MiniReporter {
  def apply(ir: ProgDef): C.Prog = rec(ir)

  // We use a cache to keep track of the C function, struct, ...
  private val funCache = MutableMap[FunDef, C.Fun]()
  private val structCache = MutableMap[ClassDef, C.Struct]()
  private val unionCache = MutableMap[ClassDef, C.Union]() // For top hierarchy classes only!
  private val enumCache = MutableMap[ClassDef, C.Enum]() // For top hierarchy classes only!
  private val arrayCache = MutableMap[ArrayType, C.Struct]()
  private val includes = MutableSet[C.Include]()
  private val typdefs = MutableSet[C.Typedef]()

  private var dataTypes = Seq[C.DataType]() // For struct & union, keeps track of definition order!

  private def register(dt: C.DataType) {
    require(!(dataTypes contains dt))

    dataTypes = dataTypes :+ dt
  }

  private def register(i: C.Include) {
    includes += i
  }

  private def register(td: C.Typedef) {
    typdefs += td
  }

  private def rec(id: Id) = C.Id(id)

  private def rec(prog: ProgDef): C.Prog = {
    // Convert all the functions and types of the input program
    val finder = new ir.DependencyFinder(RIR)
    finder(prog)

    finder.getFunctions foreach rec
    finder.getClasses foreach rec

    val main = C.generateMain(prog.entryPoint.returnType == PrimitiveType(Int32Type))

    val enums = enumCache.values.toSet
    val functions = funCache.values.toSet + main

    // Remove "mutability" on includes & typedefs
    C.Prog(includes.toSet, typdefs.toSet, enums, dataTypes, functions)
  }

  private def rec(fd: FunDef): Unit = funCache.getOrElseUpdate(fd, {
    val id = rec(fd.id)
    val returnType = rec(fd.returnType)
    val params = (fd.ctx ++ fd.params) map rec
    val body = rec(fd.body)

    C.Fun(id, returnType, params, body)
  })

  private def rec(fb: FunBody): Either[C.Block, String] = fb match {
    case FunBodyAST(body) =>
      Left(C.buildBlock(rec(body)))

    case FunBodyManual(includes, body) =>
      includes foreach { i => register(C.Include(i)) }
      Right(body)
  }

  // Yet, we do nothing here. The work is done when converting ValDef types, for example.
  private def rec(cd: ClassDef): Unit = ()

  private def rec(vd: ValDef): C.Var = {
    // TODO Add const whenever possible, based on e.g. vd.isVar
    val typ = rec(vd.getType)
    val id = rec(vd.id)
    C.Var(id, typ)
  }

  private def rec(typ: Type): C.Type = typ match {
    case PrimitiveType(pt) => C.Primitive(pt)
    case ClassType(clazz) => convertClass(clazz) // can be a struct or an enum
    case array @ ArrayType(_) => array2Struct(array)
    case ReferenceType(t) => C.Pointer(rec(t))

    case TypedefType(original, alias, include) =>
      include foreach { i => register(C.Include(i)) }
      val td = C.Typedef(rec(original), rec(alias))
      register(td)
      td

    case DroppedType => ??? // void*?
    case NoType => ???
  }

  // One major difference of this rec compared to Transformer.rec(Expr) is that here
  // we don't necessarily follow every branch of the AST. For example, we don't recurse
  // on function definitions, hence no problem with recursive functions.
  private def rec(e: Expr): C.Expr = e match {
    case Binding(vd) => C.Binding(rec(vd.id))

    case Block(exprs) => C.buildBlock(exprs map rec)

    case Decl(vd) => C.Decl(rec(vd.id), rec(vd.getType))

    case DeclInit(vd, ArrayInit(ArrayAllocStatic(arrayType, length, values))) =>
      val bufferId = C.FreshId("buffer")
      val bufferDecl = C.DeclArrayStatic(bufferId, rec(arrayType.base), length, values map rec)
      val data = C.Binding(bufferId)
      val len = C.Lit(IntLit(length))
      val array = array2Struct(arrayType)
      val varInit = C.StructInit(array, data :: len :: Nil)
      val varDecl = C.DeclInit(rec(vd.id), array, varInit)

      C.buildBlock(bufferDecl :: varDecl :: Nil)

    case DeclInit(vd, ArrayInit(ArrayAllocVLA(arrayType, length, valueInit))) =>
      val bufferId = C.FreshId("buffer")
      val lenId = C.FreshId("length")
      val lenDecl = C.DeclInit(lenId, C.Primitive(Int32Type), rec(length)) // Eval `length` once only
      val len = C.Binding(lenId)
      val bufferDecl = C.DeclArrayVLA(bufferId, rec(arrayType.base), len, rec(valueInit))
      val data = C.Binding(bufferId)
      val array = array2Struct(arrayType)
      val varInit = C.StructInit(array, data :: len :: Nil)
      val varDecl = C.DeclInit(rec(vd.id), array, varInit)

      C.buildBlock(lenDecl :: bufferDecl :: varDecl :: Nil)

    case DeclInit(vd, value) => C.DeclInit(rec(vd.id), rec(vd.getType), rec(value))

    case App(fd, extra, args) => C.Call(rec(fd.id), (extra ++ args) map rec)

    case Construct(cd, args) => constructObject(cd, args) // can be a StructInit or an EnumLiteral

    case ArrayInit(alloc) => internalError("This should be part of a DeclInit expression!")

    case FieldAccess(objekt, fieldId) => C.FieldAccess(rec(objekt), rec(fieldId))
    case ArrayAccess(array, index) => C.ArrayAccess(C.FieldAccess(rec(array), C.Id("data")), rec(index))
    case ArrayLength(array) => C.FieldAccess(rec(array), C.Id("length"))

    case Assign(lhs, rhs) => C.Assign(rec(lhs), rec(rhs))
    case BinOp(op, lhs, rhs) => C.BinOp(op, rec(lhs), rec(rhs))
    case UnOp(op, expr) => C.UnOp(op, rec(expr))

    case If(cond, thenn) => C.If(rec(cond), C.buildBlock(rec(thenn)))
    case IfElse(cond, thenn, elze) => C.IfElse(rec(cond), C.buildBlock(rec(thenn)), C.buildBlock(rec(elze)))
    case While(cond, body) => C.While(rec(cond), C.buildBlock(rec(body)))

    // Find out if we can actually handle IsInstanceOf.
    case IsA(_, ClassType(cd)) if cd.parent.isEmpty => C.True // Since it has typecheck, it can only be true.

    // Currently, classes are tagged with a unique ID per class hierarchy, but
    // without any kind of ordering. This means we cannot have test for membership
    // but only test on concrete children is supported. We could improve on that
    // using something similar to Cohen's encoding.
    case IsA(_, ClassType(cd)) if cd.isAbstract =>
      internalError("Cannot handle membership test with abstract types for now")

    case IsA(expr, ct) =>
      val tag = getEnumLiteralFor(ct.clazz)
      val tagAccess = C.FieldAccess(rec(expr), TaggedUnion.tag)
      C.BinOp(Equals, tagAccess, tag)

    case AsA(expr, ClassType(cd)) if cd.parent.isEmpty => rec(expr) // no casting, actually

    case AsA(expr, ClassType(cd)) if cd.isAbstract =>
      internalError("Cannot handle cast with abstract types for now")

    case AsA(expr, ct) =>
      val fieldId = getUnionFieldFor(ct.clazz)
      val unionAccess = C.FieldAccess(rec(expr), TaggedUnion.value)
      C.FieldAccess(unionAccess, fieldId)

    case Lit(lit) => C.Lit(lit)

    case Ref(e) => C.Ref(rec(e))
    case Deref(e) => C.Deref(rec(e))

    case Return(e) => C.Return(rec(e))
    case Break => C.Break
  }

  // Construct an instantce of the given case class.
  //
  // There are three main cases:
  //  - 1) This case class has no parent;
  //  - 2) This class is part of a class hierarchy and some of the leaves classes have fields;
  //  - 3) This class is part of a class hierarchy but none of the concrete classes have fields.
  //
  // In the first case, we can "simply" construct the structure associated with this case class.
  //
  // In the second case, we need to construct the structure representing the class hierarchy with
  // its tag (an enumeration representing the runtime type of the object) and its value (an union
  // containing the relevant bits of data for the runtime type of the object).
  //
  // In the third and final case, we can express the whole class hierarchy using only an enumeration
  // to improve both memory space and execution speed.
  //
  // This function is just a proxy for the three main cases implementations.
  //
  // NOTE Empty classes are not supported in pure C99 (GNU-C99 does) so we have to add a dummy byte
  //      field. (This is how it is implemented in GCC.)
  //
  // NOTE For scenarios 2) we consider the whole class hierarchy even though in some contexts
  //      we could work with a subset. This leaves room for improvement, such as in the following
  //      (simplified) example:
  //
  //      abstract class GrandParent
  //      case class FirstChild(...) extends GrandParent
  //      abstract class Parent extends GrandParent
  //      case class GrandChildren1(...) extends Parent
  //      case class GrandChildren2(...) extends Parent
  //
  //      def foo(p: Parent) = ...
  //      def bar = foo(GrandChildren2(...))
  //
  //      In this example, `p` could hold information only about either GrandChildren1 or GrandChildren2
  //      but the current implementation will treat it as if were a GrandParent.
  //
  //      In order to implement such optimisation, we would need to keep track of which minimal "level"
  //      of the hierarchy is required. This might also involve playing around with the how methods
  //      are extracted wihtin Leon because a method defined on, say, Parent will be extracted as a
  //      function taking a GrandParent as parameter (with an extra pre-condition requiring that
  //      the parameter is an instance of Parent).
  //
  // NOTE Leon guarantees us that every abstract class has at least one (indirect) case class child.
  //
  private def constructObject(cd: ClassDef, args: Seq[Expr]): C.Expr = {
    require(!cd.isAbstract)

    val leaves = cd.getHierarchyLeaves // only leaves have fields
    if (leaves exists { cd => cd.fields.nonEmpty }) {
      if (cd.parent.isEmpty) simpleConstruction(cd, args)
      else hierarchyConstruction(cd, args)
    } else enumerationConstruction(cd, args)
  }

  private def convertClass(cd: ClassDef): C.Type = {
    val leaves = cd.getHierarchyLeaves
    if (leaves exists { cd => cd.fields.nonEmpty }) getStructFor(cd)
    else getEnumFor(cd.hierarchyTop)
  }

  private val markedAsEmpty = MutableSet[ClassDef]()

  private def markAsEmpty(cd: ClassDef) {
    markedAsEmpty += cd
  }

  private def simpleConstruction(cd: ClassDef, args0: Seq[Expr]): C.StructInit = {
    // Ask for the C structure associated with cd
    val struct = getStructFor(cd)

    // Check whether an extra byte was added to the structure
    val args =
      if (markedAsEmpty(cd)) Seq(Lit(IntLit(0)))
      else args0

    C.StructInit(struct, args map rec)
  }

  private def hierarchyConstruction(cd: ClassDef, args: Seq[Expr]): C.StructInit = {
    // Build the structure wrapper for tagged union
    val topStruct = getStructFor(cd.hierarchyTop)
    val union = getUnionFor(cd.hierarchyTop)
    val tag = getEnumLiteralFor(cd)
    val unionField = getUnionFieldFor(cd)
    val childInit = simpleConstruction(cd, args)
    val unionInit = C.UnionInit(union, unionField, childInit)

    C.StructInit(topStruct, Seq(tag, unionInit))
  }

  private def enumerationConstruction(cd: ClassDef, args: Seq[Expr]): C.EnumLiteral = {
    if (args.nonEmpty)
      internalError("Enumeration should have no construction arguments!")

    getEnumLiteralFor(cd)
  }

  // Extract from cache, or build the C structure for the given class definition.
  //
  // Here are the three cases we can be in:
  //   1) the given class definition is a case class;
  //   2) it is the top class of a class hierarchy;
  //   3) it is an abstract class inside the class hierarchy.
  //
  // NOTE As described in a NOTE above, scenarios 2) & 3) are not yet distinguished.
  //      We currently treat case 3) as 2).
  private def getStructFor(cd: ClassDef): C.Struct = {
    val candidate = if (cd.isAbstract) cd.hierarchyTop else cd

    structCache get candidate match {
      case None =>
        val struct =
          if (candidate.isAbstract) buildStructForHierarchy(candidate)
          else buildStructForCaseClass(candidate)

        // Register the struct in the class cache AND as a datatype
        structCache.update(candidate, struct)
        register(struct)

        struct

      case Some(struct) => struct
    }
  }

  // Build the union used in the "tagged union" structure that is representing a class hierarchy.
  private def getUnionFor(cd: ClassDef): C.Union = unionCache.getOrElseUpdate(cd, {
    require(cd.isAbstract && cd.parent.isEmpty) // Top of hierarchy

    // List all (concrete) leaves of the class hierarchy as fields of the union.
    val leaves = cd.getHierarchyLeaves
    val fields = leaves.toSeq map { c => C.Var(getUnionFieldFor(c), getStructFor(c)) }
    val id = rec("union_" + cd.id)

    val union = C.Union(id, fields)

    // Register the union as a datatype as well
    register(union)

    union
  })

  // Build the enum used in the "tagged union" structure that is representing a class hierarchy.
  private def getEnumFor(cd: ClassDef): C.Enum = enumCache.getOrElseUpdate(cd, {
    // List all (concrete) leaves of the class hierarchy as fields of the union.
    val leaves = cd.getHierarchyLeaves
    val literals = leaves.toSeq map getEnumLiteralFor
    val id = rec("enum_" + cd.id)

    C.Enum(id, literals)
  })

  // Get the tagged union field id for a leaf
  private def getUnionFieldFor(cd: ClassDef) = C.Id(cd.id + "_v")

  // Get the tag id for a leaf
  private def getEnumLiteralFor(cd: ClassDef) = C.EnumLiteral(C.Id("tag_" + cd.id))

  // Build a tagged union for the class hierarchy
  private def buildStructForHierarchy(top: ClassDef): C.Struct = {
    val tagType = getEnumFor(top)
    val tag = C.Var(TaggedUnion.tag, tagType)

    val unionType = getUnionFor(top)
    val union = C.Var(TaggedUnion.value, unionType)

    C.Struct(rec(top.id), tag :: union :: Nil)
  }

  private def buildStructForCaseClass(cd: ClassDef): C.Struct = {
    // Here the mapping is straightforward: map the class fields,
    // possibly creating a dummy one to avoid empty classes.
    val fields = if (cd.fields.isEmpty) {
      warning(s"Empty structures are not allowed according to the C99 standard. " +
              s"I'm adding a dummy byte to ${cd.id} structure for compatibility purposes.")
      markAsEmpty(cd)
      Seq(C.Var(C.Id("extra"), C.Primitive(CharType)))
    } else cd.fields map rec

    C.Struct(rec(cd.id), fields)
  }

  private object TaggedUnion {
    val tag = C.Id("tag")
    val value = C.Id("value")
  }

  // Create a structure that will contain a data and length member to nicely wrap an array
  private def array2Struct(arrayType: ArrayType): C.Struct = arrayCache.getOrElseUpdate(arrayType, {
    val length = C.Var(Array.length, C.Primitive(Int32Type)) // Add const
    val base = rec(arrayType.base)
    val data = C.Var(Array.data, C.Pointer(base))
    val id = C.Id(repId(arrayType))

    val array = C.Struct(id, data :: length :: Nil)

    // This needs to get registered as a datatype as well
    register(array)

    array
  })

  private object Array {
    val length = C.Id("length")
    val data = C.Id("data")
  }

}

