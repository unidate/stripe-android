package com.stripe.android;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.stripe.android.testharness.TestEphemeralKeyProvider;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.net.HttpURLConnection;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Test class for {@link EphemeralKeyManager}.
 */
@RunWith(RobolectricTestRunner.class)
public class EphemeralKeyManagerTest {

    private static final String FIRST_SAMPLE_KEY_RAW = "{\n" +
            "  \"id\": \"ephkey_123\",\n" +
            "  \"object\": \"ephemeral_key\",\n" +
            "  \"secret\": \"ek_test_123\",\n" +
            "  \"created\": 1501199335,\n" +
            "  \"livemode\": false,\n" +
            "  \"expires\": 1501199335,\n" +
            "  \"associated_objects\": [{\n" +
            "            \"type\": \"customer\",\n" +
            "            \"id\": \"cus_AQsHpvKfKwJDrF\"\n" +
            "            }]\n" +
            "}";

    private static final long TEST_SECONDS_BUFFER = 10L;
    private static final long DEFAULT_EXPIRES = 1501199335L;

    @Mock private EphemeralKeyManager.KeyManagerListener<CustomerEphemeralKey> mKeyManagerListener;
    @Captor private ArgumentCaptor<Map<String, Object>> mArgumentCaptor;

    private TestEphemeralKeyProvider mTestEphemeralKeyProvider;

    @Nullable
    private CustomerEphemeralKey getCustomerEphemeralKey(@NonNull String key) {
        try {
            return CustomerEphemeralKey.fromString(key);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }
    
    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTestEphemeralKeyProvider = new TestEphemeralKeyProvider();
    }

    @Test
    public void shouldRefreshKey_whenKeyIsNullAndTimeIsInFuture_returnsTrue() {
        Calendar futureCalendar = Calendar.getInstance();
        futureCalendar.add(Calendar.YEAR, 1);
        // If you don't call getTime or getTimeInMillis on a Calendar, none of the updates happen.
        futureCalendar.getTimeInMillis();
        assertTrue(EphemeralKeyManager.shouldRefreshKey(null,
                TEST_SECONDS_BUFFER,
                futureCalendar));
    }

    @Test
    public void shouldRefreshKey_whenKeyIsNullAndTimeIsInPast_returnsTrue() {
        Calendar pastCalendar = Calendar.getInstance();
        pastCalendar.add(Calendar.YEAR, -1);
        // If you don't call getTime or getTimeInMillis on a Calendar, none of the updates happen.
        pastCalendar.getTimeInMillis();
        assertTrue(EphemeralKeyManager.shouldRefreshKey(null,
                TEST_SECONDS_BUFFER,
                pastCalendar));
    }

    @Test
    public void shouldRefreshKey_whenKeyExpiryIsAfterBufferFromPresent_returnsFalse() {
        final Calendar fixedCalendar = Calendar.getInstance();
        final long expires = TimeUnit.SECONDS.toMillis(DEFAULT_EXPIRES + 2 * TEST_SECONDS_BUFFER);
        final CustomerEphemeralKey key = createEphemeralKey(expires);
        fixedCalendar.setTimeInMillis(expires);

        // If you don't call getTime or getTimeInMillis on a Calendar, none of the updates happen.
        assertEquals(expires, fixedCalendar.getTimeInMillis());
        assertFalse(EphemeralKeyManager.shouldRefreshKey(key,
                TEST_SECONDS_BUFFER,
                fixedCalendar));
    }

    @Test
    public void shouldRefreshKey_whenKeyExpiryIsInThePast_returnsTrue() {
        final Calendar fixedCalendar = Calendar.getInstance();
        final long timeAgoInMillis = fixedCalendar.getTimeInMillis() - 100L;
        final CustomerEphemeralKey key = createEphemeralKey(
                TimeUnit.MILLISECONDS.toSeconds(timeAgoInMillis));
        assertTrue(EphemeralKeyManager.shouldRefreshKey(key,
                TEST_SECONDS_BUFFER,
                fixedCalendar));
    }

    @Test
    public void shouldRefreshKey_whenKeyExpiryIsInFutureButWithinBuffer_returnsTrue() {
        Calendar fixedCalendar = Calendar.getInstance();
        CustomerEphemeralKey key = getCustomerEphemeralKey(FIRST_SAMPLE_KEY_RAW);
        assertNotNull(key);

        long parsedExpiryTimeInMillis = TimeUnit.SECONDS.toMillis(key.getExpires());
        long bufferTimeInMillis = TimeUnit.SECONDS.toMillis(TEST_SECONDS_BUFFER);

        long notFarEnoughInTheFuture = parsedExpiryTimeInMillis + bufferTimeInMillis / 2;
        fixedCalendar.setTimeInMillis(notFarEnoughInTheFuture);
        assertEquals(notFarEnoughInTheFuture, fixedCalendar.getTimeInMillis());

        assertTrue(EphemeralKeyManager.shouldRefreshKey(key,
                TEST_SECONDS_BUFFER,
                fixedCalendar));
    }

    @Test
    public void createKeyManager_updatesEphemeralKey_notifiesListener() {
        CustomerEphemeralKey testKey = getCustomerEphemeralKey(FIRST_SAMPLE_KEY_RAW);
        assertNotNull(testKey);

        mTestEphemeralKeyProvider.setNextRawEphemeralKey(FIRST_SAMPLE_KEY_RAW);
        EphemeralKeyManager<CustomerEphemeralKey> keyManager = new EphemeralKeyManager<>(
                mTestEphemeralKeyProvider,
                mKeyManagerListener,
                TEST_SECONDS_BUFFER,
                null,
                CustomerEphemeralKey.class);

        verify(mKeyManagerListener).onKeyUpdate(
                ArgumentMatchers.<CustomerEphemeralKey>any(),
                ArgumentMatchers.<String>isNull(),
                ArgumentMatchers.<Map<String, Object>>isNull());
        assertNotNull(keyManager.getEphemeralKey());
        assertEquals(testKey.getId(), keyManager.getEphemeralKey().getId());
    }

    @Test
    public void retrieveEphemeralKey_whenUpdateNecessary_returnsUpdateAndArguments() {
        final Calendar fixedCalendar = Calendar.getInstance();
        mTestEphemeralKeyProvider.setNextRawEphemeralKey(FIRST_SAMPLE_KEY_RAW);

        EphemeralKeyManager<CustomerEphemeralKey> keyManager = new EphemeralKeyManager<>(
                mTestEphemeralKeyProvider,
                mKeyManagerListener,
                TEST_SECONDS_BUFFER,
                fixedCalendar,
                CustomerEphemeralKey.class);

        // We already tested this setup, so let's reset the mock to avoid confusion.
        reset(mKeyManagerListener);

        final String ACTION_STRING = "action";
        final Map<String, Object> ACTION_ARGS = new HashMap<>();
        ACTION_ARGS.put("key", "value");
        keyManager.retrieveEphemeralKey(ACTION_STRING, ACTION_ARGS);

        ArgumentCaptor<CustomerEphemeralKey> keyArgumentCaptor =
                ArgumentCaptor.forClass(CustomerEphemeralKey.class);
        ArgumentCaptor<String> stringArgumentCaptor =
                ArgumentCaptor.forClass(String.class);

        verify(mKeyManagerListener).onKeyUpdate(
                keyArgumentCaptor.capture(),
                stringArgumentCaptor.capture(),
                mArgumentCaptor.capture());

        final Map<String, Object> capturedMap = mArgumentCaptor.getValue();
        assertNotNull(capturedMap);
        assertNotNull(keyArgumentCaptor.getValue());
        assertEquals(1, capturedMap.size());
        assertEquals("value", capturedMap.get("key"));
        assertEquals(ACTION_STRING, stringArgumentCaptor.getValue());
    }

    @Test
    public void updateKeyIfNecessary_whenReturnsError_setsExistingKeyToNull() {
        CustomerEphemeralKey testKey = getCustomerEphemeralKey(FIRST_SAMPLE_KEY_RAW);
        assertNotNull(testKey);

        Calendar proxyCalendar = Calendar.getInstance();
        long expiryTimeInMillis = TimeUnit.SECONDS.toMillis(testKey.getExpires());
        // The time is one millisecond past the expiration date for this test.
        proxyCalendar.setTimeInMillis(expiryTimeInMillis + 1L);
        // Testing this just to invoke getTime
        assertEquals(expiryTimeInMillis + 1L, proxyCalendar.getTimeInMillis());

        mTestEphemeralKeyProvider.setNextRawEphemeralKey(FIRST_SAMPLE_KEY_RAW);
        EphemeralKeyManager<CustomerEphemeralKey> keyManager = new EphemeralKeyManager<>(
                mTestEphemeralKeyProvider,
                mKeyManagerListener,
                TEST_SECONDS_BUFFER,
                proxyCalendar,
                CustomerEphemeralKey.class);

        // Make sure we're in a good state
        verify(mKeyManagerListener).onKeyUpdate(
                ArgumentMatchers.<CustomerEphemeralKey>any(),
                ArgumentMatchers.<String>isNull(),
                ArgumentMatchers.<Map<String, Object>>isNull());
        assertNotNull(keyManager.getEphemeralKey());

        // Set up the error
        final String errorMessage = "This is an error";
        mTestEphemeralKeyProvider.setNextError(404, errorMessage);

        // It should be necessary to update because the key is expired.
        keyManager.retrieveEphemeralKey(null, null);

        verify(mKeyManagerListener).onKeyError(404, errorMessage);
        verifyNoMoreInteractions(mKeyManagerListener);
        assertNull(keyManager.getEphemeralKey());
    }

    @Test
    public void triggerCorrectErrorOnInvalidRawKey() {

        mTestEphemeralKeyProvider.setNextRawEphemeralKey("Not_a_JSON");
        EphemeralKeyManager<CustomerEphemeralKey> keyManager = new EphemeralKeyManager<>(
                mTestEphemeralKeyProvider,
                mKeyManagerListener,
                TEST_SECONDS_BUFFER,
                null,
                CustomerEphemeralKey.class);

        verify(mKeyManagerListener, never()).onKeyUpdate(
                ArgumentMatchers.<CustomerEphemeralKey>isNull(),
                ArgumentMatchers.<String>isNull(),
                ArgumentMatchers.<Map<String, Object>>isNull());
        verify(mKeyManagerListener).onKeyError(
                HttpURLConnection.HTTP_INTERNAL_ERROR,
                "EphemeralKeyUpdateListener.onKeyUpdate was passed a value that " +
                        "could not be JSON parsed: [Value Not_a_JSON of type java.lang.String " +
                        "cannot be converted to JSONObject]. The raw body from Stripe's " +
                        "response should be passed");
        assertNull(keyManager.getEphemeralKey());
    }

    @Test
    public void triggerCorrectErrorOnInvalidJsonKey() {
        mTestEphemeralKeyProvider.setNextRawEphemeralKey("{}");
        EphemeralKeyManager<CustomerEphemeralKey> keyManager = new EphemeralKeyManager<>(
                mTestEphemeralKeyProvider,
                mKeyManagerListener,
                TEST_SECONDS_BUFFER,
                null,
                CustomerEphemeralKey.class);

        verify(mKeyManagerListener, never()).onKeyUpdate(
                ArgumentMatchers.<CustomerEphemeralKey>isNull(),
                ArgumentMatchers.<String>isNull(),
                ArgumentMatchers.<Map<String, Object>>isNull());
        verify(mKeyManagerListener).onKeyError(
                HttpURLConnection.HTTP_INTERNAL_ERROR,
                "EphemeralKeyUpdateListener.onKeyUpdate was passed a JSON String " +
                        "that was invalid: [Improperly formatted JSON for ephemeral " +
                        "key CustomerEphemeralKey - No value for created]. The raw body " +
                        "from Stripe's response should be passed");
        assertNull(keyManager.getEphemeralKey());
    }

    @Test
    public void triggerCorrectErrorOnNullKey() {
        mTestEphemeralKeyProvider.setNextRawEphemeralKey(null);
        EphemeralKeyManager<CustomerEphemeralKey> keyManager = new EphemeralKeyManager<>(
                mTestEphemeralKeyProvider,
                mKeyManagerListener,
                TEST_SECONDS_BUFFER,
                null,
                CustomerEphemeralKey.class);

        verify(mKeyManagerListener, never()).onKeyUpdate(
                ArgumentMatchers.<CustomerEphemeralKey>isNull(),
                ArgumentMatchers.<String>isNull(),
                ArgumentMatchers.<Map<String, Object>>isNull());
        verify(mKeyManagerListener).onKeyError(
                HttpURLConnection.HTTP_INTERNAL_ERROR,
                "EphemeralKeyUpdateListener.onKeyUpdate was called with a null value");
        assertNull(keyManager.getEphemeralKey());
    }

    @NonNull
    private CustomerEphemeralKey createEphemeralKey(long expires) {
        return new CustomerEphemeralKey(1501199335L, "cus_AQsHpvKfKwJDrF",
                expires, "ephkey_123", false, "customer", "", "");
    }
}
