package alpine
package parsing

import alpine.ast
import alpine.util.FatalError

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.SeqView.Reverse
import scala.compiletime.ops.double
import alpine.symbols.Name.builtin
import alpine.symbols.Type.Sum
import alpine.symbols.Type.BuiltinModule

class Parser(val source: SourceFile):

  import ast.*
  import Token.Kind as K

  /** The token stream. */
  private var tokens = Lexer(source)

  /** The boundary of the last consumed token. */
  private var lastBoundary = 0

  /** The next token in the stream if it has already been produced. */
  private var lookahead: Option[Token] = None

  /** The errors collected by the parser. */
  private var errors = mutable.ListBuffer[SyntaxError]()

  /** A stack of predicates identifying tokens suitable for error recovery. */
  private var recoveryPredicates = mutable.ListBuffer[Token => Boolean]()

  /** The diagnostics collected by the parser. */
  def diagnostics: DiagnosticSet =
    DiagnosticSet(errors)

  // --- Declarations ---------------------------------------------------------

  /** Parses and returns a program. */
  def program(): alpine.Program =
    @tailrec def loop(partialResult: List[Declaration]): IArray[Declaration] =
      if peek != None then
        loop(partialResult :+ topLevel())
      else
        IArray.from(partialResult)
    Program(loop(List()))

  /** Parses and returns a top-level declaration. */
  def topLevel(): Declaration =
    peek match
      case Some(Token(K.Let, _)) =>
        binding()
      case Some(Token(K.Fun, _)) =>
        function()
      case Some(Token(K.Type, _)) =>
        typeDeclaration()
      case _ =>
        recover(ExpectedTree("top-level declaration", emptySiteAtLastBoundary), ErrorTree.apply)

  /** Parses and returns a binding declaration. */
  private[parsing] def binding(initializerIsExpected: Boolean = true): Binding =
    val lt = expect(K.Let)
    val id = take(K.Identifier)
      .map(s => s.site.text.toString)
      .getOrElse(missingName)

    val typ = take(K.Colon).map(_ => tpe())
    (initializerIsExpected,take(K.Eq).isDefined) match
      case (true,true) =>
        val exp = expression()
        Binding(id,typ,Some(exp),lt.site.extendedTo(exp.site.end))
      case (true,false) =>
        report(SyntaxError("Expected an initializer",emptySiteAtLastBoundary))
        Binding(id,typ,None,lt.site.extendedTo(typ.map(t => t.site.end).getOrElse(lt.site.end)))
      case (false,true) =>
        report(SyntaxError("Expected no initializer",emptySiteAtLastBoundary))
        Binding(id,typ,None,lt.site.extendedTo(typ.map(t => t.site.end).getOrElse(lt.site.end)))
      case (false,false) =>
        Binding(id,typ,None,lt.site.extendedTo(typ.map(t => t.site.end).getOrElse(lt.site.end)))

  /** Parses and returns a function declaration. */
  private[parsing] def function(): Function =
    val fn = expect(K.Fun)
    val id = functionIdentifier()
    val param = valueParameterList()
    val typ = take(K.Arrow).map(_ => tpe())
    expect(K.LBrace)
    val body = expression()
    val end = expect(K.RBrace)

    Function(id,List(),param,typ,body,fn.site.extendedTo(end.site.end))

  /** Parses and returns the identifier of a function. */
  private def functionIdentifier(): String =
    take(K.Identifier) match
      case Some(s) =>
        s.site.text.toString
      case _ if peek.map((t) => t.kind.isOperatorPart).getOrElse(false) =>
        operatorIdentifier()(1).text.toString
      case _ =>
        missingName

  /** Parses and returns a list of parameter declarations in parentheses. */
  private[parsing] def valueParameterList(): List[Parameter] =
    inParentheses(() => commaSeparatedList(K.RParen.matches, parameter))
      .collect({ case p: Parameter => p })

  /** Parses and returns a parameter declaration. */
  private[parsing] def parameter(): Declaration =
    val ss = snapshot()
    var source:SourceSpan = null
    var p:Parameter = null
    val n = take()
    .flatMap(s => {
      source = s.site
      (s.kind,s.kind.isKeyword) match
        case (K.Identifier,_) =>
          Some(s.site.text.toString)
        case (K.Underscore,_) =>
          None
        case (_,true) =>
          Some(s.site.text.toString)
        case (_,false) =>
          None
    })
    n match
      case Some(s:String) =>
        val v = identifier()
        source.extendedTo(v.site.end)
        val typ = take(K.Colon).map(_ => tpe())
        Parameter(n,v.value,typ,source)
      case None =>
        val v = identifier()
        source = v.site
        val typ = take(K.Colon).map(_ => tpe())
        Parameter(n,v.value,typ,source)

  /** Parses and returns a type declaration. */
  private[parsing] def typeDeclaration(): TypeDeclaration =
    val t = expect(K.Type)
    val id = take(K.Identifier)
      .map(s => s.site.text.toString)
      .getOrElse(missingName)
    expect(K.Eq)
    val typ = tpe()
    TypeDeclaration(id,List(),typ,t.site.extendedTo(typ.site.end))

  /** Parses and returns a list of parameter declarations in angle brackets. */
  //--- This is intentionally left in the handout /*+++ +++*/
  private def typeParameterList(): List[Parameter] =
    inAngles(() => commaSeparatedList(K.RAngle.matches, parameter))
      .collect({ case p: Parameter => p })

  // --- Expressions ----------------------------------------------------------

  /** Parses and returns a term-level expression. */
  def expression(): Expression =
    infixExpression()

  /** Parses and returns an infix expression. */
  private[parsing] def infixExpression(precedence: Int = ast.OperatorPrecedence.min): Expression =
    def operatorInfo(): (Int, Identifier) =
      val (optOp, opSite) = operatorIdentifier()
      val op = optOp.getOrElse(throw FatalError("", emptySiteAtLastBoundary))
      val id = Identifier(op.toString, opSite)
      val opPrecedance = op.precedence
      (opPrecedance, id)

    def inner(precedence: Int): (Expression, Option[(Int, Identifier)]) =
      val left = ascribed()
      if !peek.exists(t => t.kind.isOperatorPart) then
        (left, None)
      else
        val (opPrec, opId) = operatorInfo()

        if precedence < opPrec then
          val (right, nextOp) = inner(opPrec)
          (InfixApplication(opId, left, right, left.site.extendedTo(right.site.end)), nextOp)
        else
          (left, Some((opPrec, opId)))

    var (left, nextOp) = inner(precedence)
    while nextOp.isDefined do
      val (opPrec, opId) = nextOp.get
      val (right, op) = inner(opPrec)
      left = InfixApplication(opId, left, right, left.site.extendedTo(right.site.end))
      nextOp = op

    return left

  /** Parses and returns an expression with an optional ascription. */
  private[parsing] def ascribed(): Expression =
    val pre = prefixExpression()
    val p = peek
    p match
      case Some(Token(K.At,_)) =>
        val tyc = typecast()
        val typ = tpe()
        AscribedExpression(pre,tyc,typ,pre.site.extendedTo(typ.site.end))
      case Some(Token(K.AtBang,_)) => 
        val tyc = typecast()
        val typ = tpe()
        AscribedExpression(pre,tyc,typ,pre.site.extendedTo(typ.site.end))
      case Some(Token(K.AtQuery,_)) => 
        val tyc = typecast()
        val typ = tpe()
        AscribedExpression(pre,tyc,typ,pre.site.extendedTo(typ.site.end))
      case _=>
        pre

  /** Parses and returns a prefix application. */
  private[parsing] def prefixExpression(): Expression =
    val p = peek
    p match
      case Some(Token(K.Operator,_)) =>
        val op = operatorIdentifier()
        op._1 match
          case Some(value) =>
            val id = Identifier(op._2.text.toString,op._2)
            if !noWhitespaceBeforeNextToken then
              id 
            else
              val ce = compoundExpression()
              PrefixApplication(id,ce,id.site.extendedTo(ce.site.end))
          case None => 
            compoundExpression()
      case _ =>
        compoundExpression()

  /** Parses and returns a compound expression. */
  private[parsing] def compoundExpression(): Expression =
    def compExp(): List[Labeled[Expression]] = 
      peek match
        case Some(Token(K.Dot,_)) =>
          take()
          peek match
            case Some(Token(K.Integer,s)) =>
              List(Labeled(None,integerLiteral(),s)):::compExp()
            case Some(Token(K.Identifier,s)) =>
              List(Labeled(None,identifier(),s)):::compExp()
            case Some(Token(K.Operator,s)) =>
              List(Labeled(None,operator(),s)):::compExp()
            case _ =>
              List()
        case Some(Token(K.LParen,_)) =>
          parenthesizedLabeledList(expression):::compExp()
        case _ =>
          List()

    val prExp = primaryExpression()
    val p1 = peek
    p1 match
      case Some(Token(K.Dot,_)) =>
        take(K.Dot)
        val p2 = peek
        p2 match
          case Some(Token(K.Integer,s)) =>
            val i = integerLiteral()
            Selection(prExp,i,prExp.site.extendedTo(i.site.end))
          case Some(Token(K.Identifier,s)) =>
            val i = identifier()
            Selection(prExp,i,prExp.site.extendedTo(i.site.end))
          case Some(Token(K.Operator,s)) =>
            val o = operator()
            Selection(prExp,Identifier(o.site.text.toString,o.site),prExp.site.extendedTo(o.site.end))
          case _ =>
            val o = take().map(t => (t.site.text.toString,t.site)).getOrElse((missingName,emptySiteAtLastBoundary))
            Selection(prExp,Identifier(o._1,o._2),prExp.site.extendedTo(o._2.end))
      case Some(Token(K.LParen,_)) =>
        val p = parenthesizedLabeledList(expression)
        Application(prExp,p,SourceSpan(prExp.site.file,prExp.site.start,p.lastOption.map(l => l.site.end).getOrElse(prExp.site.end)))
      case _ =>
        prExp



  /** Parses and returns a term-level primary exression.
   *
   *  primary-expression ::=
   *    | value-identifier
   *    | integer-literal
   *    | float-literal
   *    | string-literal
   */
  private[parsing] def primaryExpression(): Expression =
    val p = peek
    p match
      case Some(Token(K.Identifier, s)) =>
        identifier()
      case Some(Token(K.True, _)) =>
        booleanLiteral()
      case Some(Token(K.False, _)) =>
        booleanLiteral()
      case Some(Token(K.Integer, _)) =>
        integerLiteral()
      case Some(Token(K.Float, _)) =>
        floatLiteral()
      case Some(Token(K.String, _)) =>
        stringLiteral()
      case Some(Token(K.Label, _)) =>
        recordExpression()
      case Some(Token(K.If, _)) =>
        conditional()
      case Some(Token(K.Match, _)) =>
        mtch()
      case Some(Token(K.Let, _)) =>
        let()
      case Some(Token(K.LParen, _)) =>
        lambdaOrParenthesizedExpression()
      case Some(t) if t.kind.isOperatorPart =>
        operator()
      case _ =>
        recover(ExpectedTree("expression", emptySiteAtLastBoundary), ErrorTree.apply)
  
  /** Parses and returns an Boolean literal expression. */
  private[parsing] def booleanLiteral(): BooleanLiteral =
    val s = expect("Boolean literal", K.True | K.False)
    BooleanLiteral(s.site.text.toString, s.site)

  /** Parses and returns an integer literal expression. */
  private[parsing] def integerLiteral(): IntegerLiteral =
    val s = expect(K.Integer)
    IntegerLiteral(s.site.text.toString, s.site)

  /** Parses and returns a floating-point literal expression. */
  private[parsing] def floatLiteral(): FloatLiteral =
    val s = expect(K.Float)
    FloatLiteral(s.site.text.toString, s.site)
    

  /** Parses and returns a string literal expression. */
  private[parsing] def stringLiteral(): StringLiteral =
    val s = expect(K.String)
    StringLiteral(s.site.text.toString, s.site)
    

  /** Parses and returns a term-level record expression. */
  private def recordExpression(): Record =
    val lab = expect(K.Label)
    val lel = recordExpressionFields()
    Record(lab.site.text.toString,lel,SourceSpan(lab.site.file,lab.site.start,lel.lastOption.map(l => l.site.end).getOrElse(lab.site.end)))


  /** Parses and returns the fields of a term-level record expression. */
  private def recordExpressionFields(): List[Labeled[Expression]] =
    peek match
      case Some(Token(K.LParen,_)) =>
        parenthesizedLabeledList(expression)
      case _ =>
        List()

  /** Parses and returns a conditional expression. */
  private[parsing] def conditional(): Expression =
    val i = expect(K.If)
    val e1 = expression()
    val t = expect(K.Then)
    val e2 = expression()
    val e = expect(K.Else)
    val e3 = expression()
    Conditional(e1,e2,e3,e1.site.extendedTo(e3.site.end))

  /** Parses and returns a match expression. */
  private[parsing] def mtch(): Expression =
    val m = expect(K.Match)
    val s = expression()
    val b = matchBody()
    Match(s,b,m.site.extendedTo(b.lastOption.map(lc => lc.site.end).getOrElse(m.site.end)))

  /** Parses and returns a the cases of a match expression. */
  private def matchBody(): List[Match.Case] =
    @tailrec def loop(partialResult: List[Match.Case]): List[Match.Case] =
      peek match
        case Some(Token(K.RBrace, _)) =>
          partialResult
        case Some(Token(K.Case, _)) =>
          loop(partialResult :+ matchCase())
        case _ =>
          report(ExpectedTokenError(K.Case, emptySiteAtLastBoundary))
          discardUntilRecovery()
          if peek == None then partialResult else loop(partialResult)
    inBraces({ () => recovering(K.Case.matches, () => loop(List())) })

  /** Parses and returns a case in a match expression. */
  private def matchCase(): Match.Case =
    val s = peek.map((t) => t.site)
    if take(K.Case) == None then
      report(ExpectedTokenError(K.Case, emptySiteAtLastBoundary))
    val p = pattern()
    if take(K.Then) == None then
      recover(
        ExpectedTokenError(K.Then, emptySiteAtLastBoundary),
        (e) => Match.Case(p, ErrorTree(e), s.get.extendedTo(lastBoundary)))
    else
      val b = expression()
      Match.Case(p, b, s.get.extendedTo(lastBoundary))

  /** Parses and returns a let expression. */
  private[parsing] def let(): Let =
    val l = binding()
    val b = inBraces(expression)
    Let(l,b,l.site.extendedTo(b.site.end))

  /** Parses and returns a lambda or parenthesized term-level expression. */
  private def lambdaOrParenthesizedExpression(): Expression =
    val snap = snapshot()
    try
      val params = valueParameterList()
      peek match
        case Some(Token(K.LBrace, _)) =>
          val body = inBraces(() => expression())
          Lambda(params, None, body, /*params.head.site.extendedTo(body.site.end)*/ body.site)
        case Some(Token(K.Arrow, _)) =>
          take(K.Arrow)
          val t = tpe()
          val body = inBraces(() => expression())
          Lambda(params, Some(t), body, params.head.site.extendedTo(body.site.end))
        case _ =>
          restore(snap)
          ParenthesizedExpression(inParentheses(() => expression()), emptySiteAtLastBoundary)
    catch case _ => 
      restore(snap)
      ParenthesizedExpression(inParentheses(() => expression()), emptySiteAtLastBoundary)


  /** Parses and returns an operator. */
  private def operator(): Expression =
    operatorIdentifier() match
      case (Some(o), p) => Identifier(p.text.toString, p)
      case (_, p) => ErrorTree(p)

  /** Parses and returns an operator identifier, along with its source positions.
   *
   *  If the the parsed operator is undefined, a diagnostic is reported and the returned identifier
   *  is `None`. In any case, the returned span represents the positions of the parsed identifier.
   */
  private def operatorIdentifier(): (Option[ast.OperatorIdentifier], SourceSpan) =
    import ast.OperatorIdentifier as O

    @tailrec def loop(start: Int, end: Int): (Option[ast.OperatorIdentifier], SourceSpan) =
      if takeIf((t) => t.isOperatorPartImmediatelyAfter(end)) != None then
        loop(start, lastBoundary)
      else
        val p = source.span(start, end)
        val s = p.text
        val o = if s == "||" then
          Some(O.LogicalOr)
        else if s == "&&" then
          Some(O.LogicalAnd)
        else if s == "<" then
          Some(O.LessThan)
        else if s == "<=" then
          Some(O.LessThanOrEqual)
        else if s == ">" then
          Some(O.GreaterThan)
        else if s == ">=" then
          Some(O.GreaterThanOrEqual)
        else if s == "==" then
          Some(O.Equal)
        else if s == "!=" then
          Some(O.NotEqual)
        else if s == "..." then
          Some(O.ClosedRange)
        else if s == "..<" then
          Some(O.HaflOpenRange)
        else if s == "+" then
          Some(O.Plus)
        else if s == "-" then
          Some(O.Minus)
        else if s == "|" then
          Some(O.BitwiseOr)
        else if s == "^" then
          Some(O.BitwiseXor)
        else if s == "*" then
          Some(O.Star)
        else if s == "/" then
          Some(O.Slash)
        else if s == "%" then
          Some(O.Percent)
        else if s == "&" then
          Some(O.Ampersand)
        else if s == "<<" then
          Some(O.LeftShift)
        else if s == ">>" then
          Some(O.RightShift)
        else if s == "~" then
          Some(O.Tilde)
        else if s == "!" then
          Some(O.Bang)
        else
          report(SyntaxError(s"undefined operator '${s}'", p))
          None
        (o, p)
    val p = peek.map(t =>t.site.text.toString).getOrElse("NONE")
    val dummy = 0
    val h = expect("operator", (t) => t.kind.isOperatorPart)
    loop(h.site.start, h.site.end)

  /** Parses and returns a type cast operator. */
  private def typecast(): Typecast =
    peek match
      case Some(Token(K.At, _)) =>
        take(); Typecast.Widen
      case Some(Token(K.AtQuery, _)) =>
        take(); Typecast.Narrow
      case Some(Token(K.AtBang, _)) =>
        take(); Typecast.NarrowUnconditionally
      case _ =>
        throw FatalError("expected typecast operator", emptySiteAtLastBoundary)

  // --- Types ----------------------------------------------------------------

  /** Parses and returns a type-level expression. */
  private[parsing] def tpe(): Type =
    def sumTpe(): ast.Sum = 
      val p1 = primaryType()
      val p = peek
      p match
        case Some(Token(K.Operator,_)) =>
          if p.get.site.text.toString == "|" then
            take(K.Operator)
            val p2 = sumTpe()
            alpine.ast.Sum(p1::p2.members,p1.site.extendedTo(p2.site.end))
          else
            alpine.ast.Sum(List(p1),p1.site)
        case _ =>
          alpine.ast.Sum(List(p1),p1.site)

    val p1 = primaryType()
    val p = peek
      p match
        case Some(Token(K.Operator,_)) =>
          if p.get.site.text.toString == "|" then
            take(K.Operator)
            val p2 = sumTpe()
            alpine.ast.Sum(p1::p2.members,p1.site.extendedTo(p2.site.end))
          else
            p1
        case _ =>
          p1

  /** Parses and returns a type-level primary exression. */
  private def primaryType(): Type =
    peek match
      case Some(Token(K.Identifier, s)) =>
        typeIdentifier()
      case Some(Token(K.Label, _)) =>
        recordType()
      case Some(Token(K.LParen, _)) =>
        arrowOrParenthesizedType()
      case _ =>
        recover(ExpectedTree("type expression", emptySiteAtLastBoundary), ErrorTree.apply)

  /** Parses and returns a type identifier. */
  private def typeIdentifier(): Type =
    val t = take().get
    TypeIdentifier(t.site.text.toString,t.site)

  /** Parses and returns a list of type arguments. */
  private def typeArguments(): List[Labeled[Type]] =
    parenthesizedLabeledList(tpe)

  /** Parses and returns a type-level record expressions. */
  private[parsing] def recordType(): RecordType =
    val lab = expect(K.Label)
    val lel = recordTypeFields()
    RecordType(lab.site.text.toString,lel,SourceSpan(lab.site.file,lab.site.start,lel.lastOption.map(l => l.site.end).getOrElse(lab.site.end)))
    

  /** Parses and returns the fields of a type-level record expression. */
  private[parsing] def recordTypeFields(): List[Labeled[Type]] =
    peek match
      case Some(Token(K.LParen,_)) =>
        parenthesizedLabeledList(tpe)
      case _ =>
        List()

  /** Parses and returns a arrow or parenthesized type-level expression. */
  private[parsing] def arrowOrParenthesizedType(): Type =
    val ss = snapshot()
    val lp = expect(K.LParen)
    var typ = tpe()
    take(K.RParen) match
      case Some(value) => 
        peek match
          case Some(Token(K.Arrow,_)) =>
            restore(ss)
            val ta = typeArguments()
            expect(K.Arrow)
            typ = tpe()
            Arrow(ta,typ,ta.headOption.map(h => h.site.extendedTo(typ.site.end))getOrElse(emptySiteAtLastBoundary))
          case _ =>
            ParenthesizedType(typ,typ.site)
      case _ =>
        restore(ss)
        val ta = typeArguments()
        expect(K.Arrow)
        typ = tpe()
        Arrow(ta,typ,ta.headOption.map(h => h.site.extendedTo(typ.site.end))getOrElse(emptySiteAtLastBoundary))


  // --- Patterns -------------------------------------------------------------

  /** Parses and returns a pattern. */
  private[parsing] def pattern(): Pattern =
    peek match
      case Some(Token(K.Underscore, _)) =>
        wildcard()
      case Some(Token(K.Label, _)) =>
        recordPattern()
      case Some(Token(K.Let, _)) =>
        bindingPattern()
      case _ =>
        valuePattern()

  /** Parses and returns a wildcard pattern. */
  def wildcard(): Wildcard =
    Wildcard(expect(K.Underscore).site)

  /** Parses and returns a record pattern. */
  private def recordPattern(): RecordPattern =
    record(recordPatternFields,(s,l,p) => RecordPattern(s,l,p))

  /** Parses and returns the fields of a record pattern. */
  private def recordPatternFields(): List[Labeled[Pattern]] =
    parenthesizedLabeledList(pattern)

  /** Parses and returns a binding pattern. */
  private def bindingPattern(): Binding =
    binding(false)

  /** Parses and returns a value pattern. */
  private def valuePattern(): ValuePattern =
    val exp = expression()
    ValuePattern(exp,exp.site)

  // --- Common trees ---------------------------------------------------------

  /** Parses and returns an identifier. */
  private def identifier(): Identifier =
    val s = expect(K.Identifier)
    Identifier(s.site.text.toString, s.site)

  // --- Combinators ----------------------------------------------------------

  /** Parses and returns a record.
   *
   *  @param fields A closure parsing the fields of the record.
   *  @param make A closure constructing a record tree from its name, fields, and site.
   */
  private def record[Field <: Labeled[Tree], T <: RecordPrototype[Field]](
      fields: () => List[Field],
      make: (String, List[Field], SourceSpan) => T
  ): T =
    val p1 = peek
    p1 match
      case Some(Token(K.Label,_)) =>
        val l = take(K.Label)
        val lst = l.map(t => t.site.text.toString).getOrElse(missingName)
        val lsp = l.map(t => t.site).getOrElse(emptySiteAtLastBoundary)
        val p2 = peek
        p2 match
          case Some(Token(K.LParen,_)) =>
            val f = fields()
            val r = make(lst,f,lsp.extendedTo(f.lastOption.map(fi => fi.site.end).getOrElse(lsp.end)))
            val dummy = 0
            return r
          case _ =>
            val r = make(lst,List(),lsp)
            val dummy = 0
            return r
      case _ =>
        val f = fields()
        val r = make(missingName,f,f.headOption.map(fh => fh.site.extendedTo(f.lastOption.map(ft => ft.site.end).getOrElse(fh.site.end))).getOrElse(emptySiteAtLastBoundary))
        val dummy = 0
        return r

  /** Parses and returns a parenthesized list of labeled value.
   *
   *  See also [[this.labeledList]].
   *
   *  @param value A closure parsing a labeled value.
   */
  private[parsing] def parenthesizedLabeledList[T <: Tree](
      value: () => T
  ): List[Labeled[T]] =
    inParentheses({() => commaSeparatedList(K.RParen.matches,{() => {labeled}.apply(value).asInstanceOf[Labeled[T]]})})
    
    

  /** Parses and returns a value optionally prefixed by a label.
   *
   *  This combinator attempts to parse a label `n` followed by a colon and then applies `value`.
   *  If that succeeds, returned tree is the result of `value` labeled by `n`. If there is no label,
   *  the combinator backtracks, re-applies `value`, and returns its result sans label.
   *
   *  @param value A closure parsing a labeled value.
   */
  private[parsing] def labeled[T <: Tree](
      value: () => T
  ): Labeled[T] =
    val ss = snapshot()
    var source:SourceSpan = null
    val n = take()
    .flatMap(s => {
      source = s.site
      (s.kind,s.kind.isKeyword) match
        case (K.Identifier,_) =>
          Some(s.site.text.toString)
        case (_,true) =>
          Some(s.site.text.toString)
        case (_,false) =>
          None
    })
    n match
      case Some(s:String) =>
        take(K.Colon) match
          case Some(mv) =>
            val v = value()
            source.extendedTo(v.site.end)
            return Labeled(n,v,source)
          case None =>
      case None =>
    restore(ss)
    val v = value()
    source = v.site
    Labeled(None,v,source)


  /** Parses and returns a sequence of `element` separated by commas and delimited on the RHS  by a
   *  token satisfying `isTerminator`.
   */
  private[parsing] def commaSeparatedList[T](isTerminator: Token => Boolean, element: () => T): List[T] =
    @tailrec def loop(partialResult: List[T]): List[T] =
      if peek.map(isTerminator).getOrElse(false) then
        partialResult
      else
        val nextPartialResult = partialResult :+ recovering(K.Comma.matches, element)
        if peek.map(isTerminator).getOrElse(false) then
          nextPartialResult
        else if take(K.Comma) != None then
          loop(nextPartialResult)
        else
          report(ExpectedTokenError(K.Comma, emptySiteAtLastBoundary))
          loop(nextPartialResult)
    loop(List())

  /** Parses and returns `element` surrounded by a pair of parentheses. */
  private[parsing] def inParentheses[T](element: () => T): T =
    expect(K.LParen)
    val e = element()
    expect(K.RParen)
    e

  /** Parses and returns `element` surrounded by a pair of braces. */
  private[parsing] def inBraces[T](element: () => T): T =
    expect(K.LBrace)
    val e = element()
    expect(K.RBrace)
    e

  /** Parses and returns `element` surrounded by angle brackets. */
  private[parsing] def inAngles[T](element: () => T): T =
    expect(K.LAngle)
    val e = element()
    expect(K.RAngle)
    e

  /** Parses and returns `element` surrounded by a `left` and `right`. */
  private[parsing] def delimited[T](left: Token.Kind, right: Token.Kind, element: () => T): T =
    if take(left) == None then
      report(ExpectedTokenError(right, emptySiteAtLastBoundary))
    val contents = recovering(right.matches, element)
    if take(right) == None then
      report(ExpectedTokenError(right, emptySiteAtLastBoundary))
    contents

  /** Returns the result of `element` with `isRecoveryToken` added to the recovery predicates. */
  private def recovering[T](isRecoveryToken: Token => Boolean, element: () => T): T =
    recoveryPredicates += isRecoveryToken
    try
      element()
    finally
      recoveryPredicates.dropRightInPlace(1)

  // --- Utilities ------------------------------------------------------------

  /** Returns `true` iff there isn't any whitespace before the next token in the stream. */
  private def noWhitespaceBeforeNextToken: Boolean =
    peek.map((t) => lastBoundary == t.site.start).getOrElse(false)

  /** Reports a missing identifier and returns "_". */
  def missingName =
    report(ExpectedTokenError(K.Identifier, emptySiteAtLastBoundary))
    "_"

  /** Reports `error`, advances the stream to the next recovery token, and returns the result of
   *  calling `errorTree` on the skipped positions.
   */
  private def recover[T](error: SyntaxError, errorTree: SourceSpan => T): T =
    report(error)
    errorTree(discardUntilRecovery())

  /** Advances the stream to the next recovery token and returns the skipped positions. */
  private def discardUntilRecovery(): SourceSpan =
    @tailrec def loop(s: Int): SourceSpan =
      if !peek.isDefined || Reverse(recoveryPredicates).exists((p) => p(peek.get)) then
        source.span(s, lastBoundary)
      else
        take()
        loop(s)
    loop(lastBoundary)

  /** Consumes and returns the next token in the stream iff it has kind `k` or throw an exception
    * otherwise,
    */
  private def expect(construct: String, predicate: (Token) => Boolean): Token =
    takeIf(predicate) match
      case Some(next) => next
      case _ => throw FatalError(s"expected ${construct}", emptySiteAtLastBoundary)

  /** Consumes and returns the next token in the stream iff it has kind `k` or throw an exception
    * otherwise,
    */
  private def expect(k: Token.Kind): Token =
    take(k) match
      case Some(next) => next
      case _ => 
        val x = take()
        throw FatalError(s"expected ${k}", emptySiteAtLastBoundary)

  /** Returns the next token in the stream without consuming it. */
  private def peek: Option[Token] =
    if lookahead == None then
      lookahead = tokens.next()
    lookahead

  /** Consumes the next token in the stream iff it has kind `k` and returns the result of `action`
   *  applied on that token. */
  private def taking[T](k: Token.Kind, action: Token => T): Option[T] =
    take(k).map(action)

  /** Consumes and returns the next token in the stream. */
  private def take(): Option[Token] =
    val p = peek.map({ (next) =>
      lastBoundary = next.site.end
      lookahead = None
      next
    })
    p

  /** Consumes and returns the next token in the stream iff it has kind `k`. */
  private def take(k: Token.Kind): Option[Token] =
    takeIf(k.matches)

  /** Consumes and returns the next character in the stream iff it satisfies `predicate`. */
  private def takeIf(predicate: Token => Boolean): Option[Token] =
    if peek.map(predicate).getOrElse(false) then take() else None

  /** Returns an empty range at the position of the last consumed token. */
  private def emptySiteAtLastBoundary: SourceSpan =
    source.span(lastBoundary, lastBoundary)

  /** Reports the given diagnostic. */
  private def report(d: SyntaxError): Unit =
    errors += d

  /** Returns a backup of this instance's state. */
  private[parsing] def snapshot(): Parser.Snapshot =
    Parser.Snapshot(
      tokens.copy(), lastBoundary, lookahead, errors.length, recoveryPredicates.length)

  /** Restores this instance to state `s`. */
  private[parsing] def restore(s: Parser.Snapshot): Unit =
    tokens = s.tokens
    lastBoundary = s.lastBoundary
    lookahead = s.lookahead
    errors.dropRightInPlace(errors.length - s.errorCount)
    recoveryPredicates.dropRightInPlace(recoveryPredicates.length - s.recoveryPredicateCount)

end Parser

object Parser:

  /** The information necessary to restore the state of a parser instance. */
  private[parsing] final case class Snapshot(
      tokens: Lexer,
      lastBoundary: Int,
      lookahead: Option[Token],
      errorCount: Int,
      recoveryPredicateCount: Int)

end Parser

extension (self: Token.Kind) def | (other: Token.Kind): (Token) => Boolean =
  (t) => (t.kind == self) || (t.kind == other)

extension (self: Token => Boolean) def | (other: Token.Kind): (Token) => Boolean =
  (t) => self(t) || (t.kind == other)