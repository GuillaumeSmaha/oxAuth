/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.session.ws.rs;

import com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.xdi.model.security.Identity;
import org.xdi.oxauth.audit.ApplicationAuditLogger;
import org.xdi.oxauth.model.audit.Action;
import org.xdi.oxauth.model.audit.OAuth2AuditLog;
import org.xdi.oxauth.model.authorize.AuthorizeRequestParam;
import org.xdi.oxauth.model.common.AuthorizationGrant;
import org.xdi.oxauth.model.common.AuthorizationGrantList;
import org.xdi.oxauth.model.common.SessionState;
import org.xdi.oxauth.model.config.Constants;
import org.xdi.oxauth.model.configuration.AppConfiguration;
import org.xdi.oxauth.model.error.ErrorResponseFactory;
import org.xdi.oxauth.model.registration.Client;
import org.xdi.oxauth.model.session.EndSessionErrorResponseType;
import org.xdi.oxauth.model.session.EndSessionParamsValidator;
import org.xdi.oxauth.model.util.Util;
import org.xdi.oxauth.service.ClientService;
import org.xdi.oxauth.service.GrantService;
import org.xdi.oxauth.service.RedirectionUriService;
import org.xdi.oxauth.service.SessionStateService;
import org.xdi.oxauth.service.external.ExternalApplicationSessionService;
import org.xdi.oxauth.util.ServerUtil;
import org.xdi.util.Pair;
import org.xdi.util.StringHelper;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.util.Set;

/**
 * @author Javier Rojas Blum
 * @author Yuriy Movchan
 * @author Yuriy Zabrovarnyy
 * @version December 15, 2015
 */
@Path("/")
public class EndSessionRestWebServiceImpl implements EndSessionRestWebService {

    @Inject
    private Logger log;

    @Inject
    private ErrorResponseFactory errorResponseFactory;

    @Inject
    private RedirectionUriService redirectionUriService;

    @Inject
    private AuthorizationGrantList authorizationGrantList;

    @Inject
    private ExternalApplicationSessionService externalApplicationSessionService;

    @Inject
    private SessionStateService sessionStateService;

    @Inject
    private ClientService clientService;

    @Inject
    private GrantService grantService;

    @Inject
    private Identity identity;

    @Inject
    private ApplicationAuditLogger applicationAuditLogger;

    @Inject
    private AppConfiguration appConfiguration;

    @Override
    public Response requestEndSession(String idTokenHint, String postLogoutRedirectUri, String state, String sessionState,
                                      HttpServletRequest httpRequest, HttpServletResponse httpResponse, SecurityContext sec) {

        log.debug("Attempting to end session, idTokenHint: {}, postLogoutRedirectUri: {}, sessionState: {}, Is Secure = {}",
                idTokenHint, postLogoutRedirectUri, sessionState, sec.isSecure());

        EndSessionParamsValidator.validateParams(idTokenHint, sessionState, errorResponseFactory);

        final Pair<SessionState, AuthorizationGrant> pair = endSession(idTokenHint, sessionState, httpRequest, httpResponse, sec);

        auditLogging(httpRequest, pair);

        return httpBased(postLogoutRedirectUri, state, pair);
    }


    public Response httpBased(String postLogoutRedirectUri, String state, Pair<SessionState, AuthorizationGrant> pair) {
        SessionState sessionState = pair.getFirst();
        AuthorizationGrant authorizationGrant = pair.getSecond();

        // Validate redirectUri
        String redirectUri;
        if (authorizationGrant == null) {
        	redirectUri = redirectionUriService.validatePostLogoutRedirectUri(sessionState, postLogoutRedirectUri);
        } else {
        	redirectUri = redirectionUriService.validatePostLogoutRedirectUri(authorizationGrant.getClient().getClientId(), postLogoutRedirectUri);
        }

        final Set<String> frontchannelLogoutUris = getRpFrontchannelLogoutUris(pair);
        final String html = constructPage(frontchannelLogoutUris, redirectUri, state);
        log.debug("Constructed http logout page: " + html);
        return Response.ok().
                cacheControl(ServerUtil.cacheControl(true, true)).
                header("Pragma", "no-cache").
                type(MediaType.TEXT_HTML_TYPE).entity(html).
                build();
    }

    private Pair<SessionState, AuthorizationGrant> endSession(String idTokenHint, String sessionState,
                                                              HttpServletRequest httpRequest, HttpServletResponse httpResponse, SecurityContext sec) {
        AuthorizationGrant authorizationGrant = authorizationGrantList.getAuthorizationGrantByIdToken(idTokenHint);
        if (authorizationGrant == null) {
        	Boolean endSessionWithAccessToken = appConfiguration.getEndSessionWithAccessToken();
        	if ((endSessionWithAccessToken != null) && endSessionWithAccessToken) {
        		authorizationGrant = authorizationGrantList.getAuthorizationGrantByAccessToken(idTokenHint);
        	}
        }

        SessionState ldapSessionState = removeSessionState(sessionState, httpRequest, httpResponse);
        if ((authorizationGrant == null) && (ldapSessionState == null)) {
            log.info("Failed to find out authorization grant for id_token_hint '{}' and session_state '{}'", idTokenHint, sessionState);
            errorResponseFactory.throwUnauthorizedException(EndSessionErrorResponseType.INVALID_GRANT_AND_SESSION);
        }

        boolean isExternalLogoutPresent;
        boolean externalLogoutResult = false;

        isExternalLogoutPresent = externalApplicationSessionService.isEnabled();
        if (isExternalLogoutPresent && (ldapSessionState != null)) {
        	String userName = ldapSessionState.getSessionAttributes().get(Constants.AUTHENTICATED_USER);
            externalLogoutResult = externalApplicationSessionService.executeExternalEndSessionMethods(httpRequest, ldapSessionState);
            log.info("End session result for '{}': '{}'", userName, "logout", externalLogoutResult);
        }

        boolean isGrantAndExternalLogoutSuccessful = isExternalLogoutPresent && externalLogoutResult;
        if (isExternalLogoutPresent && !isGrantAndExternalLogoutSuccessful) {
            errorResponseFactory.throwUnauthorizedException(EndSessionErrorResponseType.INVALID_GRANT);
        }

        if (ldapSessionState != null) {
            grantService.removeAllTokensBySession(ldapSessionState.getDn());
        }

        if (identity != null) {
            identity.logout();
        }

        return new Pair<SessionState, AuthorizationGrant>(ldapSessionState, authorizationGrant);
    }

    private Set<String> getRpFrontchannelLogoutUris(Pair<SessionState, AuthorizationGrant> pair) {
        final Set<String> result = Sets.newHashSet();

        SessionState sessionState = pair.getFirst();
        AuthorizationGrant authorizationGrant = pair.getSecond();
        if (sessionState == null) {
            log.error("session_state is not passed to endpoint (as cookie or manually). Therefore unable to match clients for session_state." +
                    "Http based html will contain no iframes.");
            return result;
        }

        final Set<Client> clientsByDns = sessionState.getPermissionGrantedMap() != null ?
                clientService.getClient(sessionState.getPermissionGrantedMap().getClientIds(true), true) :
                Sets.<Client>newHashSet();
        if (authorizationGrant != null) {
        	clientsByDns.add(authorizationGrant.getClient());
        }

        for (Client client : clientsByDns) {
            String[] logoutUris = client.getFrontChannelLogoutUri();

            if (logoutUris == null) {
                continue;
            }

            for (String logoutUri : logoutUris) {
                if (Util.isNullOrEmpty(logoutUri)) {
                    continue; // skip client if logout_uri is blank
                }

                if (client.getFrontChannelLogoutSessionRequired() != null && client.getFrontChannelLogoutSessionRequired()) {
                    if (logoutUri.contains("?")) {
                        logoutUri = logoutUri + "&sid=" + sessionState.getId();
                    } else {
                        logoutUri = logoutUri + "?sid=" + sessionState.getId();
                    }
                }
                result.add(logoutUri);
            }
        }
        return result;
    }

    private SessionState removeSessionState(String sessionState, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        SessionState ldapSessionState = null;

        try {
            String id = sessionState;
            if (StringHelper.isEmpty(id)) {
                id = sessionStateService.getSessionStateFromCookie(httpRequest);
            }

            if (StringHelper.isNotEmpty(id)) {
                ldapSessionState = sessionStateService.getSessionState(id);
                if (ldapSessionState != null) {
                    boolean result = sessionStateService.remove(ldapSessionState);
                    if (!result) {
                        log.error("Failed to remove session_state '{}' from LDAP", id);
                    }
                } else {
                    log.error("Failed to load session from LDAP by session_state: '{}'", id);
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            sessionStateService.removeSessionStateCookie(httpResponse);
        }
        return ldapSessionState;
    }

    private String constructPage(Set<String> logoutUris, String postLogoutUrl, String state) {
        String iframes = "";
        for (String logoutUri : logoutUris) {
            iframes = iframes + String.format("<iframe height=\"0\" width=\"0\" src=\"%s\"></iframe>", logoutUri);
        }

        String html = "<!DOCTYPE html>" +
                "<html>" +
                "<head>";

        if (!Util.isNullOrEmpty(postLogoutUrl)) {

            if (!Util.isNullOrEmpty(state)) {
                if (postLogoutUrl.contains("?")) {
                    postLogoutUrl += "&state=" + state;
                } else {
                    postLogoutUrl += "?state=" + state;
                }
            }

            html += "<script>" +
                    "window.onload=function() {" +
                    "window.location='" + postLogoutUrl + "'" +
                    "}" +
                    "</script>";
        }

        html += "<title>Gluu Generated logout page</title>" +
                "</head>" +
                "<body>" +
                "Logout requests sent.<br/>" +
                iframes +
                "</body>" +
                "</html>";
        return html;
    }

    private void auditLogging(HttpServletRequest request, Pair<SessionState, AuthorizationGrant> pair){
    	SessionState sessionState = pair.getFirst();
    	AuthorizationGrant authorizationGrant = pair.getSecond();

    	OAuth2AuditLog oAuth2AuditLog = new OAuth2AuditLog(ServerUtil.getIpAddress(request), Action.SESSION_DESTROYED);
        oAuth2AuditLog.setSuccess(true);
        
        if (authorizationGrant != null) {
	        oAuth2AuditLog.setClientId(authorizationGrant.getClientId());
	        oAuth2AuditLog.setScope(StringUtils.join(authorizationGrant.getScopes(), " "));
	        oAuth2AuditLog.setUsername(authorizationGrant.getUserId());
        } else {
	        oAuth2AuditLog.setClientId(sessionState.getPermissionGrantedMap().getClientIds(true).toString());
	        oAuth2AuditLog.setScope(sessionState.getSessionAttributes().get(AuthorizeRequestParam.SCOPE));
	        oAuth2AuditLog.setUsername(sessionState.getUserDn());
        }

        applicationAuditLogger.sendMessage(oAuth2AuditLog);
    }
}