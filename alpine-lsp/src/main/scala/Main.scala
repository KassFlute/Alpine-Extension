import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services._
import org.eclipse.lsp4j._ // I don't want to spend my life figuring out what to import
import org.eclipse.lsp4j.jsonrpc.services._
import java.util.concurrent.CompletableFuture

object MyLanguageServer extends LanguageServer with LanguageClientAware {

  private var client: LanguageClient = _

  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    //println("Server initializing...")
    
    val capabilities = new ServerCapabilities()
    // Define your server capabilities here
    CompletableFuture.completedFuture(new InitializeResult(capabilities))
  }

  override def initialized(params: InitializedParams): Unit = {
    //println("initialized with params = " + params.toString())
  }

  override def initialized(): Unit = {
    //println("initialized")
  }

  override def shutdown(): CompletableFuture[AnyRef] = {
    CompletableFuture.completedFuture(null)
  }

  override def exit(): Unit = {
    System.exit(0)
  }

  override def getTextDocumentService(): TextDocumentService = new TextDocumentService {
    override def didOpen(params: DidOpenTextDocumentParams): Unit = {
      //println("Text document opened: " + params.getTextDocument.getUri)
    }

    override def didChange(params: DidChangeTextDocumentParams): Unit = {
      //println("Text document changed: " + params.getTextDocument.getUri)
    }

    override def didClose(params: DidCloseTextDocumentParams): Unit = {
      //println("Text document closed: " + params.getTextDocument.getUri)
    }

    override def didSave(params: DidSaveTextDocumentParams): Unit = {
      //println("Text document saved: " + params.getTextDocument.getUri)
    }
  }

  override def getWorkspaceService(): WorkspaceService = new WorkspaceService {
    override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = {
      //println("Workspace configuration changed")
    }

    override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = {
      //println("Watched files changed")
    }
  }

  override def cancelProgress(params: WorkDoneProgressCancelParams): Unit = 
    super.cancelProgress(params)

  override def connect(client: LanguageClient): Unit = {
    this.client = client
  }

  // Implement other methods like textDocument/didOpen, didChange, etc.
}

object Main extends App {
  //println("Server started")
  val launcher = LSPLauncher.createServerLauncher(MyLanguageServer, System.in, System.out)
  val future = launcher.startListening()
  future.get()
}
