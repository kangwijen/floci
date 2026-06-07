package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appsync.AppSyncClient;
import software.amazon.awssdk.services.appsync.model.AppSyncException;
import software.amazon.awssdk.services.appsync.model.CreateGraphqlApiRequest;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;
import software.amazon.awssdk.services.sts.StsClient;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end IAM enforcement tests for AppSync (REST JSON, Phase 2 APIs).
 */
@DisplayName("AppSync IAM Enforcement")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppSyncIamEnforcementIntegrationTest {

    private static final String USER = "appsync-iam-enf-user";
    private static final String REGION = "us-east-1";

    private static IamClient iam;
    private static String userAccessKeyId;
    private static String userSecretKey;
    private static String accountId;

    @BeforeAll
    static void setup() {
        Assumptions.assumeTrue(TestFixtures.isIamEnforcementEnabled(),
                "IAM enforcement is not enabled");

        iam = TestFixtures.iamClient();
        try (StsClient sts = TestFixtures.stsClient()) {
            accountId = sts.getCallerIdentity().account();
        }

        iam.createUser(CreateUserRequest.builder().userName(USER).build());
        CreateAccessKeyResponse keyResp = iam.createAccessKey(
                CreateAccessKeyRequest.builder().userName(USER).build());
        userAccessKeyId = keyResp.accessKey().accessKeyId();
        userSecretKey = keyResp.accessKey().secretAccessKey();
    }

    @AfterAll
    static void cleanup() {
        if (iam == null) {
            return;
        }
        try {
            iam.deleteUserPolicy(DeleteUserPolicyRequest.builder()
                    .userName(USER).policyName("inline-appsync-allow").build());
        } catch (Exception ignored) {
        }
        try {
            iam.deleteAccessKey(DeleteAccessKeyRequest.builder()
                    .userName(USER).accessKeyId(userAccessKeyId).build());
        } catch (Exception ignored) {
        }
        try {
            iam.deleteUser(DeleteUserRequest.builder().userName(USER).build());
        } catch (Exception ignored) {
        }
        iam.close();
    }

    private static AppSyncClient appSyncWithUserCredentials() {
        return AppSyncClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(userAccessKeyId, userSecretKey)))
                .build();
    }

    private static String allowCreateGraphqlApiPolicy(String resource) {
        return """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"appsync:CreateGraphqlApi","Resource":"%s"}
            ]}""".formatted(resource);
    }

    @Test
    @Order(1)
    @DisplayName("CreateGraphqlApi denied without policy")
    void createGraphqlApiDeniedWithoutPolicy() {
        try (AppSyncClient client = appSyncWithUserCredentials()) {
            assertThatThrownBy(() -> client.createGraphqlApi(CreateGraphqlApiRequest.builder()
                            .name(TestFixtures.uniqueName("appsync-deny"))
                            .authenticationType("API_KEY")
                            .build()))
                    .isInstanceOf(AppSyncException.class)
                    .extracting(e -> ((AppSyncException) e).statusCode())
                    .isEqualTo(403);
        }
    }

    @Test
    @Order(2)
    @DisplayName("CreateGraphqlApi allowed with apis/* resource")
    void createGraphqlApiAllowedWithApisWildcard() {
        String resource = "arn:aws:appsync:" + REGION + ":" + accountId + ":apis/*";

        iam.putUserPolicy(PutUserPolicyRequest.builder()
                .userName(USER)
                .policyName("inline-appsync-allow")
                .policyDocument(allowCreateGraphqlApiPolicy(resource))
                .build());

        try (AppSyncClient client = appSyncWithUserCredentials()) {
            assertThatCode(() -> client.createGraphqlApi(CreateGraphqlApiRequest.builder()
                            .name(TestFixtures.uniqueName("appsync-allow"))
                            .authenticationType("API_KEY")
                            .build()))
                    .doesNotThrowAnyException();
        }
    }
}
