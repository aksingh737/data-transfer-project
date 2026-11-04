/*
 * Copyright 2019 The Data Transfer Project Authors.
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

package org.datatransferproject.transfer.microsoft.media;

import static com.google.common.truth.Truth.assertThat;
import static org.datatransferproject.transfer.microsoft.media.MicrosoftMediaExporter.DRIVE_TOKEN_PREFIX;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.spi.transfer.provider.ExportResult;
import org.datatransferproject.spi.transfer.types.ContinuationData;
import org.datatransferproject.transfer.microsoft.common.MicrosoftCredentialFactory;
import org.datatransferproject.transfer.microsoft.driveModels.*;
import org.datatransferproject.types.common.ExportInformation;
import org.datatransferproject.types.common.StringPaginationToken;
import org.datatransferproject.types.common.models.ContainerResource;
import org.datatransferproject.types.common.models.IdOnlyContainerResource;
import org.datatransferproject.types.common.models.media.MediaAlbum;
import org.datatransferproject.types.common.models.media.MediaContainerResource;
import org.datatransferproject.types.common.models.photos.PhotoModel;
import org.datatransferproject.types.common.models.videos.VideoModel;
import org.datatransferproject.types.transfer.auth.AppCredentials;
import org.datatransferproject.types.transfer.auth.TokensAndUrlAuthData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests of Microsoft onedrive exporter for Media models. */
public class MicrosoftMediaExporterTest {
  private String PHOTO_URI = "imageDownloadUri";
  private String PHOTO_ID = "uniquePhotoId";
  private String PHOTO_FILENAME = "selfie-taken-by-cat.jpg";
  private String VIDEO_URI = "videoDownloadUri";
  private String VIDEO_ID = "uniqueVideoId";
  private String VIDEO_FILENAME = "cats-dancing.ogg";
  private String FOLDER_ID = "microsoftOneDriveFolderId";
  private String DRIVE_PAGE_URL = "driveToken";
  private String UPLOAD_TIME = "2024-01-02T03:04:05.123Z";
  private UUID uuid = UUID.randomUUID();

  private TokensAndUrlAuthData token;
  private MicrosoftMediaExporter microsoftMediaExporter;
  private MicrosoftMediaInterface mediaInterface;
  private MicrosoftCredentialFactory credentialFactory;
  private final MockWebServer server = new MockWebServer();

  private MicrosoftDriveItemsResponse driveItemsResponse;

  @BeforeEach
  public void setUp() throws IOException {
    credentialFactory = mock(MicrosoftCredentialFactory.class);
    mediaInterface = mock(MicrosoftMediaInterface.class);
    driveItemsResponse = mock(MicrosoftDriveItemsResponse.class);
    Monitor monitor = mock(Monitor.class);

    microsoftMediaExporter = new MicrosoftMediaExporter(
        credentialFactory, new JacksonFactory(), mediaInterface, monitor);

    when(mediaInterface.getDriveItems(any(Optional.class), any(Optional.class)))
        .thenReturn(driveItemsResponse);
    when(mediaInterface.getDriveItemsFromSpecialFolder(
             any(MicrosoftSpecialFolder.FolderType.class)))
        .thenReturn(driveItemsResponse);
    verifyNoInteractions(credentialFactory);
  }

  @AfterEach
  public void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  public void exportOneAlbumWithNextPage() throws IOException {
    // Setup
    MicrosoftDriveItem folderItem = setUpSingleAlbum();
    when(driveItemsResponse.getDriveItems()).thenReturn(new MicrosoftDriveItem[] {folderItem});
    when(driveItemsResponse.getNextPageLink()).thenReturn(DRIVE_PAGE_URL);

    // Run
    ExportResult<MediaContainerResource> result = microsoftMediaExporter.exportOneDrivePhotos(
        null, Optional.empty(), Optional.empty(), uuid);

    // Verify method calls
    verify(mediaInterface)
        .getDriveItemsFromSpecialFolder(MicrosoftSpecialFolder.FolderType.photos);
    verify(driveItemsResponse).getDriveItems();

    // Verify pagination token is set
    ContinuationData continuationData = result.getContinuationData();
    StringPaginationToken paginationToken =
        (StringPaginationToken) continuationData.getPaginationData();
    assertThat(paginationToken.getToken()).isEqualTo(DRIVE_TOKEN_PREFIX + DRIVE_PAGE_URL);

    // Verify one album is ready for import
    Collection<MediaAlbum> actualAlbums = result.getExportedData().getAlbums();
    assertThat(actualAlbums.stream().map(MediaAlbum::getId).collect(Collectors.toList()))
        .containsExactly(FOLDER_ID);

    // Verify photos should be empty (in the root)
    Collection<PhotoModel> actualPhotos = result.getExportedData().getPhotos();
    assertThat(actualPhotos).isEmpty();

    // Verify there is one container ready for sub-processing
    List<ContainerResource> actualResources = continuationData.getContainerResources();
    assertThat(actualResources.stream()
                   .map(a -> ((IdOnlyContainerResource) a).getId())
                   .collect(Collectors.toList()))
        .containsExactly(FOLDER_ID);
  }

  @Test
  public void exportAlbumWithoutNextPage() throws IOException {
    // Setup
    MicrosoftDriveItem folderItem = setUpSingleAlbum();
    when(driveItemsResponse.getDriveItems()).thenReturn(new MicrosoftDriveItem[] {folderItem});
    when(driveItemsResponse.getNextPageLink()).thenReturn(null);
    StringPaginationToken inputPaginationToken =
        new StringPaginationToken(DRIVE_TOKEN_PREFIX + DRIVE_PAGE_URL);

    // Run
    ExportResult<MediaContainerResource> result = microsoftMediaExporter.exportOneDrivePhotos(
        null, Optional.empty(), Optional.of(inputPaginationToken), uuid);

    // Verify method calls
    verify(mediaInterface).getDriveItems(Optional.empty(), Optional.of(DRIVE_PAGE_URL));
    verify(driveItemsResponse).getDriveItems();

    // Verify next pagination token is absent
    ContinuationData continuationData = result.getContinuationData();
    StringPaginationToken paginationToken =
        (StringPaginationToken) continuationData.getPaginationData();
    assertThat(paginationToken).isEqualTo(null);

    // Verify one album is ready for import
    Collection<MediaAlbum> actualAlbums = result.getExportedData().getAlbums();
    assertThat(actualAlbums.stream().map(MediaAlbum::getId).collect(Collectors.toList()))
        .containsExactly(FOLDER_ID);

    // Verify photos should be empty (in the root)
    Collection<PhotoModel> actualPhotos = result.getExportedData().getPhotos();
    assertThat(actualPhotos).isEmpty();

    // Verify there is one container ready for sub-processing
    List<ContainerResource> actualResources = continuationData.getContainerResources();
    assertThat(actualResources.stream()
                   .map(a -> ((IdOnlyContainerResource) a).getId())
                   .collect(Collectors.toList()))
        .containsExactly(FOLDER_ID);
  }

  @Test
  public void exportMediaWithNextPage() throws IOException {
    // Setup
    when(driveItemsResponse.getNextPageLink()).thenReturn(null);
    when(driveItemsResponse.getDriveItems()).thenReturn(new MicrosoftDriveItem[] {
      setUpSinglePhoto(PHOTO_FILENAME, PHOTO_URI, PHOTO_ID),
      setUpSingleVideo(VIDEO_FILENAME, VIDEO_URI, VIDEO_ID)
    });
    when(driveItemsResponse.getNextPageLink()).thenReturn(DRIVE_PAGE_URL);
    IdOnlyContainerResource idOnlyContainerResource = new IdOnlyContainerResource(FOLDER_ID);

    // Run
    ExportResult<MediaContainerResource> result = microsoftMediaExporter.exportOneDrivePhotos(
        null, Optional.of(idOnlyContainerResource), Optional.empty(), uuid);

    // Verify method calls
    verify(mediaInterface).getDriveItems(Optional.of(FOLDER_ID), Optional.empty());
    verify(driveItemsResponse).getDriveItems();

    // Verify pagination token is set
    ContinuationData continuationData = result.getContinuationData();
    StringPaginationToken paginationToken =
        (StringPaginationToken) continuationData.getPaginationData();
    assertThat(paginationToken.getToken()).isEqualTo(DRIVE_TOKEN_PREFIX + DRIVE_PAGE_URL);

    // Verify no albums are exported
    Collection<MediaAlbum> actualAlbums = result.getExportedData().getAlbums();
    assertThat(actualAlbums).isEmpty();

    // Verify one photo (in an album) should be exported
    Collection<PhotoModel> actualPhotos = result.getExportedData().getPhotos();
    assertThat(actualPhotos.size()).isEqualTo(1);
    assertThat(actualPhotos.stream().map(PhotoModel::getFetchableUrl).collect(Collectors.toList()))
        .containsExactly(PHOTO_URI);
    assertThat(actualPhotos.stream().map(PhotoModel::getAlbumId).collect(Collectors.toList()))
        .containsExactly(FOLDER_ID);
    assertThat(actualPhotos.stream().map(PhotoModel::getTitle).collect(Collectors.toList()))
        .containsExactly(PHOTO_FILENAME);

    // Verify one video (in an album) should be exported
    Collection<VideoModel> actualVideos = result.getExportedData().getVideos();
    assertThat(actualVideos.size()).isEqualTo(1);
    assertThat(actualVideos.stream().map(VideoModel::getFetchableUrl).collect(Collectors.toList()))
        .containsExactly(VIDEO_URI);
    assertThat(actualVideos.stream().map(VideoModel::getAlbumId).collect(Collectors.toList()))
        .containsExactly(FOLDER_ID);
    assertThat(actualVideos.stream().map(VideoModel::getName).collect(Collectors.toList()))
        .containsExactly(VIDEO_FILENAME);

    // Verify there are no containers ready for sub-processing
    List<ContainerResource> actualResources = continuationData.getContainerResources();
    assertThat(actualResources).isEmpty();
  }

  @Test
  public void exportMediaWithoutNextPage() throws IOException {
    // Setup
    when(driveItemsResponse.getNextPageLink()).thenReturn(null);
    when(driveItemsResponse.getDriveItems()).thenReturn(new MicrosoftDriveItem[] {
      setUpSinglePhoto(PHOTO_FILENAME, PHOTO_URI, PHOTO_ID),
      setUpSingleVideo(VIDEO_FILENAME, VIDEO_URI, VIDEO_ID)
    });
    when(driveItemsResponse.getNextPageLink()).thenReturn(null);
    StringPaginationToken inputPaginationToken =
        new StringPaginationToken(DRIVE_TOKEN_PREFIX + DRIVE_PAGE_URL);
    IdOnlyContainerResource idOnlyContainerResource = new IdOnlyContainerResource(FOLDER_ID);

    // Run
    ExportResult<MediaContainerResource> result = microsoftMediaExporter.exportOneDrivePhotos(
        null, Optional.of(idOnlyContainerResource), Optional.of(inputPaginationToken), uuid);

    // Verify method calls
    verify(mediaInterface).getDriveItems(Optional.of(FOLDER_ID), Optional.of(DRIVE_PAGE_URL));
    verify(driveItemsResponse).getDriveItems();

    // Verify next pagination token is absent
    ContinuationData continuationData = result.getContinuationData();
    StringPaginationToken paginationToken =
        (StringPaginationToken) continuationData.getPaginationData();
    assertThat(paginationToken).isEqualTo(null);

    // Verify no albums are exported
    Collection<MediaAlbum> actualAlbums = result.getExportedData().getAlbums();
    assertThat(actualAlbums).isEmpty();

    // Verify one photo (in an album) should be exported
    Collection<PhotoModel> actualPhotos = result.getExportedData().getPhotos();
    assertThat(actualPhotos.stream().map(PhotoModel::getFetchableUrl).collect(Collectors.toList()))
        .containsExactly(PHOTO_URI);
    assertThat(actualPhotos.stream().map(PhotoModel::getAlbumId).collect(Collectors.toList()))
        .containsExactly(FOLDER_ID);
    assertThat(actualPhotos.stream().map(PhotoModel::getTitle).collect(Collectors.toList()))
        .containsExactly(PHOTO_FILENAME);

    // Verify there are no containers ready for sub-processing
    List<ContainerResource> actualResources = continuationData.getContainerResources();
    assertThat(actualResources).isEmpty();
  }

  @Test
  public void exportAlbumAndMediaWithNextPage() throws IOException {
    // Setup
    when(driveItemsResponse.getDriveItems())
        .thenReturn(new MicrosoftDriveItem[] {
          setUpSingleAlbum(),
          setUpSinglePhoto(PHOTO_FILENAME, PHOTO_URI, PHOTO_ID)
        });
    when(driveItemsResponse.getNextPageLink()).thenReturn(DRIVE_PAGE_URL);

    // Run
    ExportResult<MediaContainerResource> result = microsoftMediaExporter.exportOneDrivePhotos(
        null, Optional.empty(), Optional.empty(), uuid);

    // Verify method calls
    verify(mediaInterface)
        .getDriveItemsFromSpecialFolder(MicrosoftSpecialFolder.FolderType.photos);
    verify(driveItemsResponse).getDriveItems();

    // Verify pagination token is set
    ContinuationData continuationData = result.getContinuationData();
    StringPaginationToken paginationToken =
        (StringPaginationToken) continuationData.getPaginationData();
    assertThat(paginationToken.getToken()).isEqualTo(DRIVE_TOKEN_PREFIX + DRIVE_PAGE_URL);

    // Verify one album is ready for import
    Collection<MediaAlbum> actualAlbums = result.getExportedData().getAlbums();
    assertThat(actualAlbums.stream().map(MediaAlbum::getId).collect(Collectors.toList()))
        .containsExactly(FOLDER_ID);

    // Verify one photo should be present (in the root Photos special folder)
    Collection<PhotoModel> actualPhotos = result.getExportedData().getPhotos();
    assertThat(actualPhotos.stream().map(PhotoModel::getFetchableUrl).collect(Collectors.toList()))
        .containsExactly(PHOTO_URI);
    assertThat(actualPhotos.stream().map(PhotoModel::getAlbumId).collect(Collectors.toList()))
        .containsExactly(null);
    assertThat(actualPhotos.stream().map(PhotoModel::getTitle).collect(Collectors.toList()))
        .containsExactly(PHOTO_FILENAME);

    // Verify there is one container ready for sub-processing
    List<ContainerResource> actualResources = continuationData.getContainerResources();
    assertThat(actualResources.stream()
                   .map(a -> ((IdOnlyContainerResource) a).getId())
                   .collect(Collectors.toList()))
        .containsExactly(FOLDER_ID);
  }

  @Test
  public void exportMediaWithSha1() throws IOException {
    String photoSha1 = "0123456789ABCDEF0123456789ABCDEF01234567";
    String videoSha1 = "fedcba9876543210fedcba9876543210fedcba98";

    MicrosoftDriveItem photoItem = setUpSinglePhoto(PHOTO_FILENAME, PHOTO_URI, PHOTO_ID);
    photoItem.file.hashes = new MicrosoftFileMetadata.MicrosoftFileHashes();
    photoItem.file.hashes.sha1Hash = photoSha1;

    MicrosoftDriveItem videoItem = setUpSingleVideo(VIDEO_FILENAME, VIDEO_URI, VIDEO_ID);
    videoItem.file.hashes = new MicrosoftFileMetadata.MicrosoftFileHashes();
    videoItem.file.hashes.sha1Hash = videoSha1;

    when(driveItemsResponse.getDriveItems())
        .thenReturn(new MicrosoftDriveItem[] {photoItem, videoItem});
    when(driveItemsResponse.getNextPageLink()).thenReturn(null);

    ExportResult<MediaContainerResource> result =
        microsoftMediaExporter.exportOneDrivePhotos(
            null, Optional.of(new IdOnlyContainerResource(FOLDER_ID)), Optional.empty(), uuid);

    PhotoModel exportedPhoto = result.getExportedData().getPhotos().iterator().next();
    assertThat(exportedPhoto.getSha1()).isEqualTo(photoSha1);
    assertThat(exportedPhoto.getUploadedTime()).isNull();

    VideoModel exportedVideo = result.getExportedData().getVideos().iterator().next();
    assertThat(exportedVideo.getSha1()).isEqualTo(videoSha1);
    assertThat(exportedVideo.getUploadedTime()).isNull();
  }

  @Test
  public void exportMediaWithInvalidSha1LeavesSha1Unset() throws IOException {
    MicrosoftDriveItem photoItem = setUpSinglePhoto(PHOTO_FILENAME, PHOTO_URI, PHOTO_ID);
    photoItem.file.hashes = new MicrosoftFileMetadata.MicrosoftFileHashes();
    photoItem.file.hashes.sha1Hash = "0123456789abcdef"; // Too short.

    MicrosoftDriveItem videoItem = setUpSingleVideo(VIDEO_FILENAME, VIDEO_URI, VIDEO_ID);
    videoItem.file.hashes = new MicrosoftFileMetadata.MicrosoftFileHashes();
    videoItem.file.hashes.sha1Hash = "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"; // Not hex.

    when(driveItemsResponse.getDriveItems())
        .thenReturn(new MicrosoftDriveItem[] {photoItem, videoItem});
    when(driveItemsResponse.getNextPageLink()).thenReturn(null);

    ExportResult<MediaContainerResource> result =
        microsoftMediaExporter.exportOneDrivePhotos(
            null, Optional.of(new IdOnlyContainerResource(FOLDER_ID)), Optional.empty(), uuid);

    assertThat(result.getExportedData().getPhotos().iterator().next().getSha1()).isNull();
    assertThat(result.getExportedData().getVideos().iterator().next().getSha1()).isNull();
  }

  @Test
  public void exportMediaWithTakenDateTimeUsesTakenTime() throws IOException {
    MicrosoftDriveItem photoItem = setUpSinglePhoto(PHOTO_FILENAME, PHOTO_URI, PHOTO_ID);
    photoItem.photo.takenDateTime = "2023-05-14T15:30:00Z";
    photoItem.createdDateTime = UPLOAD_TIME;

    MicrosoftDriveItem videoItem = setUpSingleVideo(VIDEO_FILENAME, VIDEO_URI, VIDEO_ID);
    videoItem.photo.takenDateTime = "2023-05-14T15:30:00+02:00";
    videoItem.createdDateTime = UPLOAD_TIME;

    ExportResult<MediaContainerResource> result = exportPhotoAndVideo(photoItem, videoItem);

    PhotoModel exportedPhoto = result.getExportedData().getPhotos().iterator().next();
    assertThat(exportedPhoto.getUploadedTime())
        .isEqualTo(Date.from(Instant.parse("2023-05-14T15:30:00Z")));
    // Favorite info stays unknown, as it was before the time was exported.
    assertThat(exportedPhoto.getFavoriteInfo().getFavorited()).isFalse();
    assertThat(exportedPhoto.getFavoriteInfo().getLastUpdateTime()).isNull();

    VideoModel exportedVideo = result.getExportedData().getVideos().iterator().next();
    assertThat(exportedVideo.getUploadedTime())
        .isEqualTo(Date.from(Instant.parse("2023-05-14T13:30:00Z")));
    assertThat(exportedVideo.getFavoriteInfo().getFavorited()).isFalse();
    assertThat(exportedVideo.getFavoriteInfo().getLastUpdateTime()).isNull();
  }

  @Test
  public void exportMediaWithoutTakenDateTimeUsesUploadTime() throws IOException {
    MicrosoftDriveItem photoItem = setUpSinglePhoto(PHOTO_FILENAME, PHOTO_URI, PHOTO_ID);
    photoItem.photo.takenDateTime = null;
    photoItem.createdDateTime = UPLOAD_TIME;

    MicrosoftDriveItem videoItem = setUpSingleVideo(VIDEO_FILENAME, VIDEO_URI, VIDEO_ID);
    videoItem.photo = null; // No photo facet, so no taken time.
    videoItem.createdDateTime = UPLOAD_TIME;

    ExportResult<MediaContainerResource> result = exportPhotoAndVideo(photoItem, videoItem);

    Date uploadTime = Date.from(Instant.parse(UPLOAD_TIME));
    assertThat(result.getExportedData().getPhotos().iterator().next().getUploadedTime())
        .isEqualTo(uploadTime);
    assertThat(result.getExportedData().getVideos().iterator().next().getUploadedTime())
        .isEqualTo(uploadTime);
  }

  @Test
  public void exportMediaWithEmptyOrInvalidTakenDateTimeUsesUploadTime() throws IOException {
    MicrosoftDriveItem photoItem = setUpSinglePhoto(PHOTO_FILENAME, PHOTO_URI, PHOTO_ID);
    photoItem.photo.takenDateTime = "not a date";
    photoItem.createdDateTime = UPLOAD_TIME;

    MicrosoftDriveItem videoItem = setUpSingleVideo(VIDEO_FILENAME, VIDEO_URI, VIDEO_ID);
    videoItem.photo.takenDateTime = "";
    videoItem.createdDateTime = UPLOAD_TIME;

    ExportResult<MediaContainerResource> result = exportPhotoAndVideo(photoItem, videoItem);

    Date uploadTime = Date.from(Instant.parse(UPLOAD_TIME));
    assertThat(result.getExportedData().getPhotos().iterator().next().getUploadedTime())
        .isEqualTo(uploadTime);
    assertThat(result.getExportedData().getVideos().iterator().next().getUploadedTime())
        .isEqualTo(uploadTime);
  }

  @Test
  public void exportMediaWithoutValidTimesLeavesUploadedTimeUnset() throws IOException {
    MicrosoftDriveItem photoItem = setUpSinglePhoto(PHOTO_FILENAME, PHOTO_URI, PHOTO_ID);
    photoItem.photo.takenDateTime = "not a date";
    photoItem.createdDateTime = "also not a date";

    MicrosoftDriveItem videoItem = setUpSingleVideo(VIDEO_FILENAME, VIDEO_URI, VIDEO_ID);
    videoItem.photo = null; // No taken time, and no upload time either.

    ExportResult<MediaContainerResource> result = exportPhotoAndVideo(photoItem, videoItem);

    assertThat(result.getExportedData().getPhotos().iterator().next().getUploadedTime()).isNull();
    assertThat(result.getExportedData().getVideos().iterator().next().getUploadedTime()).isNull();
  }

  /** Exports one page of a folder that contains the given photo and video. */
  private ExportResult<MediaContainerResource> exportPhotoAndVideo(
      MicrosoftDriveItem photoItem, MicrosoftDriveItem videoItem) throws IOException {
    when(driveItemsResponse.getDriveItems())
        .thenReturn(new MicrosoftDriveItem[] {photoItem, videoItem});
    when(driveItemsResponse.getNextPageLink()).thenReturn(null);

    return microsoftMediaExporter.exportOneDrivePhotos(
        null, Optional.of(new IdOnlyContainerResource(FOLDER_ID)), Optional.empty(), uuid);
  }

  @Test
  public void exportRefreshesTokenOnceAndRetriesAfter401() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(401));
    server.enqueue(drivePageResponse(null /*nextPageLink*/));

    ExportResult<MediaContainerResource> result =
        exporterWithTokenRefresh().export(uuid, authData("job-token"), drivePage("/page"));

    verify(credentialFactory, times(1)).refreshCredential(any());
    assertThat(authorizationHeaders())
        .containsExactly("Bearer job-token", "Bearer job-token-refreshed")
        .inOrder();
    assertThat(result.getExportedData().getPhotos().stream()
            .map(PhotoModel::getTitle).collect(Collectors.toList()))
        .containsExactly(PHOTO_FILENAME);
  }

  @Test
  public void exportFailsAfterOneRefreshWhenStillUnauthorized() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(401));
    server.enqueue(new MockResponse().setResponseCode(401));
    MicrosoftMediaExporter exporter = exporterWithTokenRefresh();

    IOException e = assertThrows(IOException.class,
        () -> exporter.export(uuid, authData("job-token"), drivePage("/page")));

    assertThat(e).hasMessageThat().contains("401 Unauthorized after refreshing");
    assertThat(e).hasCauseThat().isInstanceOf(HttpResponseException.class);
    verify(credentialFactory, times(1)).refreshCredential(any());
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  public void exportFailsWithoutRetryingWhenRefreshFails() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(401));
    MicrosoftMediaExporter exporter = exporterWithTokenRefresh();
    IOException refreshFailure = new IOException("refresh rejected");
    doThrow(refreshFailure).when(credentialFactory).refreshCredential(any());

    IOException e = assertThrows(IOException.class,
        () -> exporter.export(uuid, authData("job-token"), drivePage("/page")));

    assertThat(e).isSameAs(refreshFailure);
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  public void exportDoesNotRefreshWithoutRefreshToken() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(401));
    MicrosoftMediaExporter exporter = exporterWithTokenRefresh();
    TokensAndUrlAuthData authData =
        new TokensAndUrlAuthData("job-token", null, server.url("/token").toString());

    HttpResponseException e = assertThrows(HttpResponseException.class,
        () -> exporter.export(uuid, authData, drivePage("/page")));

    assertThat(e.getStatusCode()).isEqualTo(401);
    verify(credentialFactory, never()).refreshCredential(any());
  }

  @Test
  public void exportReusesRefreshedTokenOnLaterPagesOfTheSameJobOnly() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(401));
    server.enqueue(drivePageResponse(server.url("/page2").toString()));
    server.enqueue(drivePageResponse(null /*nextPageLink*/));
    server.enqueue(drivePageResponse(null /*nextPageLink*/));
    MicrosoftMediaExporter exporter = exporterWithTokenRefresh();
    TokensAndUrlAuthData authData = authData("job-token");

    ExportResult<MediaContainerResource> firstPage =
        exporter.export(uuid, authData, drivePage("/page1"));
    exporter.export(uuid, authData, Optional.of(
        new ExportInformation(firstPage.getContinuationData().getPaginationData(), null)));
    exporter.export(UUID.randomUUID(), authData("other-job-token"), drivePage("/other-job"));

    verify(credentialFactory, times(1)).refreshCredential(any());
    verify(credentialFactory, times(2)).createCredential(any());
    assertThat(authorizationHeaders())
        .containsExactly(
            "Bearer job-token", // First page, rejected.
            "Bearer job-token-refreshed", // First page, retried.
            "Bearer job-token-refreshed", // Second page reuses the refreshed credential.
            "Bearer other-job-token") // Another job uses its own credential.
        .inOrder();
  }

  /**
   * Returns an exporter that builds its own media interface for each job. Each job's credential is
   * built from its auth data as in production, and refreshing it appends "-refreshed" to the
   * access token.
   */
  private MicrosoftMediaExporter exporterWithTokenRefresh() throws IOException {
    MicrosoftCredentialFactory realFactory = new MicrosoftCredentialFactory(
        new NetHttpTransport(), new JacksonFactory(), new AppCredentials("appKey", "appSecret"));
    when(credentialFactory.createCredential(any()))
        .thenAnswer(invocation -> realFactory.createCredential(invocation.getArgument(0)));
    when(credentialFactory.refreshCredential(any())).thenAnswer(invocation -> {
      Credential credential = invocation.getArgument(0);
      return credential.setAccessToken(credential.getAccessToken() + "-refreshed");
    });
    return new MicrosoftMediaExporter(credentialFactory, new JacksonFactory(), mock(Monitor.class));
  }

  private TokensAndUrlAuthData authData(String accessToken) {
    return new TokensAndUrlAuthData(accessToken, "refreshToken", server.url("/token").toString());
  }

  /** Points an export at a page of drive items on the mock server, as pagination does. */
  private Optional<ExportInformation> drivePage(String path) {
    return Optional.of(new ExportInformation(
        new StringPaginationToken(DRIVE_TOKEN_PREFIX + server.url(path)), null));
  }

  /** Returns a page holding one photo, linking to the next page if there is one. */
  private MockResponse drivePageResponse(String nextPageLink) {
    String nextPage =
        nextPageLink == null ? "" : ", \"@odata.nextLink\": \"" + nextPageLink + "\"";
    return new MockResponse().setBody("{\"value\": [{\"id\": \"" + PHOTO_ID + "\", \"name\": \""
        + PHOTO_FILENAME + "\", \"file\": {\"mimeType\": \"image/jpeg\"}}]" + nextPage + "}");
  }

  /** Returns the Authorization header of each request the mock server received, in order. */
  private List<String> authorizationHeaders() throws InterruptedException {
    List<String> headers = new ArrayList<>();
    for (int i = server.getRequestCount(); i > 0; i--) {
      headers.add(server.takeRequest().getHeader("Authorization"));
    }
    return headers;
  }

  /** Sets up a response with a single album, containing photos and/or videos. */
  private MicrosoftDriveItem setUpSingleAlbum() {
    MicrosoftDriveItem albumEntry = new MicrosoftDriveItem();
    albumEntry.id = FOLDER_ID;
    albumEntry.name = "Title";
    albumEntry.folder = new MicrosoftDriveFolder();
    // TODO(zacsh) remove this childCount setting (or better: set it to a non-sensical value like
    // zero). We clearly don't care about this (our tests don't break if this is incorrect) so even
    // though the upstream APIs provide this, we shouldn't be writing test-doubles that imply that
    // we adhere to this API in anyway.
    albumEntry.folder.childCount = 1;

    return albumEntry;
  }

  /** Sets up a response for a single file. */
  private MicrosoftDriveItem setupSingleFile(String fileName, String downloadUrl, String fileId, String mimeType) {
    MicrosoftDriveItem photoEntry = new MicrosoftDriveItem();
    photoEntry.description = String.format("Description of %s", fileId);
    photoEntry.file = new MicrosoftFileMetadata();
    photoEntry.file.mimeType = mimeType;
    photoEntry.name = fileName;
    photoEntry.id = fileId;
    photoEntry.downloadUrl = downloadUrl;
    photoEntry.photo = new MicrosoftPhotoMetadata();
    return photoEntry;
  }

  /** Sets up a response for a single photo. */
  private MicrosoftDriveItem setUpSinglePhoto(String fileName, String imageUri, String photoId) {
    MicrosoftDriveItem driveItem = setupSingleFile(fileName, imageUri, photoId, "image/jpeg");
    driveItem.photo = new MicrosoftPhotoMetadata();
    return driveItem;
  }

  /** Sets up a response for a single video. */
  private MicrosoftDriveItem setUpSingleVideo(String fileName, String imageUri, String videoId) {
    MicrosoftDriveItem driveItem = setupSingleFile(fileName, imageUri, videoId, "video/ogg");
    driveItem.video = new MicrosoftVideoMetadata();
    return driveItem;
  }
}
