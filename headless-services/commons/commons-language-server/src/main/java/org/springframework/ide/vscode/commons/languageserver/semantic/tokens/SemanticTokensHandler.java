/*******************************************************************************
 * Copyright (c) 2024 Broadcom, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Broadcom, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.commons.languageserver.semantic.tokens;

import java.util.List;

import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.springframework.ide.vscode.commons.util.text.TextDocument;

public interface SemanticTokensHandler {
	
	SemanticTokensWithRegistrationOptions getCapability();
	
	default List<SemanticTokenData> semanticTokensFull(TextDocument doc, CancelChecker cancelChecker) {
		return null;
	}

	default List<SemanticTokenData> semanticTokensRange(TextDocument doc, Range range, CancelChecker cancelChecker) {
		return null;
	}

}
