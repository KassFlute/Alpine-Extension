// The module 'vscode' contains the VS Code extensibility API
// Import the module and reference it with the alias vscode in your code below
import * as vscode from 'vscode';
import * as path from 'path';
import { LanguageClient, LanguageClientOptions, ServerOptions, TransportKind } from 'vscode-languageclient/node';

// Alpine LSP server
let client: LanguageClient;

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

	// Run alpine LSP server
	const serverModule = context.asAbsolutePath(
		path.join('..', 'alpine-lsp', 'target', 'scala-2.13', 'alpine-lsp-assembly-1.0.jar')
	);
	const debugOptions = { execArgv: ['--nolazy', '--inspect=6009'] };

	// const serverOptions: ServerOptions = {
	// 	run: { module: serverModule, transport: TransportKind.ipc },
	// 	debug: { module: serverModule, transport: TransportKind.ipc, options: debugOptions }
	// };

	const serverOptions: ServerOptions = {
		run: {
			command: 'java',
			transport: TransportKind.stdio,
			args: ['-jar', serverModule],
		},
		debug: {
			command: 'java',
			transport: TransportKind.stdio,
			args: ['-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005', '-jar', serverModule],
		}
	};

	const clientOptions: LanguageClientOptions = {
		documentSelector: [{ scheme: 'file', language: 'alpine' }, 'plaintext'],
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
	if (!client) {
		return undefined;
	}
	return client.stop();
}
