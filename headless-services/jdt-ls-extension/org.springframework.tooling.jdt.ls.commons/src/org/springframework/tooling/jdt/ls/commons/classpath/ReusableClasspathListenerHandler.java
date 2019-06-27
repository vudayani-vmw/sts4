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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import javax.management.Notification;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.springframework.tooling.jdt.ls.commons.Logger;
import org.springframework.tooling.jdt.ls.commons.classpath.ClasspathListenerManager.ClasspathListener;

/**
 * {@link ReusableClasspathListenerHandler} is an 'abstracted' version of the jdtls ClasspathListenerHandler. 
 */
public class ReusableClasspathListenerHandler {

	private final Logger logger;
	private final ClientCommandExecutor conn;
	private final Supplier<Comparator<IProject>> projectSorterFactory;
	
	public ReusableClasspathListenerHandler(Logger logger, ClientCommandExecutor conn) {
		this(logger, conn, null);
	}
	
	public ReusableClasspathListenerHandler(Logger logger, ClientCommandExecutor conn, Supplier<Comparator<IProject>> projectSorterFactory) {
		this.logger = logger;
		this.projectSorterFactory = projectSorterFactory;
		this.conn = conn;
		logger.log("Instantiating ReusableClasspathListenerHandler");
	}
	
	class Subscriptions {

		private Map<String, SendClasspathNotificationsJob> subscribers = null;
		private ClasspathListenerManager classpathListener = null;
		
		public synchronized void subscribe(String callbackCommandId, boolean isBatched) {
			if (subscribers==null) {
				//First subscriber
				subscribers = new HashMap<>();
			}
			if (!subscribers.containsKey(callbackCommandId)) {
				logger.log("subscribing to classpath changes: " + callbackCommandId +" isBatched = "+isBatched);
				classpathListener = new ClasspathListenerManager(logger, new ClasspathListener() {
					@Override
					public void classpathChanged(IJavaProject jp) {
						sendNotification(jp, subscribers.keySet());
					}

					@Override
					public void projectBuilt(IJavaProject jp) {
						sendNotificationOnProjectBuilt(jp, subscribers.keySet());
					}
					
				});
				subscribers.put(callbackCommandId, new SendClasspathNotificationsJob(logger, conn, callbackCommandId, isBatched));
				logger.log("subsribers = " + subscribers);
				sendInitialEvents(callbackCommandId);
			}
		}
		
		private void sendInitialEvents(String callbackCommandId) {
			logger.log("Sending initial event for all projects ...");
			
			IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
			if (projectSorterFactory != null) {
				Arrays.sort(projects, projectSorterFactory.get());
			}

			for (IProject p : projects) {
				logger.log("project "+p.getName() +" ..." );
				try {
					if (p.isAccessible() && p.hasNature(JavaCore.NATURE_ID)) {
						IJavaProject jp = JavaCore.create(p);
						sendNotification(jp, Collections.singleton(callbackCommandId));
					} else {
						logger.log("project "+p.getName() +" SKIPPED" );
					}
				} catch (CoreException e) {
					logger.log(e);
				}
			}
			logger.log("Sending initial event for all projects DONE");
		}

		private synchronized void sendNotification(IJavaProject jp, Collection<String> callbackIds) {
			if (subscribers!=null) {
				for (String callbackId : callbackIds) {
					SendClasspathNotificationsJob sendNotificationJob = subscribers.get(callbackId);
					sendNotificationJob.queue.add(jp);
					sendNotificationJob.schedule();
				}
			}
		}

		private synchronized void sendNotificationOnProjectBuilt(IJavaProject jp, Collection<String> callbackIds) {
			if (subscribers!=null) {
				for (String callbackId : callbackIds) {
					SendClasspathNotificationsJob sendNotificationJob = subscribers.get(callbackId);
					sendNotificationJob.builtProjectQueue.add(jp);
					sendNotificationJob.schedule();
				}
			}
		}
		
		public synchronized void unsubscribe(String callbackCommandId) {
			logger.log("unsubscribing from classpath changes: " + callbackCommandId);
			if (subscribers != null) {
				subscribers.remove(callbackCommandId);
				if (subscribers.isEmpty()) {
					subscribers = null;
					if (classpathListener!=null) {
						classpathListener.dispose();
						classpathListener = null;
					}
				}
			}
			logger.log("subsribers = " + subscribers);
		}

		public boolean isEmpty() {
			return subscribers == null || subscribers.isEmpty();
		}
	}
	
	private Subscriptions subscribptions = new Subscriptions();

	public Object removeClasspathListener(String callbackCommandId) {
		logger.log("ClasspathListenerHandler removeClasspathListener " + callbackCommandId);
		subscribptions.unsubscribe(callbackCommandId);
		logger.log("ClasspathListenerHandler removeClasspathListener " + callbackCommandId + " => OK");
		return "ok";
	}

	@Deprecated
	public Object addClasspathListener(String callbackCommandId) {
		return addClasspathListener(callbackCommandId, false);
	}

	public Object addClasspathListener(String callbackCommandId, boolean isBatched) {
		logger.log("ClasspathListenerHandler addClasspathListener " + callbackCommandId + "isBatched = "+isBatched);
		subscribptions.subscribe(callbackCommandId, isBatched);
		logger.log("ClasspathListenerHandler addClasspathListener " + callbackCommandId + " => OK");
		return "ok";
	}

	public boolean hasNoActiveSubscriptions() {
		return subscribptions.isEmpty();
	}

}
