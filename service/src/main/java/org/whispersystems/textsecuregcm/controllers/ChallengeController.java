/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.controllers;

import com.codahale.metrics.annotation.Timed;
import io.dropwizard.auth.Auth;
import java.util.NoSuchElementException;
import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.whispersystems.textsecuregcm.auth.AuthenticatedAccount;
import org.whispersystems.textsecuregcm.entities.AnswerChallengeRequest;
import org.whispersystems.textsecuregcm.entities.AnswerPushChallengeRequest;
import org.whispersystems.textsecuregcm.entities.AnswerRecaptchaChallengeRequest;
import org.whispersystems.textsecuregcm.limits.RateLimitChallengeManager;
import org.whispersystems.textsecuregcm.metrics.UserAgentTagUtil;
import org.whispersystems.textsecuregcm.push.NotPushRegisteredException;
import org.whispersystems.textsecuregcm.util.ForwardedIpUtil;

import static org.whispersystems.textsecuregcm.metrics.MetricsUtil.name;

@Path("/v1/challenge")
public class ChallengeController {

  private final RateLimitChallengeManager rateLimitChallengeManager;

  private static final String CHALLENGE_RESPONSE_COUNTER_NAME = name(ChallengeController.class, "challengeResponse");
  private static final String CHALLENGE_TYPE_TAG = "type";

  public ChallengeController(final RateLimitChallengeManager rateLimitChallengeManager) {
    this.rateLimitChallengeManager = rateLimitChallengeManager;
  }

  @Timed
  @PUT
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public Response handleChallengeResponse(@Auth final AuthenticatedAccount auth,
      @Valid final AnswerChallengeRequest answerRequest,
      @HeaderParam("X-Forwarded-For") final String forwardedFor,
      @HeaderParam(HttpHeaders.USER_AGENT) final String userAgent) throws RetryLaterException {

    Tags tags = Tags.of(UserAgentTagUtil.getPlatformTag(userAgent));

    try {
      if (answerRequest instanceof final AnswerPushChallengeRequest pushChallengeRequest) {
        tags = tags.and(CHALLENGE_TYPE_TAG, "push");

        rateLimitChallengeManager.answerPushChallenge(auth.getAccount(), pushChallengeRequest.getChallenge());
      } else if (answerRequest instanceof AnswerRecaptchaChallengeRequest) {
        tags = tags.and(CHALLENGE_TYPE_TAG, "recaptcha");

        try {
          final AnswerRecaptchaChallengeRequest recaptchaChallengeRequest = (AnswerRecaptchaChallengeRequest) answerRequest;
          final String mostRecentProxy = ForwardedIpUtil.getMostRecentProxy(forwardedFor).orElseThrow();

          rateLimitChallengeManager.answerRecaptchaChallenge(auth.getAccount(), recaptchaChallengeRequest.getCaptcha(),
              mostRecentProxy);

        } catch (final NoSuchElementException e) {
          return Response.status(400).build();
        }
      } else {
        tags = tags.and(CHALLENGE_TYPE_TAG, "unrecognized");
      }
    } catch (final RateLimitExceededException e) {
      throw new RetryLaterException(e);
    } finally {
      Metrics.counter(CHALLENGE_RESPONSE_COUNTER_NAME, tags).increment();
    }

    return Response.status(200).build();
  }

  @Timed
  @POST
  @Path("/push")
  public Response requestPushChallenge(@Auth final AuthenticatedAccount auth) {
    try {
      rateLimitChallengeManager.sendPushChallenge(auth.getAccount());
      return Response.status(200).build();
    } catch (final NotPushRegisteredException e) {
      return Response.status(404).build();
    }
  }
}
