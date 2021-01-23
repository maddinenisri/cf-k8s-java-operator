package com.mdstech.opearator.cloudformation;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.auth.WebIdentityTokenCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder;
import com.amazonaws.services.cloudformation.model.*;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.Credentials;
import io.javaoperatorsdk.operator.api.*;

import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
public class StackController implements ResourceController<Stack> {

    public static final String ASSUME_ROLE = "ASSUME_ROLE_ARN";
    public static final String REGION = "AWS_REGION";
    private static final String DEFAULT_TAGS = "DEFAULT_TAGS";
    private static final String DEFAULT_CAPABILITIES = "DEFAULT_CAPABILITIES";
    public static final String STATUS_CHECK_WAIT_TIME_IN_SEC="STATUS_CHECK_WAIT_TIME_IN_SEC";
    private static final Tag STANDARD_TAG  = new Tag().withKey("kubernetes.io/controlled-by").withValue("cloudformation.mdstechinc.com/operator");
    private final Logger log = LoggerFactory.getLogger(getClass());

    private String assumeRoleArn;
    private String region;
    private Collection<String>  defaultCapabilities;
    private List<Tag> defaultTags;
    private long statusWaitTime = 10000;
    private String roleSessionName = "awsCFSession";

    public StackController() {
        initializeEnvProperties();
    }

    private void initializeEnvProperties() {
        Arrays.asList(ASSUME_ROLE, REGION, DEFAULT_CAPABILITIES, DEFAULT_TAGS, STATUS_CHECK_WAIT_TIME_IN_SEC).stream().forEach(key -> {
            log.info(String.format("%s:  %s", key, System.getenv(key)));
        });
        region = getProperty(REGION, Regions.US_EAST_1.getName());
        assumeRoleArn = getProperty(ASSUME_ROLE, null);
        defaultCapabilities = convertToCapabilities(getProperty(DEFAULT_CAPABILITIES, null));
        defaultTags = convertToDefaultTags(getProperty(DEFAULT_TAGS, null));
        statusWaitTime = Long.parseLong(getProperty(STATUS_CHECK_WAIT_TIME_IN_SEC, "10"))*1000;
    }

    private String getProperty(String key, String defaultValue) {
        if(System.getenv(key) == null || System.getenv(key).isBlank()) {
            return defaultValue;
        }
        return System.getenv(key);
    }

    @Override
    public UpdateControl<Stack> createOrUpdateResource(Stack stack, Context<Stack> context) {
        log.info("Execution createOrUpdateResource for: {}", stack.getMetadata().getName());
        AmazonCloudFormation amazonCloudFormation = createAWSClientSession();
        boolean isStackExist = isStackExist(amazonCloudFormation, stack.getMetadata().getName());
        if(isStackExist) {
            updateStack(amazonCloudFormation, stack);
        }
        else {
            createStack(amazonCloudFormation, stack);
        }
        try {
            com.amazonaws.services.cloudformation.model.Stack cfStack = waitForCompletion(amazonCloudFormation, stack.getMetadata().getName());
            StackStatus status = new StackStatus();
            status.setStackID(cfStack.getStackId());
            status.setOutputs(convertToOutput(cfStack.getOutputs()));
            stack.setStatus(status);
            return UpdateControl.updateStatusSubResource(stack);
        }
        catch (Exception e) {
            log.error("Error while creating Stack", e);
            StackStatus status = new StackStatus();
            status.setStatus("ERROR");
            stack.setStatus(status);
            return UpdateControl.updateCustomResource(stack);
        }
    }

    @Override
    public DeleteControl deleteResource(Stack stack, Context<Stack> context) {
        log.info("Execution deleteResource for: {}", stack.getMetadata().getName());
        AmazonCloudFormation amazonCloudFormation = createAWSClientSession();
        DeleteStackRequest deleteStackRequest = new DeleteStackRequest().withStackName(stack.getMetadata().getName());
        amazonCloudFormation.deleteStack(deleteStackRequest);
        try {
            com.amazonaws.services.cloudformation.model.Stack cfStack = waitForCompletion(amazonCloudFormation, stack.getMetadata().getName());
            StackStatus status = new StackStatus();
            status.setStackID(cfStack.getStackId());
            status.setOutputs(convertToOutput(cfStack.getOutputs()));
            stack.setStatus(status);
            return DeleteControl.DEFAULT_DELETE;
        }
        catch (Exception e) {
            log.error("Error while deleting Stack", e);
            StackStatus status = new StackStatus();
            status.setStatus("ERROR");
            stack.setStatus(status);
            return DeleteControl.NO_FINALIZER_REMOVAL;
        }
    }

    private AmazonCloudFormation createAWSClientSession() {
        if(assumeRoleArn != null) {
            log.info("Establishing AWS session by using role assume role");
            AWSSecurityTokenService sts_client = AWSSecurityTokenServiceClientBuilder.standard().withRegion(region).build();
            Credentials credentials =
                    sts_client.assumeRole(
                            new AssumeRoleRequest()
                                    .withRoleArn(assumeRoleArn)
                                    .withRoleSessionName(roleSessionName))
                            .getCredentials();
            BasicSessionCredentials sessionCredentials = new BasicSessionCredentials(
                    credentials.getAccessKeyId(),
                    credentials.getSecretAccessKey(),
                    credentials.getSessionToken());

            return AmazonCloudFormationClientBuilder
                    .standard()
                    .withCredentials(new AWSStaticCredentialsProvider(sessionCredentials))
                    .withRegion(region)
                    .build();
        }
        else {
            log.info("Establishing AWS session using Service account role");
            return AmazonCloudFormationClientBuilder
                    .standard()
                    .withCredentials(WebIdentityTokenCredentialsProvider.create())
                    .withRegion(region)
                    .build();
        }
    }

    private List<Tag> convertToDefaultTags(String defaultTags) {
        List<Tag> tagList = new ArrayList<>();
        tagList.add(new Tag().withKey("kubernetes.io/controlled-by").withValue("cloudformation.mdstechinc.com/operator"));
        if(defaultTags != null) {
            tagList.addAll(Arrays.stream(defaultTags.split(","))
                    .map(tag -> tag.split(":"))
                    .map(pair -> new Tag().withKey(pair[0]).withValue(pair[1])).collect(Collectors.toList()));
        }
        return Collections.unmodifiableList(tagList);
    }

    private Collection<Tag> convertToTags(Map<String, String> tags) {
        List<Tag> tagList = new ArrayList<>();
        tagList.addAll(defaultTags);
        if(tags  != null) {
            tagList.addAll(tags.entrySet().stream().map(entry -> new Tag().withKey(entry.getKey()).withValue(entry.getValue())).collect(Collectors.toList()));
        }
        return tagList;
    }

    private Collection<Parameter> convertToParameters(Map<String, String> parameters) {
        if(parameters == null || parameters.isEmpty()) {
            return null;
        }
        return parameters.entrySet().stream()
                .map(entry -> new Parameter().withParameterKey(entry.getKey()).withParameterValue(entry.getValue())).collect(Collectors.toList());
    }

    private Collection<String> convertToCapabilities(String capabilities) {
        if(capabilities == null) {
            return null;
        }
        return Arrays.stream(capabilities.split(",")).collect(Collectors.toList());
    }

    private void createStack(AmazonCloudFormation amazonCloudFormation, Stack stack) {
        log.info("Execution createStack for: {}", stack.getMetadata().getName());
        CreateStackRequest createStackRequest =  new CreateStackRequest()
                .withCapabilities(defaultCapabilities)
                .withStackName(stack.getMetadata().getName())
                .withRoleARN(stack.getSpec().getCustomRoleARN())
                .withTemplateBody(stack.getSpec().getTemplate())
                .withParameters(convertToParameters(stack.getSpec().getParameters()))
                .withTags(convertToTags(stack.getSpec().getTags()));
        amazonCloudFormation.createStack(createStackRequest);
    }

    private void updateStack(AmazonCloudFormation amazonCloudFormation, Stack stack) {
        log.info("Execution updateStack for: {}", stack.getMetadata().getName());
        UpdateStackRequest updateStackRequest = new UpdateStackRequest()
                .withCapabilities(defaultCapabilities)
                .withStackName(stack.getMetadata().getName())
                .withTemplateBody(stack.getSpec().getTemplate())
                .withRoleARN(stack.getSpec().getCustomRoleARN())
                .withParameters(convertToParameters(stack.getSpec().getParameters()))
                .withTags(convertToTags(stack.getSpec().getTags()));
        amazonCloudFormation.updateStack(updateStackRequest);
    }

    private boolean isStackExist(AmazonCloudFormation amazonCloudFormation, String stackName) {
        log.info("Before verifying stack exist...");
        ListStacksResult listStacksResult = amazonCloudFormation.listStacks();
        long count = listStacksResult.getStackSummaries().stream().filter(stackSummary -> stackSummary.getStackName().equals(stackName)).count();
        return count > 0;
    }

    private com.amazonaws.services.cloudformation.model.Stack waitForCompletion(AmazonCloudFormation amazonCloudFormation, String stackName) throws Exception {
        DescribeStacksRequest wait = new DescribeStacksRequest();
        wait.setStackName(stackName);
        Boolean completed = false;
        log.debug("Waiting for cloudformation stack completion");
        com.amazonaws.services.cloudformation.model.Stack lastStack = null;
        while (!completed) {
            List<com.amazonaws.services.cloudformation.model.Stack> stacks = amazonCloudFormation.describeStacks(wait).getStacks();
            if (stacks.isEmpty())
            {
                completed   = true;
            } else {
                for (com.amazonaws.services.cloudformation.model.Stack stack : stacks) {
                    if (stack.getStackStatus().equals(com.amazonaws.services.cloudformation.model.StackStatus.CREATE_COMPLETE.toString()) ||
                            stack.getStackStatus().equals(com.amazonaws.services.cloudformation.model.StackStatus.CREATE_FAILED.toString()) ||
                            stack.getStackStatus().equals(com.amazonaws.services.cloudformation.model.StackStatus.ROLLBACK_FAILED.toString()) ||
                            stack.getStackStatus().equals(com.amazonaws.services.cloudformation.model.StackStatus.DELETE_FAILED.toString())) {
                        completed = true;
                        lastStack = stack;
                    }
                }
            }
            log.debug("Waiting for cloudformation stack completion...");
            if (!completed) {
                Thread.sleep(statusWaitTime);
            }
        }
        log.info("Cloudformation process is completed");
        return lastStack;
    }

    private Map<String, String> convertToOutput(List<Output> outputs) {
        if(outputs == null || outputs.isEmpty()) {
            return null;
        }
        return outputs.stream().collect(
                Collectors.toMap(Output::getOutputKey, Output::getOutputValue));
    }
}
