'use strict';


import * as VSCode from 'vscode';
import { RequestType } from 'vscode-languageclient';
import { LanguageClient } from 'vscode-languageclient/node';

export function registerClasspathService(client : LanguageClient) : void {

    let addRequest = new RequestType<ClasspathListenerParams, ClasspathListenerResponse, void>("sts/addClasspathListener");
    client.onRequest(addRequest, async (params: ClasspathListenerParams) => {
        return <ClasspathListenerResponse> await VSCode.commands.executeCommand("java.execute.workspaceCommand", "sts.java.addClasspathListener", params.callbackCommandId);
    });

    let removeRequest = new RequestType<ClasspathListenerParams, ClasspathListenerResponse, void>("sts/removeClasspathListener");
    client.onRequest(removeRequest, async (params: ClasspathListenerParams) => {
        return <ClasspathListenerResponse> await VSCode.commands.executeCommand("java.execute.workspaceCommand", "sts.java.removeClasspathListener", params.callbackCommandId);
    });

}

interface ClasspathListenerParams {
    callbackCommandId: string
}

interface ClasspathListenerResponse {
}
