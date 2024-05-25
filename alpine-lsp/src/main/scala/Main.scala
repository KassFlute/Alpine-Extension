import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services._
import org.eclipse.lsp4j._ // I don't want to spend my life figuring out what to import
import org.eclipse.lsp4j.jsonrpc.services._
import java.util.concurrent.CompletableFuture
import java.io.{File, PrintWriter}
import java.time.LocalDateTime

object Logger {
  private val logFile = new PrintWriter(new File("/Users/cassien/Desktop/server.log"))

  def log(message: String): Unit = {
    logFile.write(s"${LocalDateTime.now}: $message\n")
    logFile.flush()
  }

  def close(): Unit = {
    logFile.close()
  }
}

object MyLanguageServer extends LanguageServer with LanguageClientAware {

  private var client: LanguageClient = _

  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    Logger.log("initialize called with params = " + params.toString())
    
    val capabilities = new ServerCapabilities()
    // Define your server capabilities here
    CompletableFuture.completedFuture(new InitializeResult(capabilities))
  }

  override def initialized(params: InitializedParams): Unit = {
    Logger.log("initialized called with params = " + params.toString())
  }

  override def initialized(): Unit = {
    Logger.log("initialized called")
  }

  override def shutdown(): CompletableFuture[AnyRef] = {
    Logger.log("shutdown called")
    CompletableFuture.completedFuture(null)
  }

  override def exit(): Unit = {
    Logger.log("exit called")
    System.exit(0)
  }

  override def getTextDocumentService(): TextDocumentService = new TextDocumentService {
    override def didOpen(params: DidOpenTextDocumentParams): Unit = {
      Logger.log("Text document opened: " + params.getTextDocument.getUri)
    }

    override def didChange(params: DidChangeTextDocumentParams): Unit = {
      Logger.log("Text document changed: " + params.getTextDocument.getUri)
    }

    override def didClose(params: DidCloseTextDocumentParams): Unit = {
      Logger.log("Text document closed: " + params.getTextDocument.getUri)
    }

    override def didSave(params: DidSaveTextDocumentParams): Unit = {
      Logger.log("Text document saved: " + params.getTextDocument.getUri)
    }
  }

  override def getWorkspaceService(): WorkspaceService = new WorkspaceService {
    override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = {
      Logger.log("Configuration changed")
    }

    override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = {
      Logger.log("Watched files changed")
    }
  }

  override def cancelProgress(params: WorkDoneProgressCancelParams): Unit = 
    super.cancelProgress(params)

  override def connect(client: LanguageClient): Unit = {
    Logger.log("Client connected")
    this.client = client
  }

  // Implement other methods like textDocument/didOpen, didChange, etc.
}

object Main extends App {
  //println("Server started")
  Logger.log("Starting server...")
  val launcher = LSPLauncher.createServerLauncher(MyLanguageServer, System.in, System.out)
  val future = launcher.startListening()
  future.get()
}
