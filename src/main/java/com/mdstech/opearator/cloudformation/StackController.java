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
        stack.getMetadata().getOwnerReferences().stream().forEach(or -> log.info(or.toString()));
        boolean isStackExist = isStackExist(amazonCloudFormation, stack.getMetadata().getName(), Arrays.asList("CREATE_COMPLETE", "UPDATE_COMPLETE"));
        List<com.amazonaws.services.cloudformation.model.StackStatus> statuses;
        if(isStackExist) {
            log.info("Before update stack: {}", stack.getMetadata().getName());
            try {
                updateStack(amazonCloudFormation, stack);
            }
            catch (AmazonCloudFormationException ex) {
                log.error(ex.getErrorCode()+":"+ex.getErrorMessage());
            }
            statuses = Arrays.asList(com.amazonaws.services.cloudformation.model.StackStatus.UPDATE_COMPLETE,
                    com.amazonaws.services.cloudformation.model.StackStatus.UPDATE_ROLLBACK_FAILED,
                    com.amazonaws.services.cloudformation.model.StackStatus.UPDATE_ROLLBACK_COMPLETE);
        }
        else {
            log.info("Before create stack: {}", stack.getMetadata().getName());
            try {
                createStack(amazonCloudFormation, stack);
            }
            catch (AmazonCloudFormationException ex) {
                log.error(ex.getErrorCode()+":"+ex.getErrorMessage());
            }
            statuses = Arrays.asList(com.amazonaws.services.cloudformation.model.StackStatus.CREATE_COMPLETE,
                    com.amazonaws.services.cloudformation.model.StackStatus.CREATE_FAILED,
                    com.amazonaws.services.cloudformation.model.StackStatus.ROLLBACK_FAILED);
        }
        try {
            com.amazonaws.services.cloudformation.model.Stack cfStack = waitForCompletion(amazonCloudFormation, stack.getMetadata().getName(), statuses);
            if(cfStack != null) {
                updateStatus(stack, cfStack, isStackExist);
            }
            return UpdateControl.updateStatusSubResource(stack);
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

    private void updateStatus(Stack stack, com.amazonaws.services.cloudformation.model.Stack cfStack, boolean isUpdateStackEvent) {
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
            stack.setStatus(status);
        }
    }

    @Override
    public DeleteControl deleteResource(Stack stack, Context<Stack> context) {
        log.info("Execution deleteResource for: {}", stack.getMetadata().getName());
        AmazonCloudFormation amazonCloudFormation = createAWSClientSession();
        boolean isStackDeleted = isStackExist(amazonCloudFormation, stack.getMetadata().getName(), Arrays.asList("DELETE_COMPLETE"));
        log.info("Stack {} exists: {} and metadata timestamp {}", stack.getMetadata().getName(), isStackDeleted, stack.getMetadata().getDeletionTimestamp());
        if(isStackDeleted) {
            return DeleteControl.DEFAULT_DELETE;
        }
        DeleteStackRequest deleteStackRequest = new DeleteStackRequest().withStackName(stack.getMetadata().getName());
        amazonCloudFormation.deleteStack(deleteStackRequest);
        try {
            waitForCompletion(amazonCloudFormation, stack.getMetadata().getName(),
                    Arrays.asList(com.amazonaws.services.cloudformation.model.StackStatus.DELETE_COMPLETE,
                            com.amazonaws.services.cloudformation.model.StackStatus.DELETE_FAILED));
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
                .withTemplateURL(stack.getSpec().getTemplateURL())
                .withParameters(convertToParameters(stack.getSpec().getParameters()))
                .withTags(convertToTags(stack.getSpec().getTags()));
        log.info("Create Stack {}", createStackRequest);
        amazonCloudFormation.createStack(createStackRequest);
    }

    private void updateStack(AmazonCloudFormation amazonCloudFormation, Stack stack) {
        log.info("Execution updateStack for: {}", stack.getMetadata().getName());
        UpdateStackRequest updateStackRequest = new UpdateStackRequest()
                .withCapabilities(defaultCapabilities)
                .withStackName(stack.getMetadata().getName())
                .withRoleARN(stack.getSpec().getCustomRoleARN())
                .withTemplateBody(stack.getSpec().getTemplate())
                .withTemplateURL(stack.getSpec().getTemplateURL())
                .withParameters(convertToParameters(stack.getSpec().getParameters()))
                .withTags(convertToTags(stack.getSpec().getTags()));
        log.info("UpdateStackRequest {}", updateStackRequest);

        UpdateStackResult updateStackResult = amazonCloudFormation.updateStack(updateStackRequest);
        log.info(updateStackResult.toString());
    }

    private boolean isStackExist(AmazonCloudFormation amazonCloudFormation, String stackName, List<String> stackStatuses) {
        log.info("Before verifying stack {} and status {} exists...", stackName, stackStatuses);
        ListStacksResult listStacksResult = amazonCloudFormation.listStacks(
                new ListStacksRequest().withStackStatusFilters(stackStatuses));
        List<String> stackSummaries =
                listStacksResult
                        .getStackSummaries()
                        .stream()
                        .filter(stackSummary -> stackSummary.getStackName().equals(stackName) && stackStatuses.contains(stackSummary.getStackStatus()))
                        .map(StackSummary::getStackName)
                        .collect(Collectors.toList());
        log.info("Stack {} exsits : {}" + stackName, stackSummaries.contains(stackName));
        return stackSummaries.contains(stackName);
    }

    private com.amazonaws.services.cloudformation.model.Stack waitForCompletion(AmazonCloudFormation amazonCloudFormation,
                                     String stackName,
                                     List<com.amazonaws.services.cloudformation.model.StackStatus> waitStatuses) throws InterruptedException {
        DescribeStacksRequest wait = new DescribeStacksRequest();
        wait.setStackName(stackName);
        log.info("Waiting for cloud formation stack completion");
        List<com.amazonaws.services.cloudformation.model.Stack> stacks = amazonCloudFormation.describeStacks(wait).getStacks();
        if (stacks.isEmpty()) {
            return null;
        }
        else {
            BlockingQueue<com.amazonaws.services.cloudformation.model.Stack> stackBlockingQueue = new LinkedBlockingQueue<>();
            new Thread(() -> {
                try {
                    List<com.amazonaws.services.cloudformation.model.Stack> cfStacks = amazonCloudFormation.describeStacks(wait).getStacks();
                    com.amazonaws.services.cloudformation.model.Stack stack =
                            cfStacks
                                    .stream()
                                    .filter(cfStack -> cfStack.getStackName().equals(stackName))
                                    .findFirst()
                                    .orElse(new com.amazonaws.services.cloudformation.model.Stack());
                    log.info("Stack {} status {} ", stack.getStackName(), stack.getStackStatus());
                    AtomicBoolean waiting = new AtomicBoolean(true);
                    if(waitStatuses.contains(com.amazonaws.services.cloudformation.model.StackStatus.fromValue(stack.getStackStatus()))) {
                        stackBlockingQueue.put(stack);
                        waiting.set(false);
                    }
                    while(waiting.get()) {
                        TimeUnit.SECONDS.sleep(10);
                        log.info("Stack returned status {} and after thread wakeup", stack.getStackStatus());
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }).start();
            return stackBlockingQueue.poll(4, TimeUnit.SECONDS);
        }
    }

    private Map<String, String> convertToOutput(List<Output> outputs) {
        if(outputs == null || outputs.isEmpty()) {
            return null;
        }
        return outputs.stream().collect(
                Collectors.toMap(Output::getOutputKey, Output::getOutputValue));
    }
}
