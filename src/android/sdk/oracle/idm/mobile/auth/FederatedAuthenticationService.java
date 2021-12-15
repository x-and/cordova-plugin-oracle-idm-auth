/*
 * Copyright (c) 2017, Oracle and/or its affiliates.
 * The Universal Permissive License (UPL), Version 1.0
 */


package oracle.idm.mobile.auth;

import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import oracle.idm.mobile.OMAuthenticationRequest;
import oracle.idm.mobile.OMErrorCode;
import oracle.idm.mobile.OMMobileSecurityException;
import oracle.idm.mobile.OMSecurityConstants;
import oracle.idm.mobile.auth.OMAuthenticationContext.AuthenticationProvider;
import oracle.idm.mobile.auth.logout.FedAuthLogoutCompletionHandler;
import oracle.idm.mobile.auth.logout.OMLogoutCompletionHandler;
import oracle.idm.mobile.auth.webview.LogoutWebViewClient;
import oracle.idm.mobile.auth.webview.WebViewAuthServiceInputCallbackImpl;
import oracle.idm.mobile.configuration.OMFederatedMobileSecurityConfiguration;
import oracle.idm.mobile.connection.OMConnectionHandler;
import oracle.idm.mobile.connection.OMCookieManager;
import oracle.idm.mobile.connection.OMHTTPResponse;
import oracle.idm.mobile.logging.OMLog;

/**
 * @hide
 */
public class FederatedAuthenticationService extends AuthenticationService implements ChallengeBasedService {
    private static final String TAG = FederatedAuthenticationService.class.getSimpleName();
    private static final String TOKEN_RELAY_PATH = "/fscmRestApi/tokenrelay";
    private static final String ANTI_CSRF_PATH = "/fscmRestApi/anticsrf";
    private static final String XSRF_TOKEN_RESPONSE_JSON_KEY = "xsrftoken";
    private static final String XSRF_TOKEN_REQUEST_HEADER = "X-XSRF-TOKEN";

    private OMFederatedMobileSecurityConfiguration mConfig;

    protected FederatedAuthenticationService(
            AuthenticationServiceManager asm, OMAuthenticationCompletionHandler handler, OMLogoutCompletionHandler logoutHandler) {
        super(asm, handler, logoutHandler);
        OMLog.info(TAG, "initialized");
        if (asm.getMSS().getMobileSecurityConfig() instanceof OMFederatedMobileSecurityConfiguration) {
            mConfig = ((OMFederatedMobileSecurityConfiguration) asm.getMSS().getMobileSecurityConfig());
        }
    }

    @Override
    public OMAuthenticationChallenge createLoginChallenge() {
        OMAuthenticationChallenge challenge = createCommonChallenge();
        OMLog.info(TAG, "Create Login Challenge : " + challenge.toString());
        return challenge;
    }

    @Override
    public OMAuthenticationChallenge createLogoutChallenge() {
        OMAuthenticationChallenge challenge = createCommonChallenge();
        OMLog.info(TAG, "Create Logout Challenge : " + challenge.toString());
        return challenge;
    }

    @Override
    public boolean isChallengeInputRequired(Map<String, Object> inputParams) {
        boolean result = true;
        try {
            mAuthCompletionHandler.validateResponseFields(inputParams);
            result = false;
        } catch (OMMobileSecurityException e) {
            OMLog.debug(TAG, "Response fields are not valid. Error : " + e.getErrorMessage());
        }
        OMLog.info(OMSecurityConstants.TAG, "isChallengeInputRequired " + result);
        return result;
    }

    @Override
    public OMAuthenticationCompletionHandler getCompletionHandlerImpl() {
        return null;
    }

    @Override
    public OMHTTPResponse handleAuthentication(OMAuthenticationRequest authRequest, OMAuthenticationContext authContext) throws OMMobileSecurityException {
        Set<String> requiredCookies = mASM.getMSS().getMobileSecurityConfig().getRequiredTokens();
        Map<String, Object> inputParams = authContext.getInputParams();
        Set<String> visitedUrls = (Set<String>) inputParams
                .get(OMSecurityConstants.Param.VISITED_URLS);
        try {
            Map<String, OMCookie> filteredCookies = OMCookieManager.getInstance().filterCookies(
                    requiredCookies, visitedUrls);
            if (hasNoCookiesOrNoRequiredCookies(filteredCookies, requiredCookies)) {
                /* this can happen due to the cookies requested not matching*/
                onAuthenticationFailed(
                        authContext,
                        "Cookies that are requested are not available from the server.",
                        null);
            }
            boolean parseTokenRelayResponse = mConfig.parseTokenRelayResponse();
            if (parseTokenRelayResponse) {
                String tokenRelayResponse = (String) inputParams
                        .get(OMSecurityConstants.Param.TOKEN_RELAY_RESPONSE);
                processTokenRelayResponse(tokenRelayResponse, authContext, true);
            }

            authContext.setStatus(OMAuthenticationContext.Status.SUCCESS);
            OMAuthenticationContext.AuthenticationMechanism authenticationMechanism = (OMAuthenticationContext.AuthenticationMechanism) inputParams
                    .get(OMSecurityConstants.Param.AUTHENTICATION_MECHANISM);
            if (authenticationMechanism != null) {
                authContext.setAuthenticationMechanism(authenticationMechanism);
            } else {
                authContext
                        .setAuthenticationMechanism(OMAuthenticationContext.AuthenticationMechanism.FEDERATED);
            }
        } finally {
            // AuthentcationProvider is being set here to delete the cookies
            // from CookieManger properly, in case of exceptions
            authContext
                    .setAuthenticationProvider(AuthenticationProvider.FEDERATED);
        }
        return null;
    }

    /**
     * Returns true if no cookies are present at all OR if required cookies are not present.
     */
    private boolean hasNoCookiesOrNoRequiredCookies(Map<String, OMCookie> filteredCookies,
                                                    Set<String> requiredCookies) {
        return (filteredCookies == null ||
                filteredCookies.isEmpty() ||
                (
                        requiredCookies != null &&
                                requiredCookies.size() != 0 &&
                                filteredCookies.size() < requiredCookies.size()
                ));
    }

    @Override
    public void cancel() {
        OMLog.trace(TAG, "cancel");
        if (mAuthCompletionHandler != null) {
            mAuthCompletionHandler.cancel();
        } else {
            OMLog.error(TAG, "Something went wrong. Cannot return control back to app.");
        }
    }

    @Override
    public Type getType() {
        return Type.FED_AUTH_SERVICE;
    }

    public boolean isValid(OMAuthenticationContext authContext, boolean validateOnline) {
        OMLog.info(TAG, "isValid");
        // validateOnline is not needed here as of now.
        if (authContext.getAuthenticationProvider() != AuthenticationProvider.FEDERATED) {
            return true;
        }

        Date sessionExpiry = authContext.getSessionExpiry();
        Date idleTimeExpiry = authContext.getIdleTimeExpiry();
        Date currentTime = Calendar.getInstance().getTime();

        // Non-zero checks for getSessionExpInSecs() and getIdleTimeExpInSecs()
        // added to ignore session/idle time
        // expiry if session/idle timeout value is 0.
        if ((sessionExpiry != null && authContext.getSessionExpInSecs() != 0 && (currentTime
                .after(sessionExpiry) || currentTime.equals(sessionExpiry)))
                || (idleTimeExpiry != null
                && authContext.getIdleTimeExpInSecs() != 0 && (currentTime
                .after(idleTimeExpiry) || currentTime
                .equals(idleTimeExpiry)))) {
            Log.d(TAG + "_isValid", "Idle time or Session time is expired.");
            return false;
        }

        if (authContext.getIdleTimeExpInSecs() > 0 && !authContext.resetIdleTime()) {
            return false;
        }

        if (mConfig.parseTokenRelayResponse()) {
            List<OMToken> tokens = authContext.getTokens(null);
            if (tokens == null || tokens.isEmpty()) {
                return false;
            }
            OAuthToken oAuthToken = (OAuthToken) tokens.get(0);
            if (oAuthToken.isTokenExpired()) {
                return false;
            } else {
                Log.d(TAG, "OAuth token is valid");
            }
        }


        return true;
    }

    @Override
    public void collectLoginChallengeInput(Map<String, Object> inputParams, final ASMInputController controller) {
        OMLog.info(TAG, "collectLoginChallengeInput");
        if (!isChallengeInputRequired(inputParams)) {
            //have all the required inputs lets proceed for authentication
            controller.onInputAvailable(inputParams);
        } else {
            mAuthCompletionHandler.createChallengeRequest(mASM.getMSS(), createLoginChallenge(),
                    new WebViewAuthServiceInputCallbackImpl(mASM, controller));
        }
    }


    @Override
    public void logout(final OMAuthenticationContext authContext, final boolean isDeleteUnPwd, final boolean isDeleteCookies, final boolean isDeleteTokens, final boolean isLogoutCall) {
        if (authContext.getAuthenticationProvider() != AuthenticationProvider.FEDERATED) {
            return;
        }

        if (isLogoutCall) {
            collectLogoutChallengeInput(authContext.getInputParams(), new AuthServiceInputCallback() {
                @Override
                public void onInput(Map<String, Object> inputs) {
                    //Input validation is done in proceed(). Invalid input results in onError being called.
                    OMLog.trace(TAG, "Inside AuthServiceInputCallback#onInput");
                    authContext.getInputParams().putAll(inputs);
                    handleLogout(authContext, isDeleteUnPwd, isDeleteCookies, isDeleteTokens, isLogoutCall);
                }

                @Override
                public void onError(OMErrorCode error) {
                    if (error == OMErrorCode.WEB_VIEW_REQUIRED) {
                        OMLog.info(TAG, "Since Login Webview not supplied, simply clear session cookies and report this back to app");
                        removeSessionCookies();
                        reportLogoutCompleted(mASM.getMSS(), isLogoutCall, OMErrorCode.LOGOUT_URL_NOT_LOADED);
                    }
                }

                @Override
                public void onCancel() {
                /*Cancel of logout DOES NOT happen as we expect the SDK consumer to always return a webview in which logout url can be loaded.
                  But, to cover the negative scenario of logout cancel, we clear all session cookies. This is because logout can be initiated on
                  idle/session timeout. This should make authContext invalid. If we do not clear cookies just because the webview is not returned by SDK consumer,
                  the next authentication MAY NOT show the login screen as the cookies might still be valid. */

                    removeSessionCookies();
                    reportLogoutCompleted(mASM.getMSS(), true, OMErrorCode.LOGOUT_URL_NOT_LOADED);
                }
            });
        } else {
            /*
             * This will be executed when 1. idle timeout or session timeout
             * happens 2. authentication fails because of required tokens, not
             * being present, OAuth access token not being present in case of
             * token relay with SIM.
             *
             * Hence, logout callback is not invoked.
             */
            removeSessionCookies();
        }
    }

    @Override
    public void collectLogoutChallengeInput(Map<String, Object> inputParams, final AuthServiceInputCallback callback) {
        mLogoutCompletionHandler.createLogoutChallengeRequest(mASM.getMSS(), createLogoutChallenge(), callback);
    }


    @Override
    public void handleLogout(final OMAuthenticationContext authContext, boolean isDeleteUnPwd, boolean isDeleteCookies, boolean isDeleteToken, final boolean isLogoutCall) {
        OMLog.trace(TAG, "Inside handleLogout");
        final URL logoutUrl = mASM.getMSS().getMobileSecurityConfig().getLogoutUrl();
        final Handler handler = ((FedAuthLogoutCompletionHandler) mLogoutCompletionHandler).getAppCallback().getHandler();
        if (handler != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    WebView webView = (WebView) authContext.getInputParams().get(OMSecurityConstants.Challenge.WEBVIEW_KEY);
                    WebViewClient appWebViewClient = (WebViewClient) authContext.getInputParams().get(OMSecurityConstants.Challenge.WEBVIEW_CLIENT_KEY);
                    loadLogoutURL(webView, new LogoutWebViewClient(webView, appWebViewClient, mASM.getMSS(), handler,
                            mConfig, authContext.getLogoutTimeout(), isLogoutCall), logoutUrl.toString());
                }
            });
        } else {
            removeSessionCookies();
            reportLogoutCompleted(mASM.getMSS(), isLogoutCall, OMErrorCode.LOGOUT_URL_NOT_LOADED);
        }
    }

    private OMAuthenticationChallenge createCommonChallenge() {
        OMAuthenticationChallenge challenge = new OMAuthenticationChallenge(OMAuthenticationChallengeType.EMBEDDED_WEBVIEW_REQUIRED);
        updateChallengeWithException(challenge);
        return challenge;
    }

    /**
     * Parses tokenRelayResponse as {@link OAuthToken}. Fetches XSRF Token
     * by accessing anti-CSRF endpoint if parameter "retryWithXSRFToken"
     * is true.
     */
    private void processTokenRelayResponse(String tokenRelayResponse,
                                           OMAuthenticationContext authContext,
                                           boolean retryWithXSRFToken) throws OMMobileSecurityException {
        if (OMSecurityConstants.DEBUG) {
            OMLog.debug(TAG, "tokenRelayResponse obtained: " + tokenRelayResponse);
        }
        if (TextUtils.isEmpty(tokenRelayResponse)) {
            //Normally, this does not happen with rel-13 server. It is just defensive approach.
            tokenRelayResponse = fetchAccessTokenWithXSRFToken(authContext);
        }
        try {
            OAuthToken oAuthToken = new OAuthToken(tokenRelayResponse);
            List<OAuthToken> oauthTokenList = new ArrayList<>();
            oauthTokenList.add(oAuthToken);
            authContext.setOAuthTokenList(oauthTokenList);
            OMLog.debug(TAG,
                    "Token Relay Response has a valid access token. It is parsed & set in authContext.");
        } catch (JSONException e) {
            /* This happens with rel-13 server as it returns 401 error in html format.
             * So, SDK accesses anti-csrf endpoint below. */
            OMLog.debug(TAG, "Error parsing response. " + e.getMessage()
                    + " retryWithXSRFToken = " + retryWithXSRFToken);
            if (retryWithXSRFToken) {
                tokenRelayResponse = fetchAccessTokenWithXSRFToken(authContext);
                processTokenRelayResponse(tokenRelayResponse, authContext, false);
            } else {
                onAuthenticationFailed(authContext,
                        "Token Relay Response does not have valid access token.", e);
            }
        }
    }

    private String fetchAccessTokenWithXSRFToken(OMAuthenticationContext authContext)
            throws OMMobileSecurityException {
        String tokenRelayResponse = null;
        try {
            OMConnectionHandler connectionHandler = mASM.getMSS().getConnectionHandler();
            URL tokenRelay = getTokenRelayUrl(mConfig.getLoginSuccessUrl());
            URL antiCsrf = getAntiCsrfUrl(mConfig.getLoginSuccessUrl());
            OMHTTPResponse antiCsrfResponse = connectionHandler.httpGet(antiCsrf, null);
            if (TextUtils.isEmpty(antiCsrfResponse.getResponseStringOnSuccess())) {
                onAuthenticationFailed(authContext, "Anti-CSRF response is empty.", null);
            }
            JSONObject xsrfJsonObject = new JSONObject(antiCsrfResponse.getResponseStringOnSuccess());
            String xsrfValue = xsrfJsonObject.getString(XSRF_TOKEN_RESPONSE_JSON_KEY);
            Map<String, String> headers = new HashMap<>();
            headers.put(XSRF_TOKEN_REQUEST_HEADER, xsrfValue);
            // Cookie XSRF-TOKEN will also be sent by below call in addition to the above header
            OMHTTPResponse tokenRelayOMHttpResponse = connectionHandler.httpGet(tokenRelay, headers);
            tokenRelayResponse = tokenRelayOMHttpResponse.getResponseStringOnSuccess();
        } catch (MalformedURLException e) {
            OMLog.error(TAG, e.getMessage(), e);
        } catch (JSONException e) {
            OMLog.error(TAG, e.getMessage(), e);
        }
        if (TextUtils.isEmpty(tokenRelayResponse)) {
            onAuthenticationFailed(authContext, "Token Relay Response could not obtained.", null);
        }
        return tokenRelayResponse;
    }

    private URL getTokenRelayUrl(URL url) throws MalformedURLException {
        String tokenRelayUrl = getBaseUrl(url) + TOKEN_RELAY_PATH;
        return new URL(tokenRelayUrl);
    }

    private URL getAntiCsrfUrl(URL url) throws MalformedURLException {
        String antiCsrfUrl = getBaseUrl(url) + ANTI_CSRF_PATH;
        return new URL(antiCsrfUrl);
    }

    private String getBaseUrl(URL url) {
        String baseUrl = url.getProtocol() + "://" + url.getHost();
        if (url.getPort() != -1) {
            baseUrl = baseUrl + ":" + url.getPort();
        }
        return baseUrl;
    }

    private void onAuthenticationFailed(OMAuthenticationContext authContext,
                                        String errorMessage, Throwable tr) throws OMMobileSecurityException {
        /* AuthenticationProvider is being set here to delete the cookies
         from CookieManger properly.*/
        authContext.setAuthenticationProvider(AuthenticationProvider.FEDERATED);
        authContext.setStatus(OMAuthenticationContext.Status.FAILURE);
        OMLog.error(TAG, errorMessage, tr);
        throw new OMMobileSecurityException(OMErrorCode.AUTHENTICATION_FAILED);
    }

}
