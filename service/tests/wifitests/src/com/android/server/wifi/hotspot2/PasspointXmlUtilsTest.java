/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import static org.junit.Assert.*;

import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.net.wifi.hotspot2.pps.Policy;
import android.net.wifi.hotspot2.pps.UpdateParameter;
import android.os.ParcelUuid;
import android.util.Xml;

import androidx.test.filters.SmallTest;

import com.android.internal.util.FastXmlSerializer;
import com.android.modules.utils.build.SdkLevel;
import com.android.server.wifi.WifiBaseTest;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.PasspointXmlUtilsTest}.
 */
@SmallTest
public class PasspointXmlUtilsTest extends WifiBaseTest {

    private static final int TEST_CARRIER_ID = 129;
    private static final int TEST_SUBSCRIPTION_ID = 1;
    private static final String TEST_DECORATED_IDENTITY_PREFIX = "androidwifi.dev!";
    private static final ParcelUuid GROUP_UUID = ParcelUuid
            .fromString("0000110B-0000-1000-8000-00805F9B34FB");

    /**
     * Helper function for generating a {@link PasspointConfiguration} for testing the XML
     * serialization/deserialization logic.
     *
     * @return {@link PasspointConfiguration}
     * @throws Exception
     */
    private PasspointConfiguration createFullPasspointConfiguration() throws Exception {
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        byte[] certFingerprint = new byte[32];
        Arrays.fill(certFingerprint, (byte) 0x1f);

        PasspointConfiguration config = new PasspointConfiguration();
        config.setUpdateIdentifier(12);
        config.setCredentialPriority(99);

        // AAA Server trust root.
        Map<String, byte[]> trustRootCertList = new HashMap<>();
        trustRootCertList.put("server1.trust.root.com", certFingerprint);
        config.setTrustRootCertList(trustRootCertList);

        // Subscription update.
        UpdateParameter subscriptionUpdate = new UpdateParameter();
        subscriptionUpdate.setUpdateIntervalInMinutes(120);
        subscriptionUpdate.setUpdateMethod(UpdateParameter.UPDATE_METHOD_SPP);
        subscriptionUpdate.setRestriction(UpdateParameter.UPDATE_RESTRICTION_ROAMING_PARTNER);
        subscriptionUpdate.setServerUri("subscription.update.com");
        subscriptionUpdate.setUsername("subscriptionUser");
        subscriptionUpdate.setBase64EncodedPassword("subscriptionPass");
        subscriptionUpdate.setTrustRootCertUrl("subscription.update.cert.com");
        subscriptionUpdate.setTrustRootCertSha256Fingerprint(certFingerprint);
        config.setSubscriptionUpdate(subscriptionUpdate);

        // Subscription parameters.
        config.setSubscriptionCreationTimeInMillis(format.parse("2016-02-01T10:00:00Z").getTime());
        config.setSubscriptionExpirationTimeInMillis(
                format.parse("2016-03-01T10:00:00Z").getTime());
        config.setSubscriptionType("Gold");
        config.setUsageLimitDataLimit(921890);
        config.setUsageLimitStartTimeInMillis(format.parse("2016-12-01T10:00:00Z").getTime());
        config.setUsageLimitTimeLimitInMinutes(120);
        config.setUsageLimitUsageTimePeriodInMinutes(99910);

        // HomeSP configuration.
        HomeSp homeSp = new HomeSp();
        homeSp.setFriendlyName("Century House");
        homeSp.setFqdn("mi6.co.uk");
        homeSp.setRoamingConsortiumOis(new long[] {0x112233L, 0x445566L});
        homeSp.setIconUrl("icon.test.com");
        Map<String, Long> homeNetworkIds = new HashMap<>();
        homeNetworkIds.put("TestSSID", 0x12345678L);
        homeNetworkIds.put("NullHESSID", null);
        homeSp.setHomeNetworkIds(homeNetworkIds);
        homeSp.setMatchAllOis(new long[] {0x11223344});
        homeSp.setMatchAnyOis(new long[] {0x55667788});
        homeSp.setOtherHomePartners(new String[] {"other.fqdn.com"});
        config.setHomeSp(homeSp);

        // Credential configuration.
        Credential credential = new Credential();
        credential.setCreationTimeInMillis(format.parse("2016-01-01T10:00:00Z").getTime());
        credential.setExpirationTimeInMillis(format.parse("2016-02-01T10:00:00Z").getTime());
        credential.setRealm("shaken.stirred.com");
        credential.setCheckAaaServerCertStatus(true);
        Credential.UserCredential userCredential = new Credential.UserCredential();
        userCredential.setUsername("james");
        userCredential.setPassword("Ym9uZDAwNw==");
        userCredential.setMachineManaged(true);
        userCredential.setSoftTokenApp("TestApp");
        userCredential.setAbleToShare(true);
        userCredential.setEapType(21);
        userCredential.setNonEapInnerMethod("MS-CHAP-V2");
        credential.setUserCredential(userCredential);
        Credential.CertificateCredential certCredential = new Credential.CertificateCredential();
        certCredential.setCertType("x509v3");
        certCredential.setCertSha256Fingerprint(certFingerprint);
        credential.setCertCredential(certCredential);
        Credential.SimCredential simCredential = new Credential.SimCredential();
        simCredential.setImsi("imsi");
        simCredential.setEapType(24);
        credential.setSimCredential(simCredential);
        config.setCredential(credential);

        // Policy configuration.
        Policy policy = new Policy();
        List<Policy.RoamingPartner> preferredRoamingPartnerList = new ArrayList<>();
        Policy.RoamingPartner partner1 = new Policy.RoamingPartner();
        partner1.setFqdn("test1.fqdn.com");
        partner1.setFqdnExactMatch(true);
        partner1.setPriority(127);
        partner1.setCountries("us,fr");
        Policy.RoamingPartner partner2 = new Policy.RoamingPartner();
        partner2.setFqdn("test2.fqdn.com");
        partner2.setFqdnExactMatch(false);
        partner2.setPriority(200);
        partner2.setCountries("*");
        preferredRoamingPartnerList.add(partner1);
        preferredRoamingPartnerList.add(partner2);
        policy.setPreferredRoamingPartnerList(preferredRoamingPartnerList);
        policy.setMinHomeDownlinkBandwidth(23412);
        policy.setMinHomeUplinkBandwidth(9823);
        policy.setMinRoamingDownlinkBandwidth(9271);
        policy.setMinRoamingUplinkBandwidth(2315);
        policy.setExcludedSsidList(new String[] {"excludeSSID"});
        Map<Integer, String> requiredProtoPortMap = new HashMap<>();
        requiredProtoPortMap.put(12, "34,92,234");
        policy.setRequiredProtoPortMap(requiredProtoPortMap);
        policy.setMaximumBssLoadValue(23);
        UpdateParameter policyUpdate = new UpdateParameter();
        policyUpdate.setUpdateIntervalInMinutes(120);
        policyUpdate.setUpdateMethod(UpdateParameter.UPDATE_METHOD_OMADM);
        policyUpdate.setRestriction(UpdateParameter.UPDATE_RESTRICTION_HOMESP);
        policyUpdate.setServerUri("policy.update.com");
        policyUpdate.setUsername("updateUser");
        policyUpdate.setBase64EncodedPassword("updatePass");
        policyUpdate.setTrustRootCertUrl("update.cert.com");
        policyUpdate.setTrustRootCertSha256Fingerprint(certFingerprint);
        policy.setPolicyUpdate(policyUpdate);
        config.setPolicy(policy);

        //Set suggestion related flag
        config.setCarrierMerged(true);
        config.setOemPaid(true);
        config.setOemPrivate(true);
        config.setCarrierId(TEST_CARRIER_ID);
        config.setSubscriptionId(TEST_SUBSCRIPTION_ID);
        config.setSubscriptionGroup(GROUP_UUID);

        // Extensions
        if (SdkLevel.isAtLeastS()) {
            config.setDecoratedIdentityPrefix(TEST_DECORATED_IDENTITY_PREFIX);
        }

        String[] aaaServerTrustedNames = new String[] {"www.google.com", "www.android.com"};
        config.setAaaServerTrustedNames(aaaServerTrustedNames);
        return config;
    }

    /**
     * Verify the serialization and deserialization logic of a {@link PasspointConfiguration}.
     *
     * 1. Serialize the test config to a XML block
     * 2. Deserialize the XML block to a {@link PasspointConfiguration}
     * 3. Verify that the deserialized config is the same as the test config
     *
     * @param testConfig The configuration to used for testing
     * @throws Exception
     */
    private void serializeAndDeserializePasspointConfiguration(PasspointConfiguration testConfig)
            throws Exception {
        final XmlSerializer out = new FastXmlSerializer();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        out.setOutput(outputStream, StandardCharsets.UTF_8.name());
        PasspointXmlUtils.serializePasspointConfiguration(out, testConfig);
        out.flush();

        final XmlPullParser in = Xml.newPullParser();
        final ByteArrayInputStream inputStream =
                new ByteArrayInputStream(outputStream.toByteArray());
        in.setInput(inputStream, StandardCharsets.UTF_8.name());

        PasspointConfiguration deserializedConfig =
                PasspointXmlUtils.deserializePasspointConfiguration(in, in.getDepth());
        assertEquals(testConfig, deserializedConfig);
    }

    /**
     * Verify that the serialization and deserialization logic for a full
     * {@link PasspointConfiguration} (all fields are set) works as expected.
     *
     * @throws Exception
     */
    @Test
    public void serializeAndDeserializeFullPasspointConfiguration() throws Exception {
        serializeAndDeserializePasspointConfiguration(createFullPasspointConfiguration());
    }

    /**
     * Verify that the serialization and deserialization logic for an empty
     * {@link PasspointConfiguration} works as expected.
     *
     * @throws Exception
     */
    @Test
    public void serializeAndDeserializeEmptyPasspointConfiguration() throws Exception {
        serializeAndDeserializePasspointConfiguration(new PasspointConfiguration());
    }

    /**
     * Verify that a XmlPullParserException will be not thrown when deserialize a XML block
     * for a PasspointConfiguraiton containing an unknown tag.
     *
     * @throws Exception
     */
    @Test
    public void deserializePasspointConfigurationWithUnknownTag() throws Exception {
        String xmlStr = "<boolean name=\"AutoJoinEnabled\" value=\"true\" />\n"
                + "<UnknownTag></UnknownTag>\n"
                + "<HomeSP>\n"
                + "<string name=\"FQDN\">www.xyz.com</string>\n"
                + "<string name=\"unknown\">xxx</string>\n"
                + "<string name=\"FriendlyName\">XYZ</string>\n"
                + "</HomeSP>\n";
        PasspointConfiguration expectedConfig = new PasspointConfiguration();
        expectedConfig.setAutojoinEnabled(true);
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("www.xyz.com");
        homeSp.setFriendlyName("XYZ");
        expectedConfig.setHomeSp(homeSp);

        final XmlPullParser in = Xml.newPullParser();
        final ByteArrayInputStream inputStream =
                new ByteArrayInputStream(xmlStr.getBytes(StandardCharsets.UTF_8));
        in.setInput(inputStream, StandardCharsets.UTF_8.name());
        assertEquals(expectedConfig,
                PasspointXmlUtils.deserializePasspointConfiguration(in, in.getDepth()));
    }
}
