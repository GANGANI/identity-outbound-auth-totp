/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.application.authenticator.totp;

import org.apache.axis2.context.ConfigurationContext;
import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.core.util.CryptoException;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.AuthenticationFailedException;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.authenticator.totp.exception.TOTPException;
import org.wso2.carbon.identity.application.authenticator.totp.internal.TOTPDataHolder;
import org.wso2.carbon.identity.application.authenticator.totp.util.TOTPUtil;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.event.IdentityEventConstants;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.mgt.IdentityMgtConfigException;
import org.wso2.carbon.identity.mgt.IdentityMgtServiceException;
import org.wso2.carbon.identity.mgt.NotificationSender;
import org.wso2.carbon.identity.mgt.NotificationSendingModule;
import org.wso2.carbon.identity.mgt.config.Config;
import org.wso2.carbon.identity.mgt.config.ConfigBuilder;
import org.wso2.carbon.identity.mgt.config.ConfigType;
import org.wso2.carbon.identity.mgt.config.StorageType;
import org.wso2.carbon.identity.mgt.dto.NotificationDataDTO;
import org.wso2.carbon.identity.mgt.mail.DefaultEmailSendingModule;
import org.wso2.carbon.identity.mgt.mail.Notification;
import org.wso2.carbon.identity.mgt.mail.NotificationBuilder;
import org.wso2.carbon.identity.mgt.mail.NotificationData;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import static org.wso2.carbon.identity.application.authenticator.totp.TOTPAuthenticatorConstants.TOKEN;

/**
 * TOTP Token generator class.
 *
 * @since 2.0.3
 */
public class TOTPTokenGenerator {

    private static final String FIRST_NAME = "firstname";
    private static final String TOTP_TOKEN = "totp-token";
    private static final Log log = LogFactory.getLog(TOTPTokenGenerator.class);

    private static final int TOKEN_HASH_DIVISOR = 1000000;
    // Max number of attempts to calculate a token with minimum chars.
    private static final int MAX_TOKEN_CALCULATE_ATTEMPTS = 5;

    /**
     * Get Time steps from unix epoch time.
     *
     * @return Time index
     */
    private static long getTimeIndex(AuthenticationContext context) throws TOTPException {

        return System.currentTimeMillis() / 1000 / TOTPUtil.getTimeStepSize(context);
    }

    /**
     * Generate TOTP token for a locally stored user.
     *
     * @param username Username of the user
     * @param context  Authentication context
     * @return TOTP token as a String
     * @throws TOTPException When could not find user realm for the given tenant domain, invalid
     *                       secret key, decrypting invalid key and could not find the configured hashing algorithm
     */
    public static String generateTOTPTokenLocal(String username, AuthenticationContext context)
            throws TOTPException {

        long token = 0;
        String tenantAwareUsername = null;
        if (username != null) {
            try {
                String tenantDomain = MultitenantUtils.getTenantDomain(username);
                UserRealm userRealm = TOTPUtil.getUserRealm(username);
                tenantAwareUsername = MultitenantUtils.getTenantAwareUsername(username);
                if (userRealm != null) {
                    String storedSecretKey;
                    if (context.getProperty(TOTPAuthenticatorConstants.SECRET_KEY_CLAIM_URL) != null) {
                        storedSecretKey =
                                context.getProperty(TOTPAuthenticatorConstants.SECRET_KEY_CLAIM_URL).toString();
                    } else {
                        Map<String, String> userClaimValues =
                                userRealm.getUserStoreManager().getUserClaimValues
                                        (tenantAwareUsername, new String[]{
                                                TOTPAuthenticatorConstants.SECRET_KEY_CLAIM_URL}, null);
                        storedSecretKey = userClaimValues.get(TOTPAuthenticatorConstants.SECRET_KEY_CLAIM_URL);
                    }

                    String secretKey = TOTPUtil.decrypt(storedSecretKey);
                    String firstName = userRealm
                            .getUserStoreManager().getUserClaimValue
                                    (tenantAwareUsername,
                                            TOTPAuthenticatorConstants.FIRST_NAME_CLAIM_URL, null);
                    String email = userRealm.getUserStoreManager()
                            .getUserClaimValue
                                    (tenantAwareUsername,
                                            TOTPAuthenticatorConstants.EMAIL_CLAIM_URL,
                                            null);
                    AuthenticatedUser authenticatedUser = (AuthenticatedUser) context
                            .getProperty(TOTPAuthenticatorConstants.AUTHENTICATED_USER);
                    token = sendEmailWithToken(username, authenticatedUser, tenantAwareUsername, firstName, secretKey,
                            email, tenantDomain, context);
                } else {
                    throw new TOTPException(
                            "Cannot find the user realm for the given tenant domain : " +
                                    CarbonContext.getThreadLocalCarbonContext().getTenantDomain());
                }
            } catch (UserStoreException e) {
                throw new TOTPException(
                        "TOTPTokenGenerator failed while trying to access userRealm of the user : " +
                                tenantAwareUsername, e);
            } catch (CryptoException e) {
                throw new TOTPException("Error while decrypting the key", e);
            } catch (AuthenticationFailedException e) {
                throw new TOTPException(
                        "TOTPTokenVerifier cannot find the property value for encodingMethod");
            }
        }
        return Long.toString(token);
    }

    /**
     * Generate TOTP token for a federated user.
     *
     * @param username Username of the user.
     * @param context  Authentication context.
     * @throws TOTPException When could not find user realm for the given tenant domain, invalid.
     *                       secret key, decrypting invalid key and could not find the configured hashing algorithm.
     */
    public static void generateTOTPTokenFederatedUser(String username, AuthenticatedUser authenticatedUser,
                                                      AuthenticationContext context)
            throws TOTPException {

        if (context.getProperty(TOTPAuthenticatorConstants.FEDERATED_USER_ID) == null) {
            throw new TOTPException("Error wile getting the federated user id for the user: " +
                    authenticatedUser.getUserName());
        }
        String userId = context.getProperty(TOTPAuthenticatorConstants.FEDERATED_USER_ID).toString();

        String tenantAwareUsername = authenticatedUser.getUserName();
        if (StringUtils.isNotBlank(username)) {
            String secretKey = TOTPUtil.getSecretKey(context, userId);
            Map<ClaimMapping, String> userAttributes = authenticatedUser.getUserAttributes();
            String email = getEmailForFederatedUser(userAttributes);
            String tenantDomain = authenticatedUser.getTenantDomain();

            sendEmailWithToken(username, authenticatedUser, tenantAwareUsername, username, secretKey,
                    email, tenantDomain, context);
        }
    }

    private static long sendEmailWithToken(String username, AuthenticatedUser authenticatedUser,
                                           String tenantAwareUsername, String firstName, String secretKey,
                                           String email, String tenantDomain, AuthenticationContext context)
            throws TOTPException {

        long token;
        byte[] secretKeyByteArray;
        try {
            String encoding = TOTPUtil.getEncodingMethod(tenantDomain, context);
            if (TOTPAuthenticatorConstants.BASE32.equals(encoding)) {
                Base32 codec32 = new Base32();
                secretKeyByteArray = codec32.decode(secretKey);
            } else {
                Base64 codec64 = new Base64();
                secretKeyByteArray = codec64.decode(secretKey);
            }
            token = generateToken(username, tenantDomain, secretKeyByteArray, context);
            // Check whether the authenticator is configured to use the event handler implementation.
            if (TOTPUtil.isEventHandlerBasedEmailSenderEnabled()) {
                if (log.isDebugEnabled()) {
                    log.debug("TOTP authenticator configured to use the event handler implementation.");
                }
                triggerEvent(authenticatedUser.getUserName(), authenticatedUser.getTenantDomain(), email,
                            authenticatedUser.getUserStoreDomain(), context);
            } else {
                sendNotification(tenantAwareUsername, firstName, tenantDomain, Long.toString(token), email);
            }
            if (log.isDebugEnabled()) {
                log.debug("Token is sent to via email to the user : " + tenantAwareUsername);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new TOTPException(
                    "TOTPTokenGenerator can't find the configured hashing algorithm", e);
        } catch (InvalidKeyException e) {
            throw new TOTPException("Secret key is not valid", e);
        } catch (AuthenticationFailedException e) {
            throw new TOTPException("TOTPTokenVerifier cannot find the property value for encodingMethod", e);
        }
        return token;
    }

    /**
     * Extract the email value from federated user attributes.
     *
     * @param userAttributes Map with federated user attributes.
     * @return Email attribute.
     */
    private static String getEmailForFederatedUser(Map<ClaimMapping, String> userAttributes) {

        String email = null;
        if (userAttributes != null) {
            for (Map.Entry<ClaimMapping, String> entry : userAttributes.entrySet()) {
                String key = String.valueOf(entry.getKey().getLocalClaim().getClaimUri());
                String value = entry.getValue();
                if ((TOTPAuthenticatorConstants.FEDERATED_EMAIL_ATTRIBUTE_KEY).equals(key)) {
                    email = String.valueOf(value);
                    break;
                }
            }
        }
        return email;
    }

    /**
     * Generate 6 digit TOTP token for a given secret key and time index.
     *
     * @param username     Username of the user.
     * @param tenantDomain Tenant domain of the user.
     * @param secret       Secret key in binary format.
     * @param context      Authentication context.
     * @return Six digit TOTP token value as a long.
     * @throws NoSuchAlgorithmException If the specific algorithm was not found.
     * @throws InvalidKeyException      If an invalid signKey provided.
     * @throws TOTPException            If an error occurred while getting the time index.
     */
    private static long generateToken(String username, String tenantDomain, byte[] secret,
                                      AuthenticationContext context)
            throws NoSuchAlgorithmException, InvalidKeyException, TOTPException {

        long token = getCode(secret, getTimeIndex(context));
        // We need to check whether the token at least have the minimum number of digits.
        if (isTokenHasMinimumChars(token)) {
            return token;
        }
		/*
		Calculate a new token with minimum chars. If we cannot generate an acceptable char within 5 attempts
		(MAX_TOKEN_CALCULATE_ATTEMPTS), we need to send the last generated code. This is highly unlikely scenario.
		 */
        for (int count = 0; count < MAX_TOKEN_CALCULATE_ATTEMPTS; count++) {
            token = getCode(secret, getTimeIndex(context));
            if (isTokenHasMinimumChars(token)) {
                return token;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug(String.format("Unable to generate a token with max character length for user: %s in " +
                            "tenant: %s.Therefore, sending the generated token in attempt: %s", username, tenantDomain,
                    MAX_TOKEN_CALCULATE_ATTEMPTS));
        }
        return token;
    }

    /**
     * Check whether the token has the minimum number of chars in it.
     *
     * @param token Generated token.
     * @return True if the token has the minimum number of chars.
     */
    private static boolean isTokenHasMinimumChars(long token) {

		/*
		If we can get a number which is larger than 0, when the token is multiplied by 10 and divided by
		TOKEN_HASH_DIVISOR, that means the token has the minimum number of chars.
		 */
        return token * 10 / TOKEN_HASH_DIVISOR > 0;
    }

    /**
     * Create the TOTP token for a given secret key and time index.
     *
     * @param secret    Secret key in binary format
     * @param timeIndex Number of Time elapse from the unix epoch time
     * @return TOTP token value as a long
     * @throws NoSuchAlgorithmException Could not find the specific algorithm
     * @throws InvalidKeyException      invalid signKey
     */
    private static long getCode(byte[] secret, long timeIndex)
            throws NoSuchAlgorithmException, InvalidKeyException {
        // Building the secret key specification for the HmacSHA1 algorithm.
        SecretKeySpec signKey =
                new SecretKeySpec(secret, TOTPAuthenticatorConstants.HMAC_ALGORITHM);
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putLong(timeIndex);
        byte[] timeBytes = buffer.array();
        // Getting an HmacSHA1 algorithm implementation from the Java Cryptography Extension (JCE).
        Mac mac = Mac.getInstance(TOTPAuthenticatorConstants.HMAC_ALGORITHM);
        // Initializing the MAC algorithm.
        mac.init(signKey);
        // Processing the instant of time and getting the encrypted data.
        byte[] hash = mac.doFinal(timeBytes);
        // Building the validation code performing dynamic truncation.
        int offset = hash[19] & 0xf;
        long truncatedHash = hash[offset] & 0x7f;
        for (int i = 1; i < 4; i++) {
            truncatedHash <<= 8;
            truncatedHash |= hash[offset + i] & 0xff;
        }
        truncatedHash %= TOKEN_HASH_DIVISOR;
        return truncatedHash;
    }

    /**
     * Send the TOTP token via MAILTO transport.
     *
     * @param tenantAwareUsername Tenant aware username of user
     * @param firstName           First name of user
     * @param token               Token which needs to be sent to user
     * @param email               Email address of the user
     * @throws TOTPException MAILTO transport sender is not defined
     */
    private static void sendNotification(String tenantAwareUsername, String firstName, String tenantDomain,
                                         String token, String email) throws TOTPException {
        ConfigurationContext configurationContext =
                TOTPDataHolder.getInstance().getConfigurationContextService()
                        .getServerConfigContext();
        if (configurationContext.getAxisConfiguration().getTransportsOut()
                .containsKey(TOTPAuthenticatorConstants.TRANSPORT_MAILTO)) {
            NotificationSender notificationSender = new NotificationSender();
            NotificationDataDTO notificationData = new NotificationDataDTO();
            Notification emailNotification;
            NotificationData emailNotificationData = new NotificationData();
            ConfigBuilder configBuilder = ConfigBuilder.getInstance();
            NotificationSendingModule module = new DefaultEmailSendingModule();
            int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
            String emailTemplate = null;
            Config config;
            try {
                config = configBuilder
                        .loadConfiguration(ConfigType.EMAIL, StorageType.REGISTRY, tenantId);
            } catch (IdentityMgtConfigException e) {
                throw new TOTPException("Error occurred while loading email templates for user : " +
                        tenantAwareUsername, e);
            }
            emailNotificationData.setTagData(FIRST_NAME, firstName);
            emailNotificationData.setTagData(TOTP_TOKEN, token);
            emailNotificationData.setSendTo(email);
            if (config.getProperties()
                    .containsKey(TOTPAuthenticatorConstants.EMAIL_TEMPLATE_NAME)) {
                emailTemplate = config.getProperty(TOTPAuthenticatorConstants.EMAIL_TEMPLATE_NAME);
                try {
                    emailNotification = NotificationBuilder
                            .createNotification("EMAIL", emailTemplate, emailNotificationData);
                } catch (IdentityMgtServiceException e) {
                    log.error("Error occurred while creating notification from email template : " +
                            emailTemplate, e);
                    throw new TOTPException(
                            "Error occurred while creating notification from email template : " +
                                    emailTemplate, e);
                }
                notificationData.setNotificationAddress(email);
                module.setNotificationData(notificationData);
                module.setNotification(emailNotification);
                notificationSender.sendNotification(module);
                notificationData.setNotificationSent(true);
            } else {
                throw new TOTPException("Unable to find the email template: " + emailTemplate);
            }
        } else {
            throw new TOTPException(
                    "MAILTO transport sender is not defined in axis2 configuration file");
        }
    }

    /**
     * Method to Trigger the TOTP otp event.
     *
     * @param userName            : Identity user name
     * @param tenantDomain        : Tenant domain of the user.
     * @param sendToAddress       The email address to send the otp.
     * @param userStoreDomainName : User store domain name of the user.
     * @param context             Authentication context
     * @throws AuthenticationFailedException : In occasions of failing sending the email to the user.
     * @throws TOTPException                 If an error occurred while decrypt the storedSecretKey
     * of the federated user.
     */
    private static void triggerEvent(String userName, String tenantDomain, String sendToAddress,
                                     String userStoreDomainName, AuthenticationContext context)
            throws AuthenticationFailedException, TOTPException {

        String secretKey;
        try {
            String eventName = IdentityEventConstants.Event.TRIGGER_NOTIFICATION;
            HashMap<String, Object> properties = new HashMap<>();
            properties.put(IdentityEventConstants.EventProperty.USER_NAME, userName);
            properties.put(IdentityEventConstants.EventProperty.USER_STORE_DOMAIN, userStoreDomainName);
            properties.put(IdentityEventConstants.EventProperty.TENANT_DOMAIN, tenantDomain);
            properties.put(TOTPAuthenticatorConstants.ATTRIBUTE_EMAIL_SENT_TO, sendToAddress);

            if (TOTPUtil.isLocalUser(context)) {
                secretKey = TOTPUtil.getSecretKeyOfLocalUser(context, userName);
            } else {
                if (context.getProperty(TOTPAuthenticatorConstants.FEDERATED_USER_ID) == null) {
                    throw new TOTPException("Error wile getting the federated user id for the user: " + userName);
                }
                String userId = context.getProperty(TOTPAuthenticatorConstants.FEDERATED_USER_ID).toString();
                secretKey = TOTPUtil.getSecretKey(context, userId);
            }
            String storeSecretKey = TOTPUtil.encrypt(secretKey);
            Map<String, String> claims = TOTPKeyGenerator.getGeneratedClaims(userName, tenantDomain, storeSecretKey,
                    false, context, true);

            properties.put(TOTPAuthenticatorConstants.SESSION_DATA_KEY, context.getContextIdentifier());
            properties.put(TOTPAuthenticatorConstants.QR_CODE_URL,
                    claims.get(TOTPAuthenticatorConstants.QR_CODE_CLAIM_URL));
            properties.put(TOTPAuthenticatorConstants.TEMPLATE_TYPE, TOTPAuthenticatorConstants.EVENT_NAME);
            Event identityMgtEvent = new Event(eventName, properties);
            TOTPDataHolder.getInstance().getIdentityEventService().handleEvent(identityMgtEvent);
        } catch (IdentityEventException e) {
            String errorMsg = "Error occurred while calling triggerNotification. " + e.getMessage();
            throw new AuthenticationFailedException(errorMsg, e.getCause());
        } catch (CryptoException e) {
            throw new TOTPException("TOTPKeyGenerator failed while encrypting the secret key " +
                    "of the user: " + userName, e);
        }
    }
}
