/*
 * Copyright 2026 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.datatransferproject.spi.transfer.types;

import javax.annotation.Nonnull;

/**
 * Thrown by an importer when a download URL from the exported data, such as {@link
 * org.datatransferproject.types.common.DownloadableItem#getFetchableUrl()}, has expired, for
 * example because it now returns HTTP 401 or 403.
 *
 * <p>Importing the same data again can't succeed, and an importer can't get new URLs with the
 * import credentials. So when an import fails with this exception, the copier exports the same page
 * again, to get fresh URLs, and imports that, a limited number of times per page. Items already
 * recorded as imported in the {@code IdempotentImportExecutor} are skipped. If the import still
 * fails, the job fails with {@link FailureReasons#FETCHABLE_URL_EXPIRED}.
 *
 * <p>Throw it from {@code Importer#importItem} without wrapping it. It isn't an {@code
 * IOException}, so {@code IdempotentImportExecutor#executeAndSwallowIOExceptions} passes it on.
 * Keep the URL out of the message and the cause: exceptions get logged, and download URLs often
 * carry credentials.
 */
public class FetchableUrlExpiredException extends CopyExceptionWithFailureReason {

  public FetchableUrlExpiredException(String message, Throwable cause) {
    super(message, cause);
  }

  @Nonnull
  @Override
  public String getFailureReason() {
    return FailureReasons.FETCHABLE_URL_EXPIRED.toString();
  }
}
