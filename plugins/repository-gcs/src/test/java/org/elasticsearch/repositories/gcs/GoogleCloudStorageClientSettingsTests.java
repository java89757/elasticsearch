/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.repositories.gcs;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.services.storage.StorageScopes;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.test.ESTestCase;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.elasticsearch.repositories.gcs.GoogleCloudStorageClientSettings.APPLICATION_NAME_SETTING;
import static org.elasticsearch.repositories.gcs.GoogleCloudStorageClientSettings.CONNECT_TIMEOUT_SETTING;
import static org.elasticsearch.repositories.gcs.GoogleCloudStorageClientSettings.CREDENTIALS_FILE_SETTING;
import static org.elasticsearch.repositories.gcs.GoogleCloudStorageClientSettings.ENDPOINT_SETTING;
import static org.elasticsearch.repositories.gcs.GoogleCloudStorageClientSettings.READ_TIMEOUT_SETTING;
import static org.elasticsearch.repositories.gcs.GoogleCloudStorageClientSettings.getClientSettings;
import static org.elasticsearch.repositories.gcs.GoogleCloudStorageClientSettings.loadCredential;

public class GoogleCloudStorageClientSettingsTests extends ESTestCase {

    public void testLoadWithEmptySettings() {
        Map<String, GoogleCloudStorageClientSettings> clientsSettings = GoogleCloudStorageClientSettings.load(Settings.EMPTY);
        assertEquals(1, clientsSettings.size());
        assertNotNull(clientsSettings.get("default"));
    }

    public void testLoad() throws Exception {
        final int nbClients = randomIntBetween(1, 5);
        final Tuple<Map<String, GoogleCloudStorageClientSettings>, Settings> randomClients = randomClients(nbClients);
        final Map<String, GoogleCloudStorageClientSettings> expectedClientsSettings = randomClients.v1();

        Map<String, GoogleCloudStorageClientSettings> actualClientsSettings = GoogleCloudStorageClientSettings.load(randomClients.v2());
        assertEquals(expectedClientsSettings.size(), actualClientsSettings.size());

        for (String clientName : expectedClientsSettings.keySet()) {
            GoogleCloudStorageClientSettings actualClientSettings = actualClientsSettings.get(clientName);
            assertNotNull(actualClientSettings);
            GoogleCloudStorageClientSettings expectedClientSettings = expectedClientsSettings.get(clientName);
            assertNotNull(expectedClientSettings);

            assertGoogleCredential(expectedClientSettings.getCredential(), actualClientSettings.getCredential());
            assertEquals(expectedClientSettings.getEndpoint(), actualClientSettings.getEndpoint());
            assertEquals(expectedClientSettings.getConnectTimeout(), actualClientSettings.getConnectTimeout());
            assertEquals(expectedClientSettings.getReadTimeout(), actualClientSettings.getReadTimeout());
            assertEquals(expectedClientSettings.getApplicationName(), actualClientSettings.getApplicationName());
        }
    }

    public void testLoadCredential() throws Exception {
        Tuple<Map<String, GoogleCloudStorageClientSettings>, Settings> randomClient = randomClients(1);
        GoogleCloudStorageClientSettings expectedClientSettings = randomClient.v1().values().iterator().next();
        String clientName = randomClient.v1().keySet().iterator().next();

        assertGoogleCredential(expectedClientSettings.getCredential(), loadCredential(randomClient.v2(), clientName));
    }

    /** Generates a given number of GoogleCloudStorageClientSettings along with the Settings to build them from **/
    private Tuple<Map<String, GoogleCloudStorageClientSettings>, Settings> randomClients(final int nbClients) throws Exception {
        final Map<String, GoogleCloudStorageClientSettings> expectedClients = new HashMap<>();
        expectedClients.put("default", getClientSettings(Settings.EMPTY, "default"));

        final Settings.Builder settings = Settings.builder();
        final MockSecureSettings secureSettings = new MockSecureSettings();

        for (int i = 0; i < nbClients; i++) {
            String clientName = randomAlphaOfLength(5).toLowerCase(Locale.ROOT);

            GoogleCloudStorageClientSettings clientSettings = randomClient(clientName, settings, secureSettings);
            expectedClients.put(clientName, clientSettings);
        }

        if (randomBoolean()) {
            GoogleCloudStorageClientSettings clientSettings = randomClient("default", settings, secureSettings);
            expectedClients.put("default", clientSettings);
        }

        return Tuple.tuple(expectedClients, settings.setSecureSettings(secureSettings).build());
    }

    /** Generates a random GoogleCloudStorageClientSettings along with the Settings to build it **/
    private static GoogleCloudStorageClientSettings randomClient(final String clientName,
                                                                 final Settings.Builder settings,
                                                                 final MockSecureSettings secureSettings) throws Exception {

        Tuple<GoogleCredential, byte[]> credentials = randomCredential(clientName);
        GoogleCredential credential = credentials.v1();
        secureSettings.setFile(CREDENTIALS_FILE_SETTING.getConcreteSettingForNamespace(clientName).getKey(), credentials.v2());

        String endpoint;
        if (randomBoolean()) {
            endpoint = randomAlphaOfLength(5);
            settings.put(ENDPOINT_SETTING.getConcreteSettingForNamespace(clientName).getKey(), endpoint);
        } else {
            endpoint = ENDPOINT_SETTING.getDefault(Settings.EMPTY);
        }

        TimeValue connectTimeout;
        if (randomBoolean()) {
            connectTimeout = randomTimeout();
            settings.put(CONNECT_TIMEOUT_SETTING.getConcreteSettingForNamespace(clientName).getKey(), connectTimeout.getStringRep());
        } else {
            connectTimeout = CONNECT_TIMEOUT_SETTING.getDefault(Settings.EMPTY);
        }

        TimeValue readTimeout;
        if (randomBoolean()) {
            readTimeout = randomTimeout();
            settings.put(READ_TIMEOUT_SETTING.getConcreteSettingForNamespace(clientName).getKey(), readTimeout.getStringRep());
        } else {
            readTimeout = READ_TIMEOUT_SETTING.getDefault(Settings.EMPTY);
        }

        String applicationName;
        if (randomBoolean()) {
            applicationName = randomAlphaOfLength(5);
            settings.put(APPLICATION_NAME_SETTING.getConcreteSettingForNamespace(clientName).getKey(), applicationName);
        } else {
            applicationName = APPLICATION_NAME_SETTING.getDefault(Settings.EMPTY);
        }

        return new GoogleCloudStorageClientSettings(credential, endpoint, connectTimeout, readTimeout, applicationName);
    }

    /** Generates a random GoogleCredential along with its corresponding Service Account file provided as a byte array **/
    private static Tuple<GoogleCredential, byte[]> randomCredential(final String clientName) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        GoogleCredential.Builder credentialBuilder = new GoogleCredential.Builder();
        credentialBuilder.setServiceAccountId(clientName);
        credentialBuilder.setServiceAccountProjectId("project_id_" + clientName);
        credentialBuilder.setServiceAccountScopes(Collections.singleton(StorageScopes.DEVSTORAGE_FULL_CONTROL));
        credentialBuilder.setServiceAccountPrivateKey(keyPair.getPrivate());
        credentialBuilder.setServiceAccountPrivateKeyId("private_key_id_" + clientName);

        String encodedPrivateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String serviceAccount = "{\"type\":\"service_account\"," +
            "\"project_id\":\"project_id_" + clientName + "\"," +
            "\"private_key_id\":\"private_key_id_" + clientName + "\"," +
            "\"private_key\":\"-----BEGIN PRIVATE KEY-----\\n" +
            encodedPrivateKey +
            "\\n-----END PRIVATE KEY-----\\n\"," +
            "\"client_email\":\"" + clientName + "\"," +
            "\"client_id\":\"id_" + clientName + "\"," +
            "\"auth_uri\":\"https://accounts.google.com/o/oauth2/auth\"," +
            "\"token_uri\":\"https://accounts.google.com/o/oauth2/token\"," +
            "\"auth_provider_x509_cert_url\":\"https://www.googleapis.com/oauth2/v1/certs\"," +
            "\"client_x509_cert_url\":\"https://www.googleapis.com/robot/v1/metadata/x509/" +
            clientName +
            "%40appspot.gserviceaccount.com\"}";

        return Tuple.tuple(credentialBuilder.build(), serviceAccount.getBytes(StandardCharsets.UTF_8));
    }

    private static TimeValue randomTimeout() {
        return randomFrom(TimeValue.MINUS_ONE, TimeValue.ZERO, TimeValue.parseTimeValue(randomPositiveTimeValue(), "test"));
    }

    private static void assertGoogleCredential(final GoogleCredential expected, final GoogleCredential actual) {
        if (expected != null) {
            assertEquals(expected.getServiceAccountUser(), actual.getServiceAccountUser());
            assertEquals(expected.getServiceAccountId(), actual.getServiceAccountId());
            assertEquals(expected.getServiceAccountProjectId(), actual.getServiceAccountProjectId());
            assertEquals(expected.getServiceAccountScopesAsString(), actual.getServiceAccountScopesAsString());
            assertEquals(expected.getServiceAccountPrivateKey(), actual.getServiceAccountPrivateKey());
            assertEquals(expected.getServiceAccountPrivateKeyId(), actual.getServiceAccountPrivateKeyId());
        } else {
            assertNull(actual);
        }
    }
}
