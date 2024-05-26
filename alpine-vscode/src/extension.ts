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
let currentClient: LanguageClient;
let socket: net.Socket;

// This method is called when your extension is activated
// Your extension is activated the very first time the command is executed
export function activate(context: vscode.ExtensionContext) {

	// Use the console to output diagnostic information (console.log) and errors (console.error)
	// This line of code will only be executed once when your extension is activated
	console.log('Congratulations, your extension "alpine-vscode" is now active!');

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

	const client = new LanguageClient(
		'alpine-lsp',
		'Alpine LSP',
		serverOptions,
		clientOptions
	);
	currentClient = client;
	function registerCommand(command: string, callback: (...args: any[]) => any) {
	  context.subscriptions.push(vscode.commands.registerCommand(command, callback));
	}registerCommand('alpine-vscode.helloWorld', () => {
		// The code you place here will be executed every time your command is executed
		// Display a message box to the user
		vscode.window.showInformationMessage('Hello World from alpine-vscode!');
	});

	let channelOpen = false;
	registerCommand('alpine-vscode.toggleLogs', () => {
		if (channelOpen) {
		  client.outputChannel.hide();
		  channelOpen = false;
		} else {
		  client.outputChannel.show(true);
		  channelOpen = true;
		}
	  });

	client.start();

}

// This method is called when your extension is deactivated
export function deactivate(): Thenable<void> | undefined {
	if (currentClient) {
		currentClient.stop();
	}
	// Take care of the socket to let the server know we are closing the connection
	if (socket) {
		socket.end();
		socket.destroy();
	}

	return undefined;
}
