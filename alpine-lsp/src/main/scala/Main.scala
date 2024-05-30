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

import file.*
import alpine.{SourceFile, DiagnosticSet}

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
  private var checker: Checker = _

  @JsonRequest("initialize")
  def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    println("initialize called")
    
    val capabilities = new ServerCapabilities()

    val textDocumentSyncOptions = new TextDocumentSyncOptions()
    textDocumentSyncOptions.setSave(new SaveOptions(true))
    textDocumentSyncOptions.setChange(TextDocumentSyncKind.Full)
    textDocumentSyncOptions.setOpenClose(true)
    capabilities.setTextDocumentSync(textDocumentSyncOptions)
    
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

  @JsonNotification("textDocument/didChange")
  def didChange(params: DidChangeTextDocumentParams): Unit = {
    println("Text document changed: " + params.getTextDocument.getUri)
    val uri = params.getTextDocument.getUri
    val content = new String(params.getContentChanges.get(0).getText)
    checker.update_file(uri, content)
    checker.check_syntax(uri)
    checker.check_typing(uri)
    checker.publish_diagnostics(uri)
  }

  @JsonNotification("textDocument/didOpen")
  def didOpen(params: DidOpenTextDocumentParams): Unit = {
    println("Text document opened: " + params.getTextDocument.getUri)
    val uri = params.getTextDocument.getUri
    val content = new String(params.getTextDocument.getText)
    checker.update_file(uri, content)
    checker.check_syntax(uri)
    checker.publish_diagnostics(uri)
  }

  // @JsonNotification("textDocument/didClose")
  // def didClose(params: DidCloseTextDocumentParams): Unit = {
  //   println("Text document closed: " + params.getTextDocument.getUri)
  // }

  @JsonNotification("textDocument/didSave")
  def didSave(params: DidSaveTextDocumentParams): Unit = {
    println(s"Text document saved: " + params.getTextDocument.getUri)
    val uri = params.getTextDocument.getUri
    val path = java.nio.file.Paths.get(new java.net.URI(uri))
    val content = new String(java.nio.file.Files.readAllBytes(path))
    checker.update_file(uri, content)
    val correct_syntax = checker.check_syntax(uri)
    val correct_types = checker.check_typing(uri)
    checker.publish_diagnostics(uri)

    val messageParams = (correct_syntax, correct_types) match {
      case (true, true) => 
        new MessageParams(MessageType.Info, "File saved with no errors")
      case (false, true) => 
        new MessageParams(MessageType.Error, f"File saved with syntax errors. At line ${checker.get_diagnostics(uri).head.getRange().getStart().getLine()+1}")
      case (_, _) => 
        new MessageParams(MessageType.Error, f"File saved with errors. At line ${checker.get_diagnostics(uri).head.getRange().getStart().getLine()+1}")
    }
    client.showMessage(messageParams)
  }

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
    this.checker = new Checker(client)
    val connMessageParams = new MessageParams(MessageType.Info, "ALPINE-LSP Language Server connected")
    client.showMessage(connMessageParams)
  }

  // Implement other methods like textDocument/didOpen, didChange, etc.
}

object Main extends App {
  val acceptMultipleClients = false
  val defaultPort = 5007
  val port = args match {
    case null => 
      println(s"No ports specified, using default port $defaultPort")
      defaultPort
    case Array(port) => port.toInt
    case _ =>
      println("Too many arguments. Usage: \"run <port>\"")
      sys.exit(1)
  }

  val serverSocket = new ServerSocket(port)

  try {
    if (acceptMultipleClients) {
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
    } else {
      println(s"Server listening on port $port")
      val clientSocket = serverSocket.accept()
      println(s"Client connected: ${clientSocket.getInetAddress}:${clientSocket.getPort}")

      handleClient(clientSocket)
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
