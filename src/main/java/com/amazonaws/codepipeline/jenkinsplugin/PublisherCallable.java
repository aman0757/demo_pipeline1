/*
 * Copyright 2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.codepipeline.jenkinsplugin;

import hudson.FilePath.FileCallable;
import hudson.model.BuildListener;
import hudson.remoting.VirtualChannel;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.codepipeline.model.AWSSessionCredentials;
import com.amazonaws.services.codepipeline.model.Artifact;
import com.amazonaws.services.codepipeline.model.GetJobDetailsRequest;
import com.amazonaws.services.codepipeline.model.GetJobDetailsResult;

public final class PublisherCallable implements FileCallable<Void> {
    private static final long serialVersionUID = 1L;

    private final String projectName;
    private final CodePipelineStateModel model;
    private final AWSClientFactory awsClientFactory;
    private final List<OutputArtifact> outputs;
    private final BuildListener listener;

    public PublisherCallable(
            final String projectName,
            final CodePipelineStateModel model,
            final AWSClientFactory awsClientFactory,
            final List<OutputArtifact> outputs,
            final BuildListener listener) {
        this.projectName = Objects.requireNonNull(projectName);
        this.model = Objects.requireNonNull(model);
        this.outputs = Objects.requireNonNull(outputs);
        this.listener = listener;
        this.awsClientFactory = awsClientFactory;
    }

    @Override
    public Void invoke(final File workspace, final VirtualChannel channel) throws IOException {
        final AWSClients aws = awsClientFactory.getAwsClient(
                model.getAwsAccessKey(),
                model.getAwsSecretKey(),
                model.getProxyHost(),
                model.getProxyPort(),
                model.getRegion());

        final GetJobDetailsRequest getJobDetailsRequest = new GetJobDetailsRequest()
                .withJobId(model.getJob().getId());
        final GetJobDetailsResult getJobDetailsResult = aws.getCodePipelineClient().getJobDetails(getJobDetailsRequest);
        final AWSSessionCredentials sessionCredentials = getJobDetailsResult.getJobDetails().getData().getArtifactCredentials();

        final BasicSessionCredentials temporaryCredentials = new BasicSessionCredentials(
                sessionCredentials.getAccessKeyId(),
                sessionCredentials.getSecretAccessKey(),
                sessionCredentials.getSessionToken());

        Map<String, String> artifactLocations = new HashMap<>();
        for(final OutputArtifact outputArtifact : outputs) {
            artifactLocations.put(
                    outputArtifact.getArtifactName(),
                    outputArtifact.getLocation()
            );
        }

        for(final Artifact artifact : model.getJob().getData().getOutputArtifacts()) {
            final String artifactLocation = artifactLocations.get(artifact.getName());
            if(artifactLocation != null) {
                final File compressedFile = CompressionTools.compressFile(
                        projectName,
                        workspace,
                        artifactLocation,
                        model.getCompressionType(),
                        listener);
                if (compressedFile != null) {
                    PublisherTools.uploadFile(
                            compressedFile,
                            artifact,
                            model.getCompressionType(),
                            model.getEncryptionKey(),
                            temporaryCredentials,
                            aws,
                            listener);
                } else {
                    LoggingHelper.log(listener, "Failed to compress file and upload file");
                }
            } else {
                LoggingHelper.log(listener, "No defined output artifact matched the jobs output artifact");
            }
        }

        return null;
    }

}
