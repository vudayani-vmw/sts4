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
package org.springframework.ide.vscode.boot.java.data.jpa.queries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.lsp4j.DocumentFilter;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ide.vscode.commons.java.IJavaProject;
import org.springframework.ide.vscode.commons.java.SpringProjectUtil;
import org.springframework.ide.vscode.commons.languageserver.java.JavaProjectFinder;
import org.springframework.ide.vscode.commons.languageserver.semantic.tokens.SemanticTokenData;
import org.springframework.ide.vscode.commons.languageserver.semantic.tokens.SemanticTokensDataProvider;
import org.springframework.ide.vscode.commons.languageserver.semantic.tokens.SemanticTokensHandler;
import org.springframework.ide.vscode.commons.languageserver.semantic.tokens.SemanticTokensUtils;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleTextDocumentService;
import org.springframework.ide.vscode.commons.util.BadLocationException;
import org.springframework.ide.vscode.commons.util.text.LanguageId;
import org.springframework.ide.vscode.commons.util.text.TextDocument;
import org.springframework.ide.vscode.java.properties.antlr.parser.AntlrParser;
import org.springframework.ide.vscode.java.properties.parser.ParseResults;
import org.springframework.ide.vscode.java.properties.parser.PropertiesAst;
import org.springframework.ide.vscode.java.properties.parser.PropertiesAst.Value;

public class QueryPropertiesSemanticTokensHandler implements SemanticTokensHandler {
	
	private static final Logger log = LoggerFactory.getLogger(QueryPropertiesSemanticTokensHandler.class);

	private final JavaProjectFinder projectFinder;
	private final SimpleTextDocumentService documents;
	private final JpqlSemanticTokens jpqlTokensProvider;
	private final HqlSemanticTokens hqlTokensProvider;
	private final JpqlSupportState supportState;


	public QueryPropertiesSemanticTokensHandler(SimpleTextDocumentService documents, JavaProjectFinder projectFinder, JpqlSemanticTokens jpqlTokensProvider, HqlSemanticTokens hqlTokensProvider, JpqlSupportState supportState) {
		this.documents = documents;
		this.projectFinder = projectFinder;
		this.jpqlTokensProvider = jpqlTokensProvider;
		this.hqlTokensProvider = hqlTokensProvider;
		this.supportState = supportState;
	}

	@Override
	public SemanticTokensWithRegistrationOptions getCapability() {
		SemanticTokensWithRegistrationOptions capabilities = new SemanticTokensWithRegistrationOptions();
		DocumentFilter documentFilter = new DocumentFilter();
		documentFilter.setLanguage(LanguageId.JPA_QUERY_PROPERTIES.getId());
		capabilities.setDocumentSelector(List.of(documentFilter));
		capabilities.setFull(true);
		capabilities.setLegend(new SemanticTokensLegend(
				Stream.concat(jpqlTokensProvider.getTokenTypes().stream(), hqlTokensProvider.getTokenTypes().stream()).distinct().collect(Collectors.toList()),
				Stream.concat(jpqlTokensProvider.getTypeModifiers().stream(), hqlTokensProvider.getTypeModifiers().stream()).distinct().collect(Collectors.toList())));
		return capabilities;
	}

	@Override
	public SemanticTokens semanticTokensFull(SemanticTokensParams params, CancelChecker cancelChecker) {
		if (!supportState.isEnabled()) {
			return new SemanticTokens();
		}
		Optional<IJavaProject> optProject = projectFinder.find(params.getTextDocument());
		if (optProject.isPresent() && SpringProjectUtil.hasDependencyStartingWith(optProject.get(), "spring-data-jpa", null)) {
			TextDocument doc = documents.getLatestSnapshot(params.getTextDocument().getUri());
			if (doc != null) {
				SemanticTokensDataProvider tokensProvider = SpringProjectUtil.hasDependencyStartingWith(optProject.get(), "hibernate-core", null) ? hqlTokensProvider : jpqlTokensProvider;
				AntlrParser propertiesParser = new AntlrParser();
				ParseResults result = propertiesParser.parse(doc.get());
				List<SemanticTokenData> data = new ArrayList<>();
				for (PropertiesAst.KeyValuePair node : result.ast.getNodes(PropertiesAst.KeyValuePair.class)) {
					Value value = node.getValue();
					if (value != null) {
						data.addAll(tokensProvider.computeTokens(value.decode(), value.getOffset()));
					}
				}
				Collections.sort(data);
				SemanticTokensLegend legend = new SemanticTokensLegend(tokensProvider.getTokenTypes(), tokensProvider.getTypeModifiers());
				return new SemanticTokens(SemanticTokensUtils.mapTokensDataToLsp(data, legend, t -> {
					try {
						return doc.getLineOfOffset(t);
					} catch (BadLocationException e) {
						log.error("", e);
					}
					return -1;
				}, o -> {
					try {
						return o - doc.getLineOffset(doc.getLineOfOffset(o));
					} catch (BadLocationException e) {
						log.error("", e);
					}
					return -1;
				}));
			}
		}
		return SemanticTokensHandler.super.semanticTokensFull(params, cancelChecker);
	}
	

}
