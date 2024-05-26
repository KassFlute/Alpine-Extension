import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services._
import org.eclipse.lsp4j._ // I don't want to spend my life figuring out what to import
import org.eclipse.lsp4j.jsonrpc.services._
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.io.{File, PrintWriter, IOException, InputStream, OutputStream, PrintStream, ByteArrayOutputStream}
import java.time.LocalDateTime
import java.net.ServerSocket
import java.net.Socket

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

class LoggerOutputStream(out: PrintStream) extends java.io.OutputStream {
  private val buffer = new StringBuilder()
  private var braceCount = 0

  override def write(b: Int): Unit = {
    if (b == '{') {
      braceCount += 1
    } else if (b == '}') {
      braceCount -= 1
      if (braceCount == 0) {
        flushBuffer()
      }
    }

    buffer.append(b.toChar)

    if (b == '\n') {
      flushBuffer()
      braceCount = 0
    }
    out.write(b)
  }

  private def flushBuffer(): Unit = {
    Logger.log("from stdout: " + buffer.toString())
    buffer.setLength(0)
  }
}

object MyLanguageServer extends LanguageServer with LanguageClientAware {

  private var client: LanguageClient = _

  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    println("initialize called")
    Logger.log("initialize called with params = " + params.toString())
    
    val capabilities = new ServerCapabilities()
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
  if (args.length == 0) {
    println("No ports specified, using default port 5007")
  } else if (args.length > 1) {
    println("Too many arguments. Usage: \"run <port>\"")
  }

  val port = if (args.length == 0) 5007 else args(0).toInt
  val serverSocket = new ServerSocket(port)
  println(s"Server listening on port $port")

  // Method to handle a single client connection
  def handleClient(clientSocket: Socket): Unit = {
    try {
      println(s"Client connected: ${clientSocket.getInetAddress}:${clientSocket.getPort}")

      // Set up input and output streams for communication with the client
      val in: InputStream = clientSocket.getInputStream
      val out: OutputStream = clientSocket.getOutputStream

      // Create and launch the language server for the connected client
      val server = MyLanguageServer
      val launcher: Launcher[LanguageClient] = LSPLauncher.createServerLauncher(server, in, out)
      val client: LanguageClient = launcher.getRemoteProxy
      server.connect(client)
      val future = launcher.startListening()

      future.get() // Wait for the server to finish listening
    } catch {
      case e: Exception =>
        System.err.println(s"Error handling client: ${e.getMessage}")
        e.printStackTrace()
    } finally {
      try {
        clientSocket.close()
      } catch {
        case e: Exception =>
          System.err.println(s"Error closing client socket: ${e.getMessage}")
          e.printStackTrace()
      }
    }
  }

  try {
    while (true) {
      try {
        // Accept a single client connection
        val clientSocket = serverSocket.accept()
        handleClient(clientSocket)
        println("Client connection closed")
      } catch {
        case e: Exception =>
          System.err.println(s"Error accepting client connection: ${e.getMessage}")
          e.printStackTrace()
      }
    }
  } catch {
    case e: Exception =>
      System.err.println(s"Server error: ${e.getMessage}")
      e.printStackTrace()
  } finally {
    try {
      serverSocket.close()
      println("Server socket closed.")
    } catch {
      case e: Exception =>
        System.err.println(s"Error closing server socket: ${e.getMessage}")
        e.printStackTrace()
    }
  }
}
