package file

import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams

import alpine.{DiagnosticSet, SourceFile, Program}
import alpine.driver
import alpine.util.toSubstring
import alpine.parsing.*
import alpine.typing.*

import scala.util.{Success, Failure}
import scala.collection.JavaConverters._

class Checker(client: LanguageClient) {
    private var files: Map[String, String] = Map() // Map of file uri to file content
    private var diagnostics: Map[String, List[Diagnostic]] = Map() // Map of file uri to diagnostics
    private var parsed: Map[String, Program] = Map() // Map of file uri to parsed program by the parser (null if syntax error)

    def update_file(uri: String, content: String): Unit = {
        // Store file content
        files = files.updated(uri, content)

        // Reset diagnostics
        diagnostics = diagnostics.updated(uri, List())
    }

    def check_syntax(uri: String): Boolean = {
        val name = uri.substring(uri.lastIndexOf("/") + 1)
        val sourceFile = SourceFile(name, files(uri).codePoints().toArray()) // Create a SourceFile needed for the Parser
        val parser = Parser(sourceFile)
        val syntax = parser.program()
        val ds = parser.diagnostics
        //ds.throwOnError() // TODO see if it contains more info than the .log
        ds.log()

        // store parsed program if no syntax errors
        if (ds.elements.isEmpty) then
            parsed = parsed.updated(uri, syntax)
        else
            parsed = parsed.updated(uri, null)

        // Convert diagnostics to LSP4J diagnostics
        val diagnostics = ds.elements.map { d =>
            val start = sourceFile.lineAndColumn(d.site.start)
            val end = sourceFile.lineAndColumn(d.site.end)
            val range = new Range(
                new Position(start._1 - 1, start._2 - 1),
                new Position(end._1 - 1, end._2 - 1)
            )
            val severity = d.level match {
                case alpine.Diagnostic.Level.Warning => DiagnosticSeverity.Warning
                case alpine.Diagnostic.Level.Error => DiagnosticSeverity.Error
            }
            new Diagnostic(range, d.summary, severity, "ALPINE-LSP")
        }

        // Append to diagnostics
        this.diagnostics = this.diagnostics.updated(uri, this.diagnostics.getOrElse(uri, List()) ++ diagnostics.toList)

        ds.elements.isEmpty
    }

    def check_typing(uri: String): Boolean = {
        val name = uri.substring(uri.lastIndexOf("/") + 1)
        val sourceFile = SourceFile(name, files(uri).codePoints().toArray()) // Create a SourceFile needed for the Parser

        val program = parsed(uri)
        if program == null then return false
        val typer = Typer(true)
        val typedSyntax = typer.check(program)
        val ds = typer.diagnostics
        ds.log()

        // Convert diagnostics to LSP4J diagnostics
        val diagnostics = ds.elements.map { d =>
            val start = sourceFile.lineAndColumn(d.site.start)
            val end = sourceFile.lineAndColumn(d.site.end)
            val range = new Range(
                new Position(start._1 - 1, start._2 - 1),
                new Position(end._1 - 1, end._2 - 1)
            )
            val severity = d.level match {
                case alpine.Diagnostic.Level.Warning => DiagnosticSeverity.Warning
                case alpine.Diagnostic.Level.Error => DiagnosticSeverity.Error
            }
            new Diagnostic(range, d.summary, severity, "ALPINE-LSP")
        }
        
        // Append to diagnostics
        this.diagnostics = this.diagnostics.updated(uri, this.diagnostics.getOrElse(uri, List()) ++ diagnostics.toList)

        ds.elements.isEmpty
    }

    def publish_diagnostics(uri: String): Unit = {
        val params = new PublishDiagnosticsParams(uri, diagnostics(uri).asJava)
        client.publishDiagnostics(params)
    }
    
    def get_diagnostics(uri: String): List[Diagnostic] = {
        diagnostics(uri)
    }
}
