# Configuration Management Stack

## Overview

The Configuration Management Stack provides a centralized configuration system for the Synapse-D platform. It uses AWS Systems Manager Parameter Store and AWS Secrets Manager to securely store and manage configuration parameters and secrets.

## Components

### Parameter Store

The Parameter Store is used to store non-sensitive configuration parameters such as:
- Environment variables
- Feature flags
- Service endpoints
- Application settings

### Secrets Manager

The Secrets Manager is used to store sensitive information such as:
- API keys
- Database credentials
- Stripe API keys
- Webhook secrets

### IAM Roles and Policies

The stack creates IAM roles and policies to control access to the configuration resources:
- Read-only access for application components
- Write access for administrative functions
- Cross-account access policies

## Usage

### Storing Configuration

```java
// Store a parameter
ssmClient.putParameter(PutParameterRequest.builder()
    .name("/synapsed/prod/api/endpoint")
    .value("https://api.synapsed.app")
    .type(ParameterType.STRING)
    .build());

// Store a secret
secretsManagerClient.createSecret(CreateSecretRequest.builder()
    .name("/synapsed/prod/stripe/api-key")
    .secretString("sk_test_...")
    .build());
```

### Retrieving Configuration

```java
// Get a parameter
String endpoint = ssmClient.getParameter(GetParameterRequest.builder()
    .name("/synapsed/prod/api/endpoint")
    .withDecryption(false)
    .build())
    .parameter()
    .value();

// Get a secret
String apiKey = secretsManagerClient.getSecretValue(GetSecretValueRequest.builder()
    .secretId("/synapsed/prod/stripe/api-key")
    .build())
    .secretString();
```

## Integration with Other Stacks

The Configuration Management Stack is referenced by other stacks to provide configuration values:
- Security Stack: For security settings and API keys
- Relay Stack: For WebRTC configuration and subscription settings
- Subscription Stack: For Stripe API keys and webhook secrets
- Authentication Stack: For authentication settings and secrets

## Best Practices

1. Use hierarchical parameter names (e.g., `/synapsed/prod/service/parameter`)
2. Encrypt sensitive values using AWS KMS
3. Use IAM policies to restrict access to sensitive parameters
4. Implement versioning for configuration changes
5. Use tags to organize and track configuration resources 