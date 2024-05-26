// The module 'vscode' contains the VS Code extensibility API
// Import the module and reference it with the alias vscode in your code below
import * as fs from "fs";
import * as vscode from 'vscode';
import * as path from 'path';
import * as net from 'net';
import * as childProcess from "child_process";
import { workspace, Disposable, ExtensionContext } from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions, TransportKind, StreamInfo } from 'vscode-languageclient/node';

// Alpine LSP server
let client: LanguageClient;
let socket: net.Socket;

// This method is called when your extension is activated
// Your extension is activated the very first time the command is executed
export function activate(context: vscode.ExtensionContext) {

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


	let connectionInfo = {
		port: 5007,
		host: "127.0.0.1"
	};

	const serverOptions: ServerOptions = () => {
		// Connect to language server via socket
		socket = net.connect(connectionInfo);
		let result: StreamInfo = {
			writer: socket,
			reader: socket
		};
		return Promise.resolve(result);
	};

	const clientOptions: LanguageClientOptions = {
		documentSelector: [{ scheme: 'file', language: 'alpine' }],
		synchronize: {
			configurationSection: 'alpine',
			fileEvents: vscode.workspace.createFileSystemWatcher('**/.clientrc')
		},
		outputChannel: vscode.window.createOutputChannel('Alpine LSP')
	};

	client = new LanguageClient(
		'alpine-lsp',
		'Alpine LSP',
		serverOptions,
		clientOptions
	);

	client.start();
}

// This method is called when your extension is deactivated
export function deactivate(): Thenable<void> | undefined {
	if (client) {
		client.stop();
	}

	if (socket) {
		socket.end();
		socket.destroy();
	}

	return undefined;
}
