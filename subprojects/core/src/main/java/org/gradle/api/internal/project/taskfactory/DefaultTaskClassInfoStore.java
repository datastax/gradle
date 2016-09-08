/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.project.taskfactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.internal.changedetection.TaskArtifactState;
import org.gradle.api.internal.tasks.ClassLoaderAwareTaskAction;
import org.gradle.api.internal.tasks.ContextAwareTaskAction;
import org.gradle.api.internal.tasks.TaskExecutionContext;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.incremental.IncrementalTaskInputs;
import org.gradle.internal.Factory;
import org.gradle.internal.reflect.JavaReflectionUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.gradle.api.internal.project.taskfactory.TaskPropertyParserUtils.findProperties;

public class DefaultTaskClassInfoStore implements TaskClassInfoStore {
    private final LoadingCache<Class<? extends Task>, TaskClassInfo> classInfos = CacheBuilder.newBuilder()
        .weakKeys()
        .build(new CacheLoader<Class<? extends Task>, TaskClassInfo>() {
            @Override
            public TaskClassInfo load(Class<? extends Task> type) throws Exception {
                TaskClassInfoContext context = new TaskClassInfoContext();
                findTaskActions(type, context);
                findProperties(type, context);
                context.setCacheable(type.isAnnotationPresent(CacheableTask.class));
                return context.build();
            }
        });

    @Override
    public TaskClassInfo getTaskClassInfo(Class<? extends Task> type) {
        return classInfos.getUnchecked(type);
    }

    private static void findTaskActions(Class<? extends Task> type, TaskClassInfoContext context) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                attachTaskAction(method, context);
            }
        }
    }

    private static void attachTaskAction(Method method, TaskClassInfoContext context) {
        if (method.getAnnotation(TaskAction.class) == null) {
            return;
        }
        if (Modifier.isStatic(method.getModifiers())) {
            throw new GradleException(String.format("Cannot use @TaskAction annotation on static method %s.%s().",
                method.getDeclaringClass().getSimpleName(), method.getName()));
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length > 1) {
            throw new GradleException(String.format(
                "Cannot use @TaskAction annotation on method %s.%s() as this method takes multiple parameters.",
                method.getDeclaringClass().getSimpleName(), method.getName()));
        }

        if (parameterTypes.length == 1) {
            if (!parameterTypes[0].equals(IncrementalTaskInputs.class)) {
                throw new GradleException(String.format(
                    "Cannot use @TaskAction annotation on method %s.%s() because %s is not a valid parameter to an action method.",
                    method.getDeclaringClass().getSimpleName(), method.getName(), parameterTypes[0]));
            }
            context.markAsIncremental();
        }
        if (!context.hasAlreadyProcessed(method)) {
            context.addActionFactory(createActionFactory(method, parameterTypes));
        }
    }

    private static Factory<Action<Task>> createActionFactory(final Method method, final Class<?>[] parameterTypes) {
        return new Factory<Action<Task>>() {
            public Action<Task> create() {
                if (parameterTypes.length == 1) {
                    return new IncrementalTaskAction(method);
                } else {
                    return new StandardTaskAction(method);
                }
            }
        };
    }

    private static class StandardTaskAction implements ClassLoaderAwareTaskAction {
        private final Method method;

        public StandardTaskAction(Method method) {
            this.method = method;
        }

        public void execute(Task task) {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(method.getDeclaringClass().getClassLoader());
            try {
                doExecute(task, method.getName());
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }

        protected void doExecute(Task task, String methodName) {
            JavaReflectionUtil.method(task, Object.class, methodName).invoke(task);
        }

        @Override
        public ClassLoader getClassLoader() {
            return method.getDeclaringClass().getClassLoader();
        }
    }

    private static class IncrementalTaskAction extends StandardTaskAction implements ContextAwareTaskAction {

        private TaskArtifactState taskArtifactState;

        public IncrementalTaskAction(Method method) {
            super(method);
        }

        public void contextualise(TaskExecutionContext context) {
            this.taskArtifactState = context == null ? null : context.getTaskArtifactState();
        }

        protected void doExecute(Task task, String methodName) {
            JavaReflectionUtil.method(task, Object.class, methodName, IncrementalTaskInputs.class).invoke(task, taskArtifactState.getInputChanges());
            taskArtifactState = null;
        }
    }
}
