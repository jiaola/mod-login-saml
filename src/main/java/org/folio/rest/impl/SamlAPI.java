package org.folio.rest.impl;

import io.vertx.core.*;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.auth.PRNG;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.sstore.impl.SessionImpl;
import org.folio.config.SamlClientLoader;
import org.folio.config.SamlConfigHolder;
import org.folio.rest.jaxrs.model.SamlCheck;
import org.folio.rest.jaxrs.model.SamlLogin;
import org.folio.rest.jaxrs.model.SamlLoginRequest;
import org.folio.rest.jaxrs.resource.SamlResource;
import org.folio.rest.tools.utils.BinaryOutStream;
import org.folio.session.NoopSession;
import org.folio.util.HttpActionMapper;
import org.folio.util.OkapiHelper;
import org.folio.util.VertxUtils;
import org.folio.util.model.OkapiHeaders;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;
import org.pac4j.core.exception.HttpAction;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.redirect.RedirectAction;
import org.pac4j.saml.client.SAML2Client;
import org.pac4j.saml.client.SAML2ClientConfiguration;
import org.pac4j.saml.credentials.SAML2Credentials;
import org.pac4j.vertx.VertxWebContext;
import org.springframework.util.StringUtils;

import javax.ws.rs.core.Response;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static org.pac4j.core.util.CommonHelper.assertNotNull;

/**
 * Main entry point of module
 *
 * @author rsass
 */
public class SamlAPI implements SamlResource {

  private static final Logger log = LoggerFactory.getLogger(SamlAPI.class);

  /**
   * Check that client can be loaded, SAML-Login button can be displayed.
   */
  @Override
  public void getSamlCheck(RoutingContext routingContext, Map<String, String> okapiHeaders,
                           Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {

    log.info("check");
    findSaml2Client(routingContext, false)
      .setHandler(samlClientHandler -> {
        if (samlClientHandler.failed()) {
          asyncResultHandler.handle(Future.succeededFuture(GetSamlCheckResponse.withJsonOK(new SamlCheck().withActive(false))));
        } else {
          asyncResultHandler.handle(Future.succeededFuture(GetSamlCheckResponse.withJsonOK(new SamlCheck().withActive(true))));
        }
      });
  }

  @Override
  public void postSamlLogin(SamlLoginRequest requestEntity, RoutingContext routingContext, Map<String, String> okapiHeaders,
                            Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {

    String stripesUrl = requestEntity.getStripesUrl();

    // register non-persistent session (this request only) to overWrite relayState
    Session session = new SessionImpl(new PRNG(vertxContext.owner()));
    session.put("samlRelayState", stripesUrl);
    routingContext.setSession(session);


    findSaml2Client(routingContext, false) // do not allow login, if config is missing
      .setHandler(samlClientHandler -> {
        Response response;
        if (samlClientHandler.succeeded()) {
          SAML2Client saml2Client = samlClientHandler.result();
          try {
            RedirectAction redirectAction = saml2Client.getRedirectAction(VertxUtils.createWebContext(routingContext));
            String responseJsonString = redirectAction.getContent();
            SamlLogin dto = Json.decodeValue(responseJsonString, SamlLogin.class);
            response = PostSamlLoginResponse.withJsonOK(dto);
          } catch (HttpAction httpAction) {
            response = HttpActionMapper.toResponse(httpAction);
          }
        } else {
          log.warn("Login called but cannot load client to handle", samlClientHandler.cause());
          response = PostSamlLoginResponse.withPlainInternalServerError("Login called but cannot load client to handle");
        }
        asyncResultHandler.handle(Future.succeededFuture(response));
      });
  }


  @Override
  public void postSamlCallback(RoutingContext routingContext, Map<String, String> okapiHeaders,
                               Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {

    registerFakeSession(routingContext);

    final VertxWebContext webContext = VertxUtils.createWebContext(routingContext);

    // There is no better way to get RelayState.
    final String stripesURL = webContext.getRequestParameter("RelayState");
    if (!StringUtils.hasText(stripesURL)) {
      asyncResultHandler.handle(Future.succeededFuture(PostSamlCallbackResponse.withBadRequest("Stripes URL is missing!")));
      return;
    }


    findSaml2Client(routingContext, false) // How can someone reach this point if no stored configuration? Obviously an
      // error.
      .setHandler(samlClientHandler -> {

        Response response;
        if (samlClientHandler.failed()) {
          asyncResultHandler.handle(
            Future.succeededFuture(PostSamlCallbackResponse.withPlainInternalServerError(samlClientHandler.cause().getMessage())));
        } else {
          try {
            SAML2Client client = samlClientHandler.result();

            SAML2Credentials credentials = client.getCredentials(webContext);
            log.debug("credentials: {}", credentials);

            // Get user id
            String query = null;
            String externalSystemId = ((List) credentials.getUserProfile().getAttribute("UserID")).get(0).toString();

            try {
              query = okapiHeaders.get(OkapiHeaders.OKAPI_URL_HEADER) + "/users?query="
                + URLEncoder.encode("externalSystemId==\"" + externalSystemId + "\"", "UTF-8");
            } catch (UnsupportedEncodingException exc) {
              log.error("Failed to create url", exc);
              asyncResultHandler.handle(
                Future.succeededFuture(PostSamlCallbackResponse.withPlainInternalServerError(exc.getMessage())));
              return;
            }
            HttpClient httpClient = routingContext.vertx().createHttpClient();
            HttpClientRequest request = httpClient.getAbs(query)
//              .putHeader(OkapiHeaders.OKAPI_TENANT_HEADER, okapiHeaders.get(OkapiHeaders.OKAPI_TENANT_HEADER))
              .putHeader("Accept", "application/json,text/plain");

            // forward all Okapi headers
            for (Entry<String, String> entry : okapiHeaders.entrySet()) {
              request.putHeader(entry.getKey(), entry.getValue());
            }

            request.handler(userQueryResponse -> {
              if (userQueryResponse.statusCode() != 200) {
                asyncResultHandler.handle(
                  Future.succeededFuture(PostSamlCallbackResponse.withBadRequest("Cannot get user data: " + userQueryResponse.statusMessage())));
              } else {
                userQueryResponse.bodyHandler(buf -> {
                  try {
                    JsonObject resultObject = buf.toJsonObject();
                    int recordCount = resultObject.getInteger("totalRecords");
                    if (recordCount > 1) {
                      asyncResultHandler.handle(
                        Future.succeededFuture(PostSamlCallbackResponse.withBadRequest("More than one user record found!")));
                    } else if (recordCount == 0) {
                      String message = "No user found by external system id " + externalSystemId;
                      log.warn(message);
                      asyncResultHandler.handle(
                        Future.succeededFuture(PostSamlCallbackResponse.withBadRequest(message)));
                    } else {
                      boolean active = resultObject.getJsonArray("users").getJsonObject(0).getBoolean("active");
                      String userId = resultObject.getJsonArray("users").getJsonObject(0).getString("id");
                      if (!active) {
                        log.warn("User " + externalSystemId + " is inactive");
                      }

                      // Get auth token for user id
                      JsonObject userObject = resultObject.getJsonArray("users").getJsonObject(0);

                      JsonObject payload = new JsonObject()
                        .put("sub", userObject.getString("username"));
                      payload.put("user_id", userId);

                      fetchToken(payload, okapiHeaders.get(OkapiHeaders.OKAPI_TENANT_HEADER),
                        okapiHeaders.get(OkapiHeaders.OKAPI_URL_HEADER),
                        okapiHeaders.get(OkapiHeaders.OKAPI_TOKEN_HEADER),
                        vertxContext.owner())
                        .setHandler(fetchTokenRes -> {
                          if (fetchTokenRes.failed()) {
                            String errMsg = "Error fetching token: " + fetchTokenRes.cause().getLocalizedMessage();
                            log.debug(errMsg);
                            asyncResultHandler.handle(Future.succeededFuture(PostSamlCallbackResponse.withPlainInternalServerError(errMsg)));
                          } else {
                            // Append token as header to result
                            String authToken = fetchTokenRes.result();
                            log.debug("Auth token: ", authToken);

                            String encodedToken;
                            try {
                              encodedToken = URLEncoder.encode(authToken, "UTF-8");
                              asyncResultHandler.handle(
                                Future.succeededFuture(PostSamlCallbackResponse.withMovedTemporarily("ssoToken=" + authToken,
                                  authToken, stripesURL + "/sso-landing?ssoToken=" + encodedToken + "&userId=" + userObject.getString("id"))));
                            } catch (UnsupportedEncodingException exc) {
                              String message = "Could not encode token for url parameter.";
                              log.warn(message, exc);
                              asyncResultHandler.handle(Future.succeededFuture(PostSamlCallbackResponse.withPlainInternalServerError(message)));
                            }
                          }
                        });

                    }
                  } catch (Exception e) {
                    asyncResultHandler
                      .handle(Future.succeededFuture(PostSamlCallbackResponse.withBadRequest(stripesURL)));
                  }
                });
              }
            });
            request.end();

          } catch (HttpAction httpAction) {
            response = HttpActionMapper.toResponse(httpAction);
            asyncResultHandler.handle(Future.succeededFuture(response));
          }
        }
      });
  }

  @Override
  public void getSamlRegenerate(RoutingContext routingContext, Map<String, String> okapiHeaders,
                                Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {

    regenerateSaml2Config(routingContext)
      .setHandler(regenerationHandler -> {
        if (regenerationHandler.failed()) {
          log.warn("Cannot regenerate SAML2 metadata.", regenerationHandler.cause());
          String message =
            "Cannot regenerate SAML2 matadata. Internal error was: " + regenerationHandler.cause().getMessage();
          asyncResultHandler
            .handle(Future.succeededFuture(GetSamlRegenerateResponse.withPlainInternalServerError(message)));
        } else {
          String metadata = regenerationHandler.result();

          BinaryOutStream outStream = new BinaryOutStream();
          outStream.setData(metadata.getBytes(StandardCharsets.UTF_8));
          asyncResultHandler.handle(Future.succeededFuture(GetSamlRegenerateResponse.withXmlOK(outStream)));
        }
      });
  }

  private Future<String> regenerateSaml2Config(RoutingContext routingContext) {

    Future<String> result = Future.future();
    final Vertx vertx = routingContext.vertx();

    findSaml2Client(routingContext, true) // generate KeyStore if missing
      .setHandler(handler -> {
        if (handler.failed()) {
          result.fail(handler.cause());
        } else {
          SAML2Client saml2Client = handler.result();

          vertx.executeBlocking(blockingCode -> {

            SAML2ClientConfiguration cfg = saml2Client.getConfiguration();

            // force metadata generation then init
            cfg.setForceServiceProviderMetadataGeneration(true);
            saml2Client.reinit(VertxUtils.createWebContext(routingContext));
            cfg.setForceServiceProviderMetadataGeneration(false);

            blockingCode.complete(saml2Client.getServiceProviderMetadataResolver().getMetadata());

          }, result);
        }
      });

    return result;
  }

  private Future<SAML2Client> findSaml2Client(RoutingContext routingContext, boolean generateMissingConfig) {

    String tenantId = OkapiHelper.okapiHeaders(routingContext).getTenant();
    Config config = SamlConfigHolder.getInstance().getConfig();
    final Clients clients = config.getClients();
    assertNotNull("clients", clients);

    Future<SAML2Client> result = Future.future();

    try {
      final Client client = clients.findClient(tenantId);
      if (client != null && client instanceof SAML2Client) {
        result.complete((SAML2Client) client);
      } else {
        result.fail("No client loaded or not a SAML2 client.");
      }
    } catch (TechnicalException ex) {

      // Client not loaded, try to load from configuration
      SamlClientLoader.loadFromConfiguration(routingContext, generateMissingConfig)
        .setHandler(clientResult -> {
          if (clientResult.failed()) {
            result.fail(clientResult.cause());
          } else {
            SAML2Client loadedClient = clientResult.result();

            List<Client> registeredClients = clients.getClients();
            if (registeredClients == null) {
              clients.setClients(loadedClient);
            } else {
              registeredClients.add(loadedClient);
            }
            result.complete(loadedClient);
          }
        });
    }

    return result;
  }

  /**
   * Registers a no-op session. Pac4j want to access session variablas and fails if there is no session.
   *
   * @param routingContext the current routing context
   */
  private void registerFakeSession(RoutingContext routingContext) {
    routingContext.setSession(new NoopSession());
  }

  private Future<String> fetchToken(JsonObject payload, String tenant, String okapiURL, String requestToken,
                                    Vertx vertx) {
    Future<String> future = Future.future();
    HttpClient client = vertx.createHttpClient();
    HttpClientRequest request = client.postAbs(okapiURL + "/token");
    request.putHeader(OkapiHeaders.OKAPI_TOKEN_HEADER, requestToken);
    request.putHeader(OkapiHeaders.OKAPI_TENANT_HEADER, tenant);
    request.handler(response -> {
      if (response.statusCode() < 200 || response.statusCode() > 299) {
        future.fail("Got response " + response.statusCode() + " fetching token");
      } else {
        String token = response.getHeader(OkapiHeaders.OKAPI_TOKEN_HEADER);
        log.debug("Got token " + token + " from authz");
        future.complete(token);
      }
    });
    request.end(new JsonObject().put("payload", payload).encode());
    return future;
  }

}
