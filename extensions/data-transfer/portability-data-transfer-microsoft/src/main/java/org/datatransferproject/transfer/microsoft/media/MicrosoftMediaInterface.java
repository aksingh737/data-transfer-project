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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.*;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import org.datatransferproject.transfer.microsoft.common.MicrosoftCredentialFactory;
import org.datatransferproject.transfer.microsoft.driveModels.MicrosoftDriveItemsResponse;
import org.datatransferproject.transfer.microsoft.driveModels.MicrosoftSpecialFolder;

public class MicrosoftMediaInterface {
  private static final String BASE_GRAPH_URL = "https://graph.microsoft.com";
  private static final String ODATA_TOP = "top";
  private static final int PAGE_SIZE = 50;

  private final ObjectMapper objectMapper =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private final HttpTransport httpTransport = new NetHttpTransport();
  private final MicrosoftCredentialFactory credentialFactory;
  private final Credential credential;
  private final JsonFactory jsonFactory;

  MicrosoftMediaInterface(MicrosoftCredentialFactory credentialFactory, Credential credential,
      JsonFactory jsonFactory) {
    this.credentialFactory = credentialFactory;
    this.credential = credential;
    this.jsonFactory = jsonFactory;
  }

  MicrosoftDriveItemsResponse getDriveItems(
      Optional<String> folderId, Optional<String> paginationUrl) throws IOException {
    String requestUrl;
    Map<String, String> params = new LinkedHashMap<>();

    if (paginationUrl.isPresent()) {
      requestUrl = paginationUrl.get();
    } else if (folderId.isPresent()) {
      requestUrl = BASE_GRAPH_URL + "/v1.0/me/drive/items/" + folderId.get() + "/children";
      params.put(ODATA_TOP, String.valueOf(PAGE_SIZE));
    } else {
      requestUrl = BASE_GRAPH_URL + "/v1.0/me/drive/root/children";
      params.put(ODATA_TOP, String.valueOf(PAGE_SIZE));
    }

    return makeGetRequest(requestUrl, Optional.of(params), MicrosoftDriveItemsResponse.class);
  }

  MicrosoftDriveItemsResponse getDriveItemsFromSpecialFolder(
      MicrosoftSpecialFolder.FolderType folderType) throws IOException {
    Map<String, String> params = new LinkedHashMap<>();
    params.put(ODATA_TOP, String.valueOf(PAGE_SIZE));

    return makeGetRequest(
        BASE_GRAPH_URL + "/v1.0/me/drive/special/" + folderType.toString() + "/children",
        Optional.of(params), MicrosoftDriveItemsResponse.class);
  }

  private <T> T makeGetRequest(
      String url, Optional<Map<String, String>> parameters, Class<T> tClass) throws IOException {
    HttpRequestFactory requestFactory = httpTransport.createRequestFactory();
    if (parameters.isPresent() && parameters.get().size() > 0) {
      url = url + generateODataParams(parameters.get());
    }
    HttpRequest getRequest = requestFactory.buildGetRequest(new GenericUrl(url));
    setAuthorization(getRequest);

    HttpResponse response = executeWithTokenRefresh(getRequest);
    int statusCode = response.getStatusCode();
    if (statusCode != 200) {
      throw new IOException(
          "Bad status code: " + statusCode + " error: " + response.getStatusMessage());
    }
    String result = CharStreams.toString(new InputStreamReader(response.getContent(), UTF_8));
    return objectMapper.readValue(result, tClass);
  }

  /**
   * Executes the request. If Graph rejects the access token with a 401, as it does once the token
   * expires (60 to 90 minutes after it's issued), refreshes the credential once and retries once.
   */
  private HttpResponse executeWithTokenRefresh(HttpRequest request) throws IOException {
    try {
      return request.execute();
    } catch (HttpResponseException e) {
      // Without a refresh token there's nothing to refresh, so fail as before.
      if (e.getStatusCode() != 401 || Strings.isNullOrEmpty(credential.getRefreshToken())) {
        throw e;
      }
    }
    // If the refresh fails, its exception propagates, as in MicrosoftMediaImporter.
    credentialFactory.refreshCredential(credential);
    setAuthorization(request);
    try {
      return request.execute();
    } catch (HttpResponseException e) {
      if (e.getStatusCode() != 401) {
        throw e;
      }
      throw new IOException(
          "Microsoft Graph still returned 401 Unauthorized after refreshing the access token", e);
    }
  }

  private void setAuthorization(HttpRequest request) throws IOException {
    if (credential.getAccessToken() == null) {
      credential.refreshToken();
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setAccept("application/json");
    headers.setAuthorization("Bearer " + this.credential.getAccessToken());

    request.setHeaders(headers);
  }

  private String generateODataParams(Map<String, String> params) {
    Preconditions.checkArgument(params != null);
    List<String> orderedKeys = new ArrayList<>(params.keySet());
    Collections.sort(orderedKeys);

    List<String> paramStrings = new ArrayList<>();
    for (String key : orderedKeys) {
      String k = key.trim();
      String v = params.get(key).trim();

      paramStrings.add("$" + k + "=" + v);
    }

    return "?" + String.join("&", paramStrings);
  }
}
