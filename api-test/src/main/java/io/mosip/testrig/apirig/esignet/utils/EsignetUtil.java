package io.mosip.testrig.apirig.esignet.utils;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;

import javax.ws.rs.core.MediaType;

import org.apache.log4j.Logger;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.lang.JoseException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.SkipException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.mosip.testrig.apirig.dataprovider.BiometricDataProvider;
import io.mosip.testrig.apirig.dto.TestCaseDTO;
import io.mosip.testrig.apirig.esignet.testrunner.MosipTestRunner;
import io.mosip.testrig.apirig.testrunner.BaseTestCase;
import io.mosip.testrig.apirig.utils.AdminTestUtil;
import io.mosip.testrig.apirig.utils.CertsUtil;
import io.mosip.testrig.apirig.utils.EncryptionDecrptionUtil;
import io.mosip.testrig.apirig.utils.GlobalConstants;
import io.mosip.testrig.apirig.utils.JWKKeyUtil;
import io.mosip.testrig.apirig.utils.KeycloakUserManager;
import io.mosip.testrig.apirig.utils.RestClient;
import io.mosip.testrig.apirig.utils.SkipTestCaseHandler;
import io.restassured.response.Response;

public class EsignetUtil extends AdminTestUtil {

	private static final Logger logger = Logger.getLogger(EsignetUtil.class);
	
	public static String getIdentityPluginNameFromEsignetActuator() {
		// Possible values = IdaAuthenticatorImpl, MockAuthenticationService

		String plugin = getValueFromEsignetActuator("classpath:/application.properties",
				"mosip.esignet.integration.authenticator");

		if (plugin == null || plugin.isBlank() == true) {
			plugin = getValueFromEsignetActuator("classpath:/application-default.properties",
					"mosip.esignet.integration.authenticator");
		}
		
		if (plugin == null || plugin.isBlank() == true) {
			plugin = getValueFromEsignetActuator("mosip-config/esignet",
					"mosip.esignet.integration.authenticator");
		}

		return plugin;
	}
	
	public static void getSupportedLanguage() {
		String supportedLanguages = getValueFromSignupActuator("classpath:/application-default.properties",
				"mosip.signup.supported-languages");

		if (supportedLanguages != null && supportedLanguages.isBlank() == false) {
			supportedLanguages = supportedLanguages.replace("{", "").replace("}", "").replace("'", "");

			// Split the string by commas
			String[] languages = supportedLanguages.split(",");

			// Use a TreeSet to sort the languages
			Set<String> sortedLanguages = new TreeSet<>();
			for (String language : languages) {
				sortedLanguages.add(language.trim()); // Trim to remove any extra spaces
			}

			// Add sorted languages to the languageList
			BaseTestCase.languageList.addAll(sortedLanguages);

			logger.info("languageList " + BaseTestCase.languageList);
		} else {
			logger.error("Language not found");
		}
	}
	
//	private static final Map<String, String> actuatorValueCache = new HashMap<>();
	
	public static JSONArray esignetActuatorResponseArray = null;

	public static String getValueFromEsignetActuator(String section, String key) {
		String url = EsignetConfigManager.getEsignetBaseUrl() + EsignetConfigManager.getproperty("actuatorEsignetEndpoint");
		String actuatorCacheKey = url + section + key;
		String value = actuatorValueCache.get(actuatorCacheKey);
		if (value != null && !value.isEmpty())
			return value;

		try {
			if (esignetActuatorResponseArray == null) {
				Response response = null;
				JSONObject responseJson = null;
				response = RestClient.getRequest(url, MediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON);
				responseJson = new JSONObject(response.getBody().asString());
				esignetActuatorResponseArray = responseJson.getJSONArray("propertySources");
			}

			for (int i = 0, size = esignetActuatorResponseArray.length(); i < size; i++) {
				JSONObject eachJson = esignetActuatorResponseArray.getJSONObject(i);
				if (eachJson.get("name").toString().contains(section)) {
					logger.info(eachJson.getJSONObject(GlobalConstants.PROPERTIES));
					value = eachJson.getJSONObject(GlobalConstants.PROPERTIES).getJSONObject(key)
							.get(GlobalConstants.VALUE).toString();
					if (EsignetConfigManager.IsDebugEnabled())
						logger.info("Actuator: " + url + " key: " + key + " value: " + value);
					break;
				}
			}
			actuatorValueCache.put(actuatorCacheKey, value);

			return value;
		} catch (Exception e) {
			logger.error(GlobalConstants.EXCEPTION_STRING_2 + e);
			return value;
		}

	}
	
	public static JSONArray signupActuatorResponseArray = null;

	public static String getValueFromSignupActuator(String section, String key) {
		String url = EsignetConfigManager.getSignupBaseUrl() + EsignetConfigManager.getproperty("actuatorSignupEndpoint");
		String actuatorCacheKey = url + section + key;
		String value = actuatorValueCache.get(actuatorCacheKey);
		if (value != null && !value.isEmpty())
			return value;

		try {
			if (signupActuatorResponseArray == null) {
				Response response = null;
				JSONObject responseJson = null;
				response = RestClient.getRequest(url, MediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON);
				responseJson = new JSONObject(response.getBody().asString());
				signupActuatorResponseArray = responseJson.getJSONArray("propertySources");
			}

			for (int i = 0, size = signupActuatorResponseArray.length(); i < size; i++) {
				JSONObject eachJson = signupActuatorResponseArray.getJSONObject(i);
				if (eachJson.get("name").toString().contains(section)) {
					logger.info(eachJson.getJSONObject(GlobalConstants.PROPERTIES));
					value = eachJson.getJSONObject(GlobalConstants.PROPERTIES).getJSONObject(key)
							.get(GlobalConstants.VALUE).toString();
					if (EsignetConfigManager.IsDebugEnabled())
						logger.info("Actuator: " + url + " key: " + key + " value: " + value);
					break;
				}
			}
			actuatorValueCache.put(actuatorCacheKey, value);

			return value;
		} catch (Exception e) {
			logger.error(GlobalConstants.EXCEPTION_STRING_2 + e);
			return value;
		}

	}
	
	public static String isTestCaseValidForExecution(TestCaseDTO testCaseDTO) {
		String testCaseName = testCaseDTO.getTestCaseName();
		
		
		if (MosipTestRunner.skipAll == true) {
			throw new SkipException(GlobalConstants.PRE_REQUISITE_FAILED_MESSAGE);
		}
		
		
		if (getIdentityPluginNameFromEsignetActuator().toLowerCase().contains("mockauthenticationservice")) {
			
			// TO DO - need to conform whether esignet distinguishes between UIN and VID. BAsed on that need to remove VID test case from YAML.
			BaseTestCase.setSupportedIdTypes(Arrays.asList("UIN"));
			
			// Let run test cases eSignet & mock (for identity)   -- only UIN  test cases
			
			String endpoint = testCaseDTO.getEndPoint();
			if (endpoint.contains("/esignet/vci/") == true) {
				throw new SkipException(GlobalConstants.FEATURE_NOT_SUPPORTED_MESSAGE);
			}
			if (endpoint.contains("/esignet/vci/") == false && endpoint.contains("/esignet/") == false
					&& endpoint.contains("/v1/signup/") == false && endpoint.contains("/mock-identity-system/") == false
					&& endpoint.contains("$GETENDPOINTFROMWELLKNOWN$") == false) {
				throw new SkipException(GlobalConstants.FEATURE_NOT_SUPPORTED_MESSAGE);
			}
			if ((testCaseName.contains("_KycBioAuth_") || testCaseName.contains("_BioAuth_")
					|| testCaseName.contains("_SendBindingOtp_uin_Email_Valid_Smoke")
					|| testCaseName.contains("ESignet_AuthenticateUserIDP_NonAuth_uin_Otp_Valid_Smoke"))) {
				throw new SkipException(GlobalConstants.FEATURE_NOT_SUPPORTED_MESSAGE);
			}

		} else if (getIdentityPluginNameFromEsignetActuator().toLowerCase().contains("idaauthenticatorimpl")) {
			// Let run test cases eSignet & MOSIP API calls --- both UIN and VID

			BaseTestCase.setSupportedIdTypes(Arrays.asList("UIN", "VID"));

			String endpoint = testCaseDTO.getEndPoint();
			if (endpoint.contains("/v1/signup/") == true || endpoint.contains("/mock-identity-system/") == true
					|| ((testCaseName.equals("ESignet_CreateOIDCClient_all_Valid_Smoke_sid")
							|| testCaseName.equals("ESignet_CreateOIDCClient_Misp_Valid_Smoke_sid")
							|| testCaseName.equals("ESignet_CreateOIDCClient_NonAuth_all_Valid_Smoke_sid"))
							&& endpoint.contains("/v1/esignet/client-mgmt/oauth-client"))) {
				throw new SkipException(GlobalConstants.FEATURE_NOT_SUPPORTED_MESSAGE);
			}

			JSONArray individualBiometricsArray = new JSONArray(
					getValueFromAuthActuator("json-property", "individualBiometrics"));
			String individualBiometrics = individualBiometricsArray.getString(0);

			if ((testCaseName.contains("_KycBioAuth_") || testCaseName.contains("_BioAuth_")
					|| testCaseName.contains("_SendBindingOtp_uin_Email_Valid_Smoke"))
					&& (!isElementPresent(globalRequiredFields, individualBiometrics))) {
				throw new SkipException(GlobalConstants.FEATURE_NOT_SUPPORTED_MESSAGE);
			}

		} else if (getIdentityPluginNameFromEsignetActuator().toLowerCase().contains("sunbird")) {
			// Let run test cases eSignet & Sunbird (for identity)   -- only KBI 
			
		}
		
		if (testCaseDTO.isValidityCheckRequired()) {
			if (testCaseName.contains("uin") || testCaseName.contains("UIN") || testCaseName.contains("Uin")) {
				if (BaseTestCase.getSupportedIdTypesValue().contains("UIN")
						&& BaseTestCase.getSupportedIdTypesValue().contains("uin")) {
					throw new SkipException("Idtype UIN not supported skipping the testcase");
				}
			} else if (testCaseName.contains("vid") || testCaseName.contains("VID") || testCaseName.contains("Vid")) {
				if (BaseTestCase.getSupportedIdTypesValue().contains("VID")
						&& BaseTestCase.getSupportedIdTypesValue().contains("vid")) {
					throw new SkipException("Idtype VID not supported skipping the testcase");
				}
			}
		}

		if (SkipTestCaseHandler.isTestCaseInSkippedList(testCaseName)) {
			throw new SkipException(GlobalConstants.KNOWN_ISSUES);
		}

		return testCaseName;
	}
	
	public static String inputstringKeyWordHandeler(String jsonString, String testCaseName) {
		if (jsonString.contains("$ID:")) {
			String autoGenIdFileName = esignetAutoGeneratedIdPropFileName;
			jsonString = replaceIdWithAutogeneratedId(jsonString, "$ID:", autoGenIdFileName);
		}
		
		if (jsonString.contains(GlobalConstants.TIMESTAMP)) {
			jsonString = replaceKeywordValue(jsonString, GlobalConstants.TIMESTAMP, generateCurrentUTCTimeStamp());
		}
		
		if (testCaseName.contains("ESignet_GenerateApiKey_")) {
			KeycloakUserManager.createKeyCloakUsers(genPartnerName, genPartnerEmail, "AUTH_PARTNER");
		}
		
		if (testCaseName.contains("ESignet_GenerateApiKeyKyc_")) {
			KeycloakUserManager.createKeyCloakUsers(genPartnerName + "2n", "12d" + genPartnerEmail, "AUTH_PARTNER");
		}
		
		if (jsonString.contains("$THUMBPRINT$")) {
			jsonString = replaceKeywordValue(jsonString, "$THUMBPRINT$", EncryptionDecrptionUtil.idaFirThumbPrint);
		}
		
		if (jsonString.contains("$POLICYNUMBERFORSUNBIRDRC$")) {
			jsonString = replaceKeywordValue(jsonString, "$POLICYNUMBERFORSUNBIRDRC$",
					properties.getProperty("policyNumberForSunBirdRC"));
		}
		
		if (jsonString.contains("$FULLNAMEFORSUNBIRDRC$")) {
			jsonString = replaceKeywordValue(jsonString, "$FULLNAMEFORSUNBIRDRC$", fullNameForSunBirdRC);
		}
		
		if (jsonString.contains("$DOBFORSUNBIRDRC$")) {
			jsonString = replaceKeywordValue(jsonString, "$DOBFORSUNBIRDRC$", dobForSunBirdRC);
		}
		
		if (jsonString.contains("$CHALLENGEVALUEFORSUNBIRDC$")) {

			HashMap<String, String> mapForChallenge = new HashMap<String, String>();
			mapForChallenge.put(GlobalConstants.FULLNAME, fullNameForSunBirdRC);
			mapForChallenge.put(GlobalConstants.DOB, dobForSunBirdRC);

			String challenge = gson.toJson(mapForChallenge);

			String challengeValue = BiometricDataProvider.toBase64Url(challenge);

			jsonString = replaceKeywordValue(jsonString, "$CHALLENGEVALUEFORSUNBIRDC$", challengeValue);
		}
		
		if (jsonString.contains("$ENCRYPTEDSESSIONKEY$")) {
			jsonString = replaceKeywordValue(jsonString, "$ENCRYPTEDSESSIONKEY$", encryptedSessionKeyString);
		}
		
		if (jsonString.contains("$RANDOMIDFOROIDCCLIENT$")) {
			jsonString = replaceKeywordValue(jsonString, "$RANDOMIDFOROIDCCLIENT$",
					"mosip" + generateRandomNumberString(2) + Calendar.getInstance().getTimeInMillis());
		}
		
		if (jsonString.contains("$IDPREDIRECTURI$")) {
			jsonString = replaceKeywordValue(jsonString, "$IDPREDIRECTURI$",
					ApplnURI.replace(GlobalConstants.API_INTERNAL, "healthservices") + "/userprofile");
		}
		
		if (jsonString.contains("$BINDINGJWKKEY$")) {
			String jwkKey = "";
			if (gettriggerESignetKeyGen3()) {
//				jwkKey = generateAndWriteJWKKey(bindingJWK1);
				jwkKey = JWKKeyUtil.generateAndCacheJWKKey(BINDINGJWK1);

				settriggerESignetKeyGen3(false);
			} else {
//				jwkKey = getJWKKey(BINDINGJWK1);
				jwkKey = JWKKeyUtil.getJWKKey(BINDINGJWK1);

			}
			jsonString = replaceKeywordValue(jsonString, "$BINDINGJWKKEY$", jwkKey);
		}
		
		if (jsonString.contains("$BINDINGJWKKEYVID$")) {
			String jwkKey = "";
			if (gettriggerESignetKeyGen4()) {
				jwkKey = JWKKeyUtil.generateAndCacheJWKKey(BINDINGJWKVID);
				settriggerESignetKeyGen4(false);
			} else {
				jwkKey = JWKKeyUtil.getJWKKey(BINDINGJWKVID);
			}
			jsonString = replaceKeywordValue(jsonString, "$BINDINGJWKKEYVID$", jwkKey);
		}
		
		if (jsonString.contains("$BINDINGCONSENTJWKKEY$")) {
			String jwkKey = "";
			if (gettriggerESignetKeyGen5()) {
				jwkKey = JWKKeyUtil.generateAndCacheJWKKey(BINDINGCONSENTJWK);
				settriggerESignetKeyGen5(false);
			} else {
				jwkKey = JWKKeyUtil.getJWKKey(BINDINGCONSENTJWK);
			}
			jsonString = replaceKeywordValue(jsonString, "$BINDINGCONSENTJWKKEY$", jwkKey);
		}
		
		if (jsonString.contains("$BINDINGCONSENTJWKKEYVID$")) {
			String jwkKey = "";
			if (gettriggerESignetKeyGen6()) {
				jwkKey = JWKKeyUtil.generateAndCacheJWKKey(BINDINGCONSENTJWKVID);
				settriggerESignetKeyGen6(false);
			} else {
				jwkKey = JWKKeyUtil.getJWKKey(BINDINGCONSENTJWKVID);
			}
			jsonString = replaceKeywordValue(jsonString, "$BINDINGCONSENTJWKKEYVID$", jwkKey);
		}
		
		if (jsonString.contains("$BINDINGCONSENTSAMECLAIMJWKKEY$")) {
			String jwkKey = "";
			if (gettriggerESignetKeyGen7()) {
				jwkKey = JWKKeyUtil.generateAndCacheJWKKey(BINDINGCONSENTSAMECLAIMJWK);
				settriggerESignetKeyGen7(false);
			} else {
				jwkKey = JWKKeyUtil.getJWKKey(BINDINGCONSENTSAMECLAIMJWK);
			}
			jsonString = replaceKeywordValue(jsonString, "$BINDINGCONSENTSAMECLAIMJWKKEY$", jwkKey);
		}
		
		if (jsonString.contains("$BINDINGCONSENTSAMECLAIMVIDJWKKEY$")) {
			String jwkKey = "";
			if (gettriggerESignetKeyGen8()) {
				jwkKey = JWKKeyUtil.generateAndCacheJWKKey(BINDINGCONSENTVIDSAMECLAIMJWK);
				settriggerESignetKeyGen8(false);
			} else {
				jwkKey = JWKKeyUtil.getJWKKey(BINDINGCONSENTVIDSAMECLAIMJWK);
			}
			jsonString = replaceKeywordValue(jsonString, "$BINDINGCONSENTSAMECLAIMVIDJWKKEY$", jwkKey);
		}
		
		if (jsonString.contains("$BINDINGCONSENTEMPTYCLAIMJWKKEY$")) {
			String jwkKey = "";
			if (gettriggerESignetKeyGen9()) {
				jwkKey = JWKKeyUtil.generateAndCacheJWKKey(BINDINGCONSENTEMPTYCLAIMJWK);
				settriggerESignetKeyGen9(false);
			} else {
				jwkKey = JWKKeyUtil.getJWKKey(BINDINGCONSENTEMPTYCLAIMJWK);
			}
			jsonString = replaceKeywordValue(jsonString, "$BINDINGCONSENTEMPTYCLAIMJWKKEY$", jwkKey);
		}
		
		if (jsonString.contains("$BINDINGCONSENTUSER2JWKKEY$")) {
			String jwkKey = "";
			if (gettriggerESignetKeyGen10()) {
				jwkKey = JWKKeyUtil.generateAndCacheJWKKey(BINDINGCONSENTUSER2JWK);
				settriggerESignetKeyGen10(false);
			} else {
				jwkKey = JWKKeyUtil.getJWKKey(BINDINGCONSENTUSER2JWK);
			}
			jsonString = replaceKeywordValue(jsonString, "$BINDINGCONSENTUSER2JWKKEY$", jwkKey);
		}
		
		if (jsonString.contains("$BINDINGCONSENTVIDUSER2JWKKEY$")) {
			String jwkKey = "";
			if (gettriggerESignetKeyGen11()) {
				jwkKey = JWKKeyUtil.generateAndCacheJWKKey(BINDINGCONSENTVIDUSER2JWK);
				settriggerESignetKeyGen11(false);
			} else {
				jwkKey = JWKKeyUtil.getJWKKey(BINDINGCONSENTVIDUSER2JWK);
			}
			jsonString = replaceKeywordValue(jsonString, "$BINDINGCONSENTVIDUSER2JWKKEY$", jwkKey);
		}
		
		if (jsonString.contains("$OIDCJWKKEY$")) {
			String jwkKey = "";
			if (gettriggerESignetKeyGen1()) {
				jwkKey = JWKKeyUtil.generateAndCacheJWKKey(OIDCJWK1);
				settriggerESignetKeyGen1(false);
			} else {
				jwkKey = JWKKeyUtil.getJWKKey(OIDCJWK1);
			}
			jsonString = replaceKeywordValue(jsonString, "$OIDCJWKKEY$", jwkKey);
		}
		
		if (jsonString.contains("$OIDCJWKKEY2$")) {
			String jwkKey = "";
			if (gettriggerESignetKeyGen2()) {
				jwkKey = JWKKeyUtil.generateAndCacheJWKKey(OIDCJWK2);
				settriggerESignetKeyGen2(false);
			} else {
				jwkKey = JWKKeyUtil.getJWKKey(OIDCJWK2);
			}
			jsonString = replaceKeywordValue(jsonString, "$OIDCJWKKEY2$", jwkKey);
		}
		
		if (jsonString.contains("$OIDCJWKKEY3$")) {
			String jwkKey = "";
			if (gettriggerESignetKeyGen12()) {
				jwkKey = JWKKeyUtil.generateAndCacheJWKKey(OIDCJWK3);
				settriggerESignetKeyGen12(false);
			} else {
				jwkKey = JWKKeyUtil.getJWKKey(OIDCJWK3);
			}
			jsonString = replaceKeywordValue(jsonString, "$OIDCJWKKEY3$", jwkKey);
		}
		
		if (jsonString.contains("$OIDCJWKKEY4$")) {
			String jwkKey = "";
			if (gettriggerESignetKeyGen13()) {
				jwkKey = JWKKeyUtil.generateAndCacheJWKKey(OIDCJWK4);
				settriggerESignetKeyGen13(false);
			} else {
				jwkKey = JWKKeyUtil.getJWKKey(OIDCJWK4);
			}
			jsonString = replaceKeywordValue(jsonString, "$OIDCJWKKEY4$", jwkKey);
		}
		
		if (jsonString.contains("$CLIENT_ASSERTION_JWK$")) {
			String oidcJWKKeyString = JWKKeyUtil.getJWKKey(OIDCJWK1);
			logger.info("oidcJWKKeyString =" + oidcJWKKeyString);
			try {
				oidcJWKKey1 = RSAKey.parse(oidcJWKKeyString);
				logger.info("oidcJWKKey1 =" + oidcJWKKey1);
			} catch (java.text.ParseException e) {
				logger.error(e.getMessage());
			}
			JSONObject request = new JSONObject(jsonString);
			String clientId = null;
			if (request.has("client_id")) {
				clientId = request.get("client_id").toString();
			}
			
			String tempUrl = getValueFromEsignetWellKnownEndPoint("token_endpoint", EsignetConfigManager.getEsignetBaseUrl());
			
			jsonString = replaceKeywordValue(jsonString, "$CLIENT_ASSERTION_JWK$",
					signJWKKey(clientId, oidcJWKKey1, tempUrl));
		}
		
		if (jsonString.contains("$CLIENT_ASSERTION_USER3_JWK$")) {
			String oidcJWKKeyString = JWKKeyUtil.getJWKKey(OIDCJWK3);
			logger.info("oidcJWKKeyString =" + oidcJWKKeyString);
			try {
				oidcJWKKey3 = RSAKey.parse(oidcJWKKeyString);
				logger.info("oidcJWKKey3 =" + oidcJWKKey3);
			} catch (java.text.ParseException e) {
				logger.error(e.getMessage());
			}
			JSONObject request = new JSONObject(jsonString);
			String clientId = null;
			if (request.has("client_id")) {
				clientId = request.get("client_id").toString();
			}
			
			String tempUrl = getValueFromEsignetWellKnownEndPoint("token_endpoint", EsignetConfigManager.getEsignetBaseUrl());
			jsonString = replaceKeywordValue(jsonString, "$CLIENT_ASSERTION_USER3_JWK$",
					signJWKKey(clientId, oidcJWKKey3, tempUrl));
		}
		
		if (jsonString.contains("$CLIENT_ASSERTION_USER4_JWK$")) {
			String oidcJWKKeyString = JWKKeyUtil.getJWKKey(OIDCJWK4);
			logger.info("oidcJWKKeyString =" + oidcJWKKeyString);
			try {
				oidcJWKKey4 = RSAKey.parse(oidcJWKKeyString);
				logger.info("oidcJWKKey4 =" + oidcJWKKey4);
			} catch (java.text.ParseException e) {
				logger.error(e.getMessage());
			}
			JSONObject request = new JSONObject(jsonString);
			String clientId = null;
			if (request.has("client_id")) {
				clientId = request.get("client_id").toString();
			}
			jsonString = replaceKeywordValue(jsonString, "$CLIENT_ASSERTION_USER4_JWK$",
					signJWKKeyForMock(clientId, oidcJWKKey4));
		}
		
		if (jsonString.contains("$WLATOKEN$")) {
			jsonString = replaceKeywordValue(jsonString, "$WLATOKEN$",
					generateWLAToken(jsonString, BINDINGJWK1, BINDINGCERTFILE));
		}
		
		if (jsonString.contains("$WLATOKENVID$")) {
			jsonString = replaceKeywordValue(jsonString, "$WLATOKENVID$",
					generateWLAToken(jsonString, BINDINGJWKVID, BINDINGCERTFILEVID));
		}
		
		if (jsonString.contains("$WLATOKENCONSENT$")) {
			jsonString = replaceKeywordValue(jsonString, "$WLATOKENCONSENT$",
					generateWLAToken(jsonString, BINDINGCONSENTJWK, BINDINGCERTCONSENTFILE));
		}
		
		if (jsonString.contains("$CONSENTDETACHEDSIGNATURE$")) {
			jsonString = replaceKeywordValue(jsonString, "$CONSENTDETACHEDSIGNATURE$",
					generateDetachedSignature(jsonString, BINDINGCONSENTJWK, BINDINGCERTCONSENTFILE));
		}
		
		if (jsonString.contains("$WLATOKENCONSENTVID$")) {
			jsonString = replaceKeywordValue(jsonString, "$WLATOKENCONSENTVID$",
					generateWLAToken(jsonString, BINDINGCONSENTJWKVID, BINDINGCERTCONSENTVIDFILE));
		}
		
		if (jsonString.contains("$CONSENTDETACHEDSIGNATUREVID$")) {
			jsonString = replaceKeywordValue(jsonString, "$CONSENTDETACHEDSIGNATUREVID$",
					generateDetachedSignature(jsonString, BINDINGCONSENTJWKVID, BINDINGCERTCONSENTVIDFILE));
		}
		
		if (jsonString.contains("$WLATOKENCONSENTSAMECLAIM$")) {
			jsonString = replaceKeywordValue(jsonString, "$WLATOKENCONSENTSAMECLAIM$",
					generateWLAToken(jsonString, BINDINGCONSENTSAMECLAIMJWK, BINDINGCERTCONSENTSAMECLAIMFILE));
		}
		
		if (jsonString.contains("$CONSENTDETACHEDSIGNATURESAMECLAIM$")) {
			jsonString = replaceKeywordValue(jsonString, "$CONSENTDETACHEDSIGNATURESAMECLAIM$",
					generateDetachedSignature(jsonString, BINDINGCONSENTSAMECLAIMJWK, BINDINGCERTCONSENTSAMECLAIMFILE));
		}
		
		if (jsonString.contains("$WLATOKENCONSENTVIDSAMECLAIM$")) {
			jsonString = replaceKeywordValue(jsonString, "$WLATOKENCONSENTVIDSAMECLAIM$",
					generateWLAToken(jsonString, BINDINGCONSENTVIDSAMECLAIMJWK, BINDINGCERTCONSENTVIDSAMECLAIMFILE));
		}
		
		if (jsonString.contains("$CONSENTDETACHEDSIGNATUREVIDSAMECLAIM$")) {
			jsonString = replaceKeywordValue(jsonString, "$CONSENTDETACHEDSIGNATUREVIDSAMECLAIM$",
					generateDetachedSignature(jsonString, BINDINGCONSENTVIDSAMECLAIMJWK,
							BINDINGCERTCONSENTVIDSAMECLAIMFILE));
		}
		
		if (jsonString.contains("$WLATOKENCONSENTEMPTYCLAIM$")) {
			jsonString = replaceKeywordValue(jsonString, "$WLATOKENCONSENTEMPTYCLAIM$",
					generateWLAToken(jsonString, BINDINGCONSENTEMPTYCLAIMJWK, BINDINGCERTCONSENTEMPTYCLAIMFILE));
		}
		
		if (jsonString.contains("$WLATOKENCONSENTUSER2$")) {
			jsonString = replaceKeywordValue(jsonString, "$WLATOKENCONSENTUSER2$",
					generateWLAToken(jsonString, BINDINGCONSENTUSER2JWK, BINDINGCERTCONSENTSAMECLAIMFILE));
		}
		
		if (jsonString.contains("$CONSENTDETACHEDSIGNATUREUSER2$")) {
			jsonString = replaceKeywordValue(jsonString, "$CONSENTDETACHEDSIGNATUREUSER2$",
					generateDetachedSignature(jsonString, BINDINGCONSENTUSER2JWK, BINDINGCERTCONSENTUSER2FILE));
		}
		
		if (jsonString.contains("$WLATOKENCONSENTVIDUSER2$")) {
			jsonString = replaceKeywordValue(jsonString, "$WLATOKENCONSENTVIDUSER2$",
					generateWLAToken(jsonString, BINDINGCONSENTVIDUSER2JWK, BINDINGCERTCONSENTSAMECLAIMFILE));
		}
		
		if (jsonString.contains("$CONSENTDETACHEDSIGNATUREVIDUSER2$")) {
			jsonString = replaceKeywordValue(jsonString, "$CONSENTDETACHEDSIGNATUREVIDUSER2$",
					generateDetachedSignature(jsonString, BINDINGCONSENTVIDUSER2JWK, BINDINGCERTCONSENTSAMECLAIMFILE));
		}
		
		if (jsonString.contains("$PROOFJWT$")) {

			String oidcJWKKeyString = JWKKeyUtil.getJWKKey(OIDCJWK1);
			logger.info("oidcJWKKeyString =" + oidcJWKKeyString);
			try {
				oidcJWKKey1 = RSAKey.parse(oidcJWKKeyString);
				logger.info("oidcJWKKey1 =" + oidcJWKKey1);
			} catch (java.text.ParseException e) {
				logger.error(e.getMessage());
			}

			JSONObject request = new JSONObject(jsonString);
			String clientId = "";
			String accessToken = "";
			if (request.has("client_id")) {
				clientId = request.getString("client_id");
				request.remove("client_id");
			}
			if (request.has("idpAccessToken")) {
				accessToken = request.getString("idpAccessToken");
			}
			jsonString = request.toString();
			jsonString = replaceKeywordValue(jsonString, "$PROOFJWT$",
					signJWK(clientId, accessToken, oidcJWKKey1, testCaseName));
		}
		
		if (jsonString.contains("$PROOF_JWT_2$")) {

			String oidcJWKKeyString = JWKKeyUtil.getJWKKey(OIDCJWK4);
			logger.info("oidcJWKKeyString =" + oidcJWKKeyString);
			try {
				oidcJWKKey4 = RSAKey.parse(oidcJWKKeyString);
				logger.info("oidcJWKKey4 =" + oidcJWKKey4);
			} catch (java.text.ParseException e) {
				logger.error(e.getMessage());
			}

			JSONObject request = new JSONObject(jsonString);
			String clientId = "";
			String accessToken = "";
			String tempUrl = "";
			if (request.has("client_id")) {
				clientId = request.getString("client_id");
				request.remove("client_id");
			}
			if (request.has("idpAccessToken")) {
				accessToken = request.getString("idpAccessToken");
			}
			jsonString = request.toString();
			tempUrl = getValueFromEsignetWellKnownEndPoint("issuer", EsignetConfigManager.getEsignetBaseUrl());
			if (tempUrl.contains("esignet.")) {
				tempUrl = tempUrl.replace("esignet.", EsignetConfigManager.getproperty("esignetMockBaseURL"));
			}
			jsonString = replaceKeywordValue(jsonString, "$PROOF_JWT_2$",
					signJWKForMock(clientId, accessToken, oidcJWKKey4, testCaseName, tempUrl));
		}
		
		return jsonString;
		
	}
	
	public static String replaceKeywordValue(String jsonString, String keyword, String value) {
		if (value != null && !value.isEmpty())
			return jsonString.replace(keyword, value);
		else {
			if (keyword.contains("$ID:"))
				throw new SkipException("Marking testcase as skipped as required field is empty " + keyword
						+ " please check the results of testcase: " + getTestCaseIDFromKeyword(keyword));
			else
				throw new SkipException("Marking testcase as skipped as required field is empty " + keyword);

		}
	}
	
	public static String getAuthTransactionId(String oidcTransactionId) {
		final String transactionId = oidcTransactionId.replaceAll("_|-", "");
		String lengthOfTransactionId = getValueFromEsignetActuator(
				EsignetConfigManager.getEsignetActuatorPropertySection(), "mosip.esignet.auth-txn-id-length");
		int authTransactionIdLength = lengthOfTransactionId != null ? Integer.parseInt(lengthOfTransactionId): 0;
	    final byte[] oidcTransactionIdBytes = transactionId.getBytes();
	    final byte[] authTransactionIdBytes = new byte[authTransactionIdLength];
	    int i = oidcTransactionIdBytes.length - 1;
	    int j = 0;
	    while(j < authTransactionIdLength) {
	        authTransactionIdBytes[j++] = oidcTransactionIdBytes[i--];
	        if(i < 0) { i = oidcTransactionIdBytes.length - 1; }
	    }
	    return new String(authTransactionIdBytes);
	}
	
	public static String getWlaToken(String individualId, RSAKey jwkKey, String certData)
			throws JoseException, JOSEException {
		String tempUrl = EsignetConfigManager.getproperty("validateBindingEndpoint");
		int idTokenExpirySecs = Integer
				.parseInt(getValueFromEsignetActuator(EsignetConfigManager.getEsignetActuatorPropertySection(),
						GlobalConstants.MOSIP_ESIGNET_ID_TOKEN_EXPIRE_SECONDS));
		Instant instant = Instant.now();
		long epochValue = instant.getEpochSecond();

		JSONObject payload = new JSONObject();
		payload.put("iss", "postman-inji");
		payload.put("aud", tempUrl);
		payload.put("sub", individualId);
		payload.put("iat", epochValue);
		payload.put("exp", epochValue + idTokenExpirySecs);

		X509Certificate certificate = (X509Certificate) convertToCertificate(certData);
		JsonWebSignature jwSign = new JsonWebSignature();
		if (certificate != null) {
			jwSign.setKeyIdHeaderValue(certificate.getSerialNumber().toString(10));
			jwSign.setX509CertSha256ThumbprintHeaderValue(certificate);
			jwSign.setPayload(payload.toString());
			jwSign.setAlgorithmHeaderValue(SIGN_ALGO);
			jwSign.setKey(jwkKey.toPrivateKey());
			jwSign.setDoKeyValidation(false);
			return jwSign.getCompactSerialization();
		}
		return "";
	}
	
	public static String signJWKKeyForMock(String clientId, RSAKey jwkKey) {
		String tempUrl = getValueFromEsignetWellKnownEndPoint("token_endpoint", EsignetConfigManager.getEsignetBaseUrl());
		if (tempUrl.contains("esignet.")) {
			tempUrl = tempUrl.replace("esignet.", EsignetConfigManager.getproperty("esignetMockBaseURL"));
		}
		int idTokenExpirySecs = Integer
				.parseInt(getValueFromEsignetActuator(EsignetConfigManager.getEsignetActuatorPropertySection(),
						GlobalConstants.MOSIP_ESIGNET_ID_TOKEN_EXPIRE_SECONDS));
		JWSSigner signer;

		try {
			signer = new RSASSASigner(jwkKey);

			Date currentTime = new Date();

			// Create a Calendar instance to manipulate time
			Calendar calendar = Calendar.getInstance();
			calendar.setTime(currentTime);

			// Add one hour to the current time
			calendar.add(Calendar.HOUR_OF_DAY, (idTokenExpirySecs / 3600)); // Adding one hour

			// Get the updated expiration time
			Date expirationTime = calendar.getTime();

			JWTClaimsSet claimsSet = new JWTClaimsSet.Builder().subject(clientId).audience(tempUrl).issuer(clientId)
					.issueTime(currentTime).expirationTime(expirationTime).build();

			logger.info("JWT current and expiry time " + currentTime + " & " + expirationTime);

			SignedJWT signedJWT = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwkKey.getKeyID()).build(), claimsSet);

			signedJWT.sign(signer);
			clientAssertionToken = signedJWT.serialize();
		} catch (Exception e) {
			logger.error("Exception while signing oidcJWKKey for client assertion: " + e.getMessage());
		}
		return clientAssertionToken;
	}
	
	public static String getDetachedSignature(String[] acceptedClaims, String[] permittedScope, RSAKey jwkKey,
			String certData) throws JoseException, JOSEException {
		JSONObject payload = new JSONObject();
		String signedJWT = null;

		if (acceptedClaims != null && acceptedClaims instanceof String[]) {
			Arrays.sort(acceptedClaims);
			payload.put("accepted_claims", acceptedClaims);
		}

		if (permittedScope != null && permittedScope instanceof String[]) {
			Arrays.sort(permittedScope);
			payload.put("permitted_authorized_scopes", permittedScope);
		}

		X509Certificate certificate = (X509Certificate) convertToCertificate(certData);
		JsonWebSignature jwSign = new JsonWebSignature();
		if (certificate != null) {
			jwSign.setX509CertSha256ThumbprintHeaderValue(certificate);
			jwSign.setPayload(payload.toString());
			jwSign.setAlgorithmHeaderValue(SIGN_ALGO);
			jwSign.setKey(jwkKey.toPrivateKey());
			jwSign.setDoKeyValidation(false);
			signedJWT = jwSign.getCompactSerialization();
			String[] parts = signedJWT.split("\\.");

			return parts[0] + "." + parts[2];
		}
		return "";
	}
	
	public static String generateDetachedSignature(String jsonString, String jwkKeyName, String certKeyName) {
		RSAKey jwkKey = null;
		String jwkKeyString = JWKKeyUtil.getJWKKey(jwkKeyName);
		logger.info("jwkKeyString =" + jwkKeyString);

		String[] acceptedClaims = null;
		JSONArray claimJsonArray = null;
		String[] permittedScope = null;
		JSONArray permittedScopeArray = null;
		String detachedSignature = "";
		String certificate = CertsUtil.getCertificate(certKeyName);
		JSONObject request = new JSONObject(jsonString);
		claimJsonArray = getArrayFromJson(request, "acceptedClaims");
		permittedScopeArray = getArrayFromJson(request, "permittedAuthorizeScopes");

		acceptedClaims = new String[claimJsonArray.length()];
		permittedScope = new String[permittedScopeArray.length()];

		for (int i = 0; i < claimJsonArray.length(); i++) {
			acceptedClaims[i] = claimJsonArray.getString(i);
		}
		if (acceptedClaims != null && acceptedClaims instanceof String[]) {
			Arrays.sort(acceptedClaims);
		}

		for (int i = 0; i < permittedScopeArray.length(); i++) {
			permittedScope[i] = permittedScopeArray.getString(i);
		}

		try {
			jwkKey = RSAKey.parse(jwkKeyString);
			logger.info("jwkKey =" + jwkKey);
			detachedSignature = getDetachedSignature(acceptedClaims, permittedScope, jwkKey, certificate);
		} catch (Exception e) {
			logger.error(e.getMessage());
		}

		return detachedSignature;

	}
	
	public static String generateWLAToken(String jsonString, String jwkKeyName, String certKeyName) {
		RSAKey jwkKey = null;
		String jwkKeyString = JWKKeyUtil.getJWKKey(jwkKeyName);
		logger.info("jwkKeyString =" + jwkKeyString);

		String individualId = "";
		String wlaToken = "";
		String certificate = CertsUtil.getCertificate(certKeyName);
		JSONObject request = new JSONObject(jsonString);
		individualId = request.getJSONObject(GlobalConstants.REQUEST).get(GlobalConstants.INDIVIDUALID).toString();

		try {
			jwkKey = RSAKey.parse(jwkKeyString);
			logger.info("jwkKey =" + jwkKey);
			wlaToken = getWlaToken(individualId, jwkKey, certificate);
		} catch (Exception e) {
			logger.error(e.getMessage());
		}

		return wlaToken;
	}
	
	public static String signJWK(String clientId, String accessToken, RSAKey jwkKey, String testCaseName) {
		String tempUrl = getValueFromEsignetWellKnownEndPoint("issuer", EsignetConfigManager.getEsignetBaseUrl());
		int idTokenExpirySecs = Integer
				.parseInt(getValueFromEsignetActuator(EsignetConfigManager.getEsignetActuatorPropertySection(),
						GlobalConstants.MOSIP_ESIGNET_ID_TOKEN_EXPIRE_SECONDS));
		JWSSigner signer;
		String proofJWT = "";
		String typ = "openid4vci-proof+jwt";
		JWK jwkHeader = jwkKey.toPublicJWK();
		SignedJWT signedJWT = null;

		try {
			signer = new RSASSASigner(jwkKey);
			Date currentTime = new Date();

			// Create a Calendar instance to manipulate time
			Calendar calendar = Calendar.getInstance();
			calendar.setTime(currentTime);

			// Add one hour to the current time
			calendar.add(Calendar.HOUR_OF_DAY, (idTokenExpirySecs / 3600)); // Adding one hour

			// Get the updated expiration time
			Date expirationTime = calendar.getTime();

			String[] jwtParts = accessToken.split("\\.");
			String jwtPayloadBase64 = jwtParts[1];
			byte[] jwtPayloadBytes = Base64.getDecoder().decode(jwtPayloadBase64);
			String jwtPayload = new String(jwtPayloadBytes, StandardCharsets.UTF_8);
			JWTClaimsSet claimsSet = null;
			String nonce = new ObjectMapper().readTree(jwtPayload).get("c_nonce").asText();

			if (testCaseName.contains("_Invalid_C_nonce_"))
				nonce = "jwt_payload.c_nonce123";
			else if (testCaseName.contains("_Empty_C_nonce_"))
				nonce = "";
			else if (testCaseName.contains("_SpaceVal_C_nonce_"))
				nonce = "  ";
			else if (testCaseName.contains("_Empty_Typ_"))
				typ = "";
			else if (testCaseName.contains("_SpaceVal_Typ_"))
				typ = "  ";
			else if (testCaseName.contains("_Invalid_Typ_"))
				typ = "openid4vci-123@proof+jwt";
			else if (testCaseName.contains("_Invalid_JwkHeader_"))
				jwkHeader = RSAKey.parse(JWKKeyUtil.getJWKKey(OIDCJWK2)).toPublicJWK();
			else if (testCaseName.contains("_Invalid_Aud_"))
				tempUrl = "sdfaf";
			else if (testCaseName.contains("_Empty_Aud_"))
				tempUrl = "";
			else if (testCaseName.contains("_SpaceVal_Aud_"))
				tempUrl = "  ";
			else if (testCaseName.contains("_Invalid_Iss_"))
				clientId = "sdfdsg";
			else if (testCaseName.contains("_Invalid_Exp_"))
				idTokenExpirySecs = 0;

			claimsSet = new JWTClaimsSet.Builder().audience(tempUrl).claim("nonce", nonce).issuer(clientId)
					.issueTime(currentTime).expirationTime(expirationTime).build();

			if (testCaseName.contains("_Missing_Typ_")) {
				signedJWT = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).jwk(jwkHeader).build(), claimsSet);
			} else if (testCaseName.contains("_Missing_JwkHeader_")) {
				signedJWT = new SignedJWT(
						new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType(typ)).build(), claimsSet);
			} else {
				signedJWT = new SignedJWT(
						new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType(typ)).jwk(jwkHeader).build(),
						claimsSet);
			}

			signedJWT.sign(signer);
			proofJWT = signedJWT.serialize();
		} catch (Exception e) {
			logger.error("Exception while signing proof_jwt to get credential: " + e.getMessage());
		}
		return proofJWT;
	}
	
	public static String signJWKForMock(String clientId, String accessToken, RSAKey jwkKey, String testCaseName,
			String tempUrl) {
		int idTokenExpirySecs = Integer
				.parseInt(getValueFromEsignetActuator(EsignetConfigManager.getEsignetActuatorPropertySection(),
						GlobalConstants.MOSIP_ESIGNET_ID_TOKEN_EXPIRE_SECONDS));
		JWSSigner signer;
		String proofJWT = "";
		String typ = "openid4vci-proof+jwt";
		JWK jwkHeader = jwkKey.toPublicJWK();
		SignedJWT signedJWT = null;

		try {
			signer = new RSASSASigner(jwkKey);
			Date currentTime = new Date();

			// Create a Calendar instance to manipulate time
			Calendar calendar = Calendar.getInstance();
			calendar.setTime(currentTime);

			// Add one hour to the current time
			calendar.add(Calendar.HOUR_OF_DAY, (idTokenExpirySecs / 3600)); // Adding one hour

			// Get the updated expiration time
			Date expirationTime = calendar.getTime();

			String[] jwtParts = accessToken.split("\\.");
			String jwtPayloadBase64 = jwtParts[1];
			byte[] jwtPayloadBytes = Base64.getDecoder().decode(jwtPayloadBase64);
			String jwtPayload = new String(jwtPayloadBytes, StandardCharsets.UTF_8);
			JWTClaimsSet claimsSet = null;
			String nonce = new ObjectMapper().readTree(jwtPayload).get("c_nonce").asText();

			claimsSet = new JWTClaimsSet.Builder().audience(tempUrl).claim("nonce", nonce).issuer(clientId)
					.issueTime(currentTime).expirationTime(expirationTime).build();
			signedJWT = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType(typ)).jwk(jwkHeader).build(),
					claimsSet);

			signedJWT.sign(signer);
			proofJWT = signedJWT.serialize();
		} catch (Exception e) {
			logger.error("Exception while signing proof_jwt to get credential: " + e.getMessage());
		}
		return proofJWT;
	}
	
	public static String signJWKKey(String clientId, RSAKey jwkKey, String tempUrl) {
		int idTokenExpirySecs = Integer
				.parseInt(getValueFromEsignetActuator(EsignetConfigManager.getEsignetActuatorPropertySection(),
						GlobalConstants.MOSIP_ESIGNET_ID_TOKEN_EXPIRE_SECONDS));
		JWSSigner signer;

		try {
			signer = new RSASSASigner(jwkKey);

			Date currentTime = new Date();

			// Create a Calendar instance to manipulate time
			Calendar calendar = Calendar.getInstance();
			calendar.setTime(currentTime);

			// Add one hour to the current time
			calendar.add(Calendar.HOUR_OF_DAY, (idTokenExpirySecs / 3600)); // Adding one hour

			// Get the updated expiration time
			Date expirationTime = calendar.getTime();

			JWTClaimsSet claimsSet = new JWTClaimsSet.Builder().subject(clientId).audience(tempUrl).issuer(clientId)
					.issueTime(currentTime).expirationTime(expirationTime).build();

			logger.info("JWT current and expiry time " + currentTime + " & " + expirationTime);

			SignedJWT signedJWT = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwkKey.getKeyID()).build(), claimsSet);

			signedJWT.sign(signer);
			clientAssertionToken = signedJWT.serialize();
		} catch (Exception e) {
			logger.error("Exception while signing oidcJWKKey for client assertion: " + e.getMessage());
		}
		return clientAssertionToken;
	}
	
	public static String getValueFromEsignetWellKnownEndPoint(String key, String baseURL) {
		String url = baseURL + EsignetConfigManager.getproperty("esignetWellKnownEndPoint");
		Response response = null;
		JSONObject responseJson = null;
		if (responseJson == null) {
			try {
				response = RestClient.getRequest(url, MediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON);
				responseJson = new org.json.JSONObject(response.getBody().asString());
				return responseJson.getString(key);
			} catch (Exception e) {
				logger.error(GlobalConstants.EXCEPTION_STRING_2 + e);
			}
		}
		return responseJson.getString(key);
	}
	
	private static final String SIGN_ALGO = "RS256";

	public static String clientAssertionToken;
	
	protected static final String BINDINGJWK1 = "bindingJWK1";
	protected static final String BINDINGJWKVID = "bindingJWKVid";
	protected static final String BINDINGCONSENTJWK = "bindingConsentJWK";
	protected static final String BINDINGCONSENTJWKVID = "bindingConsentJWKVid";
	protected static final String BINDINGCONSENTSAMECLAIMJWK = "bindingConsentSameClaimJWK";
	protected static final String BINDINGCONSENTVIDSAMECLAIMJWK = "bindingConsentVidSameClaimJWK";
	protected static final String BINDINGCONSENTEMPTYCLAIMJWK = "bindingConsentEmptyClaimJWK";
	protected static final String BINDINGCONSENTUSER2JWK = "bindingConsentUser2JWK";
	protected static final String BINDINGCONSENTVIDUSER2JWK = "bindingConsentVidUser2JWK";
	
	protected static final String OIDCJWK1 = "oidcJWK1";
	protected static final String OIDCJWK2 = "oidcJWK2";
	protected static final String OIDCJWK3 = "oidcJWK3";
	protected static final String OIDCJWK4 = "oidcJWK4";
	
	protected static RSAKey oidcJWKKey1 = null;
	protected static RSAKey oidcJWKKey3 = null;
	protected static RSAKey oidcJWKKey4 = null;
	
	protected static boolean triggerESignetKeyGen1 = true;
	protected static boolean triggerESignetKeyGen2 = true;
	protected static boolean triggerESignetKeyGen3 = true;
	protected static boolean triggerESignetKeyGen4 = true;
	protected static boolean triggerESignetKeyGen5 = true;
	protected static boolean triggerESignetKeyGen6 = true;
	protected static boolean triggerESignetKeyGen7 = true;
	protected static boolean triggerESignetKeyGen8 = true;
	protected static boolean triggerESignetKeyGen9 = true;
	protected static boolean triggerESignetKeyGen10 = true;
	protected static boolean triggerESignetKeyGen11 = true;
	protected static boolean triggerESignetKeyGen12 = true;
	protected static boolean triggerESignetKeyGen13 = true;
	
	private static boolean gettriggerESignetKeyGen3() {
		return triggerESignetKeyGen3;
	}
	
	private static void settriggerESignetKeyGen3(boolean value) {
		triggerESignetKeyGen3 = value;
	}
	
	private static boolean gettriggerESignetKeyGen4() {
		return triggerESignetKeyGen4;
	}
	
	private static void settriggerESignetKeyGen4(boolean value) {
		triggerESignetKeyGen4 = value;
	}
	
	private static boolean gettriggerESignetKeyGen5() {
		return triggerESignetKeyGen5;
	}
	
	private static void settriggerESignetKeyGen5(boolean value) {
		triggerESignetKeyGen5 = value;
	}
	
	private static boolean gettriggerESignetKeyGen6() {
		return triggerESignetKeyGen6;
	}
	
	private static void settriggerESignetKeyGen6(boolean value) {
		triggerESignetKeyGen6 = value;
	}
	
	private static boolean gettriggerESignetKeyGen7() {
		return triggerESignetKeyGen7;
	}
	
	private static void settriggerESignetKeyGen7(boolean value) {
		triggerESignetKeyGen7 = value;
	}
	
	private static boolean gettriggerESignetKeyGen8() {
		return triggerESignetKeyGen8;
	}
	
	private static void settriggerESignetKeyGen8(boolean value) {
		triggerESignetKeyGen8 = value;
	}
	
	private static boolean gettriggerESignetKeyGen9() {
		return triggerESignetKeyGen9;
	}
	
	private static void settriggerESignetKeyGen9(boolean value) {
		triggerESignetKeyGen9 = value;
	}
	
	private static boolean gettriggerESignetKeyGen10() {
		return triggerESignetKeyGen10;
	}
	
	private static void settriggerESignetKeyGen10(boolean value) {
		triggerESignetKeyGen10 = value;
	}
	
	private static boolean gettriggerESignetKeyGen11() {
		return triggerESignetKeyGen11;
	}
	
	private static void settriggerESignetKeyGen11(boolean value) {
		triggerESignetKeyGen11 = value;
	}
	
	private static boolean gettriggerESignetKeyGen1() {
		return triggerESignetKeyGen1;
	}
	
	private static void settriggerESignetKeyGen1(boolean value) {
		triggerESignetKeyGen1 = value;
	}
	
	private static void settriggerESignetKeyGen2(boolean value) {
		triggerESignetKeyGen2 = value;
	}

	private static boolean gettriggerESignetKeyGen2() {
		return triggerESignetKeyGen2;
	}
	
	private static void settriggerESignetKeyGen12(boolean value) {
		triggerESignetKeyGen12 = value;
	}

	private static boolean gettriggerESignetKeyGen12() {
		return triggerESignetKeyGen12;
	}
	
	private static void settriggerESignetKeyGen13(boolean value) {
		triggerESignetKeyGen13 = value;
	}

	private static boolean gettriggerESignetKeyGen13() {
		return triggerESignetKeyGen13;
	}
}