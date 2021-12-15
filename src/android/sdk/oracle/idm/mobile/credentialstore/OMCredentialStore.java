/*
 * Copyright (c) 2017, Oracle and/or its affiliates.
 * The Universal Permissive License (UPL), Version 1.0
 */


package oracle.idm.mobile.credentialstore;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import java.io.Serializable;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import oracle.idm.mobile.OMErrorCode;
import oracle.idm.mobile.auth.local.OMAuthenticationManager;
import oracle.idm.mobile.auth.local.OMAuthenticationManagerException;
import oracle.idm.mobile.auth.local.OMAuthenticator;
import oracle.idm.mobile.auth.local.OMDefaultAuthenticator;
import oracle.idm.mobile.crypto.OMKeyManagerException;
import oracle.idm.mobile.crypto.OMSecureStorageException;
import oracle.idm.mobile.crypto.OMSecureStorageService;
import oracle.idm.mobile.logging.OMLog;
import oracle.idm.mobile.util.DefaultAuthenticationUtils;

import static oracle.idm.mobile.OMMobileSecurityService.OM_PROP_APPNAME;
import static oracle.idm.mobile.OMMobileSecurityService.OM_PROP_AUTH_KEY;
import static oracle.idm.mobile.OMMobileSecurityService.OM_PROP_LOGIN_URL;
import static oracle.idm.mobile.OMMobileSecurityService.OM_PROP_OAUTH_TOKEN_ENDPOINT;
import static oracle.idm.mobile.OMSecurityConstants.OM_CREDENTIAL;

/**
 * OMCredentialStore is storage class which stores all the data that needs
 * persistent storage in a secure manner. All the data will be stored after
 * encryption. When it is read back, it will be decrypted.
 *
 *
 */
public class OMCredentialStore
{
    public static final String DEFAULT_AUTHENTICATOR_NAME = "idm_mobile_sdk_default_authenticator";

    private static final String TAG = OMCredentialStore.class.getSimpleName();

    /**
     * This is appended with the key passed to store/retrieve/delete the authentication context.
     */
    private static final String AUTH_CONTEXT_SUFFIX = "_AuthContext";

    /**
     * This is appended with the key passed to store/retrieve/delete the configuration passed via URI to the SDK.
     */
    static final String CONFIG_URI_SUFFIX = "_ConfigURI";

    /**
     * This is appended with the key passed to store/retrieve/delete the retry count in offline authentication.
     */
    private static final String RETRY_COUNT_SUFFIX = "_retryCount";

    /**
     * The set of dataIds are maintained which are created using this class, and
     * are currently present in Secure Storage. If deletion is done, the corresponding
     * dataId is removed from this set.
     */
    private static final String SECURE_STORAGE_DATA_IDS = "SecureStorageDataIds";

    private Context context;
    private String mAuthenticatorName;
    private String mAuthenticatorInstanceId;
    private OMAuthenticator mAuthenticator;
    private OMSecureStorageService mSecureStorageService;

    /**
     * With this constructor, a default authenticator is used to form the encryption key and encrypt/decrypt the credentials.
     * If the app uses local authentication e.g PIN, and if the following configuration properties were given to SDK
     * during initialization, OM_PROP_LOCAL_AUTHENTICATOR_NAME, OM_PROP_LOCAL_AUTHENTICATOR_INSTANCE_ID, then
     * {@link OMCredentialStore#OMCredentialStore(Context, String, String)} should be used instead of this
     * constructor.
     *
     * @param context Application context
     */
    public OMCredentialStore(Context context) {
        this(context, null, null);
    }

    /**
     * If authenticatorName and authenticatorInstanceId are not passed, a default authenticator
     * is used to form the encryption key and encrypt/decrypt the credentials. If they are passed, the encryption
     * key will be obtained from the authenticator specified.
     *
     * @param context Application context
     * @param authenticatorName the same authenticatorName which was passed to SDK using OM_PROP_LOCAL_AUTHENTICATOR_NAME
     * @param authenticatorInstanceId the same authenticatorInstanceId which was passed to SDK using OM_PROP_LOCAL_AUTHENTICATOR_INSTANCE_ID
     */
    public OMCredentialStore(Context context, String authenticatorName, String authenticatorInstanceId)
    {
        this.context = context;
        this.mAuthenticatorName = authenticatorName;
        this.mAuthenticatorInstanceId = authenticatorInstanceId;
    }

    /**
     * Stores the given key-value pair in the shared preference file name that
     * is supplied as input.
     *
     * @param key
     *            key to be used
     * @param value
     *            data to be stored against the given key
     */
    public void putString(String key, String value)
    {
        SharedPreferences preference = getPreference();
        SharedPreferences.Editor editor = preference.edit();
        editor.putString(key, value);
        editor.commit();
    }

    /**
     * Stores the given long value against the given key in the shared
     * preference.
     *
     * @param key
     *            key to be used
     * @param value
     *            data to be stored against the given key
     */
    public void putLong(String key, long value)
    {
        SharedPreferences preference = getPreference();
        SharedPreferences.Editor editor = preference.edit();
        editor.putLong(key, value);
        editor.commit();
    }

    /**
     * Stores the given int value against the given key in the shared
     * preference.
     *
     * @param key
     *            key to be used
     * @param value
     *            data to be stored against the given key
     */
    public void putInt(String key, int value)
    {
        SharedPreferences preference = getPreference();
        SharedPreferences.Editor editor = preference.edit();
        editor.putInt(key, value);
        editor.commit();
    }

    /**
     * Stores the given map of string values mapped against the string keys into
     * the shared preference store.
     *
     * @param values
     *            list of key-value pairs
     */
    public void putStrings(Map<String, String> values)
    {
        SharedPreferences preference = getPreference();
        SharedPreferences.Editor editor = preference.edit();
        for (Map.Entry<String, String> entry : values.entrySet())
        {
            editor.putString(entry.getKey(), entry.getValue());
        }
        editor.commit();
    }

    /**
     * Fetches the value that is stored against the given key from the shared
     * preference.
     *
     * @param key
     *            key for which value needs to be fetched
     * @return value for the key
     */
    public String getString(String key)
    {
        String value = null;
        SharedPreferences preferences = getPreference();
        if (!TextUtils.isEmpty(key))
        {
            value = preferences.getString(key, null);
        }
        return value;
    }

    /**
     * Fetches the long value that is stored against the given key from the
     * shared preference.Returns 0 as default.
     *
     * @param key
     *            key for which value needs to be fetched
     * @return value for the key
     */
    public long getLong(String key)
    {
        long value = 0;// default value is set as 0
        SharedPreferences preferences = getPreference();
        if (!TextUtils.isEmpty(key))
        {
            value = preferences.getLong(key, 0);
        }
        return value;
    }

    /**
     * Fetches the int value that is stored against the given key from the
     * shared preference.Returns 0 as default.
     *
     * @param key
     *            key for which value needs to be fetched
     * @return value for the key
     */
    public int getInt(String key)
    {
        int value = 0;// default value is set as 0
        SharedPreferences preferences = getPreference();
        if (!TextUtils.isEmpty(key))
        {
            value = preferences.getInt(key, 0);
        }
        return value;
    }

    /**
     * Fetches all the values that are stored in the shared preference as a map
     * of string keys with values.
     *
     * @return {@link Map} instance
     */
    public Map<String, ?> getAll()
    {
        SharedPreferences preferences = getPreference();

        return preferences.getAll();
    }

    /**
     * Removes all the key-value pairs stored in the shared preference.
     */
    public void removeAll()
    {
        SharedPreferences preferences = getPreference();
        SharedPreferences.Editor editor = preferences.edit();
        editor.clear();
        editor.commit();
    }

    /**
     * This is to delete a value stored against a given key from the shared
     * preference. In order to delete credential,
     * {@link OMCredentialStore#deleteCredential(String)} should be used instead
     * of this method.
     *
     * @param keyName
     *            name of the key to be removed
     */
    public void remove(String keyName)
    {
        SharedPreferences preferences = getPreference();
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove(keyName);
        editor.commit();
    }

    /**
     * Gets an {@link OMCredential} instance if the given key contains a valid
     * value stored in the preferences store.
     *
     * @param key
     *            key to be used for searching in the credential store.
     * @return an instance of {@link OMCredential}
     */
    public OMCredential getCredential(String key)
    {
        OMCredential credential = null;
        String credentialStr = null;
        OMSecureStorageService sss = getSecureStorageService();
        if (!TextUtils.isEmpty(key) && sss != null)
        {
            String realKey = key + OM_CREDENTIAL;
            Serializable data = null;
            try {
                data = sss.get(realKey);
            } catch (OMSecureStorageException e) {
                OMLog.error(TAG, e.getMessage(), e);
                return null;
            }
            if (data instanceof String) {
                /* TODO Since OMCredential is now being serialized and stored, this instanceof check
                * can be removed. Keeping this now probably for 1 or 2 years to maintain backward compatibility.
                *
                * JIRA task: OCIS-122693 ANDROID: SDK: Fortify Issue : Do not store sensitive data in immutable objects
                * */
                credentialStr = (String) data;
            } else {
                credential = (OMCredential) data;
            }
            if (credentialStr != null)
            {
                // try to see whether it is a auth context
                credential = new OMCredential(credentialStr);
                /* The String representation of Credential object was stored in secure storage which
                 * has issues as mentioned in javadoc of constructor OMCredential(String). So, deleting
                 * this entry from secure storage and storing it again in serialized format.*/
                deleteCredential(key);
                addCredential(key, credential);
            }

        }
        return credential;
    }

    /**
     * Deletes the credential information that is stored against the given key
     * in the credential store.
     *
     * @param key
     *            key to be used for deletion.
     */
    public void deleteCredential(String key)
    {
        if (key != null)
        {
            key = key + OM_CREDENTIAL;
            deleteFromSecureStorage(key);
        }
    }

    /**
     * Deletes offline credentials persisted during authentication.
     * @param configProperties should contain SDK configuration properties.
     * @return number of credentials deleted.
     */
    public int deleteCredentialForProperties(Map<String, Object> configProperties) {
        if (configProperties == null || configProperties.isEmpty()) {
            throw new IllegalArgumentException("configProperties cannot be null or empty");
        }

        String authenticationUrl = getAuthenticationUrl(configProperties);
        String offlineCredDataIdSuffix = getOfflineCredDataIdSuffix(configProperties);

        Set<String> dataIdsToDelete = new HashSet<>();
        Set<String> dataIds = getPreference().getStringSet(SECURE_STORAGE_DATA_IDS, null);
        if (dataIds == null) {
            return 0;
        }
        for (String dataId : dataIds) {
            if (dataId.startsWith(authenticationUrl) && dataId.endsWith(offlineCredDataIdSuffix)) {
                dataIdsToDelete.add(dataId);
            }
        }

        return deleteFromSecureStorage(dataIdsToDelete);
    }

    private String getAuthenticationUrl(Map<String, Object> configProperties) {
        Object loginUrlObj = configProperties.get(OM_PROP_LOGIN_URL);
        String authenticationUrl = null;
        if (loginUrlObj instanceof URL) {
            authenticationUrl = loginUrlObj.toString();
        } else if (loginUrlObj instanceof String) {
            authenticationUrl = (String) loginUrlObj;
        }

        Object tokenEndpointObj = configProperties.get(OM_PROP_OAUTH_TOKEN_ENDPOINT);
        if (tokenEndpointObj instanceof URL) {
            authenticationUrl = tokenEndpointObj.toString();
        } else if (tokenEndpointObj instanceof String) {
            authenticationUrl = (String) tokenEndpointObj;
        }

        if (authenticationUrl == null) {
            throw new IllegalArgumentException("Invalid value in configProperties: " +
                    "either OM_PROP_LOGIN_URL or OM_PROP_OAUTH_TOKEN_ENDPOINT is required");
        }

        return authenticationUrl;
    }

    private String getOfflineCredDataIdSuffix(Map<String, Object> configProperties) {
        String offlineCredDataIdSuffix = null;
        Object authKeyObj = configProperties.get(OM_PROP_AUTH_KEY);
        String authKey = null;
        if (authKeyObj instanceof String) {
            authKey = (String) authKeyObj;
        }
        if (!TextUtils.isEmpty(authKey)) {
            offlineCredDataIdSuffix = authKey;
        } else {
            Object appNameObj = configProperties.get(OM_PROP_APPNAME);
            String appName = null;
            if (appNameObj instanceof String) {
                appName = (String) appNameObj;
            }
            if (!TextUtils.isEmpty(appName)) {
                offlineCredDataIdSuffix = appName;
            }
        }

        if (offlineCredDataIdSuffix == null) {
            throw new IllegalArgumentException("OM_PROP_APPNAME is mandatory.");
        }

        offlineCredDataIdSuffix = offlineCredDataIdSuffix + OM_CREDENTIAL;
        return offlineCredDataIdSuffix;
    }

    /**
     * Updates the value that is stored against the given key with the new set
     * of information passed in this method.
     *
     * @param credential
     *            an instance of {@link OMCredential} containing the new values
     */
    public void updateCredential(String key, OMCredential credential)
    {
        addCredential(key, credential.getUserName(),
                credential.getRawUserPasswordAsCharArray(),
                credential.getIdentityDomain(), credential.getProperties());
    }

    /**
     * Updates the value that is stored against the given propertyName under the
     * given credential key. If the given property name doesn't match any of the
     * constants { "username" , "password", "tenantname" }, then it will be
     * considered as a user-defined custom property.
     *
     * @param key
     *            credential key
     * @param propertyName
     *            name of the property that needs to be updated. This can any of
     *            the constant values such as "username" , "password",
     *            "tenantname" or any of the user defined property name
     * @param propertyValue
     *            new value for the property
     *
     * @deprecated This accepts password as String which leads to security issues.
     * Instead use {@link #updateCredential(String, OMCredential)} where you can
     * update password using setter ({@link OMCredential#setUserPassword(char[])}).
     */
    @Deprecated
    public void updateCredential(String key, String propertyName,
            String propertyValue)
    {
        if (key != null)
        {

            OMCredential credential = getCredential(key);
            key = key + OM_CREDENTIAL;
            if (credential != null) {
                credential.updateValue(propertyName, propertyValue);
                storeInSecureStorage(key, credential);
                credential.invalidateUserPassword();
            }
        }
    }

    /**
     * Adds the new value into the credential store against the given key.
     *
     * @param key
     *            key to be used for searching in the credential store.
     * @param userName
     *            user name
     * @param password
     *            password
     * @param tenantName
     *            tenant name
     * @param properties
     *            set of properties
     *
     * @deprecated This accepts password as String as opposed to char[], which
     * is a security concern. Instead use {@link #addCredential(String, String, char[], String, Map)}.
     */
    @Deprecated
    public void addCredential(String key, String userName, String password,
            String tenantName, Map<String, String> properties)
    {
        addCredential(key, userName, password.toCharArray(), tenantName, properties);
    }

    /**
     * Adds the new value into the credential store against the given key.
     *
     * @param key
     *            key to be used for searching in the credential store.
     * @param userName
     *            user name
     * @param password
     *            password
     * @param tenantName
     *            tenant name
     * @param properties
     *            set of properties
     *
     */
    public void addCredential(String key, String userName, char[] password,
                              String tenantName, Map<String, String> properties)
    {
        if (key != null)
        {
            key = key + OM_CREDENTIAL;

            OMCredential credential = new OMCredential(userName, password,
                    tenantName, properties);

            storeInSecureStorage(key, credential);
        }
    }

    /**
     * Adds the {@link OMCredential} into the credential store.
     *
     * @param key
     *            key to be used for searching in the credenital store.
     * @param credential
     *            an instance of {@link OMCredential}
     */
    public void addCredential(String key, OMCredential credential)
    {
        if (key != null && credential != null)
        {
            key = key + OM_CREDENTIAL;
            storeInSecureStorage(key, credential);
        }
    }

    /**
     * Internal API to store the authentication context to the persistent
     * storage. This will avoid name space collisions with the app/auth key used
     * by the app during initialization or call to authenticate. This will avoid
     * the users of the SDK to directly get the authentication context from the
     * the {@link OMCredentialStore}.
     *
     * @param key
     * @param value
     */
    public void addAuthContext(String key, String value)
    {
        if (!TextUtils.isEmpty(key))
        {
            key = key + AUTH_CONTEXT_SUFFIX;
            storeInSecureStorage(key, value);
        }
    }

    /**
     * Internal API to retrieve authentication context from the persistent
     * storage. This resolves the name space collision and returns the
     * authentication context based on the the app/auth key used by the app.
     *
     * @param key
     * @return
     */
    public String getAuthContext(String key)
    {
        OMSecureStorageService sss = getSecureStorageService();
        String authContext = null;
        if (sss != null && !TextUtils.isEmpty(key))
        {
            key = key + AUTH_CONTEXT_SUFFIX;
            try {
                Serializable data = sss.get(key);
                if (data instanceof String) {
                    authContext = (String) data;
                }
            } catch (OMSecureStorageException e) {
                OMLog.error(TAG, e.getErrorDescription(), e);
            }
        }
        return authContext;
    }

    /**
     * Deletes the authentication context by removing the name space collision
     * with the app/auth key passed.
     *
     * @param key
     */
    public void deleteAuthContext(String key)
    {
        if (!TextUtils.isEmpty(key))
        {
            key = key + AUTH_CONTEXT_SUFFIX;
            deleteFromSecureStorage(key);
        }
    }

    /**
     * Internal API to store the configuration passed via URI to the SDK. This
     * removes name space collisions with the actual key passed to store.
     *
     * @param key
     * @param value
     * @hide
     */
    public void addConfigurationURI(String key, String value)
    {
        if (!TextUtils.isEmpty(key))
        {
            key = key + CONFIG_URI_SUFFIX;
            putString(key, value);
        }
    }

    /**
     * Internal API to get the configuration passed via URI from the Store if
     * stored any. This removes the name space collision with the actual key
     * passed to store the same.However, the SDK user can use
     * {@link oracle.idm.mobile.configuration.OMMobileSecurityConfiguration#getInitializationConfiguration(Context, String)}
     * to retrieve the stored configuration against the provided or default key.
     *
     * @param key
     * @return
     * @hide
     */
    public String getConfigurationURI(String key)
    {
        if (!TextUtils.isEmpty(key))
        {
            key = key + CONFIG_URI_SUFFIX;
            return getString(key);
        }
        return null;
    }

    /**
     * Removes the stored configuration for a particular key by avoiding the name
     * space collision. Internally used by
     * {@link oracle.idm.mobile.configuration.OMMobileSecurityConfiguration#getInitializationConfiguration(Context, String)}
     *
     * @param key
     * @hide
     */
    public void deleteConfigurationURI(String key)
    {
        if (!TextUtils.isEmpty(key))
        {
            key = key + CONFIG_URI_SUFFIX;
            remove(key);
        }
    }

    /**
     * Gets the shared preference instance.
     *
     * @return {@link SharedPreferences}
     */
    private SharedPreferences getPreference() {
        return PreferenceManager
                .getDefaultSharedPreferences(context);
    }

    public void addRetryCount(String key, int retryCount) {
        key = key + RETRY_COUNT_SUFFIX;
        putInt(key, retryCount);
    }

    public int getRetryCount(String key) {
        if (!TextUtils.isEmpty(key)) {
            key = key + RETRY_COUNT_SUFFIX;
            return getInt(key);
        }
        return -1;
    }

    public void deleteRetryCount(String key) {
        key = key + RETRY_COUNT_SUFFIX;
        remove(key);
    }

    private OMAuthenticator getAuthenticator() throws OMAuthenticationManagerException {
        if (mAuthenticator == null) {
            try {
                OMLog.trace(TAG, "Inside initializeAuthenticator");
                OMAuthenticationManager authenticationManager = OMAuthenticationManager.getInstance(context);

                if (TextUtils.isEmpty(mAuthenticatorName)) {
                    // No authenticator name is provided. Hence, we should use default authenticator
                    mAuthenticator = DefaultAuthenticationUtils.getDefaultAuthenticator(context);
                } else {
                    mAuthenticator = authenticationManager.getAuthenticator(mAuthenticatorName, mAuthenticatorInstanceId);
                }
            } catch (OMAuthenticationManagerException e) {
                OMLog.error(TAG, e.getMessage(), e);
                throw e;
            }

        }
        return mAuthenticator;
    }

    private OMSecureStorageService getSecureStorageService() {
        if (mSecureStorageService == null) {
            try {
                if (mAuthenticator == null) {
                    mAuthenticator = getAuthenticator();
                }
                if (mAuthenticator instanceof OMDefaultAuthenticator) {
                    DefaultAuthenticationUtils.initializeDefaultAuthenticator(context, (OMDefaultAuthenticator)mAuthenticator);
                }
                if (!mAuthenticator.isAuthenticated()) {
                    OMLog.error(TAG, "Local authentication is NOT done");
                    throw new OMAuthenticationManagerException(OMErrorCode.LOCAL_AUTHENTICATION_NOT_DONE);
                }
            } catch (OMAuthenticationManagerException e) {
                OMLog.error(TAG, e.getMessage(), e);
                return null;
            } catch (OMKeyManagerException e) {
                OMLog.error(TAG, e.getMessage(), e);
                return null;
            }

            mSecureStorageService = new OMSecureStorageService(context, mAuthenticator.getKeyStore(),
                    DEFAULT_AUTHENTICATOR_NAME);
        }
        return mSecureStorageService;
    }

    private void storeInSecureStorage(String dataId, Serializable data) {
        OMSecureStorageService sss = getSecureStorageService();
        if (sss != null) {
            try {
                sss.store(dataId, data);
                updateSecureStorageDataIds(dataId);
            } catch (OMSecureStorageException e) {
                // Unrecoverable exceptions. Hence, not propagating.
                OMLog.error(TAG, e.getMessage(), e);
            }
        }
    }

    private boolean deleteFromSecureStorage(String dataId) {
        boolean deleteStatus = false;
        OMSecureStorageService sss = getSecureStorageService();
        if (sss != null) {
            deleteStatus = sss.delete(dataId);
            deleteSecureStorageDataId(dataId);
        }
        return deleteStatus;
    }

    private int deleteFromSecureStorage(Set<String> dataIds) {
        OMSecureStorageService sss = getSecureStorageService();
        int deleteCount = 0;
        if (sss != null && dataIds != null) {
            for (String dataId : dataIds) {
                if (sss.delete(dataId)) {
                    deleteCount++;
                }
            }
            deleteSecureStorageDataIds(dataIds);
        }
        return deleteCount;
    }

    /**
     * Refer {@link #SECURE_STORAGE_DATA_IDS}
     */
    private void updateSecureStorageDataIds(String dataId) {
        Set<String> secureStorageDataIds = getPreference().getStringSet(SECURE_STORAGE_DATA_IDS, null);
        if (secureStorageDataIds == null) {
            secureStorageDataIds = new HashSet<>();
        } else {
            secureStorageDataIds = new HashSet<>(secureStorageDataIds);
        }
        secureStorageDataIds.add(dataId);
        getPreference().edit().putStringSet(SECURE_STORAGE_DATA_IDS, secureStorageDataIds).apply();
    }

    /**
     * Refer {@link #SECURE_STORAGE_DATA_IDS}
     */
    private void deleteSecureStorageDataId(String dataId) {
        Set<String> secureStorageDataIds = getPreference().getStringSet(SECURE_STORAGE_DATA_IDS, null);
        if (secureStorageDataIds == null) {
            OMLog.error(TAG, "Tried to delete dataId when list of dataIds is null");
            return;
        } else {
            secureStorageDataIds = new HashSet<>(secureStorageDataIds);
        }
        secureStorageDataIds.remove(dataId);
        getPreference().edit().putStringSet(SECURE_STORAGE_DATA_IDS, secureStorageDataIds).apply();
    }

    /**
     * Refer {@link #SECURE_STORAGE_DATA_IDS}
     */
    private void deleteSecureStorageDataIds(Set<String> dataIds) {
        Set<String> secureStorageDataIds = getPreference().getStringSet(SECURE_STORAGE_DATA_IDS, null);
        if (secureStorageDataIds == null) {
            OMLog.error(TAG, "Tried to delete dataIds when list of dataIds is null");
            return;
        } else {
            secureStorageDataIds = new HashSet<>(secureStorageDataIds);
        }
        secureStorageDataIds.removeAll(dataIds);
        getPreference().edit().putStringSet(SECURE_STORAGE_DATA_IDS, secureStorageDataIds).apply();
    }

}
