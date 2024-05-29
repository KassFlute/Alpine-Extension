package file

import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams

import alpine.{DiagnosticSet, SourceFile}
import alpine.driver
import alpine.util.toSubstring
import alpine.parsing.*

import scala.util.{Success, Failure}
import scala.collection.JavaConverters._
import java.sql.Driver

class Checker(client: LanguageClient) {
    private var files: Map[String, String] = Map()

    def update_file(name: String, content: String): Unit = {
        files = files.updated(name, content)
    }

    def check_syntax(name: String): Unit = {
        val sourceFile = SourceFile(name, files(name).codePoints().toArray()) // Create a SourceFile needed for the Parser
        val parser = Parser(sourceFile)
        val syntax = parser.program()
        val ds = parser.diagnostics
        //ds.throwOnError() // TODO see if it contains more info than the .log
        val diag = ds.elements.head
        val site = diag.site
        val level = diag.level
        val message = diag.summary
        ds.log()

        // Convert diagnostics to LSP4J diagnostics
        val diagnostics = ds.elements.map { d =>
            val start = sourceFile.lineAndColumn(d.site.start)
            val end = sourceFile.lineAndColumn(d.site.end)
            val range = new Range(
                new Position(start._1, start._2),
                new Position(end._1, end._2)
            )
            val severity = d.level match {
                case alpine.Diagnostic.Level.Warning => DiagnosticSeverity.Warning
                case alpine.Diagnostic.Level.Error => DiagnosticSeverity.Error
            }
            new Diagnostic(range, d.summary, severity, "ALPINE-LSP")
        }

        // Create PublishDiagnosticsParams and send to client
        val params = new PublishDiagnosticsParams(name, diagnostics.toList.asJava)
        client.publishDiagnostics(params)
    }
}
