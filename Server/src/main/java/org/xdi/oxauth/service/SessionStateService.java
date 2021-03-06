/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.service;

import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import org.apache.commons.lang.StringUtils;
import org.gluu.site.ldap.persistence.exception.EmptyEntryPersistenceException;
import org.gluu.site.ldap.persistence.exception.EntryPersistenceException;
import org.slf4j.Logger;
import org.xdi.oxauth.audit.ApplicationAuditLogger;
import org.xdi.oxauth.model.audit.Action;
import org.xdi.oxauth.model.audit.OAuth2AuditLog;
import org.xdi.oxauth.model.common.Prompt;
import org.xdi.oxauth.model.common.SessionIdState;
import org.xdi.oxauth.model.common.SessionState;
import org.xdi.oxauth.model.config.StaticConfiguration;
import org.xdi.oxauth.model.config.WebKeysConfiguration;
import org.xdi.oxauth.model.configuration.AppConfiguration;
import org.xdi.oxauth.model.crypto.signature.SignatureAlgorithm;
import org.xdi.oxauth.model.exception.AcrChangedException;
import org.xdi.oxauth.model.jwt.Jwt;
import org.xdi.oxauth.model.jwt.JwtClaimName;
import org.xdi.oxauth.model.jwt.JwtSubClaimObject;
import org.xdi.oxauth.model.token.JwtSigner;
import org.xdi.oxauth.model.util.Util;
import org.xdi.oxauth.service.external.ExternalAuthenticationService;
import org.xdi.oxauth.util.ServerUtil;
import org.xdi.service.CacheService;
import org.xdi.util.StringHelper;

import javax.ejb.Stateless;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * @author Yuriy Zabrovarnyy
 * @author Yuriy Movchan
 * @author Javier Rojas Blum
 * @version December 29, 2016
 */

@Stateless
@Named
public class SessionStateService {

    public static final String SESSION_STATE_COOKIE_NAME = "session_state";
    public static final String UMA_SESSION_STATE_COOKIE_NAME = "uma_session_state";
    public static final String SESSION_CUSTOM_STATE = "session_custom_state";

    @Inject
    private Logger log;

    @Inject
    private ExternalAuthenticationService externalAuthenticationService;

    @Inject
    private ApplicationAuditLogger applicationAuditLogger;

    @Inject
    private AppConfiguration appConfiguration;

    @Inject
    private StaticConfiguration staticConfiguration;

    @Inject
    private WebKeysConfiguration webKeysConfiguration;

    @Inject
    private FacesContext facesContext;

    @Inject
    private ExternalContext externalContext;

    @Inject
    private CacheService cacheService;

    public String getAcr(SessionState session) {
        if (session == null || session.getSessionAttributes() == null) {
            return null;
        }

        String acr = session.getSessionAttributes().get(JwtClaimName.AUTHENTICATION_CONTEXT_CLASS_REFERENCE);
        if (StringUtils.isBlank(acr)) {
            acr = session.getSessionAttributes().get("acr_values");
        }
        return acr;
    }

    // #34 - update session attributes with each request
    // 1) redirect_uri change -> update session
    // 2) acr change -> throw acr change exception
    // 3) client_id change -> do nothing
    // https://github.com/GluuFederation/oxAuth/issues/34
    public SessionState assertAuthenticatedSessionCorrespondsToNewRequest(SessionState session, String acrValuesStr) throws AcrChangedException {
        if (session != null && !session.getSessionAttributes().isEmpty() && session.getState() == SessionIdState.AUTHENTICATED) {

            final Map<String, String> sessionAttributes = session.getSessionAttributes();

            String sessionAcr = getAcr(session);

            if (StringUtils.isBlank(sessionAcr)) {
                log.error("Failed to fetch acr from session, attributes: " + sessionAttributes);
                return session;
            }

            boolean isAcrChanged = acrValuesStr != null && !acrValuesStr.equals(sessionAcr);
            if (isAcrChanged) {
                Map<String, Integer> acrToLevel = externalAuthenticationService.acrToLevelMapping();
                Integer sessionAcrLevel = acrToLevel.get(sessionAcr);
                Integer currentAcrLevel = acrToLevel.get(acrValuesStr);

                log.info("Acr is changed. Session acr: " + sessionAcr + "(level: " + sessionAcrLevel + "), " +
                        "current acr: " + acrValuesStr + "(level: " + currentAcrLevel + ")");
                if (sessionAcrLevel < currentAcrLevel) {
                    throw new AcrChangedException();
                } else { // https://github.com/GluuFederation/oxAuth/issues/291
                    return session; // we don't want to reinit login because we have stronger acr (avoid overriding)
                }
            }

            reinitLogin(session, false);
        }
        return session;
    }

    public void reinitLogin(SessionState session, boolean force) {
        final Map<String, String> sessionAttributes = session.getSessionAttributes();
        final Map<String, String> currentSessionAttributes = getCurrentSessionAttributes(sessionAttributes);
        if (force || !currentSessionAttributes.equals(sessionAttributes)) {
            sessionAttributes.putAll(currentSessionAttributes);

            // Reinit login
            sessionAttributes.put("c", "1");

            for (Iterator<Entry<String, String>> it = currentSessionAttributes.entrySet().iterator(); it.hasNext(); ) {
                Entry<String, String> currentSessionAttributesEntry = it.next();
                String name = currentSessionAttributesEntry.getKey();
                if (name.startsWith("auth_step_passed_")) {
                    it.remove();
                }
            }

            session.setSessionAttributes(currentSessionAttributes);

            boolean updateResult = updateSessionState(session, true, true, true);
            if (!updateResult) {
                log.debug("Failed to update session entry: '{}'", session.getId());
            }
        }
    }

    public void resetToStep(SessionState session, int resetToStep) {
        final Map<String, String> sessionAttributes = session.getSessionAttributes();

        int currentStep = 1;
        if (sessionAttributes.containsKey("auth_step")) {
            currentStep = StringHelper.toInteger(sessionAttributes.get("auth_step"), currentStep);
        }

        for (int i = resetToStep; i <= currentStep; i++) {
            String key = String.format("auth_step_passed_%d", i);
            sessionAttributes.remove(key);
        }

        sessionAttributes.put("auth_step", String.valueOf(resetToStep));

        boolean updateResult = updateSessionState(session, true, true, true);
        if (!updateResult) {
            log.debug("Failed to update session entry: '{}'", session.getId());
        }
    }

    private Map<String, String> getCurrentSessionAttributes(Map<String, String> sessionAttributes) {
        // Update from request
        if (facesContext != null) {
            // Clone before replacing new attributes
            final Map<String, String> currentSessionAttributes = new HashMap<String, String>(sessionAttributes);

            Map<String, String> parameterMap = externalContext.getRequestParameterMap();
            Map<String, String> newRequestParameterMap = AuthenticationService.getAllowedParameters(parameterMap);
            for (Entry<String, String> newRequestParameterMapEntry : newRequestParameterMap.entrySet()) {
                String name = newRequestParameterMapEntry.getKey();
                if (!StringHelper.equalsIgnoreCase(name, "auth_step")) {
                    currentSessionAttributes.put(name, newRequestParameterMapEntry.getValue());
                }
            }

            return currentSessionAttributes;
        } else {
            return sessionAttributes;
        }
    }

    public String getSessionStateFromCookie(HttpServletRequest request) {
        return getSessionStateFromCookie(request, SESSION_STATE_COOKIE_NAME);
    }

    public String getUmaSessionStateFromCookie(HttpServletRequest request) {
        return getSessionStateFromCookie(request, UMA_SESSION_STATE_COOKIE_NAME);
    }

    public String getSessionStateFromCookie(HttpServletRequest request, String cookieName) {
        try {
            final Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (cookie.getName().equals(cookieName) /*&& cookie.getSecure()*/) {
                        log.trace("Found session_state cookie: '{}'", cookie.getValue());
                        return cookie.getValue();
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return "";
    }

    public String getSessionStateFromCookie() {
        try {
            if (facesContext == null) {
                return null;
            }
            final HttpServletRequest request = (HttpServletRequest) externalContext.getRequest();
            return getSessionStateFromCookie(request);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        return null;
    }

    public void createSessionStateCookie(String sessionState, HttpServletResponse httpResponse, boolean isUma) {
        // Create the special cookie header with secure flag but not HttpOnly because the session_state
        // needs to be read from the OP iframe using JavaScript
        String cookieName = isUma ? UMA_SESSION_STATE_COOKIE_NAME : SESSION_STATE_COOKIE_NAME;
        String header = cookieName + "=" + sessionState;
        header += "; Path=/";
        header += "; Secure";

        if (appConfiguration.getSessionStateHttpOnly()) {
            header += "; HttpOnly";
        }

        Integer sessionStateLifetime = appConfiguration.getSessionStateLifetime();
        if (sessionStateLifetime != null) {
            DateFormat formatter = new SimpleDateFormat("E, dd MMM yyyy HH:mm:ss Z");
            Calendar expirationDate = Calendar.getInstance();
            expirationDate.add(Calendar.SECOND, sessionStateLifetime);
            header += "; Expires=" + formatter.format(expirationDate.getTime()) + ";";
        }

        httpResponse.addHeader("Set-Cookie", header);
    }

    public void createSessionStateCookie(String sessionState, boolean isUma) {
        try {
            final Object response = externalContext.getResponse();
            if (response instanceof HttpServletResponse) {
                final HttpServletResponse httpResponse = (HttpServletResponse) response;

                createSessionStateCookie(sessionState, httpResponse, isUma);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public void removeSessionStateCookie(HttpServletResponse httpResponse) {
        final Cookie cookie = new Cookie(SESSION_STATE_COOKIE_NAME, null); // Not necessary, but saves bandwidth.
        cookie.setPath("/");
        cookie.setMaxAge(0); // Don't set to -1 or it will become a session cookie!
        httpResponse.addCookie(cookie);
    }

    public void removeUmaSessionStateCookie(HttpServletResponse httpResponse) {
        final Cookie cookie = new Cookie(UMA_SESSION_STATE_COOKIE_NAME, null); // Not necessary, but saves bandwidth.
        cookie.setPath("/");
        cookie.setMaxAge(0); // Don't set to -1 or it will become a session cookie!
        httpResponse.addCookie(cookie);
    }

    public SessionState getSessionState() {
        String sessionState = getSessionStateFromCookie();

        if (StringHelper.isNotEmpty(sessionState)) {
            return getSessionState(sessionState);
        }

        return null;
    }

    public Map<String, String> getSessionAttributes(SessionState sessionState) {
        if (sessionState != null) {
            return sessionState.getSessionAttributes();
        }

        return null;
    }

    public SessionState generateAuthenticatedSessionState(String userDn) {
        return generateAuthenticatedSessionState(userDn, "");
    }

    public SessionState generateAuthenticatedSessionState(String userDn, String prompt) {
        Map<String, String> sessionIdAttributes = new HashMap<String, String>();
        sessionIdAttributes.put("prompt", prompt);

        return generateSessionState(userDn, new Date(), SessionIdState.AUTHENTICATED, sessionIdAttributes, true);
    }

    public SessionState generateAuthenticatedSessionState(String userDn, Map<String, String> sessionIdAttributes) {
        return generateSessionState(userDn, new Date(), SessionIdState.AUTHENTICATED, sessionIdAttributes, true);
    }

    public SessionState generateUnauthenticatedSessionState(String userDn, Date authenticationDate, SessionIdState state, Map<String, String> sessionIdAttributes, boolean persist) {
        return generateSessionState(userDn, authenticationDate, state, sessionIdAttributes, persist);
    }

    private SessionState generateSessionState(String userDn, Date authenticationDate, SessionIdState state, Map<String, String> sessionIdAttributes, boolean persist) {
        final String uuid = UUID.randomUUID().toString();
        final String dn = dn(uuid);

        if (StringUtils.isBlank(dn)) {
            return null;
        }

        if (SessionIdState.AUTHENTICATED == state) {
            if (StringUtils.isBlank(userDn)) {
                return null;
            }
        }

        final SessionState sessionState = new SessionState();
        sessionState.setId(uuid);
        sessionState.setDn(dn);
        sessionState.setUserDn(userDn);

        Boolean sessionAsJwt = appConfiguration.getSessionAsJwt();
        sessionState.setIsJwt(sessionAsJwt != null && sessionAsJwt);

        if (authenticationDate != null) {
            sessionState.setAuthenticationTime(authenticationDate);
        }

        if (state != null) {
            sessionState.setState(state);
        }

        sessionState.setSessionAttributes(sessionIdAttributes);
    	sessionState.setLastUsedAt(new Date());

        if (sessionState.getIsJwt()) {
            sessionState.setJwt(generateJwt(sessionState, userDn).asString());
        }

        boolean persisted = false;
        if (persist) {
            persisted = persistSessionState(sessionState);
        }

        auditLogging(sessionState);

        log.trace("Generated new session, id = '{}', state = '{}', asJwt = '{}', persisted = '{}'", sessionState.getId(), sessionState.getState(), sessionState.getIsJwt(), persisted);
        return sessionState;
    }

    private Jwt generateJwt(SessionState sessionState, String audience) {
        try {
            JwtSigner jwtSigner = new JwtSigner(appConfiguration, webKeysConfiguration, SignatureAlgorithm.RS512, audience);
            Jwt jwt = jwtSigner.newJwt();

            // claims
            jwt.getClaims().setClaim("id", sessionState.getId());
            jwt.getClaims().setClaim("authentication_time", sessionState.getAuthenticationTime());
            jwt.getClaims().setClaim("user_dn", sessionState.getUserDn());
            jwt.getClaims().setClaim("state", sessionState.getState() != null ?
                    sessionState.getState().getValue() : "");

            jwt.getClaims().setClaim("session_attributes", JwtSubClaimObject.fromMap(sessionState.getSessionAttributes()));

            jwt.getClaims().setClaim("last_used_at", sessionState.getLastUsedAt());
            jwt.getClaims().setClaim("permission_granted", sessionState.getPermissionGranted());
            jwt.getClaims().setClaim("permission_granted_map", JwtSubClaimObject.fromBooleanMap(sessionState.getPermissionGrantedMap().getPermissionGranted()));
            jwt.getClaims().setClaim("involved_clients_map", JwtSubClaimObject.fromBooleanMap(sessionState.getInvolvedClients().getPermissionGranted()));

            // sign
            return jwtSigner.sign();
        } catch (Exception e) {
            log.error("Failed to sign session jwt! " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public SessionState setSessionStateAuthenticated(SessionState sessionState, String p_userDn) {
        sessionState.setUserDn(p_userDn);
        sessionState.setAuthenticationTime(new Date());
        sessionState.setState(SessionIdState.AUTHENTICATED);

        boolean persisted = updateSessionState(sessionState, true, true, true);

        auditLogging(sessionState);
        log.trace("Authenticated session, id = '{}', state = '{}', persisted = '{}'", sessionState.getId(), sessionState.getState(), persisted);
        return sessionState;
    }

    public boolean persistSessionState(final SessionState sessionState) {
        return persistSessionState(sessionState, false);
    }

    public boolean persistSessionState(final SessionState sessionState, boolean forcePersistence) {
        List<Prompt> prompts = getPromptsFromSessionState(sessionState);

        try {
            final int unusedLifetime = appConfiguration.getSessionIdUnusedLifetime();
            if ((unusedLifetime > 0 && isPersisted(prompts)) || forcePersistence) {
                sessionState.setLastUsedAt(new Date());

                sessionState.setPersisted(true);
                log.trace("sessionStateAttributes: " + sessionState.getPermissionGrantedMap());
                putInCache(sessionState);
                return true;
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        return false;
    }

    public boolean updateSessionState(final SessionState sessionState) {
        return updateSessionState(sessionState, true);
    }

    public boolean updateSessionState(final SessionState sessionState, boolean updateLastUsedAt) {
        return updateSessionState(sessionState, updateLastUsedAt, false, true);
    }

    public boolean updateSessionState(final SessionState sessionState, boolean updateLastUsedAt, boolean forceUpdate, boolean modified) {
        List<Prompt> prompts = getPromptsFromSessionState(sessionState);

        try {
            final int unusedLifetime = appConfiguration.getSessionIdUnusedLifetime();
            if ((unusedLifetime > 0 && isPersisted(prompts)) || forceUpdate) {
            	boolean update = modified;

            	if (updateLastUsedAt) {
            		Date lastUsedAt = new Date();
            		if (sessionState.getLastUsedAt() != null) {
                        long diff = lastUsedAt.getTime() - sessionState.getLastUsedAt().getTime();
                        if (diff > 500) { // update only if diff is more than 500ms
                            update = true;
                            sessionState.setLastUsedAt(lastUsedAt);
                        }
            		} else {
                        update = true;
                        sessionState.setLastUsedAt(lastUsedAt);
                    }
                }

            	if (!sessionState.isPersisted()) {
            		update = true;
            		sessionState.setPersisted(true);
            	}

                if (sessionState.getAuthenticationTime() != null) {
                    final long currentLifetimeInSeconds = (System.currentTimeMillis() - sessionState.getAuthenticationTime().getTime()) / 1000;
                    if (appConfiguration.getSessionStateLifetime() != null) {
                        if (currentLifetimeInSeconds > appConfiguration.getSessionStateLifetime()) {
                            log.debug("Session state expired: {}, remove it.", sessionState.getId());
                            remove(sessionState); // expired
                            update = false;
                        }
                    } else {
                        log.error("Session state lifetime configuration is null.");
                    }
                }

            	if (update) {
            		try {
						mergeWithRetry(sessionState, 3);
					} catch (EmptyEntryPersistenceException ex) {
						log.warn("Failed to update session entry '{}': '{}'", sessionState.getId(), ex.getMessage());
					}
            	}
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return false;
        }

        return true;
    }

    private void putInCache(SessionState sessionState) {
        int expirationInSeconds = sessionState.getState() == SessionIdState.UNAUTHENTICATED ?
                appConfiguration.getSessionIdUnauthenticatedUnusedLifetime() :
                appConfiguration.getSessionStateLifetime();
        cacheService.put(Integer.toString(expirationInSeconds), sessionState.getId(), sessionState); // first parameter is expiration instead of region for memcached
    }

    private SessionState getFromCache(String sessionId) {
        return (SessionState) cacheService.get(null, sessionId);
    }

	private SessionState mergeWithRetry(final SessionState sessionState, int maxAttempts) {
		EntryPersistenceException lastException = null;
		for (int i = 1; i <= maxAttempts; i++) {
			try {
                putInCache(sessionState);
				return sessionState;
			} catch (EntryPersistenceException ex) {
				lastException = ex;
				if (ex.getCause() instanceof LDAPException) {
					LDAPException parentEx = ((LDAPException) ex.getCause());
					log.debug("LDAP exception resultCode: '{}'", parentEx.getResultCode().intValue());
					if ((parentEx.getResultCode().intValue() == ResultCode.NO_SUCH_ATTRIBUTE_INT_VALUE) ||
						(parentEx.getResultCode().intValue() == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS_INT_VALUE)) {
						log.warn("Session entry update attempt '{}' was unsuccessfull", i);
						continue;
					}
				}

				throw ex;
			}
		}

        log.error("Session entry update attempt was unsuccessfull after '{}' attempts", maxAttempts);
		throw lastException;
	}

	public void updateSessionStateIfNeeded(SessionState sessionState, boolean modified) {
		updateSessionState(sessionState, true, false, modified);
	}

    private boolean isPersisted(List<Prompt> prompts) {
        if (prompts != null && prompts.contains(Prompt.NONE)) {
            final Boolean persistOnPromptNone = appConfiguration.getSessionIdPersistOnPromptNone();
            return persistOnPromptNone != null && persistOnPromptNone;
        }
        return true;
    }

    private String dn(String p_id) {
        final String baseDn = getBaseDn();
        final StringBuilder sb = new StringBuilder();
        if (Util.allNotBlank(p_id, getBaseDn())) {
            sb.append("oxAuthSessionId=").append(p_id).append(",").append(baseDn);
        }
        return sb.toString();
    }

    public SessionState getSessionById(String sessionId) {
        return getFromCache(sessionId);
    }

    public SessionState getSessionState(String sessionState) {
        if (StringHelper.isEmpty(sessionState)) {
            return null;
        }

        try {
            final SessionState entity = getSessionById(sessionState);
            log.trace("Try to get session by id: {} ...", sessionState);
            if (entity != null) {
                log.trace("Session dn: {}", entity.getDn());

                if (isSessionValid(entity)) {
                    return entity;
                }
            }
        } catch (Exception ex) {
            log.trace(ex.getMessage(), ex);
        }

        log.trace("Failed to get session by id: {}", sessionState);
        return null;
    }

    private String getBaseDn() {
        return staticConfiguration.getBaseDn().getSessionId();
    }

    public boolean remove(SessionState sessionState) {
        try {
            cacheService.remove(null, sessionState.getId());
        } catch (Exception e) {
            log.error(e.getMessage(), e);

            return false;
        }
        return true;
    }

    public void remove(List<SessionState> list) {
        for (SessionState id : list) {
            try {
                remove(id);
            } catch (Exception e) {
                log.error("Failed to remove entry", e);
            }
        }
    }

    public boolean isSessionValid(SessionState sessionState) {
        if (sessionState == null) {
            return false;
        }

        final long sessionInterval = TimeUnit.SECONDS.toMillis(appConfiguration.getSessionIdUnusedLifetime());
        final long sessionUnauthenticatedInterval = TimeUnit.SECONDS.toMillis(appConfiguration.getSessionIdUnauthenticatedUnusedLifetime());

        final long timeSinceLastAccess = System.currentTimeMillis() - sessionState.getLastUsedAt().getTime();
        if (timeSinceLastAccess > sessionInterval && appConfiguration.getSessionIdUnusedLifetime() != -1) {
            return false;
        }
        if (sessionState.getState() == SessionIdState.UNAUTHENTICATED && timeSinceLastAccess > sessionUnauthenticatedInterval && appConfiguration.getSessionIdUnauthenticatedUnusedLifetime() != -1) {
            return false;
        }

        return true;
    }

    private List<Prompt> getPromptsFromSessionState(final SessionState sessionState) {
        String promptParam = sessionState.getSessionAttributes().get("prompt");
        return Prompt.fromString(promptParam, " ");
    }


    public boolean isSessionStateAuthenticated() {
        SessionState sessionState = getSessionState();

        if (sessionState == null) {
            return false;
        }

        SessionIdState sessionIdState = sessionState.getState();

        if (SessionIdState.AUTHENTICATED.equals(sessionIdState)) {
            return true;
        }

        return false;
    }

    public boolean isNotSessionStateAuthenticated() {
        return !isSessionStateAuthenticated();
    }

    private void auditLogging(SessionState sessionState) {
        HttpServletRequest httpServletRequest = ServerUtil.getRequestOrNull();
        if (httpServletRequest != null) {
            Action action;
            switch (sessionState.getState()) {
                case AUTHENTICATED:
                    action = Action.SESSION_AUTHENTICATED;
                    break;
                case UNAUTHENTICATED:
                    action = Action.SESSION_UNAUTHENTICATED;
                    break;
                default:
                    action = Action.SESSION_UNAUTHENTICATED;
            }
            OAuth2AuditLog oAuth2AuditLog = new OAuth2AuditLog(ServerUtil.getIpAddress(httpServletRequest), action);
            oAuth2AuditLog.setSuccess(true);
            applicationAuditLogger.sendMessage(oAuth2AuditLog);
        }
    }
}