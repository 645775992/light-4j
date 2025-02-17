/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.client.ClientConfig;
import com.networknt.client.oauth.OauthHelper;
import com.networknt.client.oauth.SignKeyRequest;
import com.networknt.client.oauth.TokenKeyRequest;
import com.networknt.config.Config;
import com.networknt.config.ConfigException;
import com.networknt.config.JsonMapper;
import com.networknt.exception.ExpiredTokenException;
import com.networknt.status.Status;
import com.networknt.utility.FingerPrintUtil;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.*;
import org.jose4j.jwx.JsonWebStructure;
import org.jose4j.keys.resolvers.JwksVerificationKeyResolver;
import org.jose4j.keys.resolvers.VerificationKeyResolver;
import org.jose4j.keys.resolvers.X509VerificationKeyResolver;
import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * This is a new class that is designed as non-static to replace the JwtHelper which is a static class. The reason
 * is to pass the framework specific security configuration so that we can eliminate the security.yml for token
 * verification.
 *
 * The JwtHelper will be stay for a while for backward compatibility reason as it is a public class and users might
 * use it in their application. The only thing that need to remember is to have both security.yml and openapi-security.yml
 * for the security configuration and there are overlap between these two files.
 *
 * To use this class, create an instance by passing in the security configuration and cache the instance in your app
 * as a field or an instance variable.
 *
 * @author Steve Hu
 */
public class JwtVerifier {
    static final Logger logger = LoggerFactory.getLogger(JwtVerifier.class);
    static final String GET_KEY_ERROR = "ERR10066";

    public static final String KID = "kid";
    public static final String SECURITY_CONFIG = "security";
    private static final int CACHE_EXPIRED_IN_MINUTES = 15;
    public static final String JWT_KEY_RESOLVER_X509CERT = "X509Certificate";
    public static final String JWT_KEY_RESOLVER_JWKS = "JsonWebKeySet";

    SecurityConfig config;
    int secondsOfAllowedClockSkew;
    Boolean enableJwtCache;
    Boolean bootstrapFromKeyService;

    static Cache<String, JwtClaims> cache;
    static Map<String, X509Certificate> certMap;
    static Map<String, List<JsonWebKey>> jwksMap;
    static List<String> fingerPrints;

    public JwtVerifier(SecurityConfig config) {
        this.config = config;
        this.secondsOfAllowedClockSkew = config.getClockSkewInSeconds();
        this.bootstrapFromKeyService = config.isBootstrapFromKeyService();
        this.enableJwtCache = config.isEnableJwtCache();
        if(Boolean.TRUE.equals(enableJwtCache)) {
            cache = Caffeine.newBuilder()
                    // assuming that the clock screw time is less than 5 minutes
                    .expireAfterWrite(CACHE_EXPIRED_IN_MINUTES, TimeUnit.MINUTES)
                    .build();
        }
        // init getting JWK during the initialization. The other part is in the resolver for OAuth 2.0 provider to
        // rotate keys when the first token is received with the new kid.
        String keyResolver = config.getKeyResolver();
        // cache the certificates
        certMap = new HashMap<>();
        fingerPrints = new ArrayList<>();
        if (config.getCertificate() != null) {
            Map<String, Object> keyMap = config.getCertificate();
            for(String kid: keyMap.keySet()) {
                X509Certificate cert = null;
                try {
                    cert = readCertificate((String)keyMap.get(kid));
                } catch (Exception e) {
                    logger.error("Exception:", e);
                }
                certMap.put(kid, cert);
                fingerPrints.add(FingerPrintUtil.getCertFingerPrint(cert));
            }
        }
        logger.debug("Successfully cached Certificate");
        // if KeyResolver is jwk and bootstrap from jwk is true, load jwk during server startup.
        if(JWT_KEY_RESOLVER_JWKS.equals(keyResolver) && bootstrapFromKeyService) {
            jwksMap = getJsonWebKeyMap();
        } else {
            jwksMap = new HashMap<>();
        }
    }

    /**
     * Read certificate from a file and convert it into X509Certificate object
     *
     * @param filename certificate file name
     * @return X509Certificate object
     * @throws Exception Exception while reading certificate
     */
    public X509Certificate readCertificate(String filename)
            throws Exception {
        InputStream inStream = null;
        X509Certificate cert = null;
        try {
            inStream = Config.getInstance().getInputStreamFromFile(filename);
            if (inStream != null) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                cert = (X509Certificate) cf.generateCertificate(inStream);
            } else {
                logger.info("Certificate " + Encode.forJava(filename) + " not found.");
            }
        } catch (Exception e) {
            logger.error("Exception: ", e);
        } finally {
            if (inStream != null) {
                try {
                    inStream.close();
                } catch (IOException ioe) {
                    logger.error("Exception: ", ioe);
                }
            }
        }
        return cert;
    }

    /**
     * Parse the jwt token from Authorization header.
     *
     * @param authorization authorization header.
     * @return JWT token
     */
    public static String getJwtFromAuthorization(String authorization) {
        String jwt = null;
        if(authorization != null) {
            String[] parts = authorization.split(" ");
            if (parts.length == 2) {
                String scheme = parts[0];
                String credentials = parts[1];
                Pattern pattern = Pattern.compile("^Bearer$", Pattern.CASE_INSENSITIVE);
                if (pattern.matcher(scheme).matches()) {
                    jwt = credentials;
                }
            }
        }
        return jwt;
    }

    /**
     * This method is to keep backward compatible for those call without VerificationKeyResolver. The single
     * auth server is used in this case.
     * @param jwt JWT token
     * @param ignoreExpiry indicate if the expiry will be ignored
     * @param isToken indicate if the JWT is a token
     * @param requestPath request path
     * @return JwtClaims
     * @throws InvalidJwtException throw when the token is invalid
     * @throws ExpiredTokenException throw when the token is expired
     */
    public JwtClaims verifyJwt(String jwt, boolean ignoreExpiry, boolean isToken, String requestPath) throws InvalidJwtException, ExpiredTokenException {
        return verifyJwt(jwt, ignoreExpiry, isToken, requestPath, this::getKeyResolver);
    }

    /**
     * This method is to keep backward compatible for those call without VerificationKeyResolver. The single
     * auth server is used in this case.
     * @param jwt JWT token
     * @param ignoreExpiry indicate if the expiry will be ignored
     * @param isToken indicate if the JWT is a token
     * @return JwtClaims
     * @throws InvalidJwtException throw when the token is invalid
     * @throws ExpiredTokenException throw when the token is expired
     */
    public JwtClaims verifyJwt(String jwt, boolean ignoreExpiry, boolean isToken) throws InvalidJwtException, ExpiredTokenException {
        return verifyJwt(jwt, ignoreExpiry, isToken, null, this::getKeyResolver);
    }

    /**
     * Verify JWT token format and signature. If ignoreExpiry is true, skip expiry verification, otherwise
     * verify the expiry before signature verification.
     *
     * In most cases, we need to verify the expiry of the jwt token. The only time we need to ignore expiry
     * verification is in SPA middleware handlers which need to verify csrf token in jwt against the csrf
     * token in the request header to renew the expired token.
     *
     * @param jwt String of Json web token
     * @param ignoreExpiry If true, don't verify if the token is expired.
     * @param isToken True if the jwt is an OAuth 2.0 access token
     * @param getKeyResolver How to get VerificationKeyResolver
     * @param requestPath the request path that used to find the right auth server config
     * @return JwtClaims object
     * @throws InvalidJwtException InvalidJwtException
     * @throws ExpiredTokenException ExpiredTokenException
     */
    public JwtClaims verifyJwt(String jwt, boolean ignoreExpiry, boolean isToken, String requestPath, BiFunction<String, String, VerificationKeyResolver> getKeyResolver)
            throws InvalidJwtException, ExpiredTokenException {
        JwtClaims claims;

        if(Boolean.TRUE.equals(enableJwtCache)) {
            claims = cache.getIfPresent(jwt);
            if(claims != null) {
                if(!ignoreExpiry) {
                    try {
                        // if using our own client module, the jwt token should be renewed automatically
                        // and it will never expired here. However, we need to handle other clients.
                        if ((NumericDate.now().getValue() - secondsOfAllowedClockSkew) >= claims.getExpirationTime().getValue())
                        {
                            logger.info("Cached jwt token is expired!");
                            throw new ExpiredTokenException("Token is expired");
                        }
                    } catch (MalformedClaimException e) {
                        // This is cached token and it is impossible to have this exception
                        logger.error("MalformedClaimException:", e);
                    }
                }
                // this claims object is signature verified already
                return claims;
            }
        }

        JwtConsumer consumer = new JwtConsumerBuilder()
                .setSkipAllValidators()
                .setDisableRequireSignature()
                .setSkipSignatureVerification()
                .build();

        JwtContext jwtContext = consumer.process(jwt);
        claims = jwtContext.getJwtClaims();
        JsonWebStructure structure = jwtContext.getJoseObjects().get(0);
        // need this kid to load public key certificate for signature verification
        String kid = structure.getKeyIdHeaderValue();

        // so we do expiration check here manually as we have the claim already for kid
        // if ignoreExpiry is false, verify expiration of the token
        if(!ignoreExpiry) {
            try {
                if ((NumericDate.now().getValue() - secondsOfAllowedClockSkew) >= claims.getExpirationTime().getValue())
                {
                    logger.info("jwt token is expired!");
                    throw new ExpiredTokenException("Token is expired");
                }
            } catch (MalformedClaimException e) {
                logger.error("MalformedClaimException:", e);
                throw new InvalidJwtException("MalformedClaimException", new ErrorCodeValidator.Error(ErrorCodes.MALFORMED_CLAIM, "Invalid ExpirationTime Format"), e, jwtContext);
            }
        }

        consumer = new JwtConsumerBuilder()
                .setRequireExpirationTime()
                .setAllowedClockSkewInSeconds(315360000) // use seconds of 10 years to skip expiration validation as we need skip it in some cases.
                .setSkipDefaultAudienceValidation()
                .setVerificationKeyResolver(getKeyResolver.apply(kid, requestPath))
                .build();

        // Validate the JWT and process it to the Claims
        jwtContext = consumer.process(jwt);
        claims = jwtContext.getJwtClaims();
        if(Boolean.TRUE.equals(enableJwtCache)) {
            cache.put(jwt, claims);
        }
        return claims;
    }

    /**
     * Get VerificationKeyResolver based on the kid and isToken indicator. For the implementation, we check
     * the jwk first and 509Certificate if the jwk cannot find the kid. Basically, we want to iterate all
     * the resolvers and find the right one with the kid.
     *
     * @param kid key id from the JWT token
     * @param requestPath the request path of incoming request used to identify the serviceId to get the JWK.
     * @return VerificationKeyResolver
     */
    private VerificationKeyResolver getKeyResolver(String kid, String requestPath) {
        // try the X509 certificate first
        String keyResolver = config.getKeyResolver();
        // get the public key certificate from the cache that is loaded from security.yml. If it is not there,
        // go to the next step to access JWK if it is enabled. We need to update the light-oauth2 and oauth-kafka
        // to support JWK instead of X509Certificate endpoint. 
        X509Certificate certificate = certMap == null ? null : certMap.get(kid);
        if(certificate != null) {
            X509VerificationKeyResolver x509VerificationKeyResolver = new X509VerificationKeyResolver(certificate);
            x509VerificationKeyResolver.setTryAllOnNoThumbHeader(true);
            return x509VerificationKeyResolver;
        } else {
            if(JWT_KEY_RESOLVER_JWKS.equals(keyResolver)) {
                // try jwk if kid cannot be found in the certificate map.
                List<JsonWebKey> jwkList = jwksMap.get(kid);
                if (jwkList == null) {
                    jwkList = getJsonWebKeySetForToken(kid, requestPath);
                    if (jwkList == null || jwkList.isEmpty()) {
                        throw new RuntimeException("no JWK for kid: " + kid);
                    }
                    for(JsonWebKey jwk: jwkList) {
                        jwksMap.put(jwk.getKeyId(), jwkList);
                        logger.info("Got Json web key set for kid: {} from Oauth server", jwk.getKeyId());
                    }
                }
                logger.debug("Got Json web key set from local cache");
                return new JwksVerificationKeyResolver(jwkList);
            } else {
                logger.error("Both X509Certificate and JWK are not configured.");
                return null;
            }
        }
    }

    /**
     * Retrieve JWK set from all possible oauth servers. If there are multiple servers in the client.yml, get all
     * the jwk by iterate all of them.
     *
     * @return {@link Map} of {@link List}
     */
    private Map<String, List<JsonWebKey>> getJsonWebKeyMap() {
        // the jwk indicator will ensure that the kid is not concat to the uri for path parameter.
        // the kid is not needed to get JWK. We need to figure out only one jwk server or multiple.
        jwksMap = new HashMap<>();
        ClientConfig clientConfig = ClientConfig.get();
        if(clientConfig.isMultipleAuthServers()) {
            // iterate all the configured auth server to get JWK.
            Map<String, Object> tokenConfig = clientConfig.getTokenConfig();
            Map<String, Object> keyConfig = (Map<String, Object>)tokenConfig.get(ClientConfig.KEY);
            Map<String, Object> serviceIdAuthServers = (Map<String, Object>)keyConfig.get(ClientConfig.SERVICE_ID_AUTH_SERVERS);
            if(serviceIdAuthServers == null) {
                throw new RuntimeException("serviceIdAuthServers property is missing in the token key configuration");
            }
            for(Map.Entry<String, Object> entry : serviceIdAuthServers.entrySet()) {
                Map<String, Object> authServerConfig = (Map<String, Object>)entry.getValue();
                TokenKeyRequest keyRequest = new TokenKeyRequest(null, true, authServerConfig);
                try {
                    logger.debug("Getting Json Web Key list from {} for serviceId {}", keyRequest.getServerUrl(), entry.getKey());
                    String key = OauthHelper.getKey(keyRequest);
                    logger.debug("Got Json Web Key = " + key);
                    List<JsonWebKey> jwkList = new JsonWebKeySet(key).getJsonWebKeys();
                    if (jwkList == null || jwkList.isEmpty()) {
                        throw new RuntimeException("cannot get JWK from OAuth server");
                    }
                    for(JsonWebKey jwk: jwkList) {
                        jwksMap.put(jwk.getKeyId(), jwkList);
                        logger.debug("Successfully cached JWK for kid {} serviceId {}", jwk.getKeyId(), entry.getKey());
                    }
                } catch (Exception e) {
                    logger.error("Exception: ", e);
                    logger.error(new Status(GET_KEY_ERROR).toString());
                    throw new RuntimeException(e);
                }
            }
        } else {
            // there is only one jwk server.
            TokenKeyRequest keyRequest = new TokenKeyRequest(null, true, null);
            try {
                logger.debug("Getting Json Web Key list from {}", keyRequest.getServerUrl());
                String key = OauthHelper.getKey(keyRequest);
                logger.debug("Got Json Web Key = " + key);
                List<JsonWebKey> jwkList = new JsonWebKeySet(key).getJsonWebKeys();
                if (jwkList == null || jwkList.isEmpty()) {
                    throw new RuntimeException("cannot get JWK from OAuth server");
                }
                for(JsonWebKey jwk: jwkList) {
                    jwksMap.put(jwk.getKeyId(), jwkList);
                    logger.debug("Successfully cached JWK for kid {}", jwk.getKeyId());
                }
            } catch (Exception e) {
                logger.error("Exception: ", e);
                logger.error(new Status(GET_KEY_ERROR).toString());
                throw new RuntimeException(e);
            }
        }
        return jwksMap;
    }

    /**
     * Retrieve JWK set from an oauth server with the kid. This method is used when a new kid is received
     * and the corresponding jwk doesn't exist in the cache. It will look up the key service by kid first.
     * @param kid String of kid
     * @param requestPath String of request path
     * @return {@link List} of {@link JsonWebKey}
     */
    private List<JsonWebKey> getJsonWebKeySetForToken(String kid, String requestPath) {
        // the jwk indicator will ensure that the kid is not concat to the uri for path parameter.
        // the kid is not needed to get JWK, but if requestPath is not null, it will be used to get the keyConfig
        if(logger.isTraceEnabled()) logger.trace("kid = " + kid + " requestPath = " + requestPath);
        Map<String, Object> config = null;
        ClientConfig clientConfig = ClientConfig.get();
        if(requestPath != null && clientConfig.isMultipleAuthServers()) {
            if(logger.isTraceEnabled()) logger.trace("requestPath = " + requestPath);
            Map<String, String> pathPrefixServices = clientConfig.getPathPrefixServices();
            if(pathPrefixServices == null || pathPrefixServices.size() == 0) {
                throw new ConfigException("pathPrefixServices property is missing or empty in client.yml");
            }
            // lookup the serviceId based on the full path and the prefix mapping by iteration here.
            String serviceId = null;
            for(Map.Entry<String, String> entry: pathPrefixServices.entrySet()) {
                if(requestPath.startsWith(entry.getKey())) {
                    serviceId = entry.getValue();
                }
            }
            if(serviceId == null) {
                throw new ConfigException("serviceId cannot be identified in client.yml with the requestPath = " + requestPath);
            }
            if(logger.isTraceEnabled()) logger.trace("serviceId = " + serviceId);
            // get the serviceIdAuthServers for key definition
            Map<String, Object> tokenConfig = clientConfig.getTokenConfig();
            Map<String, Object> keyConfig = (Map<String, Object>)tokenConfig.get(ClientConfig.KEY);
            Map<String, Object> serviceIdAuthServers = (Map<String, Object>)keyConfig.get(ClientConfig.SERVICE_ID_AUTH_SERVERS);
            if(serviceIdAuthServers == null) {
                throw new ConfigException("serviceIdAuthServers property is missing in the token key configuration in client.yml");
            }
            config = (Map<String, Object>)serviceIdAuthServers.get(serviceId);
        }
        if(logger.isTraceEnabled() && config != null) logger.trace("multiple oauth config based on path = " + JsonMapper.toJson(config));
        TokenKeyRequest keyRequest = new TokenKeyRequest(kid, true, config);
        try {
            logger.debug("Getting Json Web Key list from {}", keyRequest.getServerUrl());
            String key = OauthHelper.getKey(keyRequest);
            logger.debug("Got Json Web Key list from {}", keyRequest.getServerUrl());
            return new JsonWebKeySet(key).getJsonWebKeys();
        } catch (Exception e) {
            logger.error("Exception: ", e);
            logger.error(new Status(GET_KEY_ERROR).toString());
            throw new RuntimeException(e);
        }
    }

    public X509Certificate getCertForToken(String kid) {
        X509Certificate certificate = null;
        TokenKeyRequest keyRequest = new TokenKeyRequest(kid);
        try {
            logger.warn("<Deprecated: use JsonWebKeySet instead> Getting raw certificate for kid: {} from {}", kid, keyRequest.getServerUrl());
            String key = OauthHelper.getKey(keyRequest);
            logger.warn("<Deprecated: use JsonWebKeySet instead> Got raw certificate {} for kid: {}", key, kid);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            certificate = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(key.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            logger.error("Exception: ", e);
            throw new RuntimeException(e);
        }
        return certificate;
    }

    public X509Certificate getCertForSign(String kid) {
        X509Certificate certificate = null;
        SignKeyRequest keyRequest = new SignKeyRequest(kid);
        try {
            String key = OauthHelper.getKey(keyRequest);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            certificate = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(key.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            logger.error("Exception: ", e);
            throw new RuntimeException(e);
        }
        return certificate;
    }

    /**
     * Get a list of certificate fingerprints for server info endpoint so that certification process in light-portal
     * can detect if your service still use the default public key certificates provided by the light-4j framework.
     *
     * The default public key certificates are for dev only and should be replaced on any other environment or
     * set bootstrapFromKeyService: true if you are using light-oauth2 so that key can be dynamically loaded.
     *
     * @return List of certificate fingerprints
     */
    public List getFingerPrints() {
        return fingerPrints;
    }

}
