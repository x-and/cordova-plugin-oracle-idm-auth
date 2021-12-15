/*
 * Copyright (c) 2017, Oracle and/or its affiliates.
 * The Universal Permissive License (UPL), Version 1.0
 */


package oracle.idm.mobile.auth;

import android.text.TextUtils;
import android.webkit.WebViewDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import oracle.idm.mobile.OMAuthenticationRequest;
import oracle.idm.mobile.OMMobileSecurityException;
import oracle.idm.mobile.OMMobileSecurityService;
import oracle.idm.mobile.OMSecurityConstants;
import oracle.idm.mobile.auth.openID.OpenIDToken;
import oracle.idm.mobile.auth.openID.OpenIDUserInfo;
import oracle.idm.mobile.callback.OMMobileSecurityServiceCallback;
import oracle.idm.mobile.configuration.OMFederatedMobileSecurityConfiguration;
import oracle.idm.mobile.configuration.OMMobileSecurityConfiguration;
import oracle.idm.mobile.connection.OMCookieManager;
import oracle.idm.mobile.credentialstore.OMCredential;
import oracle.idm.mobile.credentialstore.OMCredentialStore;
import oracle.idm.mobile.crypto.CryptoScheme;
import oracle.idm.mobile.logging.OMLog;
import oracle.idm.mobile.util.ArrayUtils;

import static oracle.idm.mobile.OMSecurityConstants.Challenge.IDENTITY_DOMAIN_KEY;
import static oracle.idm.mobile.OMSecurityConstants.Challenge.OFFLINE_CREDENTIAL_KEY;
import static oracle.idm.mobile.OMSecurityConstants.Challenge.PASSWORD_KEY_2;
import static oracle.idm.mobile.OMSecurityConstants.Challenge.USERNAME_KEY;
import static oracle.idm.mobile.OMSecurityConstants.DOMAIN;
import static oracle.idm.mobile.OMSecurityConstants.EXPIRES;
import static oracle.idm.mobile.OMSecurityConstants.EXPIRY_DATE;
import static oracle.idm.mobile.OMSecurityConstants.IDENTITY_DOMAIN;
import static oracle.idm.mobile.OMSecurityConstants.IS_HTTP_ONLY;
import static oracle.idm.mobile.OMSecurityConstants.IS_SECURE;
import static oracle.idm.mobile.OMSecurityConstants.OAUTH_ACCESS_TOKEN;
import static oracle.idm.mobile.OMSecurityConstants.OAUTH_TOKEN_SCOPE;
import static oracle.idm.mobile.OMSecurityConstants.PATH;
import static oracle.idm.mobile.OMSecurityConstants.Param.AUTHENTICATION_MECHANISM;
import static oracle.idm.mobile.OMSecurityConstants.Param.CLEAR_PASSWORD;
import static oracle.idm.mobile.OMSecurityConstants.TOKEN_NAME;
import static oracle.idm.mobile.OMSecurityConstants.TOKEN_VALUE;


/**
 * OMAuthenticationContext class is the resultant object which is constructed
 * after a successful authentication with the server. It contains tokens
 * obtained from the server, expiry time, idle time, mode of authentication, type of
 * authentication provider and the user name for which the tokens are obtained.
 */
public class OMAuthenticationContext {

    /**
     * @hide Note: AuthContext Status is kept separate for OpenID and OAuth to avoid code changes in lower layers.
     */
    enum Status {
        SUCCESS,
        FAILURE,
        CANCELED,
        IN_PROGRESS,
        INITIAL_VALIDATION_DONE,
        COLLECT_OFFLINE_CREDENTIALS,
        /**
         * Status used when the IDCS dynamic client registration is in progress for an OAuth client
         **/
        OAUTH_IDCS_CLIENT_REGISTRATION_IN_PROGRESS,
        /**
         * Status used when the IDCS dynamic client registration is done for an OAuth client
         **/
        OAUTH_IDCS_CLIENT_REGISTRATION_DONE,
        /**
         * Status used when the IDCS dynamic client registration is in progress for an OpenID client
         **/
        OPENID_IDCS_CLIENT_REGISTRATION_IN_PROGRESS,
        /**
         * Status used when the IDCS dynamic client registration is in progress for an OpenID client
         **/
        OPENID_IDCS_CLIENT_REGISTRATION_DONE,


        OAUTH_DYCR_DONE,
        OAUTH_PRE_AUTHZ_DONE,
        OAUTH_DYCR_IN_PROGRESS
    }

    public enum AuthenticationProvider {
        CBA,
        BASIC,
        OAUTH20,
        OFFLINE,
        FEDERATED,
        OPENIDCONNECT10
    }

    /**
     * Mentions the mechanism using which authentication was done.
     * {@link OMAuthenticationContext#getAuthenticationMechanism()} should be used
     * to determine the authentication mechanism.
     * <p>
     * Currently, it distinguishes authentication mechanism used in FedAuth. This is
     * required by SDK consumer to kill the app after logout in case of
     * Basic/NTLM./Kerberos. App kill is required because webview replays the
     * user credentials during next login even if SDK tries to clear it using
     * corres. android APIs.
     * Bug: https://code.google.com/p/android/issues/detail?id=22272
     */
    public enum AuthenticationMechanism {
        /**
         * This means that federated authentication was done using form based
         * authentication.
         */
        FEDERATED,
        /**
         * Http Auth challenge was received during fedreated authentication. This
         * includes Http Basic Auth, Kerberos, NTLM, etc.
         */
        FEDERATED_HTTP_AUTH
    }

    public enum AuthenticationMode {
        ONLINE,
        OFFLINE
    }

    public enum TimeoutType {
        IDLE_TIMEOUT,
        SESSION_TIMEOUT
    }

    // ===---=== OWSM-MA Start ===---===

    // constants
    public static final String CREDENTIALS = "credentials";
    public static final String USERNAME_PROPERTY = "javax.xml.ws.security.auth.username";
    /**
     * This is used as a key against which password is populated in a {@link Map}.
     * The password is present as {@link String}.
     *
     * @deprecated Keeping password as String in memory is a security concern. Instead of
     * this, use {@link #PASSWORD_PROPERTY_2}.
     */
    @Deprecated
    public static final String PASSWORD_PROPERTY = "javax.xml.ws.security.auth.password";
    /**
     * This is used as a key against which password is populated in a {@link Map}.
     * The password is present as char[].
     */
    public static final String PASSWORD_PROPERTY_2 = "javax.xml.ws.security.auth.password.char.array";
    public static final String ERROR = "Error";
    public static final String CREDENTIALS_UNAVAILABLE = "Credentials unavailable";
    public static final String COOKIES = "cookies";
    public static final String HEADERS = "headers";
    private static final String OWSM_MA_COOKIES = "owsmMACookies";

    private static final String TAG = OMAuthenticationContext.class.getSimpleName();

    // Begin: Keys against which value will be stored in the String representation of OMAuthenticationContext
    private static final String USERNAME = "username";
    private static final String AUTHEN_MODE = "authenticatedMode";
    private static final String AUTHEN_PROVIDER = "authenticationProvider";
    private static final String TOKENS = "tokens";
    private static final String OAUTH_TOKEN = "oauthTokenSet";
    private static final String SESSION_EXPIRY = "sessionExpiry";
    private static final String IDLETIME_EXPIRY = "idleTimeExpiry";
    private static final String SESSION_EXPIRY_SECS = "sessionExpInSecs";
    private static final String IDLETIME_EXPIRY_SECS = "idleTimeExpInSecs";
    private static final String LOGOUT_TIMEOUT_VALUE = "logoutTimeoutValue";
    // End: Keys against which value will be stored in the String representation of OMAuthenticationContext

    private Status mStatus;
    private String mStorageKey;
    private AuthenticationServiceManager mASM;
    private OMMobileSecurityException mException;
    private OMAuthenticationRequest mAuthRequest;
    private AuthenticationProvider authenticationProvider;
    private AuthenticationMode authenticatedMode = AuthenticationMode.ONLINE;
    private AuthenticationMechanism authenticationMechanism;
    private String offlineCredentialKey;
    private String identityDomain;
    private Map<String, Object> mInputParams;
    private boolean authContextDeleted;
    private String userName;
    // Field used internally for validation
    private Date sessionExpiry;
    private Date idleTimeExpiry;
    private int sessionExpInSecs;
    private int idleTimeExpInSecs;
    private TimeoutManager mTimeoutManager;
    private boolean isIdleTimeout = false;
    /**
     * Any auxiliary tokens generated during the auth process are added to
     * this OAuth token list, e.g: OpenIDToken (instanceof OAuthToken) is present.
     * OpenID token is also added to authContext.getTokens().
     */
    private List<OAuthToken> oAuthTokenList;
    private Map<String, OMToken> tokens;
    private Map<String, OMToken> owsmMACookies;

    private int logoutTimeout;
    private Set<URI> mVisitedUrls;
    private List<OMCookie> mCookies;
    private OpenIDUserInfo mOpenIDUserInfo;
    private boolean isForceAuthentication;

    /**
     * Just idle time out has happened; session time out has not happened.
     */
    private boolean idleTimeExpired;

    OMAuthenticationContext(AuthenticationServiceManager asm, OMAuthenticationRequest authRequest, String storageKey) {
        mASM = asm;
        mStorageKey = storageKey;
        mAuthRequest = authRequest;
    }

    OMAuthenticationContext(AuthenticationServiceManager asm, String authContextString, String storageKey) {
        mASM = asm;
        mStorageKey = storageKey;
        populateFields(authContextString);
    }

    OMAuthenticationContext(Status status) {
        mStatus = status;
    }

    /**
     * Hold inputs collected from the app/user.
     * Note this is something that is totally internal to the SDK, and will contain more detailed info/params.
     *
     * @return
     */
    Map<String, Object> getInputParams() {
        if (mInputParams == null) {
            mInputParams = new HashMap<>();
        }
        return mInputParams;
    }

    void setStatus(Status status) {
        mStatus = status;
    }

    void setException(OMMobileSecurityException e) {
        mException = e;
    }

    OMMobileSecurityException getMobileException() {
        return mException;
    }

    Status getStatus() {
        return mStatus;
    }

    OMAuthenticationRequest getAuthRequest() {
        return mAuthRequest;
    }

    /**
     * Returns the type of authentication provider.
     *
     * @return {@link AuthenticationProvider}
     */
    public AuthenticationProvider getAuthenticationProvider() {
        return authenticationProvider;
    }

    void setAuthenticationProvider(AuthenticationProvider authenticationProvider) {
        this.authenticationProvider = authenticationProvider;
    }

    public AuthenticationMode getAuthenticatedMode() {
        return authenticatedMode;
    }

    void setAuthenticatedMode(AuthenticationMode authenticatedMode) {
        this.authenticatedMode = authenticatedMode;
    }

    public AuthenticationMechanism getAuthenticationMechanism() {
        return authenticationMechanism;
    }

    void setForceAuthentication(
            boolean isForceAuthentication) {
        this.isForceAuthentication = isForceAuthentication;
    }

    public boolean getForceAuthentication() {
        return isForceAuthentication;
    }

    void setAuthenticationMechanism(
            AuthenticationMechanism authenticationMechanism) {
        this.authenticationMechanism = authenticationMechanism;
    }

    public void populateExpiryTime(OMMobileSecurityServiceCallback appCallback) {
        if (mASM != null && mASM.getMSS() != null) {//TODO CHECK identity domain NPE

            int sessionExp = mASM.getMSS().getMobileSecurityConfig().getSessionDuration();
            int expTime = (sessionExp > 0) ? sessionExp : 0;

            Calendar futureTime = Calendar.getInstance();
            futureTime.add(Calendar.SECOND, expTime);
            sessionExpiry = futureTime.getTime();
            sessionExpInSecs = expTime;

            int idleExp = mASM.getMSS().getMobileSecurityConfig().getIdleTime();
            if (idleExp > 0) {
                Calendar futureIdleTime = Calendar.getInstance();
                futureIdleTime.add(Calendar.SECOND, idleExp);
                idleTimeExpiry = futureIdleTime.getTime();
                idleTimeExpInSecs = idleExp;
            }
            mTimeoutManager = new TimeoutManager(mASM.getMSS().getAuthenticationContextCallback(), this);
            if (authenticationProvider == AuthenticationProvider.OAUTH20 && authenticatedMode == AuthenticationMode.OFFLINE && idleExp > 0) {
                mTimeoutManager.startIdleTimeoutAdvanceNotificationTimer();
            } else if (authenticationProvider != AuthenticationProvider.OAUTH20) {
                if (idleExp > 0) {
                    mTimeoutManager.startIdleTimeoutAdvanceNotificationTimer();
                }
                if (expTime > 0) {
                    mTimeoutManager.startSessionTimeoutTimer();
                }
            }
        }
    }

    public boolean resetTimer() {
        if (this.isValid()) {
            /*Timer is being reset twice. Once as part of isValid call, and second time because of mTimeoutManager.resetTimer().
            It involves lot of changes to get the status of timer being reset as part of isValid call, because of which it is done this way.
            Otherwise, just isValid() call is enough and mTimeoutManager.resetTimer() could have been removed.*/
            return resetIdleTime();
        } else {
            OMLog.debug(TAG, "Cannot reset the timer, authcontext not valid");
        }
        return false;
    }

    /**
     * Sets the credential key as the user defined key
     *
     * @param storageKey
     */
    void setStorageKey(String storageKey) {
        mStorageKey = storageKey;
    }

    public String getStorageKey() {
        return mStorageKey;
    }

    /**
     * Returns the offline credential key for this authentication context.
     *
     * @return
     * @hide
     */
    public String getOfflineCredentialKey() {
        return offlineCredentialKey;
    }

    /**
     * Sets the offline credential key for the authentication context.
     *
     * @param offlineCredentialKey
     */
    void setOfflineCredentialKey(String offlineCredentialKey) {
        this.offlineCredentialKey = offlineCredentialKey;
    }

    /**
     * Checks whether the {@link OMAuthenticationContext} is valid or not. Based
     * on the {@link AuthenticationProvider} the {@link AuthenticationService}
     * will internally validate the tokens and returns the result.
     * <p/>
     * This api is similar to {@link OMAuthenticationContext#isValid()} which by
     * default checks the validity of tokens online wherever required. However,
     * in this api the application can indicate whether the check needs to be
     * done online or locally. Please Note: If true is passed, this api can not
     * be called from the main thread of the application. It is recommended to
     * call this from a worker thread. Applications targeting Android 3.0 or
     * above will result in an exception if this is not followed.
     *
     * @param validateOnline boolean value which indicates that whether the check needs to
     *                       be performed online or only local.
     * @return
     */
    public boolean isValid(boolean validateOnline) {
        boolean valid = false;
        try {
            valid = isValidInternal(validateOnline);
        } catch (OMMobileSecurityException e) {
            OMLog.error(TAG, e.getMessage());
        }
        return valid;
    }

    /**
     * Checks whether the token is valid based on the expiry time.In case of
     * authentication using M&S server, if the expiry time has not been elapsed,
     * then SDK will check with M&S server, whether the tokens are actually
     * valid.
     * <p/>
     * So, it is recommended to call this method from a thread other than the UI
     * thread. In case, the apps are targeted for devices with Android 3.0 and
     * above, this method <b>should be</b> called from a thread other than UI
     * thread. Please note this api will perform validation of the tokens
     * against server in Mobile and Social authentication. For specific
     * preference refer to {@link OMAuthenticationContext#isValid(boolean)}
     * where we can control this behavior.
     *
     * @return true / false
     */
    public boolean isValid() {
        // since this is released api with default behavior is to validate
        // Online when applicable.
        return isValid(true);
    }

    /**
     * This is internal isValid which will be called from the public isValids.
     * This facilitates the public api preference for the validateOnline
     * preference.
     *
     * @param validateOnline true if online validation is needed else pass false.
     * @return
     * @throws OMMobileSecurityException if an exception occurs
     */
    private boolean isValidInternal(boolean validateOnline)
            throws OMMobileSecurityException {
        OMLog.debug(TAG, "__isValidInternal__");
        if (mASM == null) {
            return false;
        } else {
            if (mASM.getMSS().isLogoutInProgress()) {
                return false;
            }
            if (getAuthenticationProvider() == null) {
                return false;
            }
            if (getAuthenticationProvider() == AuthenticationProvider.OAUTH20 || getAuthenticationProvider() == AuthenticationProvider.OPENIDCONNECT10) {
                if (getOAuthTokenList().isEmpty()
                        && getAuthenticatedMode() == AuthenticationMode.ONLINE) {
                    return false;
                }
            }
            boolean isValid = true;

            if (mASM.getMSS().retrieveAuthenticationContext() == null) {
                return false;
            }
            if (mASM != null) {
                // Since the list of authentication services are lazily loaded, we
                // will no have all the authentication service instances to validate
                // the token. Hence load all the services here and perform the
                // validation. Once the work is done, unload all the services to
                // release the memory
                mASM.loadAllAuthenticationServices();
                OMLog.debug(TAG, "AuthContext validity check online ? "
                        + validateOnline);

                String credentialKey = getStorageKey() != null ? getStorageKey() : mASM.getAppCredentialKey();
                String authenticationUrl = mASM.getMSS().getMobileSecurityConfig().getAuthenticationURL().toString();

                String serverSpecificKey = OfflineAuthenticationService
                        .createServerSpecificKey(authenticationUrl, credentialKey, getIdentityDomain(),
                                getUserName());
                OMCredential credential = mASM.getMSS()
                        .getCredentialStoreService().getCredential(serverSpecificKey);
                boolean credentialsAvailable = false;
                if (credential != null
                        && !TextUtils.isEmpty(credential.getUserName())
                        && !ArrayUtils.isEmpty(credential.getUserPasswordAsCharArray())) {
                    credentialsAvailable = true;
                    credential.invalidateUserPassword();
                }

                for (AuthenticationService authService : mASM
                        .getAuthServiceMap().values()) {
                    // As this is the old/existing api we are passsing true,
                    // since the default behavior was to check online whenever
                    // possible
                    isValid = authService.isValid(this, validateOnline);

                    if (!isValid) {
                        // Remove from the credential store as well
                        boolean isDeleteUnPwd = !(mASM.getMSS()
                                .getMobileSecurityConfig()
                                .isOfflineAuthenticationAllowed());

                        boolean isDeleteTokensAndCookies = true;

                        if (authService instanceof BasicAuthenticationService) {
                            if (authContextDeleted) {
                                break;
                            }
                            BasicAuthenticationService basicAuthenticationService = ((BasicAuthenticationService) authService);
                            /**
                             * once the session is expired then remove the
                             * credentials completely from the store.
                             */
                            isDeleteUnPwd = basicAuthenticationService
                                    .isSessionTimedOut();
                            idleTimeExpired = isIdleTimeout();
                            isDeleteTokensAndCookies = !(idleTimeExpired && credentialsAvailable);

                        } else if (authService instanceof OfflineAuthenticationService) {
                            idleTimeExpired = ((OfflineAuthenticationService) authService)
                                    .isIdleTimeOut();
                            isDeleteTokensAndCookies = !(idleTimeExpired && credentialsAvailable);
                        }

                        deleteAuthContext(isDeleteUnPwd,
                                isDeleteTokensAndCookies, isDeleteTokensAndCookies,
                                false);
                        if (authService instanceof BasicAuthenticationService
                                && ((BasicAuthenticationService) authService)
                                .isSessionTimedOut()
                                || !(authService instanceof BasicAuthenticationService)) {
                            /*
                             * Setting this to true so that deleteAuthContext() is
                             * not called again when
                             * OMAuthenticationContext#isValid() is called
                             * subsequently. If it is called, it would lead to
                             * unnecessary invocation of logout url again.
                             */
                            authContextDeleted = true;
                        }
                        /* since its not a logout call */
                        break;
                    }
                }

                mASM.unloadAuthServices();
            }

            return isValid;
        }
    }

    /**
     * Checks the validity of the OAuth tokens. If a token that matches the
     * request scopes is expired, it is refreshed if the refreshExpiredTokens
     * flag passed is true.
     * <p>
     * <b>Note:</b> If refreshExpiredTokens is passed as true, this method MUST be called
     * from a background thread. This is because new tokens will be obtained only
     * by contacting OAuth server.
     *
     * @param scopes               The set of OAuth scopes to check in the token
     * @param refreshExpiredTokens {@code}true if the expired tokens should be refreshed.
     * @return true / false depending upon the validity of the tokens.
     */
    public boolean isValid(Set<String> scopes, boolean refreshExpiredTokens) {
        boolean isValid = false;
        try {
            isValid = isValidInternal(scopes, refreshExpiredTokens);
        } catch (OMMobileSecurityException e) {
            OMLog.error(TAG, e.getMessage());
        }
        return isValid;
    }

    private boolean isValidInternal(Set<String> scopes,
                                    boolean refreshExpiredTokens) throws OMMobileSecurityException {
        boolean isValid = false;
        // list to store the matching tokens with the scopes passed
        if (authenticationProvider == AuthenticationProvider.OAUTH20
                || authenticationProvider == AuthenticationProvider.OPENIDCONNECT10) {
            if (mASM != null) {
                // loading OAuth20 service in map

                AuthenticationService.Type serviceType;
                if (authenticationProvider == AuthenticationProvider.OAUTH20) {
                    serviceType = mASM.getOAuthServiceType();
                } else {
                    serviceType = AuthenticationService.Type.OPENIDCONNECT10;
                }
                if (serviceType != null) {
                    mASM.getAuthServiceMap()
                            .put(serviceType, // TODO handle for all grant types
                                    mASM.getAuthService(serviceType));
                    OAuthAuthenticationService oAuthService = (OAuthAuthenticationService) mASM
                            .getAuthServiceMap().get(serviceType);
                    if (oAuthService != null) {
                        OMLog.info(TAG, "Checking validity for : " + oAuthService.getType().name());
                        isValid = oAuthService.isValid(this, scopes,
                                refreshExpiredTokens);
                    } else
                        isValid = false;
                    mASM.getAuthServiceMap().remove(serviceType);
                }
            }
        }
        return isValid;
    }

    /**
     * Returns the identity domain for which this authentication context is
     * created.
     *
     * @return the identityDomain
     */
    public String getIdentityDomain() {
        return identityDomain;
    }

    /**
     * Returns the username for which this authentication context is created.
     *
     * @return username string
     */
    public String getUserName() {
        return userName;
    }

    void setUserName(String userName) {
        this.userName = userName;
    }

    void setSessionExpiry(Date sessionExpiry) {
        this.sessionExpiry = sessionExpiry;
    }

    void setSessionExpInSecs(int sessionExpInSecs) {
        this.sessionExpInSecs = sessionExpInSecs;
    }

    int getSessionExpInSecs() {
        return sessionExpInSecs;
    }

    int getIdleTimeExpInSecs() {
        return idleTimeExpInSecs;
    }

    boolean resetIdleTime() {
        boolean resetIdleTimeStatus = false;
        if (idleTimeExpInSecs > 0) {
            resetIdleTimeStatus = mTimeoutManager.resetTimer();
            if (resetIdleTimeStatus) {
                Calendar futureTime = Calendar.getInstance();
                futureTime.add(Calendar.SECOND, idleTimeExpInSecs);
                idleTimeExpiry = futureTime.getTime();
                OMLog.debug(TAG, "Idle time is reset to : " + idleTimeExpiry);
            }
        } else {
            OMLog.info(TAG, "Need not reset idle timer, since idle timeout is not specified or is 0 (which means no idle timeout)");
        }
        return resetIdleTimeStatus;
    }


    /**
     * Gets the session expiry time for this authentication context.
     *
     * @return session expiry time.
     */
    public Date getSessionExpiry() {
        return sessionExpiry;
    }

    /**
     * Gets the idle time expiry for this authentication context.
     *
     * @return idle time expiry.
     */
    public Date getIdleTimeExpiry() {
        return idleTimeExpiry;
    }

    AuthenticationServiceManager getAuthenticationServiceManager() {
        return mASM;
    }

    /**
     * From the given authentication context string, remove the values whichever
     * is sent as true and return the rest of the string as a json string. This
     * is used in the case of deleting the values from the store based on
     * various conditions such as forget device, remove user token when logout,
     * remove offline credentials etc.,
     *
     * @param isDeleteUnPwd   should we delete user name and password
     * @param isDeleteCookies should we delete cookies
     */
    void deleteAuthContext(boolean isDeleteUnPwd,
                           boolean isDeleteCookies, boolean isDeleteToken,
                           boolean isLogoutCall) {

        String TAG = OMAuthenticationContext.TAG + "_deleteAuthContext";

        if (mASM != null) {
            /*
             * Instead of removing cached instance here, it will be removed in
             * OMMobileSecurityService#onLogoutCompleted(). This change is
             * required so that SDK can clear the cookies set by OWSM MA
             * (OMAuthenticationContext#getOWSMMACookies()) after logout is done
             * by corresponding Authentication services.
             */
            if (isDeleteUnPwd
                    && isDeleteCookies && !isLogoutCall) {
                /*
                 * The cached instance should be removed here itself, if it is
                 * session timeout and not logout call, i.e when isValid is
                 * called.
                 */
                mASM.setAuthenticationContext(null);
            }

            AuthenticationService authService = null;
            do {
                authService = mASM.getStateTransition().getLogoutState(
                        authService);
                OMLog.debug(TAG, "Logout authService: " + authService);
                if (authService != null) {
                    authService.logout(this, isDeleteUnPwd, isDeleteCookies, isDeleteToken,
                            isLogoutCall);
                }
            }
            while (authService != null);

            boolean authContextPersistenceAllowed = mASM.getMSS().getMobileSecurityConfig()
                    .isAuthContextPersistenceAllowed();
            if (authContextPersistenceAllowed) {
                deletePersistedAuthContext(isDeleteUnPwd, isDeleteToken, isLogoutCall);
            }

            if (isDeleteUnPwd && isDeleteCookies) {
                //no op
                //TODO To check with Jyotsna why this is required. Ideally auth services can be unloaded irrespctive of these flags.
            } else {
                mASM.unloadAuthServices();
            }
        }
    }

    public void deletePersistedAuthContext(boolean isDeleteUnPwd, boolean isDeleteToken,
                                           boolean isLogoutCall) {
        // this checks whether the app configuration allows auth context persistence or not?
        boolean authContextPersistenceAllowed = mASM.getMSS().getMobileSecurityConfig()
                .isAuthContextPersistenceAllowed();
        if (!authContextPersistenceAllowed) {
            return;
        }
        OMCredentialStore css = mASM.getMSS().getCredentialStoreService();
        String credentialKey = getStorageKey() != null ? getStorageKey() : mASM.getAppCredentialKey();
        if (isDeleteUnPwd && isDeleteToken) {
            /* This means complete information has
            to be removed from the store.*/
            css.deleteAuthContext(credentialKey);

            OMLog.debug(TAG,
                    "After forget device all the details are removed for the key "
                            + credentialKey
                            + " from the credential store");
        } else {

            boolean isRemoveFromStore = false;

            if ((isDeleteToken && getAuthenticatedMode() == AuthenticationMode.OFFLINE)
                    || (isDeleteToken && isLogoutCall)) {
                /*If it is logout use-case or offline authentication
                 * session being invalid, authContext should be completely
                 * removed.*/
                isRemoveFromStore = true;
            }
            /** Tokens would have been removed in logout() of respective AuthenticationServices.
             * So, even though true is passed for isAllowTokens in the below toString method,
             * normally tokens will not be present in the string representation. It will
             * be present in following scenario:
             * OAuth Resource owner flow with offline authentication enabled,
             * idle timeout occurred, tokens with refresh token are not cleared.
             * Refer: {@link OAuthAuthenticationService#clearOAuthTokens(OMAuthenticationContext, boolean)}
             * */
            String detailsToStore = toString(true);

            if (detailsToStore != null && !isRemoveFromStore) {
                css.addAuthContext(credentialKey, detailsToStore);
                OMLog.debug(TAG,
                        "After logout the authentication context for the key "
                                + credentialKey
                                + " in the credential store is : "
                                + detailsToStore);
            } else {
                css.deleteAuthContext(credentialKey);
                OMLog.debug(TAG,
                        "After logout the authentication context for the key "
                                + credentialKey
                                + " is removed from the  credential store");
            }
        }
    }

    void setOpenIdUserInfo(OpenIDUserInfo info) {
        OMLog.debug(TAG, "Setting openID User info" + ((info != null) ? (" for user: " + info.getDisplayName()) : " null "));
        mOpenIDUserInfo = info;
    }

    public OpenIDUserInfo getOpenIDUserInfo() {
        if (authenticationProvider != AuthenticationProvider.OPENIDCONNECT10) {
            return null;
        }
        return mOpenIDUserInfo;
    }

    private void populateFields(String authContextString) {
        try {
            JSONObject jsonObject = new JSONObject(authContextString);
            this.userName = jsonObject.optString(USERNAME_KEY, "");
            this.identityDomain = jsonObject.optString(IDENTITY_DOMAIN_KEY, "");
            this.offlineCredentialKey = jsonObject.optString(
                    OFFLINE_CREDENTIAL_KEY, "");
            long sessionExp = jsonObject.optLong(SESSION_EXPIRY, -1);
            int sessionExpInSecs = jsonObject.optInt(SESSION_EXPIRY_SECS, -1);

            if (sessionExp != -1 && sessionExpInSecs != -1) {
                this.sessionExpiry = new Date(sessionExp);
                this.sessionExpInSecs = sessionExpInSecs;
            }

            long idleTimeExp = jsonObject.optLong(IDLETIME_EXPIRY, -1);
            int idleTimeExpInSecs = jsonObject.optInt(IDLETIME_EXPIRY_SECS, -1);

            if (idleTimeExp != -1 && idleTimeExpInSecs != -1) {
                this.idleTimeExpiry = new Date(idleTimeExp);
                this.idleTimeExpInSecs = idleTimeExpInSecs;
            }

            JSONArray jsonArray = jsonObject.optJSONArray(TOKENS);
            this.tokens = convertJSONArrayToMap(jsonArray);

            jsonArray = jsonObject.optJSONArray(OWSM_MA_COOKIES);
            this.owsmMACookies = convertJSONArrayToMap(jsonArray);

            jsonArray = jsonObject.optJSONArray(OAUTH_TOKEN);
            this.oAuthTokenList = convertJSONArrayToList(jsonArray);

            mStatus = Status.SUCCESS;
            this.authenticatedMode = AuthenticationMode.valueOf(jsonObject
                    .optString(AUTHEN_MODE, AuthenticationMode.ONLINE.toString()));
            this.authenticationProvider = AuthenticationProvider
                    .valueOf(jsonObject.optString(AUTHEN_PROVIDER,
                            AuthenticationProvider.OFFLINE.name()));
            String authenticationMechanism = jsonObject
                    .optString(OMSecurityConstants.Param.AUTHENTICATION_MECHANISM);
            if (!TextUtils.isEmpty(authenticationMechanism)) {
                this.authenticationMechanism = AuthenticationMechanism
                        .valueOf(authenticationMechanism);
            }
            logoutTimeout = jsonObject.optInt(LOGOUT_TIMEOUT_VALUE);
        } catch (JSONException e) {
            OMLog.error(TAG + "_populateFields", e.getMessage(), e);
        }
    }

    /**
     * Password returned should be cleared (zeroed out) immediately when
     * its use is over.
     */
    char[] getUserPassword() {
        char[] password = null;
        OMMobileSecurityService mss = mASM.getMSS();
        OMCredentialStore credService = mss.getCredentialStoreService();
        if (!TextUtils.isEmpty(offlineCredentialKey)) {
            OMCredential credential = credService.getCredential(offlineCredentialKey);
            if (credential != null) {
                CryptoScheme scheme = mss.getMobileSecurityConfig()
                        .getCryptoScheme();
                // If the password is hashed, returning null
                if (!CryptoScheme.isHashAlgorithm(scheme)) {
                    /* Since the password is available in plaintext (already decrypted by SecureStorageService),
                    just removing the prefix.*/
                    password = credential.getUserPasswordAsCharArray();
                }
            }
        } else {
            OMLog.error(TAG, "Offline Key not set[SDK error]");
        }
        return password;
    }

    int getLogoutTimeout() {
        return logoutTimeout;
    }

    void setLogoutTimeout(int logoutTimeout) {
        this.logoutTimeout = logoutTimeout;
    }

    /**
     * This method performs the logout from the server. Clear the details stored
     * in the credential store. If the forgetDevice is true, then it will clear
     * the CRH handles as well; otherwise it will clear only the tokens stored.
     *
     * @param forgetDevice true if CRH also needs to be deleted(which is forget device)
     */
    @SuppressWarnings("deprecated")
    public void logout(boolean forgetDevice) {
        mASM.getMSS().setLogoutInProgress(true);
        boolean justRetainIdleTimeExpiryAsEpoch = false;

        if (forgetDevice) {
            if (authenticationProvider == AuthenticationProvider.FEDERATED
                    || authenticationProvider == AuthenticationProvider.OAUTH20) {
                /*
                 * Clearing username and password which may be stored in
                 * WebViewDatabase if authentication is done using embedded
                 * webview.
                 */

                //TODO ajulka see what we can do for OAuth here.
                WebViewDatabase webViewDatabase = WebViewDatabase
                        .getInstance(mASM.getApplicationContext());
                webViewDatabase.clearUsernamePassword();
                webViewDatabase.clearFormData();
                OMLog.debug(TAG, "Logout(true): Cleared username,password and form data");
            }
            // removing the credentials for the given url
            deleteAuthContext(true, true, true, true);
            // remove the user preferences as well
            if (mASM.getMSS().getMobileSecurityConfig().isAnyRCFeatureEnabled()) {
                mASM.getRCUtility().removeAll();
            }

        } else {
            deleteAuthContext(false, true, true, true);
            if (mASM.getMSS().getMobileSecurityConfig().isAnyRCFeatureEnabled()) {
                mASM.getRCUtility().inValidateRememberedCredentials();
            }
        }
    }

    /**
     * This method will be called internally to clear the fields which are not
     * necessary once the authentication operation is completed.
     */
    void clearFields() {
        clearPassword();
        if (mAuthRequest != null && mAuthRequest.getAuthenticationURL() != null) {
            mASM.getMSS().getMobileSecurityConfig()
                    .setAuthenticationURL(mAuthRequest.getAuthenticationURL());
        }
        this.mAuthRequest = null;

        String userNameFromMap = (String) getInputParams().get(USERNAME_KEY);
        if (userNameFromMap != null) {
            this.userName = userNameFromMap;
        }
        this.identityDomain = (String) getInputParams().get(IDENTITY_DOMAIN_KEY);
        this.getInputParams().clear();
    }

    /**
     * This method is used internally to clear all the fields of this object
     * once the authentication is failure.
     */
    void clearAllFields() {
        clearPassword();
        mASM = null;
        mAuthRequest = null;
        this.idleTimeExpiry = null;
        this.sessionExpiry = null;
        this.sessionExpInSecs = 0;
        this.idleTimeExpInSecs = 0;
        this.tokens = null;
        this.mCookies = null;

        String userNameFromMap = (String) getInputParams().get(USERNAME_KEY);
        if (userNameFromMap != null) {
            this.userName = userNameFromMap;
        }
        this.getInputParams().clear();
        this.authenticationProvider = null;
    }

    void clearPassword() {
        /*char[] password should be cleared by SDK only if SDK has created it. If it is created
         * by developer, developer MUST clear it, e.g: in onAuthenticationCompleted().*/
        boolean clearPassword = false;
        Object clearPasswordObj = getInputParams().get(CLEAR_PASSWORD);
        if (clearPasswordObj instanceof Boolean && (boolean) clearPasswordObj) {
            /* This is the case in following 2 scenarios:
            1. SDK creates char[] when password is passed as String in
            Authentication challenge.
            2. During auto login, SDK retrieves password and does not
            expose it to developer.*/
            clearPassword = true;
        }
        Object passwordObj = getInputParams().get(PASSWORD_KEY_2);
        if (clearPassword && passwordObj instanceof char[]) {
            char[] password = (char[]) passwordObj;
            Arrays.fill(password, ' ');
            OMLog.trace(TAG, "password is cleared");
        }
        getInputParams().remove(PASSWORD_KEY_2);
    }

    boolean checkIdleTimeout() {
        Date currentTime = Calendar.getInstance().getTime();
        if (sessionExpiry != null
                && getSessionExpInSecs() != 0
                && (currentTime.after(sessionExpiry) || currentTime
                .equals(sessionExpiry))) {
            return false;
        }
        if (idleTimeExpiry != null
                && getIdleTimeExpInSecs() != 0
                && (currentTime.after(idleTimeExpiry) || currentTime
                .equals(idleTimeExpiry))) {
            isIdleTimeout = true;
        }
        OMLog.debug(TAG, "checkIdleTimeout in authcontext " + isIdleTimeout);
        return isIdleTimeout;
    }

    void setIdleTimeout(boolean isIdleTimeout) {
        this.isIdleTimeout = isIdleTimeout;
    }

    boolean isIdleTimeout() {
        return isIdleTimeout;
    }

    /**
     * @return TimeoutManager
     * @hide
     */
    public TimeoutManager getTimeoutManager() {
        return mTimeoutManager;
    }

    void setOAuthTokenList(List<OAuthToken> newTokenList) {
        this.oAuthTokenList = newTokenList;
    }

    /**
     * Returns all access tokens obtained as part of OAuth.
     *
     * @return
     */
    public List<OAuthToken> getOAuthTokenList() {
        if (oAuthTokenList == null) {
            oAuthTokenList = new ArrayList<>();
        }
        return oAuthTokenList;
    }

    /**
     * Checks if there is any refresh token for the passed scopes.
     * Refresh token is checked in all access tokens including
     * expired ones.
     */
    boolean hasRefreshToken(Set<String> scopes) {
        List<OMToken> tokens = getTokens(scopes, true);
        if (tokens == null || tokens.isEmpty()) {
            return false;
        }
        boolean hasRefreshToken = false;
        for (OMToken omToken : tokens) {
            if (omToken instanceof OAuthToken && ((OAuthToken) omToken).hasRefreshToken()) {
                hasRefreshToken = true;
                break;
            }
        }
        return hasRefreshToken;
    }


    private boolean isOAuthRelated() {

        boolean isOAuth = false;
        if (authenticationProvider == AuthenticationProvider.OAUTH20 || authenticationProvider == AuthenticationProvider.OPENIDCONNECT10) {
            isOAuth = true;
        } else if (authenticationProvider == AuthenticationProvider.FEDERATED) {
            /*
             * In case of FedAuth, we get OAuth access token from Token Relay
             * service.
             */
            if (((OMFederatedMobileSecurityConfiguration) mASM.getMSS().getMobileSecurityConfig()).parseTokenRelayResponse()) {
                isOAuth = true;
            }
        }
        OMLog.info(TAG, "isOAuthRelated : " + isOAuth);
        return isOAuth;
    }

    /**
     * This method returns a list of available OAuth2.0 access tokens based on
     * the Scopes passed. If null is passed as scopes then the SDK will return
     * all the unexpired access tokens . Other wise it will return all the
     * unexpired access tokens whose scopes contains all the scopes passed
     * in the request.
     * .
     *
     * @param scopes {@link List} of scopes for which we want to get the access
     *               tokens .
     * @return {@link List} of Access tokens matching the criteria .
     */
    public List<OMToken> getTokens(Set<String> scopes) {
        return getTokens(scopes, false);
    }

    private List<OMToken> getTokens(Set<String> scopes, boolean includeExpiredTokens) {
        List<OMToken> matchedTokens = new ArrayList<>();
        if (!isOAuthRelated()) {
            return null;
        }
        if (scopes == null || scopes.size() == 0) {
            for (OMToken token : getOAuthTokenList()) {
                matchedTokens.add(token);
            }
        } else {
            for (OMToken token : getOAuthTokenList()) {
                OAuthToken oAuthToken = (OAuthToken) token;
                if (oAuthToken.getScopes() != null) {
                    if (oAuthToken.getScopes().containsAll(scopes)) {
                        if (includeExpiredTokens || !token.isTokenExpired()) {
                            // return only if token is not expired
                            matchedTokens.add(token);
                        }
                    }
                } else {
                    // return auxillary tokens also as these are without scopes
                    // and an oauth access token is always associated with a
                    // scope if not set a default scopes is associated with the
                    // token.
                    matchedTokens.add(token);
                }
            }
        }
        return matchedTokens;
    }

    /**
     * This method returns a Map of requested credential information from the
     * credential store. It also contains custom headers to be injected in the
     * web service request, if any. Currently, this method supports the
     * following keys: <br />
     * <br />
     * <p/>
     * {@link #CREDENTIALS} - Returns credentials of the user associated with this
     * authentication context. Format of the returned map :
     * {{@link #USERNAME_PROPERTY}:"username_value",
     * {@link #PASSWORD_PROPERTY}:"password_value",
     * {@link #HEADERS}:{"headerName1":"headerValue1","headerName2":"headerValue2",...}
     * } <br />
     * <br />
     * {@link OMSecurityConstants#OAUTH_ACCESS_TOKEN} - Returns OAuth access tokens associated with
     * this authentication context.
     * <p/>
     * Format of the returned map : {"oauth_access_token1":"value1",
     * "oauth_access_token2":"value2",...,
     * "{@link #HEADERS}":{"headerName1":"headerValue1"
     * ,"headerName2":"headerValue2",...}}
     *
     * @param keys a String array of the information requested. e.g. credentials
     *             or tokens.
     * @return map of requested credential information.
     * @deprecated This returns password as String which is a security concern.
     * Instead use {@link #getCredentialInformation2(String[])} which returns
     * password as a char array against the key: {@link #PASSWORD_PROPERTY_2}.
     */
    @Deprecated
    public Map<String, Object> getCredentialInformation(String[] keys) {
        return getCredentialInformationInternal(keys, false);
    }

    /**
     * This method returns a Map of requested credential information from the
     * credential store. It also contains custom headers to be injected in the
     * web service request, if any. Currently, this method supports the
     * following keys:
     * <ul>
     * <li>
     * {@link #CREDENTIALS} - Returns credentials of the user associated with this
     * authentication context. Format of the returned map :
     * {{@link #USERNAME_PROPERTY}:"username_value",
     * {@link #PASSWORD_PROPERTY_2}:['p','a','s','s','w','o','r','d'],
     * {@link #HEADERS}:{"headerName1":"headerValue1","headerName2":"headerValue2",...}
     * }
     * <br />
     * <b>Note:</b> password char[] MUST BE cleared after use by the caller of this method.
     * </li>
     * <p>
     * <li>
     * {@link OMSecurityConstants#OAUTH_ACCESS_TOKEN} - Returns OAuth access tokens associated with
     * this authentication context.
     * Format of the returned map : {"oauth_access_token1":"value1",
     * "oauth_access_token2":"value2",...,
     * "{@link #HEADERS}":{"headerName1":"headerValue1"
     * ,"headerName2":"headerValue2",...}}
     * </li>
     * </ul>
     *
     * @param keys a String array of the information requested. e.g. credentials
     *             or tokens.
     * @return map of requested credential information.
     */
    public Map<String, Object> getCredentialInformation2(String[] keys) {
        return getCredentialInformationInternal(keys, true);
    }

    private Map<String, Object> getCredentialInformationInternal(String[] keys, boolean passwordAsCharArray) {
        Map<String, Object> credentialInfo = new HashMap<>();
        for (String key : keys) {
            if (key.equalsIgnoreCase(CREDENTIALS)) {
                char[] password = getUserPassword();
                if (ArrayUtils.isEmpty(password)) {
                    credentialInfo.put(ERROR, CREDENTIALS_UNAVAILABLE);
                } else {
                    credentialInfo.put(USERNAME_PROPERTY, getUserName());
                    if (passwordAsCharArray) {
                        credentialInfo.put(PASSWORD_PROPERTY_2, password);
                    } else {
                        credentialInfo.put(PASSWORD_PROPERTY, new String(password));
                    }
                }
            } else {
                try {
                    credentialInfo.putAll(getTokensMapForCredInfo(key));
                } catch (JSONException e) {
                    OMLog.error(TAG, "getCredentialInformation(" + key + "): "
                            + e.getMessage(), e);
                }
            }
        }
        Map<String, String> headers = getCustomHeaders();
        if (!headers.isEmpty()) {
            credentialInfo.put(HEADERS, headers);
        }
        return credentialInfo;
    }

    /**
     * This method stores the credential information <code>credInfo</code>
     * passed in this authentication context. Currently, this method only
     * supports cookies. It sets the cookies passed in the map
     * <code>credInfo</code> to the cookie store of the mobile app.
     *
     * @param credInfo Map of the values to set. Format of the map expected in case
     *                 of cookies: {"cookie1Name_cookie1Domain": {"name":"cookieName"
     *                 "domain", "cookieDomain" "expiresdate",
     *                 "cookieExpiryInMilliseconds" ... }, ...}
     */
    public void setCredentialInformation(Map<String, Object> credInfo) {
        for (Map.Entry<String, Object> entry : credInfo.entrySet()) {
            Map<String, String> cookieValues = (Map<String, String>) entry
                    .getValue();
            String tokenName = cookieValues.get(TOKEN_NAME);
            String url = cookieValues.get(OMSecurityConstants.URL);
            String tokenValue = cookieValues.get(TOKEN_VALUE);
            String expiryDateStr = cookieValues.get(EXPIRY_DATE);

            String domain = cookieValues.get(DOMAIN);
            boolean httpOnly = Boolean.parseBoolean(cookieValues
                    .get(IS_HTTP_ONLY));
            boolean secure = Boolean.parseBoolean(cookieValues.get(IS_SECURE));
            String path = cookieValues.get(PATH);
            OMToken cookie = new OMCookie(url, tokenName, tokenValue, domain,
                    path, expiryDateStr, httpOnly, secure);
            String cookieNameWithHostAppended = entry.getKey();
            getOWSMMACookies().put(cookieNameWithHostAppended, cookie);
            OMLog.debug(TAG,
                    "Cookie obtained from OWSM MA: " + cookie.toString());

            Map<String, OMToken> tokens = new HashMap<>();
            tokens.put(cookieNameWithHostAppended, cookie);
            mASM.storeCookieString(tokens, false);
        }

        updateAuthContextWithOWSMCookies();
    }

    /**
     * This updates the authContext string stored in SharedPreferences with the
     * new set of OWSM MA cookies.
     */
    private void updateAuthContextWithOWSMCookies() {
        boolean authContextPersistenceAllowed = mASM.getMSS()
                .getMobileSecurityConfig().isAuthContextPersistenceAllowed();
        if (authContextPersistenceAllowed) {
            OMCredentialStore css = mASM.getMSS()
                    .getCredentialStoreService();
            String credentialKey = getStorageKey() != null ? getStorageKey()
                    : mASM.getAppCredentialKey();

            String authContextString = css.getAuthContext(credentialKey);
            try {
                JSONObject authContextJSONObject = new JSONObject(
                        authContextString);
                authContextJSONObject.putOpt(OWSM_MA_COOKIES,
                        convertMapToJSONArray(getOWSMMACookies()));
                String newAuthContext = authContextJSONObject.toString();
                css.addAuthContext(credentialKey, newAuthContext);
                OMLog.debug(TAG + "_updateAuthContextWithOWSMCookies",
                        "authentication context for the key " + credentialKey
                                + " in the credential store is : "
                                + newAuthContext);
            } catch (JSONException e) {
                OMLog.error(TAG + "_updateAuthContextWithOWSMCookies",
                        e.getMessage(), e);
            }

        }
    }

    private JSONArray convertMapToJSONArray(Map<String, OMToken> tokens)
            throws JSONException {
        JSONArray jsonArray = new JSONArray();
        for (Map.Entry<String, OMToken> entry : tokens.entrySet()) {
            JSONObject jsonToken = new JSONObject();
            if (entry.getValue() != null) {
                jsonToken.put(entry.getKey(), entry.getValue().toJSONObject());
            }
            jsonArray.put(jsonToken);
        }
        return jsonArray;
    }

    private Map<String, OMToken> convertJSONArrayToMap(JSONArray jsonArray)
            throws JSONException {
        if (jsonArray != null) {
            Map<String, OMToken> tokens = new HashMap<>();
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject tokenObj = jsonArray.getJSONObject(i);

                @SuppressWarnings("rawtypes")
                Iterator itr = tokenObj.keys();
                String key = (String) itr.next();

                OMToken token = null;
                JSONObject tokenJSONObject = tokenObj.getJSONObject(key);
                if (OpenIDToken.OPENID_CONNECT_TOKEN.equals(key)) {
                    try {
                        token = new OpenIDToken(tokenJSONObject);
                    } catch (ParseException e) {
                        OMLog.error(TAG, e.getMessage(), e);
                    }
                } else {
                    token = new OMToken(tokenJSONObject);
                }
                if (token != null) {
                    tokens.put(key, token);
                }
            }
            return tokens;
        }
        return null;
    }

    private List<OAuthToken> convertJSONArrayToList(JSONArray jsonArray) throws JSONException {
        List<OAuthToken> tokenList = new ArrayList<>();
        if (jsonArray != null) {
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject token = jsonArray.getJSONObject(i);
                if (token.has(OpenIDToken.OPENID_CONNECT_TOKEN)) {
                    try {
                        tokenList.add(new OpenIDToken(token));
                    } catch (ParseException e) {
                        OMLog.trace(TAG, e.getMessage(), e);
                    }
                } else {
                    tokenList.add(new OAuthToken(token));
                }
            }
        }
        return tokenList;
    }

    /**
     * This returns the cookies corresponding to the given URL, as returned by
     * {@link OMCookieManager#getCookie(String)}. The return map will
     * also contain the custom headers if includeHeaders flag is true.
     *
     * @param url            Url for which request params are required.
     * @param includeHeaders flag to indicate, if custom headers are required.
     * @return Format of the returned map : {{@link #COOKIES}:{@link OMCookieManager#getCookie(String)},
     * "{@link #HEADERS}":{"headerName1":"headerValue1"
     * ,"headerName2":"headerValue2",...}}
     */
    public Map<String, Object> getRequestParams(String url,
                                                boolean includeHeaders) {
        Map<String, Object> params = new HashMap<>();
        String cookieString = OMCookieManager.getInstance().getCookie(url);
        if (!TextUtils.isEmpty(cookieString)) {
            params.put(COOKIES, cookieString);
        }
        Map<String, String> headers = getCustomHeaders();
        if (includeHeaders && !headers.isEmpty()) {
            params.put(HEADERS, headers);
        }
        return params;
    }

    /**
     * This returns the custom headers which are to be added in REST calls made by the app.
     * This is formed from the values set using the following properties:
     * <ul>
     * <li> {@link OMMobileSecurityService#OM_PROP_CUSTOM_HEADERS_FOR_MOBILE_AGENT}
     * <li> {@link OMMobileSecurityService#OM_PROP_SEND_IDENTITY_DOMAIN_HEADER_TO_MOBILE_AGENT}
     * <li> {@link OMMobileSecurityService#OM_PROP_IDENTITY_DOMAIN_HEADER_NAME}
     * <li> {@link OMMobileSecurityService#OM_PROP_IDENTITY_DOMAIN_NAME}
     * </ul>
     */
    public Map<String, String> getCustomHeaders() {
        Map<String, String> headers = new HashMap<>();
        if (mASM == null) {
            return headers;
        }
        OMMobileSecurityConfiguration config = mASM.getMSS().getMobileSecurityConfig();
        if (config.getCustomHeadersMobileAgent() != null
                && !config.getCustomHeadersMobileAgent().isEmpty()) {
            headers.putAll(config.getCustomHeadersMobileAgent());
        }
        if (!TextUtils.isEmpty(identityDomain)
                && config.isSendIdDomainToMobileAgent()) {
            headers.put(config.getIdentityDomainHeaderName(), identityDomain);
        }
        return headers;
    }

    // returns map for tokens if at all the valid keys are passed other wise
    // returns nothing.
    private Map<String, Object> getTokensMapForCredInfo(String requestedToken)
            throws JSONException {
        Map<String, Object> tokensMap = new HashMap<>();

        if (OMSecurityConstants.OAUTH_ACCESS_TOKEN
                .equalsIgnoreCase(requestedToken)) {
            int count = 1;
            
            if (isOAuthRelated()) {
                // can have oauth access tokens, user_assertion and
                // client_assertion
                // populate OAuth access tokens.
                for (OMToken token : getOAuthTokenList()) {
                    if (OpenIDToken.OPENID_CONNECT_TOKEN.equals(token.getName())) {
                        /* This is because OWSM MA expects only OAuth access tokens. It does not
                        expect other tokens like OpenID token.*/
                        continue;
                    }
                    Map<String, String> tokenValues = new HashMap<>();
                    tokenValues.put(TOKEN_NAME, token.getName());
                    tokenValues.put(TOKEN_VALUE, token.getValue());
                    tokenValues.put(EXPIRES, token.getExpiryTime()
                            .toString());
                    Set<String> scopes = ((OAuthToken) token).getScopes();
                    if (scopes != null && !scopes.isEmpty()) {
                        tokenValues.put(OAUTH_TOKEN_SCOPE, scopes.toString());
                    }
                    tokensMap.put(OAUTH_ACCESS_TOKEN + count, tokenValues);
                    count++;
                }
            }
        }
        // handle other token keys later.
        return tokensMap;
    }

    /**
     * Gets a map of token name as key and value as instance of {@link OMToken}.
     *
     * @return Map instance
     */
    public Map<String, OMToken> getTokens() {
        if (tokens == null) {
            tokens = new HashMap<>();
        }

        return tokens;
    }

    public Map<String, OMToken> getOWSMMACookies() {
        if (owsmMACookies == null) {
            owsmMACookies = new HashMap<>();
        }

        return owsmMACookies;
    }


    void setTokens(Map<String, OMToken> tokens) {
        this.tokens = tokens;
    }

    public Set<URI> getVisitedUrls() {
        return mVisitedUrls;
    }

    void setCookies(List<OMCookie> cookies) {
        mCookies = cookies;
    }

    public List<OMCookie> getCookies() {
        return mCookies;
    }

    void setVisitedUrls(Set<URI> visitedUrls) {
        mVisitedUrls = visitedUrls;
    }

    /**
     * Deletes the cookies locally to be on safe side, although server might have deleted them
     * as part of invoking logout url. Cookies have to be deleted locally only after logout url is invoked.
     * If they are deleted before invoking logout url, it will result in dangling sessions at server side.
     */
    public void deleteCookies() {
        OMLog.trace(TAG, "deleteCookies");
        if (mASM != null) {
            OMCookieManager.getInstance().removeSessionCookies(mASM.getApplicationContext(), mCookies);
        }
    }

    /**
     * Provides a string representation of this object.
     *
     * @param isAllowTokens whether the tokens are to be included in the string
     *                      representation
     * @return string representation of the object
     */
    String toString(boolean isAllowTokens) {
        JSONObject jsonObject = new JSONObject();

        try {
            jsonObject.put(USERNAME,
                    !TextUtils.isEmpty(userName) ? userName
                            : getInputParams().get(USERNAME));
            String identityDomain = (String) getInputParams().get(
                    IDENTITY_DOMAIN);
            jsonObject.put(IDENTITY_DOMAIN,
                    !TextUtils.isEmpty(identityDomain) ? identityDomain
                            : this.identityDomain);

            if (getAuthenticatedMode() != null) {
                jsonObject.put(AUTHEN_MODE, getAuthenticatedMode().name());
            }
            if (getAuthenticationProvider() != null) {
                jsonObject.put(AUTHEN_PROVIDER, getAuthenticationProvider()
                        .name());
            }
            if (authenticationMechanism != null) {
                jsonObject.put(AUTHENTICATION_MECHANISM,
                        authenticationMechanism.name());
            }
            jsonObject.put(OFFLINE_CREDENTIAL_KEY, getOfflineCredentialKey());

            boolean isExpDetAdd = false;

            if (isAllowTokens && !getTokens().isEmpty()) {
                JSONArray tokens = convertMapToJSONArray(getTokens());
                jsonObject.put(TOKENS, tokens);

                isExpDetAdd = true;
            }
            if (isAllowTokens && !getOAuthTokenList().isEmpty()) {
                JSONArray jsonArray = new JSONArray();
                for (OMToken token : getOAuthTokenList()) {
                    OAuthToken oAuthToken = (OAuthToken) token;
                    jsonArray.put(oAuthToken.toJSONObject());
                }
                jsonObject.put(OAUTH_TOKEN, jsonArray);
            }
            if (getAuthenticatedMode() == AuthenticationMode.OFFLINE) {
                isExpDetAdd = true;
            }

            if (isExpDetAdd) {
                if (sessionExpiry != null) {
                    jsonObject.put(SESSION_EXPIRY, sessionExpiry.getTime());
                    jsonObject.put(SESSION_EXPIRY_SECS, sessionExpInSecs);
                }

                if (idleTimeExpiry != null) {
                    jsonObject.put(IDLETIME_EXPIRY, idleTimeExpiry.getTime());
                    jsonObject.put(IDLETIME_EXPIRY_SECS, idleTimeExpInSecs);
                }
            }

            if (!getOWSMMACookies().isEmpty()) {
                JSONArray owsmMACookies = convertMapToJSONArray(getOWSMMACookies());
                jsonObject.put(OWSM_MA_COOKIES, owsmMACookies);
            }

            jsonObject.put(LOGOUT_TIMEOUT_VALUE, logoutTimeout);

        } catch (JSONException e) {
            OMLog.debug(TAG + "_toString", e.getLocalizedMessage(), e);
        }

        if (jsonObject.length() > 0) {
            return jsonObject.toString();
        } else {
            return null;
        }
    }

    /**
     * This method will be used internally to copy the details from the auth
     * context retrieved from the secure storage. The string representation
     * {@link #toString(boolean)} is used to store auth context in secure
     * storage.
     *
     * @param authContext
     */
    void copyFromAuthContext(OMAuthenticationContext authContext) {
        this.userName = authContext.getUserName();
        this.identityDomain = authContext.getIdentityDomain();
        this.authenticatedMode = authContext.getAuthenticatedMode();
        this.authenticationProvider = authContext.getAuthenticationProvider();
        this.authenticationMechanism = authContext.getAuthenticationMechanism();
        this.offlineCredentialKey = authContext.getOfflineCredentialKey();
        this.tokens = authContext.getTokens();
        this.oAuthTokenList = authContext.oAuthTokenList;
        this.sessionExpiry = authContext.getSessionExpiry();
        this.sessionExpInSecs = authContext.getSessionExpInSecs();
        this.idleTimeExpiry = authContext.getIdleTimeExpiry();
        this.idleTimeExpInSecs = authContext.getIdleTimeExpInSecs();
        this.owsmMACookies = authContext.getOWSMMACookies();
        this.logoutTimeout = authContext.getLogoutTimeout();
        this.mStorageKey = authContext.getStorageKey();
    }
}
