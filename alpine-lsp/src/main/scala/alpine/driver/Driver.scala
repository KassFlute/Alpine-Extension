package alpine
package driver

import scala.util.{Success, Failure}
import java.io.FileWriter
import java.io.File
import java.util.Calendar
import scala.compiletime.ops.boolean

def printTreeMap(map: Map[alpine.ast.Tree,Any]):String =
    val s_b = StringBuilder()
    map.keys.toList.sortWith((t1,t2) =>{
      if t1.site.start<t2.site.start then
         true 
      else if t1.site.start==t2.site.start then 
        if t1.site.end<t2.site.end then true
        else t1.site.gnu.length()<t2.site.gnu.length()
      else false}).foreach(t => {s_b ++= t.toString()+" -> "+map(t).toString()+"\n"})
    s_b.toString()
  
def printDeclMap(map: Map[alpine.ast.Declaration,Any]):String =
    val s_b = StringBuilder()
    map.keys.toList.sortWith((t1,t2) =>{
      if t1.site.start<t2.site.start then
         true 
      else if t1.site.start==t2.site.start then 
        if t1.site.end<t2.site.end then true
        else t1.site.gnu.length()<t2.site.gnu.length()
      else false}).foreach(t => {s_b ++= t.toString()+" -> "+map(t).toString()+"\n"})
    s_b.toString()
  
/** Run syntax analysis with the given `configuration`. */
def parse(configuration: Configuration): Program =
  val parser = parsing.Parser(configuration.inputs.head) // TODO: multiple inputs
  val syntax = parser.program()
  val ds = parser.diagnostics
  ds.throwOnError()
  ds.log()
  syntax

/** Run semantic analysis with the given `configuration`. */
def typeCheck(configuration: Configuration): TypedProgram =
  val syntax = parse(configuration)
  val typer = typing.Typer(configuration.traceInference)
  val typedSyntax = typer.check(syntax)

  val s_b = StringBuilder()
  val tsb = StringBuilder()
  var indent = 0
  
  
  //s_b ++= typedSyntax.declarationToNameDeclared.mkString("declarationToNameDeclared:\n","\n","\n")
  //s_b ++= "declarationToNameDeclared:\n"
  //s_b ++= printDeclMap(typedSyntax.declarationToNameDeclared)

  //s_b ++= typedSyntax.declarationToScope.mkString("\ndeclarationToScope:\n","\n","\n")
  //s_b ++= "\ndeclarationToScope:\n"
  //s_b ++= printDeclMap(typedSyntax.declarationToScope)

  //s_b ++= "\nentry:\n"+typedSyntax.entry+"\n"

  //s_b ++= typedSyntax.scopeToName.mkString("\nscopeToName:\n","\n","\n")
  //s_b ++= "\nscopeToName:\n"
  //s_b ++= printTreeMap(typedSyntax.scopeToName)

  //s_b ++= typedSyntax.treeToReferredEntity.mkString("\ntreeToReferredEntity:\n","\n","\n")
  s_b ++= "\ntreeToReferredEntity:\n"
  s_b ++= printTreeMap(typedSyntax.treeToReferredEntity)

  //s_b ++= typedSyntax.treeToType.mkString("\ntreeToType:\n","\n","\n")
  s_b ++= "\ntreeToType:\n"
  s_b ++= printTreeMap(typedSyntax.treeToType)

  tsb ++= "\ndeclarations:\n"
  typedSyntax.declarations.foreach(d => {d.toString().foreach(c =>{
    tsb ++= c.toString()
    if c == '(' then
      indent+=1
      tsb ++= "\n"
      tsb ++= "  " * indent
    if c == ',' then
      tsb ++= "\n"
      tsb ++= "  " * indent
    if c == ')' then
      indent-=1
    
  });tsb ++= "\n"})
  //s_b ++= tsb.toString()
  def getTimeStr: String =
    val time = Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Zurich")).getTime()
    f"${time.getYear()+1900}%04d-${(time.getMonth()+1)}%02d-${time.getDate()}%02d-${time.getHours()}%02d${time.getMinutes()}%02d${time.getSeconds()}%02d"
  
  //File("tmp/").mkdir(); val file = new File("tmp/typer_"+getTimeStr+".txt"); val fw = new FileWriter(file); fw.write(s_b.toString()); fw.close()
  
  //println("\n"+s_b.toString())

  val ds = typer.diagnostics
  if ds.containsError then ds.log()
  ds.throwOnError()
  ds.log()
  typedSyntax

/** Rewrites the input program in Scala using the given `configuration`. */
def transpile(configuration: Configuration): Unit =
  val typedSyntax = typeCheck(configuration)
  val transpiler = codegen.ScalaPrinter(typedSyntax)
  //val output = transpiler.transpile()
  configuration.standardOutput.write(transpiler.transpile().getBytes())
  //println(configuration.standardOutput.toString())

/** Interpret the input program with the given `configuration` and returns its exit status. */
def interpret(configuration: Configuration): Int =
  val typedSyntax = typeCheck(configuration)
  val interpreter = evaluation.Interpreter(
    typedSyntax,
    configuration.standardOutput,
    configuration.standardError)
  interpreter.run()
