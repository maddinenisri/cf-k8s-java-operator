package com.mdstech.opearator.cloudformation;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder;
import com.amazonaws.services.cloudformation.model.*;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.Credentials;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;

import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StackController implements ResourceController<Stack> {

    private static final String ASSUME_ROLE = "ASSUME_ROLE_ARN";
    private static final String REGION = "AWS_REGION";
    private static final String DEFAULT_TAGS = "default_tags";
    private static final String DEFAULT_CAPABILITIES = "default_capabilities";

    private static final Tag STANDARD_TAG  = new Tag().withKey("kubernetes.io/controlled-by").withValue("cloudformation.mdstechinc.com/operator");
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final KubernetesClient kubernetesClient;

    public StackController(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    private AmazonCloudFormation createAWSClientSession() {
        if(System.getenv(ASSUME_ROLE) != null) {
            AWSSecurityTokenService sts_client = AWSSecurityTokenServiceClientBuilder.standard()
                    .withEndpointConfiguration(
                            new AwsClientBuilder.EndpointConfiguration("sts-endpoint.amazonaws.com", System.getenv(REGION)))
                    .build();
            Credentials credentials =
                    sts_client.assumeRole(new AssumeRoleRequest().withRoleArn(System.getenv(ASSUME_ROLE))).getCredentials();
            BasicSessionCredentials sessionCredentials = new BasicSessionCredentials(
                    credentials.getAccessKeyId(),
                    credentials.getSecretAccessKey(),
                    credentials.getSessionToken());

            return AmazonCloudFormationClientBuilder
                    .standard()
                    .withCredentials(new AWSStaticCredentialsProvider(sessionCredentials))
                    .withRegion(System.getenv(REGION))
                    .build();
        }
        else {
            return AmazonCloudFormationClientBuilder
                    .standard()
                    .withRegion(System.getenv(REGION))
                    .build();
        }
    }

    private Collection<Tag> convertToTags(Map<String, String> tags) {
        List<Tag> tagList = new ArrayList<>();
        tagList.add(STANDARD_TAG);

        if(System.getenv(DEFAULT_TAGS) != null) {

        }
        return tagList;
    }

    private Collection<Parameter> convertToParameters(Map<String, String> parameters) {
        return parameters.entrySet().stream()
                .map(entry -> new Parameter().withParameterKey(entry.getKey()).withParameterValue(entry.getValue())).collect(Collectors.toList());
    }

    private com.amazonaws.services.cloudformation.model.Stack waitForCompletion(AmazonCloudFormation stackbuilder, String stackName) throws Exception {
        DescribeStacksRequest wait = new DescribeStacksRequest();
        wait.setStackName(stackName);
        Boolean completed = false;
        String  stackStatus = "Unknown";
        String  stackReason = "";

        System.out.print("Waiting");
        com.amazonaws.services.cloudformation.model.Stack lastStack = null;
        while (!completed) {
            List<com.amazonaws.services.cloudformation.model.Stack> stacks = stackbuilder.describeStacks(wait).getStacks();
            if (stacks.isEmpty())
            {
                completed   = true;
                stackStatus = "NO_SUCH_STACK";
                stackReason = "Stack has been deleted";
            } else {
                for (com.amazonaws.services.cloudformation.model.Stack stack : stacks) {
                    if (stack.getStackStatus().equals(com.amazonaws.services.cloudformation.model.StackStatus.CREATE_COMPLETE.toString()) ||
                            stack.getStackStatus().equals(com.amazonaws.services.cloudformation.model.StackStatus.CREATE_FAILED.toString()) ||
                            stack.getStackStatus().equals(com.amazonaws.services.cloudformation.model.StackStatus.ROLLBACK_FAILED.toString()) ||
                            stack.getStackStatus().equals(com.amazonaws.services.cloudformation.model.StackStatus.DELETE_FAILED.toString())) {
                        completed = true;
                        stackStatus = stack.getStackStatus();
                        stackReason = stack.getStackStatusReason();
                        lastStack = stack;
                    }
                }
            }

            // Show we are waiting
            System.out.print(".");

            // Not done yet so sleep for 10 seconds.
            if (!completed) Thread.sleep(10000);
        }

        // Show we are done
        System.out.print("done\n");

        return lastStack;
    }

    private Map<String, String> convertToOuptut(List<Output> outputs) {
        return outputs.stream().collect(
                Collectors.toMap(Output::getOutputKey, Output::getOutputValue));
    }
    @Override
    public UpdateControl<Stack> createOrUpdateResource(Stack stack, Context<Stack> context) {
        AmazonCloudFormation amazonCloudFormation = createAWSClientSession();
        CreateStackRequest createStackRequest =  new CreateStackRequest();
        createStackRequest.setStackName(stack.getMetadata().getName());
        createStackRequest.setTemplateBody(stack.getSpec().getTemplate());
        createStackRequest.setParameters(convertToParameters(stack.getSpec().getParameters()));
        createStackRequest.setTags(convertToTags(stack.getSpec().getTags()));
        amazonCloudFormation.createStack(createStackRequest);
        try {
            com.amazonaws.services.cloudformation.model.Stack cfStack = waitForCompletion(amazonCloudFormation, stack.getMetadata().getName());
            StackStatus status = new StackStatus();
            status.setStackID(cfStack.getStackId());
            status.setOutputs(convertToOuptut(cfStack.getOutputs()));
            stack.setStatus(status);
            return UpdateControl.updateStatusSubResource(stack);
        }
        catch (Exception e) {
            log.error("Error while creating Schema", e);
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
        DeleteStackRequest deleteStackRequest = new DeleteStackRequest();
        deleteStackRequest.setStackName(stack.getMetadata().getName());
        amazonCloudFormation.deleteStack(deleteStackRequest);
        try {
            com.amazonaws.services.cloudformation.model.Stack cfStack = waitForCompletion(amazonCloudFormation, stack.getMetadata().getName());
            StackStatus status = new StackStatus();
            status.setStackID(cfStack.getStackId());
            status.setOutputs(convertToOuptut(cfStack.getOutputs()));
            stack.setStatus(status);
            return DeleteControl.DEFAULT_DELETE;
        }
        catch (Exception e) {
            log.error("Error while creating Schema", e);
            StackStatus status = new StackStatus();
            status.setStatus("ERROR");
            stack.setStatus(status);
            return DeleteControl.NO_FINALIZER_REMOVAL;
        }
    }
}
