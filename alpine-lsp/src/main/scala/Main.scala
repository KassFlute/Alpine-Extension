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
  private val logFile = new PrintWriter(new File(LocalStrings.serverLogPath))

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

class MyLanguageServer {

  private var client: LanguageClient = _

  @JsonRequest("initialize")
  def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    println("initialize called")
    
    val capabilities = new ServerCapabilities()
    CompletableFuture.completedFuture(new InitializeResult(capabilities))
  }

  @JsonNotification("initialized")
  def initialized(): Unit = {
    println("initialized called")
  }

  @JsonRequest("shutdown")
  def shutdown(): CompletableFuture[AnyRef] = {
    println("shutdown called")
    CompletableFuture.completedFuture(null)
  }

  @JsonNotification("exit")
  def exit(): Unit = {
    println("exit called")
    System.exit(0)
  }

  // @JsonRequest("textDocument/completion")
  // def didOpen(params: DidOpenTextDocumentParams): Unit = {
  //   println("Text document opened: " + params.getTextDocument.getUri)
  // }

  // @JsonNotification("textDocument/didChange")
  // def didChange(params: DidChangeTextDocumentParams): Unit = {
  //   println("Text document changed: " + params.getTextDocument.getUri)
  // }

  // @JsonNotification("textDocument/didClose")
  // def didClose(params: DidCloseTextDocumentParams): Unit = {
  //   println("Text document closed: " + params.getTextDocument.getUri)
  // }

  // @JsonNotification("textDocument/didSave")
  // def didSave(params: DidSaveTextDocumentParams): Unit = {
  //   println("Text document saved: " + params.getTextDocument.getUri)
  // }

  @JsonNotification("workspace/didChangeConfiguration")
  def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = {
    println("didChangeConfiguration called with: " + params.toString())
  }

  // @JsonNotification("workspace/didChangeWatchedFiles")
  // def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = {
  //   println("Watched files changed")
  // }

  // def cancelProgress(params: WorkDoneProgressCancelParams): Unit = 
  //   throw new UnsupportedOperationException()

  def connect(client: LanguageClient): Unit = {
    println("connect called")
    this.client = client
  }

  // Implement other methods like textDocument/didOpen, didChange, etc.
}

object Main extends App {
  val DefaultPort = 5007
  val port = args.headOption.map(_.toInt).getOrElse {
    println(s"No ports specified, using default port $DefaultPort")
    DefaultPort
  }

  if (args.length > 1) {
    println("Too many arguments. Usage: \"run <port>\"")
    sys.exit(1)
  }

  val serverSocket = new ServerSocket(port)

  try {
    while (true) { // Loop to accept multiple clients
      try {
        println(s"Server listening on port $port")
        val clientSocket = serverSocket.accept()
        println(s"Client connected: ${clientSocket.getInetAddress}:${clientSocket.getPort}")

        new Thread(() => handleClient(clientSocket)).start()
      } catch {
        case e: Exception =>
          System.err.println(s"Error accepting client connection: ${e.getMessage}")
          e.printStackTrace()
      }
    }
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

  def handleClient(clientSocket: java.net.Socket): Unit = {
    try {
      val in: InputStream = clientSocket.getInputStream
      val out: OutputStream = clientSocket.getOutputStream

      val server = new MyLanguageServer()
      val launcher = new Launcher.Builder[LanguageClient]()
        .setRemoteInterface(classOf[LanguageClient])
        .setInput(in)
        .setOutput(out)
        .setLocalService(server)
        .create()

      val client: LanguageClient = launcher.getRemoteProxy()
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
}
