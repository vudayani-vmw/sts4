/*******************************************************************************
 * Copyright (c) 2018, 2019 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.tooling.jdt.ls.commons.classpath;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.springframework.ide.vscode.commons.protocol.java.Classpath;
import org.springframework.ide.vscode.commons.protocol.java.Classpath.CPE;
import org.springframework.tooling.jdt.ls.commons.Logger;

import com.google.common.collect.ImmutableList;

public class SendClasspathNotificationsJob extends Job {
	
	private final ClientCommandExecutor conn;
	private final Logger logger;
	private String callbackCommandId;
	
	/**
	 * Used only if caller has requested 'batched' events. This buffer, accumulates messsages to be sent out all at once,
	 * rather than one by one.
	 */
	private List<Object> buffer;

	/**
	 * To keep track of project locations. Without this we can't properly handle deletion events because
	 * deleted projects no longer have a location. So we can only send a proper 'project with this location'
	 * was deleted' event if we keep track of project locations ourselves.
	 */
	private Map<String, URI> projectLocations = new HashMap<>();
	public final Queue<IJavaProject> queue = new ConcurrentLinkedQueue<>();
	public final Queue<IJavaProject> builtProjectQueue = new ConcurrentLinkedQueue<>();
	
	private final Set<IJavaProject> notReadyProjects = new HashSet<>();
	
	public SendClasspathNotificationsJob(Logger logger, ClientCommandExecutor conn, String callbackId, boolean isBatched) {
		super("Send Classpath Notifications");
		this.logger = logger;
		this.conn = conn;
		this.callbackCommandId = callbackId;
		if (isBatched) {
			buffer = new ArrayList<>();
		}
	}

	
	private URI getProjectLocation(IJavaProject jp) {
		URI loc = jp.getProject().getLocationURI();
		if (loc!=null) {
			return loc;
		} else {
			synchronized (projectLocations) {
				//fallback on what we stored ourselves.
				return projectLocations.get(jp.getElementName());
			}
		}
	}

	
	private boolean projectExists(IJavaProject jp) {
		//We can't really deal with projects that don't exist in disk. So using this more strict 'exists' check
		//makes sure anything that looks like it doesn't exist on disk is treated as if it simply doesn't exist 
		//at all. This kind of addresses a issue caused by Eclipse's idiotic behavior when it comes to deleting
		//a project's files from the file system... Eclipse recreates a 'vanilla' project in the workspace,
		//simply refusing to accept the fact that the project is actually gone.
		if (jp.exists()) {
			try {
				URI loc = getProjectLocation(jp);
				if (loc!=null) {
					File f = new File(loc);
					return f.isDirectory() && jp.getProject().hasNature(JavaCore.NATURE_ID);
				}
			} catch (Exception e) {
				//Something bogus about this project... so just pretend it doesn't exist.
			}
		}
		return false;
	}

	
	@Override
	protected IStatus run(IProgressMonitor monitor) {
		synchronized (projectLocations) { //Could use some Eclipse job rule. But its really a bit of a PITA to create the right one.
			try {
				// Try to see if classpath needs to be sent for the projects that have been
				// built since classpath JAR may not have existed (not downloaded) at the time
				// of classpath changed event
				for (IJavaProject jp = builtProjectQueue.poll(); jp!=null; jp = builtProjectQueue.poll()) {
					if (notReadyProjects.remove(jp)) {
						queue.add(jp);
					}
				}
				for (IJavaProject jp = queue.poll(); jp!=null; jp = queue.poll()) {
					logger.log("Preparing classpath changed notification " + jp.getElementName());
					// Project wasn't ready before but now it's about to be processed for Classpath again.
					// Remove it from the set of not readt projects
					notReadyProjects.remove(jp);
					URI projectLoc = getProjectLocation(jp);
					if (projectLoc==null) {
						logger.log("Could not send event for project because no project location: "+jp.getElementName());
					} else {
						boolean exsits = projectExists(jp);
						boolean open = true; // WARNING: calling jp.isOpen is unreliable and subject to race condition. After a POST_CHAGE project open event
											// this should be true but it typically is not unless you wait for some time. No idea how you would know
											// how long you should wait (200ms is not enough, and that seems pretty long).
											// So we will just pretend / assume project is always open. If resolving classpath fails because it is not
											// open... so be it (there will be no classpath... this is expected for closed project, so that is fine).
						boolean deleted = !(exsits && open);
						logger.log("exists = "+exsits +" open = "+open +" => deleted = "+deleted);
						String projectName = jp.getElementName();

						Classpath classpath = Classpath.EMPTY;
						if (deleted) {
							// Project has been removed no need to keep in not ready projects set
							notReadyProjects.remove(jp);
							// projectLocations.remove(projectName);
						} else {
							projectLocations.put(projectName, projectLoc);
							try {
								classpath = ClasspathUtil.resolve(jp, logger);
								List<CPE> filteredCPEs = new ArrayList<>(classpath.getEntries().size());
								for (CPE cpe : classpath.getEntries()) {
									// Some classpath entries don't exist yet (to be downloaded during the build). Filter them out as these JARs won't be indexed until they exist
									if (!Classpath.isBinary(cpe) || new File(cpe.getPath()).exists()) {
										filteredCPEs.add(cpe);
									}
								}
								if (filteredCPEs.size() != classpath.getEntries().size()) {
									// If some entries in the classpath don't exist yet add the project to not ready projects set to process later when project is built
									notReadyProjects.add(jp);
									// Only send effective classpath that has all entries physically present.
									classpath = new Classpath(filteredCPEs);
								}
							} catch (Exception e) {
								logger.log(e);
							}
						}
						bufferMessage(projectLoc, deleted, projectName, classpath);
					}
				}
				flush();
			} catch (Exception e) {
				logger.log(e);
			}
			return Status.OK_STATUS;
		}
	}

	protected void bufferMessage(URI projectLoc, boolean deleted, String projectName, Classpath classpath) {
		if (buffer!=null) {
			logger.log("buffering callback "+callbackCommandId+" "+projectName+" "+deleted+" "+ classpath.getEntries().size());
			buffer.add(ImmutableList.of(projectLoc.toString(), projectName, deleted, classpath));
		} else {
			try {
				logger.log("executing callback "+callbackCommandId+" "+projectName+" "+deleted+" "+ classpath.getEntries().size());
				Object r = conn.executeClientCommand(callbackCommandId, projectLoc.toString(), projectName, deleted, classpath);
				logger.log("executing callback "+callbackCommandId+" SUCCESS ["+r+"]");
			} catch (Exception e) {
				logger.log("executing callback "+callbackCommandId+" FAILED");
				logger.log(e);
			}
		}
	}
	
	protected void flush() {
		if (buffer!=null && !buffer.isEmpty()) {
			try {
				logger.log("executing callback "+callbackCommandId+" "+buffer.size()+" batched events");
				Object r = conn.executeClientCommand(callbackCommandId, buffer.toArray(new Object[buffer.size()]));
				logger.log("executing callback "+callbackCommandId+" SUCCESS ["+r+"]");
			} catch (Exception e) {
				logger.log("executing callback "+callbackCommandId+" FAILED");
				logger.log(e);
			} finally {
				buffer.clear();
			}
		}
	}
}
