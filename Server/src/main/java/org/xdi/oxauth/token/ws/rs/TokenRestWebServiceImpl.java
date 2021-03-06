/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.token.ws.rs;

import com.google.common.base.Strings;
import org.apache.commons.lang.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.xdi.oxauth.audit.ApplicationAuditLogger;
import org.xdi.oxauth.model.audit.Action;
import org.xdi.oxauth.model.audit.OAuth2AuditLog;
import org.xdi.oxauth.model.authorize.CodeVerifier;
import org.xdi.oxauth.model.common.*;
import org.xdi.oxauth.model.configuration.AppConfiguration;
import org.xdi.oxauth.model.error.ErrorResponseFactory;
import org.xdi.oxauth.model.exception.InvalidJweException;
import org.xdi.oxauth.model.exception.InvalidJwtException;
import org.xdi.oxauth.model.registration.Client;
import org.xdi.oxauth.model.session.SessionClient;
import org.xdi.oxauth.model.token.TokenErrorResponseType;
import org.xdi.oxauth.model.token.TokenParamsValidator;
import org.xdi.oxauth.security.Identity;
import org.xdi.oxauth.service.AuthenticationFilterService;
import org.xdi.oxauth.service.AuthenticationService;
import org.xdi.oxauth.service.GrantService;
import org.xdi.oxauth.service.UserService;
import org.xdi.oxauth.uma.service.UmaTokenService;
import org.xdi.oxauth.util.ServerUtil;
import org.xdi.util.StringHelper;
import org.xdi.util.security.StringEncrypter;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.SecurityContext;
import java.security.SignatureException;

/**
 * Provides interface for token REST web services
 *
 * @author Yuriy Zabrovarnyy
 * @author Javier Rojas Blum
 * @version July 19, 2017
 */
@Path("/")
public class TokenRestWebServiceImpl implements TokenRestWebService {

    @Inject
    private Logger log;

    @Inject
    private Identity identity;

    @Inject
    private ApplicationAuditLogger applicationAuditLogger;

    @Inject
    private ErrorResponseFactory errorResponseFactory;

    @Inject
    private AuthorizationGrantList authorizationGrantList;

    @Inject
    private UserService userService;

    @Inject
    private GrantService grantService;

    @Inject
    private AuthenticationFilterService authenticationFilterService;

    @Inject
    private AuthenticationService authenticationService;

    @Inject
    private AppConfiguration appConfiguration;

    @Inject
    private UmaTokenService umaTokenService;

    @Override
    public Response requestAccessToken(String grantType, String code,
                                       String redirectUri, String username, String password, String scope,
                                       String assertion, String refreshToken,
                                       String clientId, String clientSecret, String codeVerifier,
                                       String ticket, String claimToken, String claimTokenFormat, String pctCode, String rptCode,
                                       HttpServletRequest request, SecurityContext sec) {
        log.debug(
                "Attempting to request access token: grantType = {}, code = {}, redirectUri = {}, username = {}, refreshToken = {}, " +
                        "clientId = {}, ExtraParams = {}, isSecure = {}, codeVerifier = {}, ticket = {}",
                grantType, code, redirectUri, username, refreshToken, clientId, request.getParameterMap(),
                sec.isSecure(), codeVerifier, ticket);

        boolean isUma = StringUtils.isNotBlank(ticket);
        if (isUma) {
            return umaTokenService.requestRpt(grantType, ticket, claimToken, claimTokenFormat, pctCode, rptCode, scope, request);
        }

        OAuth2AuditLog oAuth2AuditLog = new OAuth2AuditLog(ServerUtil.getIpAddress(request), Action.TOKEN_REQUEST);
        oAuth2AuditLog.setClientId(clientId);
        oAuth2AuditLog.setUsername(username);
        oAuth2AuditLog.setScope(scope);

        scope = ServerUtil.urlDecode(scope); // it may be encoded in uma case
        ResponseBuilder builder = Response.ok();

        try {
            log.debug("Starting to validate request parameters");
            if (!TokenParamsValidator.validateParams(grantType, code, redirectUri, username, password,
                    scope, assertion, refreshToken)) {
                log.trace("Failed to validate request parameters");
                builder = error(400, TokenErrorResponseType.INVALID_REQUEST);
            } else {
                log.trace("Request parameters are right");
                GrantType gt = GrantType.fromString(grantType);
                log.debug("Grant type: '{}'", gt);

                SessionClient sessionClient = identity.getSetSessionClient();
                Client client = null;
                if (sessionClient != null) {
                    client = sessionClient.getClient();
                    log.debug("Get sessionClient: '{}'", sessionClient);
                }

                if (client != null) {
                    log.debug("Get client from session: '{}'", client.getClientId());
                }

                if (gt == GrantType.AUTHORIZATION_CODE) {
                    if (client == null) {
                        return response(error(400, TokenErrorResponseType.INVALID_GRANT));
                    }
                    if (!TokenParamsValidator.validateGrantType(gt, client.getGrantTypes(), appConfiguration.getGrantTypesSupported())) {
                        return response(error(400, TokenErrorResponseType.INVALID_GRANT));
                    }

                    log.debug("Attempting to find authorizationCodeGrant by clinetId: '{}', code: '{}'", client.getClientId(), code);
                    AuthorizationCodeGrant authorizationCodeGrant = authorizationGrantList.getAuthorizationCodeGrant(client.getClientId(), code);
                    log.trace("AuthorizationCodeGrant : '{}'", authorizationCodeGrant);

                    if (authorizationCodeGrant != null) {
                        validatePKCE(authorizationCodeGrant, codeVerifier);

                        authorizationCodeGrant.setIsCachedWithNoPersistence(false);
                        authorizationCodeGrant.save();

                        AccessToken accToken = authorizationCodeGrant.createAccessToken();
                        log.debug("Issuing access token: {}", accToken.getCode());

                        RefreshToken reToken = authorizationCodeGrant.createRefreshToken();

                        if (scope != null && !scope.isEmpty()) {
                            scope = authorizationCodeGrant.checkScopesPolicy(scope);
                        }

                        IdToken idToken = null;
                        if (authorizationCodeGrant.getScopes().contains("openid")) {
                            String nonce = authorizationCodeGrant.getNonce();
                            boolean includeIdTokenClaims = Boolean.TRUE.equals(
                                    appConfiguration.getLegacyIdTokenClaims());
                            idToken = authorizationCodeGrant.createIdToken(
                                    nonce, null, accToken, authorizationCodeGrant, includeIdTokenClaims);
                        }

                        builder.entity(getJSonResponse(accToken,
                                accToken.getTokenType(),
                                accToken.getExpiresIn(),
                                reToken,
                                scope,
                                idToken));

                        oAuth2AuditLog.updateOAuth2AuditLog(authorizationCodeGrant, true);

                        grantService.removeByCode(authorizationCodeGrant.getAuthorizationCode().getCode(), authorizationCodeGrant.getClientId());
                    } else {
                        log.debug("AuthorizationCodeGrant is empty by clinetId: '{}', code: '{}'", client.getClientId(), code);
                        // if authorization code is not found then code was already used = remove all grants with this auth code
                        grantService.removeAllByAuthorizationCode(code);
                        builder = error(400, TokenErrorResponseType.INVALID_GRANT);
                    }
                } else if (gt == GrantType.REFRESH_TOKEN) {
                    if (client == null) {
                        return response(error(401, TokenErrorResponseType.INVALID_GRANT));
                    }
                    if (!TokenParamsValidator.validateGrantType(gt, client.getGrantTypes(), appConfiguration.getGrantTypesSupported())) {
                        return response(error(400, TokenErrorResponseType.INVALID_GRANT));
                    }

                    AuthorizationGrant authorizationGrant = authorizationGrantList.getAuthorizationGrantByRefreshToken(client.getClientId(), refreshToken);

                    if (authorizationGrant != null) {
                        AccessToken accToken = authorizationGrant.createAccessToken();

                        /*
                        The authorization server MAY issue a new refresh token, in which case
                        the client MUST discard the old refresh token and replace it with the
                        new refresh token.
                        */
                        RefreshToken reToken = authorizationGrant.createRefreshToken();
                        grantService.removeByCode(refreshToken, client.getClientId());

                        if (scope != null && !scope.isEmpty()) {
                            scope = authorizationGrant.checkScopesPolicy(scope);
                        }

                        builder.entity(getJSonResponse(accToken,
                                accToken.getTokenType(),
                                accToken.getExpiresIn(),
                                reToken,
                                scope,
                                null));
                        oAuth2AuditLog.updateOAuth2AuditLog(authorizationGrant, true);
                    } else {
                        builder = error(401, TokenErrorResponseType.INVALID_GRANT);
                    }
                } else if (gt == GrantType.CLIENT_CREDENTIALS) {
                    if (client == null) {
                        return response(error(401, TokenErrorResponseType.INVALID_GRANT));
                    }
                    if (!TokenParamsValidator.validateGrantType(gt, client.getGrantTypes(), appConfiguration.getGrantTypesSupported())) {
                        return response(error(400, TokenErrorResponseType.INVALID_GRANT));
                    }

                    ClientCredentialsGrant clientCredentialsGrant = authorizationGrantList.createClientCredentialsGrant(new User(), client); // TODO: fix the user arg

                    AccessToken accessToken = clientCredentialsGrant.createAccessToken();

                    if (scope != null && !scope.isEmpty()) {
                        scope = clientCredentialsGrant.checkScopesPolicy(scope);
                    }

                    IdToken idToken = null;
                    if (clientCredentialsGrant.getScopes().contains("openid")) {
                        boolean includeIdTokenClaims = Boolean.TRUE.equals(
                                appConfiguration.getLegacyIdTokenClaims());
                        idToken = clientCredentialsGrant.createIdToken(
                                null, null, null, clientCredentialsGrant, includeIdTokenClaims);
                    }

                    oAuth2AuditLog.updateOAuth2AuditLog(clientCredentialsGrant, true);
                    builder.entity(getJSonResponse(accessToken,
                            accessToken.getTokenType(),
                            accessToken.getExpiresIn(),
                            null,
                            scope,
                            idToken));
                } else if (gt == GrantType.RESOURCE_OWNER_PASSWORD_CREDENTIALS) {
                    if (client == null) {
                        log.error("Invalid client", new RuntimeException("Client is empty"));
                        return response(error(401, TokenErrorResponseType.INVALID_CLIENT));
                    }
                    if (!TokenParamsValidator.validateGrantType(gt, client.getGrantTypes(), appConfiguration.getGrantTypesSupported())) {
                        return response(error(400, TokenErrorResponseType.INVALID_GRANT));
                    }

                    User user = null;
                    if (authenticationFilterService.isEnabled()) {
                        String userDn = authenticationFilterService.processAuthenticationFilters(request.getParameterMap());
                        if (StringHelper.isNotEmpty(userDn)) {
                            user = userService.getUserByDn(userDn);
                        }
                    }

                    if (user == null) {
                        boolean authenticated = authenticationService.authenticate(username, password);
                        if (authenticated) {
                            user = authenticationService.getAuthenticatedUser();
                        }
                    }

                    if (user != null) {
                        ResourceOwnerPasswordCredentialsGrant resourceOwnerPasswordCredentialsGrant = authorizationGrantList.createResourceOwnerPasswordCredentialsGrant(user, client);
                        AccessToken accessToken = resourceOwnerPasswordCredentialsGrant.createAccessToken();
                        RefreshToken reToken = resourceOwnerPasswordCredentialsGrant.createRefreshToken();

                        if (scope != null && !scope.isEmpty()) {
                            scope = resourceOwnerPasswordCredentialsGrant.checkScopesPolicy(scope);
                        }

                        IdToken idToken = null;
                        if (resourceOwnerPasswordCredentialsGrant.getScopes().contains("openid")) {
                            boolean includeIdTokenClaims = Boolean.TRUE.equals(
                                    appConfiguration.getLegacyIdTokenClaims());
                            idToken = resourceOwnerPasswordCredentialsGrant.createIdToken(
                                    null, null, null, resourceOwnerPasswordCredentialsGrant, includeIdTokenClaims);
                        }

                        oAuth2AuditLog.updateOAuth2AuditLog(resourceOwnerPasswordCredentialsGrant, true);
                        builder.entity(getJSonResponse(accessToken,
                                accessToken.getTokenType(),
                                accessToken.getExpiresIn(),
                                reToken,
                                scope,
                                idToken));
                    } else {
                        log.error("Invalid user", new RuntimeException("User is empty"));
                        builder = error(401, TokenErrorResponseType.INVALID_CLIENT);
                    }
                }
            }
        } catch (WebApplicationException e) {
            throw e;
        } catch (SignatureException e) {
            builder = Response.status(500);
            log.error(e.getMessage(), e);
        } catch (StringEncrypter.EncryptionException e) {
            builder = Response.status(500);
            log.error(e.getMessage(), e);
        } catch (InvalidJwtException e) {
            builder = Response.status(500);
            log.error(e.getMessage(), e);
        } catch (InvalidJweException e) {
            builder = Response.status(500);
            log.error(e.getMessage(), e);
        } catch (Exception e) {
            builder = Response.status(500);
            log.error(e.getMessage(), e);
        }

        applicationAuditLogger.sendMessage(oAuth2AuditLog);
        return response(builder);
    }

    private void validatePKCE(AuthorizationCodeGrant grant, String codeVerifier) {
        log.trace("PKCE validation, code_verifier: {}, code_challenge: {}, method: {}",
                codeVerifier, grant.getCodeChallenge(), grant.getCodeChallengeMethod());

        if (Strings.isNullOrEmpty(grant.getCodeChallenge()) && Strings.isNullOrEmpty(codeVerifier)) {
            return; // if no code challenge then it's valid, no PKCE check
        }

        if (!CodeVerifier.matched(grant.getCodeChallenge(), grant.getCodeChallengeMethod(), codeVerifier)) {
            log.error("PKCE check fails. Code challenge does not match to request code verifier, " +
                    "grantId:" + grant.getGrantId() + ", codeVerifier: " + codeVerifier);
            throw new WebApplicationException(response(error(401, TokenErrorResponseType.INVALID_GRANT)));
        }
    }

    private Response response(ResponseBuilder builder) {
        CacheControl cacheControl = new CacheControl();
        cacheControl.setNoTransform(false);
        cacheControl.setNoStore(true);
        builder.cacheControl(cacheControl);
        builder.header("Pragma", "no-cache");
        return builder.build();
    }

    private ResponseBuilder error(int p_status, TokenErrorResponseType p_type) {
        return Response.status(p_status).entity(errorResponseFactory.getErrorAsJson(p_type));
    }

    /**
     * Builds a JSon String with the structure for token issues.
     */
    public String getJSonResponse(AccessToken accessToken, TokenType tokenType,
                                  Integer expiresIn, RefreshToken refreshToken, String scope,
                                  IdToken idToken) {
        JSONObject jsonObj = new JSONObject();
        try {
            jsonObj.put("access_token", accessToken.getCode()); // Required
            jsonObj.put("token_type", tokenType.toString()); // Required
            if (expiresIn != null) { // Optional
                jsonObj.put("expires_in", expiresIn);
            }
            if (refreshToken != null) { // Optional
                jsonObj.put("refresh_token", refreshToken.getCode());
            }
            if (scope != null) { // Optional
                jsonObj.put("scope", scope);
            }
            if (idToken != null) {
                jsonObj.put("id_token", idToken.getCode());
            }
        } catch (JSONException e) {
            log.error(e.getMessage(), e);
        }

        return jsonObj.toString();
    }
}