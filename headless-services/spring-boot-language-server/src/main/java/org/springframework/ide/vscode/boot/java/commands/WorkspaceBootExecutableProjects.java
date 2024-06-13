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
package org.springframework.ide.vscode.boot.java.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ide.vscode.boot.app.SpringSymbolIndex;
import org.springframework.ide.vscode.boot.java.Annotations;
import org.springframework.ide.vscode.commons.java.IJavaProject;
import org.springframework.ide.vscode.commons.java.SpringProjectUtil;
import org.springframework.ide.vscode.commons.languageserver.java.JavaProjectFinder;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleLanguageServer;
import org.springframework.ide.vscode.commons.protocol.java.Classpath;
import org.springframework.ide.vscode.commons.protocol.java.Gav;
import org.springframework.ide.vscode.commons.protocol.java.ProjectGavParams;
import org.springframework.ide.vscode.commons.protocol.spring.Bean;
import org.springframework.ide.vscode.commons.protocol.spring.BeansParams;

public class WorkspaceBootExecutableProjects {
	
	public record ExecutableProject(String name, String uri, String gav, String mainClass, Collection<String> classpath, String buildTool, String springBootVersion, String javaVersion) {}
	
	final static String CMD = "sts/spring-boot/executableBootProjects";
	
	private final static Logger log = LoggerFactory.getLogger(WorkspaceBootExecutableProjects.class);
	
	final private JavaProjectFinder projectFinder;
	final private SpringSymbolIndex symbolIndex;
	final private SimpleLanguageServer server;

	public WorkspaceBootExecutableProjects(SimpleLanguageServer server, JavaProjectFinder projectFinder, SpringSymbolIndex symbolIndex) {
		this.server = server;
		this.projectFinder = projectFinder;
		this.symbolIndex = symbolIndex;
		server.onCommand(CMD, params -> findExecutableProjects());
	}
	
	private CompletableFuture<Optional<ExecutableProject>> mapToExecProject(IJavaProject project) {
		BeansParams params = new BeansParams();
		params.setProjectName(project.getElementName());
		return symbolIndex.beans(params).thenApply(beans -> {
			List<Bean> bootAppBeans = beans.stream()
					.filter(b -> Arrays.asList(b.getAnnotations()).contains(Annotations.BOOT_APP))
					.limit(2)
					.collect(Collectors.toList());
			if (bootAppBeans.size() == 1) {
				try {
					Bean appBean = bootAppBeans.get(0);
					Collection<String> classpath = project.getClasspath().getClasspathEntries().stream()
							.filter(cpe -> !cpe.isTest() && !cpe.isSystem())
							.map(cpe -> Classpath.isSource(cpe) ? cpe.getOutputFolder() : cpe.getPath())
							.collect(Collectors.toSet());
					IGav gav = project.getProjectBuild().getGav();
					String gavStr = "%s:%s:%s".formatted(gav.getGroupId(), gav.getArtifactId(), gav.getVersion());
					String springBootVersion = SpringProjectUtil.getSpringBootVersion(project).toString();
					String buildTool = project.getProjectBuild().getType();
					String javaVersion = project.getClasspath().getJavaVersion();
					return Optional.of(new ExecutableProject(project.getElementName(), project.getLocationUri().toASCIIString(), gavStr, appBean.getType(), classpath, buildTool, springBootVersion, javaVersion));
				} catch (Exception e) {
					log.error("", e);
				}
			}
			return Optional.empty();
		});
	}
	
	private CompletableFuture<List<ExecutableProject>> findExecutableProjects() {
		List<CompletableFuture<Optional<ExecutableProject>>> futures = projectFinder.all().stream()
				.filter(p -> SpringProjectUtil.isBootProject(p))
				.map(this::mapToExecProject)
				.collect(Collectors.toList());
		List<ExecutableProject> executableProjects = Collections.synchronizedList(new ArrayList<>());
		futures.forEach(f -> f.thenAccept(opt -> opt.ifPresent(executableProjects::add)));
		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])).thenCompose(v -> {
			 final long start = System.currentTimeMillis();
			 return server.getClient().projectGAV(new ProjectGavParams(executableProjects.stream().map(p -> p.uri()).toList())).thenApply(gavs -> {
				 List<ExecutableProject> filteredExecProjects = new ArrayList<>(executableProjects.size());
				 for (int i = 0; i < executableProjects.size(); i++) {
					 ExecutableProject ep = executableProjects.get(i);
					 if (gavs.get(i) != null) {
						 Gav gav = gavs.get(i);
						 filteredExecProjects.add(new ExecutableProject(ep.name(), ep.uri(), "%s:%s:%s".formatted(gav.groupId(), gav.artifactId(), gav.version()), ep.mainClass(), ep.classpath()));
					 } else {
						 filteredExecProjects.add(ep);
					 }
				 }
				 log.info("GAV for %d projects took: %d".formatted(executableProjects.size(), System.currentTimeMillis() - start));
				 return filteredExecProjects;
			 });
		});
	}
	
}
