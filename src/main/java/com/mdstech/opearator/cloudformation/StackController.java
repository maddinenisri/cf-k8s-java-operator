package com.mdstech.opearator.cloudformation;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.auth.WebIdentityTokenCredentialsProvider;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
public class StackController implements ResourceController<Stack> {

    public static final String ASSUME_ROLE = "ASSUME_ROLE_ARN";
    public static final String REGION = "AWS_REGION";
    private static final String DEFAULT_TAGS = "DEFAULT_TAGS";
    private static final String DEFAULT_CAPABILITIES = "DEFAULT_CAPABILITIES";
    private static final Logger log = LoggerFactory.getLogger(StackController.class);

    private String assumeRoleArn;
    private String region;
    private Collection<String>  defaultCapabilities;
    private List<Tag> defaultTags;
    private String roleSessionName = "awsCFSession";

    public StackController() {
        initializeEnvProperties();
    }

    private void initializeEnvProperties() {
        Arrays.asList(ASSUME_ROLE, REGION, DEFAULT_CAPABILITIES, DEFAULT_TAGS).stream().forEach(key -> {
            log.info(String.format("%s:  %s", key, System.getenv(key)));
        });
        region = getProperty(REGION, Regions.US_EAST_1.getName());
        assumeRoleArn = getProperty(ASSUME_ROLE, null);
        defaultCapabilities = convertToCapabilities(getProperty(DEFAULT_CAPABILITIES, null));
        defaultTags = convertToDefaultTags(getProperty(DEFAULT_TAGS, null));
    }

    private String getProperty(String key, String defaultValue) {
        if(System.getenv(key) == null || System.getenv(key).isBlank()) {
            return defaultValue;
        }
        return System.getenv(key);
    }

    @Override
    public UpdateControl<Stack> createOrUpdateResource(Stack stack, Context<Stack> context) {
        log.info("Execution createOrUpdateResource for: {} and Stack is {}", stack.getMetadata().getName(), stack.getSpec());
        AmazonCloudFormation amazonCloudFormation = createAWSClientSession();
        TemplateProcessor templateProcessor = new TemplateProcessor(amazonCloudFormation, defaultTags, defaultCapabilities);
        stack.getMetadata().getOwnerReferences().stream().forEach(or -> log.info(or.toString()));
        boolean isStackExist = templateProcessor.isCreatedOrUpdatedStackExist(stack.getMetadata().getName());
        List<String> statuses;
        if(isStackExist) {
            log.info("Before update stack: {}", stack.getMetadata().getName());
            stack.getMetadata().getFinalizers().stream().forEach(log::info);
            UpdateStackResult updateStackResult = templateProcessor.updateStack(
                    stack.getMetadata().getName(),
                    stack.getSpec().getCustomRoleARN(),
                    stack.getSpec().getTemplateURL(),
                    stack.getSpec().getParameters(),
                    stack.getSpec().getTags());
            log.info("Update stack result: {}", updateStackResult);
            statuses = Arrays.asList("UPDATE_COMPLETE", "CREATE_COMPLETE", "UPDATE_ROLLBACK_FAILED","UPDATE_ROLLBACK_COMPLETE");
        }
        else {
            log.info("Before create stack: {}", stack.getMetadata().getName());
            CreateStackResult createStackResult = templateProcessor.createStack(
                    stack.getMetadata().getName(),
                    stack.getSpec().getCustomRoleARN(),
                    stack.getSpec().getTemplateURL(),
                    stack.getSpec().getParameters(),
                    stack.getSpec().getTags());
            log.info("Create stack result: {}", createStackResult);
            statuses = Arrays.asList("CREATE_FAILED", "CREATE_COMPLETE", "ROLLBACK_FAILED");
        }
        try {
            com.amazonaws.services.cloudformation.model.Stack cfStack = waitForCompletion(amazonCloudFormation, stack.getMetadata().getName(), statuses);
            if(cfStack != null) {
                return updateStatus(stack, cfStack, isStackExist);
            }
            else {
                log.warn("Returned cloud formation stacks is null");
                return UpdateControl.noUpdate();
            }
        }
        catch (Exception e) {
            log.error("Error while creating Stack", e);
            StackStatus stackStatus = new StackStatus();
            stackStatus.setStatus("ERROR");
            stack.setStatus(stackStatus);
            return UpdateControl.updateCustomResource(stack);
        }
    }

    private boolean isEqual(Map<String, String> first, Map<String, String> second) {
        if (first.size() != second.size()) {
            return false;
        }

        return first.entrySet().stream()
                .allMatch(e -> e.getValue().equals(second.get(e.getKey())));
    }

    private UpdateControl<Stack> updateStatus(Stack stack, com.amazonaws.services.cloudformation.model.Stack cfStack, boolean isUpdateStackEvent) {
        log.info("Before updated status for: {} and Update event {} ", stack.getMetadata().getName(), isUpdateStackEvent);
        Map<String, String> outputs = convertToOutput(cfStack.getOutputs());
        log.info("Status Outputs "+ outputs);
        if(!(isUpdateStackEvent ||
                stack.getStatus() == null ||
                cfStack.getStackId().equals(stack.getStatus().getStackID()) ||
                isEqual(outputs, stack.getStatus().getOutputs()))) {
            StackStatus status = new StackStatus();
            status.setStackID(cfStack.getStackId());
            status.setOutputs(outputs);
            status.setStatus(isUpdateStackEvent ? "UPDATED" : "CREATED");
//            stack.addFinalizer("stacks.cloudformation.mdstechinc.com/finalizer");
            stack.setStatus(status);
            return UpdateControl.updateCustomResource(stack);
        }
        return UpdateControl.noUpdate();
    }

    @Override
    public DeleteControl deleteResource(Stack stack, Context<Stack> context) {
        log.info("Execution deleteResource for: {}", stack.getMetadata().getName());
        AmazonCloudFormation amazonCloudFormation = createAWSClientSession();
        TemplateProcessor templateProcessor = new TemplateProcessor(amazonCloudFormation, defaultTags, defaultCapabilities);
        boolean isStackDeleted = templateProcessor.isStackDeletable(stack.getMetadata().getName());
        log.info("Stack {} deletable : {} and metadata timestamp {}", stack.getMetadata().getName(), isStackDeleted, stack.getMetadata().getDeletionTimestamp());
        if(!isStackDeleted) {
            StackStatus stackStatus = new StackStatus();
            stackStatus.setStatus("DELETED");
            stack.setStatus(stackStatus);
            stack.getMetadata().getFinalizers().stream().forEach(log::info);
            return DeleteControl.DEFAULT_DELETE;
        }
        try {
            DeleteStackResult deleteStackResult = templateProcessor.deleteStack(stack.getMetadata().getName());
            log.info("Delete stack result: {}", deleteStackResult);
            waitForCompletion(amazonCloudFormation, stack.getMetadata().getName(),
                    Arrays.asList("DELETE_COMPLETE","DELETE_FAILED"));
            StackStatus stackStatus = new StackStatus();
            stackStatus.setStatus("DELETED");
            stack.setStatus(stackStatus);
            stack.getMetadata().getFinalizers().stream().forEach(log::info);
            return DeleteControl.DEFAULT_DELETE;
        }
        catch (Exception e) {
            log.error("Error while deleting Stack", e);
            StackStatus status = new StackStatus();
            status.setStatus("ERROR");
            stack.setStatus(status);
            return DeleteControl.DEFAULT_DELETE;
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

    private Collection<String> convertToCapabilities(String capabilities) {
        if(capabilities == null) {
            return null;
        }
        return Arrays.stream(capabilities.split(",")).collect(Collectors.toList());
    }

    private com.amazonaws.services.cloudformation.model.Stack waitForCompletion(AmazonCloudFormation amazonCloudFormation,
                                     String stackName,
                                     List<String> waitStatuses) throws InterruptedException {
        DescribeStacksRequest wait = new DescribeStacksRequest();
        wait.setStackName(stackName);
        log.info("Waiting for cloud formation stack completion");
        Boolean completed = false;
        com.amazonaws.services.cloudformation.model.Stack lastStack = null;
        while (!completed) {
            List<com.amazonaws.services.cloudformation.model.Stack> stacks = amazonCloudFormation.describeStacks(wait).getStacks();
            if (stacks.isEmpty())
            {
                completed   = true;
            }
            else {
                for (com.amazonaws.services.cloudformation.model.Stack stack : stacks) {
                    if (waitStatuses.contains(stack.getStackStatus())) {
                        completed = true;
                        lastStack = stack;
                    }
                }
            }
            log.info("Waiting for cloudformation stack completion...");
            if (!completed) {
                Thread.sleep(1000);
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
