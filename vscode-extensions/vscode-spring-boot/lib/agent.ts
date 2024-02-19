import { ActivatorOptions } from '@pivotal-tools/commons-vscode';
import * as vscode from 'vscode';
import { LanguageClient } from 'vscode-languageclient/node';
import { Uri, window } from 'vscode';
import cp from "child_process";
import { homedir } from 'os';
import { WorkspaceEdit } from "vscode-languageclient";
import path from 'path';
import fs from "fs";
import { getTargetGuideMardown, getWorkspaceRoot, getExecutable } from './utils/util';
import { createConverter } from "vscode-languageclient/lib/common/protocolConverter";
import { projectCreationPrompt } from './utils/create-spring-boot-project-prompt';
import { extractCodeBlocks } from './utils/response-handler';

interface Prompt {
    systemPrompt: string;
    userPrompt: string;
}

interface PromptResponse {
    description: string;
    shortPackageName: string;
    prompt: Prompt;
}

const CONVERTER = createConverter(undefined, true, true);
const LANGUAGE_MODEL_ID = 'copilot-gpt-4';

interface SpringBootChatAgentResult extends vscode.ChatAgentResult2 {
	metadata: {
        command: string;
    }
}

async function executeCommand(args: string[], cwd?: string): Promise<string> {
    const processOpts = { cwd: cwd || (await getWorkspaceRoot())?.fsPath || homedir() };
    const executable = getExecutable();
    const process = executable.endsWith(".jar") ? await cp.exec(`java -jar ${executable} ${args.join(" ")}`, processOpts) : await cp.exec(`${executable} ${args.join(" ")}`, processOpts);
    const dataChunks: string[] = [];
    process.stdout.on("data", s => dataChunks.push(s));
    return new Promise<string>((resolve, reject) => {
        process.on("exit", (code) => {
            if (code) {
                reject(`Failed to execute command: ${dataChunks.join()}`);
            } else {
                resolve(dataChunks.join());
            }
        });
    });
}

async function exec<T>(title: string, message: string, args: string[], cwd?: string): Promise<T> {
    return vscode.window.withProgress({
        location: vscode.ProgressLocation.Window,
        cancellable: true,
        title,
    }, async (progress, cancellation) => {

        if (message) {
            progress.report({message});
        }
        
        return new Promise<T>(async (resolve, reject) => {
            if (cancellation.isCancellationRequested) {
                reject("Cancelled");
            }
            try {
                const output: string = await executeCommand(args, cwd);
                console.log(output);
                resolve(output as T);
            } catch (error) {
                console.error(`Error: ${error}`);
                reject(error);
            }
        });
    });
}

async function fetchJson<T>(title: string, message: string, args: string[], cwd?: string): Promise<T> {
    return window.withProgress({
        location: vscode.ProgressLocation.Window,
        cancellable: true,
        title
    }, async (progress, cancellation) => {
        
        if (message) {
            progress.report({message});
        }
        return new Promise<T>(async (resolve, reject) => {
            if (cancellation.isCancellationRequested) {
                reject("Cancelled");
            }
            try {
                const output = await executeCommand(args, cwd);
                const extractJson = output.substring(output.indexOf('{'));
                resolve(JSON.parse(extractJson) as T);
            } catch (error) {
                reject(error);
            }
        });
    });
}

function springCliHandleAIPrompt(question: string, cwd: string): Thenable<PromptResponse> {

    const args = [
        "ai",
        "prompt",
        "--description",
        `"${question}"`
    ];
    return fetchJson("Spring cli ai prompt", question, args, cwd);
}

async function enhanceResponse(uri: Uri, projDescription: string, cwd: string) {
    const args = [
        "ai",
        "enhance-response",
        "--file",
        uri.fsPath
    ];
    const enhancedResponse: string = await exec("Spring cli ai", "Enhance response", args, cwd);
    writeResponseToFile(enhancedResponse, projDescription, cwd);
}


function fetchLspEdit(uri: Uri, cwd?: string): Promise<WorkspaceEdit> {
    const args = [
        "guide",
        "apply",
        "--lsp-edit",
        "--file",
        uri.fsPath
    ];
    return fetchJson("Spring cli ai", "Apply lsp edit", args, cwd || path.dirname(uri.fsPath));
}

export async function applyLspEdit(uri: Uri) {
    if (!uri) {
        uri = await getTargetGuideMardown();
    }
    const lspEdit = await fetchLspEdit(uri);
    const workspaceEdit = await CONVERTER.asWorkspaceEdit(lspEdit);

    await Promise.all(workspaceEdit.entries().map(async ([uri, edits]) => {
        if (fs.existsSync(uri.fsPath)) {
            const doc = await vscode.workspace.openTextDocument(uri.fsPath);
            await window.showTextDocument(doc);
        }
    }));
    
    return await vscode.workspace.applyEdit(workspaceEdit, {
        isRefactoring: true
    });
}

async function writeResponseToFile(response: string, shortPackageName: string, cwd: string) {
    const readmeFilePath =  path.resolve(cwd, `README-ai-${shortPackageName}.md`);
    if (fs.existsSync(readmeFilePath)) {
        try {
            fs.unlinkSync(readmeFilePath);
        } catch (ex) {
            throw new Error(`Could not delete readme file: ${readmeFilePath}, ${ex}`);
        }
    }
    
    try {
        fs.writeFileSync(readmeFilePath, response);
    } catch (ex) {
        throw new Error(`Could not write readme file: ${readmeFilePath}, ${ex}`);
    }
}

async function chatRequest(enhancedPrompt: PromptResponse, token: vscode.CancellationToken) {
    const access = await vscode.lm.requestLanguageModelAccess(LANGUAGE_MODEL_ID);

    const messages = [
            new  vscode.LanguageModelSystemMessage(enhancedPrompt.prompt.systemPrompt),
            new vscode.LanguageModelUserMessage(enhancedPrompt.prompt.userPrompt)
    ];
    let response = '';
    return vscode.window.withProgress({
        location: vscode.ProgressLocation.Window,
        title: "Copilot request",
        cancellable: true
    }, async (progress, cancellation) => {
        progress.report({ message: "processing" });
        return new Promise<string>(async (resolve, reject) => {
            if (cancellation.isCancellationRequested) {
                console.log("Chat request cancelled");
            }
            try {
                const chatRequest = access.makeChatRequest(messages, {}, token);
                for await (const fragment of chatRequest.stream) {
                    response += fragment;
                }
                resolve(response);
            } catch (error) {
                reject(error);
            }
        });
    });
}

async function handleAiPrompts(request: vscode.ChatAgentRequest, context: vscode.ChatAgentContext, stream: vscode.ChatAgentResponseStream, token: vscode.CancellationToken): Promise<SpringBootChatAgentResult> {

        if (request.command == 'prompt') {
            const cwd = (await getWorkspaceRoot()).fsPath;
            // get enhanced prompt by calling spring cli `prompt` command
            const enhancedPrompt = await springCliHandleAIPrompt(request.prompt, cwd);

            // chat request to copilot LLM
            const response = await chatRequest(enhancedPrompt, token);

            // write the response to markdown file
            await writeResponseToFile(response, enhancedPrompt.shortPackageName, cwd);

            const uri = await getTargetGuideMardown();
            // modify the response from copilot LLM i.e. make response Boot 3 compliant if necessary
            await enhanceResponse(uri, enhancedPrompt.shortPackageName, cwd);

            // return modified response to chat
            const documentContent = await vscode.workspace.fs.readFile(uri);
            const chatResponse = Buffer.from(documentContent).toString();
            stream.markdown(chatResponse);
            stream.button({
                command: 'vscode-spring-boot.agent.apply',
                title: vscode.l10n.t('Apply Changes!')
            });
			return { metadata: { command: 'prompt' }  };
        }  
}

async function handleCreateProject(request: vscode.ChatAgentRequest, context: vscode.ChatAgentContext, stream: vscode.ChatAgentResponseStream, token: vscode.CancellationToken): Promise<SpringBootChatAgentResult> {
    if (request.command == 'new') {
        const access = await vscode.lm.requestLanguageModelAccess(LANGUAGE_MODEL_ID);
        
        const systemPrompt = 'Your task is to create Java source code for a comprehensive Spring Boot application from scratch \n'+
        `# Instructions:
        \`\`\`
        ${projectCreationPrompt}\n
        \`\`\``;

        const messages = [
            new vscode.LanguageModelSystemMessage(systemPrompt),
            new vscode.LanguageModelUserMessage(request.prompt)
        ];
        const chatRequest = access.makeChatRequest(messages, {}, token);
        let entireResponse = '';
        for await (const fragment of chatRequest.stream) {
            entireResponse += fragment;            
        }
        const modifiedResponse = modifyResponse(entireResponse);
        stream.markdown(modifiedResponse);
        return { metadata: { command: 'new' }  };
    }
}

function modifyResponse(response) {
const { javaCodeBlocks, xmlCodeBlocks } = extractCodeBlocks(response);
return response;
}


export function activate(
    _client: LanguageClient,
    _options: ActivatorOptions,
    context: vscode.ExtensionContext
) {

    const agent = vscode.chat.createChatAgent('springboot', async (request, context, progress, token) => {
		if (request.command === 'prompt') {
			return handleAiPrompts(request, context, progress, token);
		} else if (request.command === 'new') {
			return handleCreateProject(request, context, progress, token);
		}
    });

    agent.description = vscode.l10n.t('Hi! How can I help you with your spring boot project?');
	agent.fullName = vscode.l10n.t('Spring Boot');
    agent.commandProvider = {
        provideCommands(token) {
            return [
                { name: 'prompt', description: 'Handle AI Prompts through Spring cli' },
                { name: 'new', description: 'Create a new spring boot project'}
            ];
        }
    };
}


