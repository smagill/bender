/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 * Copyright 2017 Nextdoor.com, Inc
 *
 */

package com.nextdoor.bender.ipc.s3;

import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.nextdoor.bender.config.AbstractConfig;
import com.nextdoor.bender.ipc.Transport;
import com.nextdoor.bender.ipc.TransportException;
import com.nextdoor.bender.ipc.TransportFactory;
import com.nextdoor.bender.ipc.TransportFactoryInitException;

/**
 * Creates a {@link S3Transport} from a {@link S3TransportConfig}.
 */
public class S3TransportFactory implements TransportFactory {
  private static final Logger logger = Logger.getLogger(S3TransportFactory.class);
  private S3TransportConfig config;
  private AmazonS3Client client;

  private Map<String, MultiPartUpload> pendingMultiPartUploads = new HashMap<>();
  private S3TransportSerializer serializer = new S3TransportSerializer();

  @Override
  public Class<S3Transport> getChildClass() {
    return S3Transport.class;
  }

  @Override
  public Transport newInstance() throws TransportFactoryInitException {
    return new S3Transport(this.client, this.config.getBucketName(), this.config.getBasePath(),
        this.config.getUseCompression(), this.pendingMultiPartUploads);
  }

  @Override
  public void close() {
    Exception e = null;
    for (MultiPartUpload upload : this.pendingMultiPartUploads.values()) {
      CompleteMultipartUploadRequest req = upload.getCompleteMultipartUploadRequest();
      try {
        this.client.completeMultipartUpload(req);
      } catch (AmazonS3Exception ex) {
        String errorMessage = String.format("failed to complete multi-part upload for key %s, " +
                        "uploadId %s, and partCount %s. All remaining uploads were aborted as a result.",
                upload.getKey(),
                upload.getPartCount(),
                upload.getUploadId());
        logger.error(errorMessage, ex);
        abortAllMultiPartUploads();
        e = ex;
        break;
      }
    }

    this.pendingMultiPartUploads.clear();
    if (e != null) {
      throw new RuntimeException(e);
    }
  }

  // this helper method makes a best attempt to abort all the multipart uploads
  // whenever a single error is encountered during the close operation above.
  private void abortAllMultiPartUploads() {
    for (MultiPartUpload upload : this.pendingMultiPartUploads.values()) {
      AbortMultipartUploadRequest req = upload.getAbortMultipartUploadRequest();
      try {
        this.client.abortMultipartUpload(req);
      } catch (AmazonS3Exception e1) {
        logger.error("failed to abort multi-part upload", e1);
      }
    }
  }

  @Override
  public S3TransportBuffer newTransportBuffer() throws TransportException {
    return new S3TransportBuffer(this.config.getMaxBufferSize(),
        this.config.getUseCompression() && this.config.getCompressBuffer(), this.serializer);
  }

  @Override
  public int getMaxThreads() {
    return this.config.getThreads();
  }

  @Override
  public void setConf(AbstractConfig config) {
    this.config = (S3TransportConfig) config;
    this.client = new AmazonS3Client();

    if (this.config.getRegion() != null) {
      this.client.withRegion(this.config.getRegion());
    }
  }
}
