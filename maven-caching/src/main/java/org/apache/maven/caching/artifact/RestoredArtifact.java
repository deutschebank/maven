package org.apache.maven.caching.artifact;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.ConcurrentUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.InvalidArtifactRTException;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.project.artifact.AttachedArtifact;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static java.util.Objects.requireNonNull;

/**
 * Allows to restore files from cache lazy
 */
public class RestoredArtifact extends AttachedArtifact
{
    private final Artifact parent;
    private final String type;
    private final String classifier;
    private volatile Future<File> fileFuture;


    public RestoredArtifact( Artifact parent, Future<File> fileFuture, String type, String classifier,
                             ArtifactHandler handler )
    {
        super( parent, type, classifier, handler );
        this.parent = requireNonNull( parent, "artifact == null" );
        this.fileFuture = requireNonNull( fileFuture, "fileFuture == null" );
        this.type = type;
        this.classifier = classifier;
    }

    @Override
    public File getFile()
    {

        // TODO need logging here
        if ( !fileFuture.isDone() )
        {
            // attempt to retrieve artifact by caller instead of wait 
            if ( fileFuture instanceof Runnable )
            {
                try
                {
                    ( (Runnable) fileFuture ).run();
                }
                catch ( RuntimeException e )
                {
                    throw new InvalidArtifactRTException( parent.getGroupId(), parent.getArtifactId(),
                            parent.getVersion(), parent.getType(),
                            "Error retrieving artifact file", e );
                }
            }
            else if ( fileFuture instanceof Callable )
            {
                try
                {
                    ( (Callable) fileFuture ).call();
                }
                catch ( Exception e )
                {
                    throw new InvalidArtifactRTException( parent.getGroupId(), parent.getArtifactId(),
                            parent.getVersion(), parent.getType(),
                            "Error retrieving artifact file", e );
                }
            }
        }

        try
        {
            return fileFuture.get();
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            throw new InvalidArtifactRTException( parent.getGroupId(), parent.getArtifactId(),
                    parent.getVersion(), parent.getType(),
                    "Interrupted while retrieving artifact file" );
        }
        catch ( ExecutionException e )
        {
            final ConcurrentException cause = ConcurrentUtils.extractCause( e );
            throw new InvalidArtifactRTException( parent.getGroupId(), parent.getArtifactId(),
                    parent.getVersion(), parent.getType(), "Error retrieving artifact file",
                    cause );
        }
    }


    @Override
    public void setFile( File destination )
    {
        this.fileFuture = CompletableFuture.completedFuture( destination );
    }
}
