"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || function (mod) {
    if (mod && mod.__esModule) return mod;
    var result = {};
    if (mod != null) for (var k in mod) if (k !== "default" && Object.prototype.hasOwnProperty.call(mod, k)) __createBinding(result, mod, k);
    __setModuleDefault(result, mod);
    return result;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.deactivate = exports.activate = void 0;
// The module 'vscode' contains the VS Code extensibility API
// Import the module and reference it with the alias vscode in your code below
const vscode = __importStar(require("vscode"));
const path = __importStar(require("path"));
const node_1 = require("vscode-languageclient/node");
// Alpine LSP server
let client;
// This method is called when your extension is activated
// Your extension is activated the very first time the command is executed
function activate(context) {
    // Use the console to output diagnostic information (console.log) and errors (console.error)
    // This line of code will only be executed once when your extension is activated
    console.log('Congratulations, your extension "alpine-vscode" is now active!');
    // The command has been defined in the package.json file
    // Now provide the implementation of the command with registerCommand
    // The commandId parameter must match the command field in package.json
    let disposable = vscode.commands.registerCommand('alpine-vscode.helloWorld', () => {
        // The code you place here will be executed every time your command is executed
        // Display a message box to the user
        vscode.window.showInformationMessage('Hello World from alpine-vscode!');
    });
    context.subscriptions.push(disposable);
    // Run alpine LSP server
    const serverModule = context.asAbsolutePath(path.join('..', 'alpine-lsp', 'target', 'scala-2.13', 'alpine-lsp-assembly-1.0.jar'));
    const debugOptions = { execArgv: ['--nolazy', '--inspect=6009'] };
    // const serverOptions: ServerOptions = {
    // 	run: { module: serverModule, transport: TransportKind.ipc },
    // 	debug: { module: serverModule, transport: TransportKind.ipc, options: debugOptions }
    // };
    const serverOptions = {
        run: {
            command: 'java',
            transport: node_1.TransportKind.stdio,
            args: ['-jar', serverModule],
        },
        debug: {
            command: 'java',
            transport: node_1.TransportKind.stdio,
            args: ['-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005', '-jar', serverModule],
        }
    };
    const clientOptions = {
        documentSelector: [{ scheme: 'file', language: 'alpine' }],
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher('**/.clientrc')
        },
        outputChannel: vscode.window.createOutputChannel('Alpine LSP')
    };
    client = new node_1.LanguageClient('alpine-lsp', 'Alpine LSP', serverOptions, clientOptions);
    client.start();
}
exports.activate = activate;
// This method is called when your extension is deactivated
function deactivate() {
    if (!client) {
        return undefined;
    }
    return client.stop();
}
exports.deactivate = deactivate;
//# sourceMappingURL=extension.js.map