package file

import alpine.{DiagnosticSet, SourceFile}
import alpine.driver
import alpine.util.toSubstring
import alpine.parsing.*

import scala.util.{Success, Failure}
import java.sql.Driver

class Checker {
  def check(sourceFile: SourceFile): Unit = {
    val parser = Parser(sourceFile)
    val syntax = parser.program()
    val ds = parser.diagnostics
    //ds.throwOnError()
    ds.log()
  }
}
