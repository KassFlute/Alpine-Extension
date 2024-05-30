package alpine
package evaluation

import alpine.ast
import alpine.symbols
import alpine.symbols.{Entity, EntityReference, Type}
import alpine.util.FatalError

import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.control.NoStackTrace
import alpine.symbols.Type.Labeled
import scala.annotation.meta.field
import alpine.ast.Typecast
import java.awt.Frame
import alpine.symbols.Name
import java.awt.Label
import alpine.ast.Expression
import alpine.ast.Identifier
import alpine.ast.ErrorTree
import alpine.ast.TypeIdentifier
import alpine.ast.RecordType
import alpine.ast.TypeApplication
import alpine.ast.Arrow
import alpine.ast.Sum
import alpine.ast.ParenthesizedType
import alpine.ast.Match.Case

/** The evaluation of an Alpine program.
 *
 *  @param syntax The program being evaluated.
 *  @param standardOutput The standard output of the interpreter.
 *  @param standardError The standard output of the interpreter.
 */
final class Interpreter(
    syntax: TypedProgram,
    standardOutput: OutputStream,
    standardError: OutputStream
) extends ast.TreeVisitor[Interpreter.Context, Value]:

  import Interpreter.Context

  /** The program being evaluated. */
  private given TypedProgram = syntax

  /** A map from global entity to its declaration. */
  private val globals: mutable.HashMap[symbols.Entity, Value] =
    val partialResult = mutable.HashMap[symbols.Entity, Value]()
    for d <- syntax.declarations do
      partialResult.put(d.entityDeclared, Value.Unevaluated(d, d.tpe))
    partialResult

  /** Evaluates the entry point of the program. */
  def run(): Int =
    val e = syntax.entry.getOrElse(throw Panic("no entry point"))
    try
      e.visit(this)(using Context())
      0
    catch case e: Interpreter.Exit =>
      e.status

  def visitLabeled[T <: ast.Tree](n: ast.Labeled[T])(using context: Context): Value =
    unexpectedVisit(n)

  def visitBinding(n: ast.Binding)(using context: Context): Value =
    unexpectedVisit(n)

  def visitTypeDeclaration(n: ast.TypeDeclaration)(using context: Context): Value =
    unexpectedVisit(n)

  def visitFunction(n: ast.Function)(using context: Context): Value =
    unexpectedVisit(n)

  def visitParameter(n: ast.Parameter)(using context: Context): Value =
    unexpectedVisit(n)

  def visitIdentifier(n: ast.Identifier)(using context: Context): Value =
    val r = n.referredEntity.get
    r.entity match
      case e: Entity.Builtin =>
        builtin(e)
      case e =>
        context.getLocal(e.name).getOrElse(getGlobal(e).get)

  def visitBooleanLiteral(n: ast.BooleanLiteral)(using context: Context): Value =
    Value.Builtin(n.value == "true", Type.Bool)

  def visitIntegerLiteral(n: ast.IntegerLiteral)(using context: Context): Value =
    Value.Builtin(n.value.toInt, Type.Int)

  def visitFloatLiteral(n: ast.FloatLiteral)(using context: Context): Value =
    Value.Builtin(n.value.toFloat, Type.Float)

  def visitStringLiteral(n: ast.StringLiteral)(using context: Context): Value =
    Value.Builtin(n.value, Type.String)

  def visitRecord(n: ast.Record)(using context: Context): Value =    
    val fields = n.fields.map(_.value.visit(this)(using context))
    val labels = n.fields.map(_.label)
    val fieldTypes = fields.map(_.dynamicType)
    val labeledTypes = labels.zip(fieldTypes).map((l, t) => Labeled(l, t))
    
    Value.Record(n.identifier, fields, Type.Record(n.identifier, labeledTypes))

  def visitSelection(n: ast.Selection)(using context: Context): Value =
    n.qualification.visit(this) match
      case q: Value.Record => syntax.treeToReferredEntity.get(n) match
        case Some(symbols.EntityReference(Entity.Field(_, i), _)) =>
          q.fields(i)
        case r =>
          throw Panic(s"unexpected entity reference '${r}'")
      case q =>
        throw Panic(s"unexpected qualification of type '${q.dynamicType}'")

  def visitApplication(n: ast.Application)(using context: Context): Value =
    // Evaluate function identifier ?? we already have a function expression
    val function = n.function.visit(this)

    val evaluatedArguments = 
      for arg <- n.arguments
      yield arg.value.visit(this)

    call(function, evaluatedArguments)


  def visitPrefixApplication(n: ast.PrefixApplication)(using context: Context): Value =
    val function = n.function.visit(this)
    val evaluatedArgument = n.argument.visit(this) 
    call(function, evaluatedArgument :: Nil)

  def visitInfixApplication(n: ast.InfixApplication)(using context: Context): Value =
    val function = n.function.visit(this)
    val evaluatedArguments = List(n.lhs, n.rhs).map(_.visit(this)) 
    call(function, evaluatedArguments)

  def visitConditional(n: ast.Conditional)(using context: Context): Value =
    val condition = n.condition.visit(this)
    
    condition match
      case Value.Builtin(true, Type.Bool) => n.successCase.visit(this)
      case Value.Builtin(false, Type.Bool) => n.failureCase.visit(this)
      case _ => throw Panic(s"Condition ${condition} of type ${condition.dynamicType} does not evaluate to a Boolean!")

  def visitMatch(n: ast.Match)(using context: Context): Value =
    val sucritinee = n.scrutinee.visit(this)
    val validCase = n.cases.map(x => (x, matches(sucritinee, x.pattern))).find(_._2.isDefined)
    validCase match
      case Some((Case(_, body, _), Some(addedContext))) =>
        val newContext = context.pushing(addedContext)
        body.visit(this)(using newContext)
      case _ => throw Panic(s"No valid case found for ${sucritinee} of type ${sucritinee.dynamicType}")

  def visitMatchCase(n: ast.Match.Case)(using context: Context): Value =
    unexpectedVisit(n)

  def visitLet(n: ast.Let)(using context: Context): Value =
    val binding = n.binding
    
    val newContext = binding.initializer match
      case Some(initializer) => 
        val name = n.binding.nameDeclared
        val value = initializer.visit(this)
        context.defining(name -> value)
      case None => throw Panic("Let binding without initializer")
    n.body.visit(this)(using newContext)

  def visitLambda(n: ast.Lambda)(using context: Context): Value =
    val captures = context.flattened.filter((name, _) => !n.inputs.contains(name)) // not sure the filter is properly necessary
    Value.Lambda(n.body, n.inputs, captures, n.tpe)

  def visitParenthesizedExpression(n: ast.ParenthesizedExpression)(using context: Context): Value =
    try n.inner.visit(this)(using context)
    catch case e: Interpreter.Exit => throw e

  def visitAscribedExpression(n: ast.AscribedExpression)(using context: Context): Value =
    def convertLabeled(tpe: ast.Labeled[ast.Type]): symbols.Type.Labeled =
      Labeled(tpe.label, convert(tpe.value))

    def convert(tpe: ast.Type): symbols.Type =
      tpe match
        case ErrorTree(site) => symbols.Type.Error
        case TypeIdentifier(value, site) => 
          value match
            case "Int" => symbols.Type.Int
            case "Float" => symbols.Type.Float
            case "String" => symbols.Type.String
            case "Bool" => symbols.Type.Bool
            case _ => symbols.Type.Definition(value)
        case RecordType(identifier, fields, site) => symbols.Type.Record(identifier, fields.map(convertLabeled))
        case TypeApplication(constructor, arguments, site) => throw Panic("Unexpected type")
        case Arrow(inputs, output, site) => symbols.Type.Arrow(inputs.map(convertLabeled), convert(output))
        case Sum(members, site) => throw Panic("Unexpected type")
        case ParenthesizedType(inner, site) => convert(inner)

    val expressionValue = n.inner.visit(this)
    val ascriptionType = convert(n.ascription)

    n.operation match
      case Typecast.Widen => expressionValue
      case Typecast.NarrowUnconditionally =>
        if ascriptionType.isSubtypeOf(expressionValue.dynamicType) then expressionValue
        else throw Panic("Narrowing impossible")
      case Typecast.Narrow =>
        if ascriptionType.isSubtypeOf(expressionValue.dynamicType) then Value.some(expressionValue)
        else Value.none

  def visitTypeIdentifier(n: ast.TypeIdentifier)(using context: Context): Value =
    unexpectedVisit(n)

  def visitRecordType(n: ast.RecordType)(using context: Context): Value =
    unexpectedVisit(n)

  def visitTypeApplication(n: ast.TypeApplication)(using context: Context): Value =
    unexpectedVisit(n)

  def visitArrow(n: ast.Arrow)(using context: Context): Value =
    unexpectedVisit(n)

  def visitSum(n: ast.Sum)(using context: Context): Value =
    unexpectedVisit(n)

  def visitParenthesizedType(n: ast.ParenthesizedType)(using context: Context): Value =
    unexpectedVisit(n)

  def visitValuePattern(n: ast.ValuePattern)(using context: Context): Value =
    unexpectedVisit(n)

  def visitRecordPattern(n: ast.RecordPattern)(using context: Context): Value =
    unexpectedVisit(n)

  def visitWildcard(n: ast.Wildcard)(using context: Context): Value =
    unexpectedVisit(n)

  def visitError(n: ast.ErrorTree)(using context: Context): Value =
    unexpectedVisit(n)

  /** Returns the built-in function or value corresponding to `e`. */
  private def builtin(e: Entity.Builtin): Value =
    Type.Arrow.from(e.tpe) match
      case Some(a) => Value.BuiltinFunction(e.name.identifier, a)
      case _ => throw Panic(s"undefined built-in entity '${e.name}'")

  /** Returns the value of the global entity `e`. */
  private def getGlobal(e: Entity): Option[Value] =
    globals.get(e) match
      case Some(v: Value.Unevaluated) =>
        v.declaration match
          case d: ast.Binding =>
            globals.put(e, Value.Poison)
            val computed = d.initializer.get.visit(this)(using Context())
            globals.put(e, computed)
            Some(computed)

          case d: ast.Function =>
            val computed = Value.Function(d, Type.Arrow.from(d.tpe).get)
            globals.put(e, computed)
            Some(computed)

          case _ => ???

      case Some(Value.Poison) => throw Panic("initialization cycle")
      case v => v

  /** Returns the result of applying `f` on arguments `a`. */
  private def call(f: Value, a: Seq[Value])(using context: Context): Value =
    f match
      case Value.Function(d, _) =>
        // creating next frame
        val names = d.inputs.map(_.nameDeclared)
        val newContext = names.zip(a).foldRight(context)((p, c) => c.defining(p))

        // visiting function tree
        d.body.visit(this)(using newContext)

      case l: Value.Lambda =>
        // creating next frame
        val names = l.inputs.map(_.nameDeclared)
        val newContext = names.zip(a).foldRight(context)((p, c) => c.defining(p))
        val contextWithcaptures = newContext.pushing(l.captures)

        // visiting the lambda body tree
        l.body.visit(this)(using contextWithcaptures)


      case Value.BuiltinFunction("exit", _) =>
        val Value.Builtin(status, _) = a.head : @unchecked
        throw Interpreter.Exit(status.asInstanceOf)

      case Value.BuiltinFunction("print", _) =>
        val text = a.head match
          case Value.Builtin(s : String, _) => s.substring(1, s.length - 1) // Remove quotes
          case v => v.toString
        standardOutput.write(text.getBytes(UTF_8))
        Value.unit

      case Value.BuiltinFunction("equality", _) =>
        Value.Builtin(a(0) == a(1), Type.Bool)
      case Value.BuiltinFunction("inequality", _) =>
        Value.Builtin(a(0) != a(1), Type.Bool)
      case Value.BuiltinFunction("lnot", _) =>
        applyBuiltinUnary[Boolean](a, Type.Bool)((b) => !b)
      case Value.BuiltinFunction("land", _) =>
        applyBuiltinBinary[Boolean](a, Type.Bool)((l, r) => l && r)
      case Value.BuiltinFunction("lor", _) =>
        applyBuiltinBinary[Boolean](a, Type.Bool)((l, r) => l || r)
      case Value.BuiltinFunction("ineg", _) =>
        applyBuiltinUnary[Int](a, Type.Int)((n) => -n)
      case Value.BuiltinFunction("iadd", _) =>
        applyBuiltinBinary[Int](a, Type.Int)((l, r) => l + r)
      case Value.BuiltinFunction("isub", _) =>
        applyBuiltinBinary[Int](a, Type.Int)((l, r) => l - r)
      case Value.BuiltinFunction("imul", _) =>
        applyBuiltinBinary[Int](a, Type.Int)((l, r) => l * r)
      case Value.BuiltinFunction("idiv", _) =>
        applyBuiltinBinary[Int](a, Type.Int)((l, r) => l / r)
      case Value.BuiltinFunction("irem", _) =>
        applyBuiltinBinary[Int](a, Type.Int)((l, r) => l % r)
      case Value.BuiltinFunction("ishl", _) =>
        applyBuiltinBinary[Int](a, Type.Int)((l, r) => l << r)
      case Value.BuiltinFunction("ishr", _) =>
        applyBuiltinBinary[Int](a, Type.Int)((l, r) => l >> r)
      case Value.BuiltinFunction("ilt", _) =>
        applyBuiltinComparison[Int](a)((l, r) => l < r)
      case Value.BuiltinFunction("ile", _) =>
        applyBuiltinComparison[Int](a)((l, r) => l <= r)
      case Value.BuiltinFunction("igt", _) =>
        applyBuiltinComparison[Int](a)((l, r) => l > r)
      case Value.BuiltinFunction("ige", _) =>
        applyBuiltinComparison[Int](a)((l, r) => l >= r)
      case Value.BuiltinFunction("iinv", _) =>
        applyBuiltinUnary[Int](a, Type.Int)((n) => ~n)
      case Value.BuiltinFunction("iand", _) =>
        applyBuiltinBinary[Int](a, Type.Int)((l, r) => l & r)
      case Value.BuiltinFunction("ior", _) =>
        applyBuiltinBinary[Int](a, Type.Int)((l, r) => l | r)
      case Value.BuiltinFunction("ixor", _) =>
        applyBuiltinBinary[Int](a, Type.Int)((l, r) => l ^ r)
      case Value.BuiltinFunction("fneg", _) =>
        applyBuiltinUnary[Float](a, Type.Float)((n) => -n)
      case Value.BuiltinFunction("fadd", _) =>
        applyBuiltinBinary[Float](a, Type.Float)((l, r) => l + r)
      case Value.BuiltinFunction("fsub", _) =>
        applyBuiltinBinary[Float](a, Type.Float)((l, r) => l - r)
      case Value.BuiltinFunction("fmul", _) =>
        applyBuiltinBinary[Float](a, Type.Float)((l, r) => l * r)
      case Value.BuiltinFunction("fdiv", _) =>
        applyBuiltinBinary[Float](a, Type.Float)((l, r) => l / r)
      case Value.BuiltinFunction("flt", _) =>
        applyBuiltinComparison[Float](a)((l, r) => l < r)
      case Value.BuiltinFunction("fle", _) =>
        applyBuiltinComparison[Float](a)((l, r) => l <= r)
      case Value.BuiltinFunction("fgt", _) =>
        applyBuiltinComparison[Float](a)((l, r) => l > r)
      case Value.BuiltinFunction("fge", _) =>
        applyBuiltinComparison[Float](a)((l, r) => l >= r)
      case _ =>
        throw Panic(s"value of type '${f.dynamicType}' is not a function")

  private def applyBuiltinUnary[T](
      a: Seq[Value], r: Type.Builtin
  )(f: T => T): Value.Builtin[T] =
    val Value.Builtin(b, _) = a.head : @unchecked
    Value.Builtin(f(b.asInstanceOf[T]), r)

  private def applyBuiltinBinary[T](
      a: Seq[Value], r: Type.Builtin
  )(f: (T, T) => T): Value.Builtin[T] =
    val Value.Builtin(lhs, _) = a(0) : @unchecked
    val Value.Builtin(rhs, _) = a(1) : @unchecked
    Value.Builtin(f(lhs.asInstanceOf[T], rhs.asInstanceOf[T]), r)

  private def applyBuiltinComparison[T](
      a: Seq[Value])(f: (T, T) => Boolean
  ): Value.Builtin[Boolean] =
    val Value.Builtin(lhs, _) = a(0) : @unchecked
    val Value.Builtin(rhs, _) = a(1) : @unchecked
    Value.Builtin(f(lhs.asInstanceOf[T], rhs.asInstanceOf[T]), Type.Bool)

  /** Returns a map from binding in `pattern` to its value iff `scrutinee` matches `pattern`.  */
  private def matches(
      scrutinee: Value, pattern: ast.Pattern
  )(using context: Context): Option[Interpreter.Frame] =
    pattern match
      case p: ast.Wildcard =>
        matchesWildcard(scrutinee, p)
      case p: ast.ValuePattern =>
        matchesValue(scrutinee, p)
      case p: ast.RecordPattern =>
        matchesRecord(scrutinee, p)
      case p: ast.Binding =>
        matchesBinding(scrutinee, p)
      case p: ast.ErrorTree =>
        unexpectedVisit(p)

  /** Returns a map from binding in `pattern` to its value iff `scrutinee` matches `pattern`.  */
  private def matchesWildcard(
      scrutinee: Value, pattern: ast.Wildcard
  )(using context: Context): Option[Interpreter.Frame] =
    Some(Map())

  /** Returns a map from binding in `pattern` to its value iff `scrutinee` matches `pattern`.  */
  private def matchesValue(
      scrutinee: Value, pattern: ast.ValuePattern
  )(using context: Context): Option[Interpreter.Frame] =
    if scrutinee == pattern.value.visit(this) then Some(Map()) else None

  /** Returns a map from binding in `pattern` to its value iff `scrutinee` matches `pattern`.  */
  private def matchesRecord(
      scrutinee: Value, pattern: ast.RecordPattern
  )(using context: Context): Option[Interpreter.Frame] =
    import Interpreter.Frame
    scrutinee match
      case s: Value.Record =>
        if s.fields.size != pattern.fields.size || s.identifier != pattern.identifier then None
        else
          val elements = s.fields.zip(pattern.fields).map((value, labelledPattern) => matches(value, labelledPattern.value))
          if elements.exists(_.isEmpty) then None
          else
            Some(
              elements.map(_.get).foldLeft(Map[Name, Value]())((acc, element) => acc ++ element)
            )
      case _ =>
        None

  /** Returns a map from binding in `pattern` to its value iff `scrutinee` matches `pattern`.  */
  private def matchesBinding(
      scrutinee: Value, pattern: ast.Binding
  )(using context: Context): Option[Interpreter.Frame] =
    if scrutinee.dynamicType != pattern.tpe then None
    else Some(Map(pattern.nameDeclared -> scrutinee))

end Interpreter

object Interpreter:

  /** A map from local symbol to its value. */
  type Frame = Map[symbols.Name, Value]

  /** The local state of an Alpine interpreter.
   *
   *  @param locals A stack of maps from local symbol to its value.
   */
  final class Context(val locals: List[Frame] = List()):

    /** Returns a copy of `this` in which a frame with the given local mapping has been pushed. */
    def defining(m: (symbols.Name, Value)): Context =
      pushing(Map(m))

    /** Returns a copy of `this` in which `f` has been pushed on the top of the locals. */
    def pushing(f: Frame): Context =
      Context(locals.prepended(f))

    /** Returns the value of `n` iff it is defined in the context's locals. */
    def getLocal(n: symbols.Name): Option[Value] =
      @tailrec def loop(frames: List[Interpreter.Frame]): Option[Value] =
        frames match
          case Nil => None
          case local :: fs => local.get(n) match
            case Some(d) => Some(d)
            case _ => loop(fs)
      loop(locals)

    /** A map from visible local symbol to its value. */
    def flattened: Frame =
      val partialResult = mutable.HashMap[symbols.Name, Value]()
      for
        frame <- locals
        (n, v) <- frame if !partialResult.isDefinedAt(n)
      do
        partialResult.put(n, v)
      partialResult.toMap

  end Context

  /** An exception thrown when the interpreter evaluated a call to `Builtin.exit`. */
  private final class Exit(val status: Int) extends Exception with NoStackTrace

end Interpreter
