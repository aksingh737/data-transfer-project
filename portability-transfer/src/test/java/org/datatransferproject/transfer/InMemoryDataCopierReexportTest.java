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

package org.datatransferproject.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.datatransferproject.api.launcher.DtpInternalMetricRecorder;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.copier.stack.PortabilityStackInMemoryDataCopier;
import org.datatransferproject.spi.cloud.storage.JobStore;
import org.datatransferproject.spi.cloud.types.PortabilityJob;
import org.datatransferproject.spi.transfer.idempotentexecutor.IdempotentImportExecutor;
import org.datatransferproject.spi.transfer.idempotentexecutor.InMemoryIdempotentImportExecutor;
import org.datatransferproject.spi.transfer.provider.ExportResult;
import org.datatransferproject.spi.transfer.provider.ExportResult.ResultType;
import org.datatransferproject.spi.transfer.provider.Exporter;
import org.datatransferproject.spi.transfer.provider.ImportResult;
import org.datatransferproject.spi.transfer.provider.Importer;
import org.datatransferproject.spi.transfer.types.ContinuationData;
import org.datatransferproject.spi.transfer.types.CopyException;
import org.datatransferproject.spi.transfer.types.DestinationMemoryFullException;
import org.datatransferproject.spi.transfer.types.FetchableUrlExpiredException;
import org.datatransferproject.transfer.copier.PortabilityAbstractInMemoryDataCopier;
import org.datatransferproject.transfer.copier.PortabilityInMemoryDataCopier;
import org.datatransferproject.types.common.ExportInformation;
import org.datatransferproject.types.common.PaginationData;
import org.datatransferproject.types.common.StringPaginationToken;
import org.datatransferproject.types.common.models.DataModel;
import org.datatransferproject.types.common.models.DataVertical;
import org.datatransferproject.types.transfer.auth.AuthData;
import org.datatransferproject.types.transfer.retry.NoRetryStrategy;
import org.datatransferproject.types.transfer.retry.RetryStrategy;
import org.datatransferproject.types.transfer.retry.RetryStrategyLibrary;
import org.datatransferproject.types.transfer.retry.UniformRetryStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests that both copiers export a page again when importing it fails because its download URLs
 * expired, and only then. It lives in this package so it can set up {@link JobMetadata}.
 */
public class InMemoryDataCopierReexportTest {

  private static final Monitor MONITOR = new Monitor() {};
  // RetryingCallable retries up to maxAttempts times after the first attempt.
  private static final RetryStrategy RETRY_TWICE =
      new UniformRetryStrategy(/* maxAttempts= */ 2, /* intervalMillis= */ 1, "test");

  private final UUID jobId = UUID.randomUUID();
  private final AuthData authData = mock(AuthData.class);
  private final JobStore jobStore = mock(JobStore.class);
  private final IdempotentImportExecutor executor = new InMemoryIdempotentImportExecutor(MONITOR);

  @BeforeEach
  public void setUp() {
    JobMetadata.reset();
    JobMetadata.init(
        jobId, new byte[0], DataVertical.PHOTOS, "export", "import", Stopwatch.createUnstarted());
    PortabilityJob job = mock(PortabilityJob.class);
    when(jobStore.findJob(jobId)).thenReturn(job);
  }

  @AfterEach
  public void tearDown() {
    JobMetadata.reset();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void reexportsThePage_whenItsUrlsExpireDuringImport(boolean stackCopier)
      throws Exception {
    // photo-2's URL expires after photo-1 is imported. The re-export has fresh URLs, and
    // continuation data of its own that the copy must continue from.
    FakePage firstExport = new FakePage(ImmutableSet.of("photo-2"), "photo-1", "photo-2");
    FakePage reexport = new FakePage(ImmutableSet.of(), "photo-1", "photo-2");
    FakePage nextPage = new FakePage(ImmutableSet.of(), "photo-3");
    PaginationData nextAfterFirstExport = new StringPaginationToken("next-after-first-export");
    PaginationData nextAfterReexport = new StringPaginationToken("next-after-reexport");
    FakeExporter exporter =
        new FakeExporter(
            ImmutableList.of(
                new ExportResult<>(
                    ResultType.CONTINUE, firstExport, new ContinuationData(nextAfterFirstExport)),
                new ExportResult<>(
                    ResultType.CONTINUE, reexport, new ContinuationData(nextAfterReexport)),
                new ExportResult<>(ResultType.END, nextPage)));
    FakeImporter importer = new FakeImporter();

    newCopier(stackCopier, exporter, importer, new NoRetryStrategy())
        .copy(authData, authData, jobId, Optional.empty());

    assertEquals(
        ImmutableList.of(
            Optional.empty(),
            Optional.empty(),
            Optional.of(new ExportInformation(nextAfterReexport, null))),
        exporter.requests);
    assertEquals(ImmutableList.of(firstExport, reexport, nextPage), importer.pages);
    // photo-1 was imported before its URL expired, so importing the re-export skips it.
    assertEquals(ImmutableList.of("photo-1", "photo-2", "photo-2", "photo-3"), importer.fetches);
    // The error photo-2 got in the failed import is cleared once it imports.
    assertEquals(ImmutableList.of(), ImmutableList.copyOf(executor.getErrors()));
    // Each page's counts are added once, when its import succeeds.
    verify(jobStore).addCounts(jobId, ImmutableMap.of("items", 2));
    verify(jobStore).addCounts(jobId, ImmutableMap.of("items", 1));
    verify(jobStore, times(2)).addCounts(any(), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void failsTheCopy_whenTheUrlsStillExpireAfterTheLastReexport(boolean stackCopier) {
    FakePage page = new FakePage(ImmutableSet.of("photo-1"), "photo-1");
    // The first export and 3 re-exports, the most the copier makes for one page.
    FakeExporter exporter =
        new FakeExporter(Collections.nCopies(4, new ExportResult<>(ResultType.END, page)));
    FakeImporter importer = new FakeImporter();
    PortabilityAbstractInMemoryDataCopier copier =
        newCopier(stackCopier, exporter, importer, new NoRetryStrategy());

    assertThrows(
        FetchableUrlExpiredException.class,
        () -> copier.copy(authData, authData, jobId, Optional.empty()));

    assertEquals(Collections.nCopies(4, Optional.empty()), exporter.requests);
    assertEquals(4, importer.pages.size());
  }

  @Test
  public void doesNotRetryTheImport_withTheSameExpiredUrls() throws Exception {
    FakePage expiredPage = new FakePage(ImmutableSet.of("photo-1"), "photo-1");
    FakePage freshPage = new FakePage(ImmutableSet.of(), "photo-1");
    FakeExporter exporter =
        new FakeExporter(
            ImmutableList.of(
                new ExportResult<>(ResultType.END, expiredPage),
                new ExportResult<>(ResultType.END, freshPage)));
    FakeImporter importer = new FakeImporter();

    newCopier(false, exporter, importer, RETRY_TWICE)
        .copy(authData, authData, jobId, Optional.empty());

    // The page retry strategy doesn't apply: it would import the expired page again.
    assertEquals(ImmutableList.of(expiredPage, freshPage), importer.pages);
  }

  @Test
  public void otherImportFailures_areRetriedAndRethrownAsBefore_withoutAReexport() {
    IOException failure = new IOException("Test IO exception");
    FailingImporter importer = new FailingImporter(failure);
    FakeExporter exporter = new FakeExporter(ImmutableList.of(onePage()));
    PortabilityAbstractInMemoryDataCopier copier =
        newCopier(false, exporter, importer, RETRY_TWICE);

    CopyException thrown =
        assertThrows(
            CopyException.class, () -> copier.copy(authData, authData, jobId, Optional.empty()));

    // The copier wraps the RetryException that holds the importer's last exception.
    assertSame(failure, thrown.getCause().getCause());
    assertEquals(3, importer.attempts);
    assertEquals(1, exporter.requests.size());
  }

  @Test
  public void otherFailuresWithAFailureReason_areRethrownAsBefore_withoutAReexport() {
    DestinationMemoryFullException failure =
        new DestinationMemoryFullException("Test destination full", null);
    FakeExporter exporter = new FakeExporter(ImmutableList.of(onePage()));
    PortabilityAbstractInMemoryDataCopier copier =
        newCopier(false, exporter, new FailingImporter(failure), new NoRetryStrategy());

    assertSame(
        failure,
        assertThrows(
            DestinationMemoryFullException.class,
            () -> copier.copy(authData, authData, jobId, Optional.empty())));
    assertEquals(1, exporter.requests.size());
  }

  @Test
  public void failsTheCopy_whenTheReexportFails() {
    IOException exportFailure = new IOException("Test IO exception");
    FakePage expiredPage = new FakePage(ImmutableSet.of("photo-1"), "photo-1");
    List<Optional<ExportInformation>> requests = new ArrayList<>();
    Exporter<AuthData, FakePage> exporter =
        (id, auth, exportInformation) -> {
          requests.add(exportInformation);
          if (requests.size() > 1) {
            throw exportFailure;
          }
          return new ExportResult<>(ResultType.END, expiredPage);
        };
    PortabilityAbstractInMemoryDataCopier copier =
        newCopier(false, exporter, new FakeImporter(), new NoRetryStrategy());

    CopyException thrown =
        assertThrows(
            CopyException.class, () -> copier.copy(authData, authData, jobId, Optional.empty()));

    // The re-export's failure fails the copy, as an export failure does on any page.
    assertSame(exportFailure, thrown.getCause().getCause());
    assertEquals(2, requests.size());
  }

  private static ExportResult<FakePage> onePage() {
    return new ExportResult<>(ResultType.END, new FakePage(ImmutableSet.of(), "photo-1"));
  }

  private PortabilityAbstractInMemoryDataCopier newCopier(
      boolean stackCopier,
      Exporter<AuthData, FakePage> exporter,
      Importer<AuthData, FakePage> importer,
      RetryStrategy pageRetryStrategy) {
    DtpInternalMetricRecorder metricRecorder = mock(DtpInternalMetricRecorder.class);
    RetryStrategyLibrary pageRetries =
        new RetryStrategyLibrary(ImmutableList.of(), pageRetryStrategy);
    IdempotentImportExecutor retryingExecutor = new InMemoryIdempotentImportExecutor(MONITOR);
    if (stackCopier) {
      return new PortabilityStackInMemoryDataCopier(
          () -> exporter,
          () -> importer,
          () -> pageRetries,
          MONITOR,
          executor,
          retryingExecutor,
          metricRecorder,
          jobStore);
    }
    return new PortabilityInMemoryDataCopier(
        () -> exporter,
        () -> importer,
        () -> pageRetries,
        MONITOR,
        executor,
        retryingExecutor,
        metricRecorder,
        jobStore);
  }

  /** A page of items. Fetching an item in {@code expiredUrls} fails as if its URL had expired. */
  private static class FakePage extends DataModel {
    private final ImmutableList<String> itemIds;
    private final ImmutableSet<String> expiredUrls;

    FakePage(Set<String> expiredUrls, String... itemIds) {
      this.expiredUrls = ImmutableSet.copyOf(expiredUrls);
      this.itemIds = ImmutableList.copyOf(itemIds);
    }

    @Override
    public Map<String, Integer> getCounts() {
      return ImmutableMap.of("items", itemIds.size());
    }
  }

  /** Returns the given results in order, and records the export information of each call. */
  private static class FakeExporter implements Exporter<AuthData, FakePage> {
    private final Iterator<ExportResult<FakePage>> results;
    private final List<Optional<ExportInformation>> requests = new ArrayList<>();

    FakeExporter(List<ExportResult<FakePage>> results) {
      this.results = results.iterator();
    }

    @Override
    public ExportResult<FakePage> export(
        UUID jobId, AuthData authData, Optional<ExportInformation> exportInformation) {
      requests.add(exportInformation);
      return results.next();
    }
  }

  /**
   * Imports each item through the executor passed to it, throwing {@link
   * FetchableUrlExpiredException} for an item whose URL expired. Records each page and fetch.
   */
  private static class FakeImporter implements Importer<AuthData, FakePage> {
    private final List<FakePage> pages = new ArrayList<>();
    private final List<String> fetches = new ArrayList<>();

    @Override
    public ImportResult importItem(
        UUID jobId, IdempotentImportExecutor executor, AuthData authData, FakePage page)
        throws Exception {
      pages.add(page);
      for (String id : page.itemIds) {
        executor.executeAndSwallowIOExceptions(
            id,
            id,
            () -> {
              fetches.add(id);
              if (page.expiredUrls.contains(id)) {
                throw new FetchableUrlExpiredException("Test expired URL", null);
              }
              return id;
            });
      }
      return ImportResult.OK;
    }
  }

  /** Fails every import with the given exception. */
  private static class FailingImporter implements Importer<AuthData, FakePage> {
    private final Exception failure;
    private int attempts;

    FailingImporter(Exception failure) {
      this.failure = failure;
    }

    @Override
    public ImportResult importItem(
        UUID jobId, IdempotentImportExecutor executor, AuthData authData, FakePage page)
        throws Exception {
      attempts++;
      throw failure;
    }
  }
}
