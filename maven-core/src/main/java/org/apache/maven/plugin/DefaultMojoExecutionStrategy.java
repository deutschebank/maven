package org.apache.maven.plugin;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.codehaus.plexus.component.annotations.Component;

import java.util.List;

/**
 * Default mojo execution strategy.
 * Just iterates over mojoExecution lists and pass all of them to executor
 */
public class DefaultMojoExecutionStrategy implements MojoExecutionStrategy
{
    @Override
    public void execute( MavenSession session, List<MojoExecution> mojoExecutions, Executor executor )
            throws LifecycleExecutionException
    {
        for ( MojoExecution mojoExecution : mojoExecutions )
        {
            executor.execute( mojoExecution );
        }

    }
}
