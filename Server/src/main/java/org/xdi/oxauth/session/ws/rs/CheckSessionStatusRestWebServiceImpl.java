/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.session.ws.rs;

import java.io.IOException;
import java.util.Date;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.codehaus.jackson.annotate.JsonProperty;
import org.slf4j.Logger;
import org.xdi.model.security.Identity;
import org.xdi.oxauth.model.common.SessionState;
import org.xdi.oxauth.service.SessionStateService;
import org.xdi.oxauth.util.ServerUtil;
import org.xdi.util.StringHelper;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import com.wordnik.swagger.annotations.ApiResponse;
import com.wordnik.swagger.annotations.ApiResponses;

/**
 * @author Yuriy Movchan
 * @version 0.1 12/16/2015
 */
@Path("/")
@Api(value = "/", description = "Check Session Status Endpoint")
public class CheckSessionStatusRestWebServiceImpl {

	@Inject
	private Logger log;

	@Inject
	private SessionStateService sessionStateService;

	@Inject
	private Identity identity;

    @GET
    @Path("/session_status")
	@Produces({ MediaType.APPLICATION_JSON })
    @ApiOperation(
            value = "Determine cussrent sesion status.",
            notes = "Determine cussrent sesion status.",
            response = Response.class,
            responseContainer = "JSON"
    )
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "invalid_request\n" +
                    "The request is missing a required parameter, includes an unsupported parameter or parameter value, repeats the same parameter, uses more than one method for including an access token, or is otherwise malformed.  The resource server SHOULD respond with the HTTP 400 (Bad Request) status code.")
    })
	public Response requestCheckSessionStatus(@Context HttpServletRequest httpRequest, @Context HttpServletResponse httpResponse,
			@Context SecurityContext securityContext) throws IOException {
		String sessionStateCookie = sessionStateService.getSessionStateFromCookie(httpRequest);
		log.debug("Found session '{}' cookie: '{}'", SessionStateService.SESSION_STATE_COOKIE_NAME, sessionStateCookie);

		CheckSessionResponse response = new CheckSessionResponse("unknown", "");

		SessionState sessionState = sessionStateService.getSessionState(sessionStateCookie);
		if (sessionState != null) {
			response.setState(sessionState.getState().getValue());
			response.setAuthTime(sessionState.getAuthenticationTime());

			String sessionCustomState = sessionState.getSessionAttributes().get(SessionStateService.SESSION_CUSTOM_STATE);
			if (StringHelper.isNotEmpty(sessionCustomState)) {
				response.setCustomState(sessionCustomState);
			}
		}

		String responseJson = ServerUtil.asJson(response);
		log.debug("Check session status response: '{}'", responseJson);

		return Response.ok().type(MediaType.APPLICATION_JSON).entity(responseJson).build();
	}

	class CheckSessionResponse {

		@JsonProperty(value = "state")
		String state;
		
		@JsonProperty(value = "custom_state")
		String customState;

		@JsonProperty(value = "auth_time")
		Date authTime;

		public CheckSessionResponse(String state, String stateExt) {
			this.state = state;
			this.customState = stateExt;
		}

		public String getState() {
			return state;
		}

		public void setState(String state) {
			this.state = state;
		}

		public String getCustomState() {
			return customState;
		}

		public void setCustomState(String customState) {
			this.customState = customState;
		}

		public Date getAuthTime() {
			return authTime;
		}

		public void setAuthTime(Date authTime) {
			this.authTime = authTime;
		}

	}

}
