package me.synapsed.aws.utils;

import software.amazon.awscdk.Stack;

/**
 * Utility class for generating consistent resource names across the Synapsed platform.
 */
public class NamingUtils {

    /**
     * Generates a standardized resource name for AWS resources.
     * 
     * @param stack The CDK stack containing the resource
     * @param resourceName The name of the resource
     * @return A standardized resource name
     */
    public static String resourceName(Stack stack, String resourceName) {
        String stackName = stack.getNode().getId();
        return String.format("%s-%s", stackName, resourceName);
    }
} 