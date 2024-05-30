package alpine
package codegen

import alpine.ast
import alpine.symbols
import alpine.symbols.Entity.builtinModule
import alpine.util.FatalError

import scala.annotation.tailrec
import scala.collection.mutable
import alpine.symbols.Type
import alpine.symbols.Type.Bool

/** The transpilation of an Alpine program to Scala. */
final class ScalaPrinter(syntax: TypedProgram) extends ast.TreeVisitor[ScalaPrinter.Context, Unit]:

  import ScalaPrinter.Context

  /** The program being evaluated. */
  private given TypedProgram = syntax
  val str_bldr = StringBuilder()

  /** Returns a Scala program equivalent to `syntax`. */
  def transpile(): String =
    //str_bldr ++= "\n\n=== Begin Transpiler ===\n"
    given c: Context = Context()
    syntax.declarations.foreach(_.visit(this))
    c.typesToEmit.map(emitRecord)
    //str_bldr ++= "\n=== End Transpiler ===";println(str_bldr.toString())
    c.output.toString

  /** Writes the Scala declaration of `t` in `context`. */
  private def emitRecord(t: symbols.Type.Record)(using context: Context): Unit =
    //str_bldr ++= "\nemitRecord >>>"
    if t.fields.isEmpty then
      context.output ++= "()"
    else
      context.output ++= "case class " + discriminator(t) + "(" + t.fields.map(field =>{
        "$" + t.fields.indexOf(field) + ": " + transpiledType(field.value)
      }).mkString(", ") + ")\n"
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< emitRecord"
    
    // Cassien

  /** Writes the Scala declaration of `t`, which is not a singleton, in `context`. */
  private def emitNonSingletonRecord(t: symbols.Type.Record)(using context: Context): Unit =
    //str_bldr ++= "\nemitNonSingletonRecord >>>"
    if t.fields.isEmpty then
      throw Error(s"type '${t}' is a singleton and cannot be emitted as a non-singleton")
    else
      context.output ++= "case class " + discriminator(t) + "(" + t.fields.map(field =>{
        "$" + t.fields.indexOf(field) + ": " + transpiledType(field.value)
      }).mkString(", ") + ")\n"
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< emitNonSingletonRecord"
    
    // Cassien

  /** Returns the transpiled form of `t`. */
  private def transpiledType(t: symbols.Type)(using context: Context): String =
    t match
      case u: symbols.Type.Builtin =>
        transpiledBuiltin(u)
      case u: symbols.Type.Record =>
        transpiledRecord(u)
      case u: symbols.Type.Arrow =>
        transpiledArrow(u)
      case u: symbols.Type.Sum =>
        transpiledSum(u)
      case _ => throw Error(s"type '${t}' is not representable in Scala")

  /** Returns the transpiled form of `t`. */
  private def transpiledBuiltin(t: symbols.Type.Builtin)(using context: Context): String =
    t match
      case symbols.Type.BuiltinModule => throw Error(s"type '${t}' is not representable in Scala")
      case symbols.Type.Bool => "Boolean"
      case symbols.Type.Int => "Int"
      case symbols.Type.Float => "Float"
      case symbols.Type.String => "String"
      case symbols.Type.Any => "Any"

  /** Returns the transpiled form of `t`. */
  private def transpiledRecord(t: symbols.Type.Record)(using context: Context): String =
    if t == symbols.Type.Unit then
      "Unit"
    else
      context.registerUse(t)
      val d = discriminator(t)
      if t.fields.isEmpty then s"${d}.type" else d

  /** Returns the transpiled form of `t`. */
  private def transpiledArrow(t: symbols.Type.Arrow)(using context: Context): String =
    val r = StringBuilder()
    r ++= "("
    r.appendCommaSeparated(t.inputs) { (o, a) => o ++= transpiledType(a.value) }
    r ++= " => "
    r ++= transpiledType(t.output)
    r ++= ")"
    r.toString()

  /** Returns the transpiled form of `t`. */
  private def transpiledSum(t: symbols.Type.Sum)(using context: Context): String =
    if t.members.isEmpty then "N" else
      t.members.map(transpiledType).mkString(" | ")

  /** Returns a string uniquely identifiyng `t` for use as a discriminator in a mangled name. */
  private def discriminator(t: symbols.Type): String =
    t match
      case u: symbols.Type.Builtin =>
        discriminator(u)
      case u: symbols.Type.Meta =>
        s"M${discriminator(u.instance)}"
      case u: symbols.Type.Definition =>
        "D" + u.identifier
      case u: symbols.Type.Record =>
        discriminator(u)
      case u: symbols.Type.Arrow =>
        discriminator(u)
      case u: symbols.Type.Sum =>
        discriminator(u)
      case _ =>
        throw Error(s"unexpected type '${t}'")

  /** Returns a string uniquely identifiyng `t` for use as a discriminator in a mangled name. */
  private def discriminator(t: symbols.Type.Builtin): String =
    t match
      case symbols.Type.BuiltinModule => "Z"
      case symbols.Type.Bool => "B"
      case symbols.Type.Int => "I"
      case symbols.Type.Float => "F"
      case symbols.Type.String => "S"
      case symbols.Type.Any => "A"

  /** Returns a string uniquely identifiyng `t` for use as a discriminator in a mangled name. */
  private def discriminator(t: symbols.Type.Record): String =
    if t.fields.isEmpty then "R0" else
      "R" + t.fields.map { field =>
        discriminator(field.value)
      }.mkString("_")
    // Cassien

  /** Returns a string uniquely identifiyng `t` for use as a discriminator in a mangled name. */
  private def discriminator(t: symbols.Type.Arrow): String =
    val b = StringBuilder("X")
    for i <- t.inputs do
      b ++= i.label.getOrElse("")
      b ++= discriminator(i.value)
    b ++= discriminator(t.output)
    b.toString

  /** Returns a string uniquely identifiyng `t` for use as a discriminator in a mangled name. */
  private def discriminator(t: symbols.Type.Sum): String =
    if t.members.isEmpty then "N" else
      "E" + t.members.map(discriminator).mkString

  /** Returns a transpiled reference to `e`. */
  private def transpiledReferenceTo(e: symbols.Entity): String =
    e match
      case symbols.Entity.Builtin(n, _) => s"alpine_rt.builtin.${n.identifier}"
      case symbols.Entity.Declaration(n, t) => scalaized(n) + discriminator(t)
      case _: symbols.Entity.Field => ??? // WTF is this

  /** Returns a string representation of `n` suitable for use as a Scala identifier. */
  private def scalaized(n: symbols.Name): String =
    n.qualification match
      case Some(q) =>
        s"${scalaized(q)}_${n.identifier}"
      case None =>
        "_" + n.identifier

  override def visitLabeled[T <: ast.Tree](n: ast.Labeled[T])(using context: Context): Unit =
    unexpectedVisit(n)

  override def visitBinding(n: ast.Binding)(using context: Context): Unit =
    //str_bldr ++= "\nvisitBinding >>>"
    // Bindings represent global symbols at top-level.
    if context.isTopLevel then
      context.output ++= "  " * context.indentation

      // If the is the entry point if it's called "main".
      if n.identifier == "main" then
        context.output ++= "@main def $entry"
      else
        context.output ++= s"private val "
        context.output ++= transpiledReferenceTo(n.entityDeclared)

      context.output ++= ": "
      context.output ++= transpiledType(n.tpe)

      // Top-level bindings must have an initializer.
      assert(n.initializer.isDefined)
      context.indentation += 1
      context.output ++= " =\n"
      context.output ++= "  " * context.indentation
      //str_bldr ++= "\n"+context.output.toString()
      context.inScope((c) => n.initializer.get.visit(this)(using c))
      context.output ++= "\n\n"
      context.indentation -= 1

    // Bindings at local-scope are used in let-bindings and pattern cases.
    else
      context.output ++= s"val "
      context.output ++= transpiledReferenceTo(n.entityDeclared)
      context.output ++= ": "
      context.output ++= transpiledType(n.tpe)
      n.initializer.map { (i) =>
        context.output ++= " = "
        context.inScope((c) => i.visit(this)(using c))
      }
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitBinding"
    

  override def visitTypeDeclaration(n: ast.TypeDeclaration)(using context: Context): Unit =
    unexpectedVisit(n)

  override def visitFunction(n: ast.Function)(using context: Context): Unit =
    //str_bldr ++= "\nvisitFunction >>>"
    context.output ++= "  " * context.indentation
    context.output ++= "def "
    context.output ++= transpiledReferenceTo(n.entityDeclared)
    context.output ++= "("
    context.output.appendCommaSeparated(n.inputs) { (o, a) =>
      o ++= a.identifier
      o ++= ": "
      o ++= transpiledType(a.tpe)
    }
    context.output ++= "): "
    context.output ++= transpiledType(symbols.Type.Arrow.from(n.tpe).get.output)
    context.output ++= " =\n"

    context.indentation += 1
    context.output ++= "  " * context.indentation
    context.inScope((c) => n.body.visit(this)(using c))
    context.output ++= "\n\n"
    context.indentation -= 1
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitFunction"
    

  override def visitParameter(n: ast.Parameter)(using context: Context): Unit =
    unexpectedVisit(n)

  override def visitIdentifier(n: ast.Identifier)(using context: Context): Unit =
    context.output ++= transpiledReferenceTo(n.referredEntity.get.entity)

  override def visitBooleanLiteral(n: ast.BooleanLiteral)(using context: Context): Unit =
    context.output ++= n.value.toString

  override def visitIntegerLiteral(n: ast.IntegerLiteral)(using context: Context): Unit =
    context.output ++= n.value.toString

  override def visitFloatLiteral(n: ast.FloatLiteral)(using context: Context): Unit =
    context.output ++= n.value.toString ++ "f"

  override def visitStringLiteral(n: ast.StringLiteral)(using context: Context): Unit =
    context.output ++= n.value

  override def visitRecord(n: ast.Record)(using context: Context): Unit =
    //str_bldr ++= "\nvisitRecord >>>"
    val s_b = Context()
    val fieldValues = {n.fields.map(field =>{
      field.value.visit(this)(using s_b);val s = s_b.output.toString();s_b.output.clear();s})
  }
    context.output ++= transpiledType(n.tpe) + "(" + fieldValues.mkString(", ") + ")"
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitRecord"
    
    // Cassien prob wrong

  override def visitSelection(n: ast.Selection)(using context: Context): Unit =
    //str_bldr ++= "\nvisitSelection >>>"
    n.qualification.visit(this)
    n.referredEntity match
      case Some(symbols.EntityReference(e: symbols.Entity.Field, _)) =>
        context.output ++= ".$" + e.index
      case _ =>
        unexpectedVisit(n.selectee)
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitSelection"
    

  override def visitApplication(n: ast.Application)(using context: Context): Unit =
    //str_bldr ++= "\nvisitApp >>>"
    n.function.visit(this)
    context.output ++= "("
    context.output.appendCommaSeparated(n.arguments) { (o, a) => a.value.visit(this) }
    context.output ++= ")"
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitApp"
    

  override def visitPrefixApplication(n: ast.PrefixApplication)(using context: Context): Unit =
    //str_bldr ++= "\nvisitPrefixApp >>>"
    n.function.visit(this)
    context.output ++= "("
    n.argument.visit(this)
    context.output ++= ")"
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitPrefixApp"
    

  override def visitInfixApplication(n: ast.InfixApplication)(using context: Context): Unit =
    //str_bldr ++= "\nvisitInfixApp >>>"
    n.function.visit(this)
    context.output ++= "("
    n.lhs.visit(this)
    context.output ++= ", "
    n.rhs.visit(this)
    context.output ++= ")"
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitinfixApp"
    

  override def visitConditional(n: ast.Conditional)(using context: Context): Unit =
    //str_bldr ++= "\nvisitConditional >>>"
    context.output ++= "if "
    n.condition.visit(this)
    context.output ++= " then "
    n.successCase.visit(this)
    context.output ++= " else "
    n.failureCase.visit(this)
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitConditional"
    

  override def visitMatch(n: ast.Match)(using context: Context): Unit =
    //str_bldr ++= "\nvisitMatch >>>"
    context.output ++= ""
    n.scrutinee.visit(this)
    context.output ++= " match {\n"
    context.indentation += 1
    n.cases.foreach(_.visit(this))
    context.indentation -= 1
    context.output++= "  " * context.indentation
    context.output ++= "}"
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitMatch"
    

  override def visitMatchCase(n: ast.Match.Case)(using context: Context): Unit =
    //str_bldr ++= "\nvisitMatchCase >>>"
    context.output ++= "  " * context.indentation
    context.output ++= "case "
    val s_b = StringBuilder()
    context.swappingOutputBuffer(s_b)(c => n.pattern.visit(this))
    context.output ++= s_b.mkString.split("val").map(s =>{s.split(": Any").mkString}).mkString
    context.output ++= " => "
    n.body.visit(this)
    context.output ++= "\n"
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitMatchCase"
    

  override def visitLet(n: ast.Let)(using context: Context): Unit =
    //str_bldr ++= "\nvisitLet >>>"
    // Use a block to uphold lexical scoping.
    context.output ++= "{\n"
    context.indentation += 1
    context.output ++= "  " * context.indentation
    n.binding.visit(this)
    context.output ++= "\n"
    context.output ++= "  " * context.indentation
    n.body.visit(this)
    context.output ++= "\n"
    context.indentation -= 1
    context.output ++= "  " * context.indentation
    context.output ++= "}"
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitLet"
    

  override def visitLambda(n: ast.Lambda)(using context: Context): Unit =
    //str_bldr ++= "\nvisitLambda >>>"
    context.output ++= "("
    context.output.appendCommaSeparated(n.inputs) { (o, a) =>
      o ++= a.identifier
      o ++= ": "
      o ++= transpiledType(a.tpe)
    }
    context.output ++= ") => ("
    n.body.visit(this)
    context.output ++= "): "
    context.output ++= transpiledType(symbols.Type.Arrow.from(n.tpe).get.output)
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitLambda"
    

  override def visitParenthesizedExpression(
      n: ast.ParenthesizedExpression
  )(using context: Context): Unit =
    //str_bldr ++= "\nvisitParenthesizedExpr >>>"
    context.output ++= "("
    n.inner.visit(this)
    context.output ++= ")"
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitParenthesizedExpr"
    

  override def visitAscribedExpression(
      n: ast.AscribedExpression
  )(using context: Context): Unit =
    //str_bldr ++= "\nvisitAscribedExpr >>>"
    n.operation match
      case ast.Typecast.Widen => 
        n.inner.visit(this)
        context.output ++= ".asInstanceOf["+n.ascription.unparsed+"]"
      case ast.Typecast.Narrow =>
        context.output ++= "alpine_rt.narrow["+n.ascription.unparsed+", Option["+n.ascription.unparsed+"]]("
        n.inner.visit(this)
        context.output ++= ", t => Some(t), None"
        context.output ++= ")"
      case ast.Typecast.NarrowUnconditionally =>
        context.output ++= "alpine_rt.narrowUnconditionally["+n.ascription.unparsed+"]("
        n.inner.visit(this)
        context.output ++= ")"
    
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitAscribedExpr"
    

  override def visitTypeIdentifier(n: ast.TypeIdentifier)(using context: Context): Unit =
    unexpectedVisit(n)

  override def visitRecordType(n: ast.RecordType)(using context: Context): Unit =
    unexpectedVisit(n)

  override def visitTypeApplication(n: ast.TypeApplication)(using context: Context): Unit =
    unexpectedVisit(n)

  override def visitArrow(n: ast.Arrow)(using context: Context): Unit =
    unexpectedVisit(n)

  override def visitSum(n: ast.Sum)(using context: Context): Unit =
    unexpectedVisit(n)

  override def visitParenthesizedType(n: ast.ParenthesizedType)(using context: Context): Unit =
    unexpectedVisit(n)

  override def visitValuePattern(n: ast.ValuePattern)(using context: Context): Unit =
    //str_bldr ++= "\nvisitValueP >>>"
    n.value.visit(this)(using context)
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitValueP"
    

  override def visitRecordPattern(n: ast.RecordPattern)(using context: Context): Unit =
    //str_bldr ++= "\nvisitRecordP >>>"
    if(n.fields.isEmpty) then 
      context.output ++= "None" 
    else
      val s = "R"+n.fields.map(f =>{val d = discriminator(f.value.tpe);transpiledType(f.value.tpe);d}).mkString("_")+"§"
      context.output ++= {if s != "RA§" then s.replace("§","") else "Some"}
      val s_b = StringBuilder()
      context.swappingOutputBuffer(s_b)(c => n.fields.foreach(f => {f.value.visit(this);s_b ++= ","}))
      //var my_str = s_b.mkString.reverse.replaceFirst(",","").reverse
      //println(my_str)
      context.output ++= "("+("§"+s_b.mkString.reverse.replaceFirst(",","").reverse.strip().split("val ").map(s =>{s.split(": Any").mkString}).mkString(",") + ")").replaceFirst("§,","(").substring(1).replaceAll(",+",",")
      //str_bldr ++= " ="+s_b.mkString+"="
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitRecordP"
    

  override def visitWildcard(n: ast.Wildcard)(using context: Context): Unit =
    //str_bldr ++= "\nvisitWildcard >>>"
    context.output ++= "_"
    //str_bldr ++= "\n"+context.output.toString()
    //str_bldr ++= "\n<<< visitWildcard"
    

  override def visitError(n: ast.ErrorTree)(using context: Context): Unit =
    unexpectedVisit(n)

object ScalaPrinter:

  /** The local state of a transpilation to Scala.
   *
   *  @param indentation The current identation to add before newlines.
   */
  final class Context(var indentation: Int = 0):

    /** The types that must be emitted in the program. */
    private var _typesToEmit = mutable.Set[symbols.Type.Record]()

    /** The types that must be emitted in the program. */
    def typesToEmit: Set[symbols.Type.Record] = _typesToEmit.toSet

    /** The (partial) result of the transpilation. */
    private var _output = StringBuilder()

    /** The (partial) result of the transpilation. */
    def output: StringBuilder = _output

    /** `true` iff the transpiler is processing top-level symbols. */
    private var _isTopLevel = true

    /** `true` iff the transpiler is processing top-level symbols. */
    def isTopLevel: Boolean = _isTopLevel

    /** Adds `t` to the set of types that are used by the transpiled program. */
    def registerUse(t: symbols.Type.Record): Unit =
      if t != symbols.Type.Unit then _typesToEmit.add(t)

    /** Returns `action` applied on `this` where `output` has been exchanged with `o`. */
    def swappingOutputBuffer[R](o: StringBuilder)(action: Context => R): R =
      val old = _output
      _output = o
      try action(this) finally _output = old

    /** Returns `action` applied on `this` where `isTopLevel` is `false`. */
    def inScope[R](action: Context => R): R =
      var tl = _isTopLevel
      _isTopLevel = false
      try action(this) finally _isTopLevel = tl

  end Context

end ScalaPrinter

extension (self: StringBuilder) def appendCommaSeparated[T](ls: Seq[T])(
    reduce: (StringBuilder, T) => Unit
): Unit =
    var f = true
    for l <- ls do
      if f then f = false else self ++= ", "
      reduce(self, l)
