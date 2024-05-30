Alpine-Extension
============

A VS-Code extension for the Alpine language

Requirements
------------

* sbt
* VS-Code

Setup
-----

### Run scala language server
run in alpine-lsp folder (without paramter to use default 5007 port):
```scala
sbt run [port]
```
The server will wait for clients to connect to the choosen port

### Run the extension in VS-Code
- Open alpine-vscode in a VS-Code window
- Press ^f5 to run without debugging

the extension will try to connect to a server on port 5007

### The extension is now connected to the language server and it can be tested in the opened VS-Code test window
