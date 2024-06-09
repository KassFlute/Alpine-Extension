Alpine-Extension
============

A VS-Code extension for the Alpine language

## 💾 Installation

Alpine-extension can be installed via the VS Code [Marketplace](https://marketplace.visualstudio.com/items?itemName=alpine-vscode.alpine-vscode).

## 👽 Testing and Debugging

### Requierements
* java
* sbt
* VS-Code

### Setup
* set useDebuggingServer to true in extension.ts

* Run scala language server :
  
    run in alpine-lsp folder (without paramter to use default 5007 port):
    ```scala
    sbt run [port]
    ```
    The server will wait for clients to connect to the choosen port

* Run the extension in VS-Code :
  
    Open alpine-vscode in a VS-Code window
    Press ^f5 to run without debugging
    the extension will try to connect to a server on port 5007

* The extension is now connected to the language server and it can be tested in the opened VS-Code test window

## 🐣 Create release
* Compile scala project with ```sbt compile```
* Create jar with ```sbt assembly```
* Copy produced jar to ALPINE-VSCODE/server/
* run ```vsce publish```
